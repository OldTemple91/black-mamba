package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.hub.HubType;

import java.util.Map;

public record RouteHub(
        String name,
        HubType type,
        String role,
        String source,
        Map<String, String> metadata
) {
    public RouteHub {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
