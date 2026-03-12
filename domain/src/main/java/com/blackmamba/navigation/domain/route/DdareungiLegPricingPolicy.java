package com.blackmamba.navigation.domain.route;

final class DdareungiLegPricingPolicy implements LegPricingPolicy {

    private static final int ONE_HOUR_FARE = 1_000;
    private static final int TWO_HOUR_FARE = 2_000;
    private static final int THREE_HOUR_FARE = 3_000;
    private static final int OVERTIME_UNIT_MINUTES = 5;
    private static final int OVERTIME_UNIT_FARE = 200;

    @Override
    public boolean supports(Leg leg) {
        return leg.type() == LegType.BIKE
                && leg.mobilityInfo() != null
                && leg.mobilityInfo().mobilityType() == MobilityType.DDAREUNGI;
    }

    @Override
    public CostComponent estimate(Leg leg) {
        return new CostComponent("따릉이", ddareungiFare(leg.durationMinutes()));
    }

    /**
     * 2026-03 기준 프로젝트용 추정 요금.
     * 정식 과금 연동이 아니라 경로 비교용 예상 비용이다.
     */
    static int ddareungiFare(int durationMinutes) {
        if (durationMinutes <= 0) {
            return 0;
        }
        if (durationMinutes <= 60) {
            return ONE_HOUR_FARE;
        }
        if (durationMinutes <= 120) {
            return TWO_HOUR_FARE;
        }
        if (durationMinutes <= 180) {
            return THREE_HOUR_FARE;
        }
        int overtimeMinutes = durationMinutes - 180;
        int overtimeUnits = (int) Math.ceil((double) overtimeMinutes / OVERTIME_UNIT_MINUTES);
        return THREE_HOUR_FARE + (overtimeUnits * OVERTIME_UNIT_FARE);
    }
}
