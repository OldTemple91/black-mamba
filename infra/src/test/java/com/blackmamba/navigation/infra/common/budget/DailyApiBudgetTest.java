package com.blackmamba.navigation.infra.common.budget;

import com.blackmamba.navigation.application.route.port.BudgetLevel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class DailyApiBudgetTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T03:00:00Z"));

    @Test
    void 남은_예산이_줄면_수준이_NORMAL_SAVING_EXHAUSTED_로_바뀐다() {
        // given — 한도 10건, 남은 비율 30% 아래부터 절약
        DailyApiBudget budget = budget(10, 0.3);

        // when & then — 7건까지는 여유(남은 3건부터 절약)
        acquire(budget, 6);
        assertThat(budget.level()).isEqualTo(BudgetLevel.NORMAL);
        acquire(budget, 1);
        assertThat(budget.level()).isEqualTo(BudgetLevel.SAVING);
        acquire(budget, 3);
        assertThat(budget.level()).isEqualTo(BudgetLevel.EXHAUSTED);
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
    void API_가_한도_초과로_답하면_집계와_무관하게_소진으로_맞춘다() {
        // given — 재기동 직후라 집계는 1건뿐이지만 실제 한도는 이미 찼다
        DailyApiBudget budget = budget(1_000, 0.3);
        acquire(budget, 1);

        // when
        budget.markExhausted();

        // then
        assertThat(budget.level()).isEqualTo(BudgetLevel.EXHAUSTED);
        assertThat(budget.tryAcquire()).isFalse();
    }

    @Test
    void 날짜가_바뀌면_예산이_다시_찬다() {
        // given
        DailyApiBudget budget = budget(2, 0.3);
        acquire(budget, 2);

        // when — 다음 날
        clock.advance(Duration.ofHours(24));

        // then
        assertThat(budget.level()).isEqualTo(BudgetLevel.NORMAL);
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
