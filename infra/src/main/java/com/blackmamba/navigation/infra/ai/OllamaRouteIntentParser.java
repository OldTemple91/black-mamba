package com.blackmamba.navigation.infra.ai;

import com.blackmamba.navigation.application.route.RouteIntent;
import com.blackmamba.navigation.application.route.port.RouteIntentParser;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import com.blackmamba.navigation.infra.ai.dto.OllamaIntentPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * LLM (Ollama)을 호출해 자연어 요청을 {@link RouteIntent}로 파싱하는 어댑터.
 * <p>
 * 원칙:
 * - 라우팅 핵심 알고리즘은 결정론적으로 유지 (LLM은 <b>의도 파싱에만</b>)
 * - JSON 출력 강제 + 저온도(0.2)로 재현성 확보
 * - 실패 시 예외를 던져 API 레이어에서 폴백 처리 가능하게
 * - 모델이 스펙 밖 enum 값을 내면 500 으로 흘리지 않고 기본값/무시로 흡수
 *
 * <h3>사용 흐름</h3>
 * <pre>
 * 사용자: "강남에서 홍대까지 노인도 쉬운 경로로"
 *   ↓
 * RouteIntent{origin=강남, destination=홍대, walkingSpeedKmh=3.0}
 *   ↓
 * Controller가 origin/destination을 Geocoding으로 좌표 변환 후 경로 탐색 호출
 * </pre>
 */
@Component
public class OllamaRouteIntentParser implements RouteIntentParser {

    private static final Logger log = LoggerFactory.getLogger(OllamaRouteIntentParser.class);

    /** LLM에 전달할 시스템 프롬프트. JSON 스키마 + Few-shot 예시로 3B 모델 지시 준수력 보강 */
    private static final String SYSTEM_PROMPT = """
            당신은 대중교통 경로 검색 서비스의 자연어 요청 파서입니다.
            사용자의 한국어 요청을 아래 JSON 스키마로 변환하세요.

            스키마:
            {
              "origin": "출발지 한국어 장소명 (필수)",
              "destination": "도착지 한국어 장소명 (필수)",
              "preference": "RELIABILITY" | "TIME_PRIORITY",
              "mobility": [] 또는 ["DDAREUNGI", "PERSONAL_EBIKE", "PERSONAL_KICKBOARD"] 중 일부,
              "wheelchairAccessible": true | false,
              "walkingSpeedKmh": 숫자 또는 null
            }

            ★★ 가장 중요한 규칙 ★★
            origin / destination 에는 "순수 지명만" 추출하세요. 수식어/형용사는 반드시 제거합니다.
            예) "노인도 쉬운 강남" → origin: "강남" (수식어 "노인도 쉬운"은 walkingSpeedKmh 에 반영)
            예) "휠체어로 갈 수 있는 강남" → origin: "강남" (수식어는 wheelchairAccessible 에 반영)
            예) "빠르게 가는 강남" → origin: "강남" (수식어는 preference 에 반영)

            매핑 규칙:
            - "빠르게", "빨리", "최단 시간" → preference: "TIME_PRIORITY"
            - "환승 적게", "안정적", "신뢰할 수 있는" → preference: "RELIABILITY"
            - "따릉이" → mobility: ["DDAREUNGI"]
            - "전기자전거" → mobility: ["PERSONAL_EBIKE"]
            - "킥보드" / "개인 킥보드" → mobility: ["PERSONAL_KICKBOARD"]
            - "휠체어", "엘리베이터" → wheelchairAccessible: true
            - "노인", "천천히", "편하게" → walkingSpeedKmh: 3.0
            - 명시 없으면 preference 기본값 "RELIABILITY", 나머지는 null / false

            ★★ Few-shot 예시 (반드시 이 패턴을 따르세요) ★★

            입력: "강남에서 홍대까지"
            출력: {"origin":"강남","destination":"홍대","preference":"RELIABILITY","mobility":[],"wheelchairAccessible":false,"walkingSpeedKmh":null}

            입력: "강남역에서 홍대입구까지 빠르게"
            출력: {"origin":"강남역","destination":"홍대입구","preference":"TIME_PRIORITY","mobility":[],"wheelchairAccessible":false,"walkingSpeedKmh":null}

            입력: "노인도 쉬운 강남에서 홍대 경로"
            출력: {"origin":"강남","destination":"홍대","preference":"RELIABILITY","mobility":[],"wheelchairAccessible":false,"walkingSpeedKmh":3.0}

            입력: "휠체어로 갈 수 있는 강남→홍대"
            출력: {"origin":"강남","destination":"홍대","preference":"RELIABILITY","mobility":[],"wheelchairAccessible":true,"walkingSpeedKmh":null}

            입력: "따릉이로 강남에서 홍대까지 천천히"
            출력: {"origin":"강남","destination":"홍대","preference":"RELIABILITY","mobility":["DDAREUNGI"],"wheelchairAccessible":false,"walkingSpeedKmh":3.0}

            입력: "환승 적게 서울역에서 강남역"
            출력: {"origin":"서울역","destination":"강남역","preference":"RELIABILITY","mobility":[],"wheelchairAccessible":false,"walkingSpeedKmh":null}

            반드시 JSON 한 줄만 출력하세요. 설명, 마크다운 코드블록(```) 금지.
            """;

