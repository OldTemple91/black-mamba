package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.HubSelector;
import com.blackmamba.navigation.application.route.LastMileHubPrioritizer;
import com.blackmamba.navigation.application.route.MobilityConfig;
import com.blackmamba.navigation.application.route.MobilitySegmentBuilder;
import com.blackmamba.navigation.application.route.RecommendationPreference;
import com.blackmamba.navigation.application.route.RouteEvaluator;
import com.blackmamba.navigation.application.route.RouteHubExtractor;
import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.application.route.port.MobilityTimePort;
import com.blackmamba.navigation.application.route.port.TransitRoutePort;
import com.blackmamba.navigation.application.route.strategy.MobilityDiagnostics.Phase;
import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.GenerationDiagnostic;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.MobilityInfo;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteHub;
import com.blackmamba.navigation.domain.route.RouteType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 기본 MaaS 최적탐색 전략.
 *
 * 공유/공공 이동수단을 기준으로 4가지 패턴(B,C,D,E) + 패턴A(대중교통만)를 조합한다.
 * 개인 이동수단은 사용자 명시 선택 시 SPECIFIC 모드에서만 탐색한다.
 *
 * 패턴 B: 퍼스트마일  — 이동수단(출발→정류장) + 대중교통(정류장→목적지)
 * 패턴 C: 라스트마일  — 대중교통(출발→환승점) + 이동수단(환승점→목적지)
 * 패턴 D: 퍼스트+라스트 — 이동수단 + 대중교통(중간) + 이동수단
 * 패턴 E: 이동수단만  — haversine 거리 < 수단 최대범위일 때
 */
public class OptimalSearchStrategy implements RouteSearchStrategy {

    private static final Logger log = LoggerFactory.getLogger(OptimalSearchStrategy.class);
    private static final int MAX_CANDIDATE_HUBS = LastMileHubPrioritizer.MAX_CANDIDATE_HUBS;
    // KICKBOARD_SHARED 제외: TAGO API 서울 데이터 미제공 (B-1)
    // PERSONAL_EBIKE/PERSONAL_KICKBOARD 제외: 사용자 보유 전제, SPECIFIC 모드에서만 사용
    private static final List<MobilityType> ALL_TYPES =
            List.of(MobilityType.DDAREUNGI);

    private final TransitRoutePort transitRoutePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final HubSelector hubSelector;
    private final MobilitySegmentBuilder mobilitySegmentBuilder;
    private final LastMileHubPrioritizer hubPrioritizer;
    private final MobilityDiagnostics diagnostics;
    private final RouteRanker ranker;

    public OptimalSearchStrategy(TransitRoutePort transitRoutePort,
                                  MobilityTimePort mobilityTimePort,
                                  MobilityAvailabilityPort mobilityAvailabilityPort,
                                  HubSelector hubSelector,
                                  RouteEvaluator routeEvaluator) {
        this.transitRoutePort = transitRoutePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.hubSelector = hubSelector;
        this.mobilitySegmentBuilder = new MobilitySegmentBuilder(mobilityTimePort);
        this.hubPrioritizer = new LastMileHubPrioritizer(hubSelector, mobilityAvailabilityPort);
        this.diagnostics = new MobilityDiagnostics(mobilityAvailabilityPort);
        this.ranker = new RouteRanker(routeEvaluator);
    }

