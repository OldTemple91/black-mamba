package com.blackmamba.navigation.infra.ddareungi.dto;

public record DdareungiStation(
        String stationId,
        String stationName,
        double lat,
        double lng,
        int availableCount,
        int rackTotalCount
) {
    public DdareungiStation(String stationName, double lat, double lng, int availableCount) {
        this(null, stationName, lat, lng, availableCount, 0);
    }
}
