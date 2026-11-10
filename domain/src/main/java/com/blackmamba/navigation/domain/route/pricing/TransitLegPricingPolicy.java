package com.blackmamba.navigation.domain.route.pricing;

import com.blackmamba.navigation.domain.route.Leg;

final class TransitLegPricingPolicy implements LegPricingPolicy {

    @Override
    public boolean supports(Leg leg) {
        return leg.isTransit()
                && leg.transitInfo() != null
                && leg.transitInfo().fareWon() > 0;
    }

    @Override
    public CostComponent estimate(Leg leg) {
        return new CostComponent("대중교통", leg.transitInfo().fareWon());
    }
}
