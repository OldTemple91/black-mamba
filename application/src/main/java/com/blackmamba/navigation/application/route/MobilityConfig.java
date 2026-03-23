package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.MobilityType;

public record MobilityConfig(
        MobilityType mobilityType,
        int maxRangeMeters,  // 전기자전거: 10000, 킥보드: 5000, 따릉이: 10000
        int minEffectiveDistanceMeters
) {
    public MobilityConfig(MobilityType mobilityType, int maxRangeMeters) {
        this(mobilityType, maxRangeMeters, 0);
    }

    public static MobilityConfig bike() {
        return new MobilityConfig(MobilityType.DDAREUNGI, 10000, 700);
    }

    public static MobilityConfig personalEbike() {
        return new MobilityConfig(MobilityType.PERSONAL_EBIKE, 10000, 500);
    }

    public static MobilityConfig personalKickboard() {
        return new MobilityConfig(MobilityType.PERSONAL_KICKBOARD, 5000, 300);
    }

    public static MobilityConfig kickboard() {
        return new MobilityConfig(MobilityType.KICKBOARD_SHARED, 5000, 500);
    }
}
