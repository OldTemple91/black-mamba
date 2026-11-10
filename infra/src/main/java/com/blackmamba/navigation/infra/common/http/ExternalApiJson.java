package com.blackmamba.navigation.infra.common.http;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 외부 API 응답을 읽는 JSON 매퍼 — 남이 만든 응답은 관대하게 읽는다.
 * <p>
 * Jackson 3 은 기본값이 바뀌어, 응답에 없는 필드를 {@code int}·{@code double} 같은 primitive 로 받으면
 * 예외를 낸다(FAIL_ON_NULL_FOR_PRIMITIVES). ODsay 의 {@code payment}, Tmap LineString feature 의
 * {@code totalDistance} 처럼 "있을 때만 오는" 숫자 필드가 많아 그대로 두면 정상 응답이 통째로 파싱 실패한다.
 * 누락은 0 으로 읽고 의미 판단은 각 DTO 가 한다. 애플리케이션 컨텍스트의 매퍼 설정과 무관하게
 * {@link WebClients} 와 수동 파싱이 같은 매퍼를 쓰도록 여기 한 곳에 둔다.
 */
public final class ExternalApiJson {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private ExternalApiJson() {
    }
}
