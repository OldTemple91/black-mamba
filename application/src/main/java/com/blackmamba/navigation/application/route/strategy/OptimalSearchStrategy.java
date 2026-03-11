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
    // KICKBOARD_SHARED 제외: TAGO API 서울 데이터 미제공으로 가상 경로만 생성됨 (B-1)
    // 향후 PERSONAL_KICKBOARD 또는 MobilityHub 모델 도입 시 재추가
    private static final List<MobilityType> ALL_TYPES =
            List.of(MobilityType.DDAREUNGI);

    private final TransitRoutePort transitRoutePort;
    private final MobilityTimePort mobilityTimePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final CandidatePointSelector candidatePointSelector;
    private final RouteScoreCalculator scoreCalculator;
    private final RouteInsightFactory routeInsightFactory;
    private final MobilitySegmentBuilder mobilitySegmentBuilder;

    public OptimalSearchStrategy(TransitRoutePort transitRoutePort,
                                  MobilityTimePort mobilityTimePort,
                                  MobilityAvailabilityPort mobilityAvailabilityPort,
                                  CandidatePointSelector candidatePointSelector,
                                  RouteScoreCalculator scoreCalculator,
                                  RouteInsightFactory routeInsightFactory) {
        this.transitRoutePort = transitRoutePort;
        this.mobilityTimePort = mobilityTimePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.candidatePointSelector = candidatePointSelector;
        this.scoreCalculator = scoreCalculator;
        this.routeInsightFactory = routeInsightFactory;
        this.mobilitySegmentBuilder = new MobilitySegmentBuilder(mobilityTimePort);
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
                            .map(candidates -> rank(candidates, baseMinutes, baseRoute));
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
                        mobilityInfoForSegment(origin, transitStart, type)
                                .filter(Optional::isPresent)
                                .flatMap(avail -> {
                                    MobilityInfo info = avail.get();
                                    Mono<List<Leg>> transitLegs = transitRoutePort.getTransitRoute(transitStart, destination);
                                    Mono<Integer> transitTime = transitRoutePort.getTransitTimeMinutes(transitStart, destination);
                                    return Mono.zip(
                                                    mobilitySegmentBuilder.build(origin, transitStart, type, info),
                                                    transitLegs,
                                                    transitTime
                                            )
                                            .map(tuple -> {
                                                List<Leg> partialTransit = tuple.getT2().isEmpty()
                                                        ? List.of(transitLeg(tuple.getT3(), transitStart, destination, baseLegs))
                                                        : tuple.getT2();
                                                List<Leg> legs = new ArrayList<>();
                                                legs.addAll(tuple.getT1());
                                                legs.addAll(partialTransit);
                                                return buildRoute(legs, RouteType.MOBILITY_FIRST_TRANSIT);
                                            });
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
                        mobilityInfoForSegment(switchPoint, destination, type)
                                .filter(Optional::isPresent)
                                .flatMap(avail -> {
                                    MobilityInfo info = avail.get();
                                    Mono<List<Leg>> transitLegs = transitRoutePort.getTransitRoute(origin, switchPoint);
                                    Mono<Integer> transitTime = transitRoutePort.getTransitTimeMinutes(origin, switchPoint);
                                    return Mono.zip(
                                                    transitLegs,
                                                    transitTime,
                                                    mobilitySegmentBuilder.build(switchPoint, destination, type, info)
                                            )
                                            .map(tuple -> {
                                                List<Leg> partialTransit = tuple.getT1().isEmpty()
                                                        ? List.of(transitLeg(tuple.getT2(), origin, switchPoint, baseLegs))
                                                        : tuple.getT1();
                                                List<Leg> legs = new ArrayList<>(partialTransit);
                                                legs.addAll(tuple.getT3());
                                                return buildRoute(legs, routeTypeFor(type));
                                            });
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

        Mono<Optional<MobilityInfo>> startInfo = mobilityInfoForSegment(origin, transitStart, type);
        Mono<Optional<MobilityInfo>> endInfo   = mobilityInfoForSegment(transitEnd, destination, type);

        return Mono.zip(startInfo, endInfo)
                .filter(tuple -> tuple.getT1().isPresent() && tuple.getT2().isPresent())
                .flatMapMany(tuple -> {
                    MobilityInfo startMobility = tuple.getT1().get();
                    MobilityInfo endMobility   = tuple.getT2().get();
                    Mono<List<Leg>> startSegment = mobilitySegmentBuilder.build(origin, transitStart, type, startMobility);
                    Mono<List<Leg>>           tranLegs = transitRoutePort.getTransitRoute(transitStart, transitEnd);
                    Mono<Integer>             tranTime = transitRoutePort.getTransitTimeMinutes(transitStart, transitEnd);
                    Mono<List<Leg>> endSegment = mobilitySegmentBuilder.build(transitEnd, destination, type, endMobility);
                    return Mono.zip(startSegment, tranLegs, tranTime, endSegment)
                            .map(t -> {
                                List<Leg> middleTransit = t.getT2().isEmpty()
                                        ? List.of(transitLeg(t.getT3(), transitStart, transitEnd, baseLegs))
                                        : t.getT2();
                                List<Leg> legs = new ArrayList<>();
                                legs.addAll(t.getT1());
                                legs.addAll(middleTransit);
                                legs.addAll(t.getT4());
                                return buildRoute(legs, RouteType.MOBILITY_TRANSIT_MOBILITY);
                            })
                            .flux();
                });
    }

    // 패턴 E: 이동수단만 (직선거리 < 수단 최대범위)
    private Flux<Route> patternE(Location origin, Location destination,
                                  MobilityType type, MobilityConfig config) {
        double dist = haversineMeters(origin.lat(), origin.lng(), destination.lat(), destination.lng());
        if (dist > config.maxRangeMeters()) return Flux.empty();

        return mobilityInfoForSegment(origin, destination, type)
                .filter(Optional::isPresent)
                .flatMapMany(avail -> {
                    return mobilitySegmentBuilder.build(origin, destination, type, avail.get())
                            .map(legs -> buildRoute(legs, RouteType.MOBILITY_ONLY))
                            .flux();
                });
    }

    private Mono<Optional<MobilityInfo>> mobilityInfoForSegment(Location start, Location end, MobilityType type) {
        Mono<Optional<MobilityInfo>> pickup = mobilityAvailabilityPort
                .findNearbyMobility(start.lat(), start.lng(), type);

        if (type != MobilityType.DDAREUNGI) {
            return pickup;
        }

        Mono<Optional<MobilityInfo>> dropoff = mobilityAvailabilityPort
                .findNearbyDropoff(end.lat(), end.lng(), type);

        return Mono.zip(pickup, dropoff)
                .map(tuple -> tuple.getT1()
                        .flatMap(pickupInfo -> tuple.getT2()
                                .map(dropoffInfo -> pickupInfo.withDropoffStation(
                                        dropoffInfo.stationId(),
                                        dropoffInfo.stationName(),
                                        dropoffInfo.lat(),
                                        dropoffInfo.lng()
                                ))));
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
        List<Location> passThroughStations = transitLegs.stream()
                .filter(l -> l.transitInfo().passThroughStations() != null)
                .flatMap(l -> l.transitInfo().passThroughStations().stream())
                .toList();

        TransitInfo transitInfo = lineName.isBlank() ? null
                : new TransitInfo(lineName, lineColor, approxStations, passThroughStations);

        return new Leg(LegType.TRANSIT, "대중교통", minutes, 0, from, to, transitInfo, null, null);
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

    private List<Route> rank(List<Route> candidates, int baseMinutes, Route baseRoute) {
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
            Route ranked = i == 0 ? r.withScore(r.score(), true) : r;
            result.add(routeInsightFactory.enrich(ranked, baseRoute));
        }

        // 대중교통 단독 옵션 항상 마지막에 추가
        transitOnly.ifPresent(r -> {
            Route transitRoute = r.withComparison(new Comparison(baseMinutes, 0));
            Route ranked = result.isEmpty() ? transitRoute.withScore(transitRoute.score(), true) : transitRoute;
            result.add(routeInsightFactory.enrich(ranked, baseRoute));
        });

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
