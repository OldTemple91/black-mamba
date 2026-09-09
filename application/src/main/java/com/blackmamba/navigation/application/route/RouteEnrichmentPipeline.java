package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.GeoDistance;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.CarReferenceCalculator;
import com.blackmamba.navigation.domain.route.CarbonFootprintCalculator;
import com.blackmamba.navigation.domain.route.CarbonSummary;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 전략이 만든 경로 목록에 적용되는 후처리 파이프라인 — 순서가 의미를 가진다.
 * <ol>
 *   <li>접근성 (휠체어/보행 속도) — 경로 필터·조정</li>
 *   <li>날씨 — 공유 모빌리티·장거리 도보 페널티 (A-4)</li>
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
        return weatherAdjusted.stream()
                .map(route -> route.withCarbon(computeCarbon(route, origin, destination)))
                .map(route -> route.withCarComparison(carReferenceCalculator.compareWithRoute(route, origin, destination)))
                .toList();
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
