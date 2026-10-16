package com.blackmamba.navigation.infra.ai;

import com.blackmamba.navigation.application.route.port.NarrativeGenerator;
import com.blackmamba.navigation.application.route.port.ScoredRouteHistoryEntry;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Ollama ChatClient 를 래핑해 경로 narrative 를 생성하는 어댑터.
 * <p>
 * 프롬프트 설계: 경로 스펙 + 자가용 비교 원본 + 유사 과거 이력 3건 → LLM → 2~3문장.
 *
 * <h3>장애 격리</h3>
 * LLM 실패 / timeout 시 빈 문자열 반환. 본 경로 응답이 끊기지 않도록 호출자가 폴백.
 *
 * <h3>성능</h3>
 * ChatClient.call() 은 동기 블로킹. 호출자({@code RouteNarrativeEnhancer}) 가
 * {@code Schedulers.boundedElastic()} 에 offload 하는 것을 전제로 설계.
 */
@Component
public class OllamaNarrativeGenerator implements NarrativeGenerator {

    private static final Logger log = LoggerFactory.getLogger(OllamaNarrativeGenerator.class);

    private static final String SYSTEM_PROMPT = """
            당신은 대중교통 경로 추천 설명을 작성하는 한국어 카피라이터입니다.
            주어진 경로 정보와 과거 유사 이력을 바탕으로, 사용자가 이 경로를 선택할
            **합리적 근거** 를 한국어 2~3 문장으로 자연스럽게 설명하세요.

            작성 규칙:
            - 2~3 문장, 120자 이내
            - 과거 이력이 있다면 통계를 근거로 사용 (예: "비슷한 구간 3건 중 2건이 이 방식")
            - 자가용 비교 정보가 있다면 유지 (시간·비용·탄소)
            - 추측/과장 금지, 제공된 수치만 사용
            - 마크다운, 코드블록, 이모지 금지
            - "이 경로는", "추천합니다" 같은 상투적 표현 최소화

            절대 규칙:
            - 설명 외 다른 텍스트 출력 금지 (타이틀, 번호, 인용부호 금지)
            - 단일 문단 한 덩어리로 출력
            """;

    private final ChatClient chatClient;
    private final Counter successCounter;
    private final Counter errorCounter;
    private final Counter shortCounter;
    private final Counter hallucinationCounter;

