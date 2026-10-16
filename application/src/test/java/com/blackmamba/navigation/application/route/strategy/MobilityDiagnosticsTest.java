package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.application.route.strategy.MobilityDiagnostics.Phase;
import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.hub.HubType;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.evaluation.GenerationDiagnostic;
import com.blackmamba.navigation.domain.route.mobility.MobilityInfo;
import com.blackmamba.navigation.domain.route.mobility.MobilitySearchHint;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * "왜 자전거 경로가 안 나왔는지" 를 사용자에게 설명하는 사유 코드 — 비어 있지 않다는 것만으로는 부족하고 코드가 맞아야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MobilityDiagnosticsTest {

    @Mock MobilityAvailabilityPort port;

    private MobilityDiagnostics diagnostics;
    private final Location origin = new Location("서울역", 37.5547, 126.9706);
    private final Location destination = new Location("강남역", 37.4979, 127.0276);
    private final Hub h1 = hub("H1", 37.52, 127.00);
    private final Hub h2 = hub("H2", 37.51, 127.01);

    @BeforeEach
    void setUp() {
        diagnostics = new MobilityDiagnostics(port);
        when(port.findNearestMobilityHint(anyDouble(), anyDouble(), any(), anyBoolean())).thenReturn(Mono.just(Optional.empty()));
    }

    @Test
    void 모든_허브에서_대여가_불가하면_NO_PICKUP_과_최근접_대여_힌트() {
        // given
        when(port.findNearbyMobility(anyDouble(), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.empty()));
        when(port.findNearbyDropoff(anyDouble(), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.of(station("D"))));
        // 라스트마일의 대여 힌트는 첫 허브 주변에서 찾는다
        when(port.findNearestMobilityHint(eq(h1.location().lat()), anyDouble(), eq(MobilityType.DDAREUNGI), eq(false)))
                .thenReturn(Mono.just(Optional.of(new MobilitySearchHint("S-9 정류소", "S-9", 3, 10, 850, false))));

        // when
        Optional<GenerationDiagnostic> result = diagnostics.diagnosePhase(Phase.LAST_MILE, MobilityType.DDAREUNGI, List.of(h1, h2), origin, destination).block();

        // then
        assertThat(result).isPresent();
        assertThat(result.get().reasonCode()).isEqualTo("NO_PICKUP");
        assertThat(result.get().candidateCount()).isEqualTo(2);
        assertThat(result.get().mobilityType()).isEqualTo(MobilityType.DDAREUNGI);
        assertThat(result.get().message()).contains("S-9 정류소").contains("850m");
    }

    @Test
    void 대여는_되지만_목적지_반납이_불가하면_NO_DROPOFF() {
        // given
        when(port.findNearbyMobility(anyDouble(), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.of(station("P"))));
        when(port.findNearbyDropoff(anyDouble(), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.empty()));

        // when
        Optional<GenerationDiagnostic> result = diagnostics.diagnosePhase(Phase.LAST_MILE, MobilityType.DDAREUNGI, List.of(h1), origin, destination).block();

        // then
        assertThat(result).map(GenerationDiagnostic::reasonCode).contains("NO_DROPOFF");
    }

    @Test
    void 동일_정류소_조합이_하나라도_있으면_SAME_PICKUP_DROPOFF_가_우선한다() {
        // given — H1: 대여=반납 같은 정류소, H2: 대여 불가
        MobilityInfo same = station("S-142");
        when(port.findNearbyMobility(eq(h1.location().lat()), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.of(same)));
        when(port.findNearbyMobility(eq(h2.location().lat()), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.empty()));
        when(port.findNearbyDropoff(anyDouble(), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.of(same)));

        // when
        Optional<GenerationDiagnostic> result = diagnostics.diagnosePhase(Phase.LAST_MILE, MobilityType.DDAREUNGI, List.of(h1, h2), origin, destination).block();

        // then
        assertThat(result).map(GenerationDiagnostic::reasonCode).contains("SAME_PICKUP_DROPOFF");
    }

    @Test
    void 대여_실패와_반납_실패가_섞이면_NO_VALID_COMBINATION() {
        // given — H1: 대여 불가, H2: 대여 가능·반납 불가
        when(port.findNearbyMobility(eq(h1.location().lat()), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.empty()));
        when(port.findNearbyMobility(eq(h2.location().lat()), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.of(station("P2"))));
        when(port.findNearbyDropoff(anyDouble(), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.empty()));

        // when
        Optional<GenerationDiagnostic> result = diagnostics.diagnosePhase(Phase.LAST_MILE, MobilityType.DDAREUNGI, List.of(h1, h2), origin, destination).block();

        // then
        assertThat(result).map(GenerationDiagnostic::reasonCode).contains("NO_VALID_COMBINATION");
    }

    @Test
    void 성립하는_허브가_하나라도_있으면_사유를_내지_않는다() {
        // given — H2 는 대여 P2 / 반납 D 로 성립
        when(port.findNearbyMobility(eq(h1.location().lat()), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.empty()));
        when(port.findNearbyMobility(eq(h2.location().lat()), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.of(station("P2"))));
        when(port.findNearbyDropoff(anyDouble(), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.of(station("D"))));

        // when & then
        assertThat(diagnostics.diagnosePhase(Phase.LAST_MILE, MobilityType.DDAREUNGI, List.of(h1, h2), origin, destination).block()).isEmpty();
    }

    @Test
    void 퍼스트마일은_출발지에서_대여를_확인한다() {
        // given — 출발지 대여 불가, 허브 주변은 대여 가능
        when(port.findNearbyMobility(eq(origin.lat()), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.empty()));
        when(port.findNearbyMobility(eq(h1.location().lat()), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.of(station("P"))));
        when(port.findNearbyDropoff(anyDouble(), anyDouble(), eq(MobilityType.DDAREUNGI))).thenReturn(Mono.just(Optional.of(station("D"))));

        // when
        Optional<GenerationDiagnostic> result = diagnostics.diagnosePhase(Phase.FIRST_MILE, MobilityType.DDAREUNGI, List.of(h1), origin, destination).block();

        // then
        assertThat(result).map(GenerationDiagnostic::reasonCode).contains("NO_PICKUP");
        assertThat(result).map(GenerationDiagnostic::phase).contains("FIRST_MILE");
    }

    private static MobilityInfo station(String id) {
        return new MobilityInfo(MobilityType.DDAREUNGI, "서울시 따릉이", null, 100, id, id + " 정류소", 10,
                37.5, 127.0, 4, 120, null, null, 0.0, 0.0, null);
    }

    private static Hub hub(String id, double lat, double lng) {
        return new Hub(id, id, HubType.SUBWAY_STATION, new Location(id, lat, lng), 150, Map.of());
    }
}
