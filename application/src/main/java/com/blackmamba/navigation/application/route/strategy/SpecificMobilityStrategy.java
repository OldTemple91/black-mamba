package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.*;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 사용자가 선택한 이동수단으로 라스트마일(패턴 C)만 탐색하는 전략.
 * ODsay 결과가 없을 경우 직접 이동수단 경로로 폴백.
 */
public class SpecificMobilityStrategy implements RouteSearchStrategy {

    private final List<MobilityType> mobilityTypes;
    private final TransitRoutePort transitRoutePort;
    private final MobilityTimePort mobilityTimePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final CandidatePointSelector candidatePointSelector;
    private final RouteScoreCalculator scoreCalculator;

    public SpecificMobilityStrategy(List<MobilityType> mobilityTypes,
                                     TransitRoutePort transitRoutePort,
                                     MobilityTimePort mobilityTimePort,
                                     MobilityAvailabilityPort mobilityAvailabilityPort,
                                     CandidatePointSelector candidatePointSelector,
                                     RouteScoreCalculator scoreCalculator) {
        this.mobilityTypes = mobilityTypes;
        this.transitRoutePort = transitRoutePort;
        this.mobilityTimePort = mobilityTimePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.candidatePointSelector = candidatePointSelector;
        this.scoreCalculator = scoreCalculator;
    }

    @Override
    public Mono<List<Route>> search(Location origin, Location destination) {
        return transitRoutePort.getTransitRoute(origin, destination)
                .flatMap(baseLegs -> {
                    // ODsay 결과가 없으면 haversine 추정값으로 기준 경로 생성
                    final int baseTime;
                    final List<Leg> routeLegs;
                    if (baseLegs.isEmpty()) {
                        baseTime = haversineTransitMinutes(origin, destination);
                        routeLegs = List.of(
                                new Leg(LegType.TRANSIT, "대중교통", baseTime, 0, origin, destination, null, null)
                        );
                    } else {
                        baseTime = baseLegs.stream().mapToInt(Leg::durationMinutes).sum();
                        routeLegs = baseLegs;
                    }

                    Route baseRoute = Route.of(routeLegs, RouteType.TRANSIT_ONLY);

                    if (mobilityTypes.isEmpty()) {
                        return Mono.just(List.of(baseRoute.withScore(scoreCalculator.calculate(baseRoute), true)));
                    }
                    return generateCombinedRoutes(baseLegs, origin, destination)
                            .collectList()
                            .map(combined -> rank(baseRoute, combined, baseTime));
                });
    }

    private Flux<Route> generateCombinedRoutes(List<Leg> baseLegs, Location origin, Location destination) {
        return Flux.fromIterable(mobilityTypes)
                .flatMap(type -> {
                    MobilityConfig config = isKickboardType(type)
                            ? MobilityConfig.kickboard() : MobilityConfig.bike();
                    List<Location> candidates = candidatePointSelector.select(baseLegs, config);

                    if (candidates.isEmpty()) {
                        // 대중교통 경로 없음 → 출발지에서 목적지 직접 이동수단 경로로 폴백
                        return buildDirectRoute(origin, destination, type).flux();
                    }
                    return Flux.fromIterable(candidates)
                            .flatMap(candidate -> buildRoute(origin, candidate, destination, type));
                });
    }

    private Mono<Route> buildRoute(Location origin, Location switchPoint,
                                    Location destination, MobilityType type) {
        Mono<Integer> transitTime  = transitRoutePort.getTransitTimeMinutes(origin, switchPoint);
        Mono<Integer> mobilityTime = mobilityTimePort.getMobilityTimeMinutes(switchPoint, destination, type);
        Mono<java.util.Optional<MobilityInfo>> avail =
                mobilityAvailabilityPort.findNearbyMobility(switchPoint.lat(), switchPoint.lng(), type);

        return Mono.zip(transitTime, mobilityTime, avail)
                .filter(t -> t.getT3().isPresent())
                .map(t -> {
                    MobilityInfo info = t.getT3().get();
                    LegType legType = isKickboardType(type) ? LegType.KICKBOARD : LegType.BIKE;
                    RouteType routeType = isKickboardType(type)
                            ? RouteType.TRANSIT_WITH_KICKBOARD : RouteType.TRANSIT_WITH_BIKE;
                    List<Leg> legs = List.of(
                            new Leg(LegType.TRANSIT, "BUS", t.getT1(), 0, origin, switchPoint, null, null),
                            new Leg(legType, type.name(), t.getT2(), 0, switchPoint, destination, null, info)
                    );
                    return Route.of(legs, routeType);
                });
    }

    /** ODsay 없이 출발지→목적지 직접 이동수단 경로 */
    private Mono<Route> buildDirectRoute(Location origin, Location destination, MobilityType type) {
        Mono<Integer> mobilityTime = mobilityTimePort.getMobilityTimeMinutes(origin, destination, type);
        Mono<java.util.Optional<MobilityInfo>> avail =
                mobilityAvailabilityPort.findNearbyMobility(origin.lat(), origin.lng(), type);

        return Mono.zip(mobilityTime, avail)
                .filter(t -> t.getT2().isPresent())
                .map(t -> {
                    MobilityInfo info = t.getT2().get();
                    LegType legType = isKickboardType(type) ? LegType.KICKBOARD : LegType.BIKE;
                    List<Leg> legs = List.of(
                            new Leg(legType, type.name(), t.getT1(), 0, origin, destination, null, info)
                    );
                    return Route.of(legs, RouteType.MOBILITY_ONLY);
                });
    }

    /** KICKBOARD_SHARED 및 PERSONAL 모두 킥보드 타입으로 처리 */
    private static boolean isKickboardType(MobilityType type) {
        return type == MobilityType.KICKBOARD_SHARED || type == MobilityType.PERSONAL;
    }

    private List<Route> rank(Route base, List<Route> combined, int baseMinutes) {
        List<Route> all = new ArrayList<>(combined);
        all.add(base);
        List<Route> scored = all.stream()
                .map(r -> r.withScore(scoreCalculator.calculate(r), false))
                .sorted(Comparator.comparingDouble(Route::score).reversed())
                .limit(5).toList();
        List<Route> result = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            int saved = Math.max(baseMinutes - scored.get(i).totalMinutes(), 0);
            Route r = scored.get(i).withComparison(new Comparison(baseMinutes, saved));
            result.add(i == 0 ? r.withScore(r.score(), true) : r);
        }
        return result;
    }

    /** 대중교통 소요 시간 추정 (직선거리 × 1.4 우회계수 ÷ 25 km/h) */
    private static int haversineTransitMinutes(Location a, Location b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double distKm = 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
        return Math.max((int) Math.ceil(distKm * 1.4 / 25.0 * 60), 5);
    }
}
