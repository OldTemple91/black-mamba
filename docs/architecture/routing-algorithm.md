# 자체 라우팅 알고리즘 카탈로그

> Black Mamba 는 **도로 그래프 최단경로 문제를 푸는 엔진이 아니다.**
> ODsay / Tmap 같은 외부 엔진이 그 층을 담당하고, 본 프로젝트는 그 위에서
> **다중 이동수단을 재조합 · 평가 · 설명하는 Orchestration 층** 을 자체 설계했다.
>
> 이 문서는 그 Orchestration 층에 들어간 **8가지 자체 알고리즘 · 휴리스틱** 을 하나씩 풀어서 설명한다.

**Last updated:** 2026-04-24

---

## 0. 전체 구조

```
┌──────────────────────────────────────────────────────────────────┐
│                   Orchestration Layer (본 프로젝트)               │
│                                                                  │
│   ① Baseline-Guided Multimodal Recomposition   (탐색)            │
│   ② Two-Phase Hub Selection                    (정류장 선별)     │
│   ③ Candidate Point Selection                  (중간 정류장 발굴)│
│   ④ Two-Phase Walking Calculation              (도보 산출)       │
│   ⑤ 6-Dimensional Weighted Scoring             (평가)            │
│   ⑥ Geohash Spatial Cache                      (캐싱)            │
│   ⑦ Accessibility Post-Processor               (후처리)          │
│   ⑧ SSE Change Detection                       (실시간 재탐색)   │
└──────────────────────────────────────────────────────────────────┘
                              ▼
   ┌──────────────────┐  ┌────────────┐  ┌──────────────┐
   │ ODsay (대중교통) │  │ Tmap (도보)│  │ 따릉이 (CDN) │
   └──────────────────┘  └────────────┘  └──────────────┘
       Route Search Layer  (외부 엔진이 담당 — A*/CH/RAPTOR 등)
```

---

## 1. Baseline-Guided Multimodal Recomposition

**파일**: `OptimalSearchStrategy.java`, `SpecificMobilityStrategy.java`
**핵심 아이디어**: "순수 대중교통 경로(baseline)" 를 **설계도** 삼아, 그 위에 이동수단을 덧대 여러 변형을 만들고 평가한다.

### 1-1. 왜 이 방식인가

경로 그래프를 직접 탐색하지 않는다. 대신:

1. ODsay 에서 받은 **baseline 경로** (예: `지하철 2호선 → 버스 341` — 45분)
2. 이 경로의 **어느 지점부터 이동수단으로 바꾸면 더 빠른가?** 를 탐색
3. 여러 후보 조합을 점수로 비교

이유:
- **탐색공간 제어** — 서울 전체 정류장 × 이동수단 조합이면 수백만. Baseline 에 근접한 것만 후보로 잡으면 수십 개.
- **실패 회복성** — ODsay 가 실패해도 Haversine 추정 경로로 합성 (`haversineTransitMinutes`)
- **서비스 정체성** — "자가용 대비 몇 분 단축" 같은 스토리가 baseline 이 있어야 가능

### 1-2. 5가지 조합 패턴

`OptimalSearchStrategy` 는 baseline 에서 다음 5패턴을 병렬 생성한다.

| 패턴 | 구성 | 예시 |
|------|------|------|
| **A: TRANSIT_ONLY** | 순수 대중교통 | 2호선 → 341번 |
| **B: FIRST_MILE** | 이동수단 + 대중교통 | 따릉이 5분 → 지하철 35분 |
| **C: LAST_MILE** | 대중교통 + 이동수단 | 지하철 25분 → 따릉이 8분 |
| **D: FULL_MIXED** | 이동수단 + 대중교통 + 이동수단 | 따릉이 5 + 지하철 20 + 따릉이 6 |
| **E: MOBILITY_ONLY** | 이동수단만 | 직선거리 < 수단 최대범위 |

### 1-3. 의사코드

```kotlin
fun search(origin, destination): List<Route> {
    val baseLegs = ODsay.route(origin, destination)
                        ?: haversineFallback(origin, destination)
    val baseRoute = Route(baseLegs, TRANSIT_ONLY)

    val candidates = MobilityType.ALL.flatMap { type ->
        val config = MobilityConfig.of(type)   // 범위, 최소거리
        listOf(
            patternB(origin, destination, baseLegs, type, config),
            patternC(origin, destination, baseLegs, type, config),
            patternD(origin, destination, baseLegs, type, config),
            patternE(origin, destination, type, config)   // 직선거리 필터
        )
    }.flatten()

    return (candidates + baseRoute)
        .map { RouteEvaluator.evaluate(it, baseRoute, baseMinutes) }
        .sortedByDescending { it.score }
        .take(5)
}
```

