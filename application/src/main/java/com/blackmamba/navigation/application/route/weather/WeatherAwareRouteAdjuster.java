package com.blackmamba.navigation.application.route.weather;

import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.evaluation.RouteInsights;
import com.blackmamba.navigation.domain.weather.WeatherCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A-4: 날씨에 따라 경로 점수를 재조정하고 순위를 다시 매긴다.
 *
 * <p>설계 원칙 — 접근성 후처리와 동일:
 * <ul>
 *   <li>탐색 알고리즘에 침투하지 않고 <b>결과 집합을 후처리</b></li>
 *   <li>옵션 추가/제거가 다른 로직에 0 영향</li>
 *   <li>WeatherContext 가 CLEAR 이면 원본 그대로 통과</li>
 *   <li>추천 플래그는 여기서 건드리지 않는다 — 파이프라인이 최종 순서 기준으로 다시 매긴다</li>
 * </ul>
 *
 * <h3>페널티 정책</h3>
 * <pre>
 *   RAIN / SNOW (악천후):
 *     - 자전거/킥보드 탑승 포함 경로: score × 0.85 (RAIN), × 0.70 (SNOW)
 *     - 장거리 도보 (300m+): score × 0.90
 *
 *   HEAT (35℃+) / COLD (-5℃-):
 *     - 장거리 도보 또는 자전거/킥보드 탑승: score × 0.92
 *     - 대중교통: 무변화
 * </pre>
 */
@Component
public class WeatherAwareRouteAdjuster {

    private static final Logger log = LoggerFactory.getLogger(WeatherAwareRouteAdjuster.class);

    /** 장거리 도보 기준 (이 이상이면 날씨 페널티 대상). */
    private static final int LONG_WALK_THRESHOLD_METERS = 300;
    private static final double RAIN_RIDING_FACTOR = 0.85;
    private static final double SNOW_RIDING_FACTOR = 0.70;
    private static final double SEVERE_WEATHER_WALK_FACTOR = 0.90;
    private static final double EXTREME_TEMPERATURE_FACTOR = 0.92;

    public List<Route> apply(List<Route> routes, WeatherContext weather) {
        if (routes == null || routes.isEmpty()) return routes;
        if (weather == null || !weather.hasImpact()) return routes;

        WeatherCondition condition = weather.condition();
        log.debug("[Weather] 날씨 조정 적용: condition={}, routes={}개", condition, routes.size());

        return routes.stream()
                .map(route -> adjustScore(route, condition))
                .sorted(Comparator.comparingDouble(Route::score).reversed())
                .toList();
    }

    /**
     * 날씨로 감점된 경로에 그 사유를 리스크 배지로 붙인다.
     * 점수만 깎고 설명이 없으면 "왜 자전거 경로가 밀렸는지" 를 사용자가 알 수 없고, 비 오는 날에도 "안정적" 배지만 보인다.
     * 추천 재정렬이 배지를 다시 만들 수 있으므로 파이프라인에서 그 뒤에 호출한다.
     */
    public List<Route> annotate(List<Route> routes, WeatherContext weather) {
        if (routes == null || routes.isEmpty()) return routes;
        if (weather == null || !weather.hasImpact()) return routes;

        WeatherCondition condition = weather.condition();
        return routes.stream()
                .map(route -> withWeatherBadge(route, condition))
                .toList();
    }

    private Route withWeatherBadge(Route route, WeatherCondition condition) {
        if (computePenaltyFactor(route, condition) >= 1.0) return route;

        String badge = weatherBadge(condition, hasRidingLeg(route));
        RouteInsights insights = route.insights();
        if (insights == null) {
            return route.withInsights(new RouteInsights(List.of(), List.of(badge), List.of(), List.of()));
        }
        if (insights.riskBadges().contains(badge)) return route;

        List<String> badges = new ArrayList<>(insights.riskBadges());
        badges.addFirst(badge);
        return route.withInsights(new RouteInsights(insights.recommendationReasons(), badges,
                insights.generationDiagnostics(), insights.fallbackDiagnostics()));
    }

    private static String weatherBadge(WeatherCondition condition, boolean hasRiding) {
        String target = "긴 도보 구간";
        if (hasRiding) {
            target = "자전거·킥보드 구간";
        }
        return switch (condition) {
            case RAIN -> "비 예보 — " + target + " 감점";
            case SNOW -> "눈 예보 — " + target + " 감점";
            case HEAT -> "폭염 — 야외 이동 구간 감점";
            case COLD -> "한파 — 야외 이동 구간 감점";
            case CLEAR, UNKNOWN -> "";
        };
    }

    private Route adjustScore(Route route, WeatherCondition condition) {
        double penaltyFactor = computePenaltyFactor(route, condition);
        if (penaltyFactor >= 1.0) return route;

        double newScore = route.score() * penaltyFactor;
        log.debug("[Weather] routeId={} score {} → {} (×{})",
                route.routeId(), route.score(), newScore, penaltyFactor);

        return route.withScore(newScore, route.recommended());
    }

    /**
     * 페널티 계수 계산. 1.0 = 영향 없음, < 1.0 = 감점.
     * 여러 페널티가 겹치면 곱해서 누적 (완화하지 않음).
     */
    private double computePenaltyFactor(Route route, WeatherCondition condition) {
        double factor = 1.0;
        boolean hasRiding = hasRidingLeg(route);
        boolean hasLongWalk = hasLongWalk(route);

        if (condition.penalizesSharedMobility()) {
            // 악천후 — 노면이 미끄러운 자전거/킥보드 탑승 감점, 장거리 도보 감점
            if (hasRiding) {
                factor *= (condition == WeatherCondition.SNOW) ? SNOW_RIDING_FACTOR : RAIN_RIDING_FACTOR;
            }
            if (hasLongWalk) {
                factor *= SEVERE_WEATHER_WALK_FACTOR;
            }
            return factor;
        }

        if (condition.penalizesWalking() && (hasLongWalk || hasRiding)) {
            // 극한 기온 — 야외 노출 시간이 긴 도보·탑승 감점
            factor *= EXTREME_TEMPERATURE_FACTOR;
        }
        return factor;
    }

    private boolean hasRidingLeg(Route route) {
        return route.legs().stream()
                .anyMatch(leg -> leg.type() == LegType.BIKE || leg.type() == LegType.KICKBOARD);
    }

    private boolean hasLongWalk(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.WALK)
                .mapToInt(Leg::distanceMeters)
                .sum() >= LONG_WALK_THRESHOLD_METERS;
    }
}
