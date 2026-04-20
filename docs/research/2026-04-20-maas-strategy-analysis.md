# Black Mamba — MaaS 강화 전략 분석

> 작성일: 2026-04-20
> 목적: 외부 MaaS 사례 + 자체 프로젝트 분석 → **다음 개선 우선순위** 도출
> 대상: EV/자동차 제조사 모빌리티 백엔드 포트폴리오

---

## 0. 핵심 결론 (TL;DR)

**현재 Black Mamba는 "라우팅 엔진 8/10 + MaaS 플랫폼 4/10"** 상태다.

- 라우팅 알고리즘은 세계 수준 (허브 기반, 신뢰도 스코어링, 멀티모달 병렬)
- 그러나 **MaaS 표준 레벨 1** 에도 못 미치는 영역이 있음 (결제/예약 통합, 실시간 운영)
- "완벽한 MaaS 플랫폼" 이 아니라 **"라우팅 엔진의 MaaS 정체성 강화"** 가 현실적 목표
- **"자동차를 덜 쓰게 만드는 도시 이동 서비스"** 로 정체성을 재정의

---

## 1. MaaS 국제 표준: Sochor 5단계 통합 레벨

MaaS Alliance + 학계가 공통으로 쓰는 프레임워크.

| Level | 이름 | 특징 | Black Mamba 현재 |
|-------|------|------|-----------------|
| **0** | No integration | 각 서비스가 개별 앱 | 해당 안 됨 |
| **1** | Information integration | **여러 수단을 한 화면에서 검색** (지도/길찾기) | ✅ **완성** — 대중교통 + 따릉이 + 개인 PM 통합 검색 |
| **2** | Booking + Payment | 한 앱에서 **예약/결제** | ❌ **미구현** — 검색만 가능, 예약/결제 없음 |
| **3** | Service bundling | **구독/패키지** (월 정액 이동권) | ❌ **미구현** |
| **4** | Societal goals | 도시 전체 정책 반영 (탄소, 혼잡 관리) | ⏳ **부분** — 탄소 배출량 비교 설계됨 |

**현실적 목표: Level 1 완성도 극대화 + Level 2의 일부 기능 mock/데모**
- Level 2 (결제/예약)은 파트너십 계약 필요 → 포트폴리오 범위 초과
- 대신 **"Level 2를 위한 아키텍처 준비"** 를 증명

---

## 2. 외부 MaaS 사례 분석

### 2-1. Kakao T (한국 최대 MaaS, 3천만 사용자)

| 기능 | Kakao T | Black Mamba |
|------|---------|-------------|
| **멀티모달 통합 검색** | ✅ | ✅ |
| **택시 호출** | ✅ (Kakao T 핵심) | ❌ |
| **대리운전 예약** | ✅ | ❌ |
| **주차장 검색/결제** | ✅ | ❌ |
| **기차표 예약** | ✅ | ❌ |
| **따릉이/킥보드 통합** | ✅ | ✅ |
| **실시간 교통 정보** | ✅ | ❌ (static) |
| **사용자 계정/히스토리** | ✅ | ❌ |

**인사이트:**
- Kakao T는 **결제/예약 레벨(Level 2)** 확보가 차별화 포인트
- 우리는 **라우팅 품질/신뢰도 스코어링**이 차별화 (Kakao T는 단순 시간 기반)
- 경쟁하지 말고 **"라우팅 엔진"** 틈새에서 강화

---

### 2-2. Whim (헬싱키, 과거 세계 최초 MaaS, 2024 파산)

**교훈이 중요한 사례:**
- Level 3 (구독 패키지) 에 올인 → 수익 모델 실패
- 시사점: **Level 3은 사업자 영역**, 포트폴리오 수준 구현 무리
- **"MaaS Global is dead, long live MaaS"** — 연합 아닌 중앙 통합 모델의 한계

**우리 프로젝트 적용:**
- 구독 기능 설계 금지
- 대신 **"사업자 중립 라우팅 엔진"** 포지션 (어떤 플랫폼에도 붙을 수 있는 엔진)

---

### 2-3. Jelbi (베를린, 현재 성공 사례)

**핵심 기능:**
- End-to-end 여행 계획
- **가격/시간/환경 영향 비교** ← 핵심!
- 멀티모달 티켓 한 번에
- 실시간 가용성 데이터

**Jelbi가 Kakao T보다 우리에게 참고 가치 있는 이유:**
- 공공 교통 중심 (택시/대리운전 안 다룸)
- **탄소·비용·시간 3축 비교** 가 차별화 포인트
- 우리 프로젝트의 "신뢰도 스코어링"과 유사한 다차원 비교 철학

