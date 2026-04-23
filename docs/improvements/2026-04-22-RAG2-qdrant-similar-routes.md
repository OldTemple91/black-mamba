# RAG Phase 2: Qdrant 벡터 DB + 유사 경로 검색

> 작업일: 2026-04-20 ~ 2026-04-22
> 담당 Phase: ROADMAP.md RAG-2
> 공수: 실측 약 12시간 (4일 × 3시간)
> 커밋:
> - `44a7bad` Day 1 인프라 + 포트/어댑터 골격
> - `fe418b8` Day 2 자동 저장 hook + 시드 스크립트
> - `115bd03` Day 3 의미 유사 검색 엔드포인트 + 하이브리드 필터
> - `705ba58` Day 3+ A+B+C 보강 (threshold / mobility / @Observed)

---

## 1. 배경 (Why)

### 1-1. Phase 1 의 한계
[RAG Phase 1](./2026-04-20-RAG1-nlp-route-search.md) 에서 자연어 진입점(`/api/nlp/routes`)을
만들었지만, 이는 **"LLM 으로 의도 파싱 → 기존 결정론 엔진 호출"** 구조였다.
"RAG" 라는 이름을 썼지만 실제로는 **Retrieval 이 없는** 구조 —
`R(etrieval)` + `A(ugmented)` + `G(eneration)` 중 R 이 빠진 상태.

### 1-2. "경험의 누적" 이 없는 stateless 아키텍처
기존 프로젝트는 완전 stateless:
- 요청 → 외부 API 조합 → 응답 → 종료
- 같은 OD 를 100번 검색해도 100번 외부 호출
- "과거에 어떤 사용자가 어떤 경로를 선호했는지" 를 저장할 곳이 없음
- 따라서 **설명 가능성**(왜 이 경로가 추천인가)도 약함

### 1-3. 목표
벡터 DB 레이어를 추가해 **"Retrieval"** 을 구현한다:
1. 경로 탐색 결과를 벡터 임베딩 형태로 누적 저장
2. 자연어 쿼리에 대해 **의미 유사도 + 공간 필터 + 이동수단 필터** 하이브리드 검색
3. 기존 관측성 스택(Loki/Tempo/Prometheus) 과 일관된 통합
4. Hexagonal Architecture 원칙 유지 — 벡터 DB 교체 가능성 확보

---

## 2. 설계 결정

### 2-1. 왜 Qdrant 인가 (vs Milvus / pgvector)

| 후보 | 장점 | 단점 | 선택 여부 |
|------|------|------|----------|
| **Qdrant** | 경량(Rust, 단일 바이너리), gRPC, Spring AI starter 지원, 대시보드 내장 | 단일 노드 한정 | ✅ 채택 |
| Milvus | 대규모(수십억) 지원, 분산 | 인프라 무거움 (etcd/pulsar/minio) | ❌ 포트폴리오 스케일에 과함 |
| pgvector | 기존 Postgres 스택 재활용 | 확장·필터 기능 상대적 약함 | 차후 POI용 (RAG-3) |

**판단 기준:** 이력 20~수만 건 스케일 + Docker 1줄 + Spring AI 공식 지원.
`qdrant/qdrant:v1.13.6` 으로 docker-compose 에 붙여 **gRPC 6334** 포트 하나로 운영.
(Spring AI 1.0.2 번들 클라이언트 v1.13 과 정확히 호환되는 버전으로 고정 — 버전 스큐 경고 0.
 추후 Spring AI 업그레이드 시 Qdrant 서버도 같이 올리면 됨.)

### 2-2. 왜 `bge-m3` 인가

임베딩 모델은 "쿼리/문서의 의미를 벡터 공간에 매핑" 하는 핵심 엔진.
한국어 지명/이동수단 용어가 많은 도메인에서 품질 차이가 검색 만족도를 결정.

| 모델 | 차원 | 한국어 MTEB | 모델 크기 | 비용 |
|------|------|-------------|-----------|------|
| nomic-embed-text | 768 | 중위권 | 274MB | 무료 |
| **bge-m3 (BAAI)** | **1024** | **상위권, 다국어 특화** | 1.2GB | **무료** |
| OpenAI text-embedding-3-small | 1536 | 최고 | (클라우드) | 유료 |

