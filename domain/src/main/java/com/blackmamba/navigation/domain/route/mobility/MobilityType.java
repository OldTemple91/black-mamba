package com.blackmamba.navigation.domain.route.mobility;

public enum MobilityType {
    DDAREUNGI("따릉이"),                   // 공공 따릉이 (실 API, 일반 자전거 15 km/h)
    PERSONAL_EBIKE("개인 전기자전거"),      // 개인 전기자전거 (API 불필요, 22 km/h)
    PERSONAL_KICKBOARD("개인 킥보드"),      // 개인 전동킥보드 (API 불필요, 20 km/h)
    KICKBOARD_SHARED("공유 킥보드");        // 공유 킥보드 (TAGO API — 서울 미제공, tago.enabled=false 로 호출 스킵)

    private final String displayName;

    MobilityType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 사용자에게 보이는 이름 — 진단 문구·LLM 프롬프트·운영사 표기가 같은 수단을 다르게 부르지 않게 한 곳에 둔다.
     * (예전엔 진단은 "개인 전기자전거", 프롬프트는 "전기자전거", 공유 킥보드는 프롬프트에 enum 이름이 그대로 나갔다)
     */
    public String displayName() {
        return displayName;
    }

    /** 정해진 정류소에 반납해야 하는 수단 — 도착 지점에 반납 정류소가 없으면 구간이 성립하지 않는다. */
    public boolean requiresDropoffStation() {
        return this == DDAREUNGI;
    }

    public boolean isKickboard() {
        return this == KICKBOARD_SHARED || this == PERSONAL_KICKBOARD;
    }

    /** 재고·반납·배터리를 서비스가 보장하지 않는 공유 수단 — 신뢰도 페널티 대상. */
    public boolean isShared() {
        return this == DDAREUNGI || this == KICKBOARD_SHARED;
    }

    /** 사용자 보유 수단 — 가용성·배터리는 시스템이 모르므로 그에 관한 근거를 만들지 않는다. */
    public boolean isPersonal() {
        return this == PERSONAL_EBIKE || this == PERSONAL_KICKBOARD;
    }
}
