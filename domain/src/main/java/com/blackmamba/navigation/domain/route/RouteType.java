package com.blackmamba.navigation.domain.route;

public enum RouteType {
    TRANSIT_ONLY,
    TRANSIT_WITH_BIKE,
    TRANSIT_WITH_KICKBOARD,
    BIKE_FIRST_TRANSIT,
    MOBILITY_FIRST_TRANSIT,     // 신규: 퍼스트마일 (이동수단→대중교통)
    MOBILITY_TRANSIT_MOBILITY,  // 신규: 퍼스트+라스트 (이동수단→대중교통→이동수단)
    MOBILITY_ONLY               // 신규: 이동수단만
}
