package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.hub.MobilityConfig;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;

/**
 * 이동수단 타입 → 탐색 설정/경로 타입/표시명 매핑.
 * <p>
 * 두 전략이 각자 들고 있던 switch 를 한곳으로 모았다. 특히 SPECIFIC 전략은
 * "킥보드 계열이 아니면 무조건 따릉이 설정" 으로 분기해 PERSONAL_EBIKE 가
 * 따릉이 설정(minEffective 700m)으로 탐색되는 드리프트가 있었다 — 여기서는
 * 네 타입을 모두 명시 매핑한다.
 */
final class MobilityConfigs {

    private MobilityConfigs() {
    }

    static MobilityConfig forType(MobilityType type) {
        return switch (type) {
            case PERSONAL_EBIKE     -> MobilityConfig.personalEbike();
            case PERSONAL_KICKBOARD -> MobilityConfig.personalKickboard();
            case KICKBOARD_SHARED   -> MobilityConfig.kickboard();
            case DDAREUNGI          -> MobilityConfig.bike();
        };
    }

    static RouteType routeTypeFor(MobilityType type) {
        if (type.isKickboard()) {
            return RouteType.TRANSIT_WITH_KICKBOARD;
        }
        return RouteType.TRANSIT_WITH_BIKE;
    }
}
