package com.blackmamba.navigation.replay;

import com.blackmamba.navigation.application.route.RouteSearchQuery;
import com.blackmamba.navigation.application.route.SearchMode;
import com.blackmamba.navigation.application.route.accessibility.AccessibilityContext;
import com.blackmamba.navigation.application.route.weather.WeatherContext;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.weather.WeatherCondition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 녹화해 둔 외부 API 응답으로 실제 탐색을 다시 돌려, 순위가 의도 없이 바뀌지 않았는지 본다.
 * <p>
 * 외부 API 를 부르지 않으므로 호출 한도·네트워크와 무관하게 같은 결과가 나온다.
 * 가중치·허브 선택·후처리를 고치면 여기서 "어느 출발·도착의 순위가 어떻게 바뀌었는지" 가 드러난다.
 * <pre>
 * 재생(기본)      ./gradlew :api:test --tests '*RouteRankingReplayTest'
 * 기대값 갱신     REPLAY_UPDATE_EXPECTED=true ./gradlew :api:test --tests '*RouteRankingReplayTest' --rerun-tasks
 * 응답 녹화       REPLAY_RECORD=true REPLAY_UPDATE_EXPECTED=true (+ ODSAY_API_KEY, DDAREUNGI_API_KEY, TMAP_APP_KEY) 같은 명령
 * </pre>
 * 녹화는 실제 API 를 호출하므로 호출 한도를 쓴다. 이미 녹화된 조회는 다시 부르지 않는다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteRankingReplayTest {
    private static final Path REPLAY_DIR = Path.of("src/test/resources/replay");
    private static final Path FIXTURE_DIR = REPLAY_DIR.resolve("fixtures");
    private static final Path CASES_FILE = REPLAY_DIR.resolve("cases.json");
    private static final Path EXPECTED_FILE = REPLAY_DIR.resolve("expected.json");
    private static final double SCORE_TOLERANCE = 0.02;
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    record ReplayCase(String id, Point origin, Point destination) {
        Location originLocation() {
            return new Location(origin.name(), origin.lat(), origin.lng());
        }

        Location destinationLocation() {
            return new Location(destination.name(), destination.lat(), destination.lng());
        }

        @Override
        public String toString() {
            return id;
        }
    }

    record Point(String name, double lat, double lng) {}

    /** 순위 비교에 쓰는 경로 한 줄 — 순서가 곧 순위다. */
    record RankedRoute(String type, int totalMinutes, int totalCostWon, boolean recommended, double score) {
        static RankedRoute from(Route route) {
            return new RankedRoute(route.type().name(), route.totalMinutes(), route.totalCostWon(), route.recommended(),
                    Math.round(route.score() * 1000) / 1000.0);
        }
    }

    private final boolean isRecording = "true".equals(System.getenv("REPLAY_RECORD"));
    private final boolean isUpdatingExpected = "true".equals(System.getenv("REPLAY_UPDATE_EXPECTED"));
    private final Map<String, List<RankedRoute>> actualRankings = new TreeMap<>();

    private ReplayApiServer server;
    private ReplayEngine engine;
    private Map<String, List<RankedRoute>> expectedRankings = Map.of();

    @BeforeAll
    void setUp() throws IOException {
        Assumptions.assumeTrue(isRecording || Files.exists(EXPECTED_FILE),
                "녹화된 응답이 아직 없습니다 — REPLAY_RECORD=true 로 한 번 녹화해야 합니다 (클래스 주석 참고)");
        if (isRecording) {
            server = ReplayApiServer.recording(FIXTURE_DIR, new ReplayApiServer.Upstream(
                    requireEnv("ODSAY_API_KEY"), requireEnv("DDAREUNGI_API_KEY"), requireEnv("TMAP_APP_KEY")));
        } else {
            server = ReplayApiServer.replaying(FIXTURE_DIR);
        }
        engine = new ReplayEngine(server.baseUrl());
        if (Files.exists(EXPECTED_FILE)) {
            expectedRankings = JSON_MAPPER.readValue(EXPECTED_FILE.toFile(), new TypeReference<Map<String, List<RankedRoute>>>() {});
        }
    }

    @AfterEach
    void verifyNoMissingFixture() {
        assertThat(server.missingFixtures())
                .as("녹화되지 않은 조회가 있었습니다 — 외부 API 실패로 처리돼 결과가 달라집니다. 조회가 늘어난 것이 의도라면 다시 녹화하세요")
                .isEmpty();
        assertThat(server.rejectedResponses())
                .as("녹화 중 실제 API 가 실패로 답한 조회가 있습니다 (한도 초과·키 오류 등) — 이 결과로 기대값을 만들면 안 됩니다")
                .isEmpty();
    }

    @AfterAll
    void tearDown() {
        if (server == null) {
            return;
        }
        if (isRecording) {
            System.out.println("[replay] 새로 녹화한 응답 " + server.recordedFixtures().size() + "건");
        }
        if (isUpdatingExpected && !actualRankings.isEmpty() && server.rejectedResponses().isEmpty()) {
            JSON_MAPPER.writeValue(EXPECTED_FILE.toFile(), actualRankings);
        }
        server.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("녹화된 응답으로 탐색하면 경로 순위가 기대값과 같다")
    void search_recordedResponses_sameRanking(ReplayCase replayCase) {
        // when
        List<RankedRoute> actual = search(replayCase, AccessibilityContext.DEFAULT, WeatherContext.DEFAULT).stream()
                .map(RankedRoute::from)
                .toList();
        actualRankings.put(replayCase.id(), actual);

        // then
        assertThat(actual)
                .as("탐색 결과가 비었습니다 — 녹화된 응답이 실패 응답인지 확인하세요")
                .isNotEmpty();
        if (isUpdatingExpected) {
            return;
        }
        List<RankedRoute> expected = expectedRankings.get(replayCase.id());
        assertThat(expected)
                .as("기대값에 없는 케이스입니다 — REPLAY_UPDATE_EXPECTED=true 로 기대값을 만드세요")
                .isNotNull();
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields("score")
                .isEqualTo(expected);
        for (int rank = 0; rank < expected.size(); rank++) {
            assertThat(actual.get(rank).score())
                    .as("%d위 점수", rank + 1)
                    .isCloseTo(expected.get(rank).score(), within(SCORE_TOLERANCE));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("비가 오면 탑승 구간이 있는 경로의 점수는 맑을 때보다 높지 않고, 탑승 구간이 없는 경로는 낮아지지 않는다")
    void search_rain_ridingRoutesNotHigher(ReplayCase replayCase) {
        // given
        Map<String, Route> clearRoutes = search(replayCase, AccessibilityContext.DEFAULT, WeatherContext.DEFAULT).stream()
                .collect(Collectors.toMap(Route::routeId, Function.identity()));

        // when
        List<Route> rainRoutes = search(replayCase, AccessibilityContext.DEFAULT, WeatherContext.of(WeatherCondition.RAIN));

        // then
        assertThat(rainRoutes)
                .extracting(Route::routeId)
                .containsExactlyInAnyOrderElementsOf(clearRoutes.keySet());
        for (Route rainRoute : rainRoutes) {
            double clearScore = clearRoutes.get(rainRoute.routeId()).score();
            if (hasRidingLeg(rainRoute)) {
                assertThat(rainRoute.score())
                        .as("%s 비 올 때 점수", rainRoute.type())
                        .isLessThanOrEqualTo(clearScore);
            } else {
                assertThat(rainRoute.score())
                        .as("%s 비 올 때 점수", rainRoute.type())
                        .isGreaterThanOrEqualTo(clearScore);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("휠체어 조건이면 탑승 구간이 있는 경로는 결과에 없고, 남은 경로 중 하나만 추천된다")
    void search_wheelchair_noRidingRoutes(ReplayCase replayCase) {
        // when
        List<Route> routes = search(replayCase, AccessibilityContext.of(true, null), WeatherContext.DEFAULT);

        // then
        assertThat(routes)
                .noneMatch(RouteRankingReplayTest::hasRidingLeg);
        if (!routes.isEmpty()) {
            assertThat(routes)
                    .filteredOn(Route::recommended)
                    .hasSize(1);
        }
    }

    List<ReplayCase> cases() throws IOException {
        return JSON_MAPPER.readValue(Files.readString(CASES_FILE), new TypeReference<List<ReplayCase>>() {});
    }

    private List<Route> search(ReplayCase replayCase, AccessibilityContext accessibility, WeatherContext weather) {
        RouteSearchQuery query = new RouteSearchQuery(replayCase.originLocation(), replayCase.destinationLocation(), List.of(),
                SearchMode.OPTIMAL, RecommendationPreference.RELIABILITY, accessibility, weather);
        return engine.search(query);
    }

    private static boolean hasRidingLeg(Route route) {
        return route.legs().stream()
                .anyMatch(Leg::isRiding);
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("녹화하려면 환경변수 " + name + " 이 필요합니다");
        }
        return value;
    }
}
