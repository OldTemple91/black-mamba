package com.blackmamba.navigation.domain.route.mobility;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MobilityInfoTest {

    @ParameterizedTest
    @CsvSource({
            "LIVE,      false, false, false",
            "STALE,     true,  false, false",
            "EMPTY,     false, true,  false",
            "ESTIMATED, false, false, true",
            "OWNED,     false, false, false",
    })
    void 가용성_출처_판정은_해당_출처일_때만_참이다(AvailabilitySource source, boolean stale, boolean empty, boolean estimated) {
        // given
        MobilityInfo info = withSource(source);

        // when & then
        assertThat(info.hasStaleAvailability()).isEqualTo(stale);
        assertThat(info.hasEmptyAvailability()).isEqualTo(empty);
        assertThat(info.hasEstimatedAvailability()).isEqualTo(estimated);
    }

    @ParameterizedTest
    @CsvSource({"hasStaleAvailability", "hasEmptyAvailability", "hasEstimatedAvailability"})
    void 출처가_없으면_모든_판정이_거짓이다(String check) {
        // given — 9-인자 생성자는 출처 null
        MobilityInfo info = new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100, "정류소", 37.5, 127.0, 3, 100);

        // when & then
        boolean result = switch (check) {
            case "hasStaleAvailability" -> info.hasStaleAvailability();
            case "hasEmptyAvailability" -> info.hasEmptyAvailability();
            default -> info.hasEstimatedAvailability();
        };
        assertThat(result).isFalse();
    }

    private static MobilityInfo withSource(AvailabilitySource source) {
        return new MobilityInfo(MobilityType.DDAREUNGI, "따릉이", null, 100, "S-1", "정류소", 10,
                37.5, 127.0, 3, 100, null, null, 0.0, 0.0, source);
    }
}
