package com.blackmamba.navigation.application.route.hub;

import com.blackmamba.navigation.application.route.port.MobilityAvailabilityPort;
import com.blackmamba.navigation.domain.hub.Hub;
import com.blackmamba.navigation.domain.hub.HubSelection;
import com.blackmamba.navigation.domain.hub.HubType;
import com.blackmamba.navigation.domain.location.Location;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 어느 정류소로 사용자를 보낼지 정하는 로직 — 이 결과의 metadata(pickupHintDistanceMeters)가
 * 점수의 '대여소 접근 김' 페널티로 이어진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LastMileHubPrioritizerTest {

    @Mock HubSelector hubSelector;
    @Mock MobilityAvailabilityPort availabilityPort;

    private LastMileHubPrioritizer prioritizer;
    private final Location destination = new Location("강남역", 37.4979, 127.0276);
    private final MobilityConfig config = MobilityConfig.bike();

    @BeforeEach
    void setUp() {
        prioritizer = new LastMileHubPrioritizer(hubSelector, availabilityPort);
    }

    @Test
    void 대여_힌트가_1100m_이내인_허브만_힌트_거리_순으로_남긴다() {
        // given — 선정 순위 1~4, 힌트 거리 1300 / 900 / 1100 / 없음
        Hub h1 = hub("H1", 1, 37.510), h2 = hub("H2", 2, 37.511), h3 = hub("H3", 3, 37.512), h4 = hub("H4", 4, 37.513);
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of(h1, h2, h3, h4));
        stubHint(h1, 1_300);
        stubHint(h2, 900);
        stubHint(h3, 1_100);
        when(availabilityPort.findNearestMobilityHint(eq(h4.location().lat()), anyDouble(), any(), anyBoolean()))
                .thenReturn(Mono.just(Optional.empty()));

        // when
        List<Hub> result = prioritizer.prioritize(List.of(), destination, MobilityType.DDAREUNGI, config).block();

        // then
        assertThat(result).extracting(Hub::hubId).containsExactly("H2", "H3");
        assertThat(result.getFirst().pickupHint().distanceMeters()).isEqualTo(900);
        // 응답·신뢰도 평가는 문자열 맵을 읽는다 — 타입 필드가 같은 키로 실려야 한다
        assertThat(result.getFirst().displayMetadata())
                .containsEntry(Hub.META_PICKUP_HINT_DISTANCE_METERS, "900")
                .containsKey(Hub.META_PICKUP_HINT_STATION_ID)
                .containsKey(Hub.META_PICKUP_HINT_AVAILABLE_COUNT)
                .containsEntry(Hub.META_SELECTION_RANK, String.valueOf(result.getFirst().selection().rank()));
    }

    @Test
    void 합리적_힌트가_하나도_없으면_전부_남기되_가까운_힌트_순으로_둔다() {
        // given — 전멸 방지. 1,100m 를 다 넘어도 그중 가까운 정류소가 사용자에게 낫다
        Hub h1 = hub("H1", 1, 37.510), h2 = hub("H2", 2, 37.511);
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(List.of(h1, h2));
        stubHint(h1, 1_500);
        stubHint(h2, 1_200);

        // when
        List<Hub> result = prioritizer.prioritize(List.of(), destination, MobilityType.DDAREUNGI, config).block();

        // then
        assertThat(result).extracting(Hub::hubId).containsExactly("H2", "H1");
    }

    @Test
    void 따릉이가_아니면_힌트_조회_없이_상위_5개를_그대로_쓴다() {
        // given — 7개 후보
        List<Hub> hubs = List.of(hub("H1", 1, 37.510), hub("H2", 2, 37.511), hub("H3", 3, 37.512),
                hub("H4", 4, 37.513), hub("H5", 5, 37.514), hub("H6", 6, 37.515), hub("H7", 7, 37.516));
        when(hubSelector.selectLastMileHubs(any(), any(), any())).thenReturn(hubs);

        // when
        List<Hub> result = prioritizer.prioritize(List.of(), destination, MobilityType.PERSONAL_KICKBOARD, MobilityConfig.personalKickboard()).block();

        // then
        assertThat(result).hasSize(5);
        assertThat(result).extracting(Hub::hubId).containsExactly("H1", "H2", "H3", "H4", "H5");
        verify(availabilityPort, never()).findNearestMobilityHint(anyDouble(), anyDouble(), any(), anyBoolean());
    }

    private void stubHint(Hub hub, int distanceMeters) {
        when(availabilityPort.findNearestMobilityHint(eq(hub.location().lat()), anyDouble(), eq(MobilityType.DDAREUNGI), eq(false)))
                .thenReturn(Mono.just(Optional.of(new MobilitySearchHint("정류소-" + hub.hubId(), "S-" + hub.hubId(), 4, 10, distanceMeters, false))));
    }

    private static Hub hub(String id, int rank, double lat) {
        return new Hub(id, id, HubType.SUBWAY_STATION, new Location(id, lat, 127.0), 150,
                new HubSelection(HubSelection.Strategy.PRIMARY, rank), null, Map.of());
    }
}
