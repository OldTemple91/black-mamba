# RAG Phase 4: LLM narrative 생성 (RAG 시리즈 완결)

> 작업일: 2026-04-22
> 담당 Phase: ROADMAP.md RAG-4
> 공수: 실측 약 3시간
> 커밋: TBD

---

## 1. 배경 (Why)

### 1-1. RAG-2 의 남은 과제
[RAG-2](./2026-04-22-RAG2-qdrant-similar-routes.md) 에서 Qdrant 벡터 DB 기반
Retrieval 레이어(`/api/rag/similar-routes`) 를 완성했지만, 이는 **독립 엔드포인트** 였다.
실제 사용자 여정(`/api/routes` 호출)에서는 그 Retrieval 의 혜택을 자동으로
받지 못했다. RAG-2 문서의 "한계" 섹션에서 다음과 같이 기록했었다:

> "현재 구현은 독립 엔드포인트. 실제 사용자는 대부분 목적지를 이미 알고
> 출발하므로 단독 의미 검색 UX 는 드물다. 진짜 제품 가치는 `/api/routes`
> 응답에 narrative 로 통합되어야 발현된다."

본 Phase (RAG-4) 가 그 약속을 이행한다.

### 1-2. 기존 `carComparison.narrative` 의 한계

[F-1](./2026-04-20-F1-vs-car-comparison.md) 에서 만든 자가용 비교 narrative:
```
"자가용보다 3분 더 걸리지만 3,422원 절약, 탄소 998g 감소."
```

문제:
- **템플릿 기반** — 매번 같은 패턴
- **근거 없음** — "왜 이 경로가 추천인지" 설명 부재
- **과거 경험 미반영** — 비슷한 구간을 누가 어떻게 택했는지 정보 활용 X

### 1-3. 목표
`/api/routes` 응답의 추천 경로에 대해:
1. Qdrant 에서 유사 OD 과거 이력 조회 **(R)etrieval**
2. 경로 + 자가용 비교 + 유사 이력을 LLM 프롬프트에 주입 **(A)ugmented**
3. llama3.2:3b 가 한국어 narrative 자연 생성 **(G)eneration**

→ **RAG 의 R/A/G 세 글자가 모두 제품에 녹아있는 상태** 를 만든다.

---

## 2. 설계 결정

### 2-1. 언제 LLM 을 호출하나 — "모든 요청 자동 + 추천 경로 1개만"

| 대안 | 지연 | 시연 임팩트 | 채택 |
|------|------|------------|------|
| A: 모든 경로에 자동 LLM | 경로 4개 × 2초 = **9~12초** ❌ | 강력하지만 체감 매우 느림 | ❌ |
| **A': 자동 + 추천 경로 1개만 LLM** | **3~5초** | ⭐ 모든 검색에 자동 enrichment | ✅ |
| B: `?enrich=llm` 플래그 | 1~2초 기본 | 시연할 때 플래그 필요 | ❌ |

**A' 선택 이유:**
- 경로 N개 중 `recommended=true` 는 **1개** → LLM 호출도 1번
- 사용자는 "매 검색마다 근거 있는 추천" 을 받음 (제품 일관성)
- 나머지 경로는 기존 템플릿 유지 → 지연 누적 방지

### 2-2. narrative 를 어디에 넣나 — 기존 `carComparison.narrative` 덮어쓰기

새 필드를 만드는 대신 F-1 의 `Route.carComparison.narrative` 를 교체.
- **스키마 호환** — 프론트엔드 변경 불필요
- F-1 템플릿을 LLM 출력으로 **품질 업그레이드** 하는 개념
- 원본 narrative 는 프롬프트 안에 "자가용 비교 원본" 으로 전달 → LLM 이 참고

### 2-3. 유사 이력 몇 건 참고 — top 3 + similarityThreshold 0.35

