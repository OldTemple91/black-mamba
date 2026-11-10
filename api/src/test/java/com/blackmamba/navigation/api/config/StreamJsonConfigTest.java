package com.blackmamba.navigation.api.config;

import com.blackmamba.navigation.application.route.StreamEventPayload;
import com.blackmamba.navigation.application.route.StreamEventPayload.HeartbeatStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class StreamJsonConfigTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new StreamJsonConfig().streamEventModule())
            .build();

    @ParameterizedTest
    @CsvSource({"WATCHING, watching", "SEARCH_FAILED, search_failed", "NO_ROUTES, no_routes"})
    void 하트비트_상태는_클라이언트_계약인_소문자_코드로_나간다(HeartbeatStatus status, String expectedCode) {
        // when
        String json = mapper.writeValueAsString(StreamEventPayload.Heartbeat.from(status));

        // then
        assertThat(json).contains("\"status\":\"" + expectedCode + "\"");
    }
}