---

### 2-4. Moovit → Glimble (네덜란드 전국 MaaS)

**참고 가치:**
- GTFS/GTFS-RT 표준 활용 (**실시간 교통 정보**)
- Moovit은 **자전거 경로 MaaS 추가가 2018년 최초**
- 멀티 벤더 파트너십 (Bird, Lime, Tier, Voi 등)

**우리 프로젝트 적용:**
- **GTFS-RT 연동**: 서울 GTFS-RT가 없지만 "연동 가능 구조"만 만들어도 가치
- 벤더 추가 용이성 → 이미 `MobilityType` enum으로 해결

---

### 2-5. EV/자동차 제조사 PBV (목적 기반 모빌리티, 2027~)

**전략:**
- **SDx (Software Defined Everything)** 플랫폼
- PV5(2026) → PV7(2027) → PV9(2029) 순차 출시
- **Motional 로보택시** 3세대 시스템 (Level 4 자율주행)
- 2030년 25만대 PBV 판매 목표

**Black Mamba와 접점:**
- EV/자동차 제조사가 지향하는 건 **"차량 + 경로 + 운송 정보의 통합 관리"**
- 우리 프로젝트의 **허브 모델 + 신뢰도 스코어링**이 바로 이 방향
- PBV는 B2B (물류/택시 회사) 중심 → 우리 B2C 완전 커버와 다름

**포지셔닝 한 줄:**
> "자동차 제조사 통합 플랫폼가 PBV + 경로 + 운송정보 통합 관리라면,
>  제 Black Mamba는 그 중 **경로 + 실시간 운영 데이터** 레이어의 레퍼런스 구현입니다."

---

## 3. 자체 프로젝트 현황 (종합 점수 6.5/10)

### 강점 (8/10 이상)
| 영역 | 점수 | 증거 |
|------|------|------|
| 트립 플래닝 | 8/10 | 패턴 B/C/D/E 병렬 탐색, 6차원 스코어링 |
| 관측성 | 8/10 | Prometheus + Grafana + Loki + Tempo 완비 |
| 안정성 | 8/10 | Geohash 캐시, fallback, 22 tests, CI/CD |
| 라우팅 품질 | 8/10 | 신뢰도 기반 추천, 허브 가중치 |

### 약점 (5/10 이하)
| 영역 | 점수 | Gap |
|------|------|------|
| 멀티 지역 | 2/10 | 서울 전용 |
| 생태계 통합 | 5/10 | 결제/예약 없음 |
| 실시간 운영 | 6/10 | 대중교통 지연 반영 없음 |
| 사용자 관리 | 0/10 | 로그인/히스토리 없음 |

---

## 4. MaaS 정체성 강화를 위한 설계 방향

### 🎯 정체성 재정의

**Before (모호):**
> "대중교통 + 개인 이동수단 최적 경로 탐색 엔진"

**After (명확):**
> **"자가용 없이도 편한 도시 이동 — 신뢰도·탄소·비용까지 보여주는 MaaS 라우팅 엔진"**

---

### 🎯 설계 방향 5가지 (우선순위 순)

---

#### 🥇 방향 1: "vs 자가용" 비교 응답 (신규, 2일)

**핵심:** 단순 경로 안내 → **"자가용 대비 이 경로가 왜 나은가"** 설득

```json
{
  "route": { ... },
  "comparison": {
    "vsCar": {
      "timeDiff": "+5분",         // 자가용 대비 5분 더
      "costSaved": 6500,          // 주차+연료 절감
      "co2Reduced": 2.3,          // kg
      "caloriesBurned": 180       // 따릉이 구간
    },
    "vsBaselineTransit": {         // 기존 기능 유지
      "savedMinutes": 3,
      "savedCost": 250
    }
  },
  "narrative": "자가용 대비 5분 더 걸리지만 6,500원 절약, 탄소 2.3kg 감소"
}
```

**구현 요소:**
- `CarReferenceCalculator`: 자가용 기준 시간/비용/탄소 계산
  - 자가용 시간: 네이버 Directions 또는 `distance / 30km/h`
  - 비용: 연료(거리/연비×휘발유) + 주차(시간당 3,000원) + 톨게이트
  - 탄소: km당 171g CO₂ (평균 승용차)
- RouteEvaluation에 `carReference` 필드 추가
- 프론트엔드: RouteCard에 "vs 자가용" 배지

