package com.blackmamba.navigation.application.route.port;

import com.blackmamba.navigation.application.route.RouteIntent;

/**
 * 자연어 경로 요청 → {@link RouteIntent} 파서 (RAG Phase 1).
 *
 * <h3>Hexagonal Port 원칙</h3>
 * application/api 는 어떤 모델·프롬프트가 쓰이는지 모른다.
 * infra 어댑터({@code OllamaRouteIntentParser})가 Spring AI ChatClient 를 래핑한다 —
 * {@link NarrativeGenerator} 와 같은 구조.
 *
 * <h3>실패 정책</h3>
 * <ul>
 *   <li>입력이 비어 있으면 {@link IllegalArgumentException}</li>
 *   <li>모델 응답을 해석하지 못하면 {@link IllegalStateException} (호출자가 400 으로 매핑)</li>
 * </ul>
 */
public interface RouteIntentParser {

    RouteIntent parse(String query);
}
