# A-1: 경로 탐색 실시간 SSE 스트림

> 작업일: 2026-04-22
> 담당 Phase: ROADMAP.md A-1
> 공수: 실측 약 4시간 (예상 2일 → 단축)
> 커밋: TBD

---

## 1. 배경 (Why)

### 1-1. "정적 경로 안내" 의 한계
기존 `/api/routes` 는 1회성 요청/응답 구조.

```
사용자 검색 → 경로 4개 응답 → 끝
```

문제:
- 사용자가 이동을 시작한 뒤 **외부 상태가 변해도** 알 수 없음
- 따릉이 재고가 급감해 추천 경로가 더 이상 최적이 아니어도 그대로 안내
- MaaS 의 본질("이동 중 가이던스")에서 멀어짐

### 1-2. 목표
경로 탐색 결과를 **실시간 스트림** 으로 공급해 이동 중에도 최신 상태 반영.

```
초기 응답 → 30초 간격 재탐색 → 변화 감지 시 PUSH
                                ↓ 없으면 HEARTBEAT
5분 후 자동 COMPLETE
```

---

## 2. 설계 결정

### 2-1. Spring MVC + Flux<ServerSentEvent> (vs WebFlux 전환)

| 선택지 | 장점 | 단점 | 채택 |
|--------|------|------|------|
| SseEmitter (전통 MVC) | 간단 | 수동 스레드 관리, 레거시 스타일 | ❌ |
| **Flux<ServerSentEvent<T>>** ⭐ | Reactor 친화, 이미 사용 중 | — | ✅ |
| WebFlux 전체 전환 | 완전 reactive | 전체 아키텍처 변경, 과도 | ❌ |

**채택 이유:** Spring MVC 에서도 `Flux<ServerSentEvent<T>>` 반환 타입을 지원한다
(내부적으로 ResponseBodyEmitter). 기존 WebClient + Reactor 체인 위에 SSE 를
얹을 수 있어 **프레임워크 전환 없이** 실시간 스트리밍 가능.

### 2-2. 폴링 vs 이벤트 기반 push

| 선택지 | 복잡도 | 반응성 | 채택 |
|--------|--------|--------|------|
| **30초 폴링** ⭐ | 낮음 | 30초 | ✅ MVP |
| ApplicationEvent + 구독자 | 높음 | 즉시 | ❌ |
| Redis Pub/Sub | 높음 | 즉시 | ❌ |

**채택 이유:**
- 따릉이 snapshot 이 이미 30초 TTL 로 갱신 → 폴링 주기와 자연스레 일치
- 이벤트 기반은 별도 인프라(Redis 또는 Spring ApplicationEventPublisher) 필요
- Phase 2 (B-1 Event-Driven) 로 확장 여지만 기록

### 2-3. 변화 감지 규칙

```java
String changeReason(List<Route> prev, List<Route> cur) {
    if (cur.isEmpty()) return null;
    if (prev == null) return "초기 결과 도착";

    Route prevRec = 추천경로(prev);
    Route curRec = 추천경로(cur);

    if (!prevRec.routeId().equals(curRec.routeId()))
        return "추천 경로 변경 (TRANSIT_ONLY → MOBILITY_FIRST_TRANSIT)";
    if (Math.abs(prevRec.totalMinutes() - curRec.totalMinutes()) >= 2)
        return "추천 경로 소요시간 N분 변화";
    return null;  // HEARTBEAT
}
```

**3가지 기준:**
1. 추천 경로 ID 변경 (가장 강한 신호)
2. 추천 경로 소요시간 ±2분 이상 (러시아워 변동 감지)
3. 그 외 → HEARTBEAT

### 2-4. 스트림 종료 조건

```java
return flux
    .take(Duration.ofMinutes(5))         // 최대 5분 자동 종료
    .doOnCancel(() -> log "연결 끊김")    // 클라이언트 cancel 감지
    .doFinally(signal -> log "종료")     // 모든 종료 신호
```

- **5분 타임아웃** — MaaS 이동 시간 커버
- **클라이언트 연결 끊김** — EventSource close 즉시 감지
- **COMPLETE 이벤트** — 자연 종료 시 마지막 이벤트

### 2-5. Hexagonal — application 이 SSE 프로토콜을 모름

```
application/RouteStreamService.java
  └─ Flux<StreamEventPayload> stream(...)   ← 순수 Reactor, SSE 무관

api/RouteStreamController.java
  └─ Flux<ServerSentEvent<StreamEventPayload>>
       .map(payload -> ServerSentEvent.builder(payload).event(type).build())
```

