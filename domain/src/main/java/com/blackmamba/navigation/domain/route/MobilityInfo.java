package com.blackmamba.navigation.domain.route;

public record MobilityInfo(
        MobilityType mobilityType,
        String operatorName,
        String deviceId,
        int batteryLevel,
        String stationId,
        String stationName,
        int rackTotalCount,
        double lat,
        double lng,
        int availableCount,
        int distanceMeters,
        String dropoffStationId,
        String dropoffStationName,
        double dropoffLat,
        double dropoffLng
) {
    public MobilityInfo(MobilityType mobilityType,
                        String operatorName,
                        String deviceId,
                        int batteryLevel,
                        String stationName,
                        double lat,
                        double lng,
                        int availableCount,
                        int distanceMeters) {
        this(
                mobilityType,
                operatorName,
                deviceId,
                batteryLevel,
                null,
                stationName,
                0,
                lat,
                lng,
                availableCount,
                distanceMeters,
                null,
                null,
                0.0,
                0.0
        );
    }

    public MobilityInfo withDropoffStation(String stationId, String stationName, double lat, double lng) {
        return new MobilityInfo(
                mobilityType,
                operatorName,
                deviceId,
                batteryLevel,
                this.stationId,
                this.stationName,
                rackTotalCount,
                this.lat,
                this.lng,
                availableCount,
                distanceMeters,
                stationId,
                stationName,
                lat,
                lng
        );
    }

    public boolean hasDropoffStation() {
        return dropoffStationName != null && !dropoffStationName.isBlank();
    }

    public boolean hasSamePickupAndDropoffStation() {
        if (!hasDropoffStation()) {
            return false;
        }
        if (stationId != null && !stationId.isBlank() && dropoffStationId != null && !dropoffStationId.isBlank()) {
            return stationId.equals(dropoffStationId);
        }
        if (stationName != null && !stationName.isBlank() && dropoffStationName != null && !dropoffStationName.isBlank()) {
            return stationName.equals(dropoffStationName);
        }
        return false;
    }
}
