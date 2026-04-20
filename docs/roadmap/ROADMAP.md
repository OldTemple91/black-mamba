# Black Mamba — 향후 로드맵

> 작성일: 2026-04-17  
> 목적: EV/자동차 제조사 모빌리티 백엔드 포트폴리오용 프로젝트의 기능/비즈니스 확장 계획  
> 용법: 이 문서 보면서 Claude와 이어서 작업 (`claude chat` → "ROADMAP.md 보고 [항목 번호] 진행")

---

## 📊 전체 우선순위 매트릭스

| # | 항목 | 영역 | 공수 | 임팩트 | EV/자동차 제조사 어필 |
|---|------|------|------|--------|-----------|
| **C-1** | **EV 충전소 연동** | 비즈니스 | 2~3일 | 🔥🔥🔥🔥🔥 | ★★★★★ |
| **B-1** | **Event-Driven 재탐색** | 아키텍처 | 3~4일 | 🔥🔥🔥🔥🔥 | ★★★★ |
| **A-1** | **실시간 경로 재탐색 (SSE)** | 기능 | 2일 | 🔥🔥🔥🔥 | ★★★★ |
| **D-1** | **멀티 지역 확장 (수도권)** | 확장성 | 2~3일 | 🔥🔥🔥🔥 | ★★★ |
| **A-2** | **도착 시간 신뢰도 구간** | 기능 | 2~3일 | 🔥🔥🔥🔥 | ★★★ |
| **M-1** | **Alerting + Slack/Discord** | 모니터링 | 2시간 | 🔥🔥🔥🔥 | ★★★ |
| **A-3** | **시간대별 트래픽 인식** | 기능 | 1~2일 | 🔥🔥🔥 | ★★★ |
| **A-4** | **날씨 인식 경로** | 기능 | 반나절 | 🔥🔥🔥 | ★★★ |
| **B-2** | **사용자 개인화** | 아키텍처 | 2~3일 | 🔥🔥🔥 | ★★★ |
| **B-3** | **Geohash 공간 캐싱** | 아키텍처 | 2일 | 🔥🔥🔥 | ★★★ |
| **B-4** | **LLM 기반 추천 이유 설명** | 기능 | 1~2일 | 🔥🔥 | ★★ |
| **C-2** | **Carbon Footprint** | 비즈니스 | 반나절 | 🔥🔥 | ★★ |
| **C-3** | **Accessibility 경로** | 비즈니스 | 1~2일 | 🔥🔥 | ★★ |
| **M-2** | **호스트 메트릭 (cAdvisor)** | 모니터링 | 30분 | 🔥🔥 | ★★ |
| **M-3** | **SLO 대시보드** | 모니터링 | 2시간 | 🔥🔥🔥 | ★★★ |
| **D-2** | **A/B 테스트 프레임워크** | 확장성 | 2일 | 🔥🔥🔥 | ★★★ |
| **D-3** | **API Rate Limiting** | 확장성 | 1일 | 🔥🔥 | ★★ |
| **E-1** | **경로 이력 기반 예측** | ML/AI | 3~5일 | 🔥🔥🔥 | ★★★ |
| **E-2** | **Embedding 유사 경로** | ML/AI | 3~4일 | 🔥🔥 | ★★ |
| **T-1** | **ADR 문서화** | 설계 | 2~3시간 | 🔥🔥 | ★★ |
| **T-2** | **WireMock E2E 테스트** | 테스트 | 반나절 | 🔥🔥🔥 | ★★★ |
| **T-3** | **Resilience4j** | 안정성 | 반나절 | 🔥🔥🔥 | ★★★ |

---

## 🎯 추천 구현 순서 (발표 대비)

### Option 1: 자동차 회사 어필 극대화 (권장, 1~2주)
```
Week 1:
  Day 1~3:   C-1. EV 충전소 연동
  Day 4:     A-4. 날씨 인식
  Day 5:     C-2. 탄소 발자국
Week 2:
  Day 1~2:   M-1. Alerting + M-3. SLO
  Day 3~5:   T-1. ADR + T-2. WireMock
```
**포지셔닝:** "**EV + MaaS 통합 라우팅 엔진**"

### Option 2: 기술적 깊이 어필 (1~2주)
```
Week 1:
  Day 1~4:   B-1. Event-Driven 재탐색
  Day 5~6:   A-1. SSE 실시간
Week 2:
  Day 1~3:   A-2. 신뢰도 범위
  Day 4~5:   T-3. Resilience4j
```
**포지셔닝:** "**반응형 실시간 MaaS 플랫폼**"