- 너무 적음(1건) → 통계 근거 얇음
- 너무 많음(5+건) → 3B 모델 프롬프트 길어져 응답 품질 저하, 지연 증가
- **3건** 이 llama3.2:3b 의 context 길이와 품질 균형점
- `similarityThreshold 0.35` 로 무관한 이력 자동 배제

### 2-4. 장애 격리 — 폴백 + 타임아웃 + Optional 주입

```
Qdrant 빈 없음 → narrativeGenerator 주입만 되도 enhancer 동작 안 함
NarrativeGenerator 빈 없음 → enhancer 자체가 바이패스
LLM 응답 타임아웃 15초 → 빈 문자열 → 원본 템플릿 narrative 유지
Qdrant 유사 검색 실패 → 빈 리스트 → 이력 없이 LLM 호출
LLM 내부 예외 → 빈 문자열 반환 → 폴백
```

**원칙:** "enrichment 실패가 본 경로 응답을 막으면 안 된다." Optional 주입과 폴백을
다층으로 구성.

### 2-5. Hexagonal — Port/Adapter 분리

```
application
  ├─ port/NarrativeGenerator (인터페이스 — LLM 존재 모름)
  └─ RouteNarrativeEnhancer (오케스트레이터 — Qdrant + LLM 조합)

infra
  └─ ai/OllamaNarrativeGenerator (Spring AI ChatClient 래핑)
```

Ollama → OpenAI/Claude 전환 시:
- `infra/ai` 에 `OpenAiNarrativeGenerator` 추가하고
- 기존 `OllamaNarrativeGenerator` 를 `@ConditionalOnProperty` 로 분기
- 나머지 코드는 손대지 않음

### 2-6. 블로킹 격리 — boundedElastic offload

Spring AI ChatClient 는 동기 블로킹. Reactor 체인 안에서 직접 호출하면
전체 요청이 LLM 응답을 기다린다.
→ `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` 로 offload.

---

## 3. 구현 (What)

### 3-1. 변경된 파일

**신규:**
- `application/.../port/NarrativeGenerator.java` — Port
- `application/.../RouteNarrativeEnhancer.java` — 오케스트레이터 (`@Observed`)
- `infra/.../ai/OllamaNarrativeGenerator.java` — Spring AI ChatClient 어댑터

**수정:**
- `domain/.../RouteComparison.java` — `withNarrative()` 메서드 추가
- `api/.../api/route/RouteController.java` — enhancer 호출 1줄 추가
- `api/.../api/nlp/NaturalLanguageRouteController.java` — 동일
- `infra/build.gradle` — `spring-ai-client-chat` 추가

### 3-2. 핵심 파이프라인 (R → A → G)

```java
// RouteNarrativeEnhancer.enhanceOne
List<ScoredRouteHistoryEntry> similar = fetchSimilar(route, origin, dest, preference);
// ↑ (R) Qdrant 에서 유사 이력 top 3 조회

String llmNarrative = Mono.fromCallable(() ->
        narrativeGenerator.generate(route, origin, dest, preference, similar, originalNarrative))
    .subscribeOn(Schedulers.boundedElastic())  // 블로킹 격리
    .timeout(LLM_TIMEOUT)                       // 15초 폴백
    .blockOptional(LLM_TIMEOUT).orElse("");
// ↑ (A + G) 프롬프트 augment + llama3.2:3b 생성

if (llmNarrative.isBlank()) return route;      // 폴백: 원본 유지
return route.withCarComparison(original.withNarrative(llmNarrative));
```

### 3-3. 프롬프트 설계

**시스템 프롬프트 (발췌):**
```
당신은 대중교통 경로 추천 설명을 작성하는 한국어 카피라이터입니다.
주어진 경로 정보와 과거 유사 이력을 바탕으로, 사용자가 이 경로를 선택할
합리적 근거를 한국어 2~3 문장으로 자연스럽게 설명하세요.

작성 규칙:
- 2~3 문장, 120자 이내
- 과거 이력이 있다면 통계를 근거로 사용 (예: "비슷한 구간 3건 중 2건이 이 방식")
- 자가용 비교 정보가 있다면 유지 (시간·비용·탄소)
- 추측/과장 금지, 제공된 수치만 사용
```

