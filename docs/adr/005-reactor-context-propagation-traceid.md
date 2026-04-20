# ADR-005: Reactor Context Propagation 활성화 (MDC traceId 전파)

## Status
Accepted (2026-04-17)

## Context

분산 추적 도입 후 **Controller에서 찍은 로그**에는 traceId가 잘 나오는데,
**외부 API 호출(`OdsayRouteClient`, `TmapPedestrianClient`)**에서 찍는 로그는 `traceId=` (빈 값) 로 남았다.

```
[Controller]      traceId=69e1bc6b...    ← 정상
[OdsayRouteClient] traceId=              ← 비어있음 (Reactor 스레드)
```

### 원인 분석

1. **Spring MVC 요청 스레드**: `ServerHttpObservationFilter` 가 MDC에 traceId 주입
2. **Reactor 체인에서 `flatMap` 시 스레드 전환**: `reactor-http-nio-*` 스레드로 이동
3. **MDC는 `ThreadLocal` 기반**: 스레드 간 자동 복사 안 됨
4. Reactor 스레드에서 `MDC.get("traceId")` → null → 로그에 빈 값

### 영향
- Grafana에서 외부 API 로그의 **Tempo 점프 링크 미생성**
- 같은 요청의 span들이 로그로 연결 안 됨 → 분산 추적 가치 반감
- 사용자 피드백: "derived field로 traceId 추출이 안 된다"

## Decision

**Reactor Context Propagation을 자동 활성화**.

### 설정 (application.yml)
```yaml
spring:
  reactor:
    context-propagation: auto
```

### 내부 동작
1. `io.micrometer.context.ContextRegistry` 가 자동 등록
2. Spring Boot가 `Hooks.enableAutomaticContextPropagation()` 호출
3. Reactor operator (flatMap, map 등) 경계에서 **MDC 자동 복사**
4. Reactor 스레드에서도 MDC.get("traceId") 정상 반환

### 결과
```
[Controller]       traceId=69e1bc6b23c712c716c38e6ec9f85145
[OdsayRouteClient] traceId=69e1bc6b23c712c716c38e6ec9f85145  ← 동일
[TmapPedestrianClient] traceId=69e1bc6b23c712c716c38e6ec9f85145
```

같은 요청의 모든 로그가 **동일 traceId** 공유.

## Consequences

**Pro:**
- Reactor 체인 전체에 traceId 전파 → 분산 추적 완성
- Grafana Loki derived field 정상 동작 → Tempo 점프 링크 활성화
- 외부 API별 span이 로그와 연결됨

**Con:**
- **미세한 성능 비용**: 스레드 경계마다 Context 복사 오버헤드 (나노초 수준, 실무 무시 가능)
- **Reactor Context 전체 복사**: MDC 외 다른 Context도 전파됨 (의도치 않은 전파 가능성, 우리는 문제 없음)
- Spring Boot 3.2+ 필요 (우리 프로젝트는 3.3+)

## Alternatives Considered

### 대안 A: 매 operator마다 수동 MDC 복사
```java
Mono.deferContextual(ctx -> {
    MDC.put("traceId", ctx.get("traceId"));
    try {
        return businessLogic();
    } finally {
        MDC.remove("traceId");
    }
});
```
- 장점: 세밀한 제어
- 단점: **모든 reactive 경계마다 반복** → 코드 오염 심각
- **채택 안 함**

### 대안 B: Brave MDCScopeDecorator
- 장점: Brave 프레임워크 내장 솔루션
- 단점:
  - Brave 전용, OTel 전환 시 재작업
  - 설정 복잡 (Micrometer와 별개)
- **채택 안 함** (우리는 OTel 기반으로 이미 전환)

### 대안 C: 무시 (Controller 로그만 활용)
- 장점: 설정 불필요
- 단점:
  - **외부 API 로그가 분산 추적에서 빠짐**
  - 병목 지점 파악 불가 (ODsay가 느린지 Tmap이 느린지 로그로 알 수 없음)
- **채택 안 함**

## Related
- Commit: `4fcecc0`
- 파일: `api/src/main/resources/application.yml` 의 `spring.reactor.context-propagation`
- Improvement 기록: `docs/improvements/2026-04-17-C-otlp-protobuf-tracing.md`
- See also: ADR-001 (WebClient on MVC — reactive chain의 배경)
- See also: ADR-004 (Loki 로그 포맷 — traceId가 전파되어야 의미 있음)
