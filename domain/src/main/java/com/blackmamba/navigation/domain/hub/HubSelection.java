package com.blackmamba.navigation.domain.hub;

/**
 * 허브가 후보로 뽑힌 경위 — 어떤 전략으로, 몇 번째로.
 *
 * @param strategy 선택 전략
 * @param rank     그 전략 안에서의 순위 (1부터)
 */
public record HubSelection(Strategy strategy, int rank) {

    public enum Strategy {
        /** 기준 경로의 정류장 중 이동수단 조건을 만족해 뽑힘 */
        PRIMARY,
        /** 조건을 만족하는 정류장이 없어 가장 가까운 것으로 대체 */
        FALLBACK_NEAREST
    }

    public boolean isPrimary() {
        return strategy == Strategy.PRIMARY;
    }

    public boolean isFallback() {
        return strategy == Strategy.FALLBACK_NEAREST;
    }
}
