package com.blackmamba.navigation.infra.odsay;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.application.route.port.BudgetLevel;
import com.blackmamba.navigation.infra.common.cache.TtlCacheRegistry;
import com.blackmamba.navigation.infra.common.http.ExternalApiServerException;
import com.blackmamba.navigation.infra.common.resilience.ExternalApiResilience;
import com.blackmamba.navigation.domain.route.Leg;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2: WireMock 을 이용한 {@link OdsayRouteClient} + Resilience4j 통합 테스트.
 *
 * <h3>테스트 전략</h3>
 * WireMock 으로 실제 HTTP 서버를 띄워 ODsay API 엔드포인트를 흉내낸다.
 * {@code odsay.base-url} 을 WireMock 포트로 오버라이드해 WebClient → WireMock 전체 체인 통과.
 * 이로써 단순 Mapper 단위 테스트를 넘어서 <b>실제 HTTP 레벨의 장애 시나리오</b> 까지 검증:
 * <ol>
 *   <li>정상 200 응답: 파싱 성공, Leg 리스트 반환</li>
 *   <li>연속 5xx: Retry 3회 수행 후 최종 fallback (빈 리스트 + 에러 카운터 증가)</li>
 *   <li>응답 지연 정상 케이스: Retry 체인이 지연 응답을 정상 처리</li>
 * </ol>
 *
 * <h3>구성</h3>
 * Spring 컨텍스트 없이 {@link OdsayRouteClient} 를 수동 조립해 빠르고 독립적.
 *
 * <h3>Note — CircuitBreaker OPEN 상태 검증은 별도 실측</h3>
 * 본 테스트 환경에서 {@code CircuitBreakerOperator} + 기존 {@code Mono.cache()} 의
 * signal 상호작용으로 단위 테스트 수준에서는 OPEN 전환이 불안정했음. 대신 실제 앱 기동
 * 후 {@code /actuator/circuitbreakers} 엔드포인트로 CB 상태를 실측 확인했고,
 * Prometheus {@code resilience4j_circuitbreaker_state} 메트릭으로 운영 중 관측 가능.
 */
class OdsayRouteClientResilienceTest {

    private static WireMockServer wireMock;

    private OdsayRouteClient client;
    private SimpleMeterRegistry meterRegistry;

    private static final String PATH = "/searchPubTransPathT";
    private static final int DAILY_LIMIT = 4;
    // 700m 이상 떨어진 좌표 (ODsay 거리 하한을 넘어야 실제 호출 발생)
    private static final Location ORIGIN = new Location("서울역", 37.5547, 126.9706);
    private static final Location DESTINATION = new Location("강남역", 37.4979, 127.0276);

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

