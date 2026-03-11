package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.MobilityTimePort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.MobilityInfo;
import com.blackmamba.navigation.domain.route.MobilityRouteResult;
import com.blackmamba.navigation.domain.route.MobilityType;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 실제 대여/기기 위치를 기준으로 접근 도보 + 이동수단 + 이탈 도보 구간을 조립한다.
 */
public class MobilitySegmentBuilder {

    private static final int WALK_INSERT_THRESHOLD_METERS = 20;

    private final MobilityTimePort mobilityTimePort;

    public MobilitySegmentBuilder(MobilityTimePort mobilityTimePort) {
        this.mobilityTimePort = mobilityTimePort;
    }

    public Mono<List<Leg>> build(Location approachPoint, Location destinationPoint,
                                 MobilityType type, MobilityInfo info) {
        Location pickupPoint = pickupPoint(approachPoint, type, info);
        Location dropoffPoint = dropoffPoint(destinationPoint, type, info);

        Mono<List<Leg>> accessWalk = needsWalk(approachPoint, pickupPoint)
                ? mobilityTimePort.getWalkingRoute(approachPoint, pickupPoint)
                .map(route -> List.of(walkLeg(approachPoint, pickupPoint, route)))
                : Mono.just(List.of());

        Mono<Leg> mobilityLeg = mobilityTimePort.getMobilityRoute(pickupPoint, dropoffPoint, type)
                .map(route -> mobilityLeg(type, pickupPoint, dropoffPoint, info, route));

        Mono<List<Leg>> egressWalk = needsWalk(dropoffPoint, destinationPoint)
                ? mobilityTimePort.getWalkingRoute(dropoffPoint, destinationPoint)
                .map(route -> List.of(walkLeg(dropoffPoint, destinationPoint, route)))
                : Mono.just(List.of());

        return Mono.zip(accessWalk, mobilityLeg, egressWalk)
                .map(tuple -> {
                    List<Leg> legs = new ArrayList<>();
                    legs.addAll(tuple.getT1());
                    legs.add(tuple.getT2());
                    legs.addAll(tuple.getT3());
                    return legs;
                });
    }

    private Location pickupPoint(Location fallback, MobilityType type, MobilityInfo info) {
        return switch (type) {
            case DDAREUNGI -> new Location(
                    info.stationName() != null ? info.stationName() : fallback.name(),
                    info.lat(),
                    info.lng()
            );
            case KICKBOARD_SHARED -> new Location(
                    info.operatorName() != null ? info.operatorName() + " 킥보드" : fallback.name(),
                    info.lat(),
                    info.lng()
            );
            case PERSONAL -> fallback;
        };
    }

    private Location dropoffPoint(Location fallback, MobilityType type, MobilityInfo info) {
        if (type == MobilityType.DDAREUNGI && info.hasDropoffStation()) {
            return new Location(info.dropoffStationName(), info.dropoffLat(), info.dropoffLng());
        }
        return fallback;
    }

    private boolean needsWalk(Location a, Location b) {
        return distanceMeters(a, b) > WALK_INSERT_THRESHOLD_METERS;
    }

    private Leg walkLeg(Location start, Location end, MobilityRouteResult route) {
        return new Leg(
                LegType.WALK,
                "WALK",
                route.durationMinutes(),
                route.distanceMeters(),
                start,
                end,
                null,
                null,
                route.routeCoordinates()
        );
    }

    private Leg mobilityLeg(MobilityType type, Location start, Location end,
                            MobilityInfo info, MobilityRouteResult route) {
        LegType legType = type == MobilityType.DDAREUNGI ? LegType.BIKE : LegType.KICKBOARD;
        return new Leg(
                legType,
                type.name(),
                route.durationMinutes(),
                route.distanceMeters(),
                start,
                end,
                null,
                info,
                route.routeCoordinates()
        );
    }

    private int distanceMeters(Location a, Location b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return (int) (6371_000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h)));
    }
}
