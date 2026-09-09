package com.blackmamba.navigation.domain.route;

public record GenerationDiagnostic(
        String phase,
        MobilityType mobilityType,
        String reasonCode,
        int candidateCount,
        String message
) {
}
