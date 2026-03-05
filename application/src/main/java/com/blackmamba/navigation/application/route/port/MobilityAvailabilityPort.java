package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.route.MobilityInfo;
import com.blackmamba.navigation.domain.route.MobilityType;
import reactor.core.publisher.Mono;

import java.util.Optional;

public interface MobilityAvailabilityPort {
    Mono<Optional<MobilityInfo>> findNearbyMobility(double lat, double lng, MobilityType type);
}
