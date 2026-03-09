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

    /**
     * trafficType: 1=지하철, 2=버스, 3=도보
     *
     * ODsay 실제 JSON 구조:
     *   lane        : [{name, busColor}]  — 배열 형태
     *   passStopList: {"stations": [...]} — 중첩 객체 형태
     *   stationCount: int                 — subPath 레벨 직접 제공
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubPath(
            int trafficType,
            int sectionTime,
            int distance,
            int stationCount,
            List<Lane> lane,
            PassStopList passStopList
    ) {
        /** 편의 메서드: 첫 번째 Lane 반환 (없으면 null) */
        public Lane firstLane() {
            return (lane != null && !lane.isEmpty()) ? lane.get(0) : null;
        }

        /** null-safe 정거장 목록 */
        public List<Station> stations() {
            if (passStopList == null || passStopList.stations() == null) return List.of();
            return passStopList.stations();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Lane(String name, String busColor, String busNo) {
        /**
         * 지하철: name ("수도권 2호선")
         * 버스:   busNo ("64") → "64번" 으로 변환
         */
        public String lineName() {
            if (name != null && !name.isBlank()) return name;
            if (busNo != null && !busNo.isBlank()) return busNo + "번";
            return null;
        }
    }

    /** ODsay passStopList 중첩 래퍼: {"stations": [...]} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PassStopList(List<Station> stations) {}

    // ODsay API: x = 경도(lng), y = 위도(lat)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Station(
            String stationName,
            @JsonProperty("x") double lng,
            @JsonProperty("y") double lat
    ) {}
}
