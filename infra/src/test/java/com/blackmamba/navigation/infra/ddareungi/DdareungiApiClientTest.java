package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.domain.route.mobility.AvailabilitySource;
import com.blackmamba.navigation.infra.common.http.ExternalApiException;
import com.blackmamba.navigation.infra.common.resilience.ExternalApiResilience;
import com.blackmamba.navigation.infra.ddareungi.DdareungiApiClient.StationSnapshot;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 따릉이 snapshot 의 신뢰 규칙. 스텁은 서울 열린데이터광장 실호출로 확인한 형식을 따른다:
 * <ul>
 *   <li>응답에 전체 건수가 없다({@code list_total_count} = 이번 응답 행 수) → 가득 찬 페이지 다음을 이어 받는다</li>
 *   <li>서비스 오류는 HTTP 200 + JSON {@code RESULT} 봉투, 인증키 오류는 HTTP 200 + XML 본문</li>
 * </ul>
 */
class DdareungiApiClientTest {

    private static final String API_KEY = "test-key";
    private static final String PAGE_1 = "/" + API_KEY + "/json/bikeList/1/1000/";
    private static final String PAGE_2 = "/" + API_KEY + "/json/bikeList/1001/2000/";
    private static final String PAGE_3 = "/" + API_KEY + "/json/bikeList/2001/3000/";
    private static final int PAGE_SIZE = 1_000;
    private static final double LAT = 37.5;
    private static final double LNG = 127.0;
    private static final int RADIUS = 700;
    private static final long NO_TTL = 0L;
    private static final long DEFAULT_MAX_STALE_AGE_MS = 900_000L;
    private static final String BROKEN = "broken";

