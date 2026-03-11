package com.blackmamba.navigation.infra.kickboard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;

/**
 * TAGO API - GetPMListByProvider 응답 DTO
 * 엔드포인트: /GetPMListByProvider?cityCode={code}&_type=json
 *
 * 실제 응답 필드명 (JSON lowercase):
 *   vehicleid, providername, battery, latitude, longitude
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KickboardResponse(Response response) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(Body body) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Body(Items items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Items(List<Item> item) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String vehicleid,      // 장치 ID
            String providername,   // 운영사명 (예: SWING)
            double latitude,       // 위도 (WGS84)
            double longitude,      // 경도 (WGS84)
            int battery            // 배터리 잔량 (%)
    ) {}

    public List<KickboardDevice> toDevices() {
        if (response == null || response.body() == null
                || response.body().items() == null
                || response.body().items().item() == null) {
            return List.of();
        }
        return response.body().items().item().stream()
                .filter(Objects::nonNull)
                .map(i -> new KickboardDevice(
                        i.vehicleid(), i.providername(),
                        i.latitude(), i.longitude(), i.battery()))
                .filter(Objects::nonNull)
                .toList();
    }
}
