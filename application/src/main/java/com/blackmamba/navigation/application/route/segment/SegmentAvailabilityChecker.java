package com.blackmamba.navigation.application.route.segment;

import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.mobility.MobilityInfo;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import com.blackmamba.navigation.domain.route.mobility.SegmentAvailability;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * 구간 가용성 조회의 조립 — 대여·반납 조회(I/O)를 묶어 도메인 규칙({@link SegmentAvailability#of})에 넘긴다.
 * 판정 자체는 도메인에 있고, 여기는 "무엇을 조회해서 넘길지" 만 안다.
 */
public final class SegmentAvailabilityChecker {

    private SegmentAvailabilityChecker() {
    }

    public static Mono<SegmentAvailability> check(MobilityAvailabilityPort port, Location start, Location end, MobilityType type) {
        Mono<Optional<MobilityInfo>> pickup = port.findNearbyMobility(start.lat(), start.lng(), type);
        if (!type.requiresDropoffStation()) {
            return pickup.map(found -> SegmentAvailability.of(type, found, Optional.empty()));
        }
        Mono<Optional<MobilityInfo>> dropoff = port.findNearbyDropoff(end.lat(), end.lng(), type);
        return Mono.zip(pickup, dropoff)
                .map(found -> SegmentAvailability.of(type, found.getT1(), found.getT2()));
    }
}
