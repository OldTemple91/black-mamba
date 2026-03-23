package com.blackmamba.navigation.domain.route;

import java.util.List;

public record RouteEvaluation(
        double timeScore,
        double transferScore,
        double costScore,
        double walkingScore,
        double accessWalkScore,
        double reliabilityScore,
        double totalScore,
        String preferenceMode,  // "RELIABILITY" or "TIME_PRIORITY" — 어떤 기준으로 평가했는지 추적
        int walkingDistanceMeters,
        int transferCount,
        int maxAccessWalkDistanceMeters,
        boolean sharedMobilityDependent,
        boolean weakDropoff,
        boolean lowAvailability,
        boolean lowBattery,
        boolean weakPickupAccess,
        int maxPickupHintDistanceMeters,
        boolean weakHubDetour,
        int maxHubAnchorDistanceMeters,
        List<RouteHub> hubs
) {
    public RouteEvaluation {
        hubs = List.copyOf(hubs);
    }
}