### 1-4. 탐색 실패 시 Observability

Mixed 후보 0개이면 `OPTIMAL` 은 **"왜 안 만들어졌는지"** 를 진단 수집 (`GenerationDiagnostic`):
- `NO_CANDIDATE_HUB` — 후보 허브 자체가 없음
- `NO_PICKUP` — 대여 가능 기기 없음
- `NO_DROPOFF` — 반납 가능 정류소 없음
- `SAME_PICKUP_DROPOFF` — 동일 정류소 조합만 남음
- `DIRECT_DISTANCE_EXCEEDED` — 수단 범위 초과

→ 프론트 `MixedRouteDiagnostics` 컴포넌트가 이를 사용자에게 **"왜 혼합 경로가 없나"** 로 설명.

---

## 2. Two-Phase Hub Selection (Primary + Fallback)

**파일**: `HubSelector.java`, `BaselineTransitHubSearchAdapter.java`
**핵심 아이디어**: "원하는 반경 조건" 으로 먼저 찾고, 없으면 **조건 완화해서** 가장 가까운 것을 선택.

### 2-1. 2단계 구조

```
PRIMARY: 이상적 조건 (minEffective ≤ 거리 ≤ maxRange)
    ↓ empty?
FALLBACK_NEAREST: 완화 조건 (minEffective × 0.6)
    → 가장 가까운 순서 top-K
    → metadata.selectionStrategy = "FALLBACK_NEAREST"
```

- `primary` 는 이동수단 효율이 의미 있는 거리 (예: 따릉이 700m~10km) 중 가까운 순 top-K
- `fallback` 은 그마저 없을 때 (밀집 정류장 사이에 끼인 목적지) 완화된 기준으로 **아예 없는 것보단 가까운 걸** 선택

### 2-2. 왜 Fallback 이 필수인가

실측 벤치마크 (A-5) 에서 OD 30쌍 중 8쌍은 primary 가 0개였다.
- 예: "서초 아파트 → 서초 카페" — 경로 길이 < 700m
- fallback 없으면 "혼합 경로 불가" 응답 → 사용자 이탈

### 2-3. 메타데이터 추적

선택된 Hub 는 다음 정보를 metadata 로 들고 다닌다 → 점수 계산과 디버깅에 사용.

```java
metadata.put("selectionPhase",   "FIRST_MILE");
metadata.put("selectionStrategy", "PRIMARY");
metadata.put("selectionRank",    "3");           // 후보 중 3번째 선택
metadata.put("candidateCount",   "7");
metadata.put("distanceToAnchorMeters", "523");
metadata.put("transitHubType",   "SUBWAY_STATION");
```

### 2-4. Hub Type 추론

역 이름 문자열 패턴 + baseline leg 의 mode 로 타입을 역추론.

```java
if (name.matches("^\\d+\\..*"))      return BIKE_STATION;     // 예: "123.강남역"
if (name.contains("역")
    && !name.contains("사거리"))      return SUBWAY_STATION;
// 근접 leg 의 mode 로 판단
if (mode == "SUBWAY") return SUBWAY_STATION;
if (mode == "BUS")    return BUS_STOP;
return MOBILITY_TRANSFER_POINT;
```

---

## 3. Candidate Point Selection (중간 정류장 발굴)

**파일**: `CandidatePointSelector.java`
**핵심 아이디어**: Baseline 의 **30~80% 구간** 정류장을 후보로. 양 끝(OD 근접)은 제외.

### 3-1. 왜 30~80% 구간인가

| 구간 | 이유 |
|------|------|
| 0~30% (앞부분) | OD 출발점과 너무 가까움 → 이동수단 효율 낮음 |
| **30~80% (중간)** | **여기서 갈아타면 라스트마일 이득 최대** |
| 80~100% (끝부분) | 이미 목적지 근접 → 갈아탈 이유 없음 |

(퍼스트마일은 역으로 **0~30%** 구간을 사용)

### 3-2. 중복 제거 (deduplicateNearby)

정류장이 120m 이내에 몰려 있으면 대표 1개만 유지.

```java
private static final double DUPLICATE_STOP_THRESHOLD_METERS = 120.0;
```

이유: ODsay 가 "지하철 2호선 + 5511번 버스" 를 보여줄 때 둘의 정류장이 거의 같은 위치에 있음. 둘 다 후보로 만들면 탐색 공간이 2배로 뜨지만 결과는 같음.

