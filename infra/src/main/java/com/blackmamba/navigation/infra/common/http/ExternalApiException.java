package com.blackmamba.navigation.infra.common.http;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

/**
 * 외부 API 가 HTTP 오류 상태를 돌려준 경우.
 * <p>
 * 예전엔 각 클라이언트가 {@code new RuntimeException(...)} 으로 감쌌는데, Resilience4j 의
 * {@code recordExceptions} 목록에 없는 타입은 <b>성공</b>으로 집계되어 5xx 가 10분 이어져도
 * 회로가 열리지 않고 재시도도 되지 않았다. 이 타입(과 하위 타입)은 yml 의 record/retry 목록에 있다.
 * <ul>
 *   <li>{@link ExternalApiServerException} 5xx — 기록 + 재시도</li>
 *   <li>{@link ExternalApiClientException} 4xx — 기록만 (재시도해도 같은 답)</li>
 * </ul>
 */
public sealed class ExternalApiException extends RuntimeException permits ExternalApiClientException, ExternalApiServerException {

    private final int status;

    protected ExternalApiException(String apiName, int status, String body) {
        super(apiName + " API 오류: " + status + (body == null || body.isBlank() ? "" : " " + abbreviate(body)));
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** {@code WebClient.retrieve().onStatus(...)} 핸들러 — 응답 본문을 읽어 상태 코드에 맞는 예외로. */
    public static Mono<? extends Throwable> from(String apiName, ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> of(apiName, status.value(), body));
    }

    public static ExternalApiException of(String apiName, int status, String body) {
        if (status >= 500) {
            return new ExternalApiServerException(apiName, status, body);
        }
        return new ExternalApiClientException(apiName, status, body);
    }

    private static String abbreviate(String body) {
        return body.length() <= 200 ? body : body.substring(0, 200) + "…";
    }
}
