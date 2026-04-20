package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CarReferenceCalculatorTest {

    private final CarReferenceCalculator calculator = new CarReferenceCalculator();

    @Test
    void 도심_단거리는_25kmh_속도로_계산된다() {
        // 강남역 → 홍대입구 (직선 약 8km, 우회 1.3 = 10.4km, 25km/h → 25분)
        Location 강남 = new Location("강남역", 37.4979, 127.0276);
        Location 홍대 = new Location("홍대입구", 37.5573, 126.9246);

        CarReference car = calculator.estimate(강남, 홍대);

        assertThat(car.estimatedMinutes()).isBetween(25, 45);   // 약 36분 예상 (11.5km × 1.3 / 25km/h)
        // 연료: 10.4km / 12km/L × 1700 ≈ 1,473원
        assertThat(car.costBreakdown()).containsKey("fuel");
        // 연료: 14.6km / 12km/L × 1700 ≈ 2,067원
        assertThat(car.costBreakdown().get("fuel")).isBetween(1_500, 2_500);
        // 주차비 포함
        assertThat(car.costBreakdown().get("parking")).isEqualTo(3_000);
        // 톨게이트 없음 (30km 미만)
        assertThat(car.costBreakdown()).doesNotContainKey("toll");
    }

    @Test
    void 장거리는_고속도로_속도와_톨게이트가_포함된다() {
        // 서울 → 대전 직선 약 140km, 우회 1.3 = 182km
        Location 서울 = new Location("서울역", 37.5547, 126.9706);
        Location 대전 = new Location("대전역", 36.3323, 127.4344);

        CarReference car = calculator.estimate(서울, 대전);

        // 70km/h → 156분 = 2.6시간 정도
        assertThat(car.estimatedMinutes()).isGreaterThan(120);
        // 톨게이트 포함
        assertThat(car.costBreakdown()).containsKey("toll");
        assertThat(car.costBreakdown().get("toll")).isGreaterThan(10_000);
        // CO₂ 182km * 171g = 31kg 수준
        assertThat(car.estimatedCo2Grams()).isBetween(30_000.0, 35_000.0);
    }

    @Test
    void 비교결과에_narrative가_한국어로_생성된다() {
        Location 강남 = new Location("강남", 37.4979, 127.0276);
        Location 홍대 = new Location("홍대", 37.5573, 126.9246);

        Route route = routeOf(40, 1_450);   // 40분, 1,450원

        RouteComparison comparison = calculator.compareWithRoute(route, 강남, 홍대);

        assertThat(comparison.narrative()).contains("자가용");
        // 자가용 약 25분 vs 경로 40분 → 경로가 +15분
        assertThat(comparison.timeDiffMinutes()).isGreaterThan(0);
        // 자가용 약 4,400원 vs 경로 1,450원 → 2,950원 절약
        assertThat(comparison.costSavedWon()).isGreaterThan(0);
        assertThat(comparison.narrative()).containsAnyOf("절약", "원 더 듭니다");
    }

    @Test
    void 자가용보다_빠른_경로는_narrative가_다르게_생성된다() {
        Location 강남 = new Location("강남", 37.4979, 127.0276);
        Location 홍대 = new Location("홍대", 37.5573, 126.9246);

        // 급행 지하철 가정 — 실제로는 불가능하지만 단위 테스트용
        Route fastRoute = routeOf(15, 1_450);

        RouteComparison comparison = calculator.compareWithRoute(fastRoute, 강남, 홍대);

        assertThat(comparison.timeDiffMinutes()).isLessThan(0);   // 자가용보다 빠름
        assertThat(comparison.narrative()).contains("빠르며");
    }

    @Test
    void 매우_짧은거리에도_계산은_가능하다() {
        Location a = new Location("A", 37.5000, 127.0000);
        Location b = new Location("B", 37.5010, 127.0010);   // 약 140m

        CarReference car = calculator.estimate(a, b);

        assertThat(car.estimatedMinutes()).isGreaterThanOrEqualTo(1);   // 최소 1분
        assertThat(car.estimatedCostWon()).isGreaterThanOrEqualTo(3_000); // 최소 주차비
    }

    @Test
    void CO2_배출량은_171gPerKm_기준으로_계산된다() {
        Location a = new Location("A", 37.5000, 127.0000);
        Location b = new Location("B", 37.5000, 127.1137);   // 직선 약 10km

        CarReference car = calculator.estimate(a, b);

        // 10km × 1.3 (우회) × 171g/km ≈ 2,223g
        assertThat(car.estimatedCo2Grams()).isCloseTo(2_223.0, within(200.0));
    }

    private Route routeOf(int minutes, int cost) {
        Leg transit = new Leg(LegType.TRANSIT, "transit", minutes, 5_000,
                new Location("", 0, 0), new Location("", 0, 0), null, null, null);
        RouteCostBreakdown breakdown = new RouteCostBreakdown(
                List.of(new CostComponent("transit", cost)), cost);
        return new Route(
                "rt_test", RouteType.TRANSIT_ONLY, minutes, cost,
                breakdown, List.of(), null, 0.5, false,
                List.of(transit), null, null, null
        );
    }
}
