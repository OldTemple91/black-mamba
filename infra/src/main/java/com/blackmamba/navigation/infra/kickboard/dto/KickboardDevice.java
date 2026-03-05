package com.blackmamba.navigation.infra.kickboard.dto;

public record KickboardDevice(
        String deviceId,
        String operatorName,
        double lat,
        double lng,
        int batteryLevel
) {}
