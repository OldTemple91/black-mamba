package com.blackmamba.navigation.replay;

import com.blackmamba.navigation.application.route.RouteSearchQuery;
import com.blackmamba.navigation.application.route.SearchMode;
import com.blackmamba.navigation.application.route.accessibility.AccessibilityContext;
import com.blackmamba.navigation.application.route.weather.WeatherContext;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.route.evaluation.RouteHub;
import com.blackmamba.navigation.domain.weather.WeatherCondition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p>
 * ODsay 응답을 한 번도 녹화하지 않은 저장소에서는 건너뛴다. 녹화한 뒤로는 기대값 파일이 없으면 실패한다 — 조용히 건너뛰는 상태로 돌아가지 않게.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteRankingReplayTest {
    private static final Path REPLAY_DIR = Path.of("src/test/resources/replay");
    private static final Path FIXTURE_DIR = REPLAY_DIR.resolve("fixtures");
    private static final Path ODSAY_FIXTURE_DIR = FIXTURE_DIR.resolve("odsay");
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

    /** 순위 비교에 쓰는 경로 한 줄 — 순서가 곧 순위다. 거쳐 가는 허브까지 적어, 종류·시간·비용이 같아도 다른 허브를 고른 변화가 보인다. */
    record RankedRoute(String type, int totalMinutes, int totalCostWon, boolean recommended, List<String> hubs, double score) {
        static RankedRoute from(Route route) {
            List<String> hubs = route.selectedHubs().stream()
                    .map(RouteHub::name)
                    .toList();
            return new RankedRoute(route.type().name(), route.totalMinutes(), route.totalCostWon(), route.recommended(), hubs,
                    Math.round(route.score() * 1000) / 1000.0);
        }
    }

    private final boolean isRecording = "true".equals(System.getenv("REPLAY_RECORD"));
    private final boolean isUpdatingExpected = "true".equals(System.getenv("REPLAY_UPDATE_EXPECTED"));
    private final Map<String, List<RankedRoute>> actualRankings = new TreeMap<>();
    private final List<String> problems = new ArrayList<>();
    /** 테스트 하나가 한 탐색들에서 클라이언트가 추정값으로 대체한 횟수 */
    private final Map<String, Double> fallbackCounts = new TreeMap<>();

    private ReplayApiServer server;
    private Map<String, List<RankedRoute>> expectedRankings = Map.of();

    @BeforeAll
    void setUp() throws IOException {
        Assumptions.assumeTrue(isRecording || Files.isDirectory(ODSAY_FIXTURE_DIR),
                "ODsay 응답이 아직 녹화되지 않았습니다 — REPLAY_RECORD=true 로 한 번 녹화해야 합니다 (클래스 주석 참고)");
        if (isRecording) {
            server = ReplayApiServer.recording(FIXTURE_DIR, new ReplayApiServer.Upstream(
                    requireEnv("ODSAY_API_KEY"), requireEnv("DDAREUNGI_API_KEY"), requireEnv("TMAP_APP_KEY")));
        } else {
            server = ReplayApiServer.replaying(FIXTURE_DIR);
        }
        if (Files.exists(EXPECTED_FILE)) {
            expectedRankings = JSON_MAPPER.readValue(EXPECTED_FILE.toFile(), new TypeReference<Map<String, List<RankedRoute>>>() {});
        }
    }

    /** 실제 응답으로 만든 결과가 아니면 실패시킨다 — 빠진 파일·거절된 녹화·클라이언트 폴백은 순위를 조용히 바꾼다. */
    @AfterEach
    void verifyAnsweredFromRecordedResponses() {
        if (server == null) {
            return;
        }
        List<String> missing = List.copyOf(server.drainMissingFixtures());
        List<String> rejected = List.copyOf(server.drainRejectedResponses());
        Map<String, Double> fallbacks = new TreeMap<>(fallbackCounts);
        fallbackCounts.clear();
        if (!missing.isEmpty() || !rejected.isEmpty() || !fallbacks.isEmpty()) {
            problems.add("missing=" + missing + " rejected=" + rejected + " fallbacks=" + fallbacks);
        }
        assertThat(missing)
                .as("녹화되지 않은 조회가 있었습니다 — 외부 API 실패로 처리돼 결과가 달라집니다. 조회가 늘어난 것이 의도라면 다시 녹화하세요")
                .isEmpty();
        assertThat(rejected)
                .as("녹화 중 실제 API 가 실패로 답한 조회가 있습니다 (한도 초과·키 오류·연결 실패) — 이 결과로 기대값을 만들면 안 됩니다")
                .isEmpty();
        assertThat(fallbacks)
                .as("클라이언트가 응답을 쓰지 못하고 추정값으로 대체했습니다 — 녹화 파일을 읽지 못했거나 응답 처리 코드가 바뀌었습니다")
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
        // 기대값은 모든 케이스가 실제 응답으로 답했을 때만 쓴다
        if (isUpdatingExpected && problems.isEmpty() && actualRankings.size() == cases().size() * RecommendationPreference.values().length) {
            JSON_MAPPER.writeValue(EXPECTED_FILE.toFile(), actualRankings);
        }
        server.close();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("casesByPreference")
    void 녹화된_응답으로_탐색하면_경로_순위가_기대값과_같다(ReplayCase replayCase, RecommendationPreference preference) {
        // given
        String key = replayCase.id() + "/" + preference;

        // when
        List<RankedRoute> actual = search(replayCase, preference, AccessibilityContext.DEFAULT, WeatherContext.DEFAULT).stream()
                .map(RankedRoute::from)
                .toList();

        // then
        assertThat(actual)
                .as("탐색 결과가 비었습니다")
                .isNotEmpty();
        actualRankings.put(key, actual);
        if (isUpdatingExpected) {
            return;
        }
        List<RankedRoute> expected = expectedRankings.get(key);
        assertThat(expected)
                .as("기대값이 없습니다 — REPLAY_UPDATE_EXPECTED=true 로 기대값을 만드세요")
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
    void 비가_오면_어떤_경로도_점수가_오르지_않고_탑승_구간이_있는_경로는_내려간다(ReplayCase replayCase) {
        // given
        Map<String, Route> clearRoutes = search(replayCase, RecommendationPreference.RELIABILITY, AccessibilityContext.DEFAULT, WeatherContext.DEFAULT).stream()
                .collect(Collectors.toMap(Route::routeId, Function.identity()));

        // when
        List<Route> rainRoutes = search(replayCase, RecommendationPreference.RELIABILITY, AccessibilityContext.DEFAULT, WeatherContext.of(WeatherCondition.RAIN));

        // then — 날씨는 후보를 바꾸지 않고 점수만 깎는다(탑승 ×0.85, 긴 도보 ×0.90)
        assertThat(rainRoutes)
                .extracting(Route::routeId)
                .containsExactlyInAnyOrderElementsOf(clearRoutes.keySet());
        for (Route rainRoute : rainRoutes) {
            double clearScore = clearRoutes.get(rainRoute.routeId()).score();
            assertThat(rainRoute.score())
                    .as("%s 비 올 때 점수", rainRoute.type())
                    .isLessThanOrEqualTo(clearScore);
            if (hasRidingLeg(rainRoute) && clearScore > 0) {
                assertThat(rainRoute.score())
                        .as("%s(탑승 구간 있음) 비 올 때 점수", rainRoute.type())
                        .isLessThan(clearScore);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void 휠체어_조건이면_탑승_구간이_있는_경로는_결과에_없고_남은_경로_중_하나만_추천된다(ReplayCase replayCase) {
        // when
        List<Route> routes = search(replayCase, RecommendationPreference.RELIABILITY, AccessibilityContext.of(true, null), WeatherContext.DEFAULT);

        // then — 엘리베이터 없는 역만 지나는 구간이면 결과가 빌 수 있다
        assertThat(routes)
                .noneMatch(RouteRankingReplayTest::hasRidingLeg);
        if (!routes.isEmpty()) {
            assertThat(routes)
                    .filteredOn(Route::recommended)
                    .hasSize(1);
        }
    }

    List<ReplayCase> cases() {
        try {
            return JSON_MAPPER.readValue(Files.readString(CASES_FILE), new TypeReference<List<ReplayCase>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("케이스 파일을 읽지 못했습니다: " + CASES_FILE.toAbsolutePath(), e);
        }
    }

    Stream<Arguments> casesByPreference() {
        return cases().stream()
                .flatMap(replayCase -> Stream.of(RecommendationPreference.values())
                        .map(preference -> Arguments.of(replayCase, preference)));
    }

    /** 탐색마다 새 엔진 — 캐시가 케이스 사이에 이어지면 결과가 실행 순서에 묶인다({@link ReplayEngine}). */
    private List<Route> search(ReplayCase replayCase, RecommendationPreference preference, AccessibilityContext accessibility, WeatherContext weather) {
        RouteSearchQuery query = new RouteSearchQuery(replayCase.originLocation(), replayCase.destinationLocation(), List.of(),
                SearchMode.OPTIMAL, preference, accessibility, weather);
        ReplayEngine engine = new ReplayEngine(server.baseUrl());
        try {
            return engine.search(query);
        } finally {
            engine.fallbackCounts().forEach((api, count) -> fallbackCounts.merge(api, count, Double::sum));
        }
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
