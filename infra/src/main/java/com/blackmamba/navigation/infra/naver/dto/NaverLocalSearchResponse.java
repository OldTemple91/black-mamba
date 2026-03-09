package com.blackmamba.navigation.infra.naver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 네이버 지역 검색 API (https://openapi.naver.com/v1/search/local.json) 응답 DTO
 * - X-Naver-Client-Id / X-Naver-Client-Secret 헤더 필요 (NCP 키와 별도)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverLocalSearchResponse(
        List<Item> items
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String title,       // 장소명 (HTML <b> 태그 포함 가능)
            String address,     // 지번 주소
            String roadAddress, // 도로명 주소
            String mapx,        // 경도 × 1e7 (String)
            String mapy         // 위도 × 1e7 (String)
    ) {
        /** HTML &lt;b&gt; 태그 제거 후 순수 장소명 반환 */
        public String cleanTitle() {
            return title == null ? "" : title.replaceAll("<[^>]+>", "");
        }
    }
}
