package com.blackmamba.navigation.infra.vector;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * {@link QdrantRouteHistoryAdapter} 조건부 등록 — <b>자동 구성</b>으로 등록한다.
 *
 * <p>{@code @ConditionalOnBean(VectorStore.class)} 는 "그 시점까지 정의된 빈" 만 본다. 이 클래스가
 * 컴포넌트 스캔으로 잡히는 일반 {@code @Configuration} 이면 Spring AI 의 자동 구성보다 먼저 평가되어
 * VectorStore 가 정상이어도 조건이 항상 거짓이 된다 — 실제로 Qdrant 가 떠 있어도 어댑터가 등록되지 않아
 * 경로 이력 저장·유사 검색이 조용히 꺼져 있었다({@code --debug} 조건 리포트로 확인).
 * 일반 설정 클래스의 {@code @AutoConfigureAfter} 는 아무 효과가 없다.
 *
 * <p>그래서 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 에
 * 등록해 자동 구성 단계에서, Qdrant 자동 구성 뒤에 평가되게 한다. 등록된 클래스는 Boot 가 컴포넌트 스캔에서 제외한다.
 *
 * <h3>동작</h3>
 * <ul>
 *   <li>{@code VectorStore} 빈 있음 → Adapter 등록 → RAG 활성</li>
 *   <li>{@code VectorStore} 빈 없음 ({@code SPRING_AUTOCONFIGURE_EXCLUDE} 등) → Adapter 미등록 → 소비자 측 graceful 비활성</li>
 * </ul>
 */
@AutoConfiguration(afterName = "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration")
public class QdrantRouteHistoryConfig {

    @Bean
    @ConditionalOnBean(VectorStore.class)
    public QdrantRouteHistoryAdapter qdrantRouteHistoryAdapter(VectorStore vectorStore) {
        return new QdrantRouteHistoryAdapter(vectorStore);
    }
}