**왜 이게 1순위?**
- 프로젝트 **정체성을 한 방에** 바꿈 ("자가용 대체" 메시지)
- 탄소 비교는 **EV/자동차 제조사 ESG 전략**과 직결
- 공수 대비 임팩트 최대 (2일)

**발표 스토리:**
> "자동차 회사의 미래는 **차를 덜 파는 대신 이동 서비스를 파는** 방향입니다.
> 그러려면 '자가용 없이도 이 경로면 된다'는 **설득력**이 필요합니다.
> 제 엔진은 시간/비용/탄소 3축 비교를 **실시간 생성**해서 
> 사용자가 자가용 대체 의사결정을 할 수 있게 돕습니다."

---

#### 🥈 방향 2: 실시간 반응 (B-1 + A-1 통합, 3~4일)

**핵심:** 1회성 검색 → **이동 중 실시간 재탐색**

**시나리오:**
```
출발 → 따릉이 이용 중
  ↓
[Event: 따릉이 정류소 재고 급변]
  ↓
영향 경로 자동 감지 → SSE 푸시
  ↓
"다음 정류소 만석, 1정거장 더 가서 반납하세요"
```

**구현 요소:**
- `MobilityAvailabilityChangedEvent` (Spring ApplicationEvent)
- `InflightRouteRegistry`: 진행 중 경로 추적
- `RouteReevaluator`: 영향 받은 경로 재탐색
- SSE 엔드포인트: `/api/routes/stream` (`Flux<ServerSentEvent<Route>>`)

**실제 구현 난이도:**
- 이벤트 생성은 30초 단위 polling으로 단순화 가능
- SSE는 WebFlux 없이도 MVC에서 지원 (Spring 6)

**왜 2순위?**
- **"Reactive 생태계 활용"** 이 기술 어필 포인트
- MaaS의 "Live Journey" 경험 완성
- 우리가 이미 가진 Reactor + Observability 기반에 자연스럽게 얹힘

---

#### 🥉 방향 3: Accessibility (2일)

**핵심:** 운전 못 하는 사람 커버

**구현 요소:**
```java
// 새 파라미터
@RequestParam(required = false) Boolean wheelchairAccessible,
@RequestParam(required = false) Integer walkingSpeedKmh,  // 노인 3km/h

// 데이터 소스
- 서울 열린데이터: 엘리베이터 있는 지하철역 목록
- CandidatePointSelector: 엘리베이터 없는 역 제외
- RouteScoreCalculator: 경사/계단 가중치
```

**발표 가치:**
- **포용성(Inclusion)** = MaaS 핵심 가치
- 자동차 회사의 "운전 못 하는 사람까지 이동 자유 보장" 메시지
- UAM, 자율주행 시대의 **B2C 이동 철학**

---

#### 🏅 방향 4: GTFS-RT 실시간 교통 (1~2일, 선택)

**현황:** 한국은 GTFS-RT 공개 피드 아직 제한적.
- 서울시 대중교통 API는 XML/JSON, GTFS-RT 아님
- 하지만 **GTFS-RT 포맷으로 변환하는 어댑터**만 만들어도 표준 준수 증명

**구현 요소:**
- `GtfsRealtimeAdapter`: 서울 대중교통 API → GTFS-RT Protobuf
- Journey planner에 실시간 지연 반영
- OpenTripPlanner 호환 가능 (표준 준수)

**가치:**
- **"전 세계 어떤 도시든 붙일 수 있는 엔진"** 증명
- 실시간 지연 = 진짜 MaaS 레벨

---

#### 방향 5: 시간대·날씨 인식 (A-3 + A-4, 1일)

**이미 ROADMAP 있음.** 위 1~4보다 작은 효과.

---

## 5. 명시적으로 **안 할** 것들

### ❌ EV 충전소 연동 (C-1)
- 이유: **자가용 전제** → MaaS 정체성과 충돌
- 이미 ADR-003에서 "허브로만 모델링" 결정

### ❌ 결제/예약 시스템
- 이유: 파트너십 계약 필요, 포트폴리오 범위 초과
- 대신: **"결제 레이어 확장 지점"** 만 설계로 표시

### ❌ 사용자 계정/히스토리 전면 구현
- 이유: 간단하지만 임팩트 대비 공수 큼
- 대안: **Redis로 익명 디바이스 ID 기반 선호도 저장** (B-2 간소화 버전)

### ❌ 택시/대리운전 호출
- 이유: Kakao T 영역. 경쟁 무의미

---

## 6. 최종 추천 로드맵 (2주)

