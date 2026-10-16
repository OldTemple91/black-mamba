package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.accessibility.AccessibilityContext;
import com.blackmamba.navigation.application.route.accessibility.AccessibilityPostProcessor;
import com.blackmamba.navigation.application.route.accessibility.AccessibilityStationRegistry;
import com.blackmamba.navigation.application.route.weather.WeatherAwareRouteAdjuster;
import com.blackmamba.navigation.application.route.weather.WeatherContext;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.domain.route.TransitInfo;
import com.blackmamba.navigation.domain.route.comparison.CarReferenceCalculator;
import com.blackmamba.navigation.domain.route.comparison.CarbonFootprintCalculator;
import com.blackmamba.navigation.domain.weather.WeatherCondition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 플래그는 "최종 순서의 1위" 여야 한다 — 추천 배지·LLM narrative·SSE 변화 감지가 전부 이 플래그를 따른다.
 */
class RouteEnrichmentPipelineTest {

    private final Location origin = new Location("서울역", 37.5547, 126.9706);
    private final Location destination = new Location("강남역", 37.4979, 127.0276);
    private final CarbonFootprintCalculator carbon = new CarbonFootprintCalculator();
    private final RouteEnrichmentPipeline pipeline = new RouteEnrichmentPipeline(
            new AccessibilityPostProcessor(new AccessibilityStationRegistry()),
            new WeatherAwareRouteAdjuster(),
            carbon,
            new CarReferenceCalculator(carbon),
            new CarbonMetrics(new SimpleMeterRegistry()));

    @Test
    void 날씨_감점으로_순위가_바뀌면_추천_플래그도_새_1위로_옮겨간다() {
        // given — 자전거 0.78(추천) vs 대중교통 0.72. RAIN 이면 자전거 ×0.85 = 0.663 으로 역전
        Route bike = bikeRoute().withScore(0.78, true);
        Route transit = transitRoute().withScore(0.72, false);

        // when
        List<Route> result = pipeline.enrich(List.of(bike, transit), origin, destination,
                AccessibilityContext.DEFAULT, WeatherContext.of(WeatherCondition.RAIN));

        // then
        assertThat(result).extracting(Route::type).containsExactly(RouteType.TRANSIT_ONLY, RouteType.MOBILITY_ONLY);
        assertThat(result).extracting(Route::recommended).containsExactly(true, false);
    }

    @Test
    void 접근성_필터가_추천_경로를_제거하면_남은_1위가_추천이_된다() {
        // given — 휠체어 요청: 자전거 탑승 경로(추천) 제외
        Route bike = bikeRoute().withScore(0.9, true);
        Route transit = transitRoute().withScore(0.7, false);

        // when
        List<Route> result = pipeline.enrich(List.of(bike, transit), origin, destination,
                AccessibilityContext.of(true, null), WeatherContext.DEFAULT);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().type()).isEqualTo(RouteType.TRANSIT_ONLY);
        assertThat(result.getFirst().recommended()).isTrue();
    }

    @Test
    void 변동이_없으면_전략이_매긴_추천을_그대로_둔다() {
        // given
        Route bike = bikeRoute().withScore(0.78, true);
        Route transit = transitRoute().withScore(0.72, false);

        // when
        List<Route> result = pipeline.enrich(List.of(bike, transit), origin, destination,
                AccessibilityContext.DEFAULT, WeatherContext.DEFAULT);

        // then
        assertThat(result).extracting(Route::recommended).containsExactly(true, false);
        assertThat(result.getFirst().carbon()).isNotNull();
        assertThat(result.getFirst().carComparison()).isNotNull();
    }

    private Route bikeRoute() {
        Leg bike = new Leg(LegType.BIKE, "DDAREUNGI", 25, 6_000, origin, destination, null, null, null);
        return Route.of(List.of(bike), RouteType.MOBILITY_ONLY);
    }

    private Route transitRoute() {
        Leg subway = new Leg(LegType.TRANSIT, "SUBWAY", 30, 9_000, origin, destination,
                new TransitInfo("4호선", null, 8, 1_450, List.of()), null, null);
        return Route.of(List.of(subway), RouteType.TRANSIT_ONLY);
    }
}
