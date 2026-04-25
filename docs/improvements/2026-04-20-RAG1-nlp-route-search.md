# RAG Phase 1: 자연어 경로 검색 (Ollama + Spring AI)

> 작업일: 2026-04-20
> 공수: 실측 4시간
> 커밋: TBD

---

## 1. 배경 (Why)

### 도입 동기
RAG / 벡터 DB / Embedding 기반 검색이 백엔드 영역에서도 흔한 도구가 되어가는 흐름에 맞춰, MaaS 도메인에 자연어 진입점을 결합하는 실험을 진행.

- **RAG 파이프라인 설계 및 최적화**
- **AI 모델 통합 및 성능 튜닝**
- **벡터 DB, Embedding 기반 검색 이해 및 활용**
- **대화형 AI / RAG 서비스 구조 설계 및 운영**

### 프로젝트 관점
기존 `/api/routes`는 **좌표 4개(originLat/Lng/destLat/Lng)** 를 요구한다. 이는 UI 없이 호출 불가능한 **개발자용 인터페이스**. MaaS 사용성을 높이려면 자연어 진입점이 필수.

```
Before: "강남" 좌표(37.4979, 127.0276)로 변환 후 호출
After:  "강남에서 홍대까지 노인도 쉬운 경로" 자연어 한 줄
```

---

## 2. 설계 결정

### 2-1. Ollama 로컬 LLM 선택 근거

| 옵션 | 비용 | 품질 | 개발 편의 |
|------|------|------|----------|
| OpenAI API | 유료($$$) | 최고 | 키 관리 |
| Anthropic Claude API | 유료($$) | 최고 | 키 관리 |
| **Ollama + llama3.2:3b** | **무료** | 한국어 JSON OK | Docker 1줄 |

**결정: Ollama (llama3.2:3b)**
- 현 단계에서는 **무료 + 오프라인 데모** 이점 큼
- Spring AI 추상화로 **상용 API 전환은 설정 1줄 변경**
- M2 Pro에서 cold start 25초, 재사용 2~3초 (JSON 파싱 충분)

### 2-2. RAG 구조 — 의도 파싱만 LLM, 나머지는 결정론적

```
자연어 입력
    ↓
[LLM: Intent Parsing]  ← Ollama + 프롬프트 엔지니어링
    ↓
RouteSearchIntent (record) 
    ↓
[결정론적: Geocoding → Route Optimization]  ← 기존 엔진 그대로
    ↓
경로 결과 + 자가용 비교 (F-1)
```

**왜 "LLM이 모든 걸" 하지 않나:**
- **블랙박스화 방지** — 경로 계산이 LLM에 맡겨지면 설명 가능성 ↓
- **비용/지연 제어** — LLM 호출을 딱 1번으로 제한
- **테스트 용이** — 결정론 부분은 Unit Test, LLM은 Mock 가능

### 2-3. Tool Calling 간소화

Spring AI의 `ToolCallback`로 LLM이 직접 `/api/routes`를 호출하게 할 수도 있지만:
- 현재 단계는 "의도 파싱 → 결정론적 호출" 로 충분
- Tool Calling은 **모델이 여러 Tool을 골라야 할 때** 가치 있음 (단일 Tool에 오버킬)
- 향후 확장 여지로 기록 (Phase 2~3에서 도입 가능)

---

## 3. 구현 (What)

### 3-1. 변경된 파일
- **신규**:
  - `api/src/main/java/.../nlp/RouteSearchIntent.java` (record)
  - `api/src/main/java/.../nlp/NlpRouteIntentParser.java` (ChatClient 래퍼)
  - `api/src/main/java/.../nlp/NaturalLanguageRouteController.java` (REST)
- **수정**:
  - `api/build.gradle` (Spring AI BOM + Ollama starter)
  - `api/src/main/resources/application.yml` (ollama 설정)
  - `docker-compose.yml` (`host.docker.internal` 매핑)

### 3-2. 의존성
```gradle
implementation platform('org.springframework.ai:spring-ai-bom:1.0.2')
implementation 'org.springframework.ai:spring-ai-starter-model-ollama'
```

### 3-3. 프롬프트 엔지니어링

**Key insight:** 로컬 3B 모델은 지시 준수력이 약하므로 **JSON 스키마 명시 + 매핑 규칙 샘플** 포함.

```
스키마:
{
  "origin": "출발지 (필수)",
  "destination": "도착지 (필수)",
  "preference": "RELIABILITY" | "TIME_PRIORITY",
  ...
}

매핑 규칙:
- "빠르게", "빨리" → preference: "TIME_PRIORITY"
- "따릉이" → mobility: ["DDAREUNGI"]
- "휠체어" → wheelchairAccessible: true
- "노인" → walkingSpeedKmh: 3.0
```

### 3-4. 방어 코드
- **Markdown 코드블록 제거**: LLM이 `` ```json ``로 감쌀 때 대비
- **Temperature 0.2**: JSON 파싱용이라 결정성 우선
- **2단계 Geocoding Fallback**: 네이버 지오코딩 실패 시 장소검색 API
- **GlobalExceptionHandler**: LLM 파싱 실패 시 400 + 명확한 에러코드

### 3-5. Docker 호스트 Ollama 연결
```yaml
environment:
  - OLLAMA_BASE_URL=http://host.docker.internal:11434