    private static WireMockServer wireMock;
    private SimpleMeterRegistry meterRegistry;
    private CircuitBreaker circuitBreaker;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    void JSON_오류_봉투_200_응답은_빈_snapshot_이_아니라_회로에_기록되는_실패다() {
        // given
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).willReturn(json(errorEnvelopeJson("ERROR-500", "서버 오류입니다."))));
        DdareungiApiClient client = client(NO_TTL, DEFAULT_MAX_STALE_AGE_MS);

        // when
        StationSnapshot snapshot = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(snapshot.source()).isEqualTo(AvailabilitySource.EMPTY);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void 인증키_오류의_XML_본문도_회로에_기록되는_실패다() {
        // given — /json/ 경로여도 인증키 오류는 XML 로 온다
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/xml;charset=UTF-8")
                .withBody("<RESULT><CODE>INFO-100</CODE><MESSAGE><![CDATA[인증키가 유효하지 않습니다.]]></MESSAGE></RESULT>")));
        DdareungiApiClient client = client(NO_TTL, DEFAULT_MAX_STALE_AGE_MS);

        // when
        StationSnapshot snapshot = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(snapshot.source()).isEqualTo(AvailabilitySource.EMPTY);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void 오류_봉투가_와도_이전_snapshot_을_stale_로_유지한다() {
        // given — 첫 호출 정상, 두 번째 호출부터 오류 봉투
        stubSuccessThen("outage", json(errorEnvelopeJson("ERROR-500", "서버 오류입니다.")));
        DdareungiApiClient client = client(NO_TTL, DEFAULT_MAX_STALE_AGE_MS);

        // when
        StationSnapshot first = client.getNearbyStations(LAT, LNG, RADIUS).block();
        StationSnapshot second = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(first.source()).isEqualTo(AvailabilitySource.LIVE);
        assertThat(second.source()).isEqualTo(AvailabilitySource.STALE);
        assertThat(second.stations())
                .extracting(DdareungiStation::stationId)
                .containsExactly("ST-1");
    }

    @Test
    void 정류소_0개_성공_응답은_실패로_보고_이전_snapshot_을_stale_로_유지한다() {
        // given
        stubSuccessThen("empty", json(successJson(0, "ST")));
        DdareungiApiClient client = client(NO_TTL, DEFAULT_MAX_STALE_AGE_MS);

        // when
        client.getNearbyStations(LAT, LNG, RADIUS).block();
        StationSnapshot second = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(second.source()).isEqualTo(AvailabilitySource.STALE);
        assertThat(second.stations()).hasSize(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void 정류소_0개_응답이_이어져도_재사용_한도를_넘은_snapshot_은_내주지_않는다() {
        // given — 재사용 한도 0ms. 예전엔 0개 응답마다 TTL 만 연장해 나이 상한을 우회했다
        stubSuccessThen("aged", json(successJson(0, "ST")));
        DdareungiApiClient client = client(NO_TTL, 0L);

        // when
        client.getNearbyStations(LAT, LNG, RADIUS).block();
        StationSnapshot second = client.getNearbyStations(LAT, LNG, RADIUS).block();
        StationSnapshot third = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(second.source()).isEqualTo(AvailabilitySource.EMPTY);
        assertThat(third.source()).isEqualTo(AvailabilitySource.EMPTY);
        assertThat(fallbackCount("stale_expired")).isEqualTo(2.0);
    }

    @Test
    void 페이지가_가득_차_있으면_다음_페이지를_이어_받는다() {
        // given — 1페이지 1,000행(가득), 2페이지 1행 → 3페이지는 요청하지 않는다
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).willReturn(json(successJson(PAGE_SIZE, "P1"))));
        wireMock.stubFor(get(urlPathEqualTo(PAGE_2)).willReturn(json(successJson(1, "P2"))));
        DdareungiApiClient client = client(NO_TTL, DEFAULT_MAX_STALE_AGE_MS);

        // when
        StationSnapshot snapshot = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(snapshot.stations()).hasSize(PAGE_SIZE + 1);
        assertThat(snapshot.stations())
                .extracting(DdareungiStation::stationId)
                .contains("P1-1", "P1-1000", "P2-1");
        wireMock.verify(0, getRequestedFor(urlPathEqualTo(PAGE_3)));
    }

    @Test
    void 페이지가_가득_차지_않았으면_다음_페이지를_요청하지_않는다() {
        // given
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).willReturn(json(successJson(3, "P1"))));
        DdareungiApiClient client = client(NO_TTL, DEFAULT_MAX_STALE_AGE_MS);

        // when
        StationSnapshot snapshot = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(snapshot.stations()).hasSize(3);
        wireMock.verify(0, getRequestedFor(urlPathEqualTo(PAGE_2)));
    }

    @Test
    void 뒤_페이지가_실패하면_부분_snapshot_을_저장하지_않는다() {
        // given — 1페이지는 가득, 2페이지는 오류
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).willReturn(json(successJson(PAGE_SIZE, "P1"))));
        wireMock.stubFor(get(urlPathEqualTo(PAGE_2)).willReturn(json(errorEnvelopeJson("ERROR-500", "서버 오류입니다."))));
        DdareungiApiClient client = client(NO_TTL, DEFAULT_MAX_STALE_AGE_MS);

        // when
        StationSnapshot snapshot = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(snapshot.source()).isEqualTo(AvailabilitySource.EMPTY);
        assertThat(snapshot.stations()).isEmpty();
    }

    /** 운영 application.yml 의 recordExceptions 와 같은 구성 — 예외 타입이 틀리면 실패로 집계되지 않는다. */
    private DdareungiApiClient client(long snapshotTtlMs, long maxStaleAgeMs) {
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .recordExceptions(IOException.class, TimeoutException.class, ExternalApiException.class)
                .build());
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("ddareungi");
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
        return new DdareungiApiClient(WebClient.builder(), new ObjectMapper(), API_KEY, wireMock.baseUrl(), snapshotTtlMs, 5_000L, 0L, maxStaleAgeMs,
                meterRegistry, new ExternalApiResilience(circuitBreakerRegistry, retryRegistry));
    }

    /** 첫 호출은 정류소 1개 성공, 이후 호출은 {@code next} 응답. */
    private void stubSuccessThen(String scenario, ResponseDefinitionBuilder next) {
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).inScenario(scenario).whenScenarioStateIs(STARTED)
                .willReturn(json(successJson(1, "ST"))).willSetStateTo(BROKEN));
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).inScenario(scenario).whenScenarioStateIs(BROKEN)
                .willReturn(next));
    }

    private double fallbackCount(String reason) {
        return meterRegistry.get("navigation.ddareungi.fallback.total")
                .tag("reason", reason)
                .counter()
                .count();
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json;charset=UTF-8")
                .withBody(body);
    }

    private static String errorEnvelopeJson(String code, String message) {
        return """
               {"RESULT":{"CODE":"%s","MESSAGE":"%s"}}
               """.formatted(code, message);
    }

    /** 성공 응답 — 행 ID 는 {@code prefix-1..n}. list_total_count 는 실제 API 처럼 이번 응답의 행 수다. 모든 행은 테스트 좌표 위에 둔다. */
    private static String successJson(int rowCount, String idPrefix) {
        String rows = IntStream.rangeClosed(1, rowCount)
                .mapToObj(i -> """
                        {"stationId":"%s-%d","stationName":"%s-%d 정류소","stationLatitude":"%s","stationLongitude":"%s","parkingBikeTotCnt":"5","rackTotCnt":"10"}
                        """.formatted(idPrefix, i, idPrefix, i, LAT, LNG))
                .collect(Collectors.joining(","));
        return """
               {"rentBikeStatus":{"list_total_count":%d,"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다."},"row":[%s]}}
               """.formatted(rowCount, rows);
    }
}
