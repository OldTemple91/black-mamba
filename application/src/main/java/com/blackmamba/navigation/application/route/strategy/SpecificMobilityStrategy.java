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
import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.evaluation.GenerationDiagnostic;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import com.blackmamba.navigation.application.route.strategy.CandidateRouteBuilder.SearchContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 사용자가 선택한 이동수단으로 라스트마일(패턴 C)만 탐색하는 전략.
 * 후보 허브가 없으면 이동수단만으로 가는 경로(패턴 E)로 대체한다.
 * 후보 하나를 조립하는 방법은 OPTIMAL 전략과 같은 {@link CandidateRouteBuilder} 를 쓴다.
 */
public class SpecificMobilityStrategy implements RouteSearchStrategy {

    private static final int MAX_CANDIDATE_HUBS = LastMileHubPrioritizer.MAX_CANDIDATE_HUBS;

    private final TransitRoutePort transitRoutePort;
    private final RouteEvaluator routeEvaluator;
    private final CandidateRouteBuilder candidateRoutes;
    private final LastMileHubPrioritizer hubPrioritizer;
    private final MobilityDiagnostics diagnostics;
    private final RouteRanker ranker;

    public SpecificMobilityStrategy(TransitRoutePort transitRoutePort,
                                     MobilityTimePort mobilityTimePort,
                                     MobilityAvailabilityPort mobilityAvailabilityPort,
                                     HubSelector hubSelector,
                                     RouteEvaluator routeEvaluator) {
        this.transitRoutePort = transitRoutePort;
        this.routeEvaluator = routeEvaluator;
        this.candidateRoutes = new CandidateRouteBuilder(transitRoutePort, mobilityAvailabilityPort, new MobilitySegmentBuilder(mobilityTimePort));
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
                    return generateCombinedRoutes(new SearchContext(origin, destination, baseLegs), mobilityTypes)
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

    /** 선택한 수단별로 라스트마일(패턴 C) 후보를 만든다. 후보 허브가 없으면 이동수단만으로 가는 경로(패턴 E)로 대체한다. */
    private Flux<Route> generateCombinedRoutes(SearchContext context, List<MobilityType> mobilityTypes) {
        return Flux.fromIterable(mobilityTypes)
                .flatMap(type -> {
                    MobilityConfig config = MobilityConfigs.forType(type);
                    return hubPrioritizer.prioritize(context.baseLegs(), context.destination(), type, config)
                            .flatMapMany(candidateHubs -> {
                                if (candidateHubs.isEmpty()) {
                                    return candidateRoutes.direct(context, type, config).flux();
                                }
                                return Flux.fromIterable(candidateHubs)
                                        .flatMap(candidateHub -> candidateRoutes.lastMileVia(context, type, candidateHub));
                            });
                });
    }

    private Mono<List<GenerationDiagnostic>> buildNoMixedDiagnostics(List<Leg> baseLegs, Location origin, Location destination,
                                                                     List<MobilityType> mobilityTypes) {
        return Flux.fromIterable(mobilityTypes)
                .flatMap(type -> diagnosticsForType(baseLegs, origin, destination, type))
                .distinct()
                .collectList()
                .map(list -> list.isEmpty() ? List.of(MobilityDiagnostics.noMixedRoute()) : list);
    }

    /** 진단은 생성 때 실제로 시도한 허브 집합(우선순위화된 상위 N)에 대해 수행한다. */
    private Flux<GenerationDiagnostic> diagnosticsForType(List<Leg> baseLegs, Location origin, Location destination, MobilityType type) {
        MobilityConfig config = MobilityConfigs.forType(type);
        return hubPrioritizer.prioritize(baseLegs, destination, type, config)
                .flatMapMany(candidateHubs -> {
                    if (candidateHubs.isEmpty()) {
                        List<GenerationDiagnostic> reasons = new ArrayList<>();
                        reasons.add(MobilityDiagnostics.noCandidateHub(Phase.LAST_MILE, type));
                        if (CandidateRouteBuilder.exceedsDirectRange(origin, destination, config)) {
                            reasons.add(MobilityDiagnostics.directDistanceExceeded(type, config.maxRangeMeters(), GeoDistance.meters(origin, destination)));
                        }
                        return Flux.fromIterable(reasons);
                    }
                    return diagnostics.diagnosePhase(Phase.LAST_MILE, type, candidateHubs, origin, destination)
                            .flatMapMany(reason -> Mono.justOrEmpty(reason).flux());
                });
    }
}
