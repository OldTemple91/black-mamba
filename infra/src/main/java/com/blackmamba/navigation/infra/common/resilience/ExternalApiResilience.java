package com.blackmamba.navigation.infra.common.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 외부 API 호출에 재시도·회로 차단을 거는 단일 지점.
 * <p>
 * 클라이언트 6곳이 각자 {@code CircuitBreakerRegistry}·{@code RetryRegistry} 를 주입받아 필드 2개와
 * {@code transformDeferred} 2줄을 복사하고 있었다. 순서(Retry 안쪽, CircuitBreaker 바깥쪽)가 설계의 일부인데
 * 복사본마다 따로 지켜야 했다 — 회로는 "재시도까지 다 한 결과" 를 1건으로 집계해야 한다.
 * 인스턴스 이름은 application.yml 의 {@code resilience4j.*.instances} 키와 같다.
 */
@Component
public class ExternalApiResilience {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    public ExternalApiResilience(CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
    }

    public Guard forApi(String name) {
        return new Guard(circuitBreakerRegistry.circuitBreaker(name), retryRegistry.retry(name));
    }

    /** 한 외부 API 의 재시도·회로 차단 묶음. {@code mono.as(guard::protect)} 로 쓴다. */
    public static final class Guard {

        private final CircuitBreaker circuitBreaker;
        private final Retry retry;

        private Guard(CircuitBreaker circuitBreaker, Retry retry) {
            this.circuitBreaker = circuitBreaker;
            this.retry = retry;
        }

        /** 재시도(안쪽) 후 회로 차단(바깥쪽) — 호출 1건의 기본 보호. */
        public <T> Mono<T> protect(Mono<T> call) {
            return circuitBreak(retry(call));
        }

        /** 재시도만 — 여러 호출을 묶어 회로에는 1건으로 집계할 때 개별 호출에 건다. */
        public <T> Mono<T> retry(Mono<T> call) {
            return call.transformDeferred(RetryOperator.of(retry));
        }

        /** 회로 차단만 — {@link #retry} 를 건 호출들의 묶음 바깥에 건다. */
        public <T> Mono<T> circuitBreak(Mono<T> call) {
            return call.transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
        }
    }
}
