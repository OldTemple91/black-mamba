package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.hub.HubType;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteHub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RouteHubExtractor {

    private RouteHubExtractor() {
    }

    public static List<RouteHub> extract(Route route) {
        Map<String, RouteHub> hubs = new LinkedHashMap<>();

        for (RouteHub selectedHub : route.selectedHubs()) {
            hubs.putIfAbsent(keyOf(selectedHub), selectedHub);
        }

        for (Leg leg : route.legs()) {
            if (leg.type() == LegType.TRANSIT) {
                addHub(hubs, leg.start() != null ? leg.start().name() : null, transitHubType(leg.mode()), "TRANSIT_BOARDING", "actual", Map.of());
                addHub(hubs, leg.end() != null ? leg.end().name() : null, transitHubType(leg.mode()), "TRANSIT_ALIGHTING", "actual", Map.of());
                continue;
            }

            if (leg.type() == LegType.BIKE) {
                String pickupName = leg.mobilityInfo() != null && leg.mobilityInfo().stationName() != null
                        ? leg.mobilityInfo().stationName()
                        : leg.start() != null ? leg.start().name() : null;
                String dropoffName = leg.mobilityInfo() != null && leg.mobilityInfo().dropoffStationName() != null
                        ? leg.mobilityInfo().dropoffStationName()
                        : leg.end() != null ? leg.end().name() : null;
                addHub(hubs, pickupName, HubType.BIKE_STATION, "BIKE_PICKUP", "actual", Map.of());
                addHub(hubs, dropoffName, HubType.BIKE_STATION, "BIKE_DROPOFF", "actual", Map.of());
                continue;
            }

            if (leg.type() == LegType.KICKBOARD) {
                addHub(hubs, leg.start() != null ? leg.start().name() : null, HubType.MOBILITY_TRANSFER_POINT, "KICKBOARD_PICKUP", "actual", Map.of());
                addHub(hubs, leg.end() != null ? leg.end().name() : null, HubType.MOBILITY_TRANSFER_POINT, "KICKBOARD_DROPOFF", "actual", Map.of());
            }
        }

        return new ArrayList<>(hubs.values());
    }

    public static RouteHub fromSelectedHub(Hub hub, String role) {
        return new RouteHub(hub.name(), hub.type(), role, "selected-candidate", hub.metadata());
    }

    private static void addHub(Map<String, RouteHub> hubs, String name, HubType type, String role, String source, Map<String, String> metadata) {
        if (name == null || name.isBlank()) {
            return;
        }
        RouteHub routeHub = new RouteHub(name, type, role, source, metadata);
        hubs.putIfAbsent(keyOf(routeHub), routeHub);
    }

    private static String keyOf(RouteHub hub) {
        return hub.role() + ":" + hub.name();
    }

    private static HubType transitHubType(String mode) {
        if ("SUBWAY".equalsIgnoreCase(mode)) {
            return HubType.SUBWAY_STATION;
        }
        if ("BUS".equalsIgnoreCase(mode)) {
            return HubType.BUS_STOP;
        }
        return HubType.MOBILITY_TRANSFER_POINT;
    }
}
