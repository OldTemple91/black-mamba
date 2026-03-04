package com.blackmamba.navigation.domain.route;

public record MobilityInfo(
        MobilityType mobilityType,
        String operatorName,
        String deviceId,
        int batteryLevel,
        String stationName,
        double lat,
        double lng,
        int availableCount,
        int distanceMeters
) {}
