package com.blackmamba.navigation.api.config;

import com.blackmamba.navigation.application.route.InvalidRouteSearchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 전역 예외 핸들러.
 * <p>
 * Controller에서 발생한 예외가 Tomcat까지 전파되면 두 번 로깅된다:
 *   1) Spring ServerHttpObservationFilter가 traceId와 함께 한 번
 *   2) Tomcat StandardWrapperValve가 MDC 비워진 상태에서 한 번
 *
 * 모든 오류 응답은 Content-Type 을 application/json 으로 못박는다. SSE 엔드포인트(produces=text/event-stream)에서
 * 던진 예외는 협상 가능한 타입이 event-stream 뿐이라, 지정하지 않으면 JSON 본문을 쓰지 못해 400 이 500 빈 본문이 된다.
 * <p>
 * 이 핸들러가 예외를 여기서 막고 HTTP 500 응답을 직접 만들어서
 * Tomcat까지 올라가지 않도록 한다. → Loki에 traceId 있는 ERROR 로그 1건만 남음.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 404 (NoResourceFoundException은 Spring 기본 상태를 유지) */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        log.warn("[404] 존재하지 않는 리소스: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("NOT_FOUND", "요청한 리소스가 존재하지 않습니다: " + e.getResourcePath()));
    }

    /** Spring ErrorResponseException 계열은 본인이 가진 상태코드 유지 */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorResponse> handleErrorResponse(ErrorResponseException e) {
        log.warn("[{}] {}", e.getStatusCode(), e.getMessage());
        String message = e.getMessage() != null ? e.getMessage() : "요청 처리 실패";
        return ResponseEntity.status(e.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(e.getStatusCode().toString(), message));
    }

    /** 탐색을 시작할 수 없는 요청(좌표 범위·최소 거리) — 사유 코드를 그대로 오류 코드로 내보낸다 */
    @ExceptionHandler(InvalidRouteSearchException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRouteSearch(InvalidRouteSearchException e) {
        log.warn("[400] {}: {}", e.code(), e.getMessage());
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(e.code(), e.getMessage()));
    }

    /** 파라미터 타입 불일치(originLat=abc, searchMode=BOGUS) — Spring 의 ErrorResponse 가 아니라서 따로 받지 않으면 500 이 된다 */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(TypeMismatchException e) {
        log.warn("[400] 파라미터 타입 불일치: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("INVALID_PARAMETER", "요청 파라미터 형식이 올바르지 않습니다."));
    }

    /** 입력 검증 실패(컨트롤러·SSE 가 던지는 IllegalArgumentException) 는 400 — 사용자 입력이지 서버 장애가 아니다 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        log.warn("[400] {}", e.getMessage());
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("BAD_REQUEST", e.getMessage() != null ? e.getMessage() : "잘못된 요청입니다."));
    }

    /** 나머지 예외는 500 — 단, Spring 이 상태 코드를 들고 던진 예외(파라미터 누락·타입 불일치 등)는 그 상태를 유지 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnhandled(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse withStatus) {
            log.warn("[{}] {}: {}", withStatus.getStatusCode(), e.getClass().getSimpleName(), e.getMessage());
            return ResponseEntity.status(withStatus.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ErrorResponse.of(withStatus.getStatusCode().toString(), "요청 형식이 올바르지 않습니다."));
        }
        // e를 인자로 넘겨야 SLF4J가 stackTrace를 event.throwable에 저장 → structuredMetadata 의 %xException 에서 사용
        // line 자체에는 예외 타입 + 메시지만 출력 (스택트레이스는 분리)
        log.error("[전역 예외] {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        // 응답에는 예외 메시지를 싣지 않는다 — 내부 경로·외부 API 상세 등 민감 정보 노출 가능.
        // 상세 원인은 위 ERROR 로그(traceId 포함)로만 추적.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
    }
}
