package com.blackmamba.navigation.domain.route.mobility;

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
        double dropoffLng,
        AvailabilitySource availabilitySource
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
                0.0,
                null
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
                lng,
                availabilitySource
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

    public boolean isDdareungi() {
        return mobilityType == MobilityType.DDAREUNGI;
    }

    public boolean isSharedKickboard() {
        return mobilityType == MobilityType.KICKBOARD_SHARED;
    }

    public boolean hasStaleAvailability() {
        return availabilitySource != null && availabilitySource.isStale();
    }

    public boolean hasEmptyAvailability() {
        return availabilitySource != null && availabilitySource.isEmpty();
    }

    public boolean hasEstimatedAvailability() {
        return availabilitySource != null && availabilitySource.isEstimated();
    }
}
