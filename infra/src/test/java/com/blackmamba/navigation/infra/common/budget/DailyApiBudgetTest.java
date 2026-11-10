package com.blackmamba.navigation.infra.common.budget;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailyApiBudgetTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T03:00:00Z"));

    @Test
    void 남은_예산이_줄면_절약을_거쳐_소진으로_바뀐다() {
        // given — 한도 10건, 남은 비율 30% 아래부터 절약
        DailyApiBudget budget = budget(10, 0.3);

        // when & then — 7건까지는 여유(남은 3건부터 절약)
        acquire(budget, 6);
        assertThat(budget.isSaving()).isFalse();
        acquire(budget, 1);
        assertThat(budget.isSaving()).isTrue();
        assertThat(budget.isExhausted()).isFalse();
        acquire(budget, 3);
        assertThat(budget.isExhausted()).isTrue();
    }

    @Test
    void 소진_뒤에는_호출을_허락하지_않고_거절_수를_센다() {
        // given
        DailyApiBudget budget = budget(2, 0.3);
        acquire(budget, 2);

        // when
        boolean allowed = budget.tryAcquire();

        // then
        assertThat(allowed).isFalse();
        assertThat(budget.remaining()).isZero();
        assertThat(meterRegistry.get("navigation.api.budget.rejected.total").tag("api", "test").counter().count()).isEqualTo(1.0);
    }

    @Test
    void API_가_한도_초과로_답하면_집계와_무관하게_호출을_막는다() {
        // given — 재기동 직후라 집계는 1건뿐이지만 실제 한도는 이미 찼다
        DailyApiBudget budget = budget(1_000, 0.3);
        acquire(budget, 1);

        // when
        budget.markExhausted();

        // then
        assertThat(budget.isExhausted()).isTrue();
        assertThat(budget.remaining()).isZero();
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void 한도_초과로_막은_것은_다시_확인할_시간이_지나면_풀리고_집계는_그대로다() {
        // given
        DailyApiBudget budget = budget(1_000, 0.3);
        acquire(budget, 1);
        budget.markExhausted();

        // when
        clock.advance(DailyApiBudget.QUOTA_RECHECK_INTERVAL);

        // then — 다시 보내 볼 수 있고, 막기 전에 쓴 1건은 남아 있다
        assertThat(budget.isExhausted()).isFalse();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.remaining()).isEqualTo(998);
    }

    @Test
    void 한도_초과로_막은_것은_날짜가_바뀌어도_시간이_지나기_전에는_풀리지_않는다() {
        // given — 자정 5분 전에 한도 초과 응답. 제공자의 한도 초기화 시각은 우리 자정과 다를 수 있다
        clock.advance(Duration.ofHours(11).plusMinutes(55));
        DailyApiBudget budget = budget(1_000, 0.3);
        budget.markExhausted();

        // when — 자정이 지났지만 다시 확인할 시간은 아직이다
        clock.advance(Duration.ofMinutes(10));

        // then
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void 한도가_1_보다_작으면_만들_수_없다() {
        // when & then
        assertThatThrownBy(() -> budget(0, 0.3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 날짜가_바뀌면_예산이_다시_찬다() {
        // given
        DailyApiBudget budget = budget(2, 0.3);
        acquire(budget, 2);

        // when — 다음 날
        clock.advance(Duration.ofHours(24));

        // then
        assertThat(budget.isSaving()).isFalse();
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.remaining()).isEqualTo(1);
    }

    private DailyApiBudget budget(int dailyLimit, double savingRemainingRatio) {
        return new DailyApiBudget("test", dailyLimit, savingRemainingRatio, clock, meterRegistry);
    }

    private static void acquire(DailyApiBudget budget, int times) {
        for (int i = 0; i < times; i++) {
            budget.tryAcquire();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return KST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
