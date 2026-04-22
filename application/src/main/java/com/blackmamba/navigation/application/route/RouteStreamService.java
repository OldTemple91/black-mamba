package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import com.blackmamba.navigation.domain.route.Route;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A-1: 경로 탐색 결과를 <b>실시간 스트림</b> 으로 공급하는 서비스.
 * <p>
 * MaaS 의 본질은 "이동 중 가이던스". 기존 {@link RouteOptimizationService#findRoutes}
 * 는 1회성 응답이라 사용자가 이동 시작 후 외부 상태(따릉이 재고 등) 변화를
 * 알 수 없다. 본 서비스는 초기 탐색 이후 주기적 재탐색 → 변화 감지 → push
 * 흐름을 제공한다.
 *
 * <h3>스트림 구조</h3>
 * <pre>
 *   Flux.concat(
 *       초기 탐색 1회             → Initial
 *       Flux.interval(30s)
 *           .flatMap(재탐색)
 *           .map(변경 여부 판정)   → Update | Heartbeat
 *   )
 *   .take(5분)                   → Complete
 * </pre>
 *
 * <h3>리턴 타입</h3>
 * 의도적으로 {@link Flux}{@code <Object>} — application 레이어는 SSE 프로토콜을 모른다.
 * Controller 가 각 이벤트를 {@code ServerSentEvent} 로 래핑한다.
 */
@Service
public class RouteStreamService {

    private static final Logger log = LoggerFactory.getLogger(RouteStreamService.class);

    /** 재탐색 간격 */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(30);
    /** 스트림 전체 최대 수명 */
    private static final Duration MAX_STREAM_DURATION = Duration.ofMinutes(5);
    /** 탐색 타임아웃 (개별 호출) */
    private static final Duration SEARCH_TIMEOUT = Duration.ofSeconds(30);
    /** 추천 경로 소요시간 변화가 이 값 이상이면 UPDATE 이벤트 발행 */
    private static final int MINUTES_DELTA_THRESHOLD = 2;

    private final RouteOptimizationService routeOptimizationService;
    private final AtomicInteger activeStreams = new AtomicInteger(0);
    private final Counter updatesPushedCounter;
    private final Counter heartbeatsPushedCounter;
    private final Counter streamsOpenedCounter;

    public RouteStreamService(RouteOptimizationService routeOptimizationService,
                              MeterRegistry meterRegistry) {
        this.routeOptimizationService = routeOptimizationService;
        this.streamsOpenedCounter = Counter.builder("navigation.route.stream.opened")
                .description("열린 경로 스트림 누적 건수")
                .register(meterRegistry);
        this.updatesPushedCounter = Counter.builder("navigation.route.stream.event")
                .description("클라이언트에 푸시된 이벤트 건수")
                .tag("type", "update")
                .register(meterRegistry);
        this.heartbeatsPushedCounter = Counter.builder("navigation.route.stream.event")
                .description("클라이언트에 푸시된 이벤트 건수")
                .tag("type", "heartbeat")
                .register(meterRegistry);
        Gauge.builder("navigation.route.stream.active", activeStreams, AtomicInteger::get)
                .description("현재 열려있는 SSE 스트림 수")
                .register(meterRegistry);
    }

    @Observed(name = "navigation.route.stream",
            contextualName = "실시간 경로 스트림")
    public Flux<StreamEventPayload> stream(Location origin,
                                           Location destination,
                                           List<MobilityType> mobilityTypes,
                                           SearchMode searchMode,
                                           RecommendationPreference preference,
                                           AccessibilityContext access) {
        Instant startedAt = Instant.now();
        AtomicReference<List<Route>> lastRoutes = new AtomicReference<>();

        Flux<StreamEventPayload> initial = findRoutesMono(origin, destination, mobilityTypes, searchMode, preference, access)
                .doOnNext(lastRoutes::set)
                .map(routes -> (StreamEventPayload) new StreamEventPayload.Initial(Instant.now(), routes))
                .flux();

        Flux<StreamEventPayload> polls = Flux.interval(POLL_INTERVAL, POLL_INTERVAL)
                .flatMap(tick -> findRoutesMono(origin, destination, mobilityTypes, searchMode, preference, access)
                        .onErrorResume(err -> {
                            log.warn("[Stream] 재탐색 실패 — heartbeat 로 대체. err={}", err.getMessage());
                            return Mono.empty();
                        }))
                .map(current -> {
                    List<Route> prev = lastRoutes.get();
                    String reason = changeReason(prev, current);
                    if (reason != null) {
                        lastRoutes.set(current);
                        updatesPushedCounter.increment();
                        return (StreamEventPayload) new StreamEventPayload.Update(Instant.now(), current, reason);
                    }
                    heartbeatsPushedCounter.increment();
                    return (StreamEventPayload) StreamEventPayload.Heartbeat.watching();
                });

        return Flux.concat(initial, polls)
                .take(MAX_STREAM_DURATION)
                .concatWith(Flux.defer(() -> {
                    long seconds = Duration.between(startedAt, Instant.now()).toSeconds();
                    return Flux.just(new StreamEventPayload.Complete(Instant.now(), "timeout", seconds));
                }))
                .doOnSubscribe(sub -> {
                    streamsOpenedCounter.increment();
                    activeStreams.incrementAndGet();
                })
                .doOnCancel(() -> log.info("[Stream] 클라이언트 연결 끊김 — duration={}s",
                        Duration.between(startedAt, Instant.now()).toSeconds()))
                .doFinally(signal -> {
                    activeStreams.decrementAndGet();
                    log.info("[Stream] 종료 signal={}", signal);
                });
    }

    private Mono<List<Route>> findRoutesMono(Location origin,
                                             Location destination,
                                             List<MobilityType> mobilityTypes,
                                             SearchMode searchMode,
                                             RecommendationPreference preference,
                                             AccessibilityContext access) {
        return routeOptimizationService
                .findRoutes(origin, destination, mobilityTypes, searchMode, preference, access)
                .timeout(SEARCH_TIMEOUT);
    }

    /**
     * 이전 결과 대비 의미 있는 변화가 있으면 이유 문자열 반환.
     * 없으면 null — HEARTBEAT 로 처리.
     */
    static String changeReason(List<Route> prev, List<Route> cur) {
        if (cur == null || cur.isEmpty()) return null;
        if (prev == null || prev.isEmpty()) return "초기 결과 도착";

        Route prevRec = prev.stream().filter(Route::recommended).findFirst().orElse(null);
        Route curRec = cur.stream().filter(Route::recommended).findFirst().orElse(null);

        if (prevRec == null && curRec == null) return null;
        if (prevRec == null) return "추천 경로 생성";
        if (curRec == null) return "추천 경로 해제";

        if (!prevRec.routeId().equals(curRec.routeId())) {
            return "추천 경로 변경 (" + prevRec.type() + " → " + curRec.type() + ")";
        }
        int delta = Math.abs(prevRec.totalMinutes() - curRec.totalMinutes());
        if (delta >= MINUTES_DELTA_THRESHOLD) {
            return "추천 경로 소요시간 " + (curRec.totalMinutes() - prevRec.totalMinutes()) + "분 변화";
        }
        return null;
    }
}
