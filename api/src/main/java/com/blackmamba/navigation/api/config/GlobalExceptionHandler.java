package com.blackmamba.navigation.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /** 404 (NoResourceFoundException은 Spring 기본 상태를 유지) */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e) {
        log.warn("[404] 존재하지 않는 리소스: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code", "NOT_FOUND",
                "message", "요청한 리소스가 존재하지 않습니다: " + e.getResourcePath(),
                "timestamp", Instant.now().toString()
        ));
    }

    /** Spring ErrorResponseException 계열은 본인이 가진 상태코드 유지 */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<Map<String, Object>> handleErrorResponse(ErrorResponseException e) {
        log.warn("[{}] {}", e.getStatusCode(), e.getMessage());
        return ResponseEntity.status(e.getStatusCode()).body(Map.of(
                "code", e.getStatusCode().toString(),
                "message", e.getMessage() != null ? e.getMessage() : "요청 처리 실패",
                "timestamp", Instant.now().toString()
        ));
    }

    /** 나머지 예외는 500 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandled(Exception e) {
        // e를 인자로 넘겨야 SLF4J가 stackTrace를 event.throwable에 저장 → structuredMetadata 의 %xException 에서 사용
        // line 자체에는 예외 타입 + 메시지만 출력 (스택트레이스는 분리)
        log.error("[전역 예외] {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        // 응답에는 예외 메시지를 싣지 않는다 — 내부 경로·외부 API 상세 등 민감 정보 노출 가능.
        // 상세 원인은 위 ERROR 로그(traceId 포함)로만 추적.
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "code", "INTERNAL_SERVER_ERROR",
                        "message", "서버 내부 오류가 발생했습니다.",
                        "timestamp", Instant.now().toString()
                ));
    }
}
