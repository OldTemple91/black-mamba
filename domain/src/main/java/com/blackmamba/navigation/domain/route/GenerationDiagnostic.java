package com.blackmamba.navigation.domain.route;

public record GenerationDiagnostic(
        String phase,
        String mobilityType,
        String reasonCode,
        int candidateCount,
        String message
) {
}
