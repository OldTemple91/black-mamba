package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.application.route.port.TransitRoutePort;
import com.blackmamba.navigation.application.route.segment.MobilitySegmentBuilder;
import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.domain.route.evaluation.RouteHub;
import com.blackmamba.navigation.domain.route.evaluation.RouteHubExtractor;
import com.blackmamba.navigation.application.route.hub.MobilityConfig;
import com.blackmamba.navigation.domain.route.mobility.MobilityInfo;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 후보 경로 하나를 조립한다 — 패턴 B(퍼스트마일)·C(라스트마일)·D(양쪽)·E(이동수단만).
 * <p>
 * OPTIMAL 과 SPECIFIC 전략이 같은 조립을 각자 Reactor 람다 안에 복사해 들고 있었다:
 * "대중교통 구간 조회가 비면 근사로 대체" 가 4벌, "직접 이동 가능 거리" 규칙이 4곳, 패턴 C 는 두 전략에 통째로.
 * 전략은 "어떤 허브로 어떤 패턴을 시도할지" 만 정하고, 한 후보를 어떻게 만드는지는 여기 한 곳에 둔다.
 * <p>
 * 모든 메서드는 구간이 성립하지 않으면 값 없이 끝난다(empty) — 호출자는 그 후보를 건너뛰면 된다.
 * 가용성 확인을 대중교통 조회보다 먼저 한다: 성립하지 않을 후보에 ODsay 호출을 쓰지 않는다.
 */
final class CandidateRouteBuilder {

    private static final String ROLE_FIRST_MILE = "FIRST_MILE_CANDIDATE";
    private static final String ROLE_LAST_MILE = "LAST_MILE_CANDIDATE";

    private final TransitRoutePort transitRoutePort;
    private final MobilityAvailabilityPort mobilityAvailabilityPort;
    private final MobilitySegmentBuilder mobilitySegmentBuilder;

    CandidateRouteBuilder(TransitRoutePort transitRoutePort,
                          MobilityAvailabilityPort mobilityAvailabilityPort,
                          MobilitySegmentBuilder mobilitySegmentBuilder) {
        this.transitRoutePort = transitRoutePort;
        this.mobilityAvailabilityPort = mobilityAvailabilityPort;
        this.mobilitySegmentBuilder = mobilitySegmentBuilder;
    }

    /** 탐색 한 번의 고정 입력 — 출발·도착과 기준 대중교통 경로. */
    record SearchContext(Location origin, Location destination, List<Leg> baseLegs) {}

    /** 패턴 B: 이동수단(출발 → 허브) + 대중교통(허브 → 목적지). */
    Mono<Route> firstMileVia(SearchContext context, MobilityType type, Hub firstHub) {
        Location transitStart = firstHub.location();
        return availableSegment(context.origin(), transitStart, type)
                .flatMap(info -> Mono.zip(
                                mobilitySegmentBuilder.build(context.origin(), transitStart, type, info),
                                transitLegs(transitStart, context.destination(), context.baseLegs()))
                        .map(parts -> route(concat(parts.getT1(), parts.getT2()), RouteType.MOBILITY_FIRST_TRANSIT,
                                List.of(RouteHubExtractor.fromSelectedHub(firstHub, ROLE_FIRST_MILE)))));
    }

    /** 패턴 C: 대중교통(출발 → 허브) + 이동수단(허브 → 목적지). */
    Mono<Route> lastMileVia(SearchContext context, MobilityType type, Hub lastHub) {
        Location switchPoint = lastHub.location();
        return availableSegment(switchPoint, context.destination(), type)
                .flatMap(info -> Mono.zip(
                                transitLegs(context.origin(), switchPoint, context.baseLegs()),
                                mobilitySegmentBuilder.build(switchPoint, context.destination(), type, info))
                        .map(parts -> route(concat(parts.getT1(), parts.getT2()), MobilityConfigs.routeTypeFor(type),
                                List.of(RouteHubExtractor.fromSelectedHub(lastHub, ROLE_LAST_MILE)))));
    }

