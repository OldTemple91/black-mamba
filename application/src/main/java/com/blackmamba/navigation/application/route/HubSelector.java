package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.hub.HubType;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class HubSelector {

    private static final int DEFAULT_HUB_RADIUS_METERS = 150;

    private final CandidatePointSelector candidatePointSelector;

    public HubSelector(CandidatePointSelector candidatePointSelector) {
        this.candidatePointSelector = candidatePointSelector;
    }

    public List<Hub> selectLastMileHubs(List<Leg> legs, MobilityConfig config) {
        return candidatePointSelector.select(legs, config).stream()
                .map(location -> toTransitHub(location, legs, config, "LAST_MILE"))
                .toList();
    }

    public List<Hub> selectFirstMileHubs(Location origin, List<Leg> legs, MobilityConfig config) {
        return candidatePointSelector.selectFirstMile(origin, legs, config).stream()
                .map(location -> toTransitHub(location, legs, config, "FIRST_MILE"))
                .toList();
    }

    public Hub toMobilityTransferHub(Location location) {
        return new Hub(
                hubId(location),
                location.name(),
                HubType.MOBILITY_TRANSFER_POINT,
                location,
                DEFAULT_HUB_RADIUS_METERS,
                Map.of("source", "mobility-transfer")
        );
    }

    private Hub toTransitHub(Location location, List<Leg> legs, MobilityConfig config, String selectionPhase) {
        HubType type = inferTransitHubType(location, legs);
        return new Hub(
                hubId(location),
                location.name(),
                type,
                location,
                DEFAULT_HUB_RADIUS_METERS,
                Map.of(
                        "source", "baseline-transit-candidate",
                        "preferredMobility", config.mobilityType().name(),
                        "selectionPhase", selectionPhase,
                        "transitHubType", type.name()
                )
        );
    }

    private HubType inferTransitHubType(Location location, List<Leg> legs) {
        String name = location.name() == null ? "" : location.name();
        if (name.matches("^\\d+\\..*")) return HubType.BIKE_STATION;
        if (name.contains("역") && !name.contains("사거리") && !name.contains("빌딩")) {
            return HubType.SUBWAY_STATION;
        }

        return legs.stream()
                .filter(leg -> leg.type() == LegType.TRANSIT)
                .filter(leg -> isNear(location, leg.start()) || isNear(location, leg.end()))
                .findFirst()
                .map(Leg::mode)
                .map(this::toHubType)
                .orElse(HubType.MOBILITY_TRANSFER_POINT);
    }

    private HubType toHubType(String mode) {
        if ("SUBWAY".equalsIgnoreCase(mode)) return HubType.SUBWAY_STATION;
        if ("BUS".equalsIgnoreCase(mode)) return HubType.BUS_STOP;
        return HubType.MOBILITY_TRANSFER_POINT;
    }

    private boolean isNear(Location a, Location b) {
        if (a == null || b == null) return false;
        return Math.abs(a.lat() - b.lat()) < 0.0015 && Math.abs(a.lng() - b.lng()) < 0.0015;
    }

    private String hubId(Location location) {
        return UUID.nameUUIDFromBytes((location.name() + ":" + location.lat() + ":" + location.lng()).getBytes()).toString();
    }
}
