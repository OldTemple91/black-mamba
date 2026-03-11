# Black Mamba — 다음 단계 계획

> 작성일: 2026-03-10
> 목적: 현대/기아 모빌리티 서비스 백엔드 포트폴리오 완성

---

## 브레인스토밍 결론 (Claude + Codex 합의)

- **포지셔닝 변경**: "네비게이션 앱" → **"MaaS(Mobility as a Service) 라우팅 엔진"**
- **킥보드 전략**: KICKBOARD_SHARED(공유, TAGO API 사망) → **PERSONAL_KICKBOARD(개인, API 불필요)**
- **카셰어 처리**: MobilityType(=이동 수단 Leg)이 아닌 **MobilityHub(=환승 거점 가중치)**로 설계
- **면접 포인트**: 확장 가능한 도메인 구조 + 허브 기반 탐색 + 신뢰도 기반 스코어링

---

## Phase 1 — PERSONAL_KICKBOARD 도입 (우선순위: HIGH)

> 목표: KICKBOARD_SHARED 제거 → 개인 킥보드로 교체. API 불필요, 현재 위치 출발.

### 1-1. 도메인 모델 변경

**파일:** `domain/src/main/java/com/blackmamba/navigation/domain/route/MobilityType.java`

```java
// 변경 전
public enum MobilityType { PERSONAL, DDAREUNGI, KICKBOARD_SHARED }

// 변경 후
public enum MobilityType {
    DDAREUNGI,          // 공공 따릉이 (실 API)
    PERSONAL_BIKE,      // 개인 자전거 (API 불필요, 현 위치 출발)
    PERSONAL_KICKBOARD  // 개인 킥보드 (API 불필요, 현 위치 출발)
}
```

**이유:**
- `PERSONAL_KICKBOARD`는 픽업 불필요 → 출발지에서 바로 탑승 → 라스트마일 최적화 효과 극대화
- API 의존 없음 → 항상 가용 → 경로 탐색 신뢰성 100%
- 면접 설명: "실제 소유 킥보드를 가진 사용자의 출퇴근 멀티모달 경로 최적화"

### 1-2. MobilityAvailabilityAdapter 수정

**파일:** `infra/.../adapter/MobilityAvailabilityAdapter.java`

```java
// KICKBOARD_SHARED 케이스 제거 (코드는 주석으로 보존)
// PERSONAL_KICKBOARD 추가 → personalKickboard(lat, lng) 반환 (항상 가용)
// PERSONAL_BIKE 추가 → personalBike(lat, lng) 반환 (항상 가용)
```

### 1-3. OptimalSearchStrategy 수정

**파일:** `application/.../strategy/OptimalSearchStrategy.java`

```java
// 변경 전
private static final List<MobilityType> ALL_TYPES =
    List.of(MobilityType.DDAREUNGI);  // KICKBOARD_SHARED 이미 제거됨

// 변경 후
private static final List<MobilityType> ALL_TYPES =
    List.of(MobilityType.DDAREUNGI, MobilityType.PERSONAL_KICKBOARD);
```

### 1-4. MobilityConfig 수정

**파일:** `application/.../route/MobilityConfig.java`

```java
// PERSONAL_KICKBOARD용 config 추가
// 속도: 15 km/h, 최대 범위: 5,000m (개인 킥보드 기준)
public static MobilityConfig personalKickboard() { ... }
public static MobilityConfig personalBike() { ... }
```

### 1-5. 프론트엔드 수정

**파일:** `frontend/src/components/search/MobilitySelector.jsx`

```js
// KICKBOARD_SHARED 제거, PERSONAL_KICKBOARD / PERSONAL_BIKE 추가
const MOBILITY_OPTIONS = [
  { id: 'DDAREUNGI',         label: '🚲 따릉이' },
  { id: 'PERSONAL_KICKBOARD', label: '🛴 개인킥보드' },
  { id: 'PERSONAL_BIKE',     label: '🚴 개인자전거' },
]
```

**RouteCard 라벨 업데이트:**
```js
ROUTE_TYPE_LABEL에 PERSONAL_KICKBOARD / PERSONAL_BIKE 케이스 추가
```

---

## Phase 2 — MobilityHub 모델 도입 (우선순위: MEDIUM)

> 목표: 카셰어 존 위치를 이동수단이 아닌 "환승 거점(허브)"으로 모델링.
> TAGO 카셰어 API는 존 위치만 제공 → 허브 데이터로 활용.

### 2-1. 도메인 모델 추가

**신규 파일:** `domain/.../route/MobilityHub.java`

```java
public record MobilityHub(
    HubType type,        // CAR_SHARE_ZONE, BIKE_STATION, TRANSIT_HUB
    String name,
    double lat,
    double lng
) {}

public enum HubType { CAR_SHARE_ZONE, BIKE_STATION, TRANSIT_HUB }
```

### 2-2. TAGO 카셰어 API 클라이언트 추가

**신규 파일:** `infra/.../carshare/CarShareZoneClient.java`

```java
// TAGO getCarStation API 호출
// 응답: 카셰어 존 위치 목록 (쏘카/그린카/피플카 정류소)
// 캐싱: Caffeine 1시간 (위치 정보는 자주 바뀌지 않음)
```

