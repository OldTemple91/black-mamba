package com.blackmamba.navigation.domain.route;

import java.util.List;

public record RouteInsights(
        List<String> recommendationReasons,
        List<String> riskBadges,
        List<GenerationDiagnostic> generationDiagnostics,
        List<String> fallbackDiagnostics
) {
    public RouteInsights {
        recommendationReasons = recommendationReasons == null ? List.of() : List.copyOf(recommendationReasons);
        riskBadges = riskBadges == null ? List.of() : List.copyOf(riskBadges);
        generationDiagnostics = generationDiagnostics == null ? List.of() : List.copyOf(generationDiagnostics);
        fallbackDiagnostics = fallbackDiagnostics == null ? List.of() : List.copyOf(fallbackDiagnostics);
    }
}