    public OllamaNarrativeGenerator(ChatModel chatModel, MeterRegistry meterRegistry) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.successCounter = Counter.builder("navigation.rag.narrative.generated")
                .description("LLM narrative 생성 성공 건수")
                .register(meterRegistry);
        // 같은 metric 이름은 description 도 동일해야 함 (불일치 시 Micrometer 경고) — reason 태그로만 구분.
        // 운영자가 "모델이 자주 틀린다" 와 "모델이 짧게/빈 답을 한다" 를 구분할 수 있어야 하므로 사유를 나눈다.
        this.errorCounter = fallbackCounter(meterRegistry, "error");
        this.shortCounter = fallbackCounter(meterRegistry, "short");
        this.hallucinationCounter = fallbackCounter(meterRegistry, "hallucination");
    }

    static Counter fallbackCounter(MeterRegistry meterRegistry, String reason) {
        return Counter.builder("navigation.rag.narrative.fallback")
                .description("LLM narrative 폴백(원본 유지) 건수")
                .tag("reason", reason)
                .register(meterRegistry);
    }

    @Override
    public String generate(Route route,
                           Location origin,
                           Location destination,
                           String preference,
                           java.util.List<ScoredRouteHistoryEntry> similarHistory,
                           String originalNarrative) {
        if (route == null || origin == null || destination == null) return "";

        try {
            String userPrompt = buildUserPrompt(route, origin, destination, preference,
                    similarHistory, originalNarrative);
            String raw = chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .content();
            String cleaned = cleanOutput(raw);
            if (cleaned.isBlank()) {
                errorCounter.increment();
                log.warn("[RAG4] LLM 빈 응답 → 폴백(원본 유지). routeId={}", route.routeId());
                return "";
            }
            // 할루시네이션 감지: 출력 숫자가 프롬프트에 준 수치 밖이면 폴백
            NarrativeFacts facts = NarrativeFacts.of(route, originalNarrative, similarHistory);
            return switch (validate(cleaned, facts)) {
                case Validation.TooShort() -> {
                    shortCounter.increment();
                    log.debug("[RAG4] narrative 너무 짧음 → 폴백. routeId={} len={}", route.routeId(), cleaned.length());
                    yield "";
                }
                case Validation.Hallucination(String detail) -> {
                    hallucinationCounter.increment();
                    log.warn("[RAG4] 할루시네이션 감지 → 폴백. routeId={} 사유={} 출력=\"{}\"",
                            route.routeId(), detail, abbreviate(cleaned));
                    yield "";
                }
                case Validation.Ok() -> {
                    successCounter.increment();
                    log.debug("[RAG4] narrative 생성: \"{}\"", cleaned);
                    yield cleaned;
                }
            };
        } catch (Exception e) {
            log.warn("[RAG4] narrative 생성 실패 — 폴백(원본 유지). routeId={} err={}", route.routeId(), e.getMessage());
            errorCounter.increment();
            return "";
        }
    }

    // ─── 할루시네이션 감지 ──────────────────────────

    /** 분 수치 차이 허용 — 5분 초과 시 할루시네이션으로 간주 */
    private static final int ALLOWED_MINUTES_DELTA = 5;
    /** 원 수치 차이 허용 — 1,000원 초과 시 할루시네이션으로 간주 */
    private static final int ALLOWED_COST_DELTA = 1_000;
    /** 품질 하한 — 50자 미만은 정보성 부족으로 폴백 */
    private static final int MIN_NARRATIVE_LENGTH = 50;

    private static final Pattern MINUTES_PATTERN = Pattern.compile("(\\d+)\\s*분");
    // "1,650원" / "1650원" 둘 다 매칭
    private static final Pattern WON_PATTERN = Pattern.compile("([\\d,]+)\\s*원");
    // "3건 중 2건" — 유사 이력 통계 주장
    private static final Pattern COUNT_PATTERN = Pattern.compile("(\\d+)\\s*건");

    /** 검증 결과 — 사유 문자열은 할루시네이션일 때만 존재하므로 enum+필드 대신 타입으로 구분한다. */
    sealed interface Validation {
        record Ok() implements Validation {}

        record TooShort() implements Validation {}

        record Hallucination(String detail) implements Validation {}
    }

    /**
     * LLM 출력의 수치가 <b>프롬프트에 준 수치</b>({@link NarrativeFacts}) 밖이면 할루시네이션.
     * <ul>
     *   <li>분/원: 제공한 수치 중 하나와 허용 오차(5분 / 1,000원) 안이면 통과 — 총합·구간·자가용 차이·이력 수치 모두 인정</li>
     *   <li>"N건": 제공한 이력 건수를 넘는 통계 주장, 이력이 없는데 건수를 말하면 할루시네이션
     *       (프롬프트의 예시 "3건 중 2건" 을 그대로 베끼는 소형 모델의 전형적 실패)</li>
     *   <li>길이 50자 미만은 정보성 부족 — 별도 사유(TooShort)</li>
     * </ul>
     */
    static Validation validate(String narrative, NarrativeFacts facts) {
        if (narrative == null || narrative.isBlank() || narrative.length() < MIN_NARRATIVE_LENGTH) {
            return new Validation.TooShort();
        }

        Matcher mm = MINUTES_PATTERN.matcher(narrative);
        while (mm.find()) {
            int minutes = Integer.parseInt(mm.group(1));
            if (!isNear(minutes, facts.allowedMinutes(), ALLOWED_MINUTES_DELTA)) {
                return new Validation.Hallucination("출력 " + minutes + "분 — 제공 수치 " + facts.allowedMinutes());
            }
        }

        Matcher wm = WON_PATTERN.matcher(narrative);
        while (wm.find()) {
            int won;
            try {
                won = Integer.parseInt(wm.group(1).replace(",", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            if (!isNear(won, facts.allowedWon(), ALLOWED_COST_DELTA)) {
                return new Validation.Hallucination("출력 " + won + "원 — 제공 수치 " + facts.allowedWon());
            }
        }

        Matcher cm = COUNT_PATTERN.matcher(narrative);
        while (cm.find()) {
            int count = Integer.parseInt(cm.group(1));
            if (count > facts.historyCount()) {
                return new Validation.Hallucination("출력 " + count + "건 — 제공 이력 " + facts.historyCount() + "건");
            }
        }

        return new Validation.Ok();
    }

    private static boolean isNear(int value, java.util.Set<Integer> allowed, int tolerance) {
        for (int candidate : allowed) {
            if (Math.abs(value - candidate) <= tolerance) return true;
        }
        return false;
    }

    private static String abbreviate(String text) {
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }

    // ─── 프롬프트 작성 ─────────────────────────────

    private String buildUserPrompt(Route route,
                                   Location origin,
                                   Location destination,
                                   String preference,
                                   java.util.List<ScoredRouteHistoryEntry> history,
                                   String originalNarrative) {
        StringBuilder sb = new StringBuilder();

        sb.append("## 현재 경로\n");
        sb.append(String.format(Locale.ROOT,
                "- %s → %s%n- %d분, %,d원%n- 모드: %s%n- 구간별 소요: %s%n- 선호도: %s%n",
                safe(origin.name()),
                safe(destination.name()),
                route.totalMinutes(),
                route.totalCostWon(),
                mobilitySummary(route),
                legSummary(route),
                preference == null ? "RELIABILITY" : preference));

        if (originalNarrative != null && !originalNarrative.isBlank()) {
            sb.append(String.format("- 자가용 비교: %s%n", originalNarrative));
        }

        sb.append("\n## 비슷한 과거 이력\n");
        if (history == null || history.isEmpty()) {
            sb.append("- (유사 이력 없음 — 통계 언급 생략)\n");
        } else {
            int idx = 1;
            for (ScoredRouteHistoryEntry se : history) {
                sb.append(String.format(Locale.ROOT, "- #%d (유사도 %.2f) %s%n",
                        idx++, se.similarityScore(), se.entry().description()));
            }
        }

        sb.append("\n위 정보를 근거로 2~3 문장 한국어 설명 작성:");
        return sb.toString();
    }

    /** 구간별 소요시간 — 모델이 구간 시간을 언급할 때 참조할 수 있도록 제공 (검증 화이트리스트와 동일 출처). */
    private static String legSummary(Route route) {
        if (route.legs() == null || route.legs().isEmpty()) return "없음";
        return route.legs().stream()
                .map(leg -> legLabel(leg) + " " + leg.durationMinutes() + "분")
                .collect(Collectors.joining(", "));
    }

    private static String legLabel(com.blackmamba.navigation.domain.route.Leg leg) {
        if (leg.mobilityInfo() != null && leg.mobilityInfo().mobilityType() != null) {
            return switch (leg.mobilityInfo().mobilityType()) {
                case DDAREUNGI -> "따릉이";
                case PERSONAL_EBIKE -> "전기자전거";
                case PERSONAL_KICKBOARD -> "킥보드";
                case KICKBOARD_SHARED -> "공유 킥보드";
            };
        }
        return switch (leg.type()) {
            case TRANSIT -> "대중교통";
            case WALK -> "도보";
            default -> leg.type().name();
        };
    }

    private static String mobilitySummary(Route route) {
        if (route.legs() == null || route.legs().isEmpty()) return "도보";
        return route.legs().stream()
                .map(leg -> {
                    if (leg.mobilityInfo() != null && leg.mobilityInfo().mobilityType() != null) {
                        MobilityType m = leg.mobilityInfo().mobilityType();
                        return switch (m) {
                            case DDAREUNGI -> "따릉이";
                            case PERSONAL_EBIKE -> "전기자전거";
                            case PERSONAL_KICKBOARD -> "킥보드";
                            default -> m.name();
                        };
                    }
                    return switch (leg.type()) {
                        case TRANSIT -> "대중교통";
                        case WALK -> "도보";
                        default -> "";
                    };
                })
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.joining("+"));
    }

    private static String cleanOutput(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // 가끔 붙는 마크다운/인용 제거
        if (s.startsWith("```")) {
            int end = s.lastIndexOf("```");
            if (end > 3) s = s.substring(s.indexOf('\n') + 1, end).trim();
        }
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() > 2) {
            s = s.substring(1, s.length() - 1).trim();
        }
        // 개행 → 공백, 연속 공백 축약
        s = s.replaceAll("\\s+", " ").trim();
        // 너무 길면 잘라냄 (300자 안전장치)
        if (s.length() > 300) s = s.substring(0, 300).trim();
        return s;
    }

    private static String safe(String v) {
        return (v == null || v.isBlank()) ? "목적지" : v;
    }
}