**bge-m3 선택 이유:**
- 한국어 지명 구분력이 `nomic-embed-text` 대비 확연히 우수
- Ollama 로 로컬 실행 → 데이터 외부 반출 없음
- 1024차원 정규화(L2 norm=1) 출력 → 코사인 유사도 = 내적, 검색 고속
- `OLLAMA_EMBEDDING_MODEL` 환경변수로 교체 가능하게 설계

### 2-3. 왜 "텍스트 서술" 을 임베딩하나 (vs 수치 피처 벡터)

경로를 벡터화할 때 두 가지 방식이 가능:

```
방식 A: 수치 피처 벡터
  [37.4979, 127.0276, 37.5570, 126.9240, 36분, 1650원, 2환승, ...]
  → 차원 낮고 "거리상 유사" 만 포착
  → "빠른 지하철" 같은 추상 개념 못 잡음

방식 B: 자연어 서술 → bge-m3 임베딩  ✅ 채택
  "강남역에서 홍대입구까지 지하철 직행, 36분 1,650원.
   안정적이고 환승 적은 경로."
  → 1024차원, 의미적 유사도까지 포착
  → "빠른 지하철 경로" 쿼리가 "지하철 직행 14분" 서술과 매칭
```

**핵심:** 벡터 DB 의 본질적 가치는 **"의미" 거리** 측정. 수치 피처는 기존 RDB 에서도
가능하지만, "환승 적은" 같은 추상 개념은 의미 임베딩만이 포착한다.

Payload 필터(geohash, mobility)는 "구조 조건" 이므로 벡터와 분리하는 것이 정석
— **의미(vector) + 구조(payload filter) 하이브리드** 구조로 설계.

### 2-4. 왜 3축 하이브리드 검색인가

```
축 1. 의미 (vector, bge-m3 임베딩)
    "빠른 지하철 경로" 같은 추상 쿼리
축 2. 공간 (payload: originGeohash / destinationGeohash, AND)
    "강남역 출발 경로만" 필터
축 3. 이동수단 (payload: has_<MobilityType>, OR)
    "따릉이 포함 경로만" 필터
추가. similarityThreshold (품질 임계값)
    "관련 없으면 빈 응답을 정직하게"
```

단일 축 검색은 "의미는 맞는데 엉뚱한 지역" 이나 "맞는 지역인데 의미 무관" 결과를
내기 쉽다. 하이브리드로 **사용자가 원하는 모든 제약을 한 쿼리에 표현** 가능.

### 2-5. 왜 Port 에서 Entry 변환을 분리했나 (Hexagonal)

도메인 규칙:
- `domain` 모듈은 **외부 의존성 0** (geohash 라이브러리도 금지)
- `application` 은 Port 만 정의, 벡터 DB 존재 모름
- `infra` adapter 가 변환 + 임베딩 + 저장 전담

이 원칙에 맞추기 위해:
- `RouteHistoryPort.save(Route, Location, Location, String preference)`
  — application 은 "Route 를 저장해달라" 만 말함
- adapter 내부에서 Route → `RouteHistoryEntry` 변환, geohash 계산, payload 구성
- **Qdrant → Milvus/pgvector 전환 시 adapter 1개만 교체** 하면 됨

또한 `RagSearchRequest` 파라미터 객체를 도입해 검색 축이 더 늘어도
Port 시그니처가 변하지 않도록 설계.

### 2-6. 왜 자동 저장은 비동기 fire-and-forget 인가

```java
// RouteHistoryRecorder
Mono.fromRunnable(() -> {
        for (Route r : toSave) port.save(r, origin, destination, preference);
    })
    .subscribeOn(Schedulers.boundedElastic())
    .subscribe(v -> {}, err -> log.warn(...));
```

이유:
- Spring AI `VectorStore.add()` 는 동기. Reactor 체인 안에서 직접 호출 시 블로킹 발생
- 이력 저장 실패(Qdrant/Ollama 장애) 가 경로 탐색 응답을 지연시키면 안 됨
- 사용자 요청 응답 속도 > 이력 저장 보장

Recorder 는 `ObjectProvider<RouteHistoryPort>` 로 Optional 주입 — Qdrant 빈이 없어도
앱은 정상 기동되며 저장만 스킵.

---

## 3. 구현 (What)

### 3-1. 변경된 파일

