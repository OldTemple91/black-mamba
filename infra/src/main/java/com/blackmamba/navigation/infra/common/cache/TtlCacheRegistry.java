package com.blackmamba.navigation.infra.common.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@link TtlCache} 팩토리 — 캐시 이름을 메트릭 태그로 묶는다.
 * 만료 엔트리 청소는 Caffeine 이 하므로 주기 작업이 없다.
 */
@Component
public class TtlCacheRegistry {

    private final MeterRegistry meterRegistry;

    public TtlCacheRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * @param name  메트릭 태그({@code navigation.cache.total{cache=name}})에 쓰일 캐시 이름
     * @param ttlMs 엔트리 수명 (0 이면 매 호출이 miss — 테스트용)
     */
    public <K, V> TtlCache<K, V> create(String name, long ttlMs) {
        return new TtlCache<>(name, ttlMs, meterRegistry);
    }
}
