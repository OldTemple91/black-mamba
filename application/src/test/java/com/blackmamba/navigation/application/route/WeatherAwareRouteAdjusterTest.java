package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.domain.weather.WeatherCondition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WeatherAwareRouteAdjusterTest {

    private final WeatherAwareRouteAdjuster adjuster = new WeatherAwareRouteAdjuster();

    private final Location A = new Location("A", 37.5, 127.0);
    private final Location B = new Location("B", 37.6, 127.1);

    @Test
    void CLEAR_날씨는_원본_그대로_반환() {
        Route bike = routeWith(LegType.BIKE, "DDAREUNGI", 0.8);
        Route transit = routeWith(LegType.TRANSIT, "SUBWAY", 0.75);
        List<Route> before = List.of(bike, transit);

        List<Route> after = adjuster.apply(before, WeatherContext.DEFAULT);

        assertThat(after.get(0).score()).isEqualTo(0.8);
        assertThat(after.get(1).score()).isEqualTo(0.75);
    }

    @Test
    void RAIN_이면_공유_자전거_경로가_감점된다() {
        Route bike = routeWith(LegType.BIKE, "DDAREUNGI", 0.8);
        Route transit = routeWith(LegType.TRANSIT, "SUBWAY", 0.75);

        List<Route> after = adjuster.apply(List.of(bike, transit), WeatherContext.of(WeatherCondition.RAIN));

        // 자전거 0.8 × 0.85 = 0.68, 지하철 0.75 그대로 → 순위 역전
        assertThat(after.get(0).legs().getFirst().type()).isEqualTo(LegType.TRANSIT);
        assertThat(after.get(0).score()).isEqualTo(0.75);
        assertThat(after.get(1).score()).isCloseTo(0.68, within(0.001));
    }

    @Test
    void SNOW_는_RAIN_보다_강한_감점() {
        Route bike = routeWith(LegType.BIKE, "DDAREUNGI", 0.8);

        double afterRain = adjuster.apply(List.of(bike), WeatherContext.of(WeatherCondition.RAIN))
                .get(0).score();
        double afterSnow = adjuster.apply(List.of(bike), WeatherContext.of(WeatherCondition.SNOW))
                .get(0).score();

        // RAIN × 0.85 = 0.68, SNOW × 0.70 = 0.56
        assertThat(afterRain).isGreaterThan(afterSnow);
        assertThat(afterSnow).isCloseTo(0.56, within(0.001));
    }

    @Test
    void 킥보드_경로도_악천후에_감점() {
        Route kick = routeWith(LegType.KICKBOARD, "KICKBOARD_SHARED", 0.9);

        List<Route> after = adjuster.apply(List.of(kick), WeatherContext.of(WeatherCondition.RAIN));

        assertThat(after.get(0).score()).isCloseTo(0.765, within(0.001));  // 0.9 × 0.85
    }

    @Test
    void 대중교통만_포함된_경로는_악천후에도_감점_없음() {
        Route transit = routeWith(LegType.TRANSIT, "SUBWAY", 0.75);

        List<Route> after = adjuster.apply(List.of(transit), WeatherContext.of(WeatherCondition.RAIN));

        assertThat(after.get(0).score()).isEqualTo(0.75);
    }

    @Test
    void HEAT_는_장거리_도보를_감점() {
        // 400m 도보 + 버스 → HEAT 에서 장거리도보 페널티 적용
        Leg walk = new Leg(LegType.WALK, "WALK", 6, 400, A, B, null, null, null);
        Leg bus = new Leg(LegType.TRANSIT, "BUS", 30, 10_000, A, B, null, null, null);
        Route route = Route.of(List.of(walk, bus), RouteType.TRANSIT_ONLY).withScore(0.8, true);

        List<Route> after = adjuster.apply(List.of(route), WeatherContext.of(WeatherCondition.HEAT));

        assertThat(after.get(0).score()).isCloseTo(0.736, within(0.001));  // 0.8 × 0.92
    }

    @Test
    void 짧은_도보는_HEAT_에서도_감점_없음() {
        Leg shortWalk = new Leg(LegType.WALK, "WALK", 2, 100, A, B, null, null, null);   // 100m < 300m
        Leg bus = new Leg(LegType.TRANSIT, "BUS", 30, 10_000, A, B, null, null, null);
        Route route = Route.of(List.of(shortWalk, bus), RouteType.TRANSIT_ONLY).withScore(0.8, true);

        List<Route> after = adjuster.apply(List.of(route), WeatherContext.of(WeatherCondition.HEAT));

        assertThat(after.get(0).score()).isEqualTo(0.8);
    }

    @Test
    void RAIN_에서_자전거_경로는_공유_페널티와_도보_페널티가_모두_적용된다() {
        Leg walk = new Leg(LegType.WALK, "WALK", 6, 400, A, B, null, null, null);
        Leg bike = new Leg(LegType.BIKE, "DDAREUNGI", 15, 3_000, A, B, null, null, null);
        Route route = Route.of(List.of(walk, bike), RouteType.TRANSIT_WITH_BIKE).withScore(0.8, true);

        List<Route> after = adjuster.apply(List.of(route), WeatherContext.of(WeatherCondition.RAIN));

        // 0.8 × 0.85 (공유) × 0.92 (도보) = 0.6256
        assertThat(after.get(0).score()).isCloseTo(0.6256, within(0.001));
    }

    @Test
    void UNKNOWN_또는_null_은_영향_없음() {
        Route bike = routeWith(LegType.BIKE, "DDAREUNGI", 0.8);

        assertThat(adjuster.apply(List.of(bike), WeatherContext.of(WeatherCondition.UNKNOWN))
                .get(0).score()).isEqualTo(0.8);
        assertThat(adjuster.apply(List.of(bike), null)
                .get(0).score()).isEqualTo(0.8);
    }

    // ── helper ──
    private Route routeWith(LegType type, String mode, double score) {
        Leg leg = new Leg(type, mode, 10, 3_000, A, B, null, null, null);
        return Route.of(List.of(leg), RouteType.TRANSIT_ONLY).withScore(score, true);
    }
}
