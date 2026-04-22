package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.RouteHistoryPort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.LegType;
import com.blackmamba.navigation.domain.route.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
 *   <li><b>블로킹 격리:</b> Spring AI VectorStore.add() 는 동기라,
 *       {@code Schedulers.boundedElastic()} 에 offload 해 본 요청 응답에 영향 없음.</li>
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

    /** 품질 게이트 임계값 */
    private static final int MIN_TOTAL_MINUTES = 5;
    private static final int MIN_TOTAL_COST_WON = 0;

    private final RouteHistoryPort routeHistoryPort; // null 가능 (Qdrant 빈 부재 시)

    /**
     * (OD 해시 + preference) → 최근 저장 시각.
     * 단순 ConcurrentHashMap + 접근 시 lazy purge 로 Caffeine 등 의존성 없이 처리.
     */
    private final Map<String, Instant> recentlySaved = new ConcurrentHashMap<>();

    public RouteHistoryRecorder(ObjectProvider<RouteHistoryPort> portProvider) {
        this.routeHistoryPort = portProvider.getIfAvailable();
        if (this.routeHistoryPort == null) {
            log.info("[RAG] RouteHistoryPort 빈 없음 → 경로 이력 저장 비활성화 (Qdrant 미기동?)");
        } else {
            log.info("[RAG] RouteHistoryPort 주입 완료 — {} (품질 게이트 + 중복 감지 활성)",
                    this.routeHistoryPort.getClass().getSimpleName());
        }
    }

    /**
     * 추천 경로를 비동기로 벡터 DB 에 저장 (fire-and-forget).
     * 품질 게이트 + 중복 감지 통과한 경로만 저장.
     */
    public void recordAsync(List<Route> routes, Location origin, Location destination, String preference) {
        if (routeHistoryPort == null) return;
        if (routes == null || routes.isEmpty()) return;

        List<Route> toSave = routes.stream()
                .filter(Route::recommended)
                .filter(this::passesQualityGate)
                .toList();
        if (toSave.isEmpty()) return;

        // 중복 감지 (OD + preference 키로)
        if (isRecentlySaved(origin, destination, preference)) {
            log.debug("[RAG] 중복 저장 스킵 — {} → {} [{}]", origin.name(), destination.name(), preference);
            return;
        }

        markSaved(origin, destination, preference);

        Mono.fromRunnable(() -> {
                    for (Route r : toSave) {
                        routeHistoryPort.save(r, origin, destination, preference);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> {},
                        err -> log.warn("[RAG] 비동기 저장 실패 — {}: {}",
                                err.getClass().getSimpleName(), err.getMessage())
                );
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

    private boolean isRecentlySaved(Location origin, Location destination, String preference) {
        purgeExpired();
        String key = dedupKey(origin, destination, preference);
        Instant last = recentlySaved.get(key);
        if (last == null) return false;
        return Duration.between(last, Instant.now()).compareTo(DEDUP_WINDOW) < 0;
    }

    private void markSaved(Location origin, Location destination, String preference) {
        recentlySaved.put(dedupKey(origin, destination, preference), Instant.now());
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

    private static String dedupKey(Location origin, Location destination, String preference) {
        // 3자리 소수(~100m) 반올림으로 OD 근접 중복까지 잡는다
        return String.format("%.3f,%.3f|%.3f,%.3f|%s",
                origin.lat(), origin.lng(),
                destination.lat(), destination.lng(),
                preference == null ? "" : preference);
    }
}