### Week 1 — 정체성 강화
```
Day 1 (반나절):
  M-4 로그 레벨 정리 (10분)
  M-1 Alertmanager + Discord (2시간)
  T-4 Phase 2 Java 25 (반나절)

Day 2~3 (1.5일):
  ★ 방향 1: vs 자가용 비교 응답 (핵심 포지셔닝 전환)

Day 4~5 (2일):
  방향 3: Accessibility (포용성)
```

### Week 2 — 실시간 MaaS
```
Day 1~4 (3~4일):
  ★ 방향 2: 실시간 재탐색 (B-1 + A-1)

Day 5 (반나절):
  A-4 날씨 인식
```

### 결과 포지셔닝
```
Before: "서울 한정 경로 탐색 앱"
After:  "자가용 없이도 편한 도시 이동 — 실시간 반응형 MaaS 라우팅 엔진"
```

---

## 7. 발표 스토리 (최종)

### 1분 엘리베이터 피치
> "EV/자동차 제조사가 모빌리티 회사로 전환하려는 건 
> **차를 덜 팔고 이동 서비스를 파는** 방향이라고 이해했습니다. 
> 그래서 제 프로젝트는 **자가용 대체 경로 엔진** 으로 설계했습니다.
>
> 단순 길찾기가 아니라:
> 1) **자가용 vs 이 경로**를 시간/비용/탄소 3축으로 비교해 설득 근거 제공
> 2) **실시간 반응형**: 따릉이 재고가 급변하면 SSE로 즉시 대체 경로 안내
> 3) **포용성**: 엘리베이터 있는 역만, 노인 보행 속도 옵션
>
> **관측성**: Prometheus+Loki+Tempo로 3축 상관 (Exemplars), 
> **확장성**: Geohash 캐싱으로 외부 API 호출 53% 감소, 
> **표준 준수**: OTLP/gRPC, OpenTelemetry 기반.
>
> 자동차 제조사 통합 플랫폼 플랫폼의 **경로+운영 데이터 레이어** 레퍼런스 구현이라고 생각합니다."

### 발표관 예상 질문 대비
- **"EV 충전소는 왜 안 넣었나?"** → ADR-003 근거로 "자가용 전제라 정체성 충돌"
- **"Kakao T와 뭐가 다른가?"** → "택시/예약은 사업자 영역, 저는 라우팅 엔진 품질에 집중"
- **"결제 연동은?"** → "파트너십 필요한 영역이라 확장 지점만 설계"
- **"Whim 실패 사례 알고 있나?"** → "네, Level 3(구독)는 사업자 영역 → 의도적 제외"

---

## 8. 당장 할 일

ROADMAP 업데이트 제안:
- ⬇️ **C-1 EV 충전소**: 우선순위 **하향** (선택적 확장으로)
- ⬆️ **신규 항목 F-1: vs 자가용 비교**: **최우선**
- ⬆️ **C-3 Accessibility**: 우선순위 **상향**
- ⬇️ **D-1 멀티 지역**: **하향** (당장 임팩트 낮음)
- 유지: M-1 Alerting, A-1 SSE 실시간, B-1 Event-Driven, T-4 Phase 2

**이 문서를 기반으로 ROADMAP 재정비 후 첫 항목(F-1 vs 자가용 비교) 시작 제안드립니다.**

---

## Sources (외부 리서치)
- [Kakao T 서비스 소개](https://www.kakaocorp.com/page/service/service/KakaoT?lang=en)
- [Kakao T Wikipedia](https://en.wikipedia.org/wiki/Kakao_T)
- [Mobility Platforms in Korea Shaping the future of MaaS](https://www.seoulz.com/mobility-platforms-in-korea-shaping-the-future-of-maas/)
- [MaaS in the Netherlands | Intertraffic](https://www.intertraffic.com/news/maas/maas-in-the-netherlands)
- ["MaaS Global is dead; long live MaaS"](https://zagdaily.com/trends/maas-global-is-dead-long-live-maas/)
- [MaaS Alliance 5 Levels](https://maas-alliance.eu/wp-content/uploads/2018/11/MaaS-brochure-ENG.pdf)
- [Levels of MaaS integration taxonomy](https://www.researchgate.net/figure/Levels-of-MaaS-integration-taxonomy_fig2_348870148)
- (자동차 제조사 PBV 전략)
- (자동차 제조사 모빌리티 전략)
- [Moovit MaaS solutions](https://moovit.com/maas-solutions/)
- [GTFS Realtime Overview](https://developers.google.com/transit/gtfs-realtime)
