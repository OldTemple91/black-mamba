package com.blackmamba.navigation.infra.common.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TtlCacheTest {

    private final TtlCacheRegistry registry = new TtlCacheRegistry(new SimpleMeterRegistry());

    @Test
    void TTL_안에서는_같은_키의_로더를_한_번만_실행한다() {
        // given
        TtlCache<String, Integer> cache = registry.create("test", 60_000L);
        AtomicInteger loads = new AtomicInteger();

        // when
        Integer first = cache.get("k", () -> Mono.fromSupplier(loads::incrementAndGet)).block();
        Integer second = cache.get("k", () -> Mono.fromSupplier(loads::incrementAndGet)).block();

        // then
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(1);
        assertThat(loads).hasValue(1);
    }

    @Test
    void TTL_0_이면_매_호출이_miss_다() {
        // given
        TtlCache<String, Integer> cache = registry.create("test", 0L);
        AtomicInteger loads = new AtomicInteger();

        // when
        cache.get("k", () -> Mono.fromSupplier(loads::incrementAndGet)).block();
        cache.get("k", () -> Mono.fromSupplier(loads::incrementAndGet)).block();

        // then
        assertThat(loads).hasValue(2);
    }

    @Test
    void 캐시_불가_판정된_값은_다음_호출에서_다시_로드된다() {
        // given — 첫 로드는 폴백(빈 값, 캐시 불가), 두 번째 로드는 정상
        TtlCache<String, Integer> cache = registry.create("fallback", 60_000L);
        AtomicInteger loads = new AtomicInteger();

        // when
        Integer first = cache.get("k", () -> Mono.fromSupplier(loads::incrementAndGet), v -> v > 1).block();
        Integer second = cache.get("k", () -> Mono.fromSupplier(loads::incrementAndGet), v -> v > 1).block();
        Integer third = cache.get("k", () -> Mono.fromSupplier(loads::incrementAndGet), v -> v > 1).block();

        // then — 1(폴백)은 캐시되지 않아 재로드, 2 부터는 캐시 재생
        assertThat(first).isEqualTo(1);
        assertThat(second).isEqualTo(2);
        assertThat(third).isEqualTo(2);
        assertThat(loads).hasValue(2);
    }

    @Test
    void 만료_엔트리는_purge_로_제거되고_유효_엔트리는_남는다() {
        // given
        TtlCache<String, Integer> expiring = registry.create("expiring", 0L);
        TtlCache<String, Integer> alive = registry.create("alive", 60_000L);
        expiring.get("k", () -> Mono.just(1)).block();
        alive.get("k", () -> Mono.just(1)).block();

        // when
        registry.purgeAll();

        // then
        assertThat(expiring.size()).isZero();
        assertThat(alive.size()).isEqualTo(1);
    }
}
