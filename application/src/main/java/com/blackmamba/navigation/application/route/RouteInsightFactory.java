package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.GenerationDiagnostic;
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
        List<GenerationDiagnostic> generationDiagnostics = route.insights() != null
                ? route.insights().generationDiagnostics()
                : List.of();
        List<String> fallbackDiagnostics = route.insights() != null
                ? route.insights().fallbackDiagnostics()
                : List.of();
        return route.withInsights(new RouteInsights(
                recommendationReasons(route, baselineRoute),
                riskBadges(route),
                generationDiagnostics,
                mergeFallbackDiagnostics(route, fallbackDiagnostics)
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
            int walkDelta = RouteReliabilityMetrics.walkingDistance(baselineRoute) - RouteReliabilityMetrics.walkingDistance(route);
            if (walkDelta >= 150) reasons.add("도보 " + walkDelta + "m 감소");

            int transferDelta = RouteReliabilityMetrics.transferCount(baselineRoute) - RouteReliabilityMetrics.transferCount(route);
            if (transferDelta > 0) reasons.add("환승 " + transferDelta + "회 감소");
        }

        if (RouteReliabilityMetrics.hasBikeDropoff(route)) reasons.add("반납 정류소 확인");

        if (RouteReliabilityMetrics.hasHealthyKickboard(route)) reasons.add("배터리 여유 확보");

        int accessWalkMeters = RouteReliabilityMetrics.maxAccessWalkDistance(route);
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

        if (RouteReliabilityMetrics.hasSharedMobility(route)) badges.add("공유수단 의존");
        if (RouteReliabilityMetrics.hasLowBattery(route)) badges.add("배터리 낮음");
        if (RouteReliabilityMetrics.hasWeakDropoff(route)) badges.add("반납 정보 약함");
        if (RouteReliabilityMetrics.hasLowAvailability(route)) badges.add("대여 여유 적음");

        if (RouteReliabilityMetrics.maxAccessWalkDistance(route) >= ACCESS_WALK_WARNING_METERS) {
            badges.add("접근 도보 김");
        }

        if (RouteReliabilityMetrics.walkingDistance(route) >= TOTAL_WALK_WARNING_METERS) {
            badges.add("도보 부담");
        }

        if (RouteReliabilityMetrics.transferCount(route) >= 2) {
            badges.add("환승 많음");
        }

        if (badges.isEmpty()) {
            badges.add("안정적");
        }

        return badges.stream().limit(3).toList();
    }

    private List<String> mergeFallbackDiagnostics(Route route, List<String> existingDiagnostics) {
        Set<String> diagnostics = new LinkedHashSet<>(existingDiagnostics);

        boolean walkingFallback = route.legs().stream()
                .anyMatch(leg -> leg.type() == LegType.WALK
                        && leg.distanceMeters() > 0
                        && (leg.routeCoordinates() == null || leg.routeCoordinates().isEmpty()));
        if (walkingFallback) {
            diagnostics.add("일부 도보 구간은 TMAP 대신 직선거리 기반 추정으로 계산되었습니다.");
        }

        boolean mobilityFallback = route.legs().stream()
                .anyMatch(leg -> (leg.type() == LegType.BIKE || leg.type() == LegType.KICKBOARD)
                        && leg.distanceMeters() > 0
                        && (leg.routeCoordinates() == null || leg.routeCoordinates().isEmpty()));
        if (mobilityFallback) {
            diagnostics.add("일부 이동수단 구간은 TMAP 대신 직선거리 기반 추정으로 계산되었습니다.");
        }

        boolean ddareungiStale = route.legs().stream()
                .map(Leg::mobilityInfo)
                .filter(java.util.Objects::nonNull)
                .anyMatch(info -> info.mobilityType() == com.blackmamba.navigation.domain.route.MobilityType.DDAREUNGI
                        && "STALE".equals(info.availabilitySource()));
        if (ddareungiStale) {
            diagnostics.add("따릉이 정류소 정보는 최근 snapshot 재사용 결과입니다.");
        }

        boolean ddareungiEmpty = route.legs().stream()
                .map(Leg::mobilityInfo)
                .filter(java.util.Objects::nonNull)
                .anyMatch(info -> info.mobilityType() == com.blackmamba.navigation.domain.route.MobilityType.DDAREUNGI
                        && "EMPTY".equals(info.availabilitySource()));
        if (ddareungiEmpty) {
            diagnostics.add("따릉이 실시간 정류소 정보를 확보하지 못해 빈 결과 기준으로 계산되었습니다.");
        }

        boolean kickboardEstimated = route.legs().stream()
                .map(Leg::mobilityInfo)
                .filter(java.util.Objects::nonNull)
                .anyMatch(info -> info.mobilityType() == com.blackmamba.navigation.domain.route.MobilityType.KICKBOARD_SHARED
                        && "ESTIMATED".equals(info.availabilitySource()));
        if (kickboardEstimated) {
            diagnostics.add("킥보드 가용성은 실시간 데이터 대신 추정값을 사용했습니다.");
        }

        return diagnostics.stream().limit(4).toList();
    }
}
