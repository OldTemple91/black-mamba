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
