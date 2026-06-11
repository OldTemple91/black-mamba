package com.blackmamba.navigation.domain.location;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoDistanceTest {

    private final Location seoulStation = new Location("서울역", 37.5547, 126.9706);
    private final Location gangnam = new Location("강남역", 37.4979, 127.0276);

    @Test
    void 서울역_강남역_직선거리는_약_8km_다() {
        double meters = GeoDistance.meters(seoulStation, gangnam);

        // 실측 직선거리 약 8.1km — 공식 회귀 감지용 허용 오차 ±300m
        assertThat(meters).isCloseTo(8_100, within(300.0));
    }

    @Test
    void 같은_좌표는_0이다() {
        assertThat(GeoDistance.meters(seoulStation, seoulStation)).isZero();
    }

    @Test
    void Location_오버로드와_좌표_오버로드는_같은_값이다() {
        double byLocation = GeoDistance.meters(seoulStation, gangnam);
        double byCoords = GeoDistance.meters(
                seoulStation.lat(), seoulStation.lng(), gangnam.lat(), gangnam.lng());

        assertThat(byLocation).isEqualTo(byCoords);
    }

    @Test
    void kilometers_는_meters_나누기_1000_이다() {
        double meters = GeoDistance.meters(seoulStation, gangnam);
        double km = GeoDistance.kilometers(seoulStation, gangnam);

        assertThat(km).isCloseTo(meters / 1_000.0, within(1e-9));
    }
}
