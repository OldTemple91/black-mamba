package com.blackmamba.navigation.application.route;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * C-2: 경로 탄소 메트릭 기록 — 응답에 실리는 경로 1건당 정확히 1회.
 * <p>
 * 계산({@link CarbonFootprintCalculator})과 기록을 분리한 이유:
 * 계산기는 친환경 판정·자가용 비교 등에서 같은 경로에 대해 여러 번 호출되므로,
 * 계산기 안에서 기록하면 히스토그램 표본이 실제 경로 수의 몇 배로 부풀려진다.
 */
@Component
public class CarbonMetrics {

    private final DistributionSummary routeCarbonSummary;
    private final DistributionSummary carbonSavedSummary;

    public CarbonMetrics(MeterRegistry meterRegistry) {
        this.routeCarbonSummary = DistributionSummary.builder("navigation.route.carbon.grams")
                .description("경로별 탄소 배출량 (g, 1인 환산)")
                .baseUnit("grams")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
        this.carbonSavedSummary = DistributionSummary.builder("navigation.route.carbon.saved.grams")
                .description("자가용 대비 탄소 감축량 (g)")
                .baseUnit("grams")
                .register(meterRegistry);
    }

    /** 0 이하 값은 표본으로 기록하지 않는다 (도보/따릉이 전용 경로의 0g 가 분포를 왜곡). */
    public void record(double routeGrams, double savedGrams) {
        if (routeGrams > 0) {
            routeCarbonSummary.record(routeGrams);
        }
        if (savedGrams > 0) {
            carbonSavedSummary.record(savedGrams);
        }
    }
}
