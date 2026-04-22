package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Route;

import java.util.List;

/**
 * LLM 기반 narrative 생성기 (RAG Phase 4).
 * <p>
 * 템플릿 기반 {@code carComparison.narrative} 를 유사 이력 + LLM 조합으로
 * 자연스러운 설명으로 업그레이드한다.
 *
 * <h3>Hexagonal Port 원칙</h3>
 * application/domain 은 LLM/모델/프롬프트 존재를 모른다.
 * infra 어댑터({@code OllamaNarrativeGenerator}) 가 Spring AI ChatClient 를 래핑.
 *
 * <h3>장애 정책</h3>
 * 구현체는 LLM 장애 시 <b>빈 문자열</b> 을 반환해야 한다 (null 금지).
 * 호출자({@code RouteNarrativeEnhancer})가 빈 문자열을 폴백 신호로 해석해
 * 원본 템플릿 narrative 를 유지한다.
 */
public interface NarrativeGenerator {

    /**
     * 경로 + 유사 이력을 기반으로 자연어 narrative 생성.
     *
     * @param route             대상 경로 (보통 recommended=true 인 것)
     * @param origin            출발지
     * @param destination       도착지
     * @param preference        검색 선호도 (RELIABILITY / TIME_PRIORITY)
     * @param similarHistory    Qdrant 에서 조회한 유사 과거 경로 (top 3 권장)
     * @param originalNarrative 원본 carComparison.narrative (폴백 + 프롬프트 맥락)
     * @return LLM 이 생성한 문장. 실패 시 빈 문자열.
     */
    String generate(Route route,
                    Location origin,
                    Location destination,
                    String preference,
                    List<ScoredRouteHistoryEntry> similarHistory,
                    String originalNarrative);
}
