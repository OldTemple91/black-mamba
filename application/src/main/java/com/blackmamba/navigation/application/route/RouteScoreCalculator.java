package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import org.springframework.stereotype.Component;

/**
 * 경로 점수 계산기.
 * 점수 공식: (timeScore×0.5) + (transferScore×0.2) + (costScore×0.2) + (effortScore×0.1)
 * 정규화: 1.0 - min(value/max, 1.0) — 낮을수록 좋은 값을 높은 점수로 변환
 * 최종 점수 범위: 0.0 ~ 1.0 (높을수록 좋음)
 */
@Component
public class RouteScoreCalculator {

    private static final double TIME_WEIGHT     = 0.5;
    private static final double TRANSFER_WEIGHT = 0.2;
    private static final double COST_WEIGHT     = 0.2;
    private static final double EFFORT_WEIGHT   = 0.1;

    private static final int MAX_EXPECTED_MINUTES   = 90;
    private static final int MAX_EXPECTED_COST      = 5000;
    private static final int MAX_EXPECTED_TRANSFERS = 5;

    public double calculate(Route route) {
        double timeScore     = 1.0 - normalize(route.totalMinutes(), MAX_EXPECTED_MINUTES);
        double transferScore = 1.0 - normalize(countTransfers(route), MAX_EXPECTED_TRANSFERS);
        double costScore     = 1.0 - normalize(route.totalCostWon(), MAX_EXPECTED_COST);
        double effortScore   = 1.0; // 추후 경사도 데이터 연동 시 개선

        return (timeScore     * TIME_WEIGHT)
             + (transferScore * TRANSFER_WEIGHT)
             + (costScore     * COST_WEIGHT)
             + (effortScore   * EFFORT_WEIGHT);
    }

    private double normalize(double value, double max) {
        return Math.min(value / max, 1.0);
    }

    private long countTransfers(Route route) {
        long transitLegs = route.legs().stream()
                .filter(leg -> leg.type() == LegType.TRANSIT)
                .count();
        return Math.max(transitLegs - 1, 0);
    }
}
