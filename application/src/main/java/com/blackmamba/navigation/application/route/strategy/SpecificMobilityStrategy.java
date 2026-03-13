package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.*;
import com.blackmamba.navigation.application.route.port.*;
import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 사용자가 선택한 이동수단으로 라스트마일(패턴 C)만 탐색하는 전략.
 * ODsay 결과가 없을 경우 직접 이동수단 경로로 폴백.
 */
public class SpecificMobilityStrategy implements RouteSearchStrategy {

    private static final int MAX_CANDIDATE_HUBS = 5;
    private final List<MobilityType> mobilityTypes;
    private final TransitRoutePort transitRoutePort;
    private final MobilityTimePort mobilityTimePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final HubSelector hubSelector;
    private final RouteEvaluator routeEvaluator;
    private final RecommendationPreference recommendationPreference;
    private final MobilitySegmentBuilder mobilitySegmentBuilder;

    public SpecificMobilityStrategy(List<MobilityType> mobilityTypes,
                                     TransitRoutePort transitRoutePort,
                                     MobilityTimePort mobilityTimePort,
                                     MobilityAvailabilityPort mobilityAvailabilityPort,
                                     HubSelector hubSelector,
                                     RouteEvaluator routeEvaluator,
                                     RecommendationPreference recommendationPreference) {
        this.mobilityTypes = mobilityTypes;
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
                        return Mono.just(List.of(routeEvaluator.evaluate(baseRoute, true, recommendationPreference)));
                    }
                    return generateCombinedRoutes(baseLegs, origin, destination)
                            .collectList()
                            .flatMap(combined -> {
                                if (combined.isEmpty()) {
                                    return buildNoMixedDiagnostics(baseLegs, origin, destination)
                                            .map(diagnostics -> rank(withDiagnostics(baseRoute, diagnostics), combined, baseTime));
                                }
                                return Mono.just(rank(baseRoute, combined, baseTime));
                            });
                });
    }

    private Flux<Route> generateCombinedRoutes(List<Leg> baseLegs, Location origin, Location destination) {
        return Flux.fromIterable(mobilityTypes)
                .flatMap(type -> {
                    MobilityConfig config = isKickboardType(type)
                            ? personalAwareConfig(type) : MobilityConfig.bike();
                    List<Hub> candidateHubs = hubSelector.selectLastMileHubs(baseLegs, destination, config).stream()
                            .limit(MAX_CANDIDATE_HUBS)
                            .toList();

                    if (candidateHubs.isEmpty()) {
                        // 대중교통 경로 없음 → 출발지에서 목적지 직접 이동수단 경로로 폴백
                        return buildDirectRoute(origin, destination, type).flux();
                    }
                    return Flux.fromIterable(candidateHubs)
                            .flatMap(candidateHub -> buildRoute(origin, candidateHub, destination, type, baseLegs));
                });
    }

    private Mono<Route> buildRoute(Location origin, Hub candidateHub,
                                    Location destination, MobilityType type,
                                    List<Leg> baseLegs) {
        Location switchPoint = candidateHub.location();
        Mono<List<Leg>>           transitLegs  = transitRoutePort.getTransitRoute(origin, switchPoint);
        Mono<Integer>             transitTime  = transitRoutePort.getTransitTimeMinutes(origin, switchPoint);
        Mono<java.util.Optional<MobilityInfo>> avail = mobilityInfoForSegment(switchPoint, destination, type);

        return Mono.zip(transitLegs, transitTime, avail)
                .filter(tuple -> tuple.getT3().isPresent())
                .flatMap(tuple -> mobilitySegmentBuilder.build(switchPoint, destination, type, tuple.getT3().get())
                .map(mobilityLegs -> {
                    RouteType routeType = isKickboardType(type)
                            ? RouteType.TRANSIT_WITH_KICKBOARD : RouteType.TRANSIT_WITH_BIKE;
                    List<Leg> partialTransit = tuple.getT1().isEmpty()
                            ? List.of(buildTransitLeg(tuple.getT2(), origin, switchPoint, baseLegs))
                            : tuple.getT1();
                    List<Leg> legs = new ArrayList<>(partialTransit);
                    legs.addAll(mobilityLegs);
                    return Route.of(legs, routeType)
                            .withSelectedHubs(List.of(RouteHubExtractor.fromSelectedHub(candidateHub, "LAST_MILE_CANDIDATE")));
                }));
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
                : new TransitInfo(lineName, lineColor, approxStations, 0, passThroughStations);
        return new Leg(LegType.TRANSIT, "대중교통", minutes, 0, from, to, transitInfo, null, null);
    }

    /** ODsay 없이 출발지→목적지 직접 이동수단 경로 */
    private Mono<Route> buildDirectRoute(Location origin, Location destination, MobilityType type) {
        Mono<java.util.Optional<MobilityInfo>> avail = mobilityInfoForSegment(origin, destination, type);

        return avail
                .filter(java.util.Optional::isPresent)
                .flatMap(optionalInfo -> mobilitySegmentBuilder.build(origin, destination, type, optionalInfo.get())
                        .map(legs -> Route.of(legs, RouteType.MOBILITY_ONLY)));
    }

    private Mono<java.util.Optional<MobilityInfo>> mobilityInfoForSegment(Location start, Location end, MobilityType type) {
        Mono<java.util.Optional<MobilityInfo>> pickup = mobilityAvailabilityPort
                .findNearbyMobility(start.lat(), start.lng(), type);

        if (type != MobilityType.DDAREUNGI) {
            return pickup;
        }

        Mono<java.util.Optional<MobilityInfo>> dropoff = mobilityAvailabilityPort
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

    /** KICKBOARD_SHARED 및 PERSONAL 모두 킥보드 타입으로 처리 */
    private static boolean isKickboardType(MobilityType type) {
        return type == MobilityType.KICKBOARD_SHARED || type == MobilityType.PERSONAL;
    }

    private MobilityConfig personalAwareConfig(MobilityType type) {
        return type == MobilityType.PERSONAL ? MobilityConfig.personal() : MobilityConfig.kickboard();
    }

    private List<Route> rank(Route base, List<Route> combined, int baseMinutes) {
        List<Route> all = new ArrayList<>(combined);
        all.add(base);
        List<Route> scored = all.stream()
                .map(r -> routeEvaluator.evaluate(r, base, baseMinutes, false, recommendationPreference))
                .sorted(Comparator.comparingDouble(Route::score).reversed())
                .limit(5)
                .toList();

        if (scored.isEmpty()) {
            return scored;
        }

        List<Route> result = new ArrayList<>(scored);
        Route top = result.getFirst();
        result.set(0, routeEvaluator.evaluate(top, base, baseMinutes, true, recommendationPreference));
        return result;
    }

    private Route withDiagnostics(Route route, List<String> diagnostics) {
        return route.withInsights(new RouteInsights(List.of(), List.of(), diagnostics));
    }

    private Mono<List<String>> buildNoMixedDiagnostics(List<Leg> baseLegs, Location origin, Location destination) {
        return Flux.fromIterable(mobilityTypes)
                .flatMap(type -> diagnosticsForType(baseLegs, origin, destination, type))
                .distinct()
                .collectList()
                .map(list -> list.isEmpty() ? List.of("혼합 경로 후보를 만들지 못했습니다.") : list);
    }

    private Flux<String> diagnosticsForType(List<Leg> baseLegs, Location origin, Location destination, MobilityType type) {
        MobilityConfig config = isKickboardType(type) ? personalAwareConfig(type) : MobilityConfig.bike();
        List<Hub> candidateHubs = hubSelector.selectLastMileHubs(baseLegs, destination, config).stream()
                .limit(MAX_CANDIDATE_HUBS)
                .toList();

        if (candidateHubs.isEmpty()) {
            return Flux.just(labelFor(type) + " 라스트마일 후보 허브가 없습니다.");
        }

        return Flux.fromIterable(candidateHubs)
                .flatMap(hub -> mobilityInfoForSegment(hub.location(), destination, type))
                .any(Optional::isPresent)
                .flatMapMany(available -> {
                    if (available) {
                        return Flux.empty();
                    }
                    return Flux.just(labelFor(type) + " 라스트마일 구간에서 대여/반납 가능한 수단을 찾지 못했습니다.");
                });
    }

    private String labelFor(MobilityType type) {
        return switch (type) {
            case DDAREUNGI -> "따릉이";
            case KICKBOARD_SHARED -> "공유 킥보드";
            case PERSONAL -> "개인 이동수단";
        };
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
