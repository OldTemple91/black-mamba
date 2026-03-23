package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KickboardLegPricingPolicyTest {

    @Test
    void 공유_킥보드_요금은_잠금해제비_플러스_분당요금이다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.51, 127.01);
        MobilityInfo shared = new MobilityInfo(MobilityType.KICKBOARD_SHARED, "SWING", "device1",
                80, null, 37.5, 127.0, 1, 100);

        Leg leg = new Leg(LegType.KICKBOARD, "KICKBOARD_SHARED", 10, 1500, a, b, null, shared, null);
        Route route = Route.of(List.of(leg), RouteType.TRANSIT_WITH_KICKBOARD);

        // 잠금해제 1,000 + 10분 × 150 = 2,500원
        assertThat(route.totalCostWon()).isEqualTo(2_500);
        assertThat(route.costBreakdown().items())
                .extracting(CostComponent::label)
                .contains("공유 킥보드");
    }

    @Test
    void 개인_킥보드는_충전비만_부과된다() {
        Location a = new Location("A", 37.5, 127.0);
        Location b = new Location("B", 37.51, 127.01);
        MobilityInfo personal = new MobilityInfo(MobilityType.PERSONAL, "개인", null,
                100, null, 37.5, 127.0, 1, 0);

        Leg leg = new Leg(LegType.KICKBOARD, "PERSONAL", 15, 2000, a, b, null, personal, null);
        Route route = Route.of(List.of(leg), RouteType.MOBILITY_ONLY);

        // 15분 × 10원 = 150원
        assertThat(route.totalCostWon()).isEqualTo(150);
        assertThat(route.costBreakdown().items())
                .extracting(CostComponent::label)
                .contains("개인 킥보드(충전비)");
    }

    @Test
    void 킥보드_0분은_공유라도_잠금해제비만_부과된다() {
        Location a = new Location("A", 37.5, 127.0);
        MobilityInfo shared = new MobilityInfo(MobilityType.KICKBOARD_SHARED, "SWING", "device1",
                80, null, 37.5, 127.0, 1, 0);

        Leg leg = new Leg(LegType.KICKBOARD, "KICKBOARD_SHARED", 0, 0, a, a, null, shared, null);
        Route route = Route.of(List.of(leg), RouteType.TRANSIT_WITH_KICKBOARD);

        // 잠금해제 1,000 + 0분 = 1,000원
        assertThat(route.totalCostWon()).isEqualTo(1_000);
    }
}
