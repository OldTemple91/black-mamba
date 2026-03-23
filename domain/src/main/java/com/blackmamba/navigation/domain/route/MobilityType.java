package com.blackmamba.navigation.domain.route;

public enum MobilityType {
    DDAREUNGI,          // 공공 따릉이 (실 API, 일반 자전거 15 km/h)
    PERSONAL_EBIKE,     // 개인 전기자전거 (API 불필요, 22 km/h)
    PERSONAL_KICKBOARD, // 개인 전동킥보드 (API 불필요, 20 km/h)
    KICKBOARD_SHARED    // 공유 킥보드 (TAGO API — 서울 데이터 미제공, 호출 차단)
}
