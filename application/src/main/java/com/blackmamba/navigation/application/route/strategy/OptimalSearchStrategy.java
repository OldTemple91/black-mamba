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
import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.domain.route.evaluation.GenerationDiagnostic;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import com.blackmamba.navigation.application.route.strategy.CandidateRouteBuilder.SearchContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
    private final HubSelector hubSelector;
    private final CandidateRouteBuilder candidateRoutes;
    private final LastMileHubPrioritizer hubPrioritizer;
    private final MobilityDiagnostics diagnostics;
    private final RouteRanker ranker;

    public OptimalSearchStrategy(TransitRoutePort transitRoutePort,
                                  MobilityTimePort mobilityTimePort,
                                  MobilityAvailabilityPort mobilityAvailabilityPort,
                                  HubSelector hubSelector,
                                  RouteEvaluator routeEvaluator) {
        this.transitRoutePort = transitRoutePort;
        this.hubSelector = hubSelector;
        this.candidateRoutes = new CandidateRouteBuilder(transitRoutePort, mobilityAvailabilityPort, new MobilitySegmentBuilder(mobilityTimePort));
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

                    // 라스트마일 허브 우선순위화는 요청당·수단당 한 번만 — 패턴 C·D 와 진단이 같은 결과를 공유한다.
                    // (따릉이는 허브마다 최근접 정류소 조회가 따라붙어, 예전엔 같은 계산을 요청마다 2~3번 반복했다)
                    Map<MobilityType, Mono<List<Hub>>> lastMileHubsByType = new EnumMap<>(MobilityType.class);
                    for (MobilityType type : ALL_TYPES) {
                        lastMileHubsByType.put(type, hubPrioritizer.prioritize(baseLegs, destination, type, MobilityConfigs.forType(type)).cache());
                    }

                    Route baseRoute = baseline.toRoute();
                    int baseMinutes = baseline.minutes();
                    SearchContext context = new SearchContext(origin, destination, baseLegs);

                    Flux<Route> allPatterns = Flux.fromIterable(ALL_TYPES)
                            .flatMap(type -> {
                                MobilityConfig config = MobilityConfigs.forType(type);
                                return Flux.merge(
                                        patternB(context, type, config),
                                        patternC(context, type, lastMileHubsByType.get(type)),
                                        patternD(context, type, config, lastMileHubsByType.get(type)),
                                        patternE(context, type, config)
                                );
                            });

                    return allPatterns
                            .mergeWith(Mono.just(baseRoute))
                            .collectList()
                            .flatMap(candidates -> {
                                if (hasOnlyTransitCandidate(candidates)) {
                                    return buildNoMixedDiagnostics(origin, destination, baseLegs, lastMileHubsByType)
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
    private Flux<Route> patternB(SearchContext context, MobilityType type, MobilityConfig config) {
        return candidateRoutes.hasPickupNearOrigin(context.origin(), type)
                .flatMapMany(hasPickup -> {
                    if (!hasPickup) {
                        return Flux.empty();
                    }
                    List<Hub> firstMile = hubSelector.selectFirstMileHubs(context.origin(), context.baseLegs(), config).stream()
                            .limit(MAX_CANDIDATE_HUBS)
                            .toList();
                    return Flux.fromIterable(firstMile)
                            .flatMap(firstHub -> candidateRoutes.firstMileVia(context, type, firstHub));
                });
    }

    // 패턴 C: 대중교통으로 환승점까지 → 이동수단으로 목적지
    private Flux<Route> patternC(SearchContext context, MobilityType type, Mono<List<Hub>> lastMileHubs) {
        return lastMileHubs
                .flatMapMany(Flux::fromIterable)
                .flatMap(lastHub -> candidateRoutes.lastMileVia(context, type, lastHub));
    }

    // 패턴 D: 이동수단→정류장 + 대중교통(중간) + 이동수단→목적지
    private Flux<Route> patternD(SearchContext context, MobilityType type, MobilityConfig config, Mono<List<Hub>> lastMileHubs) {
        return candidateRoutes.hasPickupNearOrigin(context.origin(), type)
                .flatMapMany(hasPickup -> {
                    if (!hasPickup) {
                        return Flux.empty();
                    }
                    List<Hub> firstMile = hubSelector.selectFirstMileHubs(context.origin(), context.baseLegs(), config);
                    return lastMileHubs
                            .flatMapMany(lastMile -> {
                                if (firstMile.isEmpty() || lastMile.isEmpty()) return Flux.empty();
                                // 우선순위화된 목록은 "대여 가능성이 높은 순" — 가운데가 아니라 1순위를 쓴다
                                return candidateRoutes.bothEndsVia(context, type, firstMile.getFirst(), lastMile.getFirst());
                            });
                });
    }

    // 패턴 E: 이동수단만 (직선거리 < 수단 최대범위)
    private Flux<Route> patternE(SearchContext context, MobilityType type, MobilityConfig config) {
        return candidateRoutes.direct(context, type, config)
                .flux();
    }

    private boolean hasOnlyTransitCandidate(List<Route> candidates) {
        return candidates.size() == 1 && candidates.getFirst().type() == RouteType.TRANSIT_ONLY;
    }

    private Mono<List<GenerationDiagnostic>> buildNoMixedDiagnostics(Location origin, Location destination, List<Leg> baseLegs,
                                                                     Map<MobilityType, Mono<List<Hub>>> lastMileHubsByType) {
        return Flux.fromIterable(ALL_TYPES)
                .flatMap(type -> diagnosticsForType(origin, destination, baseLegs, type, lastMileHubsByType.get(type)))
                .distinct()
                .collectList()
                .map(list -> list.isEmpty() ? List.of(MobilityDiagnostics.noMixedRoute()) : list);
    }

    /** 진단은 생성 때 실제로 시도한 허브 집합(라스트마일은 우선순위화된 상위 N, 퍼스트마일은 선택 상위 N)에 대해 수행한다. */
    private Flux<GenerationDiagnostic> diagnosticsForType(Location origin, Location destination, List<Leg> baseLegs, MobilityType type,
                                                          Mono<List<Hub>> lastMileHubsMono) {
        MobilityConfig config = MobilityConfigs.forType(type);
        List<Hub> firstMileHubs = hubSelector.selectFirstMileHubs(origin, baseLegs, config).stream()
                .limit(MAX_CANDIDATE_HUBS)
                .toList();
        return lastMileHubsMono
                .flatMapMany(lastMileHubs -> diagnosticsForHubs(origin, destination, type, config, firstMileHubs, lastMileHubs));
    }

    private Flux<GenerationDiagnostic> diagnosticsForHubs(Location origin, Location destination, MobilityType type, MobilityConfig config,
                                                          List<Hub> firstMileHubs, List<Hub> lastMileHubs) {
        List<GenerationDiagnostic> immediateReasons = new ArrayList<>();
        if (firstMileHubs.isEmpty()) immediateReasons.add(MobilityDiagnostics.noCandidateHub(Phase.FIRST_MILE, type));
        if (lastMileHubs.isEmpty()) immediateReasons.add(MobilityDiagnostics.noCandidateHub(Phase.LAST_MILE, type));

        if (CandidateRouteBuilder.exceedsDirectRange(origin, destination, config)) {
            immediateReasons.add(MobilityDiagnostics.directDistanceExceeded(type, config.maxRangeMeters(), GeoDistance.meters(origin, destination)));
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
