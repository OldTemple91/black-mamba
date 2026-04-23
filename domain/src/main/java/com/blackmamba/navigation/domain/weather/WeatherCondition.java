package com.blackmamba.navigation.domain.weather;

/**
 * A-4: 경로 탐색에 영향을 주는 날씨 조건.
 *
 * <p>세세한 기상 용어가 아니라 <b>이동수단 선택에 영향을 주는 5단계</b> 로 추상화.
 * - CLEAR: 기본 선호도 유지
 * - RAIN: 공유 자전거/킥보드 감점, 지하철·버스 가점
 * - SNOW: 공유 이동수단 강한 감점 (주행 위험)
 * - HEAT: 장거리 도보/자전거 감점 (≥ 35℃)
 * - COLD: 장거리 도보 감점 (≤ -5℃)
 */
public enum WeatherCondition {
    CLEAR,
    RAIN,
    SNOW,
    HEAT,
    COLD,
    UNKNOWN;

    public boolean penalizesSharedMobility() {
        return this == RAIN || this == SNOW;
    }

    public boolean penalizesWalking() {
        return this == RAIN || this == SNOW || this == HEAT || this == COLD;
    }

    public static WeatherCondition parse(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        try {
            return WeatherCondition.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
