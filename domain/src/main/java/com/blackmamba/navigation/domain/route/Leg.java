package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;

public record Leg(
        LegType type,
        String mode,
        int durationMinutes,
        int distanceMeters,
        Location start,
        Location end,
        TransitInfo transitInfo,
        MobilityInfo mobilityInfo
) {}
