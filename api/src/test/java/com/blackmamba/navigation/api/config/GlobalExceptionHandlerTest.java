package com.blackmamba.navigation.api.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 요청_파라미터_타입_불일치는_JSON_400_으로_답한다() {
        // given — originLat=abc
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                "abc", Double.class, "originLat", null, new NumberFormatException("For input string: \"abc\""));

        // when
        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(exception);

        // then — SSE 요청(Accept: text/event-stream)에서도 본문을 쓸 수 있게 JSON 으로 고정한다
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody().code()).isEqualTo("INVALID_PARAMETER");
    }
}
