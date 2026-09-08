package com.blackmamba.navigation.infra.common.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 키별 TTL 을 가진 {@link Mono} 결과 캐시.
 * <p>
 * 외부 API 클라이언트 3곳(ODsay, Tmap, 가용성 어댑터)이 각자 들고 있던
 * {@code ConcurrentHashMap + CacheEntry(expiresAtMs) + compute() + hit/miss 카운터 + @Scheduled purge}
 * 를 한곳으로 모았다.
 *
 * <h3>의미</h3>
 * <ul>
 *   <li>{@link #get} 은 같은 키의 동시 호출을 하나의 로더로 합친다 ({@code compute} 가 bin 락)</li>
 *   <li>저장되는 Mono 는 {@code .cache()} 되어 TTL 동안 결과를 재생한다 —
 *       <b>오류 신호도 재생되므로</b> 로더는 반드시 {@code onErrorResume} 으로 끝나야 한다</li>
 *   <li>로더 실행은 {@code compute} 바깥(구독 시점)에서 일어난다 — 락 안에서 I/O 없음</li>
 *   <li>만료 엔트리는 조회 시 교체되지만, 재조회 없는 키는 {@link #purgeExpired} 로 청소해야 힙에 남지 않는다</li>
 * </ul>
 */
public final class TtlCache<K, V> {

    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final Counter hitCounter;
    private final Counter missCounter;

    TtlCache(String name, long ttlMs, MeterRegistry meterRegistry) {
        this.ttlMs = ttlMs;
        this.hitCounter = meterRegistry.counter("navigation.cache.total", "cache", name, "result", "hit");
        this.missCounter = meterRegistry.counter("navigation.cache.total", "cache", name, "result", "miss");
    }

    /**
     * 유효한 엔트리가 있으면 그 Mono 를, 없으면 {@code loader} 로 새 엔트리를 만들어 반환한다.
     */
    public Mono<V> get(K key, Supplier<Mono<V>> loader) {
        long now = System.currentTimeMillis();
        return entries.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.isExpired(now)) {
                hitCounter.increment();
                return existing;
            }
            missCounter.increment();
            return new Entry<>(loader.get().cache(), now + ttlMs);
        }).value();
    }

    /** 만료 엔트리 제거 — {@link TtlCacheRegistry} 가 주기 호출. */
    public void purgeExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    public int size() {
        return entries.size();
    }

    private record Entry<T>(Mono<T> value, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
