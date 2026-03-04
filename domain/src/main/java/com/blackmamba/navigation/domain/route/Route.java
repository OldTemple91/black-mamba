package com.blackmamba.navigation.domain.route;

import java.util.List;
import java.util.UUID;

public record Route(
        String routeId,
        RouteType type,
        int totalMinutes,
        int totalCostWon,
        double score,
        boolean recommended,
        List<Leg> legs,
        Comparison comparison
) {
    public static Route of(List<Leg> legs, RouteType type) {
        int total = legs.stream().mapToInt(Leg::durationMinutes).sum();
        return new Route(
                UUID.randomUUID().toString(),
                type, total, 0, 0.0, false, legs, null
        );
    }

    public Route withComparison(Comparison comparison) {
        return new Route(routeId, type, totalMinutes, totalCostWon, score, recommended, legs, comparison);
    }

    public Route withScore(double score, boolean recommended) {
        return new Route(routeId, type, totalMinutes, totalCostWon, score, recommended, legs, comparison);
    }
}
