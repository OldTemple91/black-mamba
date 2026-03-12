package com.blackmamba.navigation.domain.route;

interface LegPricingPolicy {

    boolean supports(Leg leg);

    CostComponent estimate(Leg leg);
}
