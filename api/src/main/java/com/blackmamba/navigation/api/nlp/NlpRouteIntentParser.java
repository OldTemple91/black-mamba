package com.blackmamba.navigation.api.nlp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.stereotype.Component;

/**
 * LLM (Ollama)을 호출해 자연어 요청을 {@link RouteSearchIntent}로 파싱.
 * <p>
 * 원칙:
 * - 라우팅 핵심 알고리즘은 결정론적으로 유지 (LLM은 <b>의도 파싱에만</b>)
 * - JSON 출력 강제 + 저온도(0.2)로 재현성 확보
 * - 실패 시 예외를 던져 API 레이어에서 폴백 처리 가능하게
 *
 * <h3>사용 흐름</h3>
 * <pre>
 * 사용자: "강남에서 홍대까지 노인도 쉬운 경로로"
 *   ↓
 * RouteSearchIntent{origin=강남, destination=홍대, walkingSpeedKmh=3.0}
 *   ↓
 * Controller가 origin/destination을 Geocoding으로 좌표 변환 후 /api/routes 호출
 * </pre>
 */
@Component
public class NlpRouteIntentParser {

    private static final Logger log = LoggerFactory.getLogger(NlpRouteIntentParser.class);

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
    private final ObjectMapper objectMapper;

    public NlpRouteIntentParser(OllamaChatModel ollamaChatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(ollamaChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * 자연어 요청을 파싱하여 구조화된 Intent 반환.
     * @throws IllegalStateException 파싱 실패 시
     */
    public RouteSearchIntent parse(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            throw new IllegalArgumentException("자연어 요청이 비어 있습니다");
        }

        Prompt prompt = new PromptTemplate(USER_PROMPT_TEMPLATE)
                .create(java.util.Map.of("query", userQuery));

        String rawResponse = chatClient.prompt(prompt).call().content();
        log.debug("[NLP] LLM raw response: {}", rawResponse);

        String jsonBody = extractJson(rawResponse);

        try {
            RouteSearchIntent intent = objectMapper.readValue(jsonBody, RouteSearchIntent.class);
            log.info("[NLP] 파싱 성공: query=\"{}\" → {}", userQuery, intent);
            validate(intent);
            return intent;
        } catch (Exception e) {
            log.error("[NLP] 파싱 실패. raw={}, error={}", rawResponse, e.getMessage());
            throw new IllegalStateException("자연어 요청을 이해하지 못했습니다. 더 명확히 입력해 주세요.", e);
        }
    }

    /**
     * 로컬 LLM은 종종 ```json 코드블록으로 감싸거나 설명 텍스트를 붙인다.
     * 가장 바깥 { ... } 블록만 추출.
     */
    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("LLM 응답에서 JSON을 찾을 수 없습니다: " + raw);
        }
        return raw.substring(start, end + 1);
    }

    private void validate(RouteSearchIntent intent) {
        if (intent.origin() == null || intent.origin().isBlank()
                || intent.destination() == null || intent.destination().isBlank()) {
            throw new IllegalStateException("출발지/도착지 파싱 실패: " + intent);
        }
    }
}
