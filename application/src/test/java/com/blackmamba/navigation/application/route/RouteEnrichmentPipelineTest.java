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
import com.blackmamba.navigation.domain.route.evaluation.RouteInsightFactory;
import com.blackmamba.navigation.domain.route.evaluation.RouteScoreCalculator;
import com.blackmamba.navigation.domain.route.evaluation.Comparison;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
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
            new RouteEvaluator(new RouteScoreCalculator(), new RouteInsightFactory()),
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
        List<Route> result = pipeline.enrich(List.of(bike, transit), origin, destination, RecommendationPreference.RELIABILITY,
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
        List<Route> result = pipeline.enrich(List.of(bike, transit), origin, destination, RecommendationPreference.RELIABILITY,
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
        List<Route> result = pipeline.enrich(List.of(bike, transit), origin, destination, RecommendationPreference.RELIABILITY,
                AccessibilityContext.DEFAULT, WeatherContext.DEFAULT);

        // then
        assertThat(result).extracting(Route::recommended).containsExactly(true, false);
        assertThat(result.getFirst().carbon()).isNotNull();
        assertThat(result.getFirst().carComparison()).isNotNull();
    }

    @Test
    void 보행_속도를_반영하면_바뀐_소요시간으로_점수와_비교_근거를_다시_평가한다() {
        // given — 도보 20분이 낀 자전거 경로(추천) vs 도보 없는 지하철. 3km/h 면 도보가 30분으로 늘어 자전거 경로가 더 느려진다
        Leg longWalk = new Leg(LegType.WALK, "WALK", 20, 1_500, origin, origin, null, null, null);
        Leg bike = new Leg(LegType.BIKE, "DDAREUNGI", 5, 1_200, origin, destination, null, null, null);
        Route walkHeavy = Route.of(List.of(longWalk, bike), RouteType.TRANSIT_WITH_BIKE).withScore(0.9, true);
        Route transit = transitRoute().withScore(0.7, false);

        // when
        List<Route> result = pipeline.enrich(List.of(walkHeavy, transit), origin, destination, RecommendationPreference.RELIABILITY,
                AccessibilityContext.of(false, 3.0), WeatherContext.DEFAULT);

        // then — 25분 → 35분, 기준 30분 대비 단축 없음. 점수는 예전 값(0.9)이 아니라 다시 계산된 값이다
        Route adjusted = result.stream()
                .filter(route -> route.type() == RouteType.TRANSIT_WITH_BIKE)
                .findFirst()
                .orElseThrow();
        assertThat(adjusted.totalMinutes()).isEqualTo(35);
        assertThat(adjusted.comparison().originalMinutes()).isEqualTo(30);
        assertThat(adjusted.comparison().savedMinutes()).isZero();
        assertThat(adjusted.score()).isEqualTo(adjusted.evaluation().totalScore());
        assertThat(adjusted.score()).isNotEqualTo(0.9);
    }

    @Test
    void 기준_경로가_목록에_없어도_전략이_계산한_기준_시간으로_비교를_유지한다() {
        // given — 상위 N 에서 대중교통 단독 경로가 밀려 목록에 없다. 기준 40분 대비 15분 단축으로 평가된 경로
        Leg walk = new Leg(LegType.WALK, "WALK", 10, 750, origin, origin, null, null, null);
        Leg bike = new Leg(LegType.BIKE, "DDAREUNGI", 15, 3_600, origin, destination, null, null, null);
        Route mixed = Route.of(List.of(walk, bike), RouteType.TRANSIT_WITH_BIKE)
                .withComparison(new Comparison(40, 15))
                .withScore(0.9, true);

        // when — 3km/h: 도보 10분 → 15분, 총 30분
        List<Route> result = pipeline.enrich(List.of(mixed), origin, destination, RecommendationPreference.RELIABILITY,
                AccessibilityContext.of(false, 3.0), WeatherContext.DEFAULT);

        // then — 자기 자신과 비교해 단축 0분이 되지 않는다
        assertThat(result.getFirst().totalMinutes()).isEqualTo(30);
        assertThat(result.getFirst().comparison().originalMinutes()).isEqualTo(40);
        assertThat(result.getFirst().comparison().savedMinutes()).isEqualTo(10);
    }

    @Test
    void 추천이_옮겨가면_추천_근거_문구도_새_1위_기준으로_다시_만든다() {
        // given — RAIN 으로 자전거(추천)가 2위로 밀린다
        Route bike = bikeRoute().withScore(0.78, true);
        Route transit = transitRoute().withScore(0.72, false);

        // when
        List<Route> result = pipeline.enrich(List.of(bike, transit), origin, destination, RecommendationPreference.RELIABILITY,
                AccessibilityContext.DEFAULT, WeatherContext.of(WeatherCondition.RAIN));

        // then — 플래그가 바뀐 두 경로 모두 근거가 다시 생성되어 있고, 감점된 자전거 경로에는 날씨 사유가 붙는다
        assertThat(result).allSatisfy(route -> assertThat(route.insights()).isNotNull());
        assertThat(result.get(1).insights().riskBadges())
                .contains("비 예보 — 자전거·킥보드 구간 감점")
                .doesNotContain("안정적");
        assertThat(result.getFirst().insights().riskBadges()).noneMatch(badge -> badge.contains("예보"));
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
