package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.weather.WeatherCondition;

/**
 * A-4: 경로 탐색 요청에 첨부되는 날씨 컨텍스트.
 *
 * <p>{@link AccessibilityContext} 와 동일한 패턴으로, 컨트롤러에서 받아
 * 서비스 → 후처리 파이프라인을 타고 내려간다.
 *
 * @param condition    날씨 카테고리 (CLEAR/RAIN/SNOW/HEAT/COLD/UNKNOWN)
 * @param temperatureC 섭씨 온도 (nullable, HEAT/COLD 판정에만 사용)
 */
public record WeatherContext(
        WeatherCondition condition,
        Double temperatureC
) {
    /** 기본값 — 날씨 영향 없음. */
    public static final WeatherContext DEFAULT = new WeatherContext(WeatherCondition.CLEAR, null);

    public static WeatherContext of(WeatherCondition condition) {
        return new WeatherContext(condition == null ? WeatherCondition.UNKNOWN : condition, null);
    }

    public static WeatherContext of(WeatherCondition condition, Double temperatureC) {
        return new WeatherContext(condition == null ? WeatherCondition.UNKNOWN : condition, temperatureC);
    }

    public boolean hasImpact() {
        return condition != null
                && condition != WeatherCondition.CLEAR
                && condition != WeatherCondition.UNKNOWN;
    }
}
