# ADR-004: Loki 로그 포맷 — 평문 1라인 + Structured Metadata

## Status
Accepted (2026-04-17)

## Context

분산 추적 도입 후 애플리케이션 로그를 **Loki**로 보내기 시작했는데,
ERROR 로그의 **목록 뷰 가독성** 문제가 여러 번 발생했다.

### 시도한 접근과 그때마다의 문제

1. **첫 시도: JSON 로그 + `%ex`로 스택트레이스 포함**
   - 엔트리 하나가 5,000+ 자
   - Grafana Loki UI가 전체 문자열을 **한 줄에 표시** → 목록이 어지러움
   - "Escape newlines" 경고

2. **두 번째 시도: `%replace` 로 개행 이스케이프**
   - 여전히 라인이 길어 펼치기/접기 토글 의미 없음

3. **세 번째 시도: LogstashLayout + stack_trace 필드 분리**
   - Grafana가 JSON 원문 통째로 표시 (Loki UI는 기본 raw)

4. **최종 정답: loki4j 2.0 + `structuredMetadata`**
   - message는 **평문 1라인**으로 짧게
   - 스택트레이스는 **Structured Metadata** 필드로 분리
   - Grafana에서 **목록은 짧게, 펼치면 필드 표시**

## Decision

**Loki 로그는 평문 1라인 + Structured Metadata** 조합으로 구성.

### loki4j 2.0 설정 (`logback-spring.xml`)
```xml
<appender name="LOKI" class="com.github.loki4j.logback.Loki4jAppender">
  <!-- 스트림 식별 라벨 -->
  <labels>
    app=black-mamba
    host=${HOSTNAME}
    level=%level
  </labels>

  <!-- 목록에 표시되는 한 줄 -->
  <message>
    <pattern>
      %d{HH:mm:ss.SSS} | %-5level | traceId=%X{traceId:-} | %replace(%msg){'[\r\n]+','\\n'}%nopex
    </pattern>
  </message>

  <!-- 펼쳐야 보이는 부가 필드 -->
  <structuredMetadata>
    traceId=%mdc{traceId:-}
    spanId=%mdc{spanId:-}
    thread=%thread
    logger=%logger
    stack_trace=%replace(%xException){'[\r\n]+','\\n'}
  </structuredMetadata>
</appender>
```

### 핵심 포인트
- `%nopex` 로 PatternLayout 기본 스택트레이스 자동 포함 **억제**
- `%xException` 으로 스택트레이스를 별도 structured metadata 필드에 담음
- 메시지 내 개행은 `\\n` 문자로 **이스케이프** (한 줄 유지)

## Consequences

**Pro:**
- Grafana Loki UI 목록: **짧은 한 줄** (약 100~200자)
- 로그 펼치면 stack_trace, traceId 등 **Fields 섹션에서 확인**
- Loki 스토리지 효율: 라벨 카디널리티는 유지, 큰 텍스트는 metadata
- traceId 기반 Tempo 점프 버튼 정상 동작

**Con:**
- **운영 표준 관점에서 옳지만**, Loki 3.0+ 및 loki4j 2.0+ 버전 필요
- **loki4j 1.x 설정 문법과 비호환** (`structuredMetadataPattern` vs `structuredMetadata`)
- 스택트레이스는 Loki에서 바로 안 보임 → 펼치거나 Tempo/파일 로그 확인 필요

## Alternatives Considered

### 대안 A: JSON 로그 (LogstashLayout)
```json
{"ts":"...", "level":"ERROR", "message":"...", "stack_trace":"..."}
```
- 장점: 구조화됐고 Grafana `| json` 파서로 필드 추출 가능
- 단점:
  - **Grafana Loki UI 기본 뷰는 raw 문자열 전체 표시** → 목록 어지러움
  - `| json` 쿼리 매번 붙여야 함
  - 사용자 불만: "접어도 전체가 보인다"
- **채택 안 함**

### 대안 B: 스택트레이스 **Loki 미전송**, 파일/콘솔만
- 장점: Loki 엔트리 최소화
- 단점: Loki에서 디버깅 시 traceId 가지고 **파일 로그로 이동** 필요 → 불편
- **채택 안 함**

### 대안 C: MDC만 쓰고 로그 포맷 기본값
- 장점: 가장 단순
- 단점: Grafana에서 traceId 파싱 regex 작성해야 함 (derived field)
- **부분 채택**: MDC + 평문은 유지, metadata도 추가

### 대안 D: LogstashLayout + stack_trace 별도 필드
- 장점: 구조화
- 단점: Grafana Loki UI가 JSON 원문을 **한 줄로 표시**해 ui 혼란은 동일
- **채택 안 함**

## Consequences 실증

### Before (스택트레이스 포함 JSON)
```
{"ts":"2026-04-17 04:47:54,379","level":"ERROR",...,"stackTrace":"java.lang.NullPointer...\n\tat com..."}
```
- 엔트리 크기: 5,280 자
- Grafana UI: 전체 한 줄로 노출, 펼치기 의미 없음

### After (평문 + structured metadata)
```
Line:
05:39:45.649 | ERROR | traceId=69e1c7a1... | [전역 예외] NullPointerException: Cannot invoke...

Structured Metadata:
  traceId: 69e1c7a1...
  spanId:  5d15c71b...
  thread:  http-nio-8081-exec-8
  logger:  com.blackmamba.navigation.api.config.GlobalExceptionHandler
  stack_trace: java.lang.NullPointerException\n\tat com.blackmamba...
```
- 라인 크기: **161 자** (97% 축소)
- Grafana UI: 목록 깔끔, 펼치면 모든 필드 확인

## Related
- Commit: `0c115a5` (nopex), 버전 업그레이드 `c63cc0a`
- 파일: `api/src/main/resources/logback-spring.xml`
- 개선 기록: `docs/improvements/2026-04-17-C-otlp-protobuf-tracing.md`
- See also: ADR-005 (MDC에 traceId 넣는 방법)
