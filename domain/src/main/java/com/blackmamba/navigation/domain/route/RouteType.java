package com.blackmamba.navigation.domain.route;

public enum RouteType {
    TRANSIT_ONLY,

    // SpecificMobilityStrategy (사용자 이동수단 선택 모드)
    TRANSIT_WITH_BIKE,          // 대중교통 + 따릉이 라스트마일
    TRANSIT_WITH_KICKBOARD,     // 대중교통 + 킥보드 라스트마일

    // OptimalSearchStrategy (최적 탐색 모드, 패턴 B/C/D/E)
    MOBILITY_FIRST_TRANSIT,     // 패턴 B: 이동수단 퍼스트마일 → 대중교통
    MOBILITY_TRANSIT_MOBILITY,  // 패턴 D: 이동수단 + 대중교통 + 이동수단
    MOBILITY_ONLY               // 패턴 E: 이동수단만
}
