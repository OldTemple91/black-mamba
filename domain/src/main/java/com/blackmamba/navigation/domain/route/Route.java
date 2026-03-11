package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;

import java.util.ArrayList;
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
        Comparison comparison,
        RouteInsights insights
) {
    // compact constructor: 모든 생성 경로에서 불변 컬렉션 강제
    public Route {
        legs = List.copyOf(normalizeLegs(legs));
    }

    public static Route of(List<Leg> legs, RouteType type) {
        int total = legs.stream().mapToInt(Leg::durationMinutes).sum();
        return new Route(
                UUID.randomUUID().toString(),
                type, total, 0, 0.0, false, legs, null, null
        );
    }

    public Route withComparison(Comparison comparison) {
        return new Route(routeId, type, totalMinutes, totalCostWon, score, recommended, legs, comparison, insights);
    }

    public Route withScore(double score, boolean recommended) {
        return new Route(routeId, type, totalMinutes, totalCostWon, score, recommended, legs, comparison, insights);
    }

    public Route withInsights(RouteInsights insights) {
        return new Route(routeId, type, totalMinutes, totalCostWon, score, recommended, legs, comparison, insights);
    }

    private static List<Leg> normalizeLegs(List<Leg> legs) {
        List<Leg> normalized = new ArrayList<>();
        for (Leg leg : legs) {
            if (!normalized.isEmpty()
                    && normalized.getLast().type() == LegType.WALK
                    && leg.type() == LegType.WALK) {
                normalized.set(normalized.size() - 1, mergeWalkLegs(normalized.getLast(), leg));
                continue;
            }
            normalized.add(leg);
        }
        return normalized;
    }

    private static Leg mergeWalkLegs(Leg first, Leg second) {
        List<Location> mergedCoordinates = new ArrayList<>();
        if (first.routeCoordinates() != null) mergedCoordinates.addAll(first.routeCoordinates());
        if (second.routeCoordinates() != null) {
            if (!mergedCoordinates.isEmpty() && !second.routeCoordinates().isEmpty()) {
                Location last = mergedCoordinates.getLast();
                Location next = second.routeCoordinates().getFirst();
                if (samePoint(last, next)) {
                    mergedCoordinates.addAll(second.routeCoordinates().subList(1, second.routeCoordinates().size()));
                } else {
                    mergedCoordinates.addAll(second.routeCoordinates());
                }
            } else {
                mergedCoordinates.addAll(second.routeCoordinates());
            }
        }

        return new Leg(
                LegType.WALK,
                "WALK",
                first.durationMinutes() + second.durationMinutes(),
                first.distanceMeters() + second.distanceMeters(),
                first.start(),
                second.end(),
                null,
                null,
                mergedCoordinates.isEmpty() ? null : List.copyOf(mergedCoordinates)
        );
    }

    private static boolean samePoint(Location a, Location b) {
        return a != null && b != null
                && Double.compare(a.lat(), b.lat()) == 0
                && Double.compare(a.lng(), b.lng()) == 0;
    }
}