    /** OPTIMAL 모드는 이동수단을 자동 선택하므로 {@code mobilityTypes} 파라미터를 무시한다. */
    @Override
    public Mono<List<Route>> search(Location origin, Location destination,
                                    List<MobilityType> mobilityTypes,
                                    RecommendationPreference preference) {
        return transitRoutePort.getTransitRoute(origin, destination)
                .flatMap(baseLegs -> {
                    BaselineTransitRoute baseline = BaselineTransitRoute.from(baseLegs, origin, destination);
                    if (baseline.estimated()) {
                        log.warn("[OPTIMAL] baseLegs 비어있음 → haversine 추정 {}분 사용", baseline.minutes());
                    } else {
                        log.info("[OPTIMAL] baseLegs={}개 totalMin={}", baseLegs.size(), baseline.minutes());
                    }

                    // 후보 지점 진단
                    List<Hub> diagHubs = hubSelector.selectLastMileHubs(baseLegs, destination, MobilityConfig.bike());
                    log.info("[OPTIMAL] lastMile 허브={}개 (ddareungi 기준)", diagHubs.size());

                    Route baseRoute = baseline.toRoute();
                    int baseMinutes = baseline.minutes();

                    Flux<Route> allPatterns = Flux.fromIterable(ALL_TYPES)
                            .flatMap(type -> {
                                MobilityConfig config = MobilityConfigs.forType(type);
                                return Flux.merge(
                                        patternB(origin, destination, baseLegs, type, config),
                                        patternC(origin, destination, baseLegs, type, config),
                                        patternD(origin, destination, baseLegs, type, config),
                                        patternE(origin, destination, type, config)
                                );
                            });

                    return allPatterns
                            .mergeWith(Mono.just(baseRoute))
                            .collectList()
                            .flatMap(candidates -> {
                                if (hasOnlyTransitCandidate(candidates)) {
                                    return buildNoMixedDiagnostics(origin, destination, baseLegs)
                                            .map(reasons -> {
                                                Route enrichedBaseRoute = RouteRanker.withDiagnostics(baseRoute, reasons);
                                                return ranker.rank(List.of(enrichedBaseRoute), enrichedBaseRoute, baseMinutes, preference);
                                            });
                                }
                                return Mono.just(ranker.rank(candidates, baseRoute, baseMinutes, preference));
                            });
                });
    }

    // 패턴 B: 이동수단으로 첫 정류장까지 → 대중교통으로 목적지
    private Flux<Route> patternB(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        return hasPickupNearOrigin(origin, type)
                .flatMapMany(hasPickup -> {
                    if (!hasPickup) {
                        return Flux.empty();
                    }
                    List<Hub> firstMile = hubSelector.selectFirstMileHubs(origin, baseLegs, config).stream()
                            .limit(MAX_CANDIDATE_HUBS)
                            .toList();
                    return Flux.fromIterable(firstMile)
                            .flatMap(firstHub -> {
                                Location transitStart = firstHub.location();
                                return mobilityInfoForSegment(origin, transitStart, type)
                                            .flatMap(info -> {
                                                Mono<List<Leg>> transitLegs = transitRoutePort.getTransitRoute(transitStart, destination);
                                                Mono<Integer> transitTime = transitRoutePort.getTransitTimeMinutes(transitStart, destination);
                                                return Mono.zip(
                                                                mobilitySegmentBuilder.build(origin, transitStart, type, info),
                                                                transitLegs,
                                                                transitTime
                                                        )
                                                        .map(tuple -> {
                                                            List<Leg> partialTransit = tuple.getT2().isEmpty()
                                                                    ? List.of(TransitLegFactory.approximate(tuple.getT3(), transitStart, destination, baseLegs))
                                                                    : tuple.getT2();
                                                            List<Leg> legs = new ArrayList<>();
                                                            legs.addAll(tuple.getT1());
                                                            legs.addAll(partialTransit);
                                                            return buildRoute(legs, RouteType.MOBILITY_FIRST_TRANSIT,
                                                                    List.of(RouteHubExtractor.fromSelectedHub(firstHub, "FIRST_MILE_CANDIDATE")));
                                                        });
                                            });
                            });
                });
    }

