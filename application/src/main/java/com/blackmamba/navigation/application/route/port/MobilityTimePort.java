package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.mobility.MobilityRouteResult;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import reactor.core.publisher.Mono;

public interface MobilityTimePort {
    Mono<MobilityRouteResult> getMobilityRoute(Location origin, Location destination, MobilityType type);
    Mono<MobilityRouteResult> getWalkingRoute(Location origin, Location destination);
}
