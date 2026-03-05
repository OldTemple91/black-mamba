package com.blackmamba.navigation.infra.ddareungi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DdareungiStationResponse(RentBikeStatus rentBikeStatus) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RentBikeStatus(List<Row> row) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(
            String stationName,
            String stationLatitude,
            String stationLongitude,
            String parkingBikeTotCnt  // 잔여 대수 (String으로 내려옴)
    ) {}

    public List<DdareungiStation> toStations() {
        if (rentBikeStatus == null || rentBikeStatus.row() == null) {
            return List.of();
        }
        return rentBikeStatus.row().stream()
                .filter(Objects::nonNull)
                .map(this::toStation)
                .filter(Objects::nonNull)  // 파싱 실패 row 제외
                .toList();
    }

    private DdareungiStation toStation(Row row) {
        try {
            double lat = Double.parseDouble(row.stationLatitude());
            double lng = Double.parseDouble(row.stationLongitude());
            int available = parseInt(row.parkingBikeTotCnt());
            return new DdareungiStation(row.stationName(), lat, lng, available);
        } catch (NumberFormatException e) {
            // 좌표 파싱 실패 row는 제외
            return null;
        }
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 0; }
    }
}
