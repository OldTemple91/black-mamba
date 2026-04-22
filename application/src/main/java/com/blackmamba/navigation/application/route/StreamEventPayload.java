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
 *   <li>{@link Heartbeat} — 30초 간격, 변화 없음을 알림</li>
 *   <li>{@link Update} — 추천 경로 변경 감지, 새 경로 + 사유</li>
 *   <li>{@link Complete} — 종료 (timeout / disconnect / error)</li>
 * </ul>
 */
public sealed interface StreamEventPayload {

    Instant timestamp();

    record Initial(Instant timestamp, List<Route> routes) implements StreamEventPayload {}

    record Heartbeat(Instant timestamp, String status) implements StreamEventPayload {
        public static Heartbeat watching() {
            return new Heartbeat(Instant.now(), "watching");
        }
    }

    record Update(Instant timestamp, List<Route> routes, String changeReason) implements StreamEventPayload {}

    record Complete(Instant timestamp, String reason, long durationSeconds) implements StreamEventPayload {}
}
