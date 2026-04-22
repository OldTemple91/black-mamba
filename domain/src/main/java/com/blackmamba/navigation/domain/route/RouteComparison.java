package com.blackmamba.navigation.domain.route;

/**
 * 특정 경로(Route)와 자가용(CarReference) 간의 비교 결과.
 * <p>
 * "자가용 대비 +5분, -6,500원, -2.3kg CO₂" 형태의 설득 메시지를 생성하기 위한 데이터.
 * Route.comparison 필드에 첨부되어 프론트엔드에서 렌더링된다.
 *
 * @param car                 자가용 기준값
 * @param timeDiffMinutes     자가용 - 이 경로 시간차. 음수면 자가용이 더 빠름
 * @param costSavedWon        자가용 - 이 경로 비용차. 양수면 절약
 * @param co2ReducedGrams     자가용 - 이 경로 CO₂ 차이. 양수면 감소
 * @param narrative           사용자에게 보여줄 한 줄 요약 메시지 (KR)
 */
public record RouteComparison(
        CarReference car,
        int timeDiffMinutes,
        int costSavedWon,
        double co2ReducedGrams,
        String narrative
) {
    /** narrative 만 교체한 새 인스턴스. RAG Phase 4 에서 LLM 출력으로 덮어쓸 때 사용. */
    public RouteComparison withNarrative(String newNarrative) {
        return new RouteComparison(car, timeDiffMinutes, costSavedWon, co2ReducedGrams, newNarrative);
    }
}
