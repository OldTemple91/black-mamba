package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.*;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * 모든수단 최적탐색 전략.
 *
 * 3가지 수단(따릉이/킥보드/개인) × 4가지 패턴(B,C,D,E) + 패턴A(대중교통만) = 최대 13개 후보 병렬 탐색.
 *
 * 패턴 B: 퍼스트마일  — 이동수단(출발→정류장) + 대중교통(정류장→목적지)
 * 패턴 C: 라스트마일  — 대중교통(출발→환승점) + 이동수단(환승점→목적지)
 * 패턴 D: 퍼스트+라스트 — 이동수단 + 대중교통(중간) + 이동수단
 * 패턴 E: 이동수단만  — haversine 거리 < 수단 최대범위일 때
 */
public class OptimalSearchStrategy implements RouteSearchStrategy {

    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final List<MobilityType> ALL_TYPES =
            List.of(MobilityType.DDAREUNGI, MobilityType.KICKBOARD_SHARED, MobilityType.PERSONAL);

    private final TransitRoutePort transitRoutePort;
    private final MobilityTimePort mobilityTimePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final CandidatePointSelector candidatePointSelector;
    private final RouteScoreCalculator scoreCalculator;

    public OptimalSearchStrategy(TransitRoutePort transitRoutePort,
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

    @Override
    public Mono<List<Route>> search(Location origin, Location destination) {
        return transitRoutePort.getTransitRoute(origin, destination)
                .flatMap(baseLegs -> {
                    Route baseRoute = Route.of(baseLegs, RouteType.TRANSIT_ONLY);
                    int baseMinutes = baseRoute.totalMinutes();

                    Flux<Route> allPatterns = Flux.fromIterable(ALL_TYPES)
                            .flatMap(type -> {
                                MobilityConfig config = configFor(type);
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
                            .map(candidates -> rank(candidates, baseMinutes));
                });
    }

    // 패턴 B: 이동수단으로 첫 정류장까지 → 대중교통으로 목적지
    private Flux<Route> patternB(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        List<Location> firstMile = candidatePointSelector.selectFirstMile(origin, baseLegs, config);
        return Flux.fromIterable(firstMile)
                .flatMap(transitStart ->
                        mobilityAvailabilityPort.findNearbyMobility(origin.lat(), origin.lng(), type)
                                .filter(Optional::isPresent)
                                .flatMap(avail -> {
                                    MobilityInfo info = avail.get();
                                    Mono<Integer> mobTime  = mobilityTimePort.getMobilityTimeMinutes(origin, transitStart, type);
                                    Mono<Integer> tranTime = transitRoutePort.getTransitTimeMinutes(transitStart, destination);
                                    return Mono.zip(mobTime, tranTime)
                                            .map(t -> buildRoute(
                                                    List.of(
                                                            mobilityLeg(type, t.getT1(), origin, transitStart, info),
                                                            transitLeg(t.getT2(), transitStart, destination)
                                                    ), RouteType.MOBILITY_FIRST_TRANSIT));
                                })
                );
    }

    // 패턴 C: 대중교통으로 환승점까지 → 이동수단으로 목적지 (기존 알고리즘과 동일)
    private Flux<Route> patternC(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        List<Location> lastMile = candidatePointSelector.select(baseLegs, config);
        return Flux.fromIterable(lastMile)
                .flatMap(switchPoint ->
                        mobilityAvailabilityPort.findNearbyMobility(switchPoint.lat(), switchPoint.lng(), type)
                                .filter(Optional::isPresent)
                                .flatMap(avail -> {
                                    MobilityInfo info = avail.get();
                                    Mono<Integer> tranTime = transitRoutePort.getTransitTimeMinutes(origin, switchPoint);
                                    Mono<Integer> mobTime  = mobilityTimePort.getMobilityTimeMinutes(switchPoint, destination, type);
                                    return Mono.zip(tranTime, mobTime)
                                            .map(t -> buildRoute(
                                                    List.of(
                                                            transitLeg(t.getT1(), origin, switchPoint),
                                                            mobilityLeg(type, t.getT2(), switchPoint, destination, info)
                                                    ), routeTypeFor(type)));
                                })
                );
    }

    // 패턴 D: 이동수단→정류장 + 대중교통(중간) + 이동수단→목적지
    private Flux<Route> patternD(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        List<Location> firstMile = candidatePointSelector.selectFirstMile(origin, baseLegs, config);
        List<Location> lastMile  = candidatePointSelector.select(baseLegs, config);
        if (firstMile.isEmpty() || lastMile.isEmpty()) return Flux.empty();

        Location transitStart = firstMile.get(0);
        Location transitEnd   = lastMile.get(lastMile.size() / 2);

        return mobilityAvailabilityPort.findNearbyMobility(origin.lat(), origin.lng(), type)
                .filter(Optional::isPresent)
                .flatMapMany(avail -> {
                    MobilityInfo info = avail.get();
                    Mono<Integer> mob1  = mobilityTimePort.getMobilityTimeMinutes(origin, transitStart, type);
                    Mono<Integer> tran  = transitRoutePort.getTransitTimeMinutes(transitStart, transitEnd);
                    Mono<Integer> mob2  = mobilityTimePort.getMobilityTimeMinutes(transitEnd, destination, type);
                    return Mono.zip(mob1, tran, mob2)
                            .map(t -> buildRoute(
                                    List.of(
                                            mobilityLeg(type, t.getT1(), origin, transitStart, info),
                                            transitLeg(t.getT2(), transitStart, transitEnd),
                                            mobilityLeg(type, t.getT3(), transitEnd, destination, info)
                                    ), RouteType.MOBILITY_TRANSIT_MOBILITY))
                            .flux();
                });
    }

    // 패턴 E: 이동수단만 (직선거리 < 수단 최대범위)
    private Flux<Route> patternE(Location origin, Location destination,
                                  MobilityType type, MobilityConfig config) {
        double dist = haversineMeters(origin.lat(), origin.lng(), destination.lat(), destination.lng());
        if (dist > config.maxRangeMeters()) return Flux.empty();

        return mobilityAvailabilityPort.findNearbyMobility(origin.lat(), origin.lng(), type)
                .filter(Optional::isPresent)
                .flatMapMany(avail -> {
                    MobilityInfo info = avail.get();
                    return mobilityTimePort.getMobilityTimeMinutes(origin, destination, type)
                            .map(t -> buildRoute(
                                    List.of(mobilityLeg(type, t, origin, destination, info)),
                                    RouteType.MOBILITY_ONLY))
                            .flux();
                });
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private Route buildRoute(List<Leg> legs, RouteType type) {
        return Route.of(legs, type);
    }

    private Leg transitLeg(int minutes, Location from, Location to) {
        return new Leg(LegType.TRANSIT, "대중교통", minutes, 0, from, to, null, null);
    }

    private Leg mobilityLeg(MobilityType type, int minutes, Location from, Location to, MobilityInfo info) {
        LegType legType = type == MobilityType.KICKBOARD_SHARED ? LegType.KICKBOARD : LegType.BIKE;
        return new Leg(legType, type.name(), minutes, 0, from, to, null, info);
    }

    private MobilityConfig configFor(MobilityType type) {
        return type == MobilityType.KICKBOARD_SHARED ? MobilityConfig.kickboard() : MobilityConfig.bike();
    }

    private RouteType routeTypeFor(MobilityType type) {
        return type == MobilityType.KICKBOARD_SHARED
                ? RouteType.TRANSIT_WITH_KICKBOARD : RouteType.TRANSIT_WITH_BIKE;
    }

    private List<Route> rank(List<Route> candidates, int baseMinutes) {
        List<Route> scored = candidates.stream()
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

    private double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng/2)*Math.sin(dLng/2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }
}
