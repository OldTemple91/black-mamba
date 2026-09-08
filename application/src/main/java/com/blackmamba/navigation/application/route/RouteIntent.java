package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.MobilityType;

import java.util.List;

/**
 * 자연어 요청을 구조화한 <b>검색 의도(Intent)</b> — 파서(LLM 등)의 출력 계약.
 * <p>
 * 필드는 이미 타입이 확정된 값만 담는다. 모델이 스펙 밖 문자열을 내는 문제는
 * 어댑터가 흡수하고, 여기 도달한 값은 그대로 경로 탐색 파라미터로 쓸 수 있다.
 *
 * @param origin               출발지 한국어 장소명 (필수)
 * @param destination          도착지 한국어 장소명 (필수)
 * @param preference           추천 기준 (null 이면 RELIABILITY)
 * @param mobility             사용자가 명시한 이동수단 (null 이면 빈 목록 = 자동 선택)
 * @param wheelchairAccessible 휠체어 접근성 요구 여부
 * @param walkingSpeedKmh      보행 속도 (노인 3.0, 미지정 시 null)
 */
public record RouteIntent(
        String origin,
        String destination,
        RecommendationPreference preference,
        List<MobilityType> mobility,
        Boolean wheelchairAccessible,
        Double walkingSpeedKmh
) {
    public RouteIntent {
        if (preference == null) preference = RecommendationPreference.RELIABILITY;
        mobility = mobility == null ? List.of() : List.copyOf(mobility);
    }

    public boolean hasExplicitMobility() {
        return !mobility.isEmpty();
    }
}
