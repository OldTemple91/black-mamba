package com.blackmamba.navigation.infra.adapter;

import com.blackmamba.navigation.domain.route.mobility.AvailabilitySource;
import com.blackmamba.navigation.domain.route.mobility.MobilityInfo;
import com.blackmamba.navigation.domain.route.mobility.MobilitySearchHint;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import com.blackmamba.navigation.infra.common.cache.TtlCacheRegistry;
import com.blackmamba.navigation.infra.ddareungi.DdareungiApiClient;
import com.blackmamba.navigation.infra.ddareungi.DdareungiApiClient.StationSnapshot;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import com.blackmamba.navigation.infra.kickboard.KickboardApiClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 따릉이 혼합 경로의 성립 조건 — "대여 가능한 정류소가 있고, 반납 정류소가 있고, 둘이 다른 정류소" —
 * 는 README 가 약속한 핵심 규칙이지만 이 어댑터 한 곳에서만 집행된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MobilityAvailabilityAdapterTest {

    private static final double HUB_LAT = 37.52, HUB_LNG = 127.0;
    private static final double DEST_LAT = 37.4979, DEST_LNG = 127.0276;
    private static final int RADIUS = 700;

    @Mock DdareungiApiClient ddareungiClient;
    @Mock KickboardApiClient kickboardClient;

    private MobilityAvailabilityAdapter adapter;

    @BeforeEach
    void setUp() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        adapter = new MobilityAvailabilityAdapter(ddareungiClient, kickboardClient, RADIUS, 0L, false,
                meterRegistry, new TtlCacheRegistry(meterRegistry));
    }

    /** 기본 어댑터는 TTL 0 이라 캐시 동작을 못 본다 — 캐시 규칙 테스트용 */
    private MobilityAvailabilityAdapter cachingAdapter() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        return new MobilityAvailabilityAdapter(ddareungiClient, kickboardClient, RADIUS, 60_000L, false,
                meterRegistry, new TtlCacheRegistry(meterRegistry));
    }

    @Test
    void 따릉이_snapshot_을_확보하지_못한_결과는_후보_없음으로_답하되_캐시하지_않는다() {
        // given — 실제 클라이언트는 장애 때 예외가 아니라 출처 EMPTY 인 빈 snapshot 을 준다. 첫 호출은 장애, 두 번째는 복구
        MobilityAvailabilityAdapter cachingAdapter = cachingAdapter();
        when(ddareungiClient.getNearbyStations(HUB_LAT, HUB_LNG, RADIUS, true))
                .thenReturn(Mono.just(new StationSnapshot(List.of(), AvailabilitySource.EMPTY)),
                        Mono.just(live(station("S-142", "142. 아현역 4번출구 앞", HUB_LAT, HUB_LNG, 5))));

        // when
        Optional<MobilityInfo> first = cachingAdapter.findNearbyMobility(HUB_LAT, HUB_LNG, MobilityType.DDAREUNGI).block();
        Optional<MobilityInfo> second = cachingAdapter.findNearbyMobility(HUB_LAT, HUB_LNG, MobilityType.DDAREUNGI).block();

        // then
        assertThat(first).isEmpty();
        assertThat(second).isPresent();
    }

    @Test
    void 반경_내_정류소가_없다는_정상_결과는_캐시된다() {
        // given — 출처 LIVE 인 빈 목록은 장애가 아니라 "근처에 없음"
        MobilityAvailabilityAdapter cachingAdapter = cachingAdapter();
        when(ddareungiClient.getNearbyStations(HUB_LAT, HUB_LNG, RADIUS, true)).thenReturn(Mono.just(live()));

        // when
        cachingAdapter.findNearbyMobility(HUB_LAT, HUB_LNG, MobilityType.DDAREUNGI).block();
        cachingAdapter.findNearbyMobility(HUB_LAT, HUB_LNG, MobilityType.DDAREUNGI).block();

        // then
        verify(ddareungiClient, times(1)).getNearbyStations(HUB_LAT, HUB_LNG, RADIUS, true);
    }

    @Test
    void 장애로_비어_버린_세그먼트_결과는_복구_후_바로_성립한다() {
        // given — 대여 조회가 첫 호출엔 장애, 두 번째엔 정상
        MobilityAvailabilityAdapter cachingAdapter = cachingAdapter();
        when(ddareungiClient.getNearbyStations(HUB_LAT, HUB_LNG, RADIUS, true))
                .thenReturn(Mono.just(new StationSnapshot(List.of(), AvailabilitySource.EMPTY)),
                        Mono.just(live(station("S-142", "142. 아현역 4번출구 앞", HUB_LAT, HUB_LNG, 5))));
        when(ddareungiClient.getNearbyStations(DEST_LAT, DEST_LNG, RADIUS, false))
                .thenReturn(Mono.just(live(station("S-200", "강남역 따릉이", DEST_LAT, DEST_LNG, 2))));

        // when
        MobilityInfo first = cachingAdapter.findSegmentMobility(HUB_LAT, HUB_LNG, DEST_LAT, DEST_LNG, MobilityType.DDAREUNGI).block();
        MobilityInfo second = cachingAdapter.findSegmentMobility(HUB_LAT, HUB_LNG, DEST_LAT, DEST_LNG, MobilityType.DDAREUNGI).block();

        // then
        assertThat(first).isNull();
        assertThat(second.stationId()).isEqualTo("S-142");
    }

    @ParameterizedTest
    @CsvSource({"false, true", "true, false"})
    void 대여_힌트는_자전거가_있는_정류소만_반납_힌트는_전체_정류소를_본다(boolean dropoff, boolean requireAvailableBike) {
        // given — 힌트 반경은 max(700 × 2, 1400)
        when(ddareungiClient.getNearbyStations(HUB_LAT, HUB_LNG, 1_400, requireAvailableBike))
                .thenReturn(Mono.just(live(station("S-142", "142. 아현역 4번출구 앞", HUB_LAT, HUB_LNG, 5))));

        // when
        Optional<MobilitySearchHint> hint = adapter.findNearestMobilityHint(HUB_LAT, HUB_LNG, MobilityType.DDAREUNGI, dropoff).block();

        // then
        assertThat(hint).isPresent();
    }

    @Test
    void 따릉이_정상_결과는_TTL_동안_캐시된다() {
        // given
        MobilityAvailabilityAdapter cachingAdapter = cachingAdapter();
        when(ddareungiClient.getNearbyStations(HUB_LAT, HUB_LNG, RADIUS, true))
                .thenReturn(Mono.just(live(station("S-142", "142. 아현역 4번출구 앞", HUB_LAT, HUB_LNG, 5))));

        // when
        cachingAdapter.findNearbyMobility(HUB_LAT, HUB_LNG, MobilityType.DDAREUNGI).block();
        cachingAdapter.findNearbyMobility(HUB_LAT, HUB_LNG, MobilityType.DDAREUNGI).block();

        // then
        verify(ddareungiClient, times(1)).getNearbyStations(HUB_LAT, HUB_LNG, RADIUS, true);
    }

    @Test
    void 대여와_반납_정류소가_다르면_세그먼트가_성립한다() {
        // given
        when(ddareungiClient.getNearbyStations(HUB_LAT, HUB_LNG, RADIUS, true))
                .thenReturn(Mono.just(live(station("S-142", "142. 아현역 4번출구 앞", HUB_LAT, HUB_LNG, 5))));
        when(ddareungiClient.getNearbyStations(DEST_LAT, DEST_LNG, RADIUS, false))
                .thenReturn(Mono.just(live(station("S-200", "강남역 따릉이", DEST_LAT, DEST_LNG, 2))));

        // when & then
        StepVerifier.create(adapter.findSegmentMobility(HUB_LAT, HUB_LNG, DEST_LAT, DEST_LNG, MobilityType.DDAREUNGI))
                .assertNext(info -> {
                    assertThat(info.stationId()).isEqualTo("S-142");
                    assertThat(info.dropoffStationId()).isEqualTo("S-200");
                    assertThat(info.availableCount()).isEqualTo(5);
                    assertThat(info.availabilitySource()).isEqualTo(AvailabilitySource.LIVE);
                })
                .verifyComplete();
    }

    @Test
    void 대여와_반납이_같은_정류소면_세그먼트를_만들지_않는다() {
        // given
        DdareungiStation same = station("S-142", "142. 아현역 4번출구 앞", HUB_LAT, HUB_LNG, 5);
        when(ddareungiClient.getNearbyStations(HUB_LAT, HUB_LNG, RADIUS, true)).thenReturn(Mono.just(live(same)));
        when(ddareungiClient.getNearbyStations(DEST_LAT, DEST_LNG, RADIUS, false)).thenReturn(Mono.just(live(same)));

        // when & then
        StepVerifier.create(adapter.findSegmentMobility(HUB_LAT, HUB_LNG, DEST_LAT, DEST_LNG, MobilityType.DDAREUNGI))
                .verifyComplete();
    }

    @Test
    void 반납_정류소가_없으면_대여가_가능해도_세그먼트를_만들지_않는다() {
        // given
        when(ddareungiClient.getNearbyStations(HUB_LAT, HUB_LNG, RADIUS, true))
                .thenReturn(Mono.just(live(station("S-142", "142. 아현역 4번출구 앞", HUB_LAT, HUB_LNG, 5))));
        when(ddareungiClient.getNearbyStations(DEST_LAT, DEST_LNG, RADIUS, false))
                .thenReturn(Mono.just(new StationSnapshot(List.of(), AvailabilitySource.LIVE)));

        // when & then
        StepVerifier.create(adapter.findSegmentMobility(HUB_LAT, HUB_LNG, DEST_LAT, DEST_LNG, MobilityType.DDAREUNGI))
                .verifyComplete();
    }

    @Test
    void 대여_가능_정류소가_없으면_세그먼트를_만들지_않는다() {
        // given
        when(ddareungiClient.getNearbyStations(HUB_LAT, HUB_LNG, RADIUS, true))
                .thenReturn(Mono.just(new StationSnapshot(List.of(), AvailabilitySource.LIVE)));
        when(ddareungiClient.getNearbyStations(DEST_LAT, DEST_LNG, RADIUS, false))
                .thenReturn(Mono.just(live(station("S-200", "강남역 따릉이", DEST_LAT, DEST_LNG, 2))));

        // when & then
        StepVerifier.create(adapter.findSegmentMobility(HUB_LAT, HUB_LNG, DEST_LAT, DEST_LNG, MobilityType.DDAREUNGI))
                .verifyComplete();
    }

    @Test
    void 개인_수단은_외부_조회_없이_보유_수단으로_성립한다() {
        // when & then
        StepVerifier.create(adapter.findSegmentMobility(HUB_LAT, HUB_LNG, DEST_LAT, DEST_LNG, MobilityType.PERSONAL_EBIKE))
                .assertNext(info -> assertThat(info.availabilitySource()).isEqualTo(AvailabilitySource.OWNED))
                .verifyComplete();
        verify(ddareungiClient, never()).getNearbyStations(anyDouble(), anyDouble(), anyInt(), eq(true));
        verify(ddareungiClient, never()).getNearbyStations(anyDouble(), anyDouble(), anyInt(), eq(false));
    }

    @Test
    void stale_snapshot_출처는_결과에_그대로_실린다() {
        // given — 갱신 실패로 이전 snapshot 재사용
        when(ddareungiClient.getNearbyStations(HUB_LAT, HUB_LNG, RADIUS, true))
                .thenReturn(Mono.just(new StationSnapshot(List.of(station("S-1", "정류소", HUB_LAT, HUB_LNG, 3)), AvailabilitySource.STALE)));

        // when & then
        StepVerifier.create(adapter.findNearbyMobility(HUB_LAT, HUB_LNG, MobilityType.DDAREUNGI))
                .assertNext(opt -> assertThat(opt).map(MobilityInfo::availabilitySource).contains(AvailabilitySource.STALE))
                .verifyComplete();
    }

    private static StationSnapshot live(DdareungiStation... stations) {
        return new StationSnapshot(List.of(stations), AvailabilitySource.LIVE);
    }

    private static DdareungiStation station(String id, String name, double lat, double lng, int available) {
        return new DdareungiStation(id, name, lat, lng, available, 10);
    }
}
