package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.RouteEvaluator;
import com.blackmamba.navigation.application.route.hub.HubSelector;
import com.blackmamba.navigation.application.route.hub.LastMileHubPrioritizer;
import com.blackmamba.navigation.application.route.hub.MobilityConfig;
import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.application.route.port.MobilityTimePort;
import com.blackmamba.navigation.application.route.port.TransitRoutePort;
import com.blackmamba.navigation.application.route.segment.MobilitySegmentBuilder;
import com.blackmamba.navigation.application.route.strategy.MobilityDiagnostics.Phase;
import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.domain.route.evaluation.GenerationDiagnostic;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.route.evaluation.RouteHubExtractor;
import com.blackmamba.navigation.domain.route.mobility.MobilityInfo;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 사용자가 선택한 이동수단으로 라스트마일(패턴 C)만 탐색하는 전략.
 * 대중교통 결과가 없을 경우 직접 이동수단 경로로 폴백.
 */
public class SpecificMobilityStrategy implements RouteSearchStrategy {

    private static final int MAX_CANDIDATE_HUBS = LastMileHubPrioritizer.MAX_CANDIDATE_HUBS;

    private final TransitRoutePort transitRoutePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final HubSelector hubSelector;
    private final RouteEvaluator routeEvaluator;
    private final MobilitySegmentBuilder mobilitySegmentBuilder;
    private final LastMileHubPrioritizer hubPrioritizer;
    private final MobilityDiagnostics diagnostics;
    private final RouteRanker ranker;

    public SpecificMobilityStrategy(TransitRoutePort transitRoutePort,
                                     MobilityTimePort mobilityTimePort,
                                     MobilityAvailabilityPort mobilityAvailabilityPort,
                                     HubSelector hubSelector,
                                     RouteEvaluator routeEvaluator) {
        this.transitRoutePort = transitRoutePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.hubSelector = hubSelector;
        this.routeEvaluator = routeEvaluator;
        this.mobilitySegmentBuilder = new MobilitySegmentBuilder(mobilityTimePort);
        this.hubPrioritizer = new LastMileHubPrioritizer(hubSelector, mobilityAvailabilityPort);
        this.diagnostics = new MobilityDiagnostics(mobilityAvailabilityPort);
        this.ranker = new RouteRanker(routeEvaluator);
    }

    @Override
    public Mono<List<Route>> search(Location origin, Location destination,
                                    List<MobilityType> mobilityTypes,
                                    RecommendationPreference preference) {
        return transitRoutePort.getTransitRoute(origin, destination)
                .flatMap(baseLegs -> {
                    BaselineTransitRoute baseline = BaselineTransitRoute.from(baseLegs, origin, destination);
                    Route baseRoute = baseline.toRoute();
                    int baseMinutes = baseline.minutes();

                    if (mobilityTypes.isEmpty()) {
                        return Mono.just(List.of(routeEvaluator.evaluate(baseRoute, true, preference)));
                    }
                    return generateCombinedRoutes(baseLegs, origin, destination, mobilityTypes)
                            .collectList()
                            .flatMap(combined -> {
                                if (combined.isEmpty()) {
                                    return buildNoMixedDiagnostics(baseLegs, origin, destination, mobilityTypes)
                                            .map(reasons -> {
                                                Route enrichedBaseRoute = RouteRanker.withDiagnostics(baseRoute, reasons);
                                                return ranker.rank(List.of(enrichedBaseRoute), enrichedBaseRoute, baseMinutes, preference);
                                            });
                                }
                                List<Route> candidates = new ArrayList<>(combined);
                                candidates.add(baseRoute);
                                return Mono.just(ranker.rank(candidates, baseRoute, baseMinutes, preference));
                            });
                });
    }

