# Black Mamba — AI 기반 MaaS 라우팅 엔진

[![CI](https://github.com/OldTemple91/black-mamba/actions/workflows/ci.yml/badge.svg)](https://github.com/OldTemple91/black-mamba/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5.13-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring AI 1.0](https://img.shields.io/badge/Spring%20AI-1.0.2-0081CB)](https://docs.spring.io/spring-ai/reference/)
[![Resilience4j](https://img.shields.io/badge/Resilience4j-2.2-green)](https://resilience4j.readme.io/)
[![Observability](https://img.shields.io/badge/Observability-Loki%20%7C%20Tempo%20%7C%20Prometheus-F46800)](docs/monitoring/observability-stack.md)

대중교통 + 공공자전거 + 개인 이동수단 멀티모달 경로 엔진에,
**Spring AI 기반 RAG 파이프라인 / Reactor 기반 SSE 실시간 스트림 / Resilience4j 장애 대응 / 4축 관측성(Logs↔Metrics↔Traces↔Alerts+SLO)** 을
통합한 "설명 가능한 MaaS" 포트폴리오.

### 🎬 20초 데모

![Demo](output/playwright/demo.gif)

> 테마 토글 → 출발/목적지 입력 → 이동수단 · 선호도 선택 → 🌧 비 날씨 → 경로 탐색 → 결과 카드(Timeline Bar + Carbon 배지) → 카드 클릭 시 지도 연동 → 다크 모드 전환

## ⚡ 한눈에 보기

| 영역 | 내용 |
|------|------|
| 🧭 **자체 라우팅 알고리즘** | Baseline-Guided Recomposition (5패턴) + 2-Phase Hub Selection + 6-Dim Weighted Scoring ⋯ → [상세](docs/architecture/routing-algorithm.md) |
| 🧠 **AI/RAG (Phase 1~6)** | 자연어 경로 검색 + Qdrant 벡터 DB + bge-m3(1024차원) 하이브리드 검색 + LLM narrative + 할루시네이션 감지 |
| ⚡ **실시간 스트림 (A-1)** | Reactor Flux 기반 SSE — 30초 재탐색, 변화 감지 push, HEARTBEAT, 자동 종료 |
| 🛡️ **장애 대응 (T-3)** | Resilience4j CircuitBreaker + Retry + Fallback — 외부 API 5개 3층 방어선 |
| 🔍 **4축 관측성** | Loki(log) ↔ Tempo(trace) ↔ Prometheus(metric) + **Alertmanager(alert) + SLO/Burn Rate** + Exemplars + OTLP/gRPC |
| 🏗️ **Clean Architecture** | Hexagonal Port/Adapter, 4-module gradle (domain/application/infra/api) |
| 📊 **성능 튜닝** | Geohash 공간 캐싱 — ODsay 히트율 46.9% → 80.4% |
| 🌱 **MaaS 정체성** | 경로별 **탄소 배출량** (이동수단별 정밀 계수) + **날씨 인식** (RAIN/SNOW 공유 모빌리티 페널티) + 접근성 (휠체어/노인) |

👉 **[자체 알고리즘 카탈로그](docs/architecture/routing-algorithm.md)** / **[개선 기록 22건](docs/improvements/README.md)** / **[ADR 7건](docs/adr/)** / **[로드맵](docs/roadmap/ROADMAP.md)**

## 🏛 시스템 아키텍처

```mermaid
flowchart TB
    subgraph Client["👤 Client"]
        UI["React 19 + Vite 7<br/>TailwindCSS v4<br/>Dark Mode · Glassmorphism"]
    end

    subgraph API["🌐 API Layer (Spring MVC)"]
        RC["RouteController<br/>/api/routes"]
        NC["NaturalLanguageController<br/>/api/nlp/routes"]
        SC["RouteStreamController<br/>/api/routes/stream (SSE)"]
        PC["PlaceController<br/>/api/places"]
    end

    subgraph App["⚙️ Application Layer (Orchestration)"]
        ROS["RouteOptimizationService"]
        OS["OptimalSearchStrategy<br/>5패턴 병렬 (A/B/C/D/E)"]
        SS["SpecificMobilityStrategy"]
        HS["HubSelector<br/>2-Phase Primary+Fallback"]
        CPS["CandidatePointSelector<br/>30~80% 윈도우 + 120m 중복제거"]
        RSC["RouteScoreCalculator<br/>6차원 가중합 × 2 프로파일"]
        APP_POST["Post-Processors<br/>Accessibility · Weather · Carbon · RAG-narrative"]
    end

    subgraph Infra["🔌 Infra Layer (Port/Adapter)"]
        ODS["ODsay Adapter<br/>(대중교통)"]
        TMAP["Tmap Adapter<br/>(보행자)"]
        DDR["따릉이 Adapter<br/>(공공자전거)"]
        NAV["Naver Adapter<br/>(지오코딩/POI)"]
        CACHE["Geohash Cache<br/>precision 7 · 150m 격자"]
        CB["Resilience4j<br/>CircuitBreaker + Retry + Fallback"]
    end

    subgraph AI["🧠 RAG Pipeline"]
        OLLAMA["Ollama<br/>llama3.2:3b · bge-m3"]
        QDRANT[("Qdrant Vector DB<br/>1024-dim · OD 이력")]
    end

    subgraph Obs["🔍 4축 Observability"]
        PROM[("Prometheus<br/>metrics + SLO")]
        LOKI[("Loki<br/>logs + traceId")]
        TEMPO[("Tempo<br/>traces OTLP/gRPC")]
        ALERT["Alertmanager<br/>→ Discord"]
        GRAF["Grafana<br/>4 dashboards"]
    end

    UI -->|HTTPS| RC & NC & SC & PC
    RC --> ROS
    NC -->|intent parse| OLLAMA
    NC --> ROS
    SC --> ROS
    PC --> NAV

    ROS --> OS & SS
    OS & SS --> HS & CPS
    HS & CPS --> ODS & TMAP & DDR
    ROS --> APP_POST
    APP_POST -->|RAG narrative| OLLAMA
    APP_POST -->|similar routes| QDRANT
    ODS & TMAP & DDR & NAV -.-> CACHE -.-> CB

    API -.emit.-> PROM & LOKI & TEMPO
    PROM --> ALERT
    PROM & LOKI & TEMPO --> GRAF

    classDef client fill:#eff6ff,stroke:#3b82f6,color:#1e3a8a
    classDef api fill:#f0f9ff,stroke:#0284c7,color:#0c4a6e
    classDef app fill:#fef3c7,stroke:#d97706,color:#78350f
    classDef infra fill:#f1f5f9,stroke:#64748b,color:#334155
    classDef ai fill:#f3e8ff,stroke:#9333ea,color:#581c87
    classDef obs fill:#dcfce7,stroke:#16a34a,color:#14532d
    class UI client
    class RC,NC,SC,PC api
    class ROS,OS,SS,HS,CPS,RSC,APP_POST app
    class ODS,TMAP,DDR,NAV,CACHE,CB infra
    class OLLAMA,QDRANT ai
    class PROM,LOKI,TEMPO,ALERT,GRAF obs
```

**3층 구조:**
- **L1 외부 엔진** (ODsay / Tmap / 따릉이) — 도로 그래프 · 시간표 기반 최단경로 제공
- **L2 Orchestration 층** — L1 결과를 재조합해 5패턴 병렬 생성 + 2-Phase Hub + 6차원 스코어링 + 후처리 (본 프로젝트 자체 구현)
- **L3 AI + Observability** — RAG narrative / Qdrant 유사 검색 / 4축 관측성

### 📦 모듈 의존 (Clean Architecture)

```mermaid
flowchart LR
    api[api<br/>Spring MVC] --> application
    application --> domain[domain<br/>Pure Java 21]
    infra --> application
    infra -.implements.-> application
    api -.-> infra

    classDef core fill:#fef3c7,stroke:#d97706
    classDef adapter fill:#f1f5f9,stroke:#64748b
    class domain,application core
    class api,infra adapter
```

- **domain / application** = 외부 의존성 0 (Port 인터페이스만)
- **infra** 가 Port 를 구현 (의존성 역전)
- **api** 는 application 의 유스케이스만 호출

## 🧭 자체 설계한 Orchestration 알고리즘 8종

> A\*/Dijkstra 같은 **도로 그래프 최단경로** 는 ODsay/Tmap 이 담당.
> 그 위에서 **다중 이동수단을 재조합 · 평가 · 설명하는 Orchestration 층** 을 직접 설계·구현했다.

| # | 알고리즘 | 한 줄 설명 | 코드 |
|---|---------|-----------|-----|
| ① | **Baseline-Guided Multimodal Recomposition** | 순수 대중교통 baseline 을 설계도 삼아 5패턴 (A/B/C/D/E) 병렬 탐색 | `OptimalSearchStrategy` |
| ② | **Two-Phase Hub Selection** | Primary(이상 조건) → Fallback(완화 60%) — 밀집·공백 지역 대응 | `HubSelector` |
| ③ | **30~80% Candidate Window + 120m 중복 제거** | Baseline 의 중간 구간에서 후보 정류장 추출 | `CandidatePointSelector` |
| ④ | **Two-Phase Walking (Haversine → Tmap)** | 후보 필터는 직선거리로 싸게, 확정 후 Tmap 보행 API 로 정밀 | `MobilitySegmentBuilder` |
| ⑤ | **6-Dim Weighted Scoring** | time/transfer/cost/walk/accessWalk/reliability × 2 프로파일 + 7 벌점 | `RouteScoreCalculator` |
| ⑥ | **Geohash Spatial Cache (precision 7)** | 150m 격자로 좌표 양자화 → ODsay 히트율 46.9% → **80.4%** | `GeohashKeyGenerator` |
| ⑦ | **Accessibility Post-Processor** | 휠체어/노인 옵션을 라우팅 로직에 침투시키지 않고 결과 후처리로 해결 | `AccessibilityPostProcessor` |
| ⑧ | **SSE Change Detection** | 30초 폴링 + 2분 임계값 이상 변화만 UPDATE, 나머지는 HEARTBEAT | `RouteStreamService#changeReason` |

**👉 [자체 라우팅 알고리즘 카탈로그](docs/architecture/routing-algorithm.md)** — 의사코드, 임계값 근거, 성과 지표, A\*/Dijkstra를 쓰지 않은 이유 (Design Non-Goals) 포함.

## 📸 실측 증거 (Observability · Vector DB)

Docker Compose 로 전체 스택 기동 후 실측 트래픽 발생 상태에서 자동 캡처.

### Grafana — 4축 관측성 대시보드 (Logs · Metrics · Traces · Alerts/SLO)

**Overview** (요청 수 / p95 / 캐시 Hit률 / JVM)
![Grafana Overview](docs/images/01-grafana-overview.png)

**Route Performance** (응답시간 히트맵, 처리량 by outcome)
![Grafana Route Performance](docs/images/02-grafana-route-performance.png)

**External APIs** (ODsay/Tmap/따릉이 호출 + 캐시 Hit률 + Fallback)
![Grafana External APIs](docs/images/03-grafana-external-apis.png)

### Qdrant — 벡터 DB 실데이터

200+ points 저장, 각 포인트에 1024차원 bge-m3 벡터 + payload (doc_content,
geohash, preference 등) 확인 가능.

![Qdrant Dashboard](docs/images/04-qdrant-dashboard.png)

> 📁 캡처 스크립트: [`scripts/screenshots/capture.mjs`](scripts/screenshots/capture.mjs)
> (Playwright 헤드리스, Docker + Ollama 기동 후 실행)

## 🎯 원래 목적: 신뢰도 중심 MaaS 라우팅

대중교통, 공공자전거, 개인 이동수단을 결합해 도착 성공 가능성이 높은 경로를 추천하는 도시형 멀티모달 라우팅 엔진입니다. 기본 `OPTIMAL` 추천은 MaaS 시나리오에 맞춰 대중교통과 공공/공유 수단 중심으로 구성하고, 개인 이동수단은 사용자가 명시적으로 선택한 경우에만 탐색합니다.

현재 실험 기준으로는 이 축을 두 개로 분리해 해석합니다.

- `OPTIMAL`:
  - 현실적인 MaaS 조합 검증
  - 대중교통 + 공공/공유 모빌리티 중심
- `SPECIFIC + PERSONAL`:
  - 사용자 보유 이동수단을 전제로 한 상한선 실험
  - 동일 비용으로 `12~20분` 단축되는 stronger case까지 확인

## 🖥 Screenshots

### 🌗 Light / Dark — 테마 토글로 즉시 전환

| 라이트 | 다크 |
|:--:|:--:|
| ![Main Light](output/playwright/main-page.png) | ![Main Dark](output/playwright/main-page-dark.png) |
| ![Routes Light](output/playwright/routes-page.png) | ![Routes Dark](output/playwright/routes-page-dark.png) |

> 우상단 🌙 / ☀️ 토글 버튼 · localStorage + `prefers-color-scheme` 우선순위 · 0.3s 부드러운 전환
> Tailwind v4 `@custom-variant dark (&:where(.dark, .dark *))` 전략.

### 📱 Mobile — iPhone 14 viewport

| 라이트 | 다크 |
|:--:|:--:|
| ![Mobile Light](output/playwright/mobile-main-light.png) | ![Mobile Dark](output/playwright/mobile-main-dark.png) |
| ![Mobile Routes Light](output/playwright/mobile-routes-light.png) | ![Mobile Routes Dark](output/playwright/mobile-routes-dark.png) |

> 데스크톱에선 `lg:grid` split layout, 모바일에선 자동으로 vertical stack.
> 검색 패널 → Mobility/Preference → 날씨 → 검색 버튼 → 통계 뱃지 → 지도 순.

### 🎨 Citymapper 스타일 Route Timeline Bar

각 경로의 구간 비율을 **서울 지하철 15개 노선 공식 색상**으로 수평 막대 분할.

```
[🚶 2' | 🚇 2호선 20' ━━━━━━━━━━━━ | 🚲 6' ]
                                     총 28분 · 3개 구간
```

### Route Recommendation — 현실 시나리오

실사용 OD 는 보통 **"역 ↔ 역"** 이 아니라 **집 / 오피스 / 카페 → 공원 / 상권** 같이 지하철역에서
수백 m ~ 1 km 떨어진 위치끼리 연결된다. 이런 조건에서 **퍼스트마일/라스트마일을 자전거로 대체**
하면 대중교통 직행 대비 시간 단축이 발생한다.

**시나리오:** 서초동 아파트 단지 (37.4850, 127.0320) → 성수 카페거리 (37.5420, 127.0554)

| 순위 | 경로 타입 | 소요 | 비고 |
|-----|----------|------|-----|
| #1 | `TRANSIT_WITH_BIKE` | **28분** | ✅ 추천 |
| #2~3 | `TRANSIT_WITH_BIKE` | 33분, 34분 | |
| **#4** | **`TRANSIT_ONLY`** | **36분** | ← 전통적 대중교통 직행 (8분 더 걸림) |
| #5 | `TRANSIT_WITH_BIKE` | 37분 | |

→ **Mixed 경로가 TRANSIT_ONLY 보다 8분 단축.** A-5 실측 벤치마크에서 이런 케이스 30쌍 자동 배치 — 상세: [14. 실측 평가](#14-실측-평가-a-5-real-user-benchmark).

## 1. Project Overview

기존 길찾기 서비스는 주로 최단시간 또는 최단거리 중심으로 경로를 추천합니다.
하지만 실제 도시 이동에서는 다음과 같은 제약이 존재합니다.

- 공유 이동수단은 항상 이용 가능한 것이 아님
- 환승은 아무 지점에서나 자연스럽게 일어나지 않음
- 자전거는 대여 가능 여부뿐 아니라 반납 정류소 존재 여부도 중요함
- 접근 도보가 길면 이론상 최적 경로도 실제로는 불편할 수 있음

이 프로젝트는 이런 현실 제약을 반영해, 단순 ETA가 아니라 실제 이동 성공 가능성이 높은 경로를 추천하는 MaaS 라우팅 엔진을 목표로 합니다.

## 2. Problem Statement

도시 이동은 단순히 "빠른 경로"를 찾는 문제로 끝나지 않습니다.

예를 들어:

- 대중교통만 이용하면 환승은 적지만 도보가 길 수 있음
- 자전거를 결합하면 더 빠를 수 있지만 반납 정류소가 없으면 경로가 성립하지 않음
- 이동수단 접근 도보가 길면 추천 경로라도 실제 선택 확률이 낮아짐
- 실시간 데이터 품질이 불완전하면 이론상 최적 경로가 실제로는 unusable 할 수 있음

따라서 이 프로젝트는 아래 질문을 해결하려고 합니다.

- 어디서 갈아타는 것이 현실적인가?
- 실제로 탈 수 있고 반납할 수 있는가?
- 최단시간보다 더 신뢰도 높은 경로는 무엇인가?

## 3. Core Idea

이 엔진은 두 단계로 경로를 탐색합니다.

### 1) Hub-Based Candidate Generation

대중교통 경로를 baseline으로 만든 뒤, 환승 가능성이 높은 지점과 허브를 후보로 선택합니다.

- baseline 대중교통 경로 생성
- 출발지/목적지 인근 허브 또는 환승 후보 지점 탐색
- 환승 후보 근처 이동수단 가용성 확인
- 자전거는 대여 정류소와 반납 정류소를 모두 검증

### 1.5) Baseline-Guided Multimodal Recomposition

이 프로젝트는 기존 대중교통 경로의 앞뒤 도보만 단순히 자전거로 치환하는 방식에 머무르지 않습니다.
baseline 대중교통 경로를 먼저 만든 뒤, 그 경로를 따라 퍼스트마일/라스트마일 허브 후보를 고르고 새로운 멀티모달 조합을 다시 구성합니다.

예를 들어 baseline이 아래와 같더라도:

- 도보 -> 지하철 A -> 지하철 B -> 도보

엔진은 아래 같은 조합을 만들 수 있습니다.

- 출발지 -> 자전거 -> 다른 허브 -> 버스/지하철 -> 도보

다만 현재는 모든 정류장/허브 조합을 완전 자유 탐색하지는 않습니다.
그 이유는 다음과 같습니다.

- 대중교통 부분 경로를 모든 허브 조합에 대해 다시 조회하면 ODsay/TMAP 호출 수가 급격히 증가함
- 무료 외부 API 플랜에서는 quota와 rate limit이 실제 제약이 됨
- 공유 이동수단 데이터는 품질과 실시간성이 완전하지 않아, 후보를 무한정 넓히는 것이 항상 품질 향상으로 이어지지 않음

그래서 현재 엔진은 `baseline 기반 허브 재조합` 전략을 사용합니다.
즉, baseline 대중교통 경로를 뼈대로 삼고 그 주변의 의미 있는 허브만 선택적으로 재조합해, 호출 수와 품질 사이의 균형을 맞춥니다.

### 2) Reliability-Aware Ranking

생성된 후보 경로를 시간뿐 아니라 다음 요소까지 포함해 평가합니다.

- 총 소요시간
- 총 도보 거리
- 환승 수
- 이동수단 접근 도보 길이
- 공유 이동수단 의존성
- 자전거 반납 정류소 존재 여부
- 가용성 부족 리스크

즉, "가장 빠른 경로"보다 "실제로 성공 가능성이 높은 경로"를 우선 추천하는 방향으로 설계했습니다.

## 4. 경로 타입 · 탐색 흐름

상단 [시스템 아키텍처 Mermaid](#-시스템-아키텍처) 에 레이어와 데이터 플로우가 모두 표현되어 있다. 여기서는 경로 모델만 요약.

**지원하는 경로 타입 (`RouteType`):**

| 타입 | 구성 |
|------|------|
| `TRANSIT_ONLY`              | 순수 대중교통 (ODsay baseline 그대로) |
| `TRANSIT_WITH_BIKE`         | 대중교통 + 따릉이 라스트마일 |
| `TRANSIT_WITH_KICKBOARD`    | 대중교통 + 킥보드 라스트마일 |
| `MOBILITY_FIRST_TRANSIT`    | 이동수단 퍼스트마일 + 대중교통 |
| `MOBILITY_TRANSIT_MOBILITY` | 이동수단 + 대중교통 + 이동수단 (양쪽 모두) |
| `MOBILITY_ONLY`             | 이동수단만 (직선거리 < 최대 범위) |

**탐색 파이프라인 개요:** baseline 생성 → 후보 지점 선택 → pickup/dropoff 검증 → 재조합 → 6차원 스코어링 → 후처리(Accessibility/Weather/Carbon) → 랭킹.

상세 의사코드와 임계값 근거: [자체 알고리즘 카탈로그](docs/architecture/routing-algorithm.md).

## 7. Reliability-Aware Recommendation

이 프로젝트는 단순 최단시간 정렬이 아니라, 실제 사용 가능성까지 반영한 추천을 목표로 합니다.

현재 추천에 반영하는 요소:

- 총 소요시간
- 총 도보 거리
- 환승 횟수
- 접근 도보 길이
- 자전거 반납 정류소 존재 여부
- 공유 이동수단 의존도
- 가용성 부족 리스크

추천 결과에는 아래 정보를 함께 제공합니다.

- 왜 추천되었는지
- 어떤 리스크가 있는지
- 어떤 환승 포인트를 거치는지

## 8. Explainable UI

지도와 경로 카드에서 다음 정보를 시각적으로 제공합니다.

- 버스/지하철/자전거/킥보드/도보 구간 구분
- 마커 근처 탑승/환승 라벨
- hover/click 시 상세 정보 팝업
- 추천 이유 배지
- 리스크 배지
- 핵심 환승 포인트 요약
- 디버그 모드 기반 엔진 판단 정보

## 9. Data Sources

- 대중교통 경로: ODsay
- 도보/마이크로모빌리티 구간 좌표: TMAP
- 공공자전거 정류소 및 대여 가능 정보: 서울시 따릉이 API

## 10. API Call Optimization

무료 외부 API의 호출 제한을 고려해 아래 최적화를 적용했습니다.

- `ODsay`: 동일 출발/도착 쌍 route/time 재사용
- `ODsay`: 출발지/도착지 직선거리 700m 이하 구간은 검색을 차단하고 사용자에게 재검색을 유도
- `따릉이`: 전체 정류소 snapshot 캐시 후 반경 필터링만 재계산
- `킥보드`: 전체 기기 snapshot 캐시 후 반경 필터링만 재계산
- `TMAP`: 동일 보행 경로 캐시
- `TMAP`: 429 quota 초과 발생 시 일정 시간 외부 호출을 차단하고 haversine fallback
- `MobilityAvailability`: pickup/dropoff 조회 캐시
- `MobilityAvailability`: start/end 세그먼트 단위 캐시로 동일 pickup/dropoff 판단 재사용
- `Hub pruning`: 목적지 기준 이동수단 최대 범위를 벗어난 라스트마일 후보를 사전 제거
- `Candidate deduplication`: 서로 매우 가까운 정류소 후보는 하나로 병합
- `Same-station pruning`: 동일 정류소 대여/반납 조합은 경로 생성 전에 조기 제외
- `Fallback hub selection`: 기본 후보 구간이 비면 가장 가까운 feasible 정류소를 보조 허브로 사용

현재 TTL은 설정값으로 관리합니다.

- `navigation.cache.odsay-route-ttl-ms`
- `navigation.cache.ddareungi-snapshot-ttl-ms`
- `navigation.cache.kickboard-snapshot-ttl-ms`
- `navigation.cache.mobility-availability-ttl-ms`
- `navigation.cache.tmap-pedestrian-route-ttl-ms`
- `navigation.cache.tmap-rate-limit-backoff-ms`

캐시 효과는 `navigation.cache.total{cache=...,result=hit|miss}` metric으로 관찰할 수 있습니다.
외부 API 예외 상황은 아래처럼 처리합니다.

- `ODsay`: 짧은 구간(`<=700m`)은 `400 SHORT_DISTANCE` 응답으로 명시적으로 안내
- `TMAP`: 429 발생 시 backoff 기간 동안 API 호출 생략
- 두 경우 모두 엔진은 fallback 경로/시간 계산으로 동작을 유지

## 11. Tech Stack

### Backend Core
- **Java 21** (LTS, records / sealed interface / pattern matching 활용)
- **Spring Boot 3.5.13** + Gradle 8.14
- **Reactor** (Mono/Flux) — WebClient + 비동기 체인
- **Micrometer Observation + `@Observed`** — 트레이싱/메트릭 통합

### AI / RAG (Phase 1~6)
- **Spring AI 1.0.2** (`ChatClient` / `VectorStore` 추상화)
- **Ollama** — `llama3.2:3b` (Chat) + `bge-m3` (Embedding, 1024차원)
- **Qdrant v1.17** — 벡터 DB (gRPC, HNSW 인덱스, Cosine 유사도, 3축 하이브리드 필터)

### 장애 대응
- **Resilience4j 2.2** — CircuitBreaker + Retry + Fallback, Reactor Operator
- 외부 API 5개 (ODsay / Tmap / 따릉이 / Naver Geocoding / Naver Local) 3층 방어선
- TAGO 런타임 킬 스위치 (`tago.enabled` env 토글)

### 관측성 (4축)
- **Prometheus v3.11** + **Grafana v12.4** — Micrometer, Exemplars (메트릭→트레이스 점프)
- **Loki 3.7** — loki4j 2.0, structured metadata (stack_trace 펼치기)
- **Tempo 2.10** — OTLP/gRPC, Micrometer Tracing Bridge (OTel)
- **Alertmanager v0.28** — Discord 웹훅 + SLO Burn Rate (Google SRE 패턴, 1h 14.4× / 6h 6×)
- **cAdvisor v0.55** + **Node Exporter v1.9** — 호스트/컨테이너 메트릭

### MaaS 정체성
- **Carbon Footprint** — 이동수단별 정밀 계수 (지하철 41 / 버스 68 / 공유 킥보드 22 g/km 등)
- **Weather-aware Routing** — RAIN/SNOW 시 공유 모빌리티 페널티 (후처리 재사용 패턴)
- **Accessibility** — 휠체어/노인 옵션 (엘리베이터 없는 역 필터 + 보행속도 재계산)
- **vs 자가용 비교** — time/cost/CO₂ 3축 narrative

### Testing
- **JUnit 5** + Mockito + AssertJ (단위 테스트 88개)
- **WireMock** — ODsay 클라이언트 HTTP 레벨 통합 테스트
- **Playwright** — 프론트 UI 자동 스크린샷 + 데모 비디오 녹화

### Frontend
- **React 19** + **Vite 7** + **TailwindCSS v4** (`@custom-variant dark` 전략)
- **Naver Maps** — 실시간 경로 라인 (passThroughStations 기반 경유 정류장 연결)
- **Dark Mode** — localStorage + `prefers-color-scheme` 우선순위
- **Route Timeline Bar** — 서울 지하철 15개 노선 공식색
- **Skeleton Loading** + **Glassmorphism** + **Hero CSS motion** + `prefers-reduced-motion` 대응

### External APIs
- **ODsay** (대중교통), **TMAP** (보행), 서울시 **따릉이**, **네이버** 지오코딩/로컬검색, **기상청** 예보 (A-4, 선택)

## 12. Run Locally

### 전체 스택 (권장)

```bash
docker compose up -d     # 앱 + Qdrant + Prometheus + Grafana + Loki + Tempo
```

### Backend 단독 (개발 중)

```bash
./gradlew :api:bootRun
```

### AI/RAG 기능 사용 시 사전 준비 (Ollama)

```bash
ollama pull llama3.2:3b     # 자연어 경로 파싱 + narrative (1.9GB)
ollama pull bge-m3          # 경로 임베딩 (1.2GB)
docker compose up -d qdrant # 벡터 DB
```

데모용 시드 200건 투입:
```bash
curl -X POST http://localhost:8081/api/rag/admin/seed
```

### 주요 엔드포인트

| 엔드포인트 | 설명 |
|-----------|------|
| `GET /api/routes?originLat=...` | 1회성 경로 탐색 |
| `GET /api/routes/stream?...` | **SSE 실시간 재탐색** (30초 주기) |
| `GET /api/nlp/routes?q=강남에서 홍대까지` | **자연어 경로 검색** (RAG-1) |
| `GET /api/rag/similar-routes?q=...` | **의미 유사 과거 경로** (RAG-2) |
| `GET /actuator/circuitbreakers` | **외부 API 회로 상태** (T-3) |
| `GET /actuator/prometheus` | 메트릭 스크레이프 |

### Frontend

```bash
cd frontend
npm install
npm run dev
```

기본 포트:

- backend: `8081`
- frontend: `5173`

### Load Testing (k6)

5종 부하 테스트 시나리오를 제공합니다. k6가 로컬에 없으면 Docker(`grafana/k6`) 자동 사용.

```bash
./scripts/k6/run.sh smoke    # 1 VU, 30초 — 기본 정상성
./scripts/k6/run.sh load     # 50 VU, 7분 — 평시 SLO 검증 (p95 < 2s)
./scripts/k6/run.sh stress   # 50→200 VU, 10분 — 한계 탐색
./scripts/k6/run.sh spike    # 50→300→50 VU — 급증 대응
./scripts/k6/run.sh cache    # Cold vs Warm 응답시간 비교
```

상세 기준 및 SLO: [`docs/performance/perf-baseline.md`](docs/performance/perf-baseline.md)

### Observability Stack (Prometheus + Grafana LGTM)

Metrics, Logs, Traces를 Grafana 한 곳에서 통합 조회할 수 있습니다.

```bash
docker compose up -d

# 접속
open http://localhost:3000     # Grafana (admin/admin) — 대시보드 자동 프로비저닝
open http://localhost:9090     # Prometheus
open http://localhost:3100     # Loki
open http://localhost:3200     # Tempo
```

| Phase | 스택 | 역할 |
|-------|-----|------|
| 1. Metrics | Prometheus + Micrometer | JVM/HTTP/비즈니스 메트릭 (`navigation.route.*`, `navigation.cache.*`) |
| 2. Logs | Loki + loki4j | 애플리케이션 로그 Push, `traceId` 자동 삽입 |
| 3. Traces | Tempo + Micrometer Tracing | 분산 추적 (Zipkin 포맷), 로그↔트레이스 연결 |

상세 설계: [`docs/monitoring/observability-stack.md`](docs/monitoring/observability-stack.md)
관련 기술 블로그: [모니터링 시스템 개발 (Prometheus & Grafana LGTM)](https://www.notion.so/Prometheus-Grafana-LGTM-15b8983855a3807c840addfdbe093342)

## 13. 구현 상태 & 개선 이력

핵심 기능은 **[개선 기록 21건](docs/improvements/README.md)** 에 누적 기록되어 있다. 상위 카테고리:

- **🧭 Orchestration 알고리즘** — Baseline-Guided Recomposition, 2-Phase Hub, 6-Dim Scoring 등 8종 ([카탈로그](docs/architecture/routing-algorithm.md))
- **🧠 AI/RAG Phase 1~6** — Ollama + Qdrant + 하이브리드 검색 + LLM narrative + 할루시네이션 감지
- **⚡ 실시간** — SSE 30초 재탐색 (A-1)
- **🌱 MaaS 정체성** — Carbon Footprint (C-2) · Weather-aware (A-4) · Accessibility (C-3) · vs 자가용 비교 (F-1)
- **🛡 장애 대응** — Resilience4j 3층 방어선 (T-3) · TAGO 런타임 킬 스위치 (T-7)
- **🔍 관측성** — Prometheus/Loki/Tempo + Alertmanager + SLO Burn Rate (M-1/2/3)
- **🎨 UI/UX** — Split layout + Glassmorphism + Dark Mode + Route Timeline Bar + Skeleton Loading
- **📊 성능 튜닝** — Geohash 공간 캐싱, ODsay 히트율 46.9% → 80.4% (B-3)

## 14. 실측 평가 (A-5 Real User Benchmark)

역 좌표 편향을 제거한 **실사용자 OD 30쌍** 자동 배치 실험 결과 ([상세](docs/improvements/2026-04-23-A5-real-user-benchmark.md)):

| 지표 | 값 |
|------|-----|
| **Mixed 경로 채택률** | **43%** (TRANSIT_ONLY 대비) |
| **평균 시간 단축** | 3.4분 |
| **최대 시간 단축** | 8분 (서초 아파트 → 성수 카페거리) |
| **아파트 출발 케이스** | **70%** 가 Mixed 경로 승리 |

**대표 시나리오** (readme 상단 스크린샷 참조):
- 서초동 아파트 (37.4850, 127.0320) → 성수 카페거리 (37.5420, 127.0554)
- `TRANSIT_WITH_BIKE` 28분 vs `TRANSIT_ONLY` 36분 → **8분 단축**

추천 기준별 정책 차이:
- `RELIABILITY` (기본) — 대중교통 안정성 우선
- `TIME_PRIORITY` — mixed 경로 적극 추천 (평균 3.9분 단축)

종합 수치와 평가 방법론: [`docs/performance/real-user-benchmark.md`](docs/performance/real-user-benchmark.md)

## 15. Limitations

현재 한계 (향후 개선 대상):

- **완전 자유 탐색이 아닌 baseline 기반 재조합** — baseline 바깥의 유망한 허브 조합을 놓칠 수 있음. 외부 API quota/rate limit 때문에 전수 탐색 대신 캐시/백오프/pruning 으로 품질과 호출량 균형.
- **허브 모델이 정류소/후보점 수준** — `Hub` 도메인으로 일반화 예정 (SUBWAY_STATION, BUS_STOP, BIKE_STATION, CARSHARE_ZONE, CHARGING_STATION).
- **점수 모델이 운영 리스크를 전부 반영하진 않음** — 현재 7가지 벌점. 사용자 클릭 로그 기반 가중치 학습은 Phase 2.
- **700m 이내 단거리 구간은 도보 전용 탐색 미제공** — `400 SHORT_DISTANCE` 로 명시적 안내.
- **공유 킥보드 실시간 데이터 미제공** — TAGO 서울 데이터 제공 없음. `TAGO_ENABLED=false` 로 외부 호출 스킵, 제공 시작 시 env 한 줄로 복구 가능 ([T-7](docs/improvements/2026-04-23-T7-tago-kill-switch.md)).
- **TMAP 429 상황에선 도보 지표가 근사치** — 백오프 동안 haversine fallback 으로 서비스 유지.
- **RAG narrative 는 로컬 LLM (llama3.2:3b) 기준** — 모델 교체는 env 변수 한 줄, 상용 LLM (GPT-4/Claude) 연동은 Spring AI `ChatClient` 추상화로 설정 레벨에서 전환 가능.

## 16. Roadmap

다음 단계는 아래 방향으로 확장할 계획입니다.

### 1) Hub Domain Generalization

- `BikeStation` 수준을 넘어 `Hub` 도메인으로 일반화
- `SUBWAY_STATION`, `BUS_STOP`, `BIKE_STATION`, `CARSHARE_ZONE`, `CHARGING_STATION` 등으로 확장
- 상세 설계 문서: [`docs/plans/2026-03-11-hub-reliability-design.md`](docs/plans/2026-03-11-hub-reliability-design.md)

### 2) Reliability Score Enhancement

- 접근 도보 penalty 고도화
- 공유 이동수단 availability risk 반영
- 경로 실패 가능성 모델 강화

### 3) Automotive-Oriented Extension

- 카셰어 존을 이동수단(Leg)이 아닌 환승 거점 가중치(MobilityHub)로 활용
- 편도 카셰어(카셰어 사업자 등) 연동 시 Car → Transit → PM 완전 통합 경로 지원
- EV charging-aware routing
- PBV / mobility hub 시나리오 확장

