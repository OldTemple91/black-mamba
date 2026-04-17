package com.blackmamba.navigation.infra.common;

import com.blackmamba.navigation.domain.location.Location;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeohashKeyGeneratorTest {

    @Test
    void 좌표가_같은_격자_내면_같은_키를_반환() {
        // 50m 이내 미세 차이 (precision 7의 150m 격자 내부)
        Location a = new Location("강남역", 37.497900, 127.027600);
        Location b = new Location("강남역 부근", 37.497950, 127.027620);

        assertThat(GeohashKeyGenerator.of(a))
                .isEqualTo(GeohashKeyGenerator.of(b));
    }

    @Test
    void 좌표가_다른_격자에_있으면_다른_키를_반환() {
        Location 강남 = new Location("강남역", 37.4979, 127.0276);
        Location 홍대 = new Location("홍대입구", 37.5573, 126.9246);

        assertThat(GeohashKeyGenerator.of(강남))
                .isNotEqualTo(GeohashKeyGenerator.of(홍대));
    }

    @Test
    void 경로_키는_출발지와_도착지_geohash를_구분자로_합친다() {
        Location origin = new Location("강남", 37.4979, 127.0276);
        Location destination = new Location("홍대", 37.5573, 126.9246);

        String key = GeohashKeyGenerator.forRoute(origin, destination);

        assertThat(key)
                .contains("|")
                .startsWith(GeohashKeyGenerator.of(origin));
    }

    @Test
    void precision이_낮을수록_격자가_커져서_더_많은_좌표가_같은_키로_묶인다() {
        Location a = new Location("강남", 37.4979, 127.0276);
        Location b = new Location("역삼", 37.5012, 127.0345);   // ~500m 떨어짐

        // precision 7 (150m): 서로 다른 격자
        assertThat(GeohashKeyGenerator.of(a, 7))
                .isNotEqualTo(GeohashKeyGenerator.of(b, 7));
        // precision 5 (5km): 같은 격자
        assertThat(GeohashKeyGenerator.of(a, 5))
                .isEqualTo(GeohashKeyGenerator.of(b, 5));
    }

    @Test
    void precision_기본값은_7이다() {
        Location loc = new Location("x", 37.5, 127.0);
        assertThat(GeohashKeyGenerator.of(loc))
                .hasSize(GeohashKeyGenerator.DEFAULT_PRECISION);
    }
}
