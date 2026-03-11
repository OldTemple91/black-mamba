package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.Route;
import org.springframework.stereotype.Component;

/**
 * 경로 점수 계산기.
 * 점수 공식:
 * (time×0.40) + (transfer×0.15) + (cost×0.10) + (walk×0.10) + (accessWalk×0.10) + (reliability×0.15)
 * 정규화: 1.0 - min(value/max, 1.0) — 낮을수록 좋은 값을 높은 점수로 변환
 * 최종 점수 범위: 0.0 ~ 1.0 (높을수록 좋음)
 */
@Component
public class RouteScoreCalculator {

    private static final double TIME_WEIGHT        = 0.40;
    private static final double TRANSFER_WEIGHT    = 0.15;
    private static final double COST_WEIGHT        = 0.10;
    private static final double WALK_WEIGHT        = 0.10;
    private static final double ACCESS_WALK_WEIGHT = 0.10;
    private static final double RELIABILITY_WEIGHT = 0.15;

    private static final int MAX_EXPECTED_MINUTES   = 90;
    private static final int MAX_EXPECTED_COST      = 5000;
    private static final int MAX_EXPECTED_TRANSFERS = 5;
    private static final int MAX_EXPECTED_WALK_METERS = 1500;
    private static final int MAX_EXPECTED_ACCESS_WALK_METERS = 600;

    public double calculate(Route route) {
        double timeScore        = 1.0 - normalize(route.totalMinutes(), MAX_EXPECTED_MINUTES);
        double transferScore    = 1.0 - normalize(RouteReliabilityMetrics.transferCount(route), MAX_EXPECTED_TRANSFERS);
        double costScore        = 1.0 - normalize(route.totalCostWon(), MAX_EXPECTED_COST);
        double walkingScore     = 1.0 - normalize(RouteReliabilityMetrics.walkingDistance(route), MAX_EXPECTED_WALK_METERS);
        double accessWalkScore  = 1.0 - normalize(RouteReliabilityMetrics.maxAccessWalkDistance(route), MAX_EXPECTED_ACCESS_WALK_METERS);
        double reliabilityScore = reliabilityScore(route);

        return (timeScore        * TIME_WEIGHT)
             + (transferScore    * TRANSFER_WEIGHT)
             + (costScore        * COST_WEIGHT)
             + (walkingScore     * WALK_WEIGHT)
             + (accessWalkScore  * ACCESS_WALK_WEIGHT)
             + (reliabilityScore * RELIABILITY_WEIGHT);
    }

    private double normalize(double value, double max) {
        return Math.min(value / max, 1.0);
    }

    private double reliabilityScore(Route route) {
        double score = 1.0;

        if (RouteReliabilityMetrics.hasWeakDropoff(route)) score -= 0.35;
        if (RouteReliabilityMetrics.hasLowAvailability(route)) score -= 0.15;
        if (RouteReliabilityMetrics.hasSharedMobility(route)) score -= 0.10;
        if (RouteReliabilityMetrics.hasLowBattery(route)) score -= 0.15;
        if (RouteReliabilityMetrics.maxAccessWalkDistance(route) >= 300) score -= 0.15;

        return Math.max(score, 0.0);
    }
}
