package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessibilityPostProcessorTest {

    private final AccessibilityPostProcessor processor =
            new AccessibilityPostProcessor(new AccessibilityStationRegistry());

    @Test
    void 제약이_없으면_원본_경로를_그대로_반환한다() {
        List<Route> input = List.of(routeOf(40, 1_000, "강남", "홍대"));

        List<Route> result = processor.apply(input, AccessibilityContext.DEFAULT);

        assertThat(result).isSameAs(input);
    }

    @Test
    void 휠체어_접근성_요청시_엘리베이터_없는_역을_거치는_경로는_제외된다() {
        Route accessible = routeOf(40, 1_000, "강남", "홍대");
        Route inaccessible = routeOf(35, 1_200, "강남", "남영");   // "남영"은 엘리베이터 미설치 역

        List<Route> result = processor.apply(
                List.of(accessible, inaccessible),
                AccessibilityContext.of(true, null)
        );

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().legs().getFirst().end().name()).isEqualTo("홍대");
    }

    @Test
    void 도보_속도_3kmh_적용시_WALK_leg_시간이_늘어난다() {
        Leg walk = new Leg(LegType.WALK, "WALK", 10, 800,
                new Location("출발", 0, 0), new Location("역", 0, 0),
                null, null, null);
        Leg transit = new Leg(LegType.TRANSIT, "지하철", 30, 10_000,
                new Location("역", 0, 0), new Location("목적지", 0, 0),
                null, null, null);

        Route route = new Route(
                "rt_walk", RouteType.TRANSIT_ONLY, 40, 1_000,
                new RouteCostBreakdown(List.of(new CostComponent("대중교통", 1_000)), 1_000),
                List.of(), null, 0, false,
                List.of(walk, transit), null, null, null, null
        );

        // 4.5 → 3.0 km/h = 1.5배 → 10분 → 15분
        List<Route> result = processor.apply(
                List.of(route),
                AccessibilityContext.of(false, 3.0)
        );

        Route adjusted = result.getFirst();
        Leg adjustedWalk = adjusted.legs().getFirst();
        assertThat(adjustedWalk.durationMinutes()).isEqualTo(15);   // 10 * 1.5
        // 총시간 = 40 + 5 = 45분
        assertThat(adjusted.totalMinutes()).isEqualTo(45);
    }

    @Test
    void 휠체어_요청_없으면_엘리베이터_없는_역도_유지된다() {
        Route route = routeOf(35, 1_200, "강남", "남영");

        List<Route> result = processor.apply(
                List.of(route),
                AccessibilityContext.of(false, null)
        );

        assertThat(result).hasSize(1);
    }

    @Test
    void 레지스트리는_블랭크_역이름을_안전하게_true_반환한다() {
        AccessibilityStationRegistry registry = new AccessibilityStationRegistry();
        assertThat(registry.isWheelchairAccessible(null)).isTrue();
        assertThat(registry.isWheelchairAccessible("")).isTrue();
        assertThat(registry.isWheelchairAccessible("강남")).isTrue();
        assertThat(registry.isWheelchairAccessible("남영역 1번")).isFalse();  // contains 매칭
    }

    private Route routeOf(int minutes, int cost, String from, String to) {
        Leg transit = new Leg(LegType.TRANSIT, "지하철", minutes, 8_000,
                new Location(from, 37.5, 127.0),
                new Location(to, 37.6, 127.0),
                null, null, null);
        return new Route(
                "rt_test_" + to, RouteType.TRANSIT_ONLY, minutes, cost,
                new RouteCostBreakdown(List.of(new CostComponent("대중교통", cost)), cost),
                List.of(), null, 0.5, false,
                List.of(transit), null, null, null, null
        );
    }
}
