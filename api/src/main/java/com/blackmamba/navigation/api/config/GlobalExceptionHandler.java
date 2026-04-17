package com.blackmamba.navigation.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * 전역 예외 핸들러.
 * <p>
 * Controller에서 발생한 예외가 Tomcat까지 전파되면 두 번 로깅된다:
 *   1) Spring ServerHttpObservationFilter가 traceId와 함께 한 번
 *   2) Tomcat StandardWrapperValve가 MDC 비워진 상태에서 한 번
 *
 * 이 핸들러가 예외를 여기서 막고 HTTP 500 응답을 직접 만들어서
 * Tomcat까지 올라가지 않도록 한다. → Loki에 traceId 있는 ERROR 로그 1건만 남음.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(Exception e) {
        // 여기서 로깅 — MDC에 traceId/spanId가 아직 살아있는 시점
        log.error("[전역 예외] {} : {}", e.getClass().getSimpleName(), e.getMessage(), e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "code", "INTERNAL_SERVER_ERROR",
                        "message", e.getMessage() != null ? e.getMessage() : "서버 내부 오류",
                        "timestamp", Instant.now().toString()
                ));
    }
}
