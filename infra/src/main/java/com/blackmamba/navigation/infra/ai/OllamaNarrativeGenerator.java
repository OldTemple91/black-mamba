package com.blackmamba.navigation.infra.ai;

import com.blackmamba.navigation.application.route.port.NarrativeGenerator;
import com.blackmamba.navigation.application.route.port.ScoredRouteHistoryEntry;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.Route;
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

    public OllamaNarrativeGenerator(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
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
            // 할루시네이션 감지: 출력 숫자가 실제 경로 수치와 크게 다르면 폴백
            String validated = validateAgainstRoute(cleaned, route);
            if (validated.isEmpty()) {
                return "";  // 폴백 신호: 호출자가 원본 narrative 유지
            }
            log.debug("[RAG4] narrative 생성: \"{}\"", validated);
            return validated;
        } catch (Exception e) {
            log.warn("[RAG4] narrative 생성 실패 — 폴백(원본 유지). err={}", e.getMessage());
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

    /**
     * LLM 출력에 포함된 숫자가 실제 경로 수치와 큰 차이가 있으면 빈 문자열 반환(폴백).
     * <p>
     * 예: 경로가 39분인데 LLM 이 "약 45분" 이라 썼다면 5분 초과 차이 → 할루시네이션으로 간주.
     * 길이가 50자 미만이면 정보성 부족으로 동일 폴백.
     */
    static String validateAgainstRoute(String narrative, Route route) {
        if (narrative == null || narrative.isBlank()) return "";
        if (narrative.length() < MIN_NARRATIVE_LENGTH) {
            log.debug("[RAG4] narrative 너무 짧음({}자) → 폴백", narrative.length());
            return "";
        }

        int expectedMinutes = route.totalMinutes();
        int expectedCost = route.totalCostWon();

        // 1) 분 검증
        Matcher mm = MINUTES_PATTERN.matcher(narrative);
        while (mm.find()) {
            try {
                int minutes = Integer.parseInt(mm.group(1));
                if (Math.abs(minutes - expectedMinutes) > ALLOWED_MINUTES_DELTA) {
                    log.warn("[RAG4] 할루시네이션 감지 (분): 출력 {}분 vs 실제 {}분 → 폴백",
                            minutes, expectedMinutes);
                    return "";
                }
            } catch (NumberFormatException ignored) {}
        }

        // 2) 비용(원) 검증
        Matcher wm = WON_PATTERN.matcher(narrative);
        while (wm.find()) {
            try {
                int won = Integer.parseInt(wm.group(1).replace(",", ""));
                if (Math.abs(won - expectedCost) > ALLOWED_COST_DELTA) {
                    log.warn("[RAG4] 할루시네이션 감지 (원): 출력 {}원 vs 실제 {}원 → 폴백",
                            won, expectedCost);
                    return "";
                }
            } catch (NumberFormatException ignored) {}
        }

        return narrative;
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
                "- %s → %s%n- %d분, %,d원%n- 모드: %s%n- 선호도: %s%n",
                safe(origin.name()),
                safe(destination.name()),
                route.totalMinutes(),
                route.totalCostWon(),
                mobilitySummary(route),
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
