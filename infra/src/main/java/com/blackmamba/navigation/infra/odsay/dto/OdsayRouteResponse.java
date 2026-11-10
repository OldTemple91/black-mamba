package com.blackmamba.navigation.infra.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ODsay 응답. 키 오류·일일 한도 초과·서버 오류는 HTTP 200 + {@code error} 봉투로 내려온다
 * ({@code {"error":{"code":"500","msg":"ApiKeyAuthFailed"}}} 또는 {@code {"error":[{"code":"-8","message":"..."}]}}).
 * 두 꼴이 모두 관찰되어 {@link JsonNode} 로 받아 {@link #findApiError()} 가 정규화한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OdsayRouteResponse(Result result, JsonNode error) {

    public Optional<ApiError> findApiError() {
        if (error == null || error.isNull() || error.isMissingNode()) {
            return Optional.empty();
        }
        JsonNode node = error;
        if (node.isArray()) {
            if (node.isEmpty()) {
                return Optional.empty();
            }
            node = node.get(0);
        }
        String message = node.path("msg").asText("");
        if (node.hasNonNull("message")) {
            message = node.path("message").asText();
        }
        return Optional.of(new ApiError(node.path("code").asText(""), message));
    }

    /**
     * ODsay 공식 오류 코드(lab.odsay.com 레퍼런스) 기준 분류. 부호로는 가를 수 없다 —
     * 3·4·5·6 은 양수지만 "정류장 없음·서비스 지역 아님" 이고, -8·-9 는 음수지만 우리 요청의 버그다.
     */
    public record ApiError(String code, String message) {

        /** 3 출발지 정류장 없음, 4 도착지 정류장 없음, 5 출·도착 정류장 없음, 6 서비스 지역 아님, -98 700m 이내, -99 검색 결과 없음 */
        private static final Set<String> NO_ROUTE_CODES = Set.of("3", "4", "5", "6", "-98", "-99");
        /** -8 입력값 형식·범위 오류, -9 필수 입력값 누락 */
        private static final Set<String> BAD_REQUEST_CODES = Set.of("-8", "-9");
        private static final String SERVER_ERROR_CODE = "500";

        /** 이 출발/도착 조합에 경로가 없다는 정상 답 — 장애가 아니다. */
        public boolean isNoRoute() {
            return NO_ROUTE_CODES.contains(code);
        }

        /** 우리가 보낸 요청이 잘못됨 — 재시도해도 같은 답이고, 조용히 넘기면 안 된다. */
        public boolean isBadRequest() {
            return BAD_REQUEST_CODES.contains(code);
        }

        public boolean isServerError() {
            return SERVER_ERROR_CODE.equals(code);
        }
    }

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
