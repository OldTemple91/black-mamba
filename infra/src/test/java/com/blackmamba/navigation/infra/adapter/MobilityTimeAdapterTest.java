package com.blackmamba.navigation.infra.adapter;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityRouteResult;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.infra.tmap.TmapPedestrianClient;
import com.blackmamba.navigation.infra.tmap.TmapPedestrianClient.TmapRouteData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class MobilityTimeAdapterTest {

    @Mock
    private TmapPedestrianClient tmapClient;

    @InjectMocks
    private MobilityTimeAdapter adapter;

    private static final Location ORIGIN = new Location("출발지", 37.5000, 127.0000);
    private static final Location DESTINATION = new Location("도착지", 37.5100, 127.0100);

    @Test
    void 전기자전거_속도는_22kmh_기준이다() {
        // 5km 도로 거리 → ceil(5 / 22 * 60) = ceil(13.636) = 14분
        given(tmapClient.getRoute(any(), any()))
                .willReturn(Mono.just(Optional.of(new TmapRouteData(5000, List.of()))));

        MobilityRouteResult result = adapter.getMobilityRoute(ORIGIN, DESTINATION, MobilityType.PERSONAL_EBIKE).block();

        assertThat(result.durationMinutes()).isEqualTo(14);
        assertThat(result.distanceMeters()).isEqualTo(5000);
    }

    @Test
    void 개인킥보드_속도는_20kmh_기준이다() {
        // 5km 도로 거리 → ceil(5 / 20 * 60) = ceil(15.0) = 15분
        given(tmapClient.getRoute(any(), any()))
                .willReturn(Mono.just(Optional.of(new TmapRouteData(5000, List.of()))));

        MobilityRouteResult result = adapter.getMobilityRoute(ORIGIN, DESTINATION, MobilityType.PERSONAL_KICKBOARD).block();

        assertThat(result.durationMinutes()).isEqualTo(15);
        assertThat(result.distanceMeters()).isEqualTo(5000);
    }

    @Test
    void 따릉이_속도는_15kmh_기준이다() {
        // 5km 도로 거리 → ceil(5 / 15 * 60) = ceil(20.0) = 20분
        given(tmapClient.getRoute(any(), any()))
                .willReturn(Mono.just(Optional.of(new TmapRouteData(5000, List.of()))));

        MobilityRouteResult result = adapter.getMobilityRoute(ORIGIN, DESTINATION, MobilityType.DDAREUNGI).block();

        assertThat(result.durationMinutes()).isEqualTo(20);
        assertThat(result.distanceMeters()).isEqualTo(5000);
    }

    @Test
    void TMAP_실패시_하버사인_우회율_1_3배_적용() {
        given(tmapClient.getRoute(any(), any()))
                .willReturn(Mono.just(Optional.empty()));

        MobilityRouteResult result = adapter.getMobilityRoute(ORIGIN, DESTINATION, MobilityType.PERSONAL_EBIKE).block();

        // haversine 직선거리 × 1.3 우회계수 적용
        double dLat = Math.toRadians(DESTINATION.lat() - ORIGIN.lat());
        double dLng = Math.toRadians(DESTINATION.lng() - ORIGIN.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(ORIGIN.lat())) * Math.cos(Math.toRadians(DESTINATION.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double haversineKm = 6371.0 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
        double detourKm = haversineKm * 1.3;
        int expectedMinutes = Math.max(1, (int) Math.ceil(detourKm / 22.0 * 60));
        int expectedMeters = (int) Math.ceil(detourKm * 1000);

        assertThat(result.durationMinutes()).isEqualTo(expectedMinutes);
        assertThat(result.distanceMeters()).isEqualTo(expectedMeters);
        assertThat(result.routeCoordinates()).isEmpty();
    }

    @Test
    void 최소_1분_보장() {
        // 10m 도로 거리 → ceil(0.01 / 22 * 60) = ceil(0.027) = 1분 (max(1, ...))
        given(tmapClient.getRoute(any(), any()))
                .willReturn(Mono.just(Optional.of(new TmapRouteData(10, List.of()))));

        MobilityRouteResult result = adapter.getMobilityRoute(ORIGIN, DESTINATION, MobilityType.PERSONAL_EBIKE).block();

        assertThat(result.durationMinutes()).isEqualTo(1);
    }

    @Test
    void 도보_경로는_4_5kmh_기준이다() {
        // 5km 도로 거리 → ceil(5 / 4.5 * 60) = ceil(66.666) = 67분
        given(tmapClient.getRoute(any(), any()))
                .willReturn(Mono.just(Optional.of(new TmapRouteData(5000, List.of()))));

        MobilityRouteResult result = adapter.getWalkingRoute(ORIGIN, DESTINATION).block();

        assertThat(result.durationMinutes()).isEqualTo(67);
        assertThat(result.distanceMeters()).isEqualTo(5000);
    }
}
