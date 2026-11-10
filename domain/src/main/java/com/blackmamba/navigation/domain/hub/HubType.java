package com.blackmamba.navigation.domain.hub;

import com.blackmamba.navigation.domain.route.TransitMode;

public enum HubType {
    SUBWAY_STATION,
    BUS_STOP,
    BIKE_STATION,
    MOBILITY_TRANSFER_POINT,
    CARSHARE_ZONE,
    CHARGING_STATION,
    PARKING;

    /** 대중교통 구간의 승하차 지점이 어떤 거점인가. 수단을 모르면 일반 환승 지점. */
    public static HubType from(TransitMode mode) {
        return switch (mode) {
            case SUBWAY -> SUBWAY_STATION;
            case BUS -> BUS_STOP;
            case ESTIMATED, OTHER -> MOBILITY_TRANSFER_POINT;
        };
    }
}
