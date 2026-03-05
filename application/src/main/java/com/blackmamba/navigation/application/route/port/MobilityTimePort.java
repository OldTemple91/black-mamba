package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import reactor.core.publisher.Mono;

public interface MobilityTimePort {
    Mono<Integer> getMobilityTimeMinutes(Location origin, Location destination, MobilityType type);
}
