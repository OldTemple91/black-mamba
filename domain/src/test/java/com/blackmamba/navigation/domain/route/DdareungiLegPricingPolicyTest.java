package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class DdareungiLegPricingPolicyTest {

    private final DdareungiLegPricingPolicy policy = new DdareungiLegPricingPolicy();

    @ParameterizedTest(name = "{0}분 이용 → {1}원")
    @CsvSource({
            // 경계값: 시간제 구간 (1h / 2h / 3h)
            "0,    0",
            "1,    1000",
            "60,   1000",
            "61,   2000",
            "120,  2000",
            "121,  3000",
            "180,  3000",
            // 3시간 초과: 5분 단위 올림 × 200원
            "181,  3200",
            "185,  3200",
            "186,  3400",
            "190,  3400",
            "240,  5400",
    })
    void 따릉이_요금은_시간제_플러스_초과분당_과금이다(int durationMinutes, int expectedFare) {
        assertThat(DdareungiLegPricingPolicy.ddareungiFare(durationMinutes)).isEqualTo(expectedFare);
    }

    @Test
    void 따릉이_BIKE_leg_만_지원한다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.51, 127.01);
        MobilityInfo ddareungi = new MobilityInfo(MobilityType.DDAREUNGI, "서울시", null,
                100, "정류소1", 37.5, 127.0, 5, 100);
        MobilityInfo personalEbike = new MobilityInfo(MobilityType.PERSONAL_EBIKE, "개인", null,
                100, null, 37.5, 127.0, 1, 0);

        Leg ddareungiLeg = new Leg(LegType.BIKE, "DDAREUNGI", 30, 4000, a, b, null, ddareungi, null);
        Leg ebikeLeg = new Leg(LegType.BIKE, "PERSONAL_EBIKE", 30, 4000, a, b, null, personalEbike, null);
        Leg walkLeg = new Leg(LegType.WALK, "WALK", 5, 400, a, b, null, null, null);

        assertThat(policy.supports(ddareungiLeg)).isTrue();
        assertThat(policy.supports(ebikeLeg)).isFalse();
        assertThat(policy.supports(walkLeg)).isFalse();
    }

    @Test
    void 추정_요금_라벨은_따릉이다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.51, 127.01);
        MobilityInfo ddareungi = new MobilityInfo(MobilityType.DDAREUNGI, "서울시", null,
                100, "정류소1", 37.5, 127.0, 5, 100);
        Leg leg = new Leg(LegType.BIKE, "DDAREUNGI", 45, 6000, a, b, null, ddareungi, null);

        CostComponent component = policy.estimate(leg);

        assertThat(component.label()).isEqualTo("따릉이");
        assertThat(component.amountWon()).isEqualTo(1_000);
    }
}
