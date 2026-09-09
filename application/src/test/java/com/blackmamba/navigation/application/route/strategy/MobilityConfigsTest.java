package com.blackmamba.navigation.application.route.strategy;

import com.blackmamba.navigation.application.route.hub.MobilityConfig;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MobilityConfigsTest {

    @ParameterizedTest
    @CsvSource({
            "DDAREUNGI,          DDAREUNGI,          700",
            "PERSONAL_EBIKE,     PERSONAL_EBIKE,     500",
            "PERSONAL_KICKBOARD, PERSONAL_KICKBOARD, 300",
            "KICKBOARD_SHARED,   KICKBOARD_SHARED,   500",
    })
    void 타입별_탐색설정은_해당_타입_전용값을_반환한다(MobilityType type, MobilityType expectedConfigType, int expectedMinEffective) {
        // when
        MobilityConfig config = MobilityConfigs.forType(type);

        // then — SPECIFIC 전략이 PERSONAL_EBIKE 를 따릉이 설정(700m)으로 탐색하던 드리프트 회귀 방지
        assertThat(config.mobilityType()).isEqualTo(expectedConfigType);
        assertThat(config.minEffectiveDistanceMeters()).isEqualTo(expectedMinEffective);
    }

    @ParameterizedTest
    @CsvSource({
            "DDAREUNGI,          TRANSIT_WITH_BIKE",
            "PERSONAL_EBIKE,     TRANSIT_WITH_BIKE",
            "PERSONAL_KICKBOARD, TRANSIT_WITH_KICKBOARD",
            "KICKBOARD_SHARED,   TRANSIT_WITH_KICKBOARD",
    })
    void 킥보드_계열만_킥보드_혼합_경로타입이다(MobilityType type, RouteType expected) {
        // when & then
        assertThat(MobilityConfigs.routeTypeFor(type)).isEqualTo(expected);
    }
}
