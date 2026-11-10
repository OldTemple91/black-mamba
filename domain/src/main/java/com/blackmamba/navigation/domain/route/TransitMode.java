package com.blackmamba.navigation.domain.route;

/**
 * 대중교통 구간의 수단. {@link Leg#mode()} 문자열을 여러 곳에서 "SUBWAY"·"BUS" 리터럴과 비교하던 것을 한 곳으로 모은다.
 */
public enum TransitMode {
    SUBWAY,
    BUS,
    /** 실시간 경로를 얻지 못해 기준 경로·직선거리로 근사한 구간 */
    ESTIMATED,
    /** 대중교통이지만 수단을 모름 */
    OTHER;

    public static TransitMode from(String mode) {
        if (mode == null) {
            return OTHER;
        }
        if ("SUBWAY".equalsIgnoreCase(mode)) {
            return SUBWAY;
        }
        if ("BUS".equalsIgnoreCase(mode)) {
            return BUS;
        }
        if (Leg.ESTIMATED_TRANSIT_MODE.equals(mode)) {
            return ESTIMATED;
        }
        return OTHER;
    }

    public boolean isSubway() {
        return this == SUBWAY;
    }

    public boolean isBus() {
        return this == BUS;
    }
}
