package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.accessibility.AccessibilityContext;
import com.blackmamba.navigation.application.route.accessibility.AccessibilityPostProcessor;
import com.blackmamba.navigation.application.route.weather.WeatherAwareRouteAdjuster;
import com.blackmamba.navigation.application.route.weather.WeatherContext;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.route.RouteType;
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

    private final AccessibilityPostProcessor accessibilityPostProcessor;
    private final WeatherAwareRouteAdjuster weatherAwareRouteAdjuster;
    private final CarbonFootprintCalculator carbonFootprintCalculator;
    private final CarReferenceCalculator carReferenceCalculator;
    private final CarbonMetrics carbonMetrics;
    private final RouteEvaluator routeEvaluator;

    public RouteEnrichmentPipeline(RouteEvaluator routeEvaluator,
                                   AccessibilityPostProcessor accessibilityPostProcessor,
                                   WeatherAwareRouteAdjuster weatherAwareRouteAdjuster,
                                   CarbonFootprintCalculator carbonFootprintCalculator,
                                   CarReferenceCalculator carReferenceCalculator,
                                   CarbonMetrics carbonMetrics) {
        this.routeEvaluator = routeEvaluator;
        this.accessibilityPostProcessor = accessibilityPostProcessor;
        this.weatherAwareRouteAdjuster = weatherAwareRouteAdjuster;
        this.carbonFootprintCalculator = carbonFootprintCalculator;
        this.carReferenceCalculator = carReferenceCalculator;
        this.carbonMetrics = carbonMetrics;
    }

    public List<Route> enrich(List<Route> routes,
                              Location origin,
                              Location destination,
                              RecommendationPreference preference,
                              AccessibilityContext accessibilityContext,
                              WeatherContext weatherContext) {
        List<Route> accessible = accessibilityPostProcessor.apply(routes, accessibilityContext);
        if (accessibilityContext.walkingSpeedKmh() != null) {
            accessible = reEvaluate(accessible, preference);
        }
        List<Route> weatherAdjusted = weatherAwareRouteAdjuster.apply(accessible, weatherContext);
        List<Route> ranked = weatherAwareRouteAdjuster.annotate(reconcileRecommendation(weatherAdjusted, preference), weatherContext);
        return ranked.stream()
                .map(route -> route.withCarbon(computeCarbon(route, origin, destination)))
                .map(route -> route.withCarComparison(carReferenceCalculator.compareWithRoute(route, origin, destination)))
                .toList();
    }

    /**
     * 점수 내림차순으로 정렬하고 1위에만 recommended=true 를 둔다.
     * 전략이 매긴 플래그는 날씨 감점·접근성 필터 이전 기준이라 그대로 두면
     * 2위가 추천 배지를 달거나 추천 경로가 아예 없는 응답이 나간다.
     */
    private List<Route> reconcileRecommendation(List<Route> routes, RecommendationPreference preference) {
        if (routes.isEmpty()) return routes;
        List<Route> sorted = new ArrayList<>(routes);
        sorted.sort(Comparator.comparingDouble(Route::score).reversed());
        Route baseline = baselineOf(sorted);
        List<Route> reconciled = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Route route = sorted.get(i);
            boolean recommended = i == 0;
            if (route.recommended() == recommended) {
                reconciled.add(route);
                continue;
            }
            // 추천 여부가 바뀐 경로는 근거·배지 문구도 다시 만든다 — 플래그만 뒤집으면 2위가 "추천" 문구를 달고 나간다
            reconciled.add(routeEvaluator.reRecommend(route, baselineOr(baseline, route), recommended, preference));
        }
        return reconciled;
    }

    /**
     * 보행 속도 반영으로 소요시간이 바뀌면 점수·비교·근거를 바뀐 시간으로 다시 평가한다.
     * 시간만 고치고 예전 점수로 정렬하면 느린 보행자에게도 순위가 그대로이고,
     * "대중교통 대비 N분 단축" 은 조정 전 기준 경로와 비교한 값이 된다.
     */
    private List<Route> reEvaluate(List<Route> routes, RecommendationPreference preference) {
        Route baseline = baselineOf(routes);
        return routes.stream()
                .map(route -> {
                    Route base = baselineOr(baseline, route);
                    return routeEvaluator.evaluate(route, base, base.totalMinutes(), route.recommended(), preference);
                })
                .toList();
    }

    /** 비교 기준인 대중교통 단독 경로. 접근성 필터로 빠졌거나 이동수단 단독 탐색이면 없다(null). */
    private static Route baselineOf(List<Route> routes) {
        return routes.stream()
                .filter(route -> route.type() == RouteType.TRANSIT_ONLY)
                .findFirst()
                .orElse(null);
    }

    private static Route baselineOr(Route baseline, Route route) {
        if (baseline == null) {
            return route;
        }
        return baseline;
    }

    /**
     * C-2: 경로별 탄소 배출량 + 자가용 대비 감축량.
     * 메트릭은 응답에 실리는 경로 1건당 여기서만 1회 기록한다 (계산기는 순수 함수).
     */
    private CarbonSummary computeCarbon(Route route, Location origin, Location destination) {
        double grams = carbonFootprintCalculator.forRoute(route);
        int totalMeters = route.legs().stream().mapToInt(Leg::distanceMeters).sum();
        double gramsPerKm = totalMeters > 0 ? grams / (totalMeters / 1_000.0) : 0.0;

        double carKm = carReferenceCalculator.carDistanceKm(origin, destination);
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
