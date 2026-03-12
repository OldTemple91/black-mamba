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
        int walkingDistanceMeters,
        int transferCount,
        int maxAccessWalkDistanceMeters,
        boolean sharedMobilityDependent,
        boolean weakDropoff,
        boolean lowAvailability,
        boolean lowBattery,
        List<RouteHub> hubs
) {
    public RouteEvaluation {
        hubs = List.copyOf(hubs);
    }
}
