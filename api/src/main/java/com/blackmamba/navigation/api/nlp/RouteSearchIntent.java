package com.blackmamba.navigation.api.nlp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 사용자 자연어 요청을 구조화한 <b>검색 의도(Intent)</b>.
 * <p>
 * LLM이 자연어를 파싱해 이 record로 변환하고,
 * {@link NaturalLanguageRouteController}가 기존 `/api/routes` 파라미터로 매핑한다.
 *
 * <h3>예시</h3>
 * <ul>
 *   <li>"강남에서 홍대까지 환승 적은 경로" → origin=강남, destination=홍대, preference=RELIABILITY</li>
 *   <li>"가장 빠르게 가는 길" → preference=TIME_PRIORITY</li>
 *   <li>"노인도 쉬운 경로" → walkingSpeedKmh=3.0</li>
 *   <li>"휠체어 가능" → wheelchairAccessible=true</li>
 *   <li>"따릉이로" → mobility=[DDAREUNGI]</li>
 * </ul>
 *
 * @param origin               출발지 한국어 장소명 (필수, LLM이 반드시 추출)
 * @param destination          도착지 한국어 장소명 (필수)
 * @param preference           RELIABILITY(기본) / TIME_PRIORITY
 * @param mobility             사용 가능 이동수단 (기본 전체). 사용자가 명시적으로 지정 시에만.
 * @param wheelchairAccessible 휠체어 접근성 요구 여부
 * @param walkingSpeedKmh      보행 속도 (노인 3.0, 일반 4.5, 미지정 시 null)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RouteSearchIntent(
        @JsonProperty("origin") String origin,
        @JsonProperty("destination") String destination,
        @JsonProperty("preference") String preference,
        @JsonProperty("mobility") java.util.List<String> mobility,
        @JsonProperty("wheelchairAccessible") Boolean wheelchairAccessible,
        @JsonProperty("walkingSpeedKmh") Double walkingSpeedKmh
) {
    public RouteSearchIntent {
        if (preference == null || preference.isBlank()) preference = "RELIABILITY";
    }
}
