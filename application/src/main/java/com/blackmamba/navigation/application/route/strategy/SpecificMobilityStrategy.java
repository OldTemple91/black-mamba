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
import java.util.stream.Collectors;

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
                                new Leg(LegType.TRANSIT, "대중교통", baseTime, 0, origin, destination, null, null, null)
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
                            .flatMap(candidate -> buildRoute(origin, candidate, destination, type, baseLegs));
                });
    }

    private Mono<Route> buildRoute(Location origin, Location switchPoint,
                                    Location destination, MobilityType type,
                                    List<Leg> baseLegs) {
        Mono<Integer>             transitTime  = transitRoutePort.getTransitTimeMinutes(origin, switchPoint);
        Mono<MobilityRouteResult> mobilityTime = mobilityTimePort.getMobilityRoute(switchPoint, destination, type);
        Mono<java.util.Optional<MobilityInfo>> avail =
                mobilityAvailabilityPort.findNearbyMobility(switchPoint.lat(), switchPoint.lng(), type);

        return Mono.zip(transitTime, mobilityTime, avail)
                .filter(t -> t.getT3().isPresent())
                .map(t -> {
                    MobilityInfo info = t.getT3().get();
                    MobilityRouteResult mobResult = t.getT2();
                    LegType legType = isKickboardType(type) ? LegType.KICKBOARD : LegType.BIKE;
                    RouteType routeType = isKickboardType(type)
                            ? RouteType.TRANSIT_WITH_KICKBOARD : RouteType.TRANSIT_WITH_BIKE;
                    List<Leg> legs = List.of(
                            buildTransitLeg(t.getT1(), origin, switchPoint, baseLegs),
                            new Leg(legType, type.name(), mobResult.durationMinutes(), 0, switchPoint, destination, null, info, mobResult.routeCoordinates())
                    );
                    return Route.of(legs, routeType);
                });
    }

    /** baseLegs에서 노선 정보를 추출해 TransitInfo가 채워진 Leg 생성 */
    private Leg buildTransitLeg(int minutes, Location from, Location to, List<Leg> baseLegs) {
        List<Leg> transitLegs = baseLegs.stream()
                .filter(l -> l.type() == LegType.TRANSIT && l.transitInfo() != null)
                .toList();
        if (transitLegs.isEmpty()) {
            return new Leg(LegType.TRANSIT, "대중교통", minutes, 0, from, to, null, null, null);
        }
        String lineName = transitLegs.stream()
                .map(l -> l.transitInfo().lineName())
                .filter(n -> n != null && !n.isBlank())
                .distinct().limit(3)
                .collect(Collectors.joining(", "));
        String lineColor = transitLegs.stream()
                .map(l -> l.transitInfo().lineColor())
                .filter(c -> c != null && !c.isBlank())
                .findFirst().orElse(null);
        int totalBaseMinutes = baseLegs.stream().mapToInt(Leg::durationMinutes).sum();
        int totalStations = transitLegs.stream().mapToInt(l -> l.transitInfo().stationCount()).sum();
        int approxStations = totalBaseMinutes > 0
                ? Math.max(2, (int) Math.round((double) minutes / totalBaseMinutes * totalStations))
                : Math.max(2, totalStations / 2);
        List<Location> passThroughStations = transitLegs.stream()
                .filter(l -> l.transitInfo().passThroughStations() != null)
                .flatMap(l -> l.transitInfo().passThroughStations().stream())
                .toList();
        TransitInfo transitInfo = lineName.isBlank() ? null
                : new TransitInfo(lineName, lineColor, approxStations, passThroughStations);
        return new Leg(LegType.TRANSIT, "대중교통", minutes, 0, from, to, transitInfo, null, null);
    }

    /** ODsay 없이 출발지→목적지 직접 이동수단 경로 */
    private Mono<Route> buildDirectRoute(Location origin, Location destination, MobilityType type) {
        Mono<MobilityRouteResult> mobilityTime = mobilityTimePort.getMobilityRoute(origin, destination, type);
        Mono<java.util.Optional<MobilityInfo>> avail =
                mobilityAvailabilityPort.findNearbyMobility(origin.lat(), origin.lng(), type);

        return Mono.zip(mobilityTime, avail)
                .filter(t -> t.getT2().isPresent())
                .map(t -> {
                    MobilityInfo info = t.getT2().get();
                    MobilityRouteResult result = t.getT1();
                    LegType legType = isKickboardType(type) ? LegType.KICKBOARD : LegType.BIKE;
                    List<Leg> legs = List.of(
                            new Leg(legType, type.name(), result.durationMinutes(), 0, origin, destination, null, info, result.routeCoordinates())
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
