package com.blackmamba.navigation.domain.route;

import java.util.ArrayList;
import java.util.List;

final class RouteCostEstimator {

    private static final List<LegPricingPolicy> PRICING_POLICIES = List.of(
            new TransitLegPricingPolicy(),
            new DdareungiLegPricingPolicy()
    );

    private RouteCostEstimator() {
    }

    static RouteCostBreakdown estimate(List<Leg> legs) {
        List<CostComponent> rawItems = legs.stream()
                .map(RouteCostEstimator::estimateComponent)
                .filter(component -> component.amountWon() > 0)
                .toList();

        List<CostComponent> mergedItems = mergeByLabel(rawItems);
        int totalWon = mergedItems.stream()
                .mapToInt(CostComponent::amountWon)
                .sum();
        return new RouteCostBreakdown(mergedItems, totalWon);
    }

    private static CostComponent estimateComponent(Leg leg) {
        return PRICING_POLICIES.stream()
                .filter(policy -> policy.supports(leg))
                .findFirst()
                .map(policy -> policy.estimate(leg))
                .orElse(new CostComponent("기타", 0));
    }

    private static List<CostComponent> mergeByLabel(List<CostComponent> items) {
        java.util.Map<String, Integer> sums = new java.util.LinkedHashMap<>();
        for (CostComponent item : items) {
            sums.merge(item.label(), item.amountWon(), Integer::sum);
        }

        List<CostComponent> merged = new ArrayList<>();
        sums.forEach((label, amountWon) -> merged.add(new CostComponent(label, amountWon)));
        return merged;
    }
}