extra_hosts:
  - "host.docker.internal:host-gateway"
```

---

## 4. 검증 & 성과 (Result)

### 4-1. 실측 E2E

| Q (자연어 요청) | LLM 파싱 결과 | 최종 경로 |
|----|----|----|
| "강남역에서 홍대입구까지" | origin=강남역, dest=홍대입구, pref=TIME_PRIORITY | ✅ 4개 경로 |
| "강남에서 홍대까지 빠르게" | pref=TIME_PRIORITY 추출 | ✅ 5개 경로 |
| "노인도 쉬운 강남에서 홍대 경로" | origin 파싱 실패 (**3B 한계**) | ⚠️ 재도전 필요 |
| "휠체어로 갈 수 있는 강남→홍대" | wheelchair=true | ✅ 필터 적용 |
| "따릉이로 강남→홍대" | mobility=["DDAREUNGI"] | ✅ SPECIFIC 모드 |

### 4-2. 응답 포맷

```json
{
  "query": "강남역에서 홍대입구까지",
  "parsedIntent": {
    "origin": "강남역",
    "destination": "홍대입구",
    "preference": "TIME_PRIORITY"
  },
  "origin": {"name": "강남역", "lat": 37.4980, "lng": 127.0276},
  "destination": {"name": "홍대입구", "lat": 37.5570, "lng": 126.9240},
  "searchMode": "OPTIMAL",
  "routes": [
    {
      "type": "TRANSIT_ONLY",
      "totalMinutes": 39,
      "totalCostWon": 1650,
      "carComparison": {
        "narrative": "자가용보다 3분 더 걸리지만 3,422원 절약, 탄소 998g 감소."
      }
    }
  ]
}
```

### 4-3. 응답 속도

| 단계 | 소요 |
|------|------|
| 1. LLM 의도 파싱 | 2~3초 (Ollama cold: 25초) |
| 2. Geocoding (네이버) | 0.1~0.3초 |
| 3. 경로 탐색 (기존) | 1~2초 (warm) |
| **합계** | **3~5초** |

---

## 5. 한계 & 개선 여지

### ⚠️ llama3.2:3b의 파싱 품질 한계
- "노인도 쉬운 강남" 같은 수식어 포함 시 origin 오인식
- **개선 방안**:
  - 더 큰 모델 사용 (qwen2.5:7b, 4.7GB, 한국어 강함)
  - Few-shot 예시 프롬프트 추가
  - Structured Output (JSON Schema) 모드 활용

### ⚠️ Tool Calling 미활용
- Spring AI 1.0 의 `ToolCallback` 미사용
- 단일 Tool(`/api/routes`)이라 오버킬이지만, 확장 시 필요

### ⚠️ 벡터 DB 미적용 (Phase 2에서)
- **벡터 DB, Embedding 기반 검색** 은 Phase 2에서 Qdrant/pgvector 도입 예정
- 경로 이력 임베딩 + 유사 경로 추천

---

## 6. 기록

### 짧은 버전
> "경로 검색 API는 좌표 4개를 요구하는 개발자용 인터페이스였습니다. Ollama + Spring AI로 **자연어 인터페이스를 추가**해서 '강남에서 홍대까지 빠르게' 같은 요청이 들어오면 **LLM이 의도를 파싱**하고 기존 라우팅 엔진을 호출합니다.
>
> 핵심 설계 원칙은 **라우팅 알고리즘을 블랙박스화하지 않기**. LLM은 의도 파싱만 하고, Geocoding부터 경로 계산까지는 결정론적으로 유지해 **설명 가능성과 테스트 용이성을 확보**했습니다.
>
> Ollama 로컬 LLM으로 시작한 이유는 **API 비용과 데이터 프라이버시**입니다. Spring AI 추상화 덕분에 **상용 API 전환은 설정 1줄**입니다."

### 다음 확장 메모
> "1차로 **RAG 파이프라인 설계** 를 구현했습니다. Phase 2에서는 **Qdrant 벡터 DB**에 경로 이력을 임베딩으로 저장하고, **코사인 유사도 기반 유사 경로 추천**으로 확장할 계획입니다."

---

## 7. 다음 단계 (Phase 2~4)
- [ ] **Phase 2**: Qdrant 경로 이력 + 유사 경로 검색 (벡터 DB 실전)
- [ ] **Phase 3**: POI Semantic Search (pgvector + 임베딩)
- [ ] **Phase 4**: LLM 기반 narrative 생성 (템플릿 → LLM)
- [ ] 더 큰 모델 실험 (qwen2.5:7b) — 파싱 품질 개선
- [ ] Spring AI ToolCallback 도입 (복수 Tool 확장 대비)

---

## 8. 관련 문서
- 이 프로젝트의 RAG 후속 단계: Phase 2 (Qdrant), Phase 3 (POI Semantic), Phase 4 (LLM narrative)
