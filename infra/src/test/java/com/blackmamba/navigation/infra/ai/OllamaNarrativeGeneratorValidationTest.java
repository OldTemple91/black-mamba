package com.blackmamba.navigation.infra.ai;

import com.blackmamba.navigation.application.route.port.ScoredRouteHistoryEntry;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Leg;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.RouteType;
import com.blackmamba.navigation.domain.route.TransitInfo;
import com.blackmamba.navigation.domain.route.history.RouteHistoryEntry;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import com.blackmamba.navigation.infra.ai.OllamaNarrativeGenerator.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 할루시네이션 가드 — "사용자에게 틀린 숫자를 보여주지 않는다" 는 narrative 의 유일한 안전장치.
 * 기준은 경로 총합이 아니라 프롬프트에 실제로 준 수치 묶음이다.
 */
class OllamaNarrativeGeneratorValidationTest {

    private final Location origin = new Location("강남역", 37.498, 127.0276);
    private final Location destination = new Location("홍대입구", 37.557, 126.924);

    // 도보 3분 + 지하철 36분 = 39분, 1,650원
    private final Route route = Route.of(List.of(
            new Leg(LegType.WALK, "WALK", 3, 250, origin, origin, null, null, null),
            new Leg(LegType.TRANSIT, "SUBWAY", 36, 10_500, origin, destination,
                    new TransitInfo("2호선", "#33A23D", 12, 1_650, List.of()), null, null)
    ), RouteType.TRANSIT_ONLY);

    private static final String PADDING = " 환승 없이 한 번에 이동할 수 있어 출퇴근 시간대에도 안정적인 선택입니다.";

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "총 39분이 걸리고 1,650원이 듭니다.                       | Ok",
            "약 44분 정도면 도착하고 1,650원입니다.                   | Ok",            // 5분 허용 오차
            "약 45분 정도면 도착하고 1,650원입니다.                   | Hallucination", // 6분 차이
            "지하철 구간만 36분이면 충분합니다.                       | Ok",            // 구간별 수치 허용
            "총 39분, 요금은 2,700원입니다.                           | Hallucination", // 1,000원 초과 차이
            "자가용보다 12분 빠르며 4,400원 절약됩니다.               | Ok",            // 자가용 비교 원문 수치 허용
            "자가용보다 25분 빠르며 4,400원 절약됩니다.               | Hallucination", // 비교 원문에 없는 수치
    })
    void 프롬프트에_준_수치_밖의_숫자만_할루시네이션으로_본다(String sentence, String expected) {
        // given — 자가용 비교 원문은 프롬프트에 포함된다
        String originalNarrative = "자가용보다 12분 빠르며 4,400원 절약, 탄소 998g 감소.";
        NarrativeFacts facts = NarrativeFacts.of(route, originalNarrative, List.of());

        // when
        Validation validation = OllamaNarrativeGenerator.validate(sentence.trim() + PADDING, facts);

        // then
        assertThat(validation.getClass().getSimpleName()).isEqualTo(expected);
    }

    @Test
    void 유사_이력이_없는데_건수_통계를_말하면_할루시네이션이다() {
        // given — 프롬프트 예시("3건 중 2건")를 베낀 전형적 소형 모델 출력
        NarrativeFacts facts = NarrativeFacts.of(route, null, List.of());
        String sentence = "총 39분, 1,650원. 비슷한 구간 3건 중 2건이 이 방식을 택했습니다." + PADDING;

        // when
        Validation validation = OllamaNarrativeGenerator.validate(sentence, facts);

        // then
        assertThat(validation).isInstanceOfSatisfying(Validation.Hallucination.class,
                hallucination -> assertThat(hallucination.detail()).contains("3건"));
    }

    @Test
    void 제공한_이력_건수_안의_통계는_허용된다() {
        // given — 이력 3건 제공
        List<ScoredRouteHistoryEntry> history = List.of(historyEntry("h1", 38), historyEntry("h2", 40), historyEntry("h3", 42));
        NarrativeFacts facts = NarrativeFacts.of(route, null, history);
        String sentence = "총 39분, 1,650원. 비슷한 구간 3건 중 2건이 이 방식을 택했습니다." + PADDING;

        // when & then
        assertThat(OllamaNarrativeGenerator.validate(sentence, facts)).isInstanceOf(Validation.Ok.class);
    }

    @Test
    void 따릉이_전용_0원_경로에서_자가용_절약액_언급은_정상이다() {
        // given — 과거 false positive: 총비용 0원이라 "4,400원 절약" 이 |4400-0|>1000 으로 거부됐었다
        Route bikeOnly = Route.of(List.of(
                new Leg(LegType.WALK, "WALK", 2, 150, origin, origin, null, null, null),
                new Leg(LegType.BIKE, "DDAREUNGI", 25, 6_000, origin, destination, null, null, null)
        ), RouteType.MOBILITY_ONLY);
        NarrativeFacts facts = NarrativeFacts.of(bikeOnly, "자가용보다 8분 더 걸리지만 4,400원 절약.", List.of());

        // when
        Validation validation = OllamaNarrativeGenerator.validate("27분이 걸리지만 자가용 대비 4,400원 절약됩니다." + PADDING, facts);

        // then
        assertThat(validation).isInstanceOf(Validation.Ok.class);
    }

    @Test
    void 짧은_출력은_할루시네이션이_아니라_길이_부족으로_분류된다() {
        // given
        NarrativeFacts facts = NarrativeFacts.of(route, null, List.of());

        // when & then
        assertThat(OllamaNarrativeGenerator.validate("39분 1,650원.", facts)).isInstanceOf(Validation.TooShort.class);
        assertThat(OllamaNarrativeGenerator.validate("", facts)).isInstanceOf(Validation.TooShort.class);
    }

    private ScoredRouteHistoryEntry historyEntry(String id, int minutes) {
        RouteHistoryEntry entry = new RouteHistoryEntry(id, "강남역에서 홍대입구까지 지하철 직행, " + minutes + "분 1,650원",
                origin, destination, List.of(MobilityType.DDAREUNGI), RouteType.TRANSIT_ONLY, minutes, 1_650, "RELIABILITY", Instant.now());
        return new ScoredRouteHistoryEntry(entry, 0.8);
    }
}
