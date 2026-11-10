package com.blackmamba.navigation.infra.ddareungi.dto;

import com.blackmamba.navigation.infra.common.http.ExternalApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 서울 열린데이터광장 따릉이 응답.
 * <p>
 * 이 API 는 키 오류·요청 오류·서버 오류를 <b>HTTP 200 + 최상위 {@code RESULT}</b> 봉투로 돌려준다
 * ({@code {"RESULT":{"CODE":"INFO-100","MESSAGE":"인증키가 유효하지 않습니다."}}}).
 * 정상 응답은 {@code rentBikeStatus} 안에 {@code RESULT(INFO-000)}·{@code row} 가 실린다.
 * 봉투를 보지 않으면 오류가 "정류소 0개" 라는 성공으로 둔갑한다.
 * <p>
 * {@code list_total_count} 는 전체 건수가 아니라 <b>이번 응답의 행 수</b>다(실호출 확인: 1/5 → 5, 1/3 → 3).
 * 페이징 종료 판단에 쓸 수 없어 매핑하지 않고, {@link #rowCount()} 로 "페이지가 가득 찼는가" 만 본다.
 * <p>
 * 인증키 오류(INFO-100) 는 {@code /json/} 경로에서도 XML 로 내려온다 — JSON 으로 읽히지 않는 본문은
 * {@link #nonJsonError(String)} 로 같은 예외 체계에 태운다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DdareungiStationResponse(RentBikeStatus rentBikeStatus, @JsonProperty("RESULT") Result envelopeResult) {

    private static final String API_NAME = "따릉이";
    private static final String SUCCESS_CODE = "INFO-000";
    /** 요청 범위에 데이터 없음 — 마지막 페이지 너머를 요청했을 때. 오류가 아니라 빈 페이지. */
    private static final String NO_DATA_CODE = "INFO-200";
    private static final String SERVER_ERROR_CODE_PREFIX = "ERROR-5";
    private static final Pattern XML_CODE_PATTERN = Pattern.compile("<CODE>([^<]+)</CODE>");
    private static final int MAX_ERROR_BODY_LENGTH = 200;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RentBikeStatus(@JsonProperty("RESULT") Result result, List<Row> row) {}

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

    /** 이번 응답의 원본 행 수 — 요청 크기와 같으면 다음 페이지가 있을 수 있다. */
    public int rowCount() {
        if (rentBikeStatus == null || rentBikeStatus.row() == null) {
            return 0;
        }
        return rentBikeStatus.row().size();
    }

    /** JSON 이 아닌 본문(XML 오류 등)을 예외로. 본문에 {@code <CODE>} 가 있으면 그 코드로 재시도 여부를 가른다. */
    public static ExternalApiException nonJsonError(String body) {
        String abbreviated = body;
        if (body.length() > MAX_ERROR_BODY_LENGTH) {
            abbreviated = body.substring(0, MAX_ERROR_BODY_LENGTH) + "…";
        }
        Matcher matcher = XML_CODE_PATTERN.matcher(body);
        if (matcher.find()) {
            return toException(new Result(matcher.group(1), "JSON 아닌 응답: " + abbreviated));
        }
        return ExternalApiException.of(API_NAME, 500, "HTTP 200 이지만 JSON 이 아닌 응답: " + abbreviated);
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