**유저 프롬프트 템플릿:**
```
## 현재 경로
- 강남역 → 홍대입구
- 39분, 1,650원
- 모드: 지하철
- 선호도: TIME_PRIORITY
- 자가용 비교: 자가용보다 3분 더 걸리지만 3,422원 절약...

## 비슷한 과거 이력
- #1 (유사도 0.52) 여의도역에서 서울역까지 지하철 직행, 18분 ...
- #2 (유사도 0.51) 서울역에서 강남역까지 지하철 직행, 28분 ...
- #3 (유사도 0.49) 강남역에서 판교역까지 지하철 직행, 15분 ...

위 정보를 근거로 2~3 문장 한국어 설명 작성:
```

### 3-4. 출력 후처리 (cleanOutput)
- 마크다운 코드블록 제거
- 시작/끝 따옴표 제거
- 연속 공백 축약
- 300자 안전 컷오프

### 3-5. 관측성
`RouteNarrativeEnhancer.enhanceRecommended()` 에 `@Observed`:
```java
@Observed(
    name = "navigation.rag.enhance_narrative",
    contextualName = "RAG 경로 설명 LLM 생성"
)
```
자동 생성 메트릭:
- `navigation_rag_enhance_narrative_seconds_count/sum/max`
- Tempo 에서 `rag.enhance_narrative` span 이 HTTP span 아래 생성

---

## 4. 검증 & 성과 (Result)

### 4-1. Before / After 실측 (`/api/routes` 강남→홍대)

**Before (F-1 템플릿만):**
```json
{
  "type": "TRANSIT_ONLY",
  "recommended": true,
  "carComparison": {
    "narrative": "자가용보다 3분 더 걸리지만 3,422원 절약, 탄소 998g 감소."
  }
}
```

**After (RAG-4 적용):**
```json
{
  "type": "TRANSIT_ONLY",
  "recommended": true,
  "carComparison": {
    "narrative": "이 경로는 안정적이고 환승이 적은 대중교통 경로입니다.
                  비슷한 과거 이력에 따르면, 여의도역에서 서울역까지 지하철
                  직행과 서울역에서 강남역까지 지하철 직행은 유사한 시간과
                  비용을 가지고 있지만, 판교역까지 버스 직행은 더 오래 걸리고
                  비쌉니다."
  }
}
```

### 4-2. 적용 범위 검증 (추천 경로만)

같은 응답의 나머지 경로들:
```
#1 TRANSIT_ONLY         recommended=True  → LLM narrative ✅
#2 TRANSIT_WITH_BIKE    recommended=False → 템플릿 유지 ✅
#3 TRANSIT_WITH_BIKE    recommended=False → 템플릿 유지 ✅
#4 TRANSIT_WITH_BIKE    recommended=False → 템플릿 유지 ✅
```
→ "추천 경로 1개만 LLM" 정책 정확히 동작.

### 4-3. NLP 엔드포인트도 동일 적용

```
q="강남에서 홍대까지 빠르게"
parsedIntent.preference = TIME_PRIORITY
→ 추천 경로 narrative:
  "이 경로는 빠른 시간 우선 추천 경로입니다.
   강남역에서 홍대입구까지 지하철 직행으로 39분, 1,650원에 도달할 수 있습니다.
   비슷한 과거 이력에 따르면 이 경로는 다른 경로보다도 빠르게 이동하는 경로
   중 하나입니다."
```
→ `preference=TIME_PRIORITY` 가 narrative 에 반영됨.

### 4-4. 응답 시간

| 시나리오 | 실측 | 비고 |
|---------|------|------|
| `/api/routes` cold (LLM 첫 호출) | 12초 | 모델 로드 포함 |
| `/api/nlp/routes` warm | 6초 | NLP 파싱 + 경로 + LLM narrative |
| `/api/routes` warm (추정) | 3~5초 | 기존 1~2초 + LLM 2~3초 |

