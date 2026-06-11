# ADR-008: Virtual Thread + Reactor 적재적소 분리 (블로킹 격리 전략)

## Status
Accepted (2026-06-11)
Extends: ADR-001 (WebClient on Spring MVC)

## Context

ADR-001 의 구조 — Spring MVC + WebClient(Reactor) + 최종 `.block()` — 는
구현 단순성과 외부 API 병렬화를 동시에 얻었지만, 동시성 천장이 남아 있었다:

```
Tomcat platform 워커: 기본 200개
경로 탐색 1건 = 외부 API 5종 대기 (평균 ~2초, 최대 45초 timeout)
→ Controller 의 .block() 이 워커를 대기 내내 점유
→ 초당 ~100 요청이면 워커 포화 → 큐 대기 → p95 폭증
```

해결 후보 2가지:

| 후보 | 내용 | 비용 |
|------|------|------|
| A. 완전 reactive 전환 | Controller 가 `Mono<ResponseEntity>` 반환, block() 전부 제거 | Port 시그니처·예외 흐름·SSE 경로 전면 수정 (~1주) |
| B. Virtual Thread 활성화 | Tomcat 워커를 가상 스레드로 — block() 비용 자체를 제거 | 설정 1줄 |

## Decision

**B 채택 — `spring.threads.virtual.enabled: true` (Spring Boot 3.2+ 공식 통합).**

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

가상 스레드는 블로킹 시 carrier thread 를 반납하므로, Controller 의
`.block()` 이 점유하는 것은 ~KB 단위 가상 스레드일 뿐이다.
동시 처리량이 워커 수(200)가 아닌 메모리로 제한된다 — ADR-001 의
"동기 코드 단순성" 을 유지한 채 동시성 천장만 제거.

### 역할 분리 원칙 (적재적소)

| 관심사 | 도구 | 이유 |
|--------|------|------|
| 외부 API 5종 병렬 합성 | Reactor (`Mono.zip`) | 합성·에러 전파·Resilience4j Operator 통합 |
| SSE 폴링 스트림 | Reactor (`Flux.interval` + `take`) | 시간 기반 발행·라이프사이클 hook |
| 요청 처리(Controller) | Virtual Thread | 블로킹 코드 단순성 + 동시 처리량 |
| LLM 블로킹 호출 격리 | `boundedElastic` (현행 유지) | narrative 는 추천 1건만 호출 — 풀 한계 무관 |

**Reactor 를 버리지 않는 이유**: `Mono.zip` 의 합성 표현력과
Resilience4j Reactor Operator(Retry→CB→Fallback 체인)는 Virtual Thread 로
대체하면 코드가 더 길어진다 (StructuredTaskScope 는 Java 21 에서 preview).

**완전 reactive 로 가지 않는 이유**: 전면 수정 비용(~1주) 대비,
Virtual Thread 1줄이 동일한 동시성 목표를 달성한다. B2B rate-limit 필터
체인 등 reactive 전제가 생기면 그때 재평가한다.

## Consequences

### 좋아지는 것
- Controller `.block()` 의 워커 점유 비용 제거 — 동시 요청 천장 해소
- `@Async` / `@Scheduled` (캐시 purge 등) 도 가상 스레드로 자동 전환
- 코드 변경 0줄 — 기존 동기 스타일 유지

### 주의점 (검토 완료)
- **synchronized pinning**: Java 21 은 synchronized 블록 안에서 블로킹하면
  carrier 가 pin 된다. 본 코드의 synchronized 2곳(DdareungiApiClient,
  KickboardApiClient snapshot 갱신)은 블록 내부가 캐시 확인 + Mono 객체
  생성뿐 (블로킹 I/O 없음) → pinning 영향 없음. Java 24(JEP 491)부터는
  이 제약 자체가 사라진다.
- **ThreadLocal 비용**: 가상 스레드는 수가 많아 ThreadLocal 누적에 민감.
  본 프로젝트는 MDC(traceId) 외 ThreadLocal 사용 없음 — Micrometer
  context-propagation 이 관리.

### 검증 방법
- k6 `stress` 시나리오 (50→200 VU) Before/After p95 비교
- `/actuator/metrics/jvm.threads.live` 로 platform thread 수 관찰
  (활성화 후 요청 폭주에도 platform thread 가 늘지 않아야 함)
