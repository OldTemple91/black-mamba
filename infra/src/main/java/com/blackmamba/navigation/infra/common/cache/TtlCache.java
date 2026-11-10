package com.blackmamba.navigation.infra.common.cache;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 키별 TTL 을 가진 {@link Mono} 결과 캐시 — 저장소는 Caffeine {@link AsyncCache}.
 * <p>
 * 예전엔 {@code ConcurrentHashMap + 만료 시각 + @Scheduled 청소} 를 직접 구현했는데 크기 상한이 없었다.
 * OD 쌍 키는 조합 수가 많고 Tmap 엔트리는 좌표 목록을 들고 있어, 청소 주기 사이에 힙이 수십 MB 까지 불어날 수 있었다.
 * Caffeine 이 만료·크기 상한·동시 로드 합치기를 맡고, 이 클래스는 프로젝트 고유 규칙만 남긴다.
 *
 * <h3>의미</h3>
 * <ul>
 *   <li>같은 키의 동시 호출은 하나의 로더로 합쳐진다</li>
 *   <li>로더는 첫 구독 시점에 실행된다 — 구독자의 Reactor 컨텍스트(추적 정보)를 물려받는다</li>
 *   <li>오류로 끝난 로드와 값 없이 끝난 로드는 캐시에 남지 않는다 (Caffeine 기본 동작)</li>
 *   <li>로더의 <b>폴백 값</b>(오류 시 빈 결과)까지 TTL 동안 재생하면 일시 장애가 TTL 만큼 고정된다 —
 *       {@link #get(Object, Supplier, Predicate)} 로 "캐시할 가치가 있는 값" 만 남길 수 있다</li>
 *   <li>TTL 0 이면 캐시하지 않고 매번 로더를 실행한다 (테스트용)</li>
 * </ul>
 */
public final class TtlCache<K, V> {

    /** 캐시당 엔트리 상한 — 넘으면 Caffeine 이 덜 쓰인 것부터 내보낸다 */
    private static final long MAX_ENTRIES = 10_000;

    private final AsyncCache<K, V> cache;
    private final boolean enabled;
    private final Counter hitCounter;
    private final Counter missCounter;

    TtlCache(String name, long ttlMs, MeterRegistry meterRegistry) {
        this.enabled = ttlMs > 0;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMillis(Math.max(ttlMs, 0)))
                .maximumSize(MAX_ENTRIES)
                .buildAsync();
        this.hitCounter = meterRegistry.counter("navigation.cache.total", "cache", name, "result", "hit");
        this.missCounter = meterRegistry.counter("navigation.cache.total", "cache", name, "result", "miss");
    }

    /**
     * 유효한 엔트리가 있으면 그 결과를, 없으면 {@code loader} 로 새 엔트리를 만들어 반환한다.
     */
    public Mono<V> get(K key, Supplier<Mono<V>> loader) {
        return get(key, loader, _ -> true);
    }

    /**
     * {@code cacheable} 이 거짓인 값(예: 오류 폴백으로 만든 빈 결과)은 호출자에게는 전달하되
     * 엔트리를 즉시 비워 다음 호출이 다시 로드하게 한다.
     */
    public Mono<V> get(K key, Supplier<Mono<V>> loader, Predicate<V> cacheable) {
        if (!enabled) {
            return Mono.defer(() -> {
                missCounter.increment();
                return loader.get();
            });
        }
        return Mono.deferContextual(context -> {
            if (cache.getIfPresent(key) != null) {
                hitCounter.increment();
            } else {
                missCounter.increment();
            }
            CompletableFuture<V> future = cache.get(key, (_, _) -> loader.get()
                    .contextWrite(context)
                    .toFuture());
            future.thenAccept(value -> {
                if (value != null && !cacheable.test(value)) {
                    cache.asMap().remove(key, future);
                }
            });
            // 구독자 한 명의 취소가 공유 중인 로드를 취소하지 않게 한다
            return Mono.fromFuture(future, true);
        });
    }

    /** 현재 엔트리 수 (만료분 정리 후의 추정치). */
    public long size() {
        cache.synchronous().cleanUp();
        return cache.synchronous().estimatedSize();
    }
}
