package com.blackmamba.navigation.infra.tmap;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.infra.common.cache.TtlCacheRegistry;
import com.blackmamba.navigation.infra.common.resilience.ExternalApiResilience;
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
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tmap 실패 폴백(직선거리) 은 캐시되면 안 된다 — 지난 라운드에 ODsay 만 고치고 Tmap 은 빠져 있었다.
 */
class TmapPedestrianClientTest {

    private static final String PATH = "/tmap/routes/pedestrian";
    private static final Location ORIGIN = new Location("서울역", 37.5547, 126.9706);
    private static final Location DESTINATION = new Location("남대문", 37.5600, 126.9750);
    private static final long ROUTE_TTL_MS = 60_000L;
    private static final long QUOTA_BACKOFF_MS = 60_000L;
    private static final String RECOVERED = "recovered";

    private static WireMockServer wireMock;
    private SimpleMeterRegistry meterRegistry;
    private TmapPedestrianClient client;

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
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
        client = new TmapPedestrianClient(WebClient.builder(), "test-app-key", wireMock.baseUrl(), ROUTE_TTL_MS, QUOTA_BACKOFF_MS, 5_000L,
                meterRegistry, new ExternalApiResilience(CircuitBreakerRegistry.ofDefaults(), retryRegistry), new TtlCacheRegistry(meterRegistry));
    }

    @Test
    void 오류_폴백은_캐시되지_않아_다음_호출이_다시_조회한다() {
        // given — 첫 호출 401, 복구 후 정상
        wireMock.stubFor(post(urlPathEqualTo(PATH)).inScenario("outage").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(401).withBody("invalid appKey")).willSetStateTo(RECOVERED));
        wireMock.stubFor(post(urlPathEqualTo(PATH)).inScenario("outage").whenScenarioStateIs(RECOVERED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(pedestrianJson())));

        // when
        var first = client.getRoute(ORIGIN, DESTINATION).block();
        var second = client.getRoute(ORIGIN, DESTINATION).block();

        // then
        assertThat(first).isEmpty();
        assertThat(second).isPresent();
        wireMock.verify(2, postRequestedFor(urlPathEqualTo(PATH)));
        assertThat(fallbackCount("error")).isEqualTo(1.0);
    }

    @Test
    void 정상_결과는_TTL_동안_캐시된다() {
        // given
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(pedestrianJson())));

        // when
        client.getRoute(ORIGIN, DESTINATION).block();
        client.getRoute(ORIGIN, DESTINATION).block();

        // then
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    void 쿼터_초과_429는_backoff_창_동안_호출을_생략한다() {
        // given
        wireMock.stubFor(post(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(429).withBody("quota exceeded")));

        // when
        client.getRoute(ORIGIN, DESTINATION).block();
        client.getRoute(ORIGIN, DESTINATION).block();

        // then
        wireMock.verify(1, postRequestedFor(urlPathEqualTo(PATH)));
        assertThat(fallbackCount("quota_exceeded")).isEqualTo(1.0);
        assertThat(fallbackCount("quota_short_circuit")).isEqualTo(1.0);
    }

    private double fallbackCount(String reason) {
        return meterRegistry.get("navigation.tmap.fallback.total")
                .tag("reason", reason)
                .counter()
                .count();
    }

    private static String pedestrianJson() {
        return """
               {"features":[
                 {"type":"Feature","geometry":{"type":"Point","coordinates":[126.9706,37.5547]},"properties":{"totalDistance":850,"totalTime":700}},
                 {"type":"Feature","geometry":{"type":"LineString","coordinates":[[126.9706,37.5547],[126.9750,37.5600]]},"properties":{}}
               ]}
               """;
    }
}