    private Flux<Route> generateCombinedRoutes(List<Leg> baseLegs, Location origin, Location destination,
                                               List<MobilityType> mobilityTypes) {
        return Flux.fromIterable(mobilityTypes)
                .flatMap(type -> {
                    MobilityConfig config = MobilityConfigs.forType(type);
                    return hubPrioritizer.prioritize(baseLegs, destination, type, config)
                            .flatMapMany(candidateHubs -> {
                                if (candidateHubs.isEmpty()) {
                                    return buildDirectRoute(origin, destination, type).flux();
                                }
                                return Flux.fromIterable(candidateHubs)
                                        .flatMap(candidateHub -> buildRoute(origin, candidateHub, destination, type, baseLegs));
                            });
                });
    }

    private Mono<Route> buildRoute(Location origin, Hub candidateHub,
                                    Location destination, MobilityType type,
                                    List<Leg> baseLegs) {
        Location switchPoint = candidateHub.location();
        Mono<List<Leg>>           transitLegs  = transitRoutePort.getTransitRoute(origin, switchPoint);
        Mono<Integer>             transitTime  = transitRoutePort.getTransitTimeMinutes(origin, switchPoint);
        Mono<MobilityInfo> avail = mobilityInfoForSegment(switchPoint, destination, type);

        // avail 이 empty 면 zip 전체가 empty → 이 후보 스킵 (의도된 의미)
        return Mono.zip(transitLegs, transitTime, avail)
                .flatMap(tuple -> mobilitySegmentBuilder.build(switchPoint, destination, type, tuple.getT3())
                .map(mobilityLegs -> {
                    List<Leg> partialTransit = tuple.getT1().isEmpty()
                            ? List.of(TransitLegFactory.approximate(tuple.getT2(), origin, switchPoint, baseLegs))
                            : tuple.getT1();
                    List<Leg> legs = new ArrayList<>(partialTransit);
                    legs.addAll(mobilityLegs);
                    return Route.of(legs, MobilityConfigs.routeTypeFor(type))
                            .withSelectedHubs(List.of(RouteHubExtractor.fromSelectedHub(candidateHub, "LAST_MILE_CANDIDATE")));
                }));
    }

    /** 대중교통 없이 출발지→목적지 직접 이동수단 경로 */
    private Mono<Route> buildDirectRoute(Location origin, Location destination, MobilityType type) {
        return mobilityInfoForSegment(origin, destination, type)
                .flatMap(info -> mobilitySegmentBuilder.build(origin, destination, type, info)
                        .map(legs -> Route.of(legs, RouteType.MOBILITY_ONLY)));
    }

    private Mono<MobilityInfo> mobilityInfoForSegment(Location start, Location end, MobilityType type) {
        return mobilityAvailabilityPort.findSegmentMobility(start.lat(), start.lng(), end.lat(), end.lng(), type);
    }

    private Mono<List<GenerationDiagnostic>> buildNoMixedDiagnostics(List<Leg> baseLegs, Location origin, Location destination,
                                                                     List<MobilityType> mobilityTypes) {
        return Flux.fromIterable(mobilityTypes)
                .flatMap(type -> diagnosticsForType(baseLegs, origin, destination, type))
                .distinct()
                .collectList()
                .map(list -> list.isEmpty() ? List.of(MobilityDiagnostics.noMixedRoute()) : list);
    }

    private Flux<GenerationDiagnostic> diagnosticsForType(List<Leg> baseLegs, Location origin, Location destination, MobilityType type) {
        MobilityConfig config = MobilityConfigs.forType(type);
        List<Hub> candidateHubs = hubSelector.selectLastMileHubs(baseLegs, destination, config).stream()
                .limit(MAX_CANDIDATE_HUBS)
                .toList();

        if (candidateHubs.isEmpty()) {
            return Flux.just(MobilityDiagnostics.noCandidateHub(Phase.LAST_MILE, type));
        }
        return diagnostics.diagnosePhase(Phase.LAST_MILE, type, candidateHubs, origin, destination)
                .flatMapMany(reason -> Mono.justOrEmpty(reason).flux());
    }
}
