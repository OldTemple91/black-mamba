package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.weather.WeatherCondition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * A-4: 날씨에 따라 경로 점수를 재조정하고 순위를 다시 매긴다.
 *
 * <p>설계 원칙 — {@link AccessibilityPostProcessor} 와 동일:
 * <ul>
 *   <li>탐색 알고리즘에 침투하지 않고 <b>결과 집합을 후처리</b></li>
 *   <li>옵션 추가/제거가 다른 로직에 0 영향</li>
 *   <li>WeatherContext 가 CLEAR 이면 원본 그대로 통과</li>
 * </ul>
 *
 * <h3>페널티 정책</h3>
 * <pre>
 *   RAIN / SNOW (악천후):
 *     - 공유 자전거/킥보드 포함 경로: score × 0.85 (RAIN), × 0.70 (SNOW)
 *     - 장거리 도보 (300m+): score × 0.90
 *
 *   HEAT (35℃+) / COLD (-5℃-):
 *     - 장거리 도보/자전거: score × 0.92
 *     - 대중교통: 무변화
 * </pre>
 */
@Component
public class WeatherAwareRouteAdjuster {

    private static final Logger log = LoggerFactory.getLogger(WeatherAwareRouteAdjuster.class);

    /** 장거리 도보 기준 (이 이상이면 날씨 페널티 대상). */
    private static final int LONG_WALK_THRESHOLD_METERS = 300;

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

    private Route adjustScore(Route route, WeatherCondition condition) {
        double penaltyFactor = computePenaltyFactor(route, condition);
        if (penaltyFactor >= 1.0) return route;

        double newScore = route.score() * penaltyFactor;
        log.debug("[Weather] routeId={} score {} → {} (×{})",
                route.routeId(), route.score(), newScore, penaltyFactor);

        // recommended flag 는 이후 re-sort 에서 top1 만 유지되는 계약은 아님 — 점수만 재조정하고 정렬.
        return route.withScore(newScore, route.recommended());
    }

    /**
     * 페널티 계수 계산. 1.0 = 영향 없음, < 1.0 = 감점.
     * 여러 페널티가 겹치면 곱해서 누적 (완화하지 않음).
     */
    private double computePenaltyFactor(Route route, WeatherCondition condition) {
        double factor = 1.0;

        boolean hasSharedMobility = route.legs().stream()
                .anyMatch(leg -> leg.type() == LegType.BIKE || leg.type() == LegType.KICKBOARD);

        // 악천후 — 공유 이동수단 감점
        if (condition.penalizesSharedMobility() && hasSharedMobility) {
            factor *= (condition == WeatherCondition.SNOW) ? 0.70 : 0.85;
        }

        // 장거리 도보 감점 (악천후 + 극한 기온)
        if (condition.penalizesWalking() && hasLongWalk(route)) {
            factor *= 0.92;
        }

        return factor;
    }

    private boolean hasLongWalk(Route route) {
        return route.legs().stream()
                .filter(leg -> leg.type() == LegType.WALK)
                .mapToInt(Leg::distanceMeters)
                .sum() >= LONG_WALK_THRESHOLD_METERS;
    }
}
