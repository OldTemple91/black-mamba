package com.blackmamba.navigation.domain.route.evaluation;

import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;

final class RouteReliabilityMetrics {

    private static final int WEAK_PICKUP_HINT_THRESHOLD_METERS = 900;
    private static final int WEAK_HUB_DETOUR_THRESHOLD_METERS = 2_000;

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

    /** 공유 수단 여부는 운영사 문자열이 아니라 수단 타입으로 판정한다 (개인 수단은 운영사명이 "개인 전기자전거" 등으로 들어온다). */
    static boolean hasSharedMobility(Route route) {
        return route.legs().stream()
                .filter(RouteReliabilityMetrics::isMobility)
                .anyMatch(leg -> leg.mobilityInfo() != null
                        && leg.mobilityInfo().mobilityType() != null
                        && leg.mobilityInfo().mobilityType().isShared());
    }

    static boolean hasWeakDropoff(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.BIKE)
                .anyMatch(leg -> leg.mobilityInfo() == null || !leg.mobilityInfo().hasDropoffStation());
    }

    /**
     * 대여 여유 부족: 따릉이 잔여 2대 이하, 또는 실데이터 없이 추정한 가용성(공유 킥보드 ESTIMATED).
     * 추정 가용성은 "그 자리에 기기가 있다" 는 보장이 없으므로 잔여 부족과 같은 실패 모드로 본다.
     */
    static boolean hasLowAvailability(Route route) {
        return route.legs().stream()
                .filter(RouteReliabilityMetrics::isMobility)
                .filter(leg -> leg.mobilityInfo() != null)
                .anyMatch(leg -> (leg.type() == LegType.BIKE && leg.mobilityInfo().availableCount() <= 2)
                        || leg.mobilityInfo().hasEstimatedAvailability());
    }

    /** 배터리 판정은 공유 킥보드에만 — 개인 수단의 배터리는 시스템이 읽은 값이 아니다. */
    static boolean hasLowBattery(Route route) {
        return route.legs().stream()
                .filter(RouteReliabilityMetrics::isSharedKickboard)
                .anyMatch(leg -> leg.mobilityInfo().batteryLevel() < 30);
    }

    static int maxPickupHintDistance(Route route) {
        return route.selectedHubs().stream()
                .map(RouteHub::metadata)
                .map(metadata -> metadata.get("pickupHintDistanceMeters"))
                .filter(java.util.Objects::nonNull)
                .mapToInt(RouteReliabilityMetrics::parseDistanceOrZero)
                .max()
                .orElse(0);
    }

    static boolean hasWeakPickupAccess(Route route) {
        return maxPickupHintDistance(route) >= WEAK_PICKUP_HINT_THRESHOLD_METERS;
    }

    static int maxHubAnchorDistance(Route route) {
        return route.selectedHubs().stream()
                .map(RouteHub::metadata)
                .map(metadata -> metadata.get("distanceToAnchorMeters"))
                .filter(java.util.Objects::nonNull)
                .mapToInt(RouteReliabilityMetrics::parseDistanceOrZero)
                .max()
                .orElse(0);
    }

    static boolean hasWeakHubDetour(Route route) {
        return maxHubAnchorDistance(route) >= WEAK_HUB_DETOUR_THRESHOLD_METERS;
    }

    static boolean hasHealthyKickboard(Route route) {
        return route.legs().stream()
                .filter(RouteReliabilityMetrics::isSharedKickboard)
                .anyMatch(leg -> leg.mobilityInfo().batteryLevel() >= 60);
    }

    private static boolean isSharedKickboard(Leg leg) {
        return leg.type() == LegType.KICKBOARD
                && leg.mobilityInfo() != null
                && leg.mobilityInfo().isSharedKickboard();
    }

    static boolean hasBikeDropoff(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.BIKE)
                .anyMatch(leg -> leg.mobilityInfo() != null && leg.mobilityInfo().hasDropoffStation());
    }

    private static boolean isMobility(Leg leg) {
        return leg.type() == LegType.BIKE || leg.type() == LegType.KICKBOARD;
    }

    private static int parseDistanceOrZero(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException _) {
            return 0;
        }
    }
}
