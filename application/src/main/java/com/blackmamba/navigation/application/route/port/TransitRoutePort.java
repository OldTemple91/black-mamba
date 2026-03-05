package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import reactor.core.publisher.Mono;

import java.util.List;

public interface TransitRoutePort {
    Mono<List<Leg>> getTransitRoute(Location origin, Location destination);
    Mono<Integer> getTransitTimeMinutes(Location origin, Location destination);
}
