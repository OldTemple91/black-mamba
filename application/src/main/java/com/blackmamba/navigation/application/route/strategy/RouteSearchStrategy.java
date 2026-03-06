package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Route;
import reactor.core.publisher.Mono;

import java.util.List;

public interface RouteSearchStrategy {
    Mono<List<Route>> search(Location origin, Location destination);
}
