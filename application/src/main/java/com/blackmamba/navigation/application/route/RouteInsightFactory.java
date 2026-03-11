package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteInsights;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class RouteInsightFactory {

    private static final int ACCESS_WALK_WARNING_METERS = 300;
    private static final int TOTAL_WALK_WARNING_METERS = 800;

    public Route enrich(Route route, Route baselineRoute) {
        return route.withInsights(new RouteInsights(
                recommendationReasons(route, baselineRoute),
                riskBadges(route)
        ));
    }

    private List<String> recommendationReasons(Route route, Route baselineRoute) {
        List<String> reasons = new ArrayList<>();

        int baselineMinutes = route.comparison() != null
                ? route.comparison().originalMinutes()
                : baselineRoute != null ? baselineRoute.totalMinutes() : route.totalMinutes();
        int savedMinutes = route.comparison() != null
                ? route.comparison().savedMinutes()
                : Math.max(baselineMinutes - route.totalMinutes(), 0);

        if (savedMinutes > 0) {
            reasons.add("대중교통 대비 " + savedMinutes + "분 단축");
        }

        if (baselineRoute != null) {
            int walkDelta = walkingDistance(baselineRoute) - walkingDistance(route);
            if (walkDelta >= 150) reasons.add("도보 " + walkDelta + "m 감소");

            int transferDelta = transferCount(baselineRoute) - transferCount(route);
            if (transferDelta > 0) reasons.add("환승 " + transferDelta + "회 감소");
        }

        boolean hasBikeDropoff = route.legs().stream()
                .filter(leg -> leg.type() == LegType.BIKE)
                .anyMatch(leg -> leg.mobilityInfo() != null && leg.mobilityInfo().hasDropoffStation());
        if (hasBikeDropoff) reasons.add("반납 정류소 확인");

        boolean hasHealthyKickboard = route.legs().stream()
                .filter(leg -> leg.type() == LegType.KICKBOARD)
                .anyMatch(leg -> leg.mobilityInfo() != null && leg.mobilityInfo().batteryLevel() >= 60);
        if (hasHealthyKickboard) reasons.add("배터리 여유 확보");

        int accessWalkMeters = maxAccessWalkDistance(route);
        if (accessWalkMeters > 0 && accessWalkMeters < ACCESS_WALK_WARNING_METERS) {
            reasons.add("접근 도보 짧음");
        }

        if (reasons.isEmpty()) {
            reasons.add(route.recommended() ? "균형 잡힌 경로로 추천" : "비교 가능한 대안 경로");
        }

        return reasons.stream().limit(3).toList();
    }

    private List<String> riskBadges(Route route) {
        Set<String> badges = new LinkedHashSet<>();

        boolean hasSharedMobility = route.legs().stream()
                .filter(leg -> leg.type() == LegType.BIKE || leg.type() == LegType.KICKBOARD)
                .anyMatch(leg -> leg.mobilityInfo() != null
                        && leg.mobilityInfo().operatorName() != null
                        && !"개인".equals(leg.mobilityInfo().operatorName()));
        if (hasSharedMobility) badges.add("공유수단 의존");

        boolean lowBattery = route.legs().stream()
                .filter(leg -> leg.type() == LegType.KICKBOARD)
                .anyMatch(leg -> leg.mobilityInfo() != null && leg.mobilityInfo().batteryLevel() < 30);
        if (lowBattery) badges.add("배터리 낮음");

        boolean weakDropoff = route.legs().stream()
                .filter(leg -> leg.type() == LegType.BIKE)
                .anyMatch(leg -> leg.mobilityInfo() == null || !leg.mobilityInfo().hasDropoffStation());
        if (weakDropoff) badges.add("반납 정보 약함");

        boolean lowAvailability = route.legs().stream()
                .filter(leg -> leg.type() == LegType.BIKE)
                .anyMatch(leg -> leg.mobilityInfo() != null && leg.mobilityInfo().availableCount() <= 2);
        if (lowAvailability) badges.add("대여 여유 적음");

        if (maxAccessWalkDistance(route) >= ACCESS_WALK_WARNING_METERS) {
            badges.add("접근 도보 김");
        }

        if (walkingDistance(route) >= TOTAL_WALK_WARNING_METERS) {
            badges.add("도보 부담");
        }

        if (transferCount(route) >= 2) {
            badges.add("환승 많음");
        }

        if (badges.isEmpty()) {
            badges.add("안정적");
        }

        return badges.stream().limit(3).toList();
    }

    private int maxAccessWalkDistance(Route route) {
        int max = 0;
        List<Leg> legs = route.legs();
        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            if (leg.type() != LegType.WALK) continue;
            boolean beforeMobility = i > 0 && isMobility(legs.get(i - 1));
            boolean afterMobility = i < legs.size() - 1 && isMobility(legs.get(i + 1));
            if (beforeMobility || afterMobility) {
                max = Math.max(max, leg.distanceMeters());
            }
        }
        return max;
    }

    private boolean isMobility(Leg leg) {
        return leg.type() == LegType.BIKE || leg.type() == LegType.KICKBOARD;
    }

    private int walkingDistance(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.WALK)
                .mapToInt(Leg::distanceMeters)
                .sum();
    }

    private int transferCount(Route route) {
        long transitLegs = route.legs().stream()
                .filter(leg -> leg.type() == LegType.TRANSIT)
                .count();
        return (int) Math.max(transitLegs - 1, 0);
    }
}
