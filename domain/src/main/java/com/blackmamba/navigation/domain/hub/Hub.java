package com.blackmamba.navigation.domain.hub;

import com.blackmamba.navigation.domain.location.Location;

import java.util.Map;

public record Hub(
        String hubId,
        String name,
        HubType type,
        Location location,
        int radiusMeters,
        Map<String, String> metadata
) {
    public Hub {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
