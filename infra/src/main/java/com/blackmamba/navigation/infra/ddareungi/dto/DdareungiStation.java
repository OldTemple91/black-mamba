package com.blackmamba.navigation.infra.ddareungi.dto;

public record DdareungiStation(
        String stationName,
        double lat,
        double lng,
        int availableCount
) {}