### Option 3: 비즈니스 확장 어필 (1~2주)
```
Week 1:
  Day 1~3:   D-1. 멀티 지역 확장
  Day 4~5:   B-3. Geohash 캐싱
Week 2:
  Day 1~2:   D-2. A/B 테스트
  Day 3:     D-3. API Rate Limiting
  Day 4~5:   M-1. Alerting
```
**포지셔닝:** "**확장 가능한 MaaS 플랫폼**"

---

# 📦 카테고리별 상세

## A. 기능(Feature) 확장 — 사용자 가치 향상

---

### A-1. 🔥🔥🔥🔥 실시간 경로 재탐색 (SSE Streaming)

**현재:** 요청 → 응답 → 끝 (1회성)  
**개선:** 이동 중에도 실시간 재탐색 스트림

#### 시나리오
```
"강남→홍대" 검색 → 따릉이 이용 중
    ↓
따릉이 재고 급감 (다른 사용자 이용)
    ↓
대체 경로 자동 제안 (SSE push)
    ↓
"가까운 정류소 재고 부족 → 다음 역까지 도보로 이동 권장"
```

#### 구현
- `/api/routes/stream` SSE 엔드포인트
- 초기 응답 후 30초 간격 재탐색
- `Flux<ServerSentEvent<Route>>` 로 WebFlux 활용
- 재고 변화 감지 시만 이벤트 push (변화 없으면 heartbeat만)

#### 파일
- `api/src/main/java/.../api/route/RouteStreamController.java`
- `application/src/main/java/.../route/RouteStreamService.java`

#### 공수
2일

#### 발표 포인트
> "Reactive Programming을 활용해 실시간 경로 변경을 SSE로 스트리밍합니다. 도착 실패 방지가 핵심 가치입니다."

---

### A-2. 🔥🔥🔥🔥 도착 시간 신뢰도 구간 (Confidence Interval)

**현재:** "35분 소요"  
**개선:** "30~42분 (95% 신뢰구간)"

#### 응답 예시
```json
{
  "estimatedMinutes": 35,
  "confidenceRange": {"min": 30, "max": 42, "confidence": 0.95},
  "reliabilityFactors": [
    "러시아워 대중교통 지연 가능성 +5분",
    "따릉이 반납 대기 +3분"
  ]
}
```

#### 기술
- Prometheus 히스토그램 → 과거 실측 응답시간 분포
- Monte Carlo 시뮬레이션 (구간별 분포 곱하기)
- 또는 간단하게 표준편차 기반 ±1.96σ

#### 파일
- `application/.../route/RouteConfidenceCalculator.java`
- `domain/.../route/ConfidenceRange.java` (record)

#### 공수
2~3일

---

### A-3. 🔥🔥🔥 시간대별 트래픽 인식

**현재:** 출발 시각 무관 동일 응답  
**개선:** 러시아워 패턴 반영

#### 시나리오
```
아침 8시 강남→홍대:
  - 2호선 직행 (36분, 만석 위험)
  - 따릉이+9호선 (32분, 쾌적) ← 추천

주말 오후:
  - 2호선 직행 (30분) ← 추천
```

#### 기술
- 서울시 지하철 혼잡도 API
- ODsay 시간대별 소요시간 (시간 파라미터)
- 내부 캐시에 `timeOfDay` 차원 추가

#### 파일
- `infra/.../seoul/SubwayCongestionClient.java`
- `application/.../route/TimeOfDayContext.java`

#### 공수
1~2일

---

### A-4. 🔥🔥🔥 날씨 인식 경로

**현재:** 날씨 상관없이 동일  
**개선:** 비/눈/폭염 시 이동수단 가중치 조정

#### 스코어 조정
```
맑음: 따릉이 1.0 / 지하철 0.9
비:   따릉이 0.1 / 지하철 1.0  ← 역전
```

#### 기술
- 기상청 단기예보 API (무료, 3시간 단위)
- `RouteScoreCalculator` 에 weather 차원 주입

#### 파일
- `infra/.../weather/KmaWeatherClient.java`
- `application/.../route/WeatherAwareScoreCalculator.java`

#### 공수
반나절

---

## B. 아키텍처(Architecture) 고도화

---

### B-1. 🔥🔥🔥🔥🔥 Event-Driven 경로 변경 감지

