package com.blackmamba.navigation.application.route.history;

import com.blackmamba.navigation.application.route.port.RouteHistoryPort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import com.blackmamba.navigation.domain.route.evaluation.RecommendationPreference;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RouteHistoryPort} 호출을 <b>비동기 fire-and-forget</b> 으로 감싼다.
 *
 * <h3>왜 별도 컴포넌트인가</h3>
 * <ul>
 *   <li><b>Optional 주입 흡수:</b> Qdrant 빈이 없는 환경에서도 앱이 뜨도록
 *       {@link ObjectProvider#getIfAvailable()} 로 null 허용.</li>
 *   <li><b>비동기 발사:</b> 포트가 돌려주는 Mono 를 구독만 하고 결과를 기다리지 않는다.
 *       블로킹 SDK 를 어느 스케줄러에 격리할지는 어댑터 책임.</li>
 *   <li><b>실패 격리:</b> Qdrant/Ollama 장애 시 경고 로그만 남기고 조용히 실패.</li>
 * </ul>
 *
 * <h3>저장 정책 — 데이터 품질 게이트</h3>
 * "recommended=true 면 무조건 저장" 은 노이즈 유입 위험이 있어, 저장 전 품질 검증을 거친다:
 * <ol>
 *   <li><b>최소 의미 기준</b>: legs 비어있음 / 5분 미만 / 0원 이하 → 저장 제외</li>
 *   <li><b>서술 길이</b>: RouteHistoryDescriber 출력이 20자 미만 → 정보성 부족으로 제외</li>
 *   <li><b>중복 감지</b>: 동일 (OD geohash + preference) 가 최근 60초 안에 저장됐으면 제외</li>
 * </ol>
 * 이 게이트가 Qdrant 가 "의미 있는 이력만" 누적하도록 보호하는 1차 방어선.
 */
@Component
public class RouteHistoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(RouteHistoryRecorder.class);

    /** 중복 저장 방지 윈도우 */
    private static final Duration DEDUP_WINDOW = Duration.ofSeconds(60);
    /** 임베딩(Ollama) + Qdrant 저장 상한 — 응답 없는 저장이 boundedElastic 스레드를 붙잡고 쌓이지 않게 */
    private static final Duration SAVE_TIMEOUT = Duration.ofSeconds(20);

    /** 품질 게이트 임계값 */
    private static final int MIN_TOTAL_MINUTES = 5;
    private static final int MIN_TOTAL_COST_WON = 0;

    private final RouteHistoryPort routeHistoryPort; // null 가능 (Qdrant 빈 부재 시)
    private final Counter savedCounter;
    private final Counter qualityGateRejectedCounter;
    private final Counter duplicateSkippedCounter;
    private final Counter saveFailedCounter;

    /**
     * (OD 해시 + preference) → 최근 저장 시각.
     * 단순 ConcurrentHashMap + 접근 시 lazy purge 로 Caffeine 등 의존성 없이 처리.
     */
    private final Map<String, Instant> recentlySaved = new ConcurrentHashMap<>();

    public RouteHistoryRecorder(ObjectProvider<RouteHistoryPort> portProvider,
                                MeterRegistry meterRegistry) {
        this.routeHistoryPort = portProvider.getIfAvailable();
        this.savedCounter = Counter.builder("navigation.rag.history.saved")
                .description("Qdrant 에 실제 저장된 경로 이력 건수")
                .register(meterRegistry);
        this.qualityGateRejectedCounter = Counter.builder("navigation.rag.history.rejected")
                .description("품질 게이트에 걸려 제외된 경로 이력 건수")
                .tag("reason", "quality_gate")
                .register(meterRegistry);
        this.duplicateSkippedCounter = Counter.builder("navigation.rag.history.rejected")
                .description("중복 감지로 스킵된 경로 이력 건수")
                .tag("reason", "duplicate")
                .register(meterRegistry);
        this.saveFailedCounter = Counter.builder("navigation.rag.history.failed")
                .description("저장을 시도했지만 실패(오류·타임아웃)한 횟수")
                .register(meterRegistry);

        if (this.routeHistoryPort == null) {
            log.info("[RAG] RouteHistoryPort 빈 없음 → 경로 이력 저장 비활성화 (Qdrant 미기동?)");
        } else {
            log.info("[RAG] RouteHistoryPort 주입 완료 — {} (품질 게이트 + 중복 감지 + 메트릭 활성)",
                    this.routeHistoryPort.getClass().getSimpleName());
        }
    }

    /**
     * 추천 경로를 비동기로 벡터 DB 에 저장 (fire-and-forget).
     * 품질 게이트 + 중복 감지 통과한 경로만 저장.
     */
    public void recordAsync(List<Route> routes, Location origin, Location destination, RecommendationPreference preference) {
        if (routeHistoryPort == null) return;
        if (routes == null || routes.isEmpty()) return;

        List<Route> recommended = routes.stream().filter(Route::recommended).toList();
        List<Route> toSave = recommended.stream()
                .filter(this::passesQualityGate)
                .toList();

        int rejectedByQuality = recommended.size() - toSave.size();
        if (rejectedByQuality > 0) qualityGateRejectedCounter.increment(rejectedByQuality);
        if (toSave.isEmpty()) return;

        // 중복 감지 (OD + preference 키로) — 확인과 표시를 한 번에 해 동시 요청이 둘 다 통과하지 않게 한다
        String dedupKey = dedupKey(origin, destination, preference);
        if (!claim(dedupKey)) {
            log.debug("[RAG] 중복 저장 스킵 — {} → {} [{}]", origin.name(), destination.name(), preference);
            duplicateSkippedCounter.increment(toSave.size());
            return;
        }

        int savingCount = toSave.size();
        Flux.fromIterable(toSave)
                .concatMap(r -> routeHistoryPort.save(r, origin, destination, preference))
                .then()
                .timeout(SAVE_TIMEOUT)
                .subscribe(
                        _ -> { },
                        err -> {
                            // 실패한 저장이 중복 창(60초) 동안 재시도를 막지 않게 표시를 푼다
                            recentlySaved.remove(dedupKey);
                            saveFailedCounter.increment();
                            log.warn("[RAG] 비동기 저장 실패 — {}: {}", err.getClass().getSimpleName(), err.getMessage());
                        },
                        () -> savedCounter.increment(savingCount));
    }

    // ─── 품질 게이트 ─────────────────────────────

    private boolean passesQualityGate(Route route) {
        if (route == null) return false;
        if (route.legs() == null || route.legs().isEmpty()) {
            log.debug("[RAG] 품질 게이트: legs 비어있음 → 제외 routeId={}", route.routeId());
            return false;
        }
        if (route.totalMinutes() < MIN_TOTAL_MINUTES) {
            log.debug("[RAG] 품질 게이트: {}분 < {}분 → 제외 routeId={}",
                    route.totalMinutes(), MIN_TOTAL_MINUTES, route.routeId());
            return false;
        }
        if (route.totalCostWon() <= MIN_TOTAL_COST_WON) {
            log.debug("[RAG] 품질 게이트: {}원 <= {}원 → 제외 routeId={}",
                    route.totalCostWon(), MIN_TOTAL_COST_WON, route.routeId());
            return false;
        }
        // TRANSIT 또는 이동수단이 최소 하나는 있어야 의미 있는 경로
        boolean hasNonWalkLeg = route.legs().stream().anyMatch(leg -> leg.type() != LegType.WALK);
        if (!hasNonWalkLeg) {
            log.debug("[RAG] 품질 게이트: 도보만 있는 경로 → 제외 routeId={}", route.routeId());
            return false;
        }
        return true;
    }

    // ─── 중복 감지 ──────────────────────────────

    /** 최근에 저장한 적이 없으면 표시를 남기고 true. 확인-후-표시를 원자적으로 한다. */
    private boolean claim(String key) {
        purgeExpired();
        Instant now = Instant.now();
        Instant winner = recentlySaved.compute(key, (_, last) -> {
            if (last != null && Duration.between(last, now).compareTo(DEDUP_WINDOW) < 0) {
                return last;
            }
            return now;
        });
        return winner == now;
    }

    /** 접근 때마다 만료된 엔트리 제거 (별도 스케줄러 없이 self-clean) */
    private void purgeExpired() {
        Instant cutoff = Instant.now().minus(DEDUP_WINDOW);
        Iterator<Map.Entry<String, Instant>> it = recentlySaved.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Instant> e = it.next();
            if (e.getValue().isBefore(cutoff)) it.remove();
        }
    }

    private static String dedupKey(Location origin, Location destination, RecommendationPreference preference) {
        // 3자리 소수(~100m) 반올림으로 OD 근접 중복까지 잡는다
        return String.format("%.3f,%.3f|%.3f,%.3f|%s",
                origin.lat(), origin.lng(),
                destination.lat(), destination.lng(),
                preference);
    }
}