### 3-3. 이중 폴백 정책

Primary ("범위 안") → Strict Fallback ("범위 안의 최근접") → Relaxed Fallback ("최소거리를 60% 로 완화")

```java
int relaxedMinEffectiveDistance(MobilityConfig config) {
    return Math.max(250, (int) Math.round(config.minEffectiveDistanceMeters() * 0.6));
}
```

따릉이 기준 minEffective=700m → 완화 시 420m. 이 차이가 밀집 지역 후보를 살림.

### 3-4. 실제 정류장 좌표 vs 선형 보간

ODsay `passStopList` 로 **실제 중간 정류장 좌표** 를 받으면 그걸 쓰고, 없으면 stationCount 기반 **선형 보간** 으로 근사:

```java
double latStep = (end.lat - start.lat) / (count - 1);
double lngStep = (end.lng - start.lng) / (count - 1);
for (int i = 0; i < count; i++) {
    stops.add(new Location(name, start.lat + i*latStep, start.lng + i*lngStep));
}
```

선형 보간은 "대중교통이 직선으로 움직이는 근사" 라 오차가 있음. 실 좌표가 있을 때가 훨씬 정확.

---

## 4. Two-Phase Walking Calculation (Haversine → Tmap)

**파일**: `CandidatePointSelector`, `HubSelector`, `MobilitySegmentBuilder`, `TmapPedestrianClient`
**핵심 아이디어**: **넓은 필터링은 Haversine 으로 싸게**, **정밀 렌더링은 Tmap 보행 API 로 정확하게**.

### 4-1. 왜 나눠서 계산하는가

| 필요 | 방식 | 비용 | 정확도 |
|------|------|------|--------|
| 수백 개 후보 중 top-K 필터 | Haversine 직선거리 | O(1) | 중 (도로 우회 무시) |
| 실제 렌더링용 도보 경로 | **Tmap Pedestrian API** | 1 호출당 ~100ms + 요금 | 고 (도로망 기반) |

수백 개 후보 모두 Tmap 을 치면 Tmap rate limit + 초당 응답시간 초과. 그래서 **필터 → 정밀** 2단계.

### 4-2. 흐름

```
[후보 30개]
   ↓ Haversine 필터 (maxRange/minEffective 체크)
[후보 12개]
   ↓ selectionRank 기반 top-K 자르기
[후보 5개]
   ↓ HubSelector.selectXxxHubs → 후보 확정
[확정 5개]
   ↓ MobilitySegmentBuilder.build
     → 각 후보에 대해 실제 Tmap 보행 API 호출 (병렬, Mono.zip)
     → 접근 도보 + 이동수단 + 이탈 도보 조립
```

### 4-3. WALK Leg 삽입 임계값

```java
private static final int WALK_INSERT_THRESHOLD_METERS = 20;
```

pickup/dropoff 지점과 목적지 사이가 20m 이하면 WALK leg 을 **만들지 않음** (GPS 오차 수준). UI 가 1분짜리 의미 없는 leg 을 여러 개 보여주지 않게 하는 장치.

### 4-4. 거리 대비 시간 예측 vs 실측

`haversineTransitMinutes` — 대중교통이 완전히 없을 때 폴백용 추정 공식.

```java
distKm × 1.4 (우회계수) ÷ 25 km/h × 60
```

- **1.4** = 서울 시내 평균 우회율 (실측 기반 보정)
- **25 km/h** = 서울 평균 대중교통 이동속도 (버스·지하철 복합)

---

## 5. 6-Dimensional Weighted Scoring

**파일**: `RouteScoreCalculator.java`, `RouteEvaluator.java`, `RouteReliabilityMetrics.java`
**핵심 아이디어**: 시간/환승/비용/보행/접근보행/신뢰도 **6축** 을 사용자 선호 프로파일로 가중합.

### 5-1. 기본 공식

```
score =  time       × w_time
       + transfer   × w_transfer
       + cost       × w_cost
       + walk       × w_walk
       + accessWalk × w_accessWalk
       + reliability× w_reliability

각 축은 0.0~1.0 정규화: 1.0 - min(value / max, 1.0)
```

### 5-2. 프로파일 (가중치 테이블)

| 축 | RELIABILITY (기본) | TIME_PRIORITY |
|----|-------------------|---------------|
| time | 0.40 | **0.72** |
| transfer | 0.15 | 0.08 |
| cost | 0.10 | 0.03 |
| walk | 0.10 | 0.03 |
| accessWalk | 0.10 | 0.02 |
| **reliability** | **0.15** | 0.12 |