#### 시나리오
```
[따릉이 재고 변경] 
    ↓
[Redis Stream 또는 Spring Event 발행]
    ↓
[영향받는 진행 중 경로 식별]
    ↓
[재탐색 + SSE 푸시]
```

#### 구현
- Spring ApplicationEvent 또는 Redis Pub/Sub
- `MobilityAvailabilityChangedEvent` 이벤트 정의
- `InflightRouteRegistry`: 진행 중 경로 추적
- `RouteReevaluator`: 영향 경로 재탐색

#### 파일
- `domain/.../event/MobilityAvailabilityChangedEvent.java`
- `application/.../event/InflightRouteRegistry.java`
- `application/.../event/RouteReevaluator.java`

#### 공수
3~4일

#### 발표 포인트
> "진짜 MaaS는 이런 모습입니다. 실시간 데이터 변경에 반응하는 서비스."

---

### B-2. 🔥🔥🔥 사용자 개인화 / 프로필

**현재:** 모든 사용자 동일 점수  
**개선:** 개인 선호 반영

#### 프로필 구조
```java
UserPreference {
  preferredMobility: [PERSONAL_EBIKE, DDAREUNGI],
  walkingTolerance: "normal",   // low/normal/high
  fitnessLevel: "high",         // 언덕 허용
  ageGroup: "30s",              // 보행속도 보정
  avoidCrowded: true            // 혼잡 회피
}
```

#### 저장소
- 초기: Redis (익명 사용자도 지원, 디바이스 ID 키)
- 확장: PostgreSQL + JWT 인증

#### 공수
2~3일

---

### B-3. 🔥🔥🔥 Route Caching + Spatial Index (Geohash)

**현재:** 동일 OD마다 외부 API 재호출  
**개선:** Geohash 격자 단위 캐시

#### 설계
- 100m 격자 (Geohash precision 7) 로 OD 그룹핑
- 같은 격자 내 요청은 캐시 재사용
- Redis로 분산 캐시

#### 효과
- 외부 API 호출 **80%+ 절감**
- p95 응답시간 대폭 개선

#### 파일
- `application/.../cache/GeohashRouteCache.java`
- `infra/.../redis/RouteCacheAdapter.java`

#### 공수
2일

---

### B-4. 🔥🔥 LLM 기반 추천 이유 설명

#### 현재
```
"시간 5분 절감", "신뢰도 높음"
```

#### 개선
```
"지하철 직행보다 3분 느리지만, 따릉이+버스 조합을 추천합니다.
 이유: 홍대입구역에서 목적지까지 400m 도보를 피할 수 있고,
       현재 따릉이 재고가 12대로 충분하기 때문입니다."
```

#### 기술
- OpenAI/Claude API 또는 사내 LLM
- 템플릿 기반으로 시작 → LLM 고도화

#### 공수
1~2일

---

## C. 모빌리티 도메인 특화 ⭐ EV/자동차 제조사 직결

---

### C-1. 🔥🔥🔥🔥🔥 EV 충전소 연동 (최우선 추천)

#### 왜 이게 1순위?
- **EV/자동차 제조사 E-GMP 플랫폼 직결**
- EV 운전자 **실사용 니즈**
- 다른 지원자 포트폴리오에 거의 없는 차별화
- 발표 대화 10분+ 끌고 갈 주제

#### 시나리오
```
장거리 (100km+):
  출발 → 휴게소 충전 (30분) → 계속 → 도착
  vs
  출발 → 경유지 충전 (15분) → 도착 (더 빠름)
```

#### 응답 예시
```json
{
  "route": [...],
  "chargingStops": [
    {
      "station": "휴게소 A",
      "location": {...},
      "availableChargers": 3,
      "estimatedChargeMinutes": 25,
      "detourMinutes": 5
    }
  ],
  "evOptimized": true
}
```

#### 기술
- **환경부 무공해차 통합누리집 API** (무료)
- 또는 **KEPCO 전기차 충전기 API**
- 경로상 충전소 검색 → 목적지 거리 vs 배터리 고려

#### 파일
- `infra/.../ev/EvChargingStationClient.java`
- `application/.../route/EvRouteOptimizer.java`
- `domain/.../route/ChargingStop.java`

#### 공수
2~3일

