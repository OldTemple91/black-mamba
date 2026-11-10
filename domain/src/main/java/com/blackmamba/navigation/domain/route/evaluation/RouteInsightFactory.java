package com.blackmamba.navigation.domain.route.evaluation;

import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.mobility.MobilityInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class RouteInsightFactory {

    private static final int ACCESS_WALK_WARNING_METERS_RELIABILITY = 300;
    private static final int ACCESS_WALK_WARNING_METERS_TIME_PRIORITY = 420;
    private static final int TOTAL_WALK_WARNING_METERS = 800;

    public Route enrich(Route route, Route baselineRoute) {
        return enrich(route, baselineRoute, RecommendationPreference.RELIABILITY);
    }

    public Route enrich(Route route, Route baselineRoute, RecommendationPreference preference) {
        List<GenerationDiagnostic> generationDiagnostics = route.insights() != null
                ? route.insights().generationDiagnostics()
                : List.of();
        List<String> fallbackDiagnostics = route.insights() != null
                ? route.insights().fallbackDiagnostics()
                : List.of();
        return route.withInsights(new RouteInsights(
                recommendationReasons(route, baselineRoute, preference),
                riskBadges(route, preference),
                generationDiagnostics,
                mergeFallbackDiagnostics(route, fallbackDiagnostics)
        ));
    }

    private List<String> recommendationReasons(Route route, Route baselineRoute, RecommendationPreference preference) {
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

        // 배터리 낮음 배지와 양립 방지: lowBattery가 없을 때만 긍정 표시
        if (RouteReliabilityMetrics.hasHealthyKickboard(route)
                && !RouteReliabilityMetrics.hasLowBattery(route)) {
            reasons.add("배터리 여유 확보");
        }

        int accessWalkMeters = RouteReliabilityMetrics.maxAccessWalkDistance(route);
        int accessWalkThreshold = preference == RecommendationPreference.TIME_PRIORITY
                ? ACCESS_WALK_WARNING_METERS_TIME_PRIORITY
                : ACCESS_WALK_WARNING_METERS_RELIABILITY;
        if (accessWalkMeters > 0 && accessWalkMeters < accessWalkThreshold) {
            reasons.add("접근 도보 짧음");
        }

        if (reasons.isEmpty()) {
            reasons.add(route.recommended() ? "균형 잡힌 경로로 추천" : "비교 가능한 대안 경로");
        }

        return reasons.stream().limit(3).toList();
    }

    private List<String> riskBadges(Route route, RecommendationPreference preference) {
        Set<String> badges = new LinkedHashSet<>();

        if (RouteReliabilityMetrics.hasSharedMobility(route)) badges.add("공유수단 의존");
        if (RouteReliabilityMetrics.hasLowBattery(route)) badges.add("배터리 낮음");
        if (RouteReliabilityMetrics.hasWeakDropoff(route)) badges.add("반납 정보 약함");
        if (RouteReliabilityMetrics.hasLowAvailability(route)) badges.add("대여 여유 적음");
        if (RouteReliabilityMetrics.hasWeakPickupAccess(route)) badges.add("대여소 접근 김");
        if (RouteReliabilityMetrics.hasWeakHubDetour(route)) badges.add("허브 우회 큼");

        int accessWalkThreshold = preference == RecommendationPreference.TIME_PRIORITY
                ? ACCESS_WALK_WARNING_METERS_TIME_PRIORITY
                : ACCESS_WALK_WARNING_METERS_RELIABILITY;
        if (RouteReliabilityMetrics.maxAccessWalkDistance(route) >= accessWalkThreshold) {
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
                .anyMatch(leg -> leg.isWalk()
                        && leg.distanceMeters() > 0
                        && (leg.routeCoordinates() == null || leg.routeCoordinates().isEmpty()));
        if (walkingFallback) {
            diagnostics.add("일부 도보 구간은 TMAP 대신 직선거리 기반 추정으로 계산되었습니다.");
        }

        boolean mobilityFallback = route.legs().stream()
                .anyMatch(leg -> leg.isRiding()
                        && leg.distanceMeters() > 0
                        && (leg.routeCoordinates() == null || leg.routeCoordinates().isEmpty()));
        if (mobilityFallback) {
            diagnostics.add("일부 이동수단 구간은 TMAP 대신 직선거리 기반 추정으로 계산되었습니다.");
        }

        boolean transitEstimated = route.legs().stream().anyMatch(Leg::isEstimatedTransit);
        if (transitEstimated) {
            diagnostics.add("일부 대중교통 구간은 실시간 경로 조회 대신 기준 경로 기반 추정값(소요시간·요금)입니다.");
        }

        List<MobilityInfo> mobilityInfos = route.legs().stream()
                .map(Leg::mobilityInfo)
                .filter(Objects::nonNull)
                .toList();

        boolean ddareungiStale = mobilityInfos.stream()
                .anyMatch(info -> info.isDdareungi() && info.hasStaleAvailability());
        if (ddareungiStale) {
            diagnostics.add("따릉이 정류소 정보는 최근 snapshot 재사용 결과입니다.");
        }

        boolean ddareungiEmpty = mobilityInfos.stream()
                .anyMatch(info -> info.isDdareungi() && info.hasEmptyAvailability());
        if (ddareungiEmpty) {
            diagnostics.add("따릉이 실시간 정류소 정보를 확보하지 못해 빈 결과 기준으로 계산되었습니다.");
        }

        boolean kickboardEstimated = mobilityInfos.stream()
                .anyMatch(info -> info.isSharedKickboard() && info.hasEstimatedAvailability());
        if (kickboardEstimated) {
            diagnostics.add("킥보드 가용성은 실시간 데이터 대신 추정값을 사용했습니다.");
        }

        return diagnostics.stream().limit(4).toList();
    }
}
