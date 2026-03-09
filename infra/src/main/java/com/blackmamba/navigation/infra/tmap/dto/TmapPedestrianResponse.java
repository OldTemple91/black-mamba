package com.blackmamba.navigation.infra.tmap.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * TMAP 보행자 경로 API 응답 (GeoJSON FeatureCollection).
 * 첫 번째 Feature(출발지 Point)의 properties에 totalDistance(m)가 포함.
 * 없을 경우 각 구간 distance 합산으로 fallback.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmapPedestrianResponse(List<TmapFeature> features) {

    public int totalDistanceMeters() {
        if (features == null || features.isEmpty()) return 0;

        // 첫 번째 feature(출발지 Point)에서 totalDistance 확인
        TmapProperties firstProps = features.get(0).properties();
        if (firstProps != null && firstProps.totalDistance() > 0) {
            return firstProps.totalDistance();
        }

        // fallback: 모든 구간 distance 합산
        return features.stream()
                .filter(f -> f.properties() != null)
                .mapToInt(f -> f.properties().distance())
                .sum();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TmapFeature(TmapProperties properties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TmapProperties(int distance, int totalDistance, int totalTime) {}
}
