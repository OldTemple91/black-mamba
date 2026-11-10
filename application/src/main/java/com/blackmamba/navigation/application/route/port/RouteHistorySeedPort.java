package com.blackmamba.navigation.application.route.port;

/**
 * 데모용 경로 이력 시드 적재.
 * <p>
 * 관리 엔드포인트(api)가 infra 의 시더 구현을 직접 참조하지 않도록 둔 포트 —
 * api → application ← infra 방향을 ArchUnit 으로 잠근다.
 */
public interface RouteHistorySeedPort {

    /** @return 실제로 저장에 성공한 건수. 벡터 저장소가 없으면 0. */
    int seed();
}
