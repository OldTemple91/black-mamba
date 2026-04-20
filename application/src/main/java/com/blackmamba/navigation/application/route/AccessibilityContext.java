package com.blackmamba.navigation.application.route;

/**
 * 접근성(Accessibility) 요청 컨텍스트.
 * <p>
 * MaaS의 본질은 "운전 못 하는 사람까지 자유롭게 이동하도록 돕는 것".
 * 노인/장애인/유모차 동반자 등의 이동 제약을 반영한 경로를 생성하기 위한 파라미터 집합.
 *
 * @param wheelchairAccessible  true면 엘리베이터 없는 지하철역을 환승 후보에서 제외
 * @param walkingSpeedKmh       도보 속도 (null이면 기본 4.5 km/h). 노인 약 3.0 km/h
 */
public record AccessibilityContext(
        boolean wheelchairAccessible,
        Double walkingSpeedKmh
) {
    /** 제약 없음 (기본값) */
    public static final AccessibilityContext DEFAULT = new AccessibilityContext(false, null);

    public static AccessibilityContext of(Boolean wheelchairAccessible, Double walkingSpeedKmh) {
        return new AccessibilityContext(
                Boolean.TRUE.equals(wheelchairAccessible),
                walkingSpeedKmh
        );
    }

    public boolean hasAnyConstraint() {
        return wheelchairAccessible || walkingSpeedKmh != null;
    }
}
