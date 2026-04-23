package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.MobilityInfo;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CarbonFootprintCalculatorTest {

    private final CarbonFootprintCalculator calc = new CarbonFootprintCalculator();
    private final Location a = new Location("A", 37.5, 127.0);
    private final Location b = new Location("B", 37.6, 127.1);

    @Test
    void 지하철_구간은_41g_per_km_계수가_적용된다() {
        Leg subway = new Leg(LegType.TRANSIT, "SUBWAY", 20, 10_000, a, b, null, null, null);
        Route route = Route.of(List.of(subway), RouteType.TRANSIT_ONLY);

        double grams = calc.forRoute(route);

        // 10km × 41 g/km = 410g
        assertThat(grams).isEqualTo(410.0);
    }

    @Test
    void 버스_구간은_68g_per_km_계수가_적용된다() {
        Leg bus = new Leg(LegType.TRANSIT, "BUS", 25, 8_000, a, b, null, null, null);
        Route route = Route.of(List.of(bus), RouteType.TRANSIT_ONLY);

        double grams = calc.forRoute(route);

        // 8km × 68 g/km = 544g
        assertThat(grams).isEqualTo(544.0);
    }

    @Test
    void 도보와_자전거_따릉이_는_0g() {
        Leg walk = new Leg(LegType.WALK, "WALK", 5, 400, a, b, null, null, null);
        Leg bike = new Leg(LegType.BIKE, "DDAREUNGI", 10, 2_000, a, b, null, null, null);
        Route route = Route.of(List.of(walk, bike), RouteType.TRANSIT_WITH_BIKE);

        double grams = calc.forRoute(route);

        assertThat(grams).isEqualTo(0.0);
    }

    @Test
    void 이동수단별_정밀_계수_분리() {
        // 3km 각자 — ebike 10, personal kickboard 14, shared kickboard 22
        Leg ebike = new Leg(LegType.KICKBOARD, "PERSONAL_EBIKE", 10, 3_000, a, b, null, null, null);
        Leg pk    = new Leg(LegType.KICKBOARD, "PERSONAL_KICKBOARD", 10, 3_000, a, b, null, null, null);
        Leg sk    = new Leg(LegType.KICKBOARD, "KICKBOARD_SHARED", 10, 3_000, a, b, null, null, null);

        assertThat(calc.forLeg(ebike)).isEqualTo(30.0);  // 3 × 10
        assertThat(calc.forLeg(pk)).isEqualTo(42.0);     // 3 × 14
        assertThat(calc.forLeg(sk)).isEqualTo(66.0);     // 3 × 22
    }

    @Test
    void 혼합_경로_탄소량은_각_구간의_합이다() {
        Leg subway = new Leg(LegType.TRANSIT, "SUBWAY", 15, 5_000, a, b, null, null, null);     // 205g
        Leg walk   = new Leg(LegType.WALK, "WALK", 3, 300, a, b, null, null, null);              // 0
        Leg bike   = new Leg(LegType.BIKE, "DDAREUNGI", 10, 2_000, a, b, null, null, null);      // 0
        Route route = Route.of(List.of(subway, walk, bike), RouteType.TRANSIT_WITH_BIKE);

        assertThat(calc.forRoute(route)).isEqualTo(205.0);
    }

    @Test
    void 자가용_거리_배출량_계수는_171g_per_km() {
        double grams = calc.forCarDistance(10.0);
        assertThat(grams).isEqualTo(1_710.0);
    }

    @Test
    void 친환경_경로_판정_자전거_도보_위주는_eco() {
        Leg bike = new Leg(LegType.BIKE, "DDAREUNGI", 20, 5_000, a, b, null, null, null);
        Route route = Route.of(List.of(bike), RouteType.MOBILITY_ONLY);

        assertThat(calc.isEcoRoute(route)).isTrue();
    }

    @Test
    void 친환경_경로_판정_버스_위주는_non_eco() {
        Leg bus = new Leg(LegType.TRANSIT, "BUS", 30, 12_000, a, b, null, null, null);
        Route route = Route.of(List.of(bus), RouteType.TRANSIT_ONLY);

        // 68 g/km > 20 임계 → not eco
        assertThat(calc.isEcoRoute(route)).isFalse();
    }

    @Test
    void 빈_경로는_0g_이며_eco_아님() {
        Route empty = Route.of(List.of(), RouteType.TRANSIT_ONLY);
        assertThat(calc.forRoute(empty)).isEqualTo(0.0);
        assertThat(calc.isEcoRoute(empty)).isFalse();
    }

    @Test
    void null_leg_은_0() {
        assertThat(calc.forLeg(null)).isEqualTo(0.0);
    }

    @Test
    void mode_미상_대중교통은_55g_평균() {
        Leg unknown = new Leg(LegType.TRANSIT, "TRANSIT", 20, 10_000, a, b, null, null, null);
        assertThat(calc.forLeg(unknown)).isEqualTo(550.0);  // 10 × 55
    }
}
