package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.MobilityType;

public record MobilityConfig(
        MobilityType mobilityType,
        int maxRangeMeters  // 킥보드: 5000, 자전거: 10000
) {
    public static MobilityConfig kickboard() {
        return new MobilityConfig(MobilityType.KICKBOARD_SHARED, 5000);
    }

    public static MobilityConfig bike() {
        return new MobilityConfig(MobilityType.DDAREUNGI, 10000);
    }
}
