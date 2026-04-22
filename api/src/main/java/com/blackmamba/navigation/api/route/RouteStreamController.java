package com.blackmamba.navigation.api.route;

import com.blackmamba.navigation.application.route.AccessibilityContext;
import com.blackmamba.navigation.application.route.RecommendationPreference;
import com.blackmamba.navigation.application.route.RouteStreamService;
import com.blackmamba.navigation.application.route.SearchMode;
import com.blackmamba.navigation.application.route.StreamEventPayload;
import com.blackmamba.navigation.domain.location.Location;
import com.blackmamba.navigation.domain.route.MobilityType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * A-1: 경로 탐색 결과를 실시간 SSE 스트림으로 제공.
 *
 * <h3>엔드포인트</h3>
 * <pre>
 *   GET /api/routes/stream?originLat=...&originLng=...&destLat=...&destLng=...
 *   Accept: text/event-stream
 *
 *   Response:
 *     event: INITIAL
 *     data: {"timestamp":"...", "routes":[...]}
 *
 *     event: HEARTBEAT
 *     data: {"timestamp":"...", "status":"watching"}
 *
 *     event: UPDATE
 *     data: {"timestamp":"...", "routes":[...], "changeReason":"..."}
 *
 *     event: COMPLETE
 *     data: {"timestamp":"...", "reason":"timeout", "durationSeconds":300}
 * </pre>
 *
 * <h3>구현 노트</h3>
 * Spring MVC 에서 {@code Flux<ServerSentEvent<T>>} 반환 지원 (내부적으로 ResponseBodyEmitter).
 * WebFlux 전환 없이도 비동기 스트리밍 응답이 가능하다. application 레이어는 순수 Reactor Flux 만
 * 반환하고 (SSE 프로토콜 무관), 컨트롤러가 각 이벤트를 {@link ServerSentEvent} 로 래핑한다.
 */
@Tag(name = "경로 탐색 (실시간)", description = "SSE 기반 경로 재탐색 스트림")
@RestController
@RequestMapping("/api/routes")
public class RouteStreamController {

    private static final Logger log = LoggerFactory.getLogger(RouteStreamController.class);
    private static final double ODSAY_MIN_DISTANCE_METERS = 700.0;

    private final RouteStreamService routeStreamService;

    public RouteStreamController(RouteStreamService routeStreamService) {
        this.routeStreamService = routeStreamService;
    }

    @Operation(
            summary = "경로 탐색 SSE 스트림",
            description = """
                    초기 경로 탐색 후 30초 간격으로 재탐색하며, 추천 경로가 변경되면
                    UPDATE 이벤트로 push. 변경 없으면 HEARTBEAT. 5분 후 자동 COMPLETE.

                    **클라이언트 예시 (curl):**
                    ```
                    curl -N "http://localhost:8081/api/routes/stream?originLat=37.5547&originLng=126.9706&destLat=37.4979&destLng=127.0276"
                    ```

                    **브라우저 (EventSource):**
                    ```javascript
                    const es = new EventSource('/api/routes/stream?originLat=...');
                    es.addEventListener('UPDATE', e => console.log(JSON.parse(e.data)));
                    ```
                    """
    )
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEventPayload>> streamRoutes(
            @Parameter(description = "출발지 위도", example = "37.5547") @RequestParam double originLat,
            @Parameter(description = "출발지 경도", example = "126.9706") @RequestParam double originLng,
            @Parameter(description = "목적지 위도", example = "37.4979") @RequestParam double destLat,
            @Parameter(description = "목적지 경도", example = "127.0276") @RequestParam double destLng,
            @Parameter(description = "이동수단") @RequestParam(defaultValue = "") List<String> mobility,
            @Parameter(description = "탐색 모드") @RequestParam(defaultValue = "SPECIFIC") SearchMode searchMode,
            @Parameter(description = "추천 기준") @RequestParam(defaultValue = "RELIABILITY") RecommendationPreference recommendationPreference,
            @RequestParam(required = false) Boolean wheelchairAccessible,
            @RequestParam(required = false) Double walkingSpeedKmh
    ) {
        // 단순 검증 — 범위 벗어나면 Flux.error 로 즉시 종료
        if (originLat < -90 || originLat > 90 || destLat < -90 || destLat > 90) {
            return Flux.error(new IllegalArgumentException("위도는 -90 ~ 90 범위여야 합니다."));
        }
        if (originLng < -180 || originLng > 180 || destLng < -180 || destLng > 180) {
            return Flux.error(new IllegalArgumentException("경도는 -180 ~ 180 범위여야 합니다."));
        }

        Location origin = new Location("출발지", originLat, originLng);
        Location destination = new Location("목적지", destLat, destLng);

        if (distanceMeters(origin, destination) <= ODSAY_MIN_DISTANCE_METERS) {
            return Flux.error(new IllegalArgumentException(
                    "출발지와 목적지가 700m 이내는 스트리밍 탐색을 지원하지 않습니다."));
        }

        List<MobilityType> mobilityTypes;
        try {
            mobilityTypes = mobility.stream()
                    .filter(m -> !m.isBlank())
                    .map(MobilityType::valueOf)
                    .toList();
        } catch (IllegalArgumentException e) {
            return Flux.error(new IllegalArgumentException("지원하지 않는 이동수단 타입입니다."));
        }

        AccessibilityContext accessibility = AccessibilityContext.of(wheelchairAccessible, walkingSpeedKmh);

        log.info("[Stream] 새 스트림 시작 — ({},{}) → ({},{}) pref={}",
                originLat, originLng, destLat, destLng, recommendationPreference);

        return routeStreamService
                .stream(origin, destination, mobilityTypes, searchMode, recommendationPreference, accessibility)
                .map(RouteStreamController::toSse);
    }

    /** Payload → ServerSentEvent 래핑. event name 은 payload 타입에서 추론 */
    private static ServerSentEvent<StreamEventPayload> toSse(StreamEventPayload payload) {
        String eventName = switch (payload) {
            case StreamEventPayload.Initial ignored -> "INITIAL";
            case StreamEventPayload.Heartbeat ignored -> "HEARTBEAT";
            case StreamEventPayload.Update ignored -> "UPDATE";
            case StreamEventPayload.Complete ignored -> "COMPLETE";
        };
        return ServerSentEvent.<StreamEventPayload>builder(payload)
                .event(eventName)
                .build();
    }

    private double distanceMeters(Location origin, Location destination) {
        double dLat = Math.toRadians(destination.lat() - origin.lat());
        double dLng = Math.toRadians(destination.lng() - origin.lng());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(origin.lat())) * Math.cos(Math.toRadians(destination.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
