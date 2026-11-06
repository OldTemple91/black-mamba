package com.blackmamba.navigation.infra.ddareungi.dto;

import com.blackmamba.navigation.infra.common.http.ExternalApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/**
 * 서울 열린데이터광장 따릉이 응답.
 * <p>
 * 이 API 는 키 오류·요청 오류·서버 오류를 <b>HTTP 200 + 최상위 {@code RESULT}</b> 봉투로 돌려준다
 * ({@code {"RESULT":{"CODE":"INFO-100","MESSAGE":"인증키가 유효하지 않습니다."}}}).
 * 정상 응답은 {@code rentBikeStatus} 안에 {@code list_total_count}·{@code RESULT(INFO-000)}·{@code row} 가 실린다.
 * 봉투를 보지 않으면 오류가 "정류소 0개" 라는 성공으로 둔갑한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DdareungiStationResponse(RentBikeStatus rentBikeStatus, @JsonProperty("RESULT") Result envelopeResult) {

    private static final String API_NAME = "따릉이";
    private static final String SUCCESS_CODE = "INFO-000";
    /** 요청 범위에 데이터 없음 — 마지막 페이지 너머를 요청했을 때. 오류가 아니라 빈 페이지. */
    private static final String NO_DATA_CODE = "INFO-200";
    private static final String SERVER_ERROR_CODE_PREFIX = "ERROR-5";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RentBikeStatus(
            @JsonProperty("list_total_count") int listTotalCount,
            @JsonProperty("RESULT") Result result,
            List<Row> row
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(@JsonProperty("CODE") String code, @JsonProperty("MESSAGE") String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(
            String stationId,
            String stationName,
            String stationLatitude,
            String stationLongitude,
            String parkingBikeTotCnt,  // 잔여 대수 (String으로 내려옴)
            String rackTotCnt
    ) {}

    /**
     * 오류 봉투면 {@link ExternalApiException} 을 던지고, 아니면 자신을 돌려준다.
     * 예외여야 재시도·회로 차단·stale 폴백 체인이 동작한다 — 빈 목록은 그 체인을 전부 건너뛴다.
     */
    public DdareungiStationResponse requireSuccess() {
        if (rentBikeStatus != null) {
            Result result = rentBikeStatus.result();
            if (result == null || SUCCESS_CODE.equals(result.code())) {
                return this;
            }
            throw toException(result);
        }
        if (envelopeResult == null) {
            throw ExternalApiException.of(API_NAME, 500, "HTTP 200 응답에 rentBikeStatus 도 RESULT 도 없음");
        }
        if (NO_DATA_CODE.equals(envelopeResult.code())) {
            return this;
        }
        throw toException(envelopeResult);
    }

    /** 전체 정류소 수 — 1,000행 단위 페이징 계산용. */
    public int totalCount() {
        if (rentBikeStatus == null) {
            return 0;
        }
        return rentBikeStatus.listTotalCount();
    }

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

    /** ERROR-5xx 는 서버 오류(재시도 대상), 나머지(INFO-100 키 오류, ERROR-3xx 요청 오류)는 재시도해도 같은 답. */
    private static ExternalApiException toException(Result result) {
        int status = 400;
        if (result.code() != null && result.code().startsWith(SERVER_ERROR_CODE_PREFIX)) {
            status = 500;
        }
        return ExternalApiException.of(API_NAME, status, "HTTP 200 오류 봉투 " + result.code() + " " + result.message());
    }

    private DdareungiStation toStation(Row row) {
        try {
            double lat = Double.parseDouble(row.stationLatitude());
            double lng = Double.parseDouble(row.stationLongitude());
            int available = parseInt(row.parkingBikeTotCnt());
            int rackTotal = parseInt(row.rackTotCnt());
            return new DdareungiStation(row.stationId(), row.stationName(), lat, lng, available, rackTotal);
        } catch (NumberFormatException e) {
            // 좌표 파싱 실패 row는 제외
            return null;
        }
    }

    private int parseInt(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 0; }
    }
}