설계 원칙:
- 기본은 **RELIABILITY** — "실제 갈 수 있는 경로" 를 우선
- TIME_PRIORITY 는 급한 사용자용 — 시간 70% 에 몰빵
- cost 와 walk 는 아시아 도시 특성상 변별력 낮아 가중치 낮게

### 5-3. Reliability 축 — "실제로 갈 수 있나" 점수

단순히 "시간 짧음" 만 보는 게 아니라 **운영 리스크** 를 벌점으로 차감:

```java
score = 1.0
    - weakDropoffPenalty         (반납소가 불안정)
    - lowAvailabilityPenalty     (따릉이 재고 < 2대)
    - sharedMobilityPenalty      (공유 수단은 원래 변동성 큼)
    - lowBatteryPenalty          (킥보드 배터리 < 20%)
    - weakPickupAccessPenalty    (대여소까지 도보 > 1.1km)
    - weakHubDetourPenalty       (허브가 목적지에서 너무 우회)
    - accessWalkPenalty          (접근 도보 > 5분)
```

### 5-4. Top-1 재평가 (recommended 플래그)

```java
// 1) 전체 후보를 recommended=false 로 평가
// 2) sort by score → top 5
// 3) top[0] 만 recommended=true 로 재평가
//    → insights 에 "추천 이유" 를 추가로 생성
```

이유: 모든 후보에 "추천 이유" 를 붙이면 LLM 호출 비용 × 후보수. Top-1 에만 붙여서 5배 절약.

---

## 6. Geohash Spatial Cache

**파일**: `GeohashKeyGenerator.java` (B-3 개선 참조)
**핵심 아이디어**: 좌표를 **150m 격자** 로 양자화해 캐시 키로. "비슷한 위치" 요청을 같은 히트로.

### 6-1. Precision = 7 의 의미

| Geohash precision | Cell 크기 |
|-------------------|-----------|
| 6 | 1.2km × 0.6km |
| **7** | **153m × 153m** ← 본 프로젝트 선택 |
| 8 | 38m × 19m |

서울 지하철 역 간격이 평균 700~1200m, 아파트 단지 한 블록이 ~150m.
→ precision=7 이면 **"같은 블록" 수준에서 캐시 공유**.

### 6-2. 형식

```
단일 좌표: "wydm9qh"              (precision=7)
경로 OD:   "wydm9qh|wydm7zk"     (origin|destination)
```

### 6-3. 성과 (B-3 개선)

| 지표 | Before (좌표 원본) | After (Geohash) |
|------|-------------------|----------------|
| ODsay 캐시 히트율 | 46.9% | **80.4%** (1.71×) |
| 평균 응답시간 | - | - |

이유: "서초 아파트 정문" 과 "서초 아파트 후문" 이 같은 격자 → 같은 캐시.

### 6-4. 알려진 한계

격자 경계 근처의 두 좌표 (10m 차이라도) 가 서로 다른 격자면 캐시 미스.
개선 방안: `forRouteWithNeighbors()` — 인접 8칸까지 조회 (구현 예정, ADR-TBD).

---

## 7. Accessibility Post-Processor

**파일**: `AccessibilityPostProcessor.java` (C-3 개선 참조)
**핵심 아이디어**: 접근성 제약을 **라우팅 파이프라인에 침투시키지 않고** 결과 후처리로 해결.

### 7-1. 왜 Post-Process 인가

대안:
- (A) `OptimalSearchStrategy` 내부에서 매 단계 접근성 체크 → 로직 복잡도 폭발
- (B) **결과 집합에 한해 필터/재계산** ← 채택

원칙:
- 라우팅 알고리즘 변경 없음
- Accessibility 옵션 추가/제거가 다른 로직에 0 영향

### 7-2. 처리 내용

```java
if (ctx.wheelchairAccessible()) {
    // 엘리베이터 없는 환승역이 포함된 경로 제거
    routes.removeIf(containsInaccessibleStation);
}
if (ctx.walkingSpeedKmh() != null) {
    // WALK leg 의 duration 을 사용자 속도로 재계산
    // e.g. 3km/h (노인) → ratio=1.5 → 시간 1.5배
    recomputeWalkingDuration(route, ctx.walkingSpeedKmh());
}
```

### 7-3. AccessibilityStationRegistry

서울 지하철역별 엘리베이터 보유 정보를 정적 테이블로 유지 (`AccessibilityStationRegistry`).
passThroughStations 에 포함된 역까지 모두 검증 → 중간 환승역까지 포함해 안전 판정.

