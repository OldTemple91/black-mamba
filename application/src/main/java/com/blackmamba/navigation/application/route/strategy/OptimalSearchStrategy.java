package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.*;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;
import java.util.Optional;

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

    private static final Logger log = LoggerFactory.getLogger(OptimalSearchStrategy.class);
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final List<MobilityType> ALL_TYPES =
            List.of(MobilityType.DDAREUNGI, MobilityType.KICKBOARD_SHARED);

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
                    final List<Leg> baseRouteLegs;
                    final int baseMinutes;
                    if (baseLegs.isEmpty()) {
                        baseMinutes = haversineTransitMinutes(origin, destination);
                        baseRouteLegs = List.of(
                                new Leg(LegType.TRANSIT, "대중교통", baseMinutes, 0, origin, destination, null, null, null)
                        );
                        log.warn("[OPTIMAL] baseLegs 비어있음 → haversine 추정 {}분 사용", baseMinutes);
                    } else {
                        baseMinutes = baseLegs.stream().mapToInt(Leg::durationMinutes).sum();
                        baseRouteLegs = baseLegs;
                        log.info("[OPTIMAL] baseLegs={}개 totalMin={}", baseLegs.size(), baseMinutes);
                    }

                    // 후보 지점 진단
                    MobilityConfig diagConfig = MobilityConfig.kickboard();
                    List<Location> diagCandidates = candidatePointSelector.select(baseLegs, diagConfig);
                    log.info("[OPTIMAL] lastMile 후보={}개 (kickboard 기준)", diagCandidates.size());

                    Route baseRoute = Route.of(baseRouteLegs, RouteType.TRANSIT_ONLY);

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
        List<Location> firstMile = candidatePointSelector.selectFirstMile(origin, baseLegs, config)
                .stream().limit(3).toList();
        int baseMinutes = baseLegs.stream().mapToInt(Leg::durationMinutes).sum();
        return Flux.fromIterable(firstMile)
                .flatMap(transitStart ->
                        mobilityAvailabilityPort.findNearbyMobility(origin.lat(), origin.lng(), type)
                                .filter(Optional::isPresent)
                                .flatMap(avail -> {
                                    MobilityInfo info = avail.get();
                                    // transitStart → destination: 전체 기준 나머지 구간 비율로 추정 (ODsay 추가 호출 없음)
                                    int tranTime = proportionalTransitMinutes(transitStart, destination, origin, destination, baseMinutes);
                                    return mobilityTimePort.getMobilityRoute(origin, transitStart, type)
                                            .map(result -> buildRoute(
                                                    List.of(
                                                            mobilityLeg(type, result, origin, transitStart, info),
                                                            transitLeg(tranTime, transitStart, destination, baseLegs)
                                                    ), RouteType.MOBILITY_FIRST_TRANSIT));
                                })
                );
    }

    // 패턴 C: 대중교통으로 환승점까지 → 이동수단으로 목적지
    private Flux<Route> patternC(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        List<Location> lastMile = candidatePointSelector.select(baseLegs, config)
                .stream().limit(3).toList();
        int baseMinutes = baseLegs.stream().mapToInt(Leg::durationMinutes).sum();
        return Flux.fromIterable(lastMile)
                .flatMap(switchPoint ->
                        mobilityAvailabilityPort.findNearbyMobility(switchPoint.lat(), switchPoint.lng(), type)
                                .filter(Optional::isPresent)
                                .flatMap(avail -> {
                                    MobilityInfo info = avail.get();
                                    // origin → switchPoint: 전체 기준 비율로 추정 (ODsay 추가 호출 없음)
                                    int tranTime = proportionalTransitMinutes(origin, switchPoint, origin, destination, baseMinutes);
                                    return mobilityTimePort.getMobilityRoute(switchPoint, destination, type)
                                            .map(result -> buildRoute(
                                                    List.of(
                                                            transitLeg(tranTime, origin, switchPoint, baseLegs),
                                                            mobilityLeg(type, result, switchPoint, destination, info)
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
                    Mono<MobilityRouteResult> mob1  = mobilityTimePort.getMobilityRoute(origin, transitStart, type);
                    Mono<Integer>             tran  = transitRoutePort.getTransitTimeMinutes(transitStart, transitEnd);
                    Mono<MobilityRouteResult> mob2  = mobilityTimePort.getMobilityRoute(transitEnd, destination, type);
                    return Mono.zip(mob1, tran, mob2)
                            .map(t -> buildRoute(
                                    List.of(
                                            mobilityLeg(type, t.getT1(), origin, transitStart, info),
                                            transitLeg(t.getT2(), transitStart, transitEnd, baseLegs),
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
                    return mobilityTimePort.getMobilityRoute(origin, destination, type)
                            .map(result -> buildRoute(
                                    List.of(mobilityLeg(type, result, origin, destination, info)),
                                    RouteType.MOBILITY_ONLY))
                            .flux();
                });
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private Route buildRoute(List<Leg> legs, RouteType type) {
        return Route.of(legs, type);
    }

    /**
     * 혼합 경로의 대중교통 구간 Leg 생성.
     * baseLegs에서 노선명·색상을 추출해 TransitInfo를 채운다.
     * baseLegs가 비어있거나 TRANSIT 정보가 없으면 transitInfo=null.
     */
    private Leg transitLeg(int minutes, Location from, Location to, List<Leg> baseLegs) {
        List<Leg> transitLegs = baseLegs.stream()
                .filter(l -> l.type() == LegType.TRANSIT && l.transitInfo() != null)
                .toList();

        if (transitLegs.isEmpty()) {
            return new Leg(LegType.TRANSIT, "대중교통", minutes, 0, from, to, null, null, null);
        }

        // 노선명: 최대 3개 중복 제거 후 합산 (예: "2호선, 64번")
        String lineName = transitLegs.stream()
                .map(l -> l.transitInfo().lineName())
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .limit(3)
                .collect(Collectors.joining(", "));

        // 노선 색상: 첫 번째 TRANSIT leg 색상 사용
        String lineColor = transitLegs.stream()
                .map(l -> l.transitInfo().lineColor())
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElse(null);

        // 정거장 수: 전체 기준 소요시간 비율로 근사
        int totalBaseMinutes = baseLegs.stream().mapToInt(Leg::durationMinutes).sum();
        int totalStations = transitLegs.stream().mapToInt(l -> l.transitInfo().stationCount()).sum();
        int approxStations = totalBaseMinutes > 0
                ? Math.max(2, (int) Math.round((double) minutes / totalBaseMinutes * totalStations))
                : Math.max(2, totalStations / 2);

        // 경유 정류장: base 경로의 passThroughStations 합산 (근사 표시용)
        List<String> passThroughStations = transitLegs.stream()
                .filter(l -> l.transitInfo().passThroughStations() != null)
                .flatMap(l -> l.transitInfo().passThroughStations().stream())
                .toList();

        TransitInfo transitInfo = lineName.isBlank() ? null
                : new TransitInfo(lineName, lineColor, approxStations, passThroughStations);

        return new Leg(LegType.TRANSIT, "대중교통", minutes, 0, from, to, transitInfo, null, null);
    }

    private Leg mobilityLeg(MobilityType type, MobilityRouteResult result, Location from, Location to, MobilityInfo info) {
        LegType legType = isKickboardType(type) ? LegType.KICKBOARD : LegType.BIKE;
        return new Leg(legType, type.name(), result.durationMinutes(), 0, from, to, null, info, result.routeCoordinates());
    }

    private MobilityConfig configFor(MobilityType type) {
        return isKickboardType(type) ? MobilityConfig.kickboard() : MobilityConfig.bike();
    }

    private RouteType routeTypeFor(MobilityType type) {
        return isKickboardType(type)
                ? RouteType.TRANSIT_WITH_KICKBOARD : RouteType.TRANSIT_WITH_BIKE;
    }

    private static boolean isKickboardType(MobilityType type) {
        return type == MobilityType.KICKBOARD_SHARED || type == MobilityType.PERSONAL;
    }

    private List<Route> rank(List<Route> candidates, int baseMinutes) {
        // TRANSIT_ONLY는 항상 마지막에 고정 노출 — 점수와 무관하게 분리
        Optional<Route> transitOnly = candidates.stream()
                .filter(r -> r.type() == RouteType.TRANSIT_ONLY)
                .findFirst();

        List<Route> mixed = candidates.stream()
                .filter(r -> r.type() != RouteType.TRANSIT_ONLY)
                .map(r -> r.withScore(scoreCalculator.calculate(r), false))
                .sorted(Comparator.comparingDouble(Route::score).reversed())
                .limit(4)  // 혼합 최대 4개 + 대중교통 1개 = 최대 5개
                .toList();

        List<Route> result = new ArrayList<>();
        for (int i = 0; i < mixed.size(); i++) {
            int saved = Math.max(baseMinutes - mixed.get(i).totalMinutes(), 0);
            Route r = mixed.get(i).withComparison(new Comparison(baseMinutes, saved));
            result.add(i == 0 ? r.withScore(r.score(), true) : r);
        }

        // 대중교통 단독 옵션 항상 마지막에 추가
        transitOnly.ifPresent(r ->
                result.add(r.withComparison(new Comparison(baseMinutes, 0)))
        );

        return result;
    }

    /**
     * 출발-도착 간 전체 baseMinutes에서 from→to 구간 비율을 추정.
     * ODsay 추가 호출 없이 패턴 B/C의 부분 대중교통 시간을 계산한다.
     */
    private int proportionalTransitMinutes(Location from, Location to,
                                           Location origin, Location destination, int baseMinutes) {
        double totalDist = haversineMeters(origin.lat(), origin.lng(), destination.lat(), destination.lng());
        if (totalDist == 0) return baseMinutes;
        double partDist = haversineMeters(from.lat(), from.lng(), to.lat(), to.lng());
        double ratio = Math.min(1.0, partDist / totalDist);
        return Math.max(1, (int) Math.round(ratio * baseMinutes));
    }

    private double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng/2)*Math.sin(dLng/2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

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
