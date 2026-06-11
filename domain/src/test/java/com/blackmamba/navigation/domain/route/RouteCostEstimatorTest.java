package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 경로 비용 합산 — 사용자에게 보여주는 금액을 만드는 로직이므로
 * 회귀 안전망이 가장 필요한 domain 코드다.
 */
class RouteCostEstimatorTest {

    private final Location a = new Location("A", 37.5, 127.0);
    private final Location b = new Location("B", 37.51, 127.01);
    private final Location c = new Location("C", 37.52, 127.02);

    @Test
    void 같은_라벨의_비용은_하나로_합산된다() {
        // 지하철 + 버스 = 둘 다 "대중교통" 라벨 → 한 항목으로 병합
        Leg subway = transitLeg("2호선", 1_400);
        Leg bus = transitLeg("146번", 1_500);

        RouteCostBreakdown breakdown = RouteCostEstimator.estimate(List.of(subway, bus));

        assertThat(breakdown.items()).hasSize(1);
        assertThat(breakdown.items().getFirst().label()).isEqualTo("대중교통");
        assertThat(breakdown.items().getFirst().amountWon()).isEqualTo(2_900);
        assertThat(breakdown.totalWon()).isEqualTo(2_900);
    }

    @Test
    void 이동수단별_비용이_분리되어_집계된다() {
        Leg subway = transitLeg("2호선", 1_400);
        MobilityInfo ddareungi = new MobilityInfo(MobilityType.DDAREUNGI, "서울시", null,
                100, "정류소1", 37.5, 127.0, 5, 100);
        Leg bike = new Leg(LegType.BIKE, "DDAREUNGI", 30, 4000, b, c, null, ddareungi, null);

        RouteCostBreakdown breakdown = RouteCostEstimator.estimate(List.of(subway, bike));

        assertThat(breakdown.items())
                .extracting(CostComponent::label)
                .containsExactly("대중교통", "따릉이");
        assertThat(breakdown.totalWon()).isEqualTo(1_400 + 1_000);
    }

    @Test
    void 비용이_없는_구간은_항목에서_제외된다() {
        // 도보 + 요금 정보 없는 transit → 비용 0 → 항목 없음
        Leg walk = new Leg(LegType.WALK, "WALK", 5, 400, a, b, null, null, null);
        Leg noFareTransit = new Leg(LegType.TRANSIT, "SUBWAY", 20, 8000, b, c, null, null, null);

        RouteCostBreakdown breakdown = RouteCostEstimator.estimate(List.of(walk, noFareTransit));

        assertThat(breakdown.items()).isEmpty();
        assertThat(breakdown.totalWon()).isZero();
    }

    @Test
    void 빈_leg_리스트는_0원이다() {
        RouteCostBreakdown breakdown = RouteCostEstimator.estimate(List.of());

        assertThat(breakdown.items()).isEmpty();
        assertThat(breakdown.totalWon()).isZero();
    }

    private Leg transitLeg(String lineName, int fareWon) {
        TransitInfo info = new TransitInfo(lineName, "#000000", 5, fareWon, List.of());
        return new Leg(LegType.TRANSIT, "SUBWAY", 20, 8000, a, b, info, null, null);
    }
}
