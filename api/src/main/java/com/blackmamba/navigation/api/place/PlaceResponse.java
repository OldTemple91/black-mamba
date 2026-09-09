package com.blackmamba.navigation.api.place;

import com.blackmamba.navigation.domain.location.Location;

import java.util.List;

/**
 * 장소 검색·지오코딩 계열 API 의 공통 응답 항목.
 * <p>
 * 이전엔 Naver 클라이언트의 내부 record({@code PlaceItem}, {@code SuggestItem})가 그대로
 * JSON 계약이었다 — 벤더 클라이언트를 손대면 공개 API 가 깨지는 구조. 도메인 {@link Location}
 * 에서 변환하는 이 record 가 HTTP 계약의 유일한 형태다.
 */
public record PlaceResponse(String name, double lat, double lng) {

    public static PlaceResponse from(Location location) {
        return new PlaceResponse(location.name(), location.lat(), location.lng());
    }

    public static List<PlaceResponse> fromAll(List<Location> locations) {
        return locations.stream()
                .map(PlaceResponse::from)
                .toList();
    }
}
