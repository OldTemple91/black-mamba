package com.blackmamba.navigation.domain.route;

import com.blackmamba.navigation.domain.location.Location;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransitSearchDistancePolicyTest {

    private final Location gangnam = new Location("강남역", 37.4979, 127.0276);

    @Test
    void 약_500m_거리는_대중교통_탐색_대상이_아니다() {
        // given — 위도 0.0045° ≈ 500m
        Location nearby = new Location("근처", 37.5024, 127.0276);

        // when & then
        assertThat(TransitSearchDistancePolicy.isTooCloseForTransit(gangnam, nearby)).isTrue();
    }

    @Test
    void 약_1km_거리는_대중교통_탐색_대상이다() {
        // given — 위도 0.009° ≈ 1,000m
        Location farther = new Location("조금 먼 곳", 37.5069, 127.0276);

        // when & then
        assertThat(TransitSearchDistancePolicy.isTooCloseForTransit(gangnam, farther)).isFalse();
    }
}
