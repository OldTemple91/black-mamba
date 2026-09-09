package com.blackmamba.navigation.domain.route.pricing;

import com.blackmamba.navigation.domain.route.Leg;

interface LegPricingPolicy {

    boolean supports(Leg leg);

    CostComponent estimate(Leg leg);
}
