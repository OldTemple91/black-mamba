# RAG Phase 6: 데이터 규모 확장 + 운영 메트릭 노출

> 작업일: 2026-04-22
> 담당 Phase: RAG 시리즈 최종 보강
> 공수: 실측 약 2.5시간
> 커밋: TBD

---

## 1. 배경 (Why)

### 1-1. RAG-5 까지의 "완성" 의 구멍
[RAG-1 ~ RAG-5](./README.md) 로 기능 + 품질 방어선을 갖췄지만, 벡터 DB 에 쌓인
데이터가 **시드 20건 + 요청 누적 ~30건** 수준이었다. RAG 의 본질적 가치인
**"데이터 근거 기반 추천"** 을 구현하려면 **데이터 양 자체의 규모** 가 필요하다.

구체적으로:
- [RAG-4](./2026-04-22-RAG4-llm-narrative.md) 의 LLM narrative 가
  "비슷한 과거 이력에 따르면..." 이라고 말하지만, **"N건 중 M건이 이 방식"** 같은
  진짜 통계 주장을 할 만한 데이터가 없었다
- RAG-2 시드가 20건 + 한 시점에 한번에 저장되어 **임베딩 시각 맥락이 모두 동일**
  ("평일 점심" 하나로 수렴)
- 유사 검색 score 가 0.5~0.6 대에 머물러 벡터 공간 분산이 좁았다

### 1-2. 운영 관점에서의 구멍
RAG-5 에서 품질 게이트 / 중복 감지 / 할루시네이션 감지를 구현했지만 **모두 로그로만**
확인됐다. Grafana 대시보드에서 추세로 볼 수 없으면 **운영 중인 품질을 숫자로 증명할 수 없다.**

### 1-3. 목표
1. **데이터 규모 확장**: 20 → 200건, 5가지 시간대 × 2가지 선호도 × 20 OD 로
   임베딩 공간을 구조적으로 다양화
2. **운영 메트릭 노출**: 품질 게이트 제외율, 중복 스킵율, 할루시네이션 감지율,
   유사 이력 조회 건수를 Prometheus 로 노출 → Grafana 연결 가능

---

## 2. 설계 결정

### 2-1. Seeder 확장 차원 (OD × 시간대 × 선호도 = 200)

**차원 선정 근거:**
| 차원 | 개수 | 근거 |
|------|------|------|
| OD 페어 | 20 | 서울 주요 역/생활권 — 커버리지 확보 |
| 시간대 | 5 | 아침 러시 / 점심 / 저녁 러시 / 심야 / 주말 오후 — `contextTag()` 분기 전부 커버 |
| 선호도 | 2 | RELIABILITY / TIME_PRIORITY — 전부 |
| **합계** | **200** | 관리 가능 + 통계 의미 확보 |

**과거 시각 주입 방식:**
- `Port.save(..., Instant timestamp)` 오버로드 신설
- Seeder 가 "지난 주 월요일 8시 / 12시 / 18시 / 23시 + 지난 주 토요일 14시" 를 계산해
  5개 `Instant` 로 만들어 주입
- Adapter 가 `describeAt(route, ..., when)` 을 호출해 맥락 태그를 해당 시각 기준으로 부여

### 2-2. 메트릭 스키마 — 단일 계열 + 라벨 세그먼트

폭증 방지를 위해 **메트릭 이름을 최소화** 하고 라벨로 구분:

| 메트릭 이름 | 타입 | 라벨 | 용도 |
|-------------|------|------|------|
| `navigation.rag.history.saved` | Counter | - | Qdrant upsert 성공 건수 |
| `navigation.rag.history.rejected` | Counter | `reason=quality_gate \| duplicate` | 저장 거부 건수 (원인별) |
| `navigation.rag.narrative.generated` | Counter | - | LLM narrative 성공 생성 |
| `navigation.rag.narrative.fallback` | Counter | `reason=hallucination \| error_or_short` | narrative 폴백 (원인별) |
| `navigation.rag.narrative.similar_hit` | DistributionSummary | - | 참고한 유사 이력 건수 분포 (p50, p95) |

**메트릭 설계 포인트:**
- Counter + `reason` 라벨 = Grafana 에서 `sum by(reason)` 으로 원인별 비율 시각화
- DistributionSummary + percentile = "평균 몇 건 참고했는가" 추세 관측

### 2-3. Hexagonal 유지 — application 레이어 메트릭 가능성

`MeterRegistry` 는 Spring Boot 가 제공하는 infra 성격 Bean 이지만, application 레이어에서도
**Spring DI 를 통해 주입**받을 수 있다. 도메인/애플리케이션에서 **"나는 메트릭 계측 대상이다"**
라는 의도만 담고 실제 수집기 구현은 Spring 이 관리.

