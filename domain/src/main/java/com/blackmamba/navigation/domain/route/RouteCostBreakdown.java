package com.blackmamba.navigation.domain.route;

import java.util.List;

public record RouteCostBreakdown(
        List<CostComponent> items,
        int totalWon
) {
    public RouteCostBreakdown {
        items = List.copyOf(items);
    }
}
