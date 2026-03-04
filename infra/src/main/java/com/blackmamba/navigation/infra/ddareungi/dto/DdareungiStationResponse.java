package com.blackmamba.navigation.infra.ddareungi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

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
                .map(row -> new DdareungiStation(
                        row.stationName(),
                        parseDouble(row.stationLatitude()),
                        parseDouble(row.stationLongitude()),
                        parseInt(row.parkingBikeTotCnt())
                ))
                .toList();
    }

    private double parseDouble(String value) {
        try { return Double.parseDouble(value); } catch (Exception e) { return 0; }
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (Exception e) { return 0; }
    }
}