### 2-3. CandidatePointSelector 허브 인식 개선

**파일:** `application/.../route/CandidatePointSelector.java`

```java
// 허브 근접 여부를 환승 후보 가중치에 반영
// 카셰어 존 근처 정류장 → 후보 우선순위 ↑
// "모빌리티 인프라가 풍부한 환승점"을 선호하는 경로 생성
```

### 2-4. 면접 설명 포인트

```
현재 구현: 따릉이 실데이터 + 개인 킥보드 기반 멀티모달 경로 추천

확장 설계: 카셰어 존/모빌리티 허브를 환승 거점으로 고려하는 허브 기반 탐색
→ "어떤 환승역이 더 좋은가"를 이동수단 인프라 밀도로 판단

한계 인식: 공개 TAGO API는 존 위치만 제공, 실시간 재고 연동은
사업자 파트너십 API(쏘카/기아 모빌리티) 필요
```

---

## Phase 3 — 신뢰도 기반 스코어링 (우선순위: MEDIUM)

> 목표: RouteScoreCalculator에 신뢰도(Reliability) 차원 추가.

### 현재 스코어 공식
```
score = 시간(0.5) + 환승수(0.2) + 비용(0.2) + 피로도(0.1)
```

### 개선 스코어 공식
```
score = 시간(0.4) + 환승수(0.15) + 비용(0.15) + 신뢰도(0.2) + 피로도(0.1)
```

### 신뢰도 점수 산정 기준

| 조건 | 신뢰도 점수 |
|------|------------|
| 따릉이 대여가능 ≥ 3대 | 1.0 |
| 따릉이 대여가능 1~2대 | 0.7 |
| 따릉이 대여가능 0대 | 0.0 (경로 제외) |
| 개인 킥보드/자전거 | 1.0 (항상 가용) |
| 공유 킥보드(추정) | 0.3 |

**파일:** `application/.../route/RouteScoreCalculator.java`

```java
// MobilityInfo.availableCount 기반 신뢰도 점수 산정
// reliabilityScore = calcReliability(route)
```

---

## Phase 4 — 포트폴리오 포지셔닝 정리 (우선순위: HIGH)

> 목표: CLAUDE.md + README + 프로젝트 소개 문구를 MaaS 라우팅 엔진으로 업데이트.

### 4-1. CLAUDE.md 업데이트

```markdown
# 목적
서울 한정 MaaS(Mobility as a Service) 라우팅 엔진.
대중교통 + 따릉이 + 개인 이동수단의 최적 멀티모달 조합을 탐색.
현대/기아 모빌리티 서비스 백엔드 지원용 포트폴리오 프로젝트.
```

### 4-2. 프로젝트 소개 문구 (README / 이력서용)

```
Black Mamba — MaaS 라우팅 엔진

대중교통과 다양한 이동수단(따릉이, 개인 PM)을 통합해
최적 멀티모달 경로를 탐색하는 백엔드 서비스.

핵심 기술:
- 패턴 B/C/D/E 병렬 경로 탐색 (퍼스트마일/라스트마일/혼합)
- 신뢰도 기반 다차원 스코어링 (시간/환승/신뢰도/비용)
- ODsay(대중교통) + Tmap(이동경로) + 서울공공데이터(따릉이) 멀티API 통합
- Spring WebFlux 기반 논블로킹 병렬 API 호출
- 확장 가능한 도메인 구조: 새 이동수단 타입 추가 시 기존 전략 수정 불필요

확장 설계:
- MobilityHub 모델: 카셰어 존을 환승 거점 가중치로 활용
- 편도 카셰어 연동 시 Car → Transit → PM 완전 통합 경로 지원
```

### 4-3. 면접 Q&A 준비 포인트

| 예상 질문 | 핵심 답변 |
|-----------|-----------|
| "TAGO API가 안 되면 어떻게 했나요?" | 원인 진단 → 엔드포인트 오류 확인 → 전략 전환 (개인 이동수단 모델로 피벗) |
| "카셰어는 왜 안 넣었나요?" | 공개 API 한계(존 위치만) → 허브 모델로 설계, 실서비스는 파트너십 API 필요 |
| "확장성은?" | MobilityType 추가 시 Strategy 변경 없음, MobilityHub 모델로 어떤 거점도 흡수 가능 |
| "신뢰도 없는 경로 추천 어떻게 처리?" | 따릉이 가용수 0이면 해당 경로 제외, 개인 이동수단은 신뢰도 1.0 고정 |

---

## 작업 순서 요약

```
[즉시] Phase 1-1 ~ 1-5: PERSONAL_KICKBOARD 도입 (1~2일)
[다음] Phase 3: 신뢰도 스코어링 추가 (반나절)
[다음] Phase 4: 포지셔닝 문구 정리 (반나절)
[선택] Phase 2: MobilityHub 모델 (1~2일, 여유 있을 때)
```

---

## 참고: 브레인스토밍 출처

- Claude (이번 세션): MaaS 포지셔닝, 카셰어 퍼스트마일 개념, Approach 1.5 제안
- Codex: TAGO API 한계 지적, 허브 모델 제안, 신뢰도 기반 스코어링 제안
- 합의: "PM/따릉이 기반 MaaS 엔진 + 카셰어 허브 확장 설계" 방향 채택
