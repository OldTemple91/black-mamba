package com.blackmamba.navigation.domain.route;

/**
 * 킥보드/전기자전거 이용 요금 추정 정책.
 * - 공유 킥보드(KICKBOARD_SHARED): 잠금 해제 1,000원 + 분당 150원
 * - 개인 킥보드(PERSONAL_KICKBOARD): 충전 비용 추정 (분당 약 10원)
 * - 개인 전기자전거(PERSONAL_EBIKE): 충전 비용 추정 (분당 약 8원)
 */
final class KickboardLegPricingPolicy implements LegPricingPolicy {

    private static final int SHARED_UNLOCK_FEE = 1_000;
    private static final int SHARED_PER_MINUTE_FEE = 150;
    private static final int PERSONAL_KICKBOARD_PER_MINUTE_FEE = 10;
    private static final int PERSONAL_EBIKE_PER_MINUTE_FEE = 8;

    @Override
    public boolean supports(Leg leg) {
        return leg.type() == LegType.KICKBOARD || leg.type() == LegType.BIKE
                && leg.mobilityInfo() != null
                && leg.mobilityInfo().mobilityType() == MobilityType.PERSONAL_EBIKE;
    }

    @Override
    public CostComponent estimate(Leg leg) {
        if (leg.mobilityInfo() == null) {
            return new CostComponent("기타", 0);
        }

        return switch (leg.mobilityInfo().mobilityType()) {
            case PERSONAL_KICKBOARD -> {
                int cost = leg.durationMinutes() * PERSONAL_KICKBOARD_PER_MINUTE_FEE;
                yield new CostComponent("개인 킥보드(충전비)", cost);
            }
            case PERSONAL_EBIKE -> {
                int cost = leg.durationMinutes() * PERSONAL_EBIKE_PER_MINUTE_FEE;
                yield new CostComponent("개인 전기자전거(충전비)", cost);
            }
            case KICKBOARD_SHARED -> {
                int cost = SHARED_UNLOCK_FEE + (leg.durationMinutes() * SHARED_PER_MINUTE_FEE);
                yield new CostComponent("공유 킥보드", cost);
            }
            default -> new CostComponent("기타", 0);
        };
    }
}