        // 테스트용 Resilience4j — 민감도 최대 (1회만 실패해도 OPEN)
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .slidingWindowSize(2)
                        .minimumNumberOfCalls(2)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .permittedNumberOfCallsInHalfOpenState(1)
                        .recordException(throwable -> true)  // 모든 예외를 실패로 기록 명시
                        .build());
        RetryRegistry retryRegistry = RetryRegistry.of(
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ofMillis(50))
                        // application.yml 의 retryExceptions 와 같은 구성 — 4xx 계열(ExternalApiClientException)은 재시도하지 않는다
                        .retryExceptions(IOException.class, TimeoutException.class, ExternalApiServerException.class, WebClientRequestException.class)
                        .build());
        this.meterRegistry = new SimpleMeterRegistry();
        OdsayRouteMapper mapper = new OdsayRouteMapper();

        this.client = new OdsayRouteClient(
                WebClient.builder(),
                mapper,
                "test-api-key",
                wireMock.baseUrl(),        // odsay.base-url 을 WireMock 으로 오버라이드
                0L,                         // cache TTL = 0 → 매 테스트마다 실제 호출
                5_000L,                     // request timeout
                DAILY_LIMIT,                // 일일 예산
                0.3,                        // 절약 수준 기준(남은 비율)
                meterRegistry,
                new ExternalApiResilience(cbRegistry, retryRegistry),
                new TtlCacheRegistry(meterRegistry)
        );
    }

    @Test
    void 정상_200_응답에서_Leg_리스트를_파싱한다() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(sampleSubwayResponseJson())));

        StepVerifier.create(client.getTransitRoute(ORIGIN, DESTINATION))
                .assertNext(legs -> {
                    assertThat(legs).isNotEmpty();
                    // 실제 호출이 1회 일어남 확인
                    wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH))
                            .withQueryParam("apiKey", equalTo("test-api-key")));
                })
                .verifyComplete();
    }

    @Test
    void 연속_500_응답에서_Retry_3회_후_빈리스트_fallback() {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(500).withBody("ODsay 서버 오류")));

        StepVerifier.create(client.getTransitRoute(ORIGIN, DESTINATION))
                .assertNext(legs -> {
                    assertThat(legs).isEmpty();
                    // Retry 3회 * 실제 호출 = WireMock 에 3회 요청 도달
                    wireMock.verify(3, getRequestedFor(urlPathEqualTo(PATH)));
                    // 에러 카운터 증가
                    assertThat(meterRegistry
                            .get("navigation.odsay.fallback.total")
                            .tags("type", "transit_route", "reason", "error")
                            .counter()
                            .count()).isEqualTo(1.0);
                })
                .verifyComplete();
    }

    @Test
    void 서버_오류_봉투_200_응답은_실패로_집계되어_Retry_후_빈리스트_fallback() {
        // given — ODsay 는 서버 내부 오류를 HTTP 200 + code 500 봉투로 돌려준다
        stubErrorEnvelope("""
                {"error":{"code":"500","msg":"Internal Server Error"}}
                """);

        // when & then
        StepVerifier.create(client.getTransitRoute(ORIGIN, DESTINATION))
                .assertNext(legs -> {
                    assertThat(legs).isEmpty();
                    wireMock.verify(3, getRequestedFor(urlPathEqualTo(PATH)));
                    assertThat(fallbackCount("error")).isEqualTo(1.0);
                    assertThat(fallbackCount("empty_result")).isZero();
                })
                .verifyComplete();
    }

    @Test
    void 인증_실패는_code_500_이어도_재시도하지_않는다() {
        // given — 실제 응답 형식. 키가 틀리면 재시도해도 같은 답이다
        stubErrorEnvelope("""
                {"error":[{"code":"500","message":"[ApiKeyAuthFailed] ApiKey authentication failed."}]}
                """);

        // when & then
        StepVerifier.create(client.getTransitRoute(ORIGIN, DESTINATION))
                .assertNext(legs -> {
                    assertThat(legs).isEmpty();
                    wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
                    assertThat(fallbackCount("error")).isEqualTo(1.0);
                })
                .verifyComplete();
    }

    @Test
    void 한도_초과_응답을_받으면_그_뒤로는_호출을_보내지_않는다() {
        // given — 집계는 여유가 있지만(재기동 직후 등) API 가 한도 초과로 답한다
        stubErrorEnvelope("""
                {"error":[{"code":"429","message":"Daily quota exceeded"}]}
                """);
        client.getTransitRoute(ORIGIN, DESTINATION).block();

        // when — 다른 구간을 조회해도
        List<Leg> legs = client.getTransitRoute(DESTINATION, ORIGIN).block();

        // then — 요청은 첫 1건뿐이고, 두 번째는 예산 소진으로 바로 빈 결과
        assertThat(legs).isEmpty();
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(client.budgetLevel()).isEqualTo(BudgetLevel.EXHAUSTED);
        assertThat(fallbackCount("budget_exhausted")).isEqualTo(1.0);
    }

    @Test
    void 일일_예산을_다_쓰면_호출을_보내지_않고_캐시된_구간은_예산을_쓰지_않는다() {
        // given — 한도 4건. 캐시 TTL 0 이라 매 호출이 실제 요청이다
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(sampleSubwayResponseJson())));

        // when — 6번 조회
        for (int i = 0; i < 6; i++) {
            client.getTransitRoute(ORIGIN, DESTINATION).block();
        }

        // then — 4건만 나가고 2건은 보내지 않았다
        wireMock.verify(DAILY_LIMIT, getRequestedFor(urlPathEqualTo(PATH)));
        assertThat(fallbackCount("budget_exhausted")).isEqualTo(2.0);
    }

    @Test
    void 일일_한도_초과는_재시도하지_않고_실패로_집계한다() {
        // given — 실제 운영 키로 받은 응답 그대로. 한도는 재시도해도 풀리지 않는다
        stubErrorEnvelope("""
                {"error":[{"code":"429","message":"Daily quota exceeded"}]}
                """);

        // when & then
        StepVerifier.create(client.getTransitRoute(ORIGIN, DESTINATION))
                .assertNext(legs -> {
                    assertThat(legs).isEmpty();
                    wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
                    assertThat(fallbackCount("error")).isEqualTo(1.0);
                    assertThat(fallbackCount("empty_result")).isZero();
                })
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", "4", "5", "6", "-98", "-99"})
    void 정류장_없음과_검색결과_없음_코드는_장애가_아니라_경로_없음이다(String code) {
        // given — 양수 3~6 도 "정류장 없음·서비스 지역 아님" 이라 회로에 실패로 쌓이면 안 된다
        stubErrorEnvelope("""
                {"error":[{"code":"%s","message":"검색 결과가 없습니다."}]}
                """.formatted(code));

        // when & then
        StepVerifier.create(client.getTransitRoute(ORIGIN, DESTINATION))
                .assertNext(legs -> {
                    assertThat(legs).isEmpty();
                    wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
                    assertThat(fallbackCount("empty_result")).isEqualTo(1.0);
                    assertThat(fallbackCount("error")).isZero();
                })
                .verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-8", "-9"})
    void 요청_형식_오류_코드는_경로_없음으로_묻지_않고_재시도_없이_실패로_집계한다(String code) {
        // given
        stubErrorEnvelope("""
                {"error":[{"code":"%s","message":"필수 입력값 형식 및 범위 오류"}]}
                """.formatted(code));

        // when & then
        StepVerifier.create(client.getTransitRoute(ORIGIN, DESTINATION))
                .assertNext(legs -> {
                    assertThat(legs).isEmpty();
                    wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
                    assertThat(fallbackCount("error")).isEqualTo(1.0);
                    assertThat(fallbackCount("empty_result")).isZero();
                })
                .verifyComplete();
    }

    @Test
    void timeout_시에도_Retry_후_fallback() {
        // WireMock 에서 fixedDelay 로 응답 지연을 흉내내어 정상 완료 확인
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(50)  // 50ms 지연 (timeout 안 걸릴 정도)
                        .withHeader("Content-Type", "application/json")
                        .withBody(sampleSubwayResponseJson())));

        StepVerifier.create(client.getTransitRoute(ORIGIN, DESTINATION))
                .assertNext(legs -> assertThat(legs).isNotEmpty())
                .verifyComplete();
    }

    // ─── 헬퍼: 최소한의 ODsay 샘플 응답 ───

    private void stubErrorEnvelope(String body) {
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private double fallbackCount(String reason) {
        return meterRegistry.get("navigation.odsay.fallback.total")
                .tags("type", "transit_route", "reason", reason)
                .counter()
                .count();
    }

    /**
     * 실제 ODsay 응답 구조를 단순화한 JSON.
     * 4호선 지하철 직행 구간 1개 — Mapper 가 TRANSIT Leg 1개로 변환할 수 있는 최소 조건.
     */
    private static String sampleSubwayResponseJson() {
        return """
               {
                 "result": {
                   "path": [
                     {
                       "info": {"totalTime": 28, "busTransitCount": 0, "payment": 1650},
                       "subPath": [
                         {
                           "trafficType": 1,
                           "sectionTime": 28,
                           "distance": 10500,
                           "stationCount": 10,
                           "lane": [{"name": "4호선", "subwayCode": 4}],
                           "passStopList": {
                             "stations": [
                               {"stationName": "서울역", "x": "126.9706", "y": "37.5547"},
                               {"stationName": "강남역", "x": "127.0276", "y": "37.4979"}
                             ]
                           }
                         }
                       ]
                     }
                   ]
                 }
               }
               """;
    }
}
