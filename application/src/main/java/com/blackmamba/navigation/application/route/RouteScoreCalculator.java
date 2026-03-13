package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteEvaluation;
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
    private static final WeightProfile RELIABILITY_PROFILE = new WeightProfile(
            0.40, 0.15, 0.10, 0.10, 0.10, 0.15,
            new ReliabilityPenaltyProfile(0.35, 0.15, 0.10, 0.15, 300)
    );
    private static final WeightProfile TIME_PRIORITY_PROFILE = new WeightProfile(
            0.72, 0.08, 0.03, 0.03, 0.02, 0.12,
            new ReliabilityPenaltyProfile(0.20, 0.08, 0.04, 0.08, 420)
    );

    private static final int MAX_EXPECTED_MINUTES   = 90;
    private static final int MAX_EXPECTED_COST      = 5000;
    private static final int MAX_EXPECTED_TRANSFERS = 5;
    private static final int MAX_EXPECTED_WALK_METERS = 1500;
    private static final int MAX_EXPECTED_ACCESS_WALK_METERS = 600;

    public double calculate(Route route) {
        return calculate(route, RecommendationPreference.RELIABILITY);
    }

    public double calculate(Route route, RecommendationPreference preference) {
        return evaluate(route, preference).totalScore();
    }

    public RouteEvaluation evaluate(Route route) {
        return evaluate(route, RecommendationPreference.RELIABILITY);
    }

    public RouteEvaluation evaluate(Route route, RecommendationPreference preference) {
        double timeScore        = 1.0 - normalize(route.totalMinutes(), MAX_EXPECTED_MINUTES);
        int transferCount       = RouteReliabilityMetrics.transferCount(route);
        int walkingDistance     = RouteReliabilityMetrics.walkingDistance(route);
        int accessWalkDistance  = RouteReliabilityMetrics.maxAccessWalkDistance(route);
        boolean sharedMobility  = RouteReliabilityMetrics.hasSharedMobility(route);
        boolean weakDropoff     = RouteReliabilityMetrics.hasWeakDropoff(route);
        boolean lowAvailability = RouteReliabilityMetrics.hasLowAvailability(route);
        boolean lowBattery      = RouteReliabilityMetrics.hasLowBattery(route);

        double transferScore    = 1.0 - normalize(transferCount, MAX_EXPECTED_TRANSFERS);
        double costScore        = 1.0 - normalize(route.totalCostWon(), MAX_EXPECTED_COST);
        double walkingScore     = 1.0 - normalize(walkingDistance, MAX_EXPECTED_WALK_METERS);
        double accessWalkScore  = 1.0 - normalize(accessWalkDistance, MAX_EXPECTED_ACCESS_WALK_METERS);
        WeightProfile weightProfile = profileFor(preference);
        double reliabilityScore = reliabilityScore(
                sharedMobility,
                weakDropoff,
                lowAvailability,
                lowBattery,
                accessWalkDistance,
                weightProfile.reliabilityPenaltyProfile()
        );
        double totalScore = (timeScore        * weightProfile.timeWeight())
                + (transferScore    * weightProfile.transferWeight())
                + (costScore        * weightProfile.costWeight())
                + (walkingScore     * weightProfile.walkWeight())
                + (accessWalkScore  * weightProfile.accessWalkWeight())
                + (reliabilityScore * weightProfile.reliabilityWeight());

        return new RouteEvaluation(
                timeScore,
                transferScore,
                costScore,
                walkingScore,
                accessWalkScore,
                reliabilityScore,
                totalScore,
                walkingDistance,
                transferCount,
                accessWalkDistance,
                sharedMobility,
                weakDropoff,
                lowAvailability,
                lowBattery,
                RouteHubExtractor.extract(route)
        );
    }

    private double normalize(double value, double max) {
        return Math.min(value / max, 1.0);
    }

    private WeightProfile profileFor(RecommendationPreference preference) {
        return switch (preference) {
            case TIME_PRIORITY -> TIME_PRIORITY_PROFILE;
            case RELIABILITY -> RELIABILITY_PROFILE;
        };
    }

    private double reliabilityScore(boolean sharedMobility,
                                    boolean weakDropoff,
                                    boolean lowAvailability,
                                    boolean lowBattery,
                                    int accessWalkDistance,
                                    ReliabilityPenaltyProfile penaltyProfile) {
        double score = 1.0;

        if (weakDropoff) score -= penaltyProfile.weakDropoffPenalty();
        if (lowAvailability) score -= penaltyProfile.lowAvailabilityPenalty();
        if (sharedMobility) score -= penaltyProfile.sharedMobilityPenalty();
        if (lowBattery) score -= penaltyProfile.lowBatteryPenalty();
        if (accessWalkDistance >= penaltyProfile.accessWalkPenaltyThresholdMeters()) {
            score -= penaltyProfile.accessWalkPenalty();
        }

        return Math.max(score, 0.0);
    }

    private record WeightProfile(
            double timeWeight,
            double transferWeight,
            double costWeight,
            double walkWeight,
            double accessWalkWeight,
            double reliabilityWeight,
            ReliabilityPenaltyProfile reliabilityPenaltyProfile
    ) {
    }

    private record ReliabilityPenaltyProfile(
            double weakDropoffPenalty,
            double lowAvailabilityPenalty,
            double sharedMobilityPenalty,
            double accessWalkPenalty,
            int accessWalkPenaltyThresholdMeters
    ) {
        private double lowBatteryPenalty() {
            return lowAvailabilityPenalty;
        }
    }
}