    // 패턴 C: 대중교통으로 환승점까지 → 이동수단으로 목적지
    private Flux<Route> patternC(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        return hubPrioritizer.prioritize(baseLegs, destination, type, config)
                .flatMapMany(lastMile -> Flux.fromIterable(lastMile)
                .flatMap(lastHub -> {
                    Location switchPoint = lastHub.location();
                    return mobilityInfoForSegment(switchPoint, destination, type)
                                .flatMap(info -> {
                                    Mono<List<Leg>> transitLegs = transitRoutePort.getTransitRoute(origin, switchPoint);
                                    Mono<Integer> transitTime = transitRoutePort.getTransitTimeMinutes(origin, switchPoint);
                                    return Mono.zip(
                                                    transitLegs,
                                                    transitTime,
                                                    mobilitySegmentBuilder.build(switchPoint, destination, type, info)
                                            )
                                            .map(tuple -> {
                                                List<Leg> partialTransit = tuple.getT1().isEmpty()
                                                        ? List.of(TransitLegFactory.approximate(tuple.getT2(), origin, switchPoint, baseLegs))
                                                        : tuple.getT1();
                                                List<Leg> legs = new ArrayList<>(partialTransit);
                                                legs.addAll(tuple.getT3());
                                                return buildRoute(legs, MobilityConfigs.routeTypeFor(type),
                                                        List.of(RouteHubExtractor.fromSelectedHub(lastHub, "LAST_MILE_CANDIDATE")));
                                            });
                                });
                }));
    }

    // 패턴 D: 이동수단→정류장 + 대중교통(중간) + 이동수단→목적지
    private Flux<Route> patternD(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        return hasPickupNearOrigin(origin, type)
                .flatMapMany(hasPickup -> {
                    if (!hasPickup) {
                        return Flux.empty();
                    }
                    List<Hub> firstMile = hubSelector.selectFirstMileHubs(origin, baseLegs, config);
                    return hubPrioritizer.prioritize(baseLegs, destination, type, config)
                            .flatMapMany(lastMile -> {
                                if (firstMile.isEmpty() || lastMile.isEmpty()) return Flux.empty();

                                Hub startHub = firstMile.get(0);
                                Hub endHub = lastMile.get(lastMile.size() / 2);
                                Location transitStart = startHub.location();
                                Location transitEnd   = endHub.location();

                                Mono<MobilityInfo> startInfo = mobilityInfoForSegment(origin, transitStart, type);
                                Mono<MobilityInfo> endInfo   = mobilityInfoForSegment(transitEnd, destination, type);

                                // 어느 한쪽이 empty 면 zip 전체가 empty → 해당 패턴 후보 스킵 (의도된 의미)
                                return Mono.zip(startInfo, endInfo)
                                        .flatMapMany(tuple -> {
                                            MobilityInfo startMobility = tuple.getT1();
                                            MobilityInfo endMobility   = tuple.getT2();
                                            Mono<List<Leg>> startSegment = mobilitySegmentBuilder.build(origin, transitStart, type, startMobility);
                                            Mono<List<Leg>>           tranLegs = transitRoutePort.getTransitRoute(transitStart, transitEnd);
                                            Mono<Integer>             tranTime = transitRoutePort.getTransitTimeMinutes(transitStart, transitEnd);
                                            Mono<List<Leg>> endSegment = mobilitySegmentBuilder.build(transitEnd, destination, type, endMobility);
                                            return Mono.zip(startSegment, tranLegs, tranTime, endSegment)
                                                    .map(t -> {
                                                        List<Leg> middleTransit = t.getT2().isEmpty()
                                                                ? List.of(TransitLegFactory.approximate(t.getT3(), transitStart, transitEnd, baseLegs))
                                                                : t.getT2();
                                                        List<Leg> legs = new ArrayList<>();
                                                        legs.addAll(t.getT1());
                                                        legs.addAll(middleTransit);
                                                        legs.addAll(t.getT4());
                                                        return buildRoute(legs, RouteType.MOBILITY_TRANSIT_MOBILITY,
                                                                List.of(
                                                                        RouteHubExtractor.fromSelectedHub(startHub, "FIRST_MILE_CANDIDATE"),
                                                                        RouteHubExtractor.fromSelectedHub(endHub, "LAST_MILE_CANDIDATE")
                                                                ));
                                                    })
                                                    .flux();
                                        });
                            });
                });
    }

