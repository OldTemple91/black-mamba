package com.blackmamba.navigation.domain.route.pricing;

import com.blackmamba.navigation.domain.route.Leg;

/**
 * 실시간 조회 없이 근사한 대중교통 구간의 요금.
 * <p>
 * 요금을 0원으로 두면 비용 점수가 만점이 되어, 대중교통 API 가 <b>실패한</b> 후보가
 * 성공한 후보보다 높은 순위를 받는다 — 장애가 추천을 왜곡하는 역전이다.
 * 서울 대중교통 기본요금 수준을 하한으로 둔다 (실제 요금을 아는 구간은 {@link TransitLegPricingPolicy}).
 */
final class EstimatedTransitLegPricingPolicy implements LegPricingPolicy {

    /** 서울 지하철·간선버스 기본요금 수준 (2025 기준 1,400~1,550원) */
    static final int ESTIMATED_BASE_FARE_WON = 1_500;

    @Override
    public boolean supports(Leg leg) {
        return leg.isEstimatedTransit()
                && (leg.transitInfo() == null || leg.transitInfo().fareWon() <= 0);
    }

    @Override
    public CostComponent estimate(Leg leg) {
        return new CostComponent("대중교통(추정)", ESTIMATED_BASE_FARE_WON);
    }
}
