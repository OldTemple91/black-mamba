package com.blackmamba.navigation.domain.route;

import java.util.Map;

/**
 * 같은 출발/도착지를 <b>자가용</b>으로 이동했을 때의 기준값.
 * <p>
 * MaaS 서비스가 "자가용 대체"를 설득하려면 사용자에게 비교 기준을 제시해야 한다.
 * Black Mamba는 모든 경로에 {@link CarReference}를 첨부해, 프론트엔드가
 * "자가용 대비 +Xmin / -Y원 / -Zg CO2" 같은 설득형 정보를 렌더링할 수 있게 한다.
 *
 * @param estimatedMinutes  자가용 예상 소요시간(분) — 직선거리 × 우회계수 / 도심·외곽 평균속도
 * @param estimatedCostWon  연료비 + 주차비 + (장거리 시) 톨게이트
 * @param estimatedCo2Grams 자가용 CO₂ 배출량 (거리 × 171 g/km, 환경부 평균)
 * @param costBreakdown     비용 구성 요소별 분해 (fuel / parking / toll). 변경 가능성 위해 Map
 */
public record CarReference(
        int estimatedMinutes,
        int estimatedCostWon,
        double estimatedCo2Grams,
        Map<String, Integer> costBreakdown
) {
    public CarReference {
        costBreakdown = Map.copyOf(costBreakdown);   // 불변 보장
    }
}
