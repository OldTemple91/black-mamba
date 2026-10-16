package com.blackmamba.navigation.domain.route.mobility;

public enum MobilityType {
    DDAREUNGI,          // 공공 따릉이 (실 API, 일반 자전거 15 km/h)
    PERSONAL_EBIKE,     // 개인 전기자전거 (API 불필요, 22 km/h)
    PERSONAL_KICKBOARD, // 개인 전동킥보드 (API 불필요, 20 km/h)
    KICKBOARD_SHARED;   // 공유 킥보드 (TAGO API — 서울 미제공, tago.enabled=false 로 호출 스킵)

    /** 재고·반납·배터리를 서비스가 보장하지 않는 공유 수단 — 신뢰도 페널티 대상. */
    public boolean isShared() {
        return this == DDAREUNGI || this == KICKBOARD_SHARED;
    }

    /** 사용자 보유 수단 — 가용성·배터리는 시스템이 모르므로 그에 관한 근거를 만들지 않는다. */
    public boolean isPersonal() {
        return this == PERSONAL_EBIKE || this == PERSONAL_KICKBOARD;
    }
}
