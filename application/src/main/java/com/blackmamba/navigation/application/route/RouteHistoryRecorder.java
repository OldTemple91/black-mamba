package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.application.route.port.RouteHistoryPort;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * {@link RouteHistoryPort} 호출을 <b>비동기 fire-and-forget</b> 으로 감싼다.
 *
 * <h3>왜 별도 컴포넌트인가</h3>
 * <ul>
 *   <li><b>Optional 주입 흡수:</b> Qdrant 빈이 없는 환경(테스트/로컬)에서도 앱이 뜨도록
 *       {@link ObjectProvider#getIfAvailable()} 로 null 허용.
 *       {@link RouteOptimizationService} 는 이 Recorder 를 항상 필수 주입받는다.</li>
 *   <li><b>블로킹 격리:</b> Spring AI VectorStore.add() 는 동기라,
 *       Reactor 체인 안에서 직접 호출하면 경로 탐색 지연에 직결.
 *       {@code Schedulers.boundedElastic()} 에 offload 해 본 요청 응답에 영향 없음.</li>
 *   <li><b>실패 격리:</b> Qdrant/Ollama 장애 시 경고 로그만 남기고 조용히 실패.</li>
 * </ul>
 *
 * <h3>저장 정책</h3>
 * 모든 경로를 저장하지 않는다. {@code recommended=true} 경로만 저장해
 * "이미 점수 검증된 양질 이력" 만 벡터 DB 에 누적한다 (Phase 2 초기 단순화).
 */
@Component
public class RouteHistoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(RouteHistoryRecorder.class);

    private final RouteHistoryPort routeHistoryPort; // null 가능 (Qdrant 빈 부재 시)

    public RouteHistoryRecorder(ObjectProvider<RouteHistoryPort> portProvider) {
        this.routeHistoryPort = portProvider.getIfAvailable();
        if (this.routeHistoryPort == null) {
            log.info("[RAG] RouteHistoryPort 빈 없음 → 경로 이력 저장 비활성화 (Qdrant 미기동?)");
        } else {
            log.info("[RAG] RouteHistoryPort 주입 완료 — {}", this.routeHistoryPort.getClass().getSimpleName());
        }
    }

    /**
     * 추천 경로를 비동기로 벡터 DB 에 저장 (fire-and-forget).
     * 호출자는 결과를 기다리지 않는다.
     */
    public void recordAsync(List<Route> routes, Location origin, Location destination, String preference) {
        if (routeHistoryPort == null) return;
        if (routes == null || routes.isEmpty()) return;

        List<Route> toSave = routes.stream().filter(Route::recommended).toList();
        if (toSave.isEmpty()) return;

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
}