### 2-4. 발견한 버그 — `Mono<Void>.subscribe(onNext, ...)`

```java
// 버그 (Mono.fromRunnable 의 반환은 Mono<Void> — onNext 없음)
Mono.fromRunnable(...)
    .subscribe(
        v -> savedCounter.increment(...),  // ❌ 절대 호출 안 됨
        err -> log.warn(...)
    );
```

**문제 확인 경로:** 저장 로그는 찍히는데 Prometheus `history_saved_total = 0`. 로그 vs
메트릭 불일치로 발견.

**수정:**
```java
Mono.fromRunnable(...)
    .subscribeOn(Schedulers.boundedElastic())
    .doOnSuccess(v -> savedCounter.increment(savingCount))  // onComplete 시점
    .doOnError(err -> log.warn(...))
    .subscribe();
```

---

## 3. 구현 요약

### 3-1. Port / Adapter 시각 주입
- `RouteHistoryPort.save(Route, Location, Location, String, Instant)` 오버로드 추가
- `default void save(...)` 는 `timestamp=null` 로 위임 → 기존 호출자 영향 0
- `QdrantRouteHistoryAdapter.save()` 에서 timestamp → `describeAt(..., when)` +
  payload `createdAt` 에도 반영

### 3-2. Seeder 전면 개편
- OD 풀 20개 (강남/홍대/서울역/잠실/여의도/판교/이태원/신림/건대/성수/합정/한남/왕십리/용산/강변 등)
- `buildTimeSlots()` 로 5개 고정 과거 시각 계산 (지난 주 월 + 토 조합)
- 3중 루프로 OD × 시간대 × 선호도 = 200건 일괄 저장
- 시드 끝 로그: `"Seed 완료 — OD 20개 × 시간대 5개 × 선호도 2개 = 200건 저장"`

### 3-3. 메트릭 계측
- `RouteHistoryRecorder`: saved / quality_gate_rejected / duplicate_rejected 카운터
- `OllamaNarrativeGenerator`: generated / fallback(hallucination) / fallback(error_or_short)
- `RouteNarrativeEnhancer`: similar_hit DistributionSummary (p50, p95)
- `application/build.gradle` 에 `micrometer-core` 명시 추가

### 3-4. 버그 픽스
- Recorder 의 async subscribe 가 Mono<Void> 에서 onNext 가 호출되지 않는 문제 → `doOnSuccess` 로 전환

---

## 4. 검증 & 성과 (Result)

### 4-1. 데이터 규모

| 지표 | Before (RAG-5) | After (RAG-6) |
|------|-----------------|----------------|
| Qdrant 총 points | ~20 | **200** |
| 시간대 태그 종류 | 1 (시드 투입 시각) | **5 균등 분포 (각 40건)** |
| 평균 similarity score (top 5) | 0.54~0.56 | **0.70~0.72 (쿼리 1 실측)** |

### 4-2. 시간대 태그 분포 (실측)

```
평일 아침 러시아워  40건
평일 점심         40건
평일 저녁 러시아워  40건
평일 심야         40건
주말 오후         40건
```
→ 5개 밴드 정확히 균등 분포.

### 4-3. 유사 검색 품질 상승

**쿼리: "평일 저녁 러시아워 빠른 지하철"**
```
#1  0.721  | 평일 저녁 러시아워, 용산역에서 강남역까지 지하철 직행, 25분 1,650원...
#2  0.720  | 평일 저녁 러시아워, 서울역에서 강남역까지 지하철 직행, 28분 1,650원...
#3  0.713  | 평일 저녁 러시아워, 여의도역에서 서울역까지 지하철 직행, 18분 1,650원...
#4  0.704  | 평일 저녁 러시아워, 서울역에서 잠실역까지 지하철 직행, 40분 1,650원...
#5  0.702  | 평일 저녁 러시아워, 신림역에서 강남역까지 지하철 직행, 20분 1,450원...
```
→ **전부 "평일 저녁 러시아워" 태그 + 지하철 직행 + TIME_PRIORITY** 정확 매칭.
   score 대폭 상승(0.56 → 0.72, **+0.16**).

### 4-4. 운영 메트릭 (Prometheus)

```
# 실측 스냅샷 (운영 후)
navigation_rag_history_saved_total                    1.0
navigation_rag_history_rejected{reason="quality_gate"} 1.0   ← 실측 포착 (도보만 있는 경로)
navigation_rag_history_rejected{reason="duplicate"}    0.0
navigation_rag_narrative_generated_total               2.0
navigation_rag_narrative_fallback{reason="hallucination"} 1.0   ← 실측 포착 (7분 vs 32분)
navigation_rag_narrative_fallback{reason="error_or_short"} 0.0
navigation_rag_narrative_similar_hit{quantile="0.5"}   3.0
navigation_rag_narrative_similar_hit{quantile="0.95"}  3.0
```

