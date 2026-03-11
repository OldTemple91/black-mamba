package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;

final class RouteReliabilityMetrics {

    private RouteReliabilityMetrics() {
    }

    static int walkingDistance(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.WALK)
                .mapToInt(Leg::distanceMeters)
                .sum();
    }

    static int transferCount(Route route) {
        long transitLegs = route.legs().stream()
                .filter(leg -> leg.type() == LegType.TRANSIT)
                .count();
        return (int) Math.max(transitLegs - 1, 0);
    }

    static int maxAccessWalkDistance(Route route) {
        int max = 0;
        for (int i = 0; i < route.legs().size(); i++) {
            Leg leg = route.legs().get(i);
            if (leg.type() != LegType.WALK) continue;

            boolean beforeMobility = i > 0 && isMobility(route.legs().get(i - 1));
            boolean afterMobility = i < route.legs().size() - 1 && isMobility(route.legs().get(i + 1));
            if (beforeMobility || afterMobility) {
                max = Math.max(max, leg.distanceMeters());
            }
        }
        return max;
    }

    static boolean hasSharedMobility(Route route) {
        return route.legs().stream()
                .filter(RouteReliabilityMetrics::isMobility)
                .anyMatch(leg -> leg.mobilityInfo() != null
                        && leg.mobilityInfo().operatorName() != null
                        && !"개인".equals(leg.mobilityInfo().operatorName()));
    }

    static boolean hasWeakDropoff(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.BIKE)
                .anyMatch(leg -> leg.mobilityInfo() == null || !leg.mobilityInfo().hasDropoffStation());
    }

    static boolean hasLowAvailability(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.BIKE)
                .anyMatch(leg -> leg.mobilityInfo() != null && leg.mobilityInfo().availableCount() <= 2);
    }

    static boolean hasLowBattery(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.KICKBOARD)
                .anyMatch(leg -> leg.mobilityInfo() != null && leg.mobilityInfo().batteryLevel() < 30);
    }

    static boolean hasHealthyKickboard(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.KICKBOARD)
                .anyMatch(leg -> leg.mobilityInfo() != null && leg.mobilityInfo().batteryLevel() >= 60);
    }

    static boolean hasBikeDropoff(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.BIKE)
                .anyMatch(leg -> leg.mobilityInfo() != null && leg.mobilityInfo().hasDropoffStation());
    }

    private static boolean isMobility(Leg leg) {
        return leg.type() == LegType.BIKE || leg.type() == LegType.KICKBOARD;
    }
}
