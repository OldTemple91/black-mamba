package com.blackmamba.navigation.application.route.port;

/**
 * 대중교통 조회 API(일일 호출 한도)의 남은 예산 수준과, 그 수준에서 탐색을 얼마나 넓게 할지의 규칙.
 * <p>
 * OPTIMAL 탐색 한 번은 기준 경로 1회 + 후보 허브마다 부분 구간 조회로 최대 12회를 쓴다. 한도를 다 쓰면
 * 그 뒤 모든 요청의 대중교통 구간이 추정값이 된다 — 예산이 줄면 후보를 줄여 "기준 경로만큼은 실제 값" 인 요청을 오래 유지한다.
 */
public enum BudgetLevel {
    /** 여유 있음 — 탐색 폭 제한 없음 */
    NORMAL,
    /** 얼마 남지 않음 — 후보 허브를 줄이고 호출이 많은 패턴을 건너뛴다 */
    SAVING,
    /** 소진 — 조회는 이미 막혀 있다. 후보를 최소로 줄여 헛일을 줄인다 */
    EXHAUSTED;

    private static final int SAVING_CANDIDATE_HUBS = 2;
    private static final int EXHAUSTED_CANDIDATE_HUBS = 1;

    /** 한 단계(퍼스트/라스트마일)에서 시도할 후보 허브 수. */
    public int candidateHubLimit(int normalLimit) {
        return switch (this) {
            case NORMAL -> normalLimit;
            case SAVING -> Math.min(normalLimit, SAVING_CANDIDATE_HUBS);
            case EXHAUSTED -> Math.min(normalLimit, EXHAUSTED_CANDIDATE_HUBS);
        };
    }

    /** 양쪽 이동수단 패턴(D)은 후보 대비 채택률이 낮다 — 예산에 여유가 있을 때만 시도한다. */
    public boolean allowsBothEndsPattern() {
        return this == NORMAL;
    }
}