**이점:** application 레이어는 어떤 프로토콜(SSE/WebSocket/gRPC stream)로 공급될지
모른다. Controller 가 감싸는 방식만 교체하면 같은 로직을 다른 전송 수단으로 재사용.

### 2-6. 이벤트 타입 — sealed interface

Java 21 sealed interface 로 4가지 이벤트 close-set:

```java
public sealed interface StreamEventPayload {
    record Initial(Instant timestamp, List<Route> routes) implements ...
    record Heartbeat(Instant timestamp, String status) implements ...
    record Update(Instant timestamp, List<Route> routes, String changeReason) implements ...
    record Complete(Instant timestamp, String reason, long durationSeconds) implements ...
}
```

Controller 의 pattern matching 이 **새 이벤트 타입 추가 시 컴파일 에러** 로 누락 감지:

```java
String eventName = switch (payload) {
    case Initial ignored -> "INITIAL";
    case Heartbeat ignored -> "HEARTBEAT";
    case Update ignored -> "UPDATE";
    case Complete ignored -> "COMPLETE";
    // 새 이벤트 추가하면 컴파일 에러 → 여기도 추가해야 함
};
```

---

## 3. 구현 (What)

### 3-1. 변경된 파일

**신규:**
- `application/.../route/StreamEventPayload.java` — sealed interface 4종
- `application/.../route/RouteStreamService.java` — Flux 조립 + @Observed + 메트릭
- `api/.../route/RouteStreamController.java` — SSE 엔드포인트
- `application/.../route/RouteStreamServiceTest.java` — 변화 감지 단위 테스트 6개

**수정:**
- `api/src/main/resources/application.yml` — `spring.mvc.async.request-timeout=600000`

### 3-2. 엔드포인트 스펙

```
GET /api/routes/stream
  ?originLat=37.4979&originLng=127.0276
  &destLat=37.5570&destLng=126.9240
  &searchMode=OPTIMAL
  &recommendationPreference=RELIABILITY
  &mobility=DDAREUNGI  (optional, multi)
  &wheelchairAccessible=false
  &walkingSpeedKmh=4.5

Accept: text/event-stream
```

응답:
```
event:INITIAL
data:{"timestamp":"...","routes":[...]}

event:HEARTBEAT
data:{"timestamp":"...","status":"watching"}

event:UPDATE
data:{"timestamp":"...","routes":[...],"changeReason":"추천 경로 소요시간 3분 변화"}

event:COMPLETE
data:{"timestamp":"...","reason":"timeout","durationSeconds":300}
```

### 3-3. 관측성 (Prometheus + @Observed)

```
# 카운터
navigation_route_stream_opened_total                    (스트림 누적 오픈)
navigation_route_stream_event_total{type=update}        (UPDATE 이벤트 수)
navigation_route_stream_event_total{type=heartbeat}     (HEARTBEAT 수)

# 게이지
navigation_route_stream_active                          (현재 열린 스트림 수)

# @Observed 자동 생성
navigation_route_stream_seconds_count/sum/max
navigation_route_stream_active_seconds_*
```

### 3-4. 핵심 코드 — Flux 조립

```java
Flux<StreamEventPayload> initial = findRoutesMono(...)
    .doOnNext(lastRoutes::set)
    .map(routes -> new Initial(Instant.now(), routes))
    .flux();

Flux<StreamEventPayload> polls = Flux.interval(POLL_INTERVAL, POLL_INTERVAL)
    .flatMap(tick -> findRoutesMono(...).onErrorResume(e -> Mono.empty()))
    .map(current -> {
        String reason = changeReason(lastRoutes.get(), current);
        if (reason != null) {
            lastRoutes.set(current);
            return new Update(Instant.now(), current, reason);
        }
        return Heartbeat.watching();
    });

return Flux.concat(initial, polls)
    .take(MAX_STREAM_DURATION)
    .concatWith(Flux.defer(() -> Flux.just(new Complete(...))))
    .doOnSubscribe(sub -> activeStreams.incrementAndGet())
    .doOnCancel(() -> log "연결 끊김")
    .doFinally(sig -> activeStreams.decrementAndGet());
```

---

## 4. 검증 & 성과 (Result)

### 4-1. 단위 테스트 (6/6 통과)
- 이전 결과 없으면 "초기 결과 도착"
- 추천 경로 routeId 변경 시 UPDATE 사유
- 소요시간 2분 이상 차이 시 UPDATE
- 1분 차이는 HEARTBEAT
- 추천 경로 해제 감지
- 빈 결과 처리

### 4-2. 수동 E2E 실측

