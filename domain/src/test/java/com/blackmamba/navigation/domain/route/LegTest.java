package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LegTest {

    private final Location start = new Location("출발", 37.5, 127.0);
    private final Location end = new Location("도착", 37.51, 127.01);

    @ParameterizedTest
    @CsvSource({
            "KICKBOARD, PERSONAL_EBIKE, PERSONAL_EBIKE",
            "KICKBOARD, KICKBOARD_SHARED, KICKBOARD_SHARED",
            "BIKE, DDAREUNGI, DDAREUNGI",
    })
    void 수단_정보가_없어도_mode_에_실린_이름으로_실제_이동수단을_안다(LegType type, String mode, MobilityType expected) {
        // given — KICKBOARD 구간에는 개인 전기자전거도 들어온다. 구간 종류만으로는 수단을 가를 수 없다
        Leg leg = new Leg(type, mode, 10, 2_000, start, end, null, null, null);

        // when & then
        assertThat(leg.findMobilityType()).contains(expected);
        assertThat(leg.hasMobilityType(expected)).isTrue();
        assertThat(leg.isRiding()).isTrue();
    }

    @Test
    void 도보와_대중교통_구간은_이동수단이_없다() {
        // given
        Leg walk = new Leg(LegType.WALK, "WALK", 5, 300, start, end, null, null, null);
        Leg subway = new Leg(LegType.TRANSIT, "SUBWAY", 20, 8_000, start, end, null, null, null);

        // when & then
        assertThat(walk.findMobilityType()).isEmpty();
        assertThat(subway.findMobilityType()).isEmpty();
        assertThat(walk.isWalk()).isTrue();
        assertThat(subway.isTransit()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"SUBWAY, SUBWAY", "subway, SUBWAY", "BUS, BUS", "대중교통, ESTIMATED", "EXPRESS_BUS, OTHER"})
    void 대중교통_수단은_mode_문자열에서_한_곳에서만_해석한다(String mode, TransitMode expected) {
        // given
        Leg leg = new Leg(LegType.TRANSIT, mode, 20, 8_000, start, end, null, null, null);

        // when & then
        assertThat(leg.transitMode()).isEqualTo(expected);
    }
}
