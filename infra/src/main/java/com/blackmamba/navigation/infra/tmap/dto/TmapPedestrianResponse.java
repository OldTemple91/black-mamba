package com.blackmamba.navigation.infra.tmap.dto;

import com.blackmamba.navigation.domain.location.Location;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * TMAP 보행자 경로 API 응답 (GeoJSON FeatureCollection).
 * 첫 번째 Feature(출발지 Point)의 properties에 totalDistance(m)가 포함.
 * LineString Feature의 geometry.coordinates에 실제 도로 경로 좌표가 포함.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmapPedestrianResponse(List<TmapFeature> features) {

    public int totalDistanceMeters() {
        if (features == null || features.isEmpty()) return 0;

        TmapProperties firstProps = features.get(0).properties();
        if (firstProps != null && firstProps.totalDistance() > 0) {
            return firstProps.totalDistance();
        }

        return features.stream()
                .filter(f -> f.properties() != null)
                .mapToInt(f -> f.properties().distance())
                .sum();
    }

    /**
     * LineString Feature에서 실제 도로 경로 좌표 추출.
     * TMAP GeoJSON 좌표는 [lng, lat] 순서.
     */
    public List<Location> routeCoordinates() {
        if (features == null) return List.of();
        return features.stream()
                .filter(f -> f.geometry() != null && "LineString".equals(f.geometry().type()))
                .flatMap(f -> f.geometry().coordinates().stream())
                .filter(c -> c != null && c.size() >= 2)
                .map(c -> new Location(null, c.get(1), c.get(0)))  // [lng, lat] → Location(lat, lng)
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TmapFeature(TmapGeometry geometry, TmapProperties properties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TmapGeometry(String type, List<List<Double>> coordinates) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TmapProperties(int distance, int totalDistance, int totalTime) {}
}
