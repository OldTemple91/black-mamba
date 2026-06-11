package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransitLegPricingPolicyTest {

    private final TransitLegPricingPolicy policy = new TransitLegPricingPolicy();

    private final Location a = new Location("A", 37.5, 127.0);
    private final Location b = new Location("B", 37.51, 127.01);

    @Test
    void 요금_정보가_있는_TRANSIT_leg_만_지원한다() {
        TransitInfo withFare = new TransitInfo("2호선", "#00A84D", 5, 1_400, List.of());
        TransitInfo zeroFare = new TransitInfo("2호선", "#00A84D", 5, 0, List.of());

        Leg fareLeg = new Leg(LegType.TRANSIT, "SUBWAY", 20, 8000, a, b, withFare, null, null);
        Leg zeroFareLeg = new Leg(LegType.TRANSIT, "SUBWAY", 20, 8000, a, b, zeroFare, null, null);
        Leg noInfoLeg = new Leg(LegType.TRANSIT, "SUBWAY", 20, 8000, a, b, null, null, null);
        Leg walkLeg = new Leg(LegType.WALK, "WALK", 5, 400, a, b, null, null, null);

        assertThat(policy.supports(fareLeg)).isTrue();
        // 요금 0원(환승 무료 구간 등)은 비용 항목을 만들지 않는다
        assertThat(policy.supports(zeroFareLeg)).isFalse();
        assertThat(policy.supports(noInfoLeg)).isFalse();
        assertThat(policy.supports(walkLeg)).isFalse();
    }

    @Test
    void 요금은_ODsay_가_내려준_운임을_그대로_사용한다() {
        TransitInfo info = new TransitInfo("146번", "#0068B7", 12, 1_500, List.of());
        Leg leg = new Leg(LegType.TRANSIT, "BUS", 25, 9000, a, b, info, null, null);

        CostComponent component = policy.estimate(leg);

        assertThat(component.label()).isEqualTo("대중교통");
        assertThat(component.amountWon()).isEqualTo(1_500);
    }
}
