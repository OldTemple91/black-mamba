package com.blackmamba.navigation.api.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * 모든 오류 응답의 단일 형태.
 * <p>
 * 컨트롤러의 400/503/504 분기와 {@link GlobalExceptionHandler} 가 각자 {@code Map.of} 로
 * 다른 모양의 오류 본문을 만들던 것을 한 타입으로 고정한다 — 클라이언트는 항상
 * {@code code} / {@code message} / {@code timestamp} 를 기대할 수 있다.
 *
 * @param details 보조 정보 (예: NLP 파싱 결과) — 없으면 직렬화에서 제외
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        String message,
        Instant timestamp,
        Map<String, Object> details
) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now(), null);
    }

    public static ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new ErrorResponse(code, message, Instant.now(), details);
    }
}
