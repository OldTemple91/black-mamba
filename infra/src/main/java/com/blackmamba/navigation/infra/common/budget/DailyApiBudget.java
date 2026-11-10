package com.blackmamba.navigation.infra.common.budget;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 *       {@link #markExhausted()} 로 한동안 호출을 막는다(실제 상태가 집계보다 우선)</li>
 *   <li>날짜는 주입받은 시계의 시간대 기준으로 바뀐다 — 제공자의 초기화 시각과 다를 수 있다.
 *       그래서 "한도 초과" 응답으로 막은 것은 날짜가 아니라 시간으로 푼다: {@link #QUOTA_RECHECK_INTERVAL} 뒤에 다시 호출을 허락해
 *       제공자 쪽 한도가 풀렸는지 확인하고, 아직이면 다시 막는다. 날짜로 풀면 우리 자정 직후의 "한도 초과" 한 번이 새 하루를 통째로 막는다</li>
 * </ul>
 */
public final class DailyApiBudget {

    private static final Logger log = LoggerFactory.getLogger(DailyApiBudget.class);
    /** 한도 초과 응답 뒤 다시 확인하기까지의 간격 — 하루 최대 96건을 확인에 쓴다 */
    static final Duration QUOTA_RECHECK_INTERVAL = Duration.ofMinutes(15);

    private final String apiName;
    private final int dailyLimit;
    private final int savingThreshold;
    private final Clock clock;
    private final Counter rejectedCounter;

    private LocalDate day;
    private int used;
    /** API 가 한도 초과로 답해 호출을 막아 둔 시각의 끝. 막지 않았으면 EPOCH */
    private Instant blockedUntil = Instant.EPOCH;

    /**
     * @param dailyLimit           하루 호출 한도
     * @param savingRemainingRatio 남은 비율이 이 값 아래로 내려가면 절약 수준 (0.3 = 30%)
     */
    public DailyApiBudget(String apiName, int dailyLimit, double savingRemainingRatio, Clock clock, MeterRegistry meterRegistry) {
        if (dailyLimit <= 0) {
            throw new IllegalArgumentException(apiName + " 일일 호출 한도는 1 이상이어야 합니다: " + dailyLimit);
        }
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
        // 알림 규칙이 설정값(saving-remaining-ratio)을 따로 적지 않고 이 값과 비교한다
        Gauge.builder("navigation.api.budget.saving.threshold", () -> savingThreshold)
                .description("남은 예산이 이 값 이하면 절약 수준")
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
        if (isBlocked() || used >= dailyLimit) {
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

    /** API 가 한도 초과로 답했다 — 집계와 무관하게 {@link #QUOTA_RECHECK_INTERVAL} 동안 호출을 막는다. */
    public synchronized void markExhausted() {
        if (!isBlocked()) {
            log.warn("[예산] {} 가 한도 초과로 응답 — 집계({}건)와 달리 실제 예산은 소진. {}분 뒤 다시 확인한다", apiName, used, QUOTA_RECHECK_INTERVAL.toMinutes());
        }
        blockedUntil = clock.instant().plus(QUOTA_RECHECK_INTERVAL);
    }

    /** 지금 호출을 보낼 수 없는가 — 예산을 다 썼거나, 한도 초과 응답으로 막혀 있다. */
    public synchronized boolean isExhausted() {
        return remaining() <= 0;
    }

    /** 남은 예산이 절약 기준 이하인가(소진 포함). */
    public synchronized boolean isSaving() {
        return remaining() <= savingThreshold;
    }

    public synchronized int remaining() {
        rollOverIfNewDay();
        if (isBlocked()) {
            return 0;
        }
        return dailyLimit - used;
    }

    private boolean isBlocked() {
        return clock.instant().isBefore(blockedUntil);
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
