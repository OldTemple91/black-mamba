package com.blackmamba.navigation.infra.vector;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link QdrantRouteHistoryAdapter} 조건부 등록.
 *
 * <p>핵심 이슈: {@code @Component} + {@code @ConditionalOnBean(VectorStore.class)}
 * 조합은 컴포넌트 스캔 시점에 평가돼서 Spring AI 의 {@code VectorStoreAutoConfiguration}
 * 이 실행되기 전에 조건 체크가 일어나 불안정. 결과적으로 Qdrant 가 정상이어도
 * Adapter 가 등록되지 않는 경우가 발생.
 *
 * <p>해결: {@code @Configuration} + {@code @AutoConfigureAfter} 로 Spring AI
 * AutoConfig 사이클 뒤에 실행되도록 강제 → VectorStore 빈 상태가 확정된 후 조건 평가.
 *
 * <h3>동작</h3>
 * <ul>
 *   <li>{@code VectorStore} 빈 있음 → Adapter 등록 → RAG 활성</li>
 *   <li>{@code VectorStore} 빈 없음 ({@code SPRING_AUTOCONFIGURE_EXCLUDE}
 *       또는 빈 생성 실패) → Adapter 미등록 → 소비자 측 graceful 비활성</li>
 * </ul>
 */
@Configuration
@AutoConfigureAfter(name = "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration")
public class QdrantRouteHistoryConfig {

    @Bean
    @ConditionalOnBean(VectorStore.class)
    public QdrantRouteHistoryAdapter qdrantRouteHistoryAdapter(VectorStore vectorStore) {
        return new QdrantRouteHistoryAdapter(vectorStore);
    }
}
