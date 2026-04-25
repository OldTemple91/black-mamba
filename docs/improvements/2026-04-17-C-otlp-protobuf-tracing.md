# C: Zipkin/HTTP/JSON → OTLP/HTTP/Protobuf 트레이스 전송 전환

> 작업일: 2026-04-17
> 담당 Phase: ROADMAP.md 비즈니스 확장 외 "모니터링 스택 표준화"
> 공수: 실측 1.5시간
> 커밋: TBD

---

## 1. 배경 (Why)

### 기존 구조
```
[app]  --(Zipkin HTTP/1.1 + JSON)-->  [Tempo :9411 zipkin receiver]
```

### 문제점
1. **Zipkin 포맷은 비표준** — OpenTelemetry 생태계의 1급 시민 아님
2. **JSON 페이로드가 크다** — 스택트레이스 포함 span이 수 KB
3. **벤더 종속** — Tempo → Jaeger/Datadog/New Relic 이전 시 전송 포맷 재작업 필요
4. **Spring Boot 3.3 표준은 OTLP** — `micrometer-tracing-bridge-otel`로 이전 중

---

## 2. 기존 구조 (Before)

### 의존성
```gradle
implementation 'io.micrometer:micrometer-tracing-bridge-brave'
implementation 'io.zipkin.reporter2:zipkin-reporter-brave'
```

### 설정
```yaml
management:
  zipkin:
    tracing:
      endpoint: http://tempo:9411/api/v2/spans  # Zipkin HTTP
```

### 동작
- 배치 단위 span → **JSON 직렬화** → Zipkin v2 HTTP API로 POST
- 매 배치마다 TCP 재연결 비용 (HTTP/1.1 연결 재사용 제한적)

---

## 3. 개선 방향 (How)

### 대안 평가

| 옵션 | Protobuf | HTTP/2 | 표준 | Spring 3.3 지원 | 채택 |
|------|----------|--------|------|----------------|------|
| Zipkin HTTP/JSON | ❌ | 부분 | ❌ | ✅ | Before |
| **OTLP/HTTP/Protobuf** | ✅ | ✅ (OkHttp 자동) | ✅ | **✅ 공식** | **✅ After** |
| OTLP/gRPC | ✅ | ✅ | ✅ | ❌ (`transport` 3.4부터) | ROADMAP T-4로 연기 |

### 왜 OTLP/HTTP로 결정했나
1. **Spring Boot 3.3에서 `management.otlp.tracing.endpoint` 공식 지원** — 설정 한 줄로 전환
2. **핵심 가치 90% 달성**:
   - Protobuf 직렬화 ✅ (JSON 대비 페이로드 감소)
   - 표준 OpenTelemetry 프로토콜 ✅ (벤더 중립)
   - OkHttp 기반이라 HTTP/2 자동 ✅
3. **gRPC 시도 → Spring Boot 3.3에서 실패**:
   - `transport: grpc`는 Spring Boot 3.4부터 공식 속성
   - `OtlpGrpcSpanExporter`를 @Primary Bean으로 수동 등록 → auto-config의 HTTP exporter와 공존하여 중복 전송 + Connection reset 에러 발생
   - 완전 전환하려면 auto-config 전체 disable + 수동 SDK 구성 → 과한 복잡도
4. **T-4 (Spring Boot 3.5 업그레이드)** ROADMAP 추가 → 그때 `transport: grpc` 한 줄로 완결

---

## 4. 구현 (What)

### 4-1. 변경된 파일
- `api/build.gradle` — 의존성 교체 (Brave → OTel)
- `api/src/main/resources/application.yml` — `zipkin.tracing.endpoint` → `otlp.tracing.endpoint`
- `docker-compose.yml`
  - Tempo 포트 4317/4318 호스트 노출
  - app 환경변수 `MANAGEMENT_OTLP_TRACING_ENDPOINT`

### 4-2. 핵심 코드 변경

**의존성 교체:**
```gradle
// Before
implementation 'io.micrometer:micrometer-tracing-bridge-brave'
implementation 'io.zipkin.reporter2:zipkin-reporter-brave'

// After
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
```

**application.yml:**
```yaml
# Before
management:
  zipkin:
    tracing:
      endpoint: http://tempo:9411/api/v2/spans

# After
management:
  otlp:
    tracing:
      endpoint: http://tempo:4318/v1/traces
      compression: gzip
```

**docker-compose.yml — Tempo 포트 추가 (이미 tempo.yml에 설정된 receiver를 호스트에 노출):**
```yaml
tempo:
  ports:
    - "3200:3200"
    - "9411:9411"   # Zipkin (legacy, 필요 시 제거 가능)
    - "4317:4317"   # OTLP/gRPC ← 향후 T-4에서 사용
    - "4318:4318"   # OTLP/HTTP ← 현재 사용
```

### 4-3. 테스트
기존 `RouteControllerTest` 등은 tracing 설정과 무관하게 통과 (22개 테스트 모두 OK).

---

## 5. 검증 & 성과 (Result)

