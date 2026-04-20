# ADR-001: WebClient on Spring MVC (not WebFlux)

## Status
Accepted (2026-03-04)

## Context

Black Mamba는 경로 탐색 1건에 **외부 API 4개를 병렬 호출**해야 한다:
- ODsay (대중교통)
- Tmap (보행자/이동수단)
- 따릉이 (자전거 정류소)
- 네이버 (지오코딩/장소)

각 API 지연이 수백 ms 단위라 순차 호출 시 p95 응답시간이 3~5초로 악화된다.
반면 **내부 비즈니스 로직**은 CPU 중심 계산(점수, 후보 선택, 거리)이라 reactive 혜택이 작다.

또한 프론트엔드는 React + axios 기반의 일반 REST 클라이언트이고,
서버 사이드 push(SSE/WebSocket)가 현재 요구사항에 없다.

## Decision

**Spring MVC 위에 WebClient 조합** 사용.

- Controller: 일반 `@RestController` (동기 시그니처)
- 외부 API 호출: `WebClient` (Mono/Flux)
- 병렬 호출: `Mono.zip(odsay, tmap, ddareungi)` 로 동시 실행
- 최종 수렴: `.block(Duration.ofSeconds(30))` 으로 동기 반환

```java
@GetMapping
public ResponseEntity<Map<String, Object>> searchRoutes(...) {
    List<Route> routes = routeOptimizationService
            .findRoutes(origin, destination, ...)
            .block(ROUTE_SEARCH_TIMEOUT);   // ← 단일 block() 지점
    return ResponseEntity.ok(...);
}
```

내부 서비스는 일반 Java이고, 외부 API만 reactive chain으로 감싸서 병렬 처리한다.

## Consequences

**Pro:**
- 내부 비즈니스 로직은 **일반 Java** → 학습 곡선 낮음, 디버깅 쉬움 (Thread dump, AOP, IDE Debug)
- 외부 API 병렬 호출로 **p95 1.76s** 달성 (순차였다면 3~5s 예상)
- 프론트엔드는 REST 그대로 소비 (React axios)
- Spring MVC 생태계 완비 (`@MockMvc`, `@ExceptionHandler` 등)

**Con:**
- `.block()` 1곳 있음 → **명시적 timeout 필수** (30초)
- 완전 WebFlux 대비 스레드 수 많음 (Tomcat 200 + Reactor 4~8)
- 두 모델 혼용 → 반드시 MVC 레이어에서 block 해줘야 함 (코드 컨벤션 강제)

## Alternatives Considered

### 대안 A: Full WebFlux (end-to-end reactive)
- 장점: 완전 논블로킹, 최소 스레드, 이론적 최고 성능
- 단점:
  - 팀 Reactive 숙련도 낮음, 학습 곡선
  - 디버깅 어려움 (stacktrace가 Reactor operator들로 도배)
  - WebFlux용 Security/Validation 재학습 필요
  - 현재 트래픽 규모(로컬, 포트폴리오)에서 이득 미미
- **채택 안 함**

### 대안 B: Spring MVC + RestTemplate
- 장점: 가장 단순, 동기 코드만
- 단점:
  - 외부 API 병렬 호출이 복잡 (CompletableFuture.allOf 수동 관리)
  - Connection pool / backpressure 직접 처리
  - RestTemplate은 유지보수 모드 (새 기능 없음)
- **채택 안 함**

### 대안 C: Spring Cloud Gateway + Reactor
- 장점: Gateway 앞단에서 reactive 완결
- 단점: 오버스펙 (모놀리스 단계에서 불필요)
- **채택 안 함** (MSA 전환 시 재검토)

## Related
- Commit: `73fa263`, `a3d8f86`, `0f7b...`
- Timeout 추가 커밋: `47931c5`
- 관련 설정: `api/src/main/java/.../RouteController.java:79`
- See also: ADR-005 (Reactor Context Propagation) — MVC→Reactor 전환 시 MDC 전파 이슈
