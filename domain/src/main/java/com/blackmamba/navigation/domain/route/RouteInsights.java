package com.blackmamba.navigation.domain.route;

import java.util.List;

public record RouteInsights(
        List<String> recommendationReasons,
        List<String> riskBadges
) {
    public RouteInsights {
        recommendationReasons = recommendationReasons == null ? List.of() : List.copyOf(recommendationReasons);
        riskBadges = riskBadges == null ? List.of() : List.copyOf(riskBadges);
    }
}