### 전송 동작 검증
```bash
# 11건 요청 (정상 + 에러 섞어서)
for i in $(seq 1 8); do
  curl -s -o /dev/null "http://localhost:8081/api/routes?..."
done
for i in $(seq 1 3); do
  curl -s -o /dev/null "http://localhost:8081/api/debug/boom"
done
```

### Tempo 수신 결과

| 항목 | Before (Zipkin) | After (OTLP/HTTP) |
|------|----------------|-------------------|
| **전송 에러 로그** | 0건 (안정적) | **0건 (안정적)** |
| **Tempo 트레이스 수신** | 정상 | **37건 정상** |
| **프로토콜** | Zipkin v2 JSON | **OTLP Protobuf** |
| **연결 재사용** | 제한적 | **OkHttp Keep-Alive** |

### Tempo 트레이스 분포 (after)
```
 13건 | http get /api/routes
 11건 | http get /actuator/prometheus
  9건 | http get /actuator/health
  4건 | http get /api/debug/boom
```

### 표준 준수 확인
- `resource.service.name=black-mamba` 이 `spring.application.name`에서 자동 주입 ✅
- Trace/Span ID는 **OTel 표준 128-bit** (Zipkin 64-bit 확장 옵션과 다름)
- Tempo 단일 백엔드에서 두 포맷 모두 검색 가능 (backward compatibility)

### 측정 방법
```bash
# 1. Tempo 트레이스 조회
curl --data-urlencode 'q={resource.service.name="black-mamba"}' \
  http://localhost:3200/api/search

# 2. 에러 로그 체크
docker logs --since=1m black-mamba-app | grep -iE "Failed to export|Connection reset"
```

---

## 6. 사이드 이펙트 & 한계

### ⚠️ gRPC 완전 전환 실패 기록
- Spring Boot 3.3에서 수동 `OtlpGrpcSpanExporter` Bean을 `@Primary`로 등록 → auto-config의 HTTP exporter가 **함께 활성화**되어 중복 전송 발생
- gRPC Bean은 성공 전송, HTTP Bean은 4317(gRPC 포트)에 HTTP로 접근해 Connection reset
- 근본 해결은 **Spring Boot 3.4+ 업그레이드 + `transport: grpc`**
- **T-4 ROADMAP 항목**으로 분리

### ⚠️ TraceId 포맷 변화
- Zipkin: 64-bit 또는 128-bit (선택)
- OTel: **128-bit 고정**
- 기존에 저장된 Zipkin 트레이스와 새 OTLP 트레이스 검색 시 길이 혼재 (Tempo는 둘 다 지원하지만 시각화에서 혼선 가능)
- 해결: 과거 데이터 초기화 또는 기간 필터로 분리 조회

### 한계: 완전한 gRPC 미도입
- OTLP/HTTP도 OkHttp 기반이라 HTTP/2 사용 가능
- Protobuf 직렬화로 JSON 대비 페이로드 감소는 달성
- 하지만 **진정한 gRPC 장점 (Bidi streaming 등)**은 ROADMAP T-4 이후

---

## 7. 사례 정리

> **"분산 추적 전송 프로토콜 고민하신 부분이 있나요?"**
>
> "초기에는 Zipkin HTTP/JSON을 썼는데, 3가지 한계가 있었습니다.
> 첫째, 벤더 종속. Tempo → Jaeger 같은 백엔드 변경 시 전송 포맷도 바꿔야 합니다.
> 둘째, JSON 페이로드가 커서 스택트레이스 포함 span이 수 KB입니다.
> 셋째, **OpenTelemetry 표준이 아닙니다**.
>
> **OTLP/HTTP + Protobuf**로 전환했습니다. Spring Boot 3.3이 `management.otlp.tracing` 속성으로 공식 지원해서 설정 한 줄로 해결됐고,
> Protobuf 직렬화로 페이로드가 줄었습니다.
>
> 원래 **gRPC**로 가려고 했는데, `transport: grpc`는 Spring Boot 3.4부터 공식 지원입니다.
> 3.3에서 `OtlpGrpcSpanExporter`를 수동 Bean으로 등록했더니 auto-config의 HTTP exporter와
> **중복 전송 + Connection reset 에러**가 발생했습니다. 과한 복잡도로 판단해서
> **Spring Boot 3.5 업그레이드 + gRPC 완전 전환**을 별도 개선 항목(T-4)으로 분리했습니다.
>
> 지금 OTLP/HTTP 만으로도 **벤더 중립 + Protobuf 기반**이라 주요 가치의 90%는 달성했다고 판단합니다.
> 남은 10% (HTTP/2 멀티플렉싱 극대화, Bidi streaming) 는 T-4에서 마저 가져옵니다."

---

## 8. 다음 단계 (T-4 연결)
- [ ] Spring Boot 3.3.0 → 3.5.x 업그레이드
- [ ] `management.otlp.tracing.transport: grpc` 적용
- [ ] 이 문서에 Before(OTLP HTTP) → After(OTLP gRPC) 측정 결과 추가
- [ ] docker-compose에서 `9411` (Zipkin legacy 포트) 제거
