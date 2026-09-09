package com.blackmamba.navigation.domain.route.evaluation;

import com.blackmamba.navigation.domain.route.mobility.MobilityType;

public record GenerationDiagnostic(
        String phase,
        MobilityType mobilityType,
        String reasonCode,
        int candidateCount,
        String message
) {
}
