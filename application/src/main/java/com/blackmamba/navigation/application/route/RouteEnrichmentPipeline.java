package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.accessibility.AccessibilityContext;
import com.blackmamba.navigation.application.route.accessibility.AccessibilityPostProcessor;
import com.blackmamba.navigation.application.route.weather.WeatherAwareRouteAdjuster;
import com.blackmamba.navigation.application.route.weather.WeatherContext;
import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.comparison.CarReferenceCalculator;
import com.blackmamba.navigation.domain.route.comparison.CarbonFootprintCalculator;
import com.blackmamba.navigation.domain.route.comparison.CarbonSummary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 전략이 만든 경로 목록에 적용되는 후처리 파이프라인 — 순서가 의미를 가진다.
 * <ol>
 *   <li>접근성 (휠체어/보행 속도) — 경로 필터·조정</li>
 *   <li>날씨 — 자전거·킥보드 탑승/장거리 도보 페널티 (A-4)</li>
 *   <li>추천 재정렬 — 위 두 단계가 순위를 바꾸거나 추천 경로를 제거할 수 있으므로,
 *       최종 점수 순 1위에만 추천 플래그를 둔다 (추천 배지·LLM narrative·SSE 변화 감지가 이 플래그를 따른다)</li>
 *   <li>탄소 (C-2) → 자가용 비교 (F-1): 비교 narrative 가 정확한 CO₂ 값을 쓰려면
 *       탄소가 먼저 계산돼 있어야 한다</li>
 * </ol>
 * 유스케이스는 "전략 실행 → 후처리 → 이력 기록" 만 알고, 후처리의 단계 구성은 여기서 관리한다.
 */
@Component
public class RouteEnrichmentPipeline {

    /** 자가용 우회계수 — {@link CarReferenceCalculator} 의 DETOUR_FACTOR 와 동일 */
    private static final double CAR_DETOUR_FACTOR = 1.3;

    private final AccessibilityPostProcessor accessibilityPostProcessor;
    private final WeatherAwareRouteAdjuster weatherAwareRouteAdjuster;
    private final CarbonFootprintCalculator carbonFootprintCalculator;
    private final CarReferenceCalculator carReferenceCalculator;
    private final CarbonMetrics carbonMetrics;

    public RouteEnrichmentPipeline(AccessibilityPostProcessor accessibilityPostProcessor,
                                   WeatherAwareRouteAdjuster weatherAwareRouteAdjuster,
                                   CarbonFootprintCalculator carbonFootprintCalculator,
                                   CarReferenceCalculator carReferenceCalculator,
                                   CarbonMetrics carbonMetrics) {
        this.accessibilityPostProcessor = accessibilityPostProcessor;
        this.weatherAwareRouteAdjuster = weatherAwareRouteAdjuster;
        this.carbonFootprintCalculator = carbonFootprintCalculator;
        this.carReferenceCalculator = carReferenceCalculator;
        this.carbonMetrics = carbonMetrics;
    }

    public List<Route> enrich(List<Route> routes,
                              Location origin,
                              Location destination,
                              AccessibilityContext accessibilityContext,
                              WeatherContext weatherContext) {
        List<Route> accessible = accessibilityPostProcessor.apply(routes, accessibilityContext);
        List<Route> weatherAdjusted = weatherAwareRouteAdjuster.apply(accessible, weatherContext);
        return reconcileRecommendation(weatherAdjusted).stream()
                .map(route -> route.withCarbon(computeCarbon(route, origin, destination)))
                .map(route -> route.withCarComparison(carReferenceCalculator.compareWithRoute(route, origin, destination)))
                .toList();
    }

    /**
     * 점수 내림차순으로 정렬하고 1위에만 recommended=true 를 둔다.
     * 전략이 매긴 플래그는 날씨 감점·접근성 필터 이전 기준이라 그대로 두면
     * 2위가 추천 배지를 달거나 추천 경로가 아예 없는 응답이 나간다.
     */
    private static List<Route> reconcileRecommendation(List<Route> routes) {
        if (routes.isEmpty()) return routes;
        List<Route> sorted = new ArrayList<>(routes);
        sorted.sort(Comparator.comparingDouble(Route::score).reversed());
        List<Route> reconciled = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Route route = sorted.get(i);
            boolean recommended = i == 0;
            reconciled.add(route.recommended() == recommended ? route : route.withScore(route.score(), recommended));
        }
        return reconciled;
    }

    /**
     * C-2: 경로별 탄소 배출량 + 자가용 대비 감축량.
     * 메트릭은 응답에 실리는 경로 1건당 여기서만 1회 기록한다 (계산기는 순수 함수).
     */
    private CarbonSummary computeCarbon(Route route, Location origin, Location destination) {
        double grams = carbonFootprintCalculator.forRoute(route);
        int totalMeters = route.legs().stream().mapToInt(Leg::distanceMeters).sum();
        double gramsPerKm = totalMeters > 0 ? grams / (totalMeters / 1_000.0) : 0.0;

        double carKm = GeoDistance.kilometers(origin, destination) * CAR_DETOUR_FACTOR;
        double carGrams = carbonFootprintCalculator.forCarDistance(carKm);
        double saved = Math.max(0, carGrams - grams);
        carbonMetrics.record(grams, saved);

        return new CarbonSummary(
                Math.round(grams * 10.0) / 10.0,
                Math.round(gramsPerKm * 10.0) / 10.0,
                carbonFootprintCalculator.isEcoRoute(route),
                Math.round(saved * 10.0) / 10.0
        );
    }
}