```bash
curl -sN "http://localhost:8081/api/routes/stream?originLat=37.4979&originLng=127.0276&destLat=37.5570&destLng=126.9240&searchMode=OPTIMAL"
```

**결과:**
```
HTTP/1.1 200
Content-Type: text/event-stream
Transfer-Encoding: chunked

event:INITIAL  data:{"timestamp":"2026-04-22T08:03:23.787Z","routes":[...]}
event:UPDATE   data:{"routes":[...],"changeReason":"추천 경로 소요시간 ..."}
event:UPDATE   data:{"routes":[...],"changeReason":"..."}
```

32초 간 3개 이벤트 수신 (초기 + UPDATE 2회). 재탐색이 매 30초마다 발동하고
변화 감지 로직이 의미 있는 차이를 UPDATE 로 판정.

### 4-3. Prometheus 메트릭 실측

```
navigation_route_stream_active = 1.0          (현재 열린 스트림)
navigation_route_stream_opened_total = 2.0    (누적 오픈 수)
navigation_route_stream_event_total{type=update} = 3.0    (푸시된 UPDATE)
navigation_route_stream_seconds_count = 2     (@Observed 자동)
```

### 4-4. 서버 로그 증거

```
[Stream] 새 스트림 시작 — (37.4979,127.0276) → (37.557,126.924) pref=RELIABILITY
[Stream] 클라이언트 연결 끊김 — duration=32s
[Stream] 종료 signal=cancel
```

`doOnCancel` 이 클라이언트 연결 해제를 정확히 감지하고 리소스 회수.

---

## 5. 한계 & 다음 단계

### 5-1. 폴링 기반의 반응성 한계
30초 주기라 **즉시 반영** 아님. 따릉이 재고가 29초에 급감해도 다음 tick 까지 대기.
→ **B-1 Event-Driven** 에서 재고 변경 이벤트 기반 push 로 확장 예정.

### 5-2. 동시 연결 규모
각 연결이 30초마다 재탐색 → 서버 CPU 부담. 현재 Geohash 캐시(80% hit) 로 완화되지만
수천 connection 급 되면 스케일 아웃 또는 SSE 대신 WebSocket + broker 고려.

### 5-3. 재연결/resume 미지원
연결 끊겼다가 재연결 시 이전 상태를 이어받지 못함. `Last-Event-ID` 헤더로 resume
구현 가능하지만 MVP 범위 외.

### 5-4. 스트림 종료 reason 불완전
현재 `Complete` 이벤트의 reason 이 항상 "timeout". 사용자가 `cancel` 한 경우엔
이벤트 자체가 전달 안 됨 (연결 이미 끊겼으므로). 이건 SSE 프로토콜의 자연스러운 한계.

---

## 6. 기록

### 30초 버전
> "MaaS 의 본질은 '이동 중 가이던스' 입니다. 기존 1회성 경로 응답을 **Reactor Flux 기반 SSE**
> 로 확장해 초기 응답 + 30초 재탐색 + 변화 감지 시 UPDATE push 를 구현했습니다.
> Spring MVC 를 WebFlux 로 전환하지 않고 `Flux<ServerSentEvent<T>>` 반환만으로
> 비동기 스트리밍이 가능합니다."

### 3분 버전
1. **설계 축**
   - Spring MVC + Flux 조합으로 프레임워크 전환 없이 스트리밍
   - `Flux.concat(초기, interval)` + `take(5m)` + `doOnCancel` 의 조합으로
     정상 종료 + 비정상 종료 둘 다 처리
   - application 레이어는 SSE 무관, Controller 가 ServerSentEvent 래핑
2. **sealed interface + pattern matching**
   - Java 21 기능으로 이벤트 타입 4종 close-set
   - 새 이벤트 추가 시 컴파일 에러로 Controller switch 누락 방지
3. **관측성**
   - Gauge (현재 연결 수), Counter (UPDATE/HEARTBEAT), @Observed (duration p95)
   - "SSE 연결 부하" 를 실시간 Grafana 대시보드로 추적 가능
4. **자기 한계 인지**
   - 폴링 기반 → B-1 Event-Driven 으로 확장 여지 명시
   - 수천 connection 스케일 아웃 시 broker 필요성 언급

---

## 7. 관련 문서
- [ROADMAP.md](../roadmap/ROADMAP.md) — A-1 항목
- [T-3 Resilience4j 개선](./2026-04-22-T3-resilience4j.md) — 재탐색 내부 장애 대응
- 향후 [B-1 Event-Driven](../roadmap/ROADMAP.md#b-1) — 폴링 → 이벤트 기반 push 로 진화
