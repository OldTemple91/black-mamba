package com.blackmamba.navigation.infra.naver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NaverGeocodingResponse(
        String status,
        List<Address> addresses
) {
    // NCP Geocoding API: x = 경도(lng), y = 위도(lat)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Address(
            @JsonProperty("x") double lng,
            @JsonProperty("y") double lat
    ) {}
}