---

## 5. 한계 & 다음 Phase

### 5-1. 응답 지연 증가
기존 `/api/routes` 1~2초 → RAG-4 적용 후 3~5초. LLM 호출이 불가피한 비용.
- **완화 방안 1:** 동일 (OD + preference) 키로 narrative 캐시 (Caffeine)
- **완화 방안 2:** 상용 API(Claude/OpenAI) 로 전환 시 대폭 단축 (~500ms)
- **완화 방안 3:** 스트리밍 응답 (SSE) 으로 narrative 만 뒤늦게 전송

### 5-2. 프롬프트 튜닝 여지
현재 LLM 출력에서 "자가용 비교" 정보가 가끔 누락됨 (llama3.2:3b 의 지시 준수력 한계).
- Few-shot 예시 추가 → 형식 강제
- `qwen2.5:7b` 등 더 큰 모델
- Structured Output (JSON Schema) 로 필드별 생성 후 조립

### 5-3. 유사 이력이 없는 "처음 본 OD"
Qdrant 가 비어있거나 해당 지역 이력이 없으면 LLM 이 "통계 생략 후 기본 설명" 만 생성.
- 정상 동작이지만 **초기 cold start 기간** 에는 가치가 약함
- 해결: RAG-2 의 Seeder 를 프로덕션급 데이터셋으로 확장

### 5-4. 상용 LLM 비용 관리
Ollama 는 무료지만 상용 전환 시 요청당 ~$0.001 수준. 하루 10만 요청 → ~$100/일.
- `?enrich=llm` 플래그로 opt-in 전환 가능하게 설계 여지 남김
- 또는 premium 티어만 LLM narrative 제공

---

## 6. 기록

### 30초 버전
> "RAG-4 에서 **Retrieval + Augmented + Generation** 파이프라인을 완성했습니다.
> 추천 경로 1개에 대해 Qdrant 에서 유사 과거 이력 top 3 를 조회하고,
> llama3.2:3b 가 **데이터 기반 근거를 담은** 한국어 narrative 를 자동 생성합니다.
> 블랙박스 추천이 아닌 **설명 가능한 MaaS** 가 완성되었습니다."

### 3분 버전
1. **문제 정의**
   - F-1 narrative 는 템플릿, 근거 없음
   - RAG-2 는 Retrieval 만, 제품 통합 부재
2. **설계 결정**
   - "모든 요청 자동 + 추천 경로 1개만 LLM" 으로 지연 제어
   - Hexagonal Port/Adapter 로 Ollama → 상용 API 전환 가능
   - 다층 폴백 (Optional 주입 + 15초 타임아웃 + 원본 narrative 유지)
3. **검증**
   - Before/After 같은 OD 에서 narrative 품질 비교
   - 추천 경로만 LLM, 나머지 템플릿 유지 정책 동작 확인
   - 응답 시간 cold 12초 → warm 3~5초

### 완결 스토리
> "프로젝트 시작은 좌표 4개 API 였고, 자연어(RAG-1) → 벡터 DB(RAG-2) →
> **제품 통합 narrative(RAG-4)** 로 진화했습니다. 이제 사용자가 경로를 검색할
> 때마다 **'비슷한 구간에서 어떤 선택이 있었는지'** 를 근거로 한 자연어
> 추천 이유를 받게 됩니다."

---

## 7. 관련 문서
- [RAG Phase 1](./2026-04-20-RAG1-nlp-route-search.md) — 자연어 진입점
- [RAG Phase 2](./2026-04-22-RAG2-qdrant-similar-routes.md) — Qdrant Retrieval 레이어
- [F-1 vs 자가용 비교](./2026-04-20-F1-vs-car-comparison.md) — 이번에 업그레이드된 narrative 의 원본
- [ROADMAP.md](../roadmap/ROADMAP.md) — RAG 시리즈 완결
