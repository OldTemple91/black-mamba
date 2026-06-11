package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.domain.location.GeoDistance;

import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;

import java.util.List;

public class DdareungiStationFilter {


    public List<DdareungiStation> filterNearby(List<DdareungiStation> stations,
                                                double lat, double lng, int radiusMeters) {
        return filterNearby(stations, lat, lng, radiusMeters, true);
    }

    public List<DdareungiStation> filterNearby(List<DdareungiStation> stations,
                                               double lat, double lng, int radiusMeters,
                                               boolean requireAvailableBike) {
        return stations.stream()
                .filter(s -> !requireAvailableBike || s.availableCount() > 0)
                .filter(s -> distanceMeters(lat, lng, s.lat(), s.lng()) <= radiusMeters)
                .toList();
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        return GeoDistance.meters(lat1, lng1, lat2, lng2);
    }
}