**신규:**
- `domain/.../route/RouteHistoryEntry.java` — 저장/검색 엔트리 record
- `application/.../route/port/RouteHistoryPort.java` — Hexagonal Port
- `application/.../route/port/RagSearchRequest.java` — 파라미터 객체
- `application/.../route/port/ScoredRouteHistoryEntry.java` — 검색 결과 래퍼(점수 포함)
- `application/.../route/RouteHistoryRecorder.java` — 비동기 저장 컴포넌트
- `application/.../route/RouteHistorySeeder.java` — 시드 20건
- `infra/.../vector/QdrantRouteHistoryAdapter.java` — Spring AI VectorStore 래퍼
- `infra/.../vector/RouteHistoryDescriber.java` — Route → 자연어 서술
- `api/.../rag/RagAdminController.java` — POST /api/rag/admin/seed
- `api/.../rag/RagSimilarRoutesController.java` — GET /api/rag/similar-routes
- `api/.../rag/SimilarRouteResponse.java` — API 응답 DTO

**수정:**
- `docker-compose.yml` — Qdrant 서비스 + app 연결
- `api/build.gradle` / `infra/build.gradle` — Spring AI Qdrant starter / VectorStore
- `application/build.gradle` — 기존 스프링 starter 활용
- `application/.../RouteOptimizationService.java` — 자동 저장 hook 1줄
- `application/.../RouteOptimizationServiceTest.java` — mock Recorder 주입

### 3-2. Payload 스키마 (Qdrant)

```json
{
  "routeId": "uuid",
  "doc_content": "강남역에서 홍대입구까지 지하철 직행, 36분 1,650원...",
  "originGeohash": "wydm6d6",
  "destinationGeohash": "wydm8jp",
  "originName": "강남역", "originLat": 37.498, "originLng": 127.0276,
  "destinationName": "홍대입구", "destinationLat": 37.557, "destinationLng": 126.924,
  "mobilityTypes": "DDAREUNGI",         // 가독용 CSV
  "has_DDAREUNGI": "Y",                 // 필터용 keyword flag
  "routeType": "MOBILITY_FIRST_TRANSIT",
  "totalMinutes": 36, "totalCostWon": 1650,
  "preference": "RELIABILITY",
  "createdAt": 1776818555               // epoch seconds
}
```
벡터 자체는 별도 저장: **1024차원 float, L2 norm = 1 (정규화됨)**.

### 3-3. 핵심 코드 변경점

#### 3-3-1. RouteHistoryDescriber — "의미 임베딩 소스" 생성
```java
// 수치가 아닌 자연어 서술로 변환 → bge-m3 임베딩 품질의 원천
public static String describe(Route route, Location origin, Location destination,
                              String preference) {
    return "%s에서 %s까지 %s, %d분 %,d원. %s%s".formatted(
        origin.name(), destination.name(),
        summarizeLegs(route.legs()),               // "지하철+따릉이 직행"
        route.totalMinutes(), route.totalCostWon(),
        preferenceNarrative(preference),            // "빠른 시간 우선"
        route.recommended() ? " 추천 경로." : "."
    );
}
```

#### 3-3-2. QdrantRouteHistoryAdapter — 3축 하이브리드 필터
```java
private Filter.Expression buildFilter(RagSearchRequest request) {
    FilterExpressionBuilder b = new FilterExpressionBuilder();
    List<Op> parts = new ArrayList<>();
    if (request.hasOriginGeohash())     parts.add(b.eq("originGeohash", request.originGeohash()));
    if (request.hasDestinationGeohash())parts.add(b.eq("destinationGeohash", request.destinationGeohash()));
    if (request.hasMobilityFilter()) {
        Op or = null;
        for (MobilityType m : request.mobilityFilter()) {
            Op flag = b.eq("has_" + m.name(), "Y");
            or = (or == null) ? flag : b.or(or, flag);
        }
        parts.add(or);
    }
    // AND 결합
    if (parts.isEmpty()) return null;
    Op combined = parts.get(0);
    for (int i = 1; i < parts.size(); i++) combined = b.and(combined, parts.get(i));
    return combined.build();
}
```
**핵심 설계:** boolean 이 아닌 `"Y"` 문자열 플래그 사용. Spring AI 의 자동
형변환(`"true"` → boolean) 과 Qdrant 매칭 연산자 호환성 문제를 회피하기 위한
안정적 keyword 타입 사용.