#### 발표 스토리
> "EV 운전자를 위한 경로를 고도화했습니다. 목적지 인근 충전소의 재고/혼잡도를 실시간 반영해, 긴 이동 시 경유 충전이 최적인지 목적지 충전이 최적인지 자동 판단합니다. 향후 EV 제조사의 플랫폼 플랫폼 차량 데이터와 연동하면 남은 배터리 기반 실시간 충전 경로 재탐색까지 가능합니다."

---

### C-2. 🔥🔥 Carbon Footprint

#### 경로별 탄소 배출량 표시
```
대중교통: 0.8 kg CO₂
따릉이:   0.0 kg CO₂ ← 친환경 뱃지
자가용:   3.2 kg CO₂ (비교용)
```

#### 기술
- 이동수단별 km당 CO₂ (공공데이터)
- `RouteEvaluation`에 `carbonGrams` 필드 추가

#### 공수
반나절

---

### C-3. 🔥🔥 Accessibility 경로 (휠체어/유모차)

#### 필요한 데이터
- 엘리베이터 있는 지하철역 (서울시 열린데이터)
- 계단 없는 경로
- 전동 휠체어 배터리 고려

#### `MobilityType` 추가
```java
WHEELCHAIR,
ELECTRIC_WHEELCHAIR
```

#### 공수
1~2일

---

## D. 운영/확장성 (Scalability)

---

### D-1. 🔥🔥🔥🔥 멀티 지역 확장

**현재:** 서울만  
**개선:** 경기/인천까지

#### 해야 할 것
- `cityCode` 차원 추가 (11=서울, 41=경기, 28=인천)
- 지역별 ODsay API 호출 파라미터
- `MobilityConfig` 지역별 분리 (수도권은 따릉이 없음, 대신 광명/부천 공공자전거)
- 결과 캐시도 지역별

#### 출발/도착 지역 조합 처리
- 서울→경기: 광역버스/지하철 연장선
- 경기→경기: 지역 따릉이 없음, 버스 위주

#### 공수
2~3일

---

### D-2. 🔥🔥🔥 A/B 테스트 프레임워크

#### 경로 추천 알고리즘 동시 운영
```
사용자 50%: RELIABILITY 가중치 0.15
사용자 50%: RELIABILITY 가중치 0.25
→ 실제 선택률 높은 쪽 측정
```

#### 구현
- 디바이스 ID 해시 기반 bucket 분리
- Prometheus 라벨에 `experiment_variant` 추가
- 대시보드에서 variant별 지표 비교

#### 공수
2일

---

### D-3. 🔥🔥 API Rate Limiting + Quota

#### 계층별 제한
```yaml
Anonymous:  10 req/min
Registered: 100 req/min
Premium:    1000 req/min
```

#### 기술
- Spring Cloud Gateway + Redis
- 또는 Bucket4j + Caffeine (로컬)

#### 공수
1일

---

## E. ML / AI 고도화

---

### E-1. 🔥🔥🔥 과거 경로 데이터 기반 예측

#### 시나리오
- 사용자가 같은 경로 반복 → 학습
- "출근길 자주 막히는 구간" 자동 감지 후 우회 제안

#### 기술 (단계적)
- **단계 1**: 단순 Moving Average (초기)
- **단계 2**: Spark/Flink로 집계 (데이터 많아질 때)
- **단계 3**: LSTM/Transformer 기반 시계열 예측

#### 공수
3~5일

---

### E-2. 🔥🔥 Embedding 기반 유사 경로 추천

#### "이 경로와 비슷한 경로"
- 경로를 벡터로 임베딩 (거리, 환승, 시간대, 이동수단)
- 코사인 유사도로 유사 경로 탐색

#### 기술
- Sentence Transformers (Spring AI 연동)
- pgvector 또는 Qdrant

#### 공수
3~4일

---

## M. 모니터링 시스템 보완 (진행 중 프로젝트 완성)

---

### M-1. 🔥🔥🔥🔥 Alerting (Alertmanager + Discord)

#### 알림 룰
```yaml
groups:
  - name: black-mamba-alerts
    rules:
      - alert: HighP95Latency
        expr: histogram_quantile(0.95, sum(rate(navigation_route_duration_seconds_bucket[5m])) by (le)) > 3
        for: 5m
        annotations:
          summary: "경로 탐색 p95 > 3초"
      
      - alert: HighErrorRate
        expr: sum(rate(navigation_route_duration_seconds_count{outcome="timeout"}[5m])) / sum(rate(navigation_route_duration_seconds_count[5m])) > 0.05
        for: 5m
      
      - alert: DdareungiApiFailure
        expr: sum(rate(navigation_mobility_fallback_total{mobility="ddareungi",reason="error"}[5m])) > 0.2
        for: 5m

      - alert: JvmHeapHigh
        expr: sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) > 0.9
        for: 10m
```