    // 패턴 E: 이동수단만 (직선거리 < 수단 최대범위)
    private Flux<Route> patternE(Location origin, Location destination,
                                  MobilityType type, MobilityConfig config) {
        double dist = GeoDistance.meters(origin, destination);
        if (dist > config.maxRangeMeters()) return Flux.empty();

        return mobilityInfoForSegment(origin, destination, type)
                .flatMapMany(info -> mobilitySegmentBuilder.build(origin, destination, type, info)
                        .map(legs -> buildRoute(legs, RouteType.MOBILITY_ONLY, List.of()))
                        .flux());
    }

    private Mono<MobilityInfo> mobilityInfoForSegment(Location start, Location end, MobilityType type) {
        return mobilityAvailabilityPort.findSegmentMobility(start.lat(), start.lng(), end.lat(), end.lng(), type);
    }

    private Mono<Boolean> hasPickupNearOrigin(Location origin, MobilityType type) {
        if (MobilityConfigs.isPersonal(type)) {
            return Mono.just(true);
        }
        return mobilityAvailabilityPort.findNearbyMobility(origin.lat(), origin.lng(), type)
                .map(Optional::isPresent);
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private Route buildRoute(List<Leg> legs, RouteType type, List<RouteHub> selectedHubs) {
        return Route.of(legs, type).withSelectedHubs(selectedHubs);
    }

    private boolean hasOnlyTransitCandidate(List<Route> candidates) {
        return candidates.size() == 1 && candidates.getFirst().type() == RouteType.TRANSIT_ONLY;
    }

    private Mono<List<GenerationDiagnostic>> buildNoMixedDiagnostics(Location origin, Location destination, List<Leg> baseLegs) {
        return Flux.fromIterable(ALL_TYPES)
                .flatMap(type -> diagnosticsForType(origin, destination, baseLegs, type))
                .distinct()
                .collectList()
                .map(list -> list.isEmpty() ? List.of(MobilityDiagnostics.noMixedRoute()) : list);
    }

    private Flux<GenerationDiagnostic> diagnosticsForType(Location origin, Location destination, List<Leg> baseLegs, MobilityType type) {
        MobilityConfig config = MobilityConfigs.forType(type);
        List<Hub> lastMileHubs = hubSelector.selectLastMileHubs(baseLegs, destination, config).stream()
                .limit(MAX_CANDIDATE_HUBS)
                .toList();
        List<Hub> firstMileHubs = hubSelector.selectFirstMileHubs(origin, baseLegs, config).stream()
                .limit(MAX_CANDIDATE_HUBS)
                .toList();

        List<GenerationDiagnostic> immediateReasons = new ArrayList<>();
        if (firstMileHubs.isEmpty()) immediateReasons.add(MobilityDiagnostics.noCandidateHub(Phase.FIRST_MILE, type));
        if (lastMileHubs.isEmpty()) immediateReasons.add(MobilityDiagnostics.noCandidateHub(Phase.LAST_MILE, type));

        double directDistance = GeoDistance.meters(origin, destination);
        if (directDistance > config.maxRangeMeters()) {
            immediateReasons.add(MobilityDiagnostics.directDistanceExceeded(type, config.maxRangeMeters(), directDistance));
        }

        return Mono.zip(
                        diagnostics.diagnosePhase(Phase.FIRST_MILE, type, firstMileHubs, origin, destination),
                        diagnostics.diagnosePhase(Phase.LAST_MILE, type, lastMileHubs, origin, destination))
                .flatMapMany(tuple -> {
                    List<GenerationDiagnostic> reasons = new ArrayList<>(immediateReasons);
                    tuple.getT1().ifPresent(reasons::add);
                    tuple.getT2().ifPresent(reasons::add);
                    return Flux.fromIterable(reasons);
                });
    }
}