#### 3-3-3. RouteOptimizationService 훅 (1줄)
```java
return strategy.search(origin, destination)
    .map(routes -> accessibilityPostProcessor.apply(routes, accessibilityContext))
    .map(routes -> attachCarComparison(routes, origin, destination))
    .doOnNext(routes -> historyRecorder.recordAsync(    // ← 이 한 줄
            routes, origin, destination, recommendationPreference.name()));
```
`.doOnNext()` 로 side-effect 만 수행, 본 응답 흐름은 건드리지 않음.

### 3-4. @Observed 기반 관측성
```java
@Observed(
    name = "navigation.rag.similar_search",
    contextualName = "RAG 유사 경로 검색",
    lowCardinalityKeyValues = {"component", "RagSimilarRoutesController"}
)
public ResponseEntity<?> findSimilarRoutes(...) { ... }
```

자동으로 얻는 것:
- Tempo: `rag.similar_search` span (부모 HTTP span 에 chain)
- Prometheus: `navigation_rag_similar_search_seconds_{count,sum,max}` 자동 집계
- 라벨: `class`, `method`, `component`, `error`

기존 ODsay/따릉이/Tmap/RouteOptimizationService 와 **동일한 관측 스타일**.

### 3-5. 테스트 추가

- `RouteOptimizationServiceTest` 에 `@Mock RouteHistoryRecorder` 추가
- 단위 테스트 기존 기능 회귀 없음
- 통합 테스트(Testcontainers Qdrant)는 공수 사유로 다음 phase

---

## 4. 검증 & 성과 (Result)

### 4-1. E2E 실측 (시드 20건 기반)

| # | 쿼리 / 파라미터 | 결과 | 의미 |
|---|----|----|----|
| 1 | `q=빠른 지하철 경로&topK=5` | top 5 전부 `TIME_PRIORITY`+지하철 (score 0.54~0.56) | 추상 쿼리 매칭 |
| 2 | `q=따릉이로 출퇴근&topK=5` | top 4 `DDAREUNGI` 포함, 5위부터 score 0.49 로 급락 | 이동수단 의미 포착 |
| 3 | `q=환승 없는 안정적인 경로&topK=5` | top 5 전부 `RELIABILITY`+직행 (score 0.50~0.52) | 추상 수식어 포착 |
| 4 | `q=우주 여행&threshold=0.5` | **hitCount=0** | 무관 쿼리 정직한 빈 응답 |
| 5 | `q=출퇴근&mobility=DDAREUNGI` | 따릉이 포함 4건만 정확 매칭 | payload 필터 |
| 6 | `q=출퇴근&threshold=0.4&mobility=DDAREUNGI&originGeohash=wydm6d6` | 강남 출발+따릉이 2건 | **3축 하이브리드** |

### 4-2. Qdrant 실제 저장 샘플

```
Point id: 07a03992-84f9-4bc9-8628-6f05d8d61ac9
Vector  : [0.0042, 0.0047, -0.0056, ...] × 1024
L2 norm : 1.000000    ← 정규화됨 (코사인 = 내적)
min/max : -0.177 / 0.251
```

### 4-3. 응답 속도 (M2 Pro)

| 단계 | 소요 |
|------|------|
| 쿼리 임베딩 (bge-m3 cold) | 300~500ms |
| 쿼리 임베딩 (warm) | 50~100ms |
| Qdrant HNSW 검색 (20건 스케일) | < 10ms |
| **전체 `/api/rag/similar-routes`** | **warm 100~200ms** |

### 4-4. 관측성 자동 집계 증거

```
# Prometheus
navigation_rag_similar_search_seconds_count{...,error="none",method="findSimilarRoutes"} 2
navigation_rag_similar_search_seconds_sum{...} 0.365324499
```

기존 3축 관측성 스택(Loki/Tempo/Prometheus) 에 RAG 흐름이 **추가 설정 없이**
자동 편입.

---

## 5. 한계 & 다음 Phase

### ⚠️ "Retrieval 기술 검증 완료, 제품 UX 통합은 반쪽"

현재 구현은 `/api/rag/similar-routes` 라는 **독립 엔드포인트** 로 기능을 열었다.
의미 검색이 정확히 동작함은 6가지 실측 쿼리로 증명했지만, **실제 사용자 여정에서는
단독 의미 검색 UX 가 드물다** — 사용자는 대부분 목적지를 이미 알고 출발한다.

