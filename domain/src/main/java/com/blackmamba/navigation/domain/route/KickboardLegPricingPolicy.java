package com.blackmamba.navigation.domain.route;

/**
 * 킥보드 이용 요금 추정 정책.
 * 공유 킥보드(KICKBOARD_SHARED) 및 개인 킥보드(PERSONAL) 모두 지원.
 * 공유: 잠금 해제 1,000원 + 분당 150원 (주요 공유킥보드 평균 기준)
 * 개인: 충전 비용 추정 (분당 약 10원 수준)
 */
final class KickboardLegPricingPolicy implements LegPricingPolicy {

    private static final int SHARED_UNLOCK_FEE = 1_000;
    private static final int SHARED_PER_MINUTE_FEE = 150;
    private static final int PERSONAL_PER_MINUTE_FEE = 10;

    @Override
    public boolean supports(Leg leg) {
        return leg.type() == LegType.KICKBOARD;
    }

    @Override
    public CostComponent estimate(Leg leg) {
        boolean isPersonal = leg.mobilityInfo() != null
                && leg.mobilityInfo().mobilityType() == MobilityType.PERSONAL;

        if (isPersonal) {
            int cost = leg.durationMinutes() * PERSONAL_PER_MINUTE_FEE;
            return new CostComponent("개인 킥보드(충전비)", cost);
        }

        int cost = SHARED_UNLOCK_FEE + (leg.durationMinutes() * SHARED_PER_MINUTE_FEE);
        return new CostComponent("공유 킥보드", cost);
    }
}