    /** 패턴 D: 이동수단(출발 → 허브) + 대중교통(허브 → 허브) + 이동수단(허브 → 목적지). 양쪽 구간이 모두 성립해야 한다. */
    Mono<Route> bothEndsVia(SearchContext context, MobilityType type, Hub startHub, Hub endHub) {
        Location transitStart = startHub.location();
        Location transitEnd = endHub.location();
        return Mono.zip(
                        availableSegment(context.origin(), transitStart, type),
                        availableSegment(transitEnd, context.destination(), type))
                .flatMap(infos -> Mono.zip(
                                mobilitySegmentBuilder.build(context.origin(), transitStart, type, infos.getT1()),
                                transitLegs(transitStart, transitEnd, context.baseLegs()),
                                mobilitySegmentBuilder.build(transitEnd, context.destination(), type, infos.getT2()))
                        .map(parts -> route(concat(parts.getT1(), parts.getT2(), parts.getT3()), RouteType.MOBILITY_TRANSIT_MOBILITY,
                                List.of(
                                        RouteHubExtractor.fromSelectedHub(startHub, ROLE_FIRST_MILE),
                                        RouteHubExtractor.fromSelectedHub(endHub, ROLE_LAST_MILE)))));
    }

    /** 패턴 E: 대중교통 없이 이동수단만. 수단의 최대 범위를 넘으면 만들지 않는다. */
    Mono<Route> direct(SearchContext context, MobilityType type, MobilityConfig config) {
        if (exceedsDirectRange(context.origin(), context.destination(), config)) {
            return Mono.empty();
        }
        return availableSegment(context.origin(), context.destination(), type)
                .flatMap(info -> mobilitySegmentBuilder.build(context.origin(), context.destination(), type, info))
                .map(legs -> route(legs, RouteType.MOBILITY_ONLY, List.of()));
    }

    /** 출발지에서 바로 탈 수단이 있는가. 개인 수단은 사용자가 갖고 있으므로 항상 참. */
    Mono<Boolean> hasPickupNearOrigin(Location origin, MobilityType type) {
        if (type.isPersonal()) {
            return Mono.just(true);
        }
        return mobilityAvailabilityPort.findNearbyMobility(origin.lat(), origin.lng(), type)
                .map(Optional::isPresent);
    }

    /** 직선거리가 수단의 최대 이동 범위를 넘는가 — 경로 생성과 "못 만든 이유" 진단이 같은 규칙을 쓴다. */
    static boolean exceedsDirectRange(Location origin, Location destination, MobilityConfig config) {
        return GeoDistance.meters(origin, destination) > config.maxRangeMeters();
    }

    private Mono<MobilityInfo> availableSegment(Location start, Location end, MobilityType type) {
        return mobilityAvailabilityPort.findSegmentMobility(start.lat(), start.lng(), end.lat(), end.lng(), type);
    }

    /** 부분 대중교통 구간. 조회 결과가 비면(구간이 너무 짧거나 조회 실패) 기준 경로 기반 근사 구간으로 대체한다. */
    private Mono<List<Leg>> transitLegs(Location from, Location to, List<Leg> baseLegs) {
        return Mono.zip(transitRoutePort.getTransitRoute(from, to), transitRoutePort.getTransitTimeMinutes(from, to))
                .map(found -> {
                    if (found.getT1().isEmpty()) {
                        return List.of(TransitLegFactory.approximate(found.getT2(), from, to, baseLegs));
                    }
                    return found.getT1();
                });
    }

    private static Route route(List<Leg> legs, RouteType type, List<RouteHub> selectedHubs) {
        return Route.of(legs, type).withSelectedHubs(selectedHubs);
    }

    @SafeVarargs
    private static List<Leg> concat(List<Leg>... parts) {
        return Stream.of(parts)
                .flatMap(List::stream)
                .toList();
    }
}