**전 메트릭 실측 값을 확보**했고 특히:
- `quality_gate=1.0` : 품질 게이트가 실제로 기능 중 (도보만 있는 경로 차단)
- `hallucination=1.0` : 할루시네이션 감지가 실제로 폴백 유발
- `similar_hit p95=3` : 매 요청에 3건씩 유사 이력을 안정적으로 활용

### 4-5. 버그 1건 조기 포착

Mono<Void> 의 `subscribe(onNext, ...)` 버그를 **"로그는 찍히는데 메트릭은 0"** 이라는
불일치로 발견해 `doOnSuccess` 로 수정. 메트릭이 없었다면 묻혔을 결함.
→ **"메트릭 노출 자체가 품질 검증 도구"** 가 되는 경험.

---

## 5. 한계 & 다음 단계

### 5-1. 시드는 여전히 "합성 데이터"
200건이지만 모두 수작업으로 정의한 샘플. 실제 사용자 행동 분포와는 거리가 있음.
- **개선 방안:** 실 트래픽 축적 후 주기적 seeding 재실행 (불필요할 수도, 자연 증가)
- 또는 ODsay/서울시 열린데이터 통계로 OD 빈도 분포를 구해 OD 풀을 현실에 맞게 가중

### 5-2. 메트릭은 있지만 대시보드는 아직 없음
Prometheus 스크레이핑은 되지만 Grafana 에 전용 패널이 없음.
- 후속: "RAG 운영" 대시보드 jsonnet/yaml 추가 (품질 게이트 추세, 할루시네이션 비율,
  유사 이력 hit 분포 등 5~6 패널)

### 5-3. 할루시네이션 감지의 한계는 그대로
RAG-5 에서 적은 바와 같이 **수치 불일치만** 감지. 지명·이동수단 거짓 언급은 미지.

---

## 6. 발표 스토리

### 30초 버전
> "RAG 기능/품질을 다 만든 뒤, **데이터 양 자체가 RAG 신뢰도의 바닥** 이라는
> 관점에서 시드를 20건 → 200건으로 확장했습니다. OD × 시간대 × 선호도 3차원으로
> 구조적 다양성을 확보했고, 결과 top-5 유사도 score 가 0.56 → 0.72 로 뛰었습니다.
> 동시에 RAG 품질 지표 8개를 Prometheus 로 노출해 **운영 중인 RAG 의 품질을
> 숫자로 증명** 할 수 있게 했습니다."

### 3분 버전
1. **문제 정의**
   - RAG-4 narrative 가 "비슷한 이력에 따르면..." 이라는 문구를 썼지만 근거가 될 데이터가 빈약
   - RAG-5 품질 방어선이 있는데 **로그로만** 확인 가능 → 추세/비율 시각화 불가
2. **해결**
   - Port/Adapter 에 `Instant timestamp` 주입 통로를 만들어 Seeder 가 과거 시각을 선택 가능하게
   - 시드 20 → 200 (OD 20 × 시간대 5 × 선호도 2)
   - 5개 메트릭 계열 노출 (Counter 3종, DistributionSummary 1종, tag 라벨로 원인 구분)
3. **발견한 버그**
   - Mono<Void> 의 subscribe 에서 onNext 안 호출되는 문제를 "로그 vs 메트릭 불일치" 로 발견
   - 메트릭을 노출한 덕분에 묻힐 뻔한 버그를 포착
4. **결과 실측**
   - 유사도 score +0.16 상승
   - 품질 게이트 실제 1건 포착, 할루시네이션 실제 1건 포착

### 포지셔닝
> **"RAG 는 기능 3단계(R+A+G) 만 만들어서 끝나는 게 아닙니다.**
> **데이터 양이 신뢰도의 바닥이고, 메트릭이 품질의 거울입니다.**
> 두 축을 마지막에 의식적으로 채워 넣어 **'운영 준비된 RAG'** 에 도달했습니다."

---

## 7. 관련 문서
- [RAG-1 자연어 경로 검색](./2026-04-20-RAG1-nlp-route-search.md)
- [RAG-2 Qdrant 벡터 DB](./2026-04-22-RAG2-qdrant-similar-routes.md)
- [RAG-4 LLM narrative](./2026-04-22-RAG4-llm-narrative.md)
- [RAG-5 품질 보강](./2026-04-22-RAG5-quality-reinforcement.md)
- [ROADMAP.md](../roadmap/ROADMAP.md)