---

## 8. SSE Change Detection

**파일**: `RouteStreamService.java` (A-1 개선 참조)
**핵심 아이디어**: 30초마다 재탐색하고 **"의미 있는 변화" 만** UPDATE 로 push. 나머지는 HEARTBEAT.

### 8-1. 변화 판정 함수

```java
static String changeReason(List<Route> prev, List<Route> cur) {
    if (cur == null || cur.isEmpty()) return null;
    if (prev == null || prev.isEmpty()) return "초기 결과 도착";

    Route prevRec = prev.firstOrNull { it.recommended };
    Route curRec  = cur.firstOrNull  { it.recommended };

    if (!prevRec.routeId.equals(curRec.routeId))
        return "추천 경로 변경 (${prevRec.type} → ${curRec.type})";

    int delta = abs(prevRec.totalMinutes - curRec.totalMinutes);
    if (delta >= 2) return "추천 경로 ${delta}분 변화";

    return null;   // HEARTBEAT
}
```

### 8-2. 임계값 선택 근거

- **30초 폴링** — ODsay/따릉이 TTL (30초) 과 동기. 캐시 1사이클당 1회 재탐색.
- **2분 변화 임계값** — 1분 이하 변화는 추천 흔들림으로 사용자를 혼란만 시킴.
- **5분 최대 수명** — Spring MVC async request-timeout 10분 내에서 안전하게 마무리.

### 8-3. Backpressure

Flux.interval 은 컨슈머 지연 시 `onBackpressureDrop` 필요할 수 있지만, SSE 는 TCP 흐름제어가 받아줌. 명시적 조치 없이 정상 동작.

---

## 9. Design Non-Goals (본 프로젝트가 채택하지 않은 알고리즘)

### 9-1. A\*, Dijkstra, CH, ALT

도로 그래프 최단경로는 **외부 엔진(ODsay/Tmap)이 담당**. 자체 OSM 그래프를 띄워 다시 풀 이유가 없다.
→ **자체 OSM + OSRM 도입은 "외부 rate-limit 해방" 이 필요해질 때.** 현재는 ODsay 캐시 히트율 80%+ 로 관리 가능.

### 9-2. RAPTOR / Connection Scan (CSA)

대중교통 시간표 기반 라운드 기반 탐색 — ODsay 내부가 이 층을 담당. 덮어쓸 실익 0.

### 9-3. 베이지안 최적화 / 강화학습 (가중치 튜닝)

6차원 가중치는 현재 **하드코딩 2개 프로파일** (RELIABILITY / TIME_PRIORITY).
→ 사용자 피드백 데이터가 쌓이면 *"사용자별 최적 가중치"* 를 ML 로 학습하는 게 의미 있음. Phase 2 로 보류.

### 9-4. k-d Tree / R-Tree (공간 인덱스)

Hub 후보 수가 건당 <50개라 선형 탐색으로 충분. "n² 이 문제" 가 되는 순간이 오면 도입.

---

## 10. 요약 — "이 프로젝트가 만든 것"

```
외부: A*, Dijkstra, RAPTOR  (도로·시간표 최단경로)
  ↓
Orchestration (자체 설계)
  ├── Baseline-Guided Recomposition (5패턴 병렬)
  ├── 2-Phase Hub Selection (primary + fallback)
  ├── 30~80% Candidate Window + 중복 제거
  ├── 2-Phase Walking (Haversine 필터 → Tmap 정밀)
  ├── 6-Dim Weighted Scoring (2 프로파일 + 7가지 벌점)
  ├── Geohash Spatial Cache (150m × 150m)
  ├── Accessibility Post-Processor (비침투 설계)
  └── SSE Change Detection (2분 임계 + 30초 폴링)
  ↓
결과: 설명 가능한 다중 이동수단 추천 5개
```

---

## 관련 문서

- [B-3: Geohash Spatial Caching](../improvements/2026-04-17-B3-geohash-spatial-caching.md)
- [C-3: Accessibility](../improvements/2026-04-20-C3-accessibility.md)
- [A-1: SSE Route Stream](../improvements/2026-04-22-A1-sse-route-stream.md)
- [A-5: Real User Benchmark](../improvements/2026-04-23-A5-real-user-benchmark.md)
- [A-6: Place Autocomplete Fallback](../improvements/2026-04-23-A6-place-autocomplete-fallback.md)
- [T-7: TAGO Kill Switch](../improvements/2026-04-23-T7-tago-kill-switch.md)