#### 구현
- `monitoring/alertmanager.yml`: Discord 웹훅 연동
- `monitoring/prometheus-rules.yml`: 알림 룰
- docker-compose에 alertmanager 서비스 추가

#### 공수
2시간

---

### M-2. 🔥🔥 호스트/컨테이너 메트릭

#### 추가할 Exporter
- **cAdvisor** (컨테이너 CPU/Mem/Network)
- **Node Exporter** (호스트 리소스)

#### 공수
30분

---

### M-3. 🔥🔥🔥 SLO 대시보드

#### 에러 버짓 계산
```
SLO: p95 < 2s (99% of time)
Error Budget: 1% per month = 7h 18min
Current Burn Rate: ...
```

#### 구현
- Prometheus recording rules
- Grafana SLO 대시보드

#### 공수
2시간

---

### M-4. 🔥 로그 레벨 정리 (빠른 개선)

현재: `com.blackmamba.navigation` → DEBUG (Loki로 전부)  
개선: Docker 프로필에서 INFO만

#### 파일
- `api/src/main/resources/logback-spring.xml`

#### 공수
10분

---

### M-5. 🔥 Grafana 보안

현재: `GF_AUTH_ANONYMOUS_ORG_ROLE=Admin`  
개선: Viewer로 (운영 용도) 또는 auth 추가

#### 공수
5분

---

### M-6. 🔥 Tempo 볼륨 / 보존 정책

- Tempo 볼륨 매핑 추가 (재기동 시 트레이스 유지)
- Loki 보존 7일로 명시
- Tempo 1시간 → 24시간으로 늘리기

#### 공수
10분

---

## T. 테스트/설계 증명

---

### T-1. 🔥🔥 ADR (Architecture Decision Records)

#### 작성할 것
- `docs/adr/001-why-webclient-on-mvc.md`
- `docs/adr/002-why-tago-kickboard-abandoned.md`
- `docs/adr/003-why-hub-not-mobility-type-for-carshare.md`
- `docs/adr/004-why-loki-plain-text-not-json.md`
- `docs/adr/005-why-reactor-context-propagation.md`
- `docs/adr/006-why-global-exception-handler.md`

#### 형식
```markdown
# ADR-001: Why WebClient on MVC (not WebFlux)

## Context
...

## Decision
...

## Consequences
- Pro:
- Con:

## Alternatives Considered
...
```

#### 공수
2~3시간

---

### T-2. 🔥🔥🔥 WireMock E2E 테스트

#### 현재: 단위 테스트 15개만 (46% 커버리지)
#### 개선: 외부 API mock한 E2E 테스트

#### 시나리오
```java
@Test
void 강남에서_홍대까지_경로_탐색시_3개_API_호출_후_신뢰도_기반_순위_반환() {
    // given: ODsay 모킹, 따릉이 모킹, Tmap 모킹
    wireMockServer.stubFor(get("/api/transit")...)
    
    // when
    var response = mockMvc.perform(get("/api/routes?..."))
    
    // then
    response.andExpect(status().isOk())
            .andExpect(jsonPath("$.routes").isArray())
            .andExpect(jsonPath("$.routes[0].evaluation.totalScore").isNumber())
    
    // 외부 API 호출 횟수 검증
    wireMockServer.verify(1, getRequestedFor(urlMatching("/api/transit.*")))
}
```

#### 파일
- `api/src/test/java/.../RouteSearchE2ETest.java`
- `api/src/test/resources/wiremock-responses/`

#### 공수
반나절

---

### T-3. 🔥🔥🔥 Resilience4j (Circuit Breaker)

#### 외부 API 장애 시 대응
- **Circuit Breaker**: 연속 실패 시 일정 시간 호출 차단
- **Retry**: 지수 백오프 재시도
- **Bulkhead**: API별 스레드풀 격리
- **Rate Limiter**: 클라이언트 요청 제한

#### 적용 대상
```
@CircuitBreaker(name = "odsay", fallbackMethod = "fallbackToHaversine")
@Retry(name = "odsay")
@Bulkhead(name = "odsay", type = Bulkhead.Type.SEMAPHORE)
public Mono<List<Leg>> getTransitRoute(...) { ... }
```

