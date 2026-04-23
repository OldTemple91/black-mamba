package com.blackmamba.navigation.domain.route;

/**
 * C-2: 경로별 탄소 배출량 요약.
 *
 * <p>"자가용 대비" 가 아니라 <b>이 경로 자체의 절대 CO₂</b> 를 표현한다.
 * 프론트엔드는 {@code grams} 값으로 친환경 뱃지 / Carbon Budget 위젯을 렌더링한다.
 *
 * @param grams           경로 전체 CO₂ 배출량 (g, 1인 환산)
 * @param gramsPerKm      평균 탄소 강도 (g/km)
 * @param eco             친환경 기준 (< 20 g/km) 통과 여부
 * @param savedVsCarGrams 동일 거리 자가용 대비 절감량 (g). 양수면 감축.
 */
public record CarbonSummary(
        double grams,
        double gramsPerKm,
        boolean eco,
        double savedVsCarGrams
) {
    public static CarbonSummary empty() {
        return new CarbonSummary(0.0, 0.0, true, 0.0);
    }
}
