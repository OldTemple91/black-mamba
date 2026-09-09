package com.blackmamba.navigation.domain.route.mobility;

/**
 * {@link MobilityInfo} 의 가용성 정보가 어디서 왔는지.
 * <p>
 * 사용자에게 "이 정보는 실시간이 아닐 수 있다" 를 알려주는 근거가 되므로
 * 문자열이 아닌 enum 으로 고정한다 — 생산(infra)과 소비(application)가 같은 심볼을 본다.
 */
public enum AvailabilitySource {
    /** 방금 조회한 실시간 데이터 */
    LIVE,
    /** 갱신 실패로 만료된 snapshot 을 재사용 */
    STALE,
    /** snapshot 자체가 없어 빈 결과 기준으로 계산 */
    EMPTY,
    /** 실데이터 없이 추정값 (예: 공유 킥보드 API 미제공) */
    ESTIMATED,
    /** 사용자 보유 수단 — 조회 대상 아님 */
    OWNED;

    public boolean isStale() {
        return this == STALE;
    }

    public boolean isEmpty() {
        return this == EMPTY;
    }

    public boolean isEstimated() {
        return this == ESTIMATED;
    }
}
