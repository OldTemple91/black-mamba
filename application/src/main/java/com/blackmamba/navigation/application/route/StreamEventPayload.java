package com.blackmamba.navigation.application.route;

import com.blackmamba.navigation.domain.route.Route;

import java.time.Instant;
import java.util.List;

/**
 * 경로 스트림({@link RouteStreamService}) 의 이벤트 payload 계약.
 * <p>
 * application 레이어는 SSE 프로토콜을 모른다. 이 sealed interface 는 "이벤트 종류 + 필요한 데이터"
 * 만 담고, api 레이어가 {@code ServerSentEvent} 래핑 + event name 지정을 담당한다.
 *
 * <h3>이벤트 4종</h3>
 * <ul>
 *   <li>{@link Initial} — 구독 직후 1회, 초기 경로 리스트</li>
 *   <li>{@link Heartbeat} — 30초 간격. status: {@code watching}(변화 없음) / {@code search_failed}(재탐색 실패, 이전 결과 유지) /
 *       {@code no_routes}(재탐색은 됐지만 경로 0건) — 클라이언트가 "조용한 정상" 과 "조용한 장애" 를 구분할 수 있어야 한다</li>
 *   <li>{@link Update} — 추천 경로 변경 감지, 새 경로 + 사유</li>
 *   <li>{@link Complete} — 종료 (timeout / disconnect / error)</li>
 * </ul>
 */
public sealed interface StreamEventPayload {

    Instant timestamp();

    record Initial(Instant timestamp, List<Route> routes) implements StreamEventPayload {}

    record Heartbeat(Instant timestamp, HeartbeatStatus status) implements StreamEventPayload {

        public static Heartbeat from(HeartbeatStatus status) {
            return new Heartbeat(Instant.now(), status);
        }
    }

    /** 변화가 없을 때 스트림이 살아 있음을 알리는 신호의 종류. 전송 형식(소문자 코드)은 api 계층이 정한다. */
    enum HeartbeatStatus {
        /** 재탐색했고 의미 있는 변화가 없다 */
        WATCHING("watching"),
        /** 재탐색 자체가 실패했다 — 이전 결과를 유지한다 */
        SEARCH_FAILED("search_failed"),
        /** 재탐색은 성공했지만 경로가 0건이다 */
        NO_ROUTES("no_routes");

        private final String code;

        HeartbeatStatus(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    record Update(Instant timestamp, List<Route> routes, String changeReason) implements StreamEventPayload {}

    record Complete(Instant timestamp, String reason, long durationSeconds) implements StreamEventPayload {}
}
