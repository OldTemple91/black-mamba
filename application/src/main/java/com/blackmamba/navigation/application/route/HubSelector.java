package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.HubSearchPort;
import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.hub.HubType;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class HubSelector {

    private static final int DEFAULT_HUB_RADIUS_METERS = 150;

    private final HubSearchPort hubSearchPort;

    public HubSelector(HubSearchPort hubSearchPort) {
        this.hubSearchPort = hubSearchPort;
    }

    public List<Hub> selectLastMileHubs(List<Leg> legs, Location destination, MobilityConfig config) {
        List<Location> primaryCandidates = hubSearchPort.findLastMilePrimaryCandidates(legs, destination, config);
        if (!primaryCandidates.isEmpty()) {
            return toTransitHubs(primaryCandidates, legs, config, destination, "LAST_MILE", "PRIMARY");
        }

        return toTransitHubs(
                hubSearchPort.findLastMileFallbackCandidates(destination, legs, config),
                legs,
                config,
                destination,
                "LAST_MILE",
                "FALLBACK_NEAREST"
        );
    }

    public List<Hub> selectFirstMileHubs(Location origin, List<Leg> legs, MobilityConfig config) {
        List<Location> primaryCandidates = hubSearchPort.findFirstMilePrimaryCandidates(origin, legs, config);
        if (!primaryCandidates.isEmpty()) {
            return toTransitHubs(primaryCandidates, legs, config, origin, "FIRST_MILE", "PRIMARY");
        }

        return toTransitHubs(
                hubSearchPort.findFirstMileFallbackCandidates(origin, legs, config),
                legs,
                config,
                origin,
                "FIRST_MILE",
                "FALLBACK_NEAREST"
        );
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

    private List<Hub> toTransitHubs(List<Location> candidates,
                                    List<Leg> legs,
                                    MobilityConfig config,
                                    Location anchor,
                                    String selectionPhase,
                                    String selectionStrategy) {
        int candidateCount = candidates.size();
        return java.util.stream.IntStream.range(0, candidates.size())
                .mapToObj(index -> toTransitHub(
                        candidates.get(index),
                        legs,
                        config,
                        anchor,
                        selectionPhase,
                        selectionStrategy,
                        index + 1,
                        candidateCount
                ))
                .toList();
    }

    private Hub toTransitHub(Location location,
                             List<Leg> legs,
                             MobilityConfig config,
                             Location anchor,
                             String selectionPhase,
                             String selectionStrategy,
                             int selectionRank,
                             int candidateCount) {
        HubType type = inferTransitHubType(location, legs);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("source", "baseline-transit-candidate");
        metadata.put("preferredMobility", config.mobilityType().name());
        metadata.put("selectionPhase", selectionPhase);
        metadata.put("selectionStrategy", selectionStrategy);
        metadata.put("selectionRank", String.valueOf(selectionRank));
        metadata.put("candidateCount", String.valueOf(candidateCount));
        metadata.put("distanceToAnchorMeters", String.valueOf((int) Math.round(distanceMeters(location, anchor))));
        metadata.put("transitHubType", type.name());
        return new Hub(
                hubId(location),
                location.name(),
                type,
                location,
                DEFAULT_HUB_RADIUS_METERS,
                metadata
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

    private double distanceMeters(Location a, Location b) {
        if (a == null || b == null) return 0;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double x = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
    }

    private String hubId(Location location) {
        return UUID.nameUUIDFromBytes((location.name() + ":" + location.lat() + ":" + location.lng()).getBytes()).toString();
    }
}
