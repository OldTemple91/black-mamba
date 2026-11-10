package com.blackmamba.navigation.infra.common.budget;

import com.blackmamba.navigation.application.route.port.BudgetLevel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;

/**
 * 외부 API 의 일일 호출 예산.
 * <p>
 * 무료 요금제는 하루 호출 수가 정해져 있다. 한도를 넘으면 API 가 거절하고, 그 뒤 모든 요청이 추정값으로 떨어진다.
 * 여기서 호출 수를 세어 (1) 남은 예산 수준을 전략에 알려 탐색 폭을 줄이게 하고, (2) 소진 뒤에는 어차피 거절될 호출을 보내지 않는다.
 *
 * <h3>한계</h3>
 * <ul>
 *   <li>집계는 프로세스 메모리에 있다 — 재기동하면 0 부터 다시 센다. 그래서 API 가 "한도 초과" 로 답하면
 *       {@link #markExhausted()} 로 그날 남은 예산을 0 으로 맞춘다(실제 상태가 집계보다 우선)</li>
 *   <li>날짜는 주입받은 시계의 시간대 기준으로 바뀐다 — 제공자의 초기화 시각과 다를 수 있다</li>
 * </ul>
 */
public final class DailyApiBudget {

    private static final Logger log = LoggerFactory.getLogger(DailyApiBudget.class);

    private final String apiName;
    private final int dailyLimit;
    private final int savingThreshold;
    private final Clock clock;
    private final Counter rejectedCounter;

    private LocalDate day;
    private int used;

    /**
     * @param dailyLimit           하루 호출 한도
     * @param savingRemainingRatio 남은 비율이 이 값 아래로 내려가면 절약 수준 (0.3 = 30%)
     */
    public DailyApiBudget(String apiName, int dailyLimit, double savingRemainingRatio, Clock clock, MeterRegistry meterRegistry) {
        this.apiName = apiName;
        this.dailyLimit = dailyLimit;
        this.savingThreshold = (int) Math.floor(dailyLimit * savingRemainingRatio);
        this.clock = clock;
        this.day = LocalDate.now(clock);
        this.rejectedCounter = meterRegistry.counter("navigation.api.budget.rejected.total", "api", apiName);
        Gauge.builder("navigation.api.budget.remaining", this, DailyApiBudget::remaining)
                .description("오늘 남은 외부 API 호출 예산")
                .tag("api", apiName)
                .register(meterRegistry);
        Gauge.builder("navigation.api.budget.limit", () -> dailyLimit)
                .description("외부 API 일일 호출 한도")
                .tag("api", apiName)
                .register(meterRegistry);
    }

    /** 호출 1건을 쓴다. 예산이 없으면 쓰지 않고 false — 호출자는 요청을 보내지 않는다. */
    public synchronized boolean tryAcquire() {
        rollOverIfNewDay();
        if (used >= dailyLimit) {
            rejectedCounter.increment();
            return false;
        }
        used++;
        if (savingThreshold > 0 && used == dailyLimit - savingThreshold) {
            log.warn("[예산] {} 일일 예산이 절약 수준에 들어섬 — {}/{}건 사용", apiName, used, dailyLimit);
        }
        if (used == dailyLimit) {
            log.warn("[예산] {} 일일 예산 소진 — {}건. 내일까지 호출을 보내지 않는다", apiName, dailyLimit);
        }
        return true;
    }

    /** API 가 한도 초과로 답했다 — 집계와 무관하게 오늘 예산을 소진으로 맞춘다. */
    public synchronized void markExhausted() {
        rollOverIfNewDay();
        if (used < dailyLimit) {
            log.warn("[예산] {} 가 한도 초과로 응답 — 집계({}건)와 달리 실제 예산은 소진. 내일까지 호출을 보내지 않는다", apiName, used);
            used = dailyLimit;
        }
    }

    public synchronized BudgetLevel level() {
        rollOverIfNewDay();
        int remaining = dailyLimit - used;
        if (remaining <= 0) {
            return BudgetLevel.EXHAUSTED;
        }
        if (remaining <= savingThreshold) {
            return BudgetLevel.SAVING;
        }
        return BudgetLevel.NORMAL;
    }

    public synchronized int remaining() {
        rollOverIfNewDay();
        return dailyLimit - used;
    }

    private void rollOverIfNewDay() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            log.info("[예산] {} 일일 예산 초기화 — 어제 {}건 사용", apiName, used);
            day = today;
            used = 0;
        }
    }
}