    private static final String USER_PROMPT_TEMPLATE = "요청: \"{query}\"";

    private final ChatClient chatClient;
    /**
     * JSON → payload 변환은 Spring AI 의 변환기에 맡긴다 (어댑터가 Jackson ObjectMapper 를 직접 들지 않는다).
     * {@code call().entity(..)} 를 쓰지 않는 이유: entity 는 프롬프트에 스키마 지시문을 덧붙여 few-shot 으로 맞춘
     * 프롬프트를 바꾸고, 3B 모델이 JSON 앞뒤에 붙이는 설명 문장을 견디지 못한다 — 그 부분은 {@link #extractJson} 이 맡는다.
     */
    private final BeanOutputConverter<OllamaIntentPayload> payloadConverter = new BeanOutputConverter<>(OllamaIntentPayload.class);

    public OllamaRouteIntentParser(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
    }

    @Override
    public RouteIntent parse(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("자연어 요청이 비어 있습니다");
        }

        Prompt prompt = new PromptTemplate(USER_PROMPT_TEMPLATE).create(Map.of("query", query));

        String rawResponse = chatClient.prompt(prompt).call().content();
        log.debug("[NLP] LLM raw response: {}", rawResponse);

        if (rawResponse == null || rawResponse.isBlank()) {
            // 모델이 아무것도 돌려주지 않은 것(null, 빈 문자열)은 사용자 입력 문제가 아니다 — 파싱 실패(400)가 아니라 모델 불가(503)로 올린다
            throw new NoSuchElementException("LLM 응답이 비어 있음");
        }
        String jsonBody = extractJson(rawResponse);

        OllamaIntentPayload payload;
        try {
            payload = payloadConverter.convert(jsonBody);
        } catch (RuntimeException e) {
            log.error("[NLP] 파싱 실패. raw={}, error={}", rawResponse, e.getMessage());
            throw new IllegalStateException("자연어 요청을 이해하지 못했습니다. 더 명확히 입력해 주세요.", e);
        }
        if (payload == null || !payload.hasOriginAndDestination()) {
            log.error("[NLP] 출발지/도착지 파싱 실패: {}", payload);
            throw new IllegalStateException("자연어 요청을 이해하지 못했습니다. 출발지와 도착지를 명확히 입력해 주세요.");
        }

        RouteIntent intent = toIntent(payload);
        log.info("[NLP] 파싱 성공: query=\"{}\" → {}", query, intent);
        return intent;
    }

    /**
     * 로컬 LLM은 종종 ```json 코드블록으로 감싸거나 설명 텍스트를 붙인다.
     * 가장 바깥 { ... } 블록만 추출.
     */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            // 모델 원문은 로그에만 — 예외 메시지는 400 본문으로 사용자에게 나간다
            log.warn("[NLP] LLM 응답에 JSON 없음. raw={}", raw);
            throw new IllegalStateException("자연어 요청을 이해하지 못했습니다. 더 명확히 입력해 주세요.");
        }
        return raw.substring(start, end + 1);
    }

    private RouteIntent toIntent(OllamaIntentPayload payload) {
        return new RouteIntent(
                payload.origin().trim(),
                payload.destination().trim(),
                parsePreference(payload.preference()),
                parseMobilityTypes(payload.mobility()),
                payload.wheelchairAccessible(),
                payload.walkingSpeedKmh()
        );
    }

    /** 스펙 밖 preference 는 기본값(RELIABILITY) 으로 폴백. */
    private static RecommendationPreference parsePreference(String raw) {
        if (raw == null || raw.isBlank()) return RecommendationPreference.RELIABILITY;
        try {
            return RecommendationPreference.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            log.warn("[NLP] 알 수 없는 preference \"{}\" → RELIABILITY 폴백", raw);
            return RecommendationPreference.RELIABILITY;
        }
    }

    /** 스펙 밖 mobility 는 해당 값만 건너뛴다 (전체 요청 실패 방지). */
    private static List<MobilityType> parseMobilityTypes(List<String> raw) {
        if (raw == null) return List.of();
        List<MobilityType> types = new ArrayList<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) continue;
            try {
                types.add(MobilityType.valueOf(value.trim()));
            } catch (IllegalArgumentException e) {
                log.warn("[NLP] 알 수 없는 mobility \"{}\" 무시", value);
            }
        }
        return types;
    }
}
