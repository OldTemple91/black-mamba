package com.blackmamba.navigation.application.route.port;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetLevelTest {

    @ParameterizedTest
    @CsvSource({
            "NORMAL, 5, 5, true",
            "SAVING, 5, 2, false",
            "EXHAUSTED, 5, 1, false",
            "SAVING, 1, 1, false",
    })
    void 예산이_줄수록_후보_허브를_줄이고_양쪽_이동수단_패턴을_건너뛴다(BudgetLevel level, int normalLimit, int expectedLimit, boolean allowsBothEnds) {
        // when & then
        assertThat(level.candidateHubLimit(normalLimit)).isEqualTo(expectedLimit);
        assertThat(level.allowsBothEndsPattern()).isEqualTo(allowsBothEnds);
    }
}
