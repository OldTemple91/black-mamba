package com.blackmamba.navigation.infra.ai;

import com.blackmamba.navigation.application.route.RouteIntent;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import com.blackmamba.navigation.domain.route.mobility.MobilityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LLM 출력 → {@link RouteIntent} 변환 규칙. 로컬 3B 모델은 JSON 을 코드블록으로 감싸거나 설명을 붙이고,
 * 스펙 밖 enum 값을 내기도 한다 — 그 느슨함을 어디까지 받아 주는지가 이 어댑터의 계약이다.
 */
class OllamaRouteIntentParserTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final OllamaRouteIntentParser parser = new OllamaRouteIntentParser(chatModel);

    @Test
    void JSON_한_줄_응답을_의도로_변환한다() {
        // given
        llmAnswers("""
                {"origin":"강남역","destination":"홍대입구","preference":"TIME_PRIORITY","mobility":["DDAREUNGI"],"wheelchairAccessible":true,"walkingSpeedKmh":3.0}
                """);

        // when
        RouteIntent intent = parser.parse("따릉이로 강남역에서 홍대입구까지 빠르게");

        // then
        assertThat(intent.origin()).isEqualTo("강남역");
        assertThat(intent.destination()).isEqualTo("홍대입구");
        assertThat(intent.preference()).isEqualTo(RecommendationPreference.TIME_PRIORITY);
        assertThat(intent.mobility()).containsExactly(MobilityType.DDAREUNGI);
        assertThat(intent.wheelchairAccessible()).isTrue();
        assertThat(intent.walkingSpeedKmh()).isEqualTo(3.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "```json\n{\"origin\":\"강남\",\"destination\":\"홍대\"}\n```",
            "요청을 분석했습니다. {\"origin\":\"강남\",\"destination\":\"홍대\"} 입니다.",
    })
    void 코드블록이나_설명_문장이_붙어도_JSON_만_꺼내_읽는다(String answer) {
        // given
        llmAnswers(answer);

        // when
        RouteIntent intent = parser.parse("강남에서 홍대까지");

        // then
        assertThat(intent.origin()).isEqualTo("강남");
        assertThat(intent.destination()).isEqualTo("홍대");
    }

    @Test
    void 스펙_밖_enum_값은_요청을_실패시키지_않고_기본값과_무시로_흡수한다() {
        // given
        llmAnswers("""
                {"origin":"강남","destination":"홍대","preference":"FAST","mobility":["bike","PERSONAL_EBIKE"]}
                """);

        // when
        RouteIntent intent = parser.parse("강남에서 홍대까지");

        // then
        assertThat(intent.preference()).isEqualTo(RecommendationPreference.RELIABILITY);
        assertThat(intent.mobility()).containsExactly(MobilityType.PERSONAL_EBIKE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "죄송합니다, 이해하지 못했습니다.",
            "{\"origin\":\"강남\"}",
            "{origin: 강남, destination 홍대}",
    })
    void JSON_이_없거나_출발지_도착지가_빠지면_모델_원문을_담지_않은_예외를_던진다(String answer) {
        // given
        llmAnswers(answer);

        // when & then
        assertThatThrownBy(() -> parser.parse("강남에서 홍대까지"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("자연어 요청을 이해하지 못했습니다.");
    }

    private void llmAnswers(String text) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }
}