#### 공수
반나절

---

## 🎬 작업 재개 가이드

### 집에서 이어서 작업할 때

```bash
cd <project-root>
git pull
claude chat
```

Claude에게:
```
"ROADMAP.md 읽고 [항목 번호] 진행해줘"
예: "ROADMAP.md 읽고 C-1 EV 충전소 연동 진행해줘"
```

### 체크리스트 양식으로 진행 원할 때

```
"ROADMAP.md의 Option 1 순서대로 진행하자. 
 첫 단계부터 시작해줘"
```

### 새로 떠오른 아이디어 추가할 때

```
"ROADMAP.md에 [새 아이디어] 추가해줘. 
 공수/임팩트 산정해서 우선순위 매트릭스에도 반영"
```

---

## 📝 진행 상황 추적 (체크하면서 작업)

### 현재 완료된 것 (2026-04-17 기준)
- [x] 기반 인프라: Prometheus + Grafana + Loki + Tempo 구축
- [x] 구조화 로깅 (traceId 전파, stack_trace 분리)
- [x] 외부 API @Observed (ODsay/Tmap/따릉이 각각 span)
- [x] Exemplars (메트릭 ↔ 트레이스 연결)
- [x] 드릴다운 대시보드 (Overview / Route Performance / External APIs)
- [x] GlobalExceptionHandler (중복 로그 제거)
- [x] 디버그 엔드포인트 (/api/debug/boom, /npe, /slow)
- [x] CI/CD (GitHub Actions + Jacoco)
- [x] Docker Compose 원클릭 실행
- [x] k6 부하 테스트 5종
- [x] **B-3 Geohash 공간 인덱스 캐시** (ODsay 히트율 46.9% → 80.4%) — [개선 기록](../improvements/2026-04-17-B3-geohash-spatial-caching.md)
- [x] **Zipkin/JSON → OTLP/Protobuf 트레이스 전송** (OpenTelemetry 표준 준수, Protobuf 페이로드) — [개선 기록](../improvements/2026-04-17-C-otlp-protobuf-tracing.md)
- [x] **T-4 Phase 1: Spring Boot 3.5.13 + OTLP/gRPC + Gradle 8.14.3** — [개선 기록](../improvements/2026-04-20-T4-phase1-springboot-3.5-otlp-grpc.md)

### 다음 진행 예정 (우선순위 순)
- [ ] **C-1** EV 충전소 연동 (**발표 1순위**)
- [ ] **M-1** Alerting + Discord 웹훅
- [ ] **M-4** 로그 레벨 정리
- [ ] **A-4** 날씨 인식
- [ ] **T-2** WireMock E2E 테스트
- [ ] **T-1** ADR 작성
- [ ] **T-4 Phase 2**: Java 21 → Java 25 LTS 전환 (Phase 1 완료, Phase 2 대기)
- [ ] **B-1** Event-Driven 재탐색
- [ ] **A-1** SSE 실시간 재탐색
- [ ] **D-1** 멀티 지역 확장
- [ ] **T-3** Resilience4j

---

## 💡 발표 스토리텔링 (참고)

### 스토리 1: EV + MaaS
> "자동차 회사의 미래는 차량 판매가 아니라 모빌리티 서비스입니다. 제 프로젝트는 서울의 대중교통/따릉이/개인 PM에서 시작해 **EV 충전 인프라까지 통합한 MaaS 라우팅 엔진**으로 확장됩니다. 향후 E-GMP 플랫폼 차량 데이터와 연동하면 남은 배터리 기반 실시간 경로 재탐색도 가능합니다."

### 스토리 2: 관측성 + 신뢰성
> "운영 관점에서는 Prometheus/Loki/Tempo를 traceId 하나로 연결해 로그↔메트릭↔트레이스 **3축 관측성**을 구현했습니다. Exemplars로 p99 스파이크를 즉시 트레이스로 역추적하고, @Observed로 외부 API 호출별 병목을 Gantt 차트에서 시각화합니다."

### 스토리 3: 확장 가능한 도메인 설계
> "MobilityType enum 하나 추가하면 새로운 이동수단 지원이 확장됩니다. Hub 모델로 카셰어존 같은 메타 인프라도 추가할 수 있고, 지역 확장은 cityCode 차원 하나만 추가하면 되는 구조입니다. Clean Architecture로 외부 API를 언제든 교체 가능합니다."
