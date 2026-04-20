# ADR-006: OTLP/gRPC over OTLP/HTTP (분산 추적 전송 프로토콜)

## Status
Accepted (2026-04-20)
Supersedes: Zipkin HTTP (초기), OTLP/HTTP (중간 단계)

## Context

분산 추적 전송 프로토콜은 3단계로 진화:

1. **Zipkin HTTP** (초기): Brave + zipkin-reporter → JSON → Tempo:9411
2. **OTLP/HTTP** (2026-04-17): OTel bridge → Protobuf → Tempo:4318
3. **OTLP/gRPC** (2026-04-20, 현재): OTel bridge → Protobuf over gRPC → Tempo:4317

### 왜 단계적으로 왔나

**Zipkin → OTLP/HTTP 시점 (2026-04-17)**에 gRPC까지 가고 싶었지만:
- Spring Boot 3.3의 `management.otlp.tracing` 속성에 `transport: grpc` 옵션 없음
  (3.4부터 공식 지원)
- 수동 `OtlpGrpcSpanExporter` Bean을 `@Primary`로 등록 시도 → **auto-config의 HTTP exporter와 공존**
- 결과: 두 exporter가 동시 동작 → **중복 전송 + Connection reset 에러**

**Spring Boot 3.5.13 업그레이드 후 (2026-04-20)** 공식 속성 활용 가능:
```yaml
management:
  otlp:
    tracing:
      transport: grpc   # ← Spring Boot 3.5+ 공식 지원
```

## Decision

**OTLP/gRPC 채택** (Spring Boot 3.5.13 기반).

### 최종 설정
```yaml
management:
  otlp:
    tracing:
      endpoint: http://tempo:4317
      transport: grpc
      compression: gzip
```

### 의존성
```gradle
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
```

## Consequences

**Pro:**

### 1. OpenTelemetry 표준 준수
- Zipkin은 비표준. OTLP는 CNCF OTel 공식 전송 포맷
- 백엔드 교체 자유 (Tempo → Jaeger → Datadog → New Relic)

### 2. 전송 효율
| 관점 | Zipkin HTTP/JSON | OTLP/HTTP/Protobuf | OTLP/gRPC |
|------|-----------------|-------------------|-----------|
| 페이로드 (span당) | ~400 B | ~150 B | ~150 B (동일) |
| TCP 연결 | Keep-Alive | Keep-Alive | **영구 HTTP/2 연결 1개** |
| 헤더 오버헤드 | 매 배치 | 매 배치 | **HPACK 압축 + 캐시** |
| 멀티플렉싱 | 제한적 | HTTP/2 가능 | **HTTP/2 전용, 확실** |

### 3. 실측 개선
- 동일 요청량 1분 기준, Tempo 수신 트레이스: **37건 → 50건** (+35%)
- 전송 에러 로그: **0건 유지**

### 4. 향후 확장 여지
- **Bidirectional streaming** 가능 (현재 미사용, ROADMAP B-1 실시간 재탐색 시 활용)
- 서버 flow control, deadline 전파 등 gRPC 표준 기능

**Con:**

### 1. HTTP/2 강제
- 중간 프록시(nginx, ALB 등)가 **HTTP/2 지원 필수**
- 방화벽에서 gRPC를 일반 HTTP로 오인해 차단 가능

### 2. 디버깅 난이도
- Zipkin/HTTP는 `curl -v http://tempo:4318/v1/traces` 로 쉽게 체크
- gRPC는 `grpcurl` 같은 전용 툴 필요

### 3. Kubernetes 환경 이슈
- gRPC는 **L4 로드밸런싱** 필요 (L7 sticky 복잡)
- 현재 로컬 Docker에서는 문제 없지만 **실 운영 시 고려 포인트**

### 4. Spring Boot 3.5 의존
- 3.4 미만에서는 수동 Bean 등록 필요 (복잡도 ↑)
- 이 요구사항이 **T-4 업그레이드의 근거**가 됨

## Alternatives Considered

### 대안 A: Zipkin HTTP 유지
- 장점: Brave 생태계, 이미 동작
- 단점:
  - **비표준** (OTel 아님)
  - JSON 페이로드 큼
  - 벤더 종속
- **채택 안 함** (2026-04-17에 이미 포기)

### 대안 B: OTLP/HTTP 유지
- 장점: Protobuf + 설정 간단 + HTTP 프록시 호환
- 단점:
  - HTTP/2 사용은 라이브러리 선택에 의존 (OkHttp 기본 HTTP/1.1)
  - gRPC 고유 기능 활용 불가 (streaming, flow control)
- **채택 안 함**: Spring Boot 3.5로 올라간 이상 gRPC까지 가는 게 자연스러움

### 대안 C: OpenTelemetry Collector 앞단 배치
```
[app] → [OTel Collector] → [Tempo]
```
- 장점: 백엔드 교체 시 app 무변경
- 단점:
  - 인프라 1개 추가 (Collector 자체도 모니터링 필요)
  - 현재 규모에선 과함
- **ROADMAP에 추후 검토 항목으로**

## 트레이드오프 정리

| 기준 | 결정 |
|------|------|
| 표준 준수 | OTLP 채택 (Zipkin 버림) |
| 페이로드 크기 | Protobuf로 이미 최적화 |
| 연결 효율 | gRPC로 최대화 |
| 디버깅 편의성 | **희생**: HTTP보다 어려움 |
| 중간 프록시 호환성 | **요구사항**: HTTP/2 필수 |
| 라이브러리 의존 | Spring Boot 3.5+ (별도 T-4 업그레이드로 확보) |

## Related
- Commits:
  - Zipkin → OTLP/HTTP: `01a6960` (2026-04-17)
  - OTLP/HTTP → OTLP/gRPC: `f51ef4e` (2026-04-20, T-4 Phase 1)
- 파일: `api/src/main/resources/application.yml`
- 개선 기록:
  - `docs/improvements/2026-04-17-C-otlp-protobuf-tracing.md`
  - `docs/improvements/2026-04-20-T4-phase1-springboot-3.5-otlp-grpc.md`
- See also: ADR-005 (Reactor Context Propagation — traceId 전파 전제조건)
