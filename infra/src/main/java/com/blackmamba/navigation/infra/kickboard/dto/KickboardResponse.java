package com.blackmamba.navigation.infra.kickboard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;

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
            String deviceId,
            String operatorName,
            String latitude,
            String longitude,
            String batteryLevel
    ) {}

    public List<KickboardDevice> toDevices() {
        if (response == null || response.body() == null
                || response.body().items() == null
                || response.body().items().item() == null) {
            return List.of();
        }
        return response.body().items().item().stream()
                .filter(Objects::nonNull)
                .map(this::toDevice)
                .filter(Objects::nonNull)  // 파싱 실패 제외
                .toList();
    }

    private KickboardDevice toDevice(Item item) {
        try {
            double lat = Double.parseDouble(item.latitude());
            double lng = Double.parseDouble(item.longitude());
            int battery = parseInt(item.batteryLevel());
            return new KickboardDevice(item.deviceId(), item.operatorName(), lat, lng, battery);
        } catch (NumberFormatException e) {
            return null;  // 좌표 파싱 실패 → 제외
        }
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 0; }
    }
}
