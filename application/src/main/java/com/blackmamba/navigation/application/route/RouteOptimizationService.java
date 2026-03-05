package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 핵심 멀티모달 경로 탐색 알고리즘.
 *
 * 흐름:
 * 1. ODsay API로 기본 대중교통 경로 조회
 * 2. 중간 30~80% 구간 정류장을 후보 환승 지점으로 선택
 * 3. 후보 지점마다 (대중교통 + 이동수단) 조합 경로를 병렬로 계산
 * 4. 점수 기반 정렬 → 상위 5개 반환, 1위를 추천(recommended=true)으로 표시
 */
@Service
public class RouteOptimizationService {

    private final TransitRoutePort transitRoutePort;
    private final MobilityTimePort mobilityTimePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final CandidatePointSelector candidatePointSelector;
    private final RouteScoreCalculator scoreCalculator;

    public RouteOptimizationService(TransitRoutePort transitRoutePort,
                                     MobilityTimePort mobilityTimePort,
                                     MobilityAvailabilityPort mobilityAvailabilityPort,
                                     CandidatePointSelector candidatePointSelector,
                                     RouteScoreCalculator scoreCalculator) {
        this.transitRoutePort = transitRoutePort;
        this.mobilityTimePort = mobilityTimePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.candidatePointSelector = candidatePointSelector;
        this.scoreCalculator = scoreCalculator;
    }

    public Mono<List<Route>> findRoutes(Location origin, Location destination,
                                         List<MobilityType> availableMobility) {
        return transitRoutePort.getTransitRoute(origin, destination)
                .flatMap(baseLegs -> {
                    Route baseRoute = Route.of(baseLegs, RouteType.TRANSIT_ONLY);

                    // 이동수단 옵션이 없으면 기본 경로만 반환
                    if (availableMobility.isEmpty()) {
                        double score = scoreCalculator.calculate(baseRoute);
                        return Mono.just(List.of(baseRoute.withScore(score, true)));
                    }

                    return generateCombinedRoutes(baseLegs, origin, destination, availableMobility)
                            .collectList()
                            .map(combinedRoutes ->
                                    rankRoutes(baseRoute, combinedRoutes, baseRoute.totalMinutes()));
                });
    }

    private Flux<Route> generateCombinedRoutes(List<Leg> baseLegs, Location origin,
                                                Location destination,
                                                List<MobilityType> mobilityTypes) {
        return Flux.fromIterable(mobilityTypes)
                .flatMap(mobilityType -> {
                    MobilityConfig config = mobilityType == MobilityType.KICKBOARD_SHARED
                            ? MobilityConfig.kickboard() : MobilityConfig.bike();
                    List<Location> candidates = candidatePointSelector.select(baseLegs, config);

                    return Flux.fromIterable(candidates)
                            .flatMap(candidate -> buildCombinedRoute(
                                    origin, candidate, destination, mobilityType));
                });
    }

    private Mono<Route> buildCombinedRoute(Location origin, Location switchPoint,
                                            Location destination, MobilityType mobilityType) {
        Mono<Integer> transitTime = transitRoutePort.getTransitTimeMinutes(origin, switchPoint);
        Mono<Integer> mobilityTime = mobilityTimePort.getMobilityTimeMinutes(
                switchPoint, destination, mobilityType);
        Mono<java.util.Optional<MobilityInfo>> availability =
                mobilityAvailabilityPort.findNearbyMobility(
                        switchPoint.lat(), switchPoint.lng(), mobilityType);

        return Mono.zip(transitTime, mobilityTime, availability)
                .filter(tuple -> tuple.getT3().isPresent())
                .map(tuple -> {
                    int transitMin  = tuple.getT1();
                    int mobilityMin = tuple.getT2();
                    MobilityInfo mobilityInfo = tuple.getT3().get();

                    // 환승 지점까지의 대중교통 Leg (API에서 받은 실제 소요시간 반영)
                    Leg transitToSwitchLeg = new Leg(
                            LegType.TRANSIT, "BUS", transitMin, 0,
                            origin, switchPoint, null, null);

                    LegType legType = mobilityType == MobilityType.KICKBOARD_SHARED
                            ? LegType.KICKBOARD : LegType.BIKE;
                    Leg mobilityLeg = new Leg(
                            legType, mobilityType.name(), mobilityMin, 0,
                            switchPoint, destination, null, mobilityInfo);

                    RouteType routeType = mobilityType == MobilityType.KICKBOARD_SHARED
                            ? RouteType.TRANSIT_WITH_KICKBOARD : RouteType.TRANSIT_WITH_BIKE;

                    return Route.of(List.of(transitToSwitchLeg, mobilityLeg), routeType);
                });
    }

    private List<Route> rankRoutes(Route baseRoute, List<Route> combinedRoutes, int baseMinutes) {
        // 조합 경로를 먼저 스코어링 → 점수가 높으면 1위로 올라옴
        List<Route> all = new ArrayList<>();
        all.addAll(combinedRoutes);
        all.add(baseRoute);

        List<Route> scored = all.stream()
                .map(r -> r.withScore(scoreCalculator.calculate(r), false))
                .sorted(Comparator.comparingDouble(Route::score).reversed())
                .limit(5)
                .toList();

        // 1위 추천 표시 + 절약 시간 계산
        List<Route> result = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            Route r = scored.get(i);
            int saved = Math.max(baseMinutes - r.totalMinutes(), 0);
            Route withComparison = r.withComparison(new Comparison(baseMinutes, saved));
            result.add(i == 0 ? withComparison.withScore(withComparison.score(), true)
                              : withComparison);
        }
        return result;
    }
}
