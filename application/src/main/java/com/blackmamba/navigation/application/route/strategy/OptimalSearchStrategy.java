package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.*;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.hub.Hub;
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
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final int MAX_CANDIDATE_HUBS = 5;
    // KICKBOARD_SHARED 제외: TAGO API 서울 데이터 미제공으로 가상 경로만 생성됨 (B-1)
    // PERSONAL 제외: 사용자 보유 여부가 전제이므로 OPTIMAL 기본 추천에는 포함하지 않음
    private static final List<MobilityType> ALL_TYPES =
            List.of(MobilityType.DDAREUNGI);

    private final TransitRoutePort transitRoutePort;
    private final MobilityTimePort mobilityTimePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final HubSelector hubSelector;
    private final RouteEvaluator routeEvaluator;
    private final RecommendationPreference recommendationPreference;
    private final MobilitySegmentBuilder mobilitySegmentBuilder;

    public OptimalSearchStrategy(TransitRoutePort transitRoutePort,
                                  MobilityTimePort mobilityTimePort,
                                  MobilityAvailabilityPort mobilityAvailabilityPort,
                                  HubSelector hubSelector,
                                  RouteEvaluator routeEvaluator,
                                  RecommendationPreference recommendationPreference) {
        this.transitRoutePort = transitRoutePort;
        this.mobilityTimePort = mobilityTimePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.hubSelector = hubSelector;
        this.routeEvaluator = routeEvaluator;
        this.recommendationPreference = recommendationPreference;
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
                    MobilityConfig diagConfig = MobilityConfig.bike();
                    List<Hub> diagHubs = hubSelector.selectLastMileHubs(baseLegs, destination, diagConfig);
                    log.info("[OPTIMAL] lastMile 허브={}개 (ddareungi 기준)", diagHubs.size());

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
                            .flatMap(candidates -> {
                                if (hasOnlyTransitCandidate(candidates)) {
                                    return buildNoMixedDiagnostics(origin, destination, baseLegs)
                                            .map(diagnostics -> {
                                                Route enrichedBaseRoute = withDiagnostics(baseRoute, diagnostics);
                                                return rank(List.of(enrichedBaseRoute), baseMinutes, enrichedBaseRoute);
                                            });
                                }
                                return Mono.just(rank(candidates, baseMinutes, baseRoute));
                            });
                });
    }

    // 패턴 B: 이동수단으로 첫 정류장까지 → 대중교통으로 목적지
    private Flux<Route> patternB(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        List<Hub> firstMile = hubSelector.selectFirstMileHubs(origin, baseLegs, config).stream()
                .limit(MAX_CANDIDATE_HUBS)
                .toList();
        return Flux.fromIterable(firstMile)
                .flatMap(firstHub -> {
                    Location transitStart = firstHub.location();
                    return mobilityInfoForSegment(origin, transitStart, type)
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
                                                return buildRoute(legs, RouteType.MOBILITY_FIRST_TRANSIT,
                                                        List.of(RouteHubExtractor.fromSelectedHub(firstHub, "FIRST_MILE_CANDIDATE")));
                                            });
                                });
                });
    }

    // 패턴 C: 대중교통으로 환승점까지 → 이동수단으로 목적지
    private Flux<Route> patternC(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        List<Hub> lastMile = hubSelector.selectLastMileHubs(baseLegs, destination, config).stream()
                .limit(MAX_CANDIDATE_HUBS)
                .toList();
        return Flux.fromIterable(lastMile)
                .flatMap(lastHub -> {
                    Location switchPoint = lastHub.location();
                    return mobilityInfoForSegment(switchPoint, destination, type)
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
                                                return buildRoute(legs, routeTypeFor(type),
                                                        List.of(RouteHubExtractor.fromSelectedHub(lastHub, "LAST_MILE_CANDIDATE")));
                                            });
                                });
                });
    }

    // 패턴 D: 이동수단→정류장 + 대중교통(중간) + 이동수단→목적지
    private Flux<Route> patternD(Location origin, Location destination,
                                  List<Leg> baseLegs, MobilityType type, MobilityConfig config) {
        List<Hub> firstMile = hubSelector.selectFirstMileHubs(origin, baseLegs, config);
        List<Hub> lastMile  = hubSelector.selectLastMileHubs(baseLegs, destination, config);
        if (firstMile.isEmpty() || lastMile.isEmpty()) return Flux.empty();

        Hub startHub = firstMile.get(0);
        Hub endHub = lastMile.get(lastMile.size() / 2);
        Location transitStart = startHub.location();
        Location transitEnd   = endHub.location();

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
                                return buildRoute(legs, RouteType.MOBILITY_TRANSIT_MOBILITY,
                                        List.of(
                                                RouteHubExtractor.fromSelectedHub(startHub, "FIRST_MILE_CANDIDATE"),
                                                RouteHubExtractor.fromSelectedHub(endHub, "LAST_MILE_CANDIDATE")
                                        ));
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
                            .map(legs -> buildRoute(legs, RouteType.MOBILITY_ONLY, List.of()))
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
                                ))
                                .filter(info -> !info.hasSamePickupAndDropoffStation())));
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private Route buildRoute(List<Leg> legs, RouteType type, List<RouteHub> selectedHubs) {
        return Route.of(legs, type).withSelectedHubs(selectedHubs);
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
                : new TransitInfo(lineName, lineColor, approxStations, 0, passThroughStations);

        return new Leg(LegType.TRANSIT, "대중교통", minutes, 0, from, to, transitInfo, null, null);
    }

    private MobilityConfig configFor(MobilityType type) {
        return switch (type) {
            case PERSONAL -> MobilityConfig.personal();
            case KICKBOARD_SHARED -> MobilityConfig.kickboard();
            case DDAREUNGI -> MobilityConfig.bike();
        };
    }

    private RouteType routeTypeFor(MobilityType type) {
        return isKickboardType(type)
                ? RouteType.TRANSIT_WITH_KICKBOARD : RouteType.TRANSIT_WITH_BIKE;
    }

    private static boolean isKickboardType(MobilityType type) {
        return type == MobilityType.KICKBOARD_SHARED || type == MobilityType.PERSONAL;
    }

    private List<Route> rank(List<Route> candidates, int baseMinutes, Route baseRoute) {
        List<Route> evaluated = candidates.stream()
                .map(route -> routeEvaluator.evaluate(route, baseRoute, baseMinutes, false, recommendationPreference))
                .sorted(Comparator.comparingDouble(Route::score).reversed())
                .limit(5)
                .toList();

        if (evaluated.isEmpty()) {
            return evaluated;
        }

        List<Route> result = new ArrayList<>(evaluated);
        Route top = result.getFirst();
        result.set(0, routeEvaluator.evaluate(top, baseRoute, baseMinutes, true, recommendationPreference));
        return result;
    }

    private boolean hasOnlyTransitCandidate(List<Route> candidates) {
        return candidates.size() == 1 && candidates.getFirst().type() == RouteType.TRANSIT_ONLY;
    }

    private Route withDiagnostics(Route route, List<String> diagnostics) {
        return route.withInsights(new RouteInsights(List.of(), List.of(), diagnostics, List.of()));
    }

    private Mono<List<String>> buildNoMixedDiagnostics(Location origin, Location destination, List<Leg> baseLegs) {
        return Flux.fromIterable(ALL_TYPES)
                .flatMap(type -> diagnosticsForType(origin, destination, baseLegs, type))
                .distinct()
                .collectList()
                .map(list -> list.isEmpty() ? List.of("혼합 경로 후보를 만들지 못했습니다.") : list);
    }

    private Flux<String> diagnosticsForType(Location origin, Location destination, List<Leg> baseLegs, MobilityType type) {
        MobilityConfig config = configFor(type);
        List<Hub> lastMileHubs = hubSelector.selectLastMileHubs(baseLegs, destination, config).stream()
                .limit(MAX_CANDIDATE_HUBS)
                .toList();
        List<Hub> firstMileHubs = hubSelector.selectFirstMileHubs(origin, baseLegs, config).stream()
                .limit(MAX_CANDIDATE_HUBS)
                .toList();

        List<String> immediateReasons = new ArrayList<>();
        if (firstMileHubs.isEmpty()) immediateReasons.add(labelFor(type) + " 퍼스트마일 후보 허브가 없습니다.");
        if (lastMileHubs.isEmpty()) immediateReasons.add(labelFor(type) + " 라스트마일 후보 허브가 없습니다.");

        double directDistance = haversineMeters(origin.lat(), origin.lng(), destination.lat(), destination.lng());
        if (directDistance > config.maxRangeMeters()) {
            immediateReasons.add(labelFor(type) + " 직접 이동 가능 거리(" + config.maxRangeMeters() + "m)를 초과했습니다. 현재 직선거리 약 " + (int) directDistance + "m");
        }

        Mono<Optional<String>> lastMileReason = diagnoseSegmentAvailability(
                Flux.fromIterable(lastMileHubs)
                        .flatMap(hub -> diagnoseSegment(origin, hub.location(), destination, type, false)),
                labelFor(type),
                "라스트마일",
                lastMileHubs.size()
        );

        Mono<Optional<String>> firstMileReason = diagnoseSegmentAvailability(
                Flux.fromIterable(firstMileHubs)
                        .flatMap(hub -> diagnoseSegment(origin, hub.location(), destination, type, true)),
                labelFor(type),
                "퍼스트마일",
                firstMileHubs.size()
        );

        return Mono.zip(firstMileReason, lastMileReason)
                .flatMapMany(tuple -> {
                    List<String> reasons = new ArrayList<>(immediateReasons);
                    tuple.getT1().ifPresent(reasons::add);
                    tuple.getT2().ifPresent(reasons::add);
                    return Flux.fromIterable(reasons);
                });
    }

    private Mono<Optional<String>> diagnoseSegmentAvailability(Flux<SegmentDiagnostic> diagnostics,
                                                               String label,
                                                               String phase,
                                                               int hubCount) {
        return diagnostics.collectList()
                .map(items -> {
                    if (hubCount == 0 || items.isEmpty()) {
                        return Optional.<String>empty();
                    }
                    long validCount = items.stream().filter(SegmentDiagnostic::isValid).count();
                    if (validCount > 0) {
                        return Optional.<String>empty();
                    }
                    long noPickup = items.stream().filter(item -> item.reason() == DiagnosticReason.NO_PICKUP).count();
                    long noDropoff = items.stream().filter(item -> item.reason() == DiagnosticReason.NO_DROPOFF).count();
                    long sameStation = items.stream().filter(item -> item.reason() == DiagnosticReason.SAME_STATION).count();

                    if (sameStation > 0) {
                        return Optional.of(label + " " + phase + " 후보 " + hubCount + "개를 확인했지만 동일 정류소 대여/반납 조합만 발견되어 제외했습니다.");
                    }
                    if (noDropoff > 0 && noPickup == 0) {
                        return Optional.of(label + " " + phase + " 후보 " + hubCount + "개를 확인했지만 반납 가능한 정류소를 찾지 못했습니다.");
                    }
                    if (noPickup > 0 && noDropoff == 0) {
                        return Optional.of(label + " " + phase + " 후보 " + hubCount + "개를 확인했지만 반경 내 대여 가능한 수단을 찾지 못했습니다.");
                    }
                    return Optional.of(label + " " + phase + " 후보 " + hubCount + "개를 확인했지만 대여/반납 가능한 수단을 찾지 못했습니다.");
                });
    }

    private Mono<SegmentDiagnostic> diagnoseSegment(Location origin,
                                                    Location switchPoint,
                                                    Location destination,
                                                    MobilityType type,
                                                    boolean firstMile) {
        Location start = firstMile ? origin : switchPoint;
        Location end = firstMile ? switchPoint : destination;

        Mono<Optional<MobilityInfo>> pickup = mobilityAvailabilityPort.findNearbyMobility(start.lat(), start.lng(), type);

        if (type != MobilityType.DDAREUNGI) {
            return pickup.map(result -> result.isPresent()
                    ? SegmentDiagnostic.success()
                    : SegmentDiagnostic.of(DiagnosticReason.NO_PICKUP));
        }

        Mono<Optional<MobilityInfo>> dropoff = mobilityAvailabilityPort.findNearbyDropoff(end.lat(), end.lng(), type);
        return Mono.zip(pickup, dropoff)
                .map(tuple -> {
                    Optional<MobilityInfo> pickupInfo = tuple.getT1();
                    Optional<MobilityInfo> dropoffInfo = tuple.getT2();
                    if (pickupInfo.isEmpty()) {
                        return SegmentDiagnostic.of(DiagnosticReason.NO_PICKUP);
                    }
                    if (dropoffInfo.isEmpty()) {
                        return SegmentDiagnostic.of(DiagnosticReason.NO_DROPOFF);
                    }
                    MobilityInfo enriched = pickupInfo.get().withDropoffStation(
                            dropoffInfo.get().stationId(),
                            dropoffInfo.get().stationName(),
                            dropoffInfo.get().lat(),
                            dropoffInfo.get().lng()
                    );
                    if (enriched.hasSamePickupAndDropoffStation()) {
                        return SegmentDiagnostic.of(DiagnosticReason.SAME_STATION);
                    }
                        return SegmentDiagnostic.success();
                });
    }

    private record SegmentDiagnostic(boolean valid, DiagnosticReason reason) {
        private boolean isValid() {
            return valid;
        }

        private static SegmentDiagnostic success() {
            return new SegmentDiagnostic(true, DiagnosticReason.VALID);
        }

        private static SegmentDiagnostic of(DiagnosticReason reason) {
            return new SegmentDiagnostic(false, reason);
        }
    }

    private enum DiagnosticReason {
        VALID,
        NO_PICKUP,
        NO_DROPOFF,
        SAME_STATION
    }

    private String labelFor(MobilityType type) {
        return switch (type) {
            case DDAREUNGI -> "따릉이";
            case KICKBOARD_SHARED -> "공유 킥보드";
            case PERSONAL -> "개인 이동수단";
        };
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
