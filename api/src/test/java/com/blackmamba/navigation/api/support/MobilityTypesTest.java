package com.blackmamba.navigation.api.support;

import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MobilityTypesTest {

    @Test
    void 콤마_분리와_반복_파라미터를_동일하게_파싱한다() {
        // when
        List<MobilityType> comma = MobilityTypes.parseStrict(List.of("DDAREUNGI, PERSONAL_EBIKE"));
        List<MobilityType> repeated = MobilityTypes.parseStrict(List.of("DDAREUNGI", "PERSONAL_EBIKE"));

        // then
        assertThat(comma).containsExactly(MobilityType.DDAREUNGI, MobilityType.PERSONAL_EBIKE);
        assertThat(repeated).isEqualTo(comma);
    }

    @Test
    void strict_는_잘못된_값에_예외를_던진다() {
        // when & then
        assertThatThrownBy(() -> MobilityTypes.parseStrict(List.of("DDAREUNGI", "INVALID")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lenient_는_잘못된_값만_건너뛴다() {
        // when
        List<MobilityType> result = MobilityTypes.parseLenient(Arrays.asList("INVALID", null, "", "PERSONAL_KICKBOARD"));

        // then
        assertThat(result).containsExactly(MobilityType.PERSONAL_KICKBOARD);
    }

    @Test
    void null_과_빈_입력은_빈_목록이다() {
        // when & then
        assertThat(MobilityTypes.parseStrict(null)).isEmpty();
        assertThat(MobilityTypes.parseLenient(List.of(""))).isEmpty();
    }
}
