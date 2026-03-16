package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.MobilityType;

public record MobilityConfig(
        MobilityType mobilityType,
        int maxRangeMeters,  // 킥보드: 5000, 자전거: 10000
        int minEffectiveDistanceMeters
) {
    public MobilityConfig(MobilityType mobilityType, int maxRangeMeters) {
        this(mobilityType, maxRangeMeters, 0);
    }

    public static MobilityConfig kickboard() {
        return new MobilityConfig(MobilityType.KICKBOARD_SHARED, 5000, 500);
    }

    public static MobilityConfig personal() {
        return new MobilityConfig(MobilityType.PERSONAL, 8000, 300);
    }

    public static MobilityConfig bike() {
        return new MobilityConfig(MobilityType.DDAREUNGI, 10000, 700);
    }
}
