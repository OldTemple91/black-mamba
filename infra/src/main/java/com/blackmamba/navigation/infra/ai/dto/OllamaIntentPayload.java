package com.blackmamba.navigation.infra.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * LLM 이 출력하는 JSON 의 와이어 포맷 — 문자열 그대로 받는다.
 * <p>
 * 로컬 3B 모델은 스펙 밖 값("FAST", "bike" 등)을 낼 수 있으므로 enum 으로 바로 받지 않고,
 * {@code OllamaRouteIntentParser} 가 느슨하게 도메인 타입으로 변환한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OllamaIntentPayload(
        @JsonProperty("origin") String origin,
        @JsonProperty("destination") String destination,
        @JsonProperty("preference") String preference,
        @JsonProperty("mobility") List<String> mobility,
        @JsonProperty("wheelchairAccessible") Boolean wheelchairAccessible,
        @JsonProperty("walkingSpeedKmh") Double walkingSpeedKmh
) {
    public boolean hasOriginAndDestination() {
        return origin != null && !origin.isBlank()
                && destination != null && !destination.isBlank();
    }
}