즉 `GET /api/rag/similar-routes?q=빠른 지하철 경로` 같은 쿼리를 사용자가 직접
치는 시나리오는 제한적이다. 진짜 제품 가치는 기존 `/api/routes` 응답에
**"비슷한 구간의 과거 이력 N건"** 이 자동으로 포함되어, 추천의 **근거**를
데이터로 제공하는 데 있다.

→ **해결 계획: ROADMAP RAG-4 에서 `/api/routes` 응답의 `carComparison.narrative`
   에 Qdrant 유사 이력 + LLM 설명을 자동 포함.**

```
Before (현재 carComparison.narrative):
  "자가용보다 3분 더 걸리지만 3,422원 절약, 탄소 998g 감소."

After (RAG-4 도입 후):
  "비슷한 구간에서 9건 중 7건이 이 경로를 선택했습니다.
   자가용보다 3분 더 걸려도 주차 걱정 없이 탄소 998g 덜 배출하면서
   편하게 앉아 가실 수 있어요."
```

현재 Phase 2 는 **Retrieval 레이어** 까지가 완성 범위, **Augmented + Generation
통합은 Phase 4** 에서 담당한다.

### ⚠️ 기타 기술 한계

- **Spring AI Qdrant client 버전 갭**: Spring AI 1.0.2 번들 client(1.13) vs 서버(1.17) 마이너 차이.
  warn 로그만 발생하고 동작엔 이상 없음. Spring AI 1.0.3+ 또는 client 직접 pinning 으로 해결 가능.
- **시드 서술의 템플릿 단조성**: 20건이 "~에서 ~까지 ~, ~분 ~원" 패턴 — 벡터 공간에서 문서가 가까이 뭉침.
  결과적으로 쿼리-문서 거리(score)가 0.5~0.6 수준으로 수렴. 순위 정렬 품질은 정확하므로 실용적 문제는 없지만,
  실 트래픽 누적 시 자연스럽게 분산이 커진다.
- **통합 테스트 부재**: Testcontainers + Qdrant 로 E2E 격리 테스트는 다음 phase 로 유예.
  현재는 Production Qdrant 로 수동 실측.

---

## 6. 발표 스토리

### 30초 버전
> "RAG Phase 2 에서 **Qdrant 벡터 DB 기반 Retrieval 레이어** 를 구축했습니다.
> 경로 탐색 결과를 `bge-m3` 로 1024차원 임베딩해 비동기로 누적 저장하고,
> **의미(벡터) + 공간(geohash) + 이동수단(payload flag) 3축 하이브리드 검색**
> 을 지원합니다. `@Observed` 로 기존 Loki/Tempo/Prometheus 3축 관측성 스택에
> 자동 편입됩니다."

### 3분 버전 (기술 깊이)
1. **설계 축**
   - Hexagonal Port 로 벡터 DB 구현을 교체 가능하게. Qdrant → Milvus/pgvector 전환 시 adapter 1개만.
   - 텍스트 서술 임베딩으로 의미, payload 필터로 구조, 둘을 분리.
   - `ObjectProvider` + `Schedulers.boundedElastic()` 로 벡터 DB 장애가 본 요청을 막지 않도록.
2. **검증 방법**
   - 6가지 실측 쿼리 (추상 / 이동수단 / 하이브리드 / 무관 쿼리 / threshold / 3축)
   - Qdrant REST API 로 실제 저장 벡터·payload 직접 확인
   - Prometheus metrics count 증가로 @Observed 자동 집계 증명
3. **자기비판 + 로드맵**
   - 현재 독립 엔드포인트는 **Retrieval 기술 검증** 용도. 진짜 제품 가치는
     `/api/routes` 응답에 narrative 로 통합되어야 발현됨.
   - Phase 4 에서 RAG 의 G(eneration) 까지 완성하고, 블랙박스 추천이 아닌
     **설명 가능한 MaaS** 를 완성할 예정.

---

## 7. 관련 문서
- [RAG Phase 1 (2026-04-20)](./2026-04-20-RAG1-nlp-route-search.md) — 자연어 진입점
- [ROADMAP.md](../roadmap/ROADMAP.md) — RAG 시리즈 전체 계획
- [observability-stack.md](../monitoring/observability-stack.md) — @Observed 기반 관측성
