package com.blackmamba.navigation.infra.ddareungi;

import com.blackmamba.navigation.domain.route.mobility.AvailabilitySource;
import com.blackmamba.navigation.infra.ddareungi.DdareungiApiClient.StationSnapshot;
import com.blackmamba.navigation.infra.ddareungi.dto.DdareungiStation;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 따릉이 snapshot 의 신뢰 규칙 — 서울 열린데이터광장은 오류를 HTTP 200 봉투로 주고, 1회 1,000행만 준다.
 * 둘 다 놓치면 장애가 "반경 내 정류소 없음" 으로, 1,000행 밖 구는 영구히 "정류소 없음" 으로 보인다.
 */
class DdareungiApiClientTest {

    private static final String API_KEY = "test-key";
    private static final String PAGE_1 = "/" + API_KEY + "/json/bikeList/1/1000/";
    private static final String PAGE_2 = "/" + API_KEY + "/json/bikeList/1001/2000/";
    private static final double LAT = 37.5;
    private static final double LNG = 127.0;
    private static final int RADIUS = 700;
    private static final long NO_TTL = 0L;
    private static final long DEFAULT_MAX_STALE_AGE_MS = 900_000L;
    private static final String BROKEN = "broken";

    private static WireMockServer wireMock;
    private SimpleMeterRegistry meterRegistry;

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
    void 오류_봉투_200_응답은_빈_LIVE_snapshot_이_아니라_실패로_처리한다() {
        // given
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).willReturn(json(errorEnvelopeJson("INFO-100", "인증키가 유효하지 않습니다."))));
        DdareungiApiClient client = client(NO_TTL, DEFAULT_MAX_STALE_AGE_MS);

        // when
        StationSnapshot snapshot = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(snapshot.source()).isEqualTo(AvailabilitySource.EMPTY);
        assertThat(fallbackCount("refresh_backoff")).isEqualTo(1.0);
    }

    @Test
    void 오류_봉투가_와도_이전_snapshot_을_stale_로_유지한다() {
        // given — 첫 호출 정상, 두 번째 호출부터 오류 봉투
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).inScenario("outage").whenScenarioStateIs(STARTED)
                .willReturn(json(successJson(1, "ST-1"))).willSetStateTo(BROKEN));
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).inScenario("outage").whenScenarioStateIs(BROKEN)
                .willReturn(json(errorEnvelopeJson("ERROR-500", "서버 오류"))));
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
    void 정류소_0개_성공_응답은_이전_snapshot_을_덮지_않는다() {
        // given
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).inScenario("empty").whenScenarioStateIs(STARTED)
                .willReturn(json(successJson(1, "ST-1"))).willSetStateTo(BROKEN));
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).inScenario("empty").whenScenarioStateIs(BROKEN)
                .willReturn(json(successJson(0))));
        DdareungiApiClient client = client(NO_TTL, DEFAULT_MAX_STALE_AGE_MS);

        // when
        client.getNearbyStations(LAT, LNG, RADIUS).block();
        StationSnapshot second = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(second.source()).isEqualTo(AvailabilitySource.STALE);
        assertThat(second.stations()).hasSize(1);
        assertThat(fallbackCount("empty_live_ignored")).isEqualTo(1.0);
    }

    @Test
    void 재사용_한도를_넘은_stale_snapshot_은_빈_결과로_처리한다() {
        // given — 재사용 한도 0ms
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).inScenario("aged").whenScenarioStateIs(STARTED)
                .willReturn(json(successJson(1, "ST-1"))).willSetStateTo(BROKEN));
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).inScenario("aged").whenScenarioStateIs(BROKEN)
                .willReturn(json(errorEnvelopeJson("ERROR-500", "서버 오류"))));
        DdareungiApiClient client = client(NO_TTL, 0L);

        // when
        client.getNearbyStations(LAT, LNG, RADIUS).block();
        StationSnapshot second = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(second.source()).isEqualTo(AvailabilitySource.EMPTY);
        assertThat(second.stations()).isEmpty();
        assertThat(fallbackCount("stale_expired")).isEqualTo(1.0);
    }

    @Test
    void 전체_정류소가_한_페이지를_넘으면_이어서_조회한다() {
        // given — 전체 1,500개 → 1/1000 + 1001/2000
        wireMock.stubFor(get(urlPathEqualTo(PAGE_1)).willReturn(json(successJson(1_500, "ST-1"))));
        wireMock.stubFor(get(urlPathEqualTo(PAGE_2)).willReturn(json(successJson(1_500, "ST-1001"))));
        DdareungiApiClient client = client(NO_TTL, DEFAULT_MAX_STALE_AGE_MS);

        // when
        StationSnapshot snapshot = client.getNearbyStations(LAT, LNG, RADIUS).block();

        // then
        assertThat(snapshot.stations())
                .extracting(DdareungiStation::stationId)
                .containsExactly("ST-1", "ST-1001");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(PAGE_2)));
    }

    private DdareungiApiClient client(long snapshotTtlMs, long maxStaleAgeMs) {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
        return new DdareungiApiClient(WebClient.builder(), API_KEY, wireMock.baseUrl(), snapshotTtlMs, 5_000L, 0L, maxStaleAgeMs,
                meterRegistry, CircuitBreakerRegistry.ofDefaults(), retryRegistry);
    }

    private double fallbackCount(String reason) {
        return meterRegistry.get("navigation.ddareungi.fallback.total")
                .tag("reason", reason)
                .counter()
                .count();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }

    private static String errorEnvelopeJson(String code, String message) {
        return """
               {"RESULT":{"CODE":"%s","MESSAGE":"%s"}}
               """.formatted(code, message);
    }

    /** 성공 응답 — 모든 행은 테스트 좌표 위에 두어 반경 필터를 통과한다. */
    private static String successJson(int totalCount, String... stationIds) {
        StringBuilder rows = new StringBuilder();
        for (String stationId : stationIds) {
            if (!rows.isEmpty()) {
                rows.append(',');
            }
            rows.append("""
                    {"stationId":"%s","stationName":"%s 정류소","stationLatitude":"%s","stationLongitude":"%s","parkingBikeTotCnt":"5","rackTotCnt":"10"}
                    """.formatted(stationId, stationId, LAT, LNG));
        }
        return """
               {"rentBikeStatus":{"list_total_count":%d,"RESULT":{"CODE":"INFO-000","MESSAGE":"정상 처리되었습니다"},"row":[%s]}}
               """.formatted(totalCount, rows);
    }
}
