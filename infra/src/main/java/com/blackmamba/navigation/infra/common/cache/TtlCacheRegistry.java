package com.blackmamba.navigation.infra.common.cache;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link TtlCache} 팩토리 + 단일 주기 청소기.
 * <p>
 * 클라이언트마다 {@code @Scheduled} 를 붙이던 것을 여기 하나로 모아,
 * 청소 주기 설정({@code navigation.cache.purge-interval-ms})이 한 곳에서만 읽힌다.
 */
@Component
public class TtlCacheRegistry {

    private static final Logger log = LoggerFactory.getLogger(TtlCacheRegistry.class);

    private final MeterRegistry meterRegistry;
    private final List<TtlCache<?, ?>> caches = new CopyOnWriteArrayList<>();

    public TtlCacheRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * @param name  메트릭 태그({@code navigation.cache.total{cache=name}})에 쓰일 캐시 이름
     * @param ttlMs 엔트리 수명 (0 이면 매 호출이 miss — 테스트용)
     */
    public <K, V> TtlCache<K, V> create(String name, long ttlMs) {
        TtlCache<K, V> cache = new TtlCache<>(name, ttlMs, meterRegistry);
        caches.add(cache);
        return cache;
    }

    /**
     * 만료 캐시 엔트리 주기 청소.
     * 조회 시 만료 검사만으로는 "재조회가 없는 키" 가 영구 잔류해 힙을 잠식한다 —
     * OD 쌍·세그먼트 키는 조합 수가 많아 장기 운영 시 OOM 위험.
     */
    @Scheduled(fixedDelayString = "${navigation.cache.purge-interval-ms:300000}")
    void purgeAll() {
        for (TtlCache<?, ?> cache : caches) {
            cache.purgeExpired();
        }
        log.debug("[TtlCacheRegistry] 만료 엔트리 청소 완료 — 캐시 {}개", caches.size());
    }
}
