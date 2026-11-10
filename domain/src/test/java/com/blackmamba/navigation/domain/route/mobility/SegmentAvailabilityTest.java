package com.blackmamba.navigation.domain.route.mobility;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 혼합 경로의 성립 규칙 — 경로 생성과 "왜 못 만들었는지" 진단이 함께 쓰는 단일 판정.
 */
class SegmentAvailabilityTest {

    @Test
    void 따릉이는_대여와_반납_정류소가_모두_있고_서로_달라야_성립한다() {
        // when
        SegmentAvailability availability = SegmentAvailability.of(MobilityType.DDAREUNGI,
                Optional.of(station("S-1", "아현역")), Optional.of(station("S-2", "강남역")));

        // then — 성립한 구간에는 반납 정류소가 채워져 있다
        assertThat(availability).isInstanceOfSatisfying(SegmentAvailability.Available.class,
                available -> assertThat(available.info().dropoffStationId()).isEqualTo("S-2"));
    }

    @Test
    void 대여_가능한_수단이_없으면_반납_여부와_무관하게_NoPickup_이다() {
        // when & then
        assertThat(SegmentAvailability.of(MobilityType.DDAREUNGI, Optional.empty(), Optional.of(station("S-2", "강남역"))))
                .isInstanceOf(SegmentAvailability.NoPickup.class);
        assertThat(SegmentAvailability.of(MobilityType.PERSONAL_EBIKE, Optional.empty(), Optional.empty()))
                .isInstanceOf(SegmentAvailability.NoPickup.class);
    }

    @Test
    void 따릉이는_반납_정류소가_없으면_NoDropoff_이다() {
        // when & then
        assertThat(SegmentAvailability.of(MobilityType.DDAREUNGI, Optional.of(station("S-1", "아현역")), Optional.empty()))
                .isInstanceOf(SegmentAvailability.NoDropoff.class);
    }

    @Test
    void 대여와_반납이_같은_정류소면_성립하지_않는다() {
        // when & then
        assertThat(SegmentAvailability.of(MobilityType.DDAREUNGI, Optional.of(station("S-1", "아현역")), Optional.of(station("S-1", "아현역"))))
                .isInstanceOf(SegmentAvailability.SamePickupDropoff.class);
    }

    @Test
    void 반납_정류소가_필요_없는_수단은_대여만_되면_성립한다() {
        // when
        SegmentAvailability availability = SegmentAvailability.of(MobilityType.PERSONAL_KICKBOARD,
                Optional.of(personal(MobilityType.PERSONAL_KICKBOARD)), Optional.empty());

        // then
        assertThat(availability).isInstanceOf(SegmentAvailability.Available.class);
        assertThat(availability.findInfo()).isPresent();
    }

    private static MobilityInfo station(String stationId, String stationName) {
        return new MobilityInfo(MobilityType.DDAREUNGI, "서울시 따릉이", null, 100, stationId, stationName, 10,
                37.5, 127.0, 5, 100, null, null, 0.0, 0.0, AvailabilitySource.LIVE);
    }

    private static MobilityInfo personal(MobilityType type) {
        return new MobilityInfo(type, type.displayName(), null, 100, null, null, 0,
                37.5, 127.0, 1, 0, null, null, 0.0, 0.0, AvailabilitySource.OWNED);
    }
}
