package com.blackmamba.navigation.api.route;

import com.blackmamba.navigation.domain.route.Route;

import java.util.List;

/** {@code GET /api/routes} 응답 봉투. */
public record RoutesResponse(List<Route> routes) {

    public static RoutesResponse from(List<Route> routes) {
        return new RoutesResponse(routes == null ? List.of() : routes);
    }
}
