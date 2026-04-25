# Black Mamba — Observability Stack (Prometheus + Grafana LGTM)

> 참고: [기술 블로그 - 모니터링 시스템 개발 (Prometheus & Grafana LGTM)](https://www.notion.so/Prometheus-Grafana-LGTM-15b8983855a3807c840addfdbe093342)

---

## 전체 구성

```
[Black Mamba App]
       │
       ├── /actuator/prometheus   ─────► [Prometheus]  ──► [Grafana]
       │                                                    (Metrics)
       │
       ├── Loki4jAppender (Push) ──────► [Loki]        ──► [Grafana]
       │                                                    (Logs)
       │
       └── Micrometer Tracing ────────► [Tempo]        ──► [Grafana]
           (Zipkin 포맷, Brave bridge)                      (Traces)
```

세 데이터를 Grafana 한 곳에서 통합 조회하며, 로그와 트레이스는 `traceId`로 상호 연결됩니다.

---

## Phase 1: Metrics (Prometheus)

### 구성 요소
- **Spring Boot Actuator + Micrometer Prometheus Registry**
- `/actuator/prometheus` 엔드포인트로 메트릭 노출
- Prometheus가 15초 간격으로 Pull

### 커스텀 비즈니스 메트릭

| 메트릭 | 타입 | 라벨 | 의미 |
|--------|------|------|------|
| `navigation.route.duration` | Timer (히스토그램) | mode, preference, outcome | 경로 탐색 응답시간 |
| `navigation.route.generated` | Counter | mode, preference | 생성된 경로 수 |
| `navigation.cache.total` | Counter | cache, result | 캐시 hit/miss |
| `navigation.mobility.fallback.total` | Counter | mobility, reason | 외부 API 폴백 발생 |

### Grafana 대시보드 (자동 프로비저닝)
`monitoring/grafana-dashboards/black-mamba-overview.json`

**패널 구성:**
- 🔢 총 경로 탐색 요청 수 (최근 5분)
- ⏱ 경로 탐색 p95 응답시간
- 💾 캐시 Hit률
- 🧠 JVM 힙 사용량
- 📈 응답시간 p50/p95/p99 시계열
- 🔀 모드×선호도별 처리량
- 💾 캐시별 hit/miss
- ⚠️ 외부 API 폴백 발생율
- 🌐 URI별 HTTP p95
- 🗑 GC Pause

---

## Phase 2: Logs (Loki + loki4j)

### 구성 요소
- **loki4j (Logback Appender)**: 애플리케이션에서 Loki로 직접 Push
- **Batch**: 100건 or 10초 주기 전송 (오버헤드 최소화)
- **Label**: `app`, `host`, `level` (Loki 검색 키)

### 블로그 문서 기반 결정 근거

블로그 비교 결과:
- **Promtail (Pull)**: 에러 stack trace가 분리되어 가독성 ↓
- **loki4j (Push)**: stack trace를 단일 엔트리로 보존, 구현 단순

→ Push 방식 채택

### 로그 포맷 (JSON)
```json
{
  "ts": "2026-04-17T12:34:56.789Z",
  "level": "INFO",
  "logger": "com.blackmamba.navigation.application.route.RouteOptimizationService",
  "thread": "reactor-http-nio-2",
  "traceId": "abc123...",
  "spanId": "def456...",
  "message": "[OPTIMAL] baseLegs=3개 totalMin=42",
  "stackTrace": ""
}
```

`traceId` 포함으로 Grafana에서 로그 → 트레이스 점프 가능.

---

## Phase 3: Traces (Tempo + Micrometer Tracing)

### 구성 요소
- **Micrometer Tracing Bridge (Brave)**: Spring Boot 3.x 표준
- **Zipkin Reporter**: Brave 형식을 Zipkin HTTP API로 전송
- **Tempo**: Zipkin 포맷 수신 → 자체 저장 (S3/파일)

### 블로그 문서 기반 결정 근거

블로그에서 검토된 옵션:
- **OpenTelemetry Java Agent + OTLP/gRPC**: 메서드 단위 세분화 강하지만 초기 셋업 복잡
- **Zipkin HTTP**: 서비스 간 호출 중심, 간단

→ 프로젝트 범위로는 **Micrometer Tracing + Zipkin 포맷**이 균형

### @Observed 어노테이션

핵심 비즈니스 메서드에 span 자동 생성:
```java
@Observed(name = "navigation.route.search",
          contextualName = "경로 탐색",
          lowCardinalityKeyValues = {"component", "RouteOptimizationService"})
public Mono<List<Route>> findRoutes(...) { ... }
```

### B3 vs W3C (블로그 비교 결과)
현재 **B3 Propagation** 사용 (Brave bridge 기본값). 나중에 W3C로 전환 가능.

---

## 실행 방법

```bash
# 1. .env 파일 준비
cp .env.example .env
# API 키 입력

# 2. 전체 스택 기동
docker compose up -d

# 3. 접속
open http://localhost:8081/swagger-ui.html     # 애플리케이션
open http://localhost:9090                     # Prometheus
open http://localhost:3000                     # Grafana (admin/admin)
open http://localhost:3100                     # Loki
open http://localhost:3200                     # Tempo
```

## 검증 시나리오

### 1. Metrics 확인
```bash
curl http://localhost:8081/actuator/prometheus | grep navigation_route
```

### 2. 경로 탐색 요청 발생시킨 후 대시보드 확인
```bash
./scripts/k6/run.sh load
# Grafana → "Black Mamba Overview" 대시보드에서 실시간 수치 관찰
```

### 3. Trace-to-Logs 연결 확인
1. Grafana → Explore → Tempo 선택
2. 최근 trace 하나 클릭
3. 각 span에서 "Logs for this span" 클릭 → Loki 로그 자동 필터링

---

## 설명 포인트

### 1. 왜 Prometheus Pull 방식인가?
대상 서버 부하 제어 + 가변 인프라 대응(Service Discovery). 블로그 문서 3장 참조.

### 2. 왜 loki4j Push 방식인가?
Promtail Pull은 stack trace 가독성 문제. loki4j는 비동기 배치로 애플리케이션 부하 최소화.

### 3. 왜 OpenTelemetry Java Agent가 아닌가?
- Agent는 메서드 단위 세분화가 강하지만 프로젝트 범위에서 과함
- Spring Boot 3.3 표준인 Micrometer Tracing으로 충분
- 나중에 OTel Agent로 전환 가능 (OTLP/gRPC exporter 대체)

### 4. 운영 성숙도 증명
- 커스텀 비즈니스 메트릭 4종 (`navigation.*`)
- p50/p95/p99 히스토그램 자동 생성
- 캐시 효과 실시간 관찰
- 외부 API 폴백 발생율 추적

---

## 다음 단계 (미구현)

- **Prometheus Alertmanager**: Slack/Discord 경보 라우팅
- **Node Exporter**: 호스트 시스템 메트릭 (CPU/Mem/Disk)
- **Blackbox Exporter**: 외부 API 가용성 모니터링
- **OpenTelemetry Collector**: Metrics/Logs/Traces 파이프라인 통합
- **Grafana Alloy**: OTel Collector 기반 Grafana 통합 배포판
