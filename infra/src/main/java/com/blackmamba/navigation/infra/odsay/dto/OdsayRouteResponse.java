package com.blackmamba.navigation.infra.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayRouteResponse(Result result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(List<Path> path) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Path(PathInfo info, List<SubPath> subPath) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PathInfo(int totalTime, int transitCount, int payment) {}

    // trafficType: 1=지하철, 2=버스, 3=도보
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubPath(
            int trafficType,
            int sectionTime,
            int distance,
            Lane lane,
            List<Station> passStopList
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lane(String name, String busColor) {}

    // ODsay API: x = 경도(lng), y = 위도(lat)
    // 생성자 파라미터 순서: (stationName, lng, lat) — x/y JSON 매핑 주의
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Station(
            String stationName,
            @JsonProperty("x") double lng,
            @JsonProperty("y") double lat
    ) {}
}
