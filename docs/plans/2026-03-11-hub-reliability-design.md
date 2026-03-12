# Hub / Reliability 확장 설계

## 1. 목표

현재 엔진은 `후보 지점 + 이동수단 가용성 + 추천 이유` 수준까지 구현되어 있다.
다음 단계의 목표는 이를 아래 두 축으로 명확히 끌어올리는 것이다.

- `Hub-Based Candidate Generation`
- `Reliability-Aware Recommendation`

즉, 임의의 좌표 기반 후보 탐색을 `허브 중심 탐색`으로 일반화하고,
프론트 표시용 수준의 리스크 판단을 `엔진 핵심 평가 요소`로 승격하는 것이 목적이다.

## 2. 현재 구조 요약

현재 핵심 흐름:

- ODsay로 baseline 대중교통 경로 생성
- `CandidatePointSelector`가 baseline 기반 후보 지점 선택
- `MobilityAvailabilityPort`가 근처 이동수단/정류소 존재 여부 확인
- `MobilitySegmentBuilder`가 접근 도보 + 이동수단 + 이탈 도보를 구성
- `RouteScoreCalculator`가 시간/환승/비용 중심 점수 계산
- `RouteInsightFactory`가 추천 이유/리스크를 생성

현재 한계:

- 후보의 의미가 명시적 허브가 아니라 "좋아 보이는 점"에 가까움
- 신뢰도 지표가 점수보다는 인사이트 생성에 더 많이 쓰임
- 허브/리스크가 도메인 모델로 충분히 끌어올려지지 않음

### 2.1 왜 완전 자유 탐색 대신 baseline 기반 재조합인가

현재 구현은 baseline 대중교통 경로를 먼저 만들고, 그 경로를 따라 의미 있는 허브를 선택한 뒤 멀티모달 조합을 다시 구성하는 방식이다.
즉, "기존 대중교통 경로의 앞뒤 도보만 치환"보다 넓지만, "모든 허브 조합을 완전 자유 탐색"하는 방식은 아니다.

이 선택은 품질 부족 때문이라기보다 운영 제약을 고려한 설계 결정에 가깝다.

- ODsay, TMAP, 공유 이동수단 API는 모두 호출 수 제한과 rate limit이 존재함
- 완전 자유 탐색은 허브 후보 쌍마다 부분 대중교통 경로를 재조회해야 해 호출 수가 빠르게 폭증함
- 공유 이동수단 데이터는 실시간 품질이 완전하지 않아, 탐색 공간을 넓혀도 항상 품질이 좋아지지 않음

따라서 현재 단계에서는 아래 전략이 현실적이다.

- baseline 대중교통 경로를 검색 비용이 낮은 뼈대로 사용
- 그 위에서 퍼스트마일/라스트마일 허브를 제한적으로 선택
- 선택된 허브에 대해서만 이동수단 가용성과 부분 경로를 재조합
- 캐시, pruning, snapshot 재사용으로 외부 API 호출을 제어

이 방식의 장점:

- 제한된 외부 API quota 안에서도 반복 가능한 실험이 가능함
- 추천 품질과 호출 비용 사이의 trade-off를 통제할 수 있음
- 향후 `HubSearchPort`와 허브별 adapter를 붙여도 구조를 유지할 수 있음

즉 현재 엔진은 "완전 자유 탐색의 축소판"이 아니라,
`외부 API 제약 하에서 현실적으로 운영 가능한 baseline-guided hub recomposition`을 목표로 한다.

## 3. Hub Domain 방향

### 3.1 새 도메인 제안

```text
Hub
 - hubId
 - name
 - type
 - location
 - radiusMeters
 - metadata

HubType
 - SUBWAY_STATION
 - BUS_STOP
 - BIKE_STATION
 - CARSHARE_ZONE
 - CHARGING_STATION
 - PARKING
```

### 3.2 역할

- `CandidatePointSelector`의 추상 후보점을 `Hub`로 대체 또는 감싸기
- 실제 환승이 일어나는 지점을 `허브`라는 의미 있는 도메인으로 표현
- 이후 카셰어, EV 충전소, PBV 거점 같은 확장을 자연스럽게 흡수

### 3.3 애플리케이션 계층 제안

```text
HubSearchPort
 - findNearbyHubs(origin, constraints)
 - findHubsAlongTransitRoute(legs, constraints)

HubSelector
 - selectFirstMileHubs(...)
 - selectLastMileHubs(...)
 - selectTransferHubs(...)
```

### 3.4 도입 순서

1. `Location` 기반 후보를 내부적으로 유지
2. `Location`에 대응되는 `Hub`를 함께 생성
3. 전략 클래스는 `Hub`를 우선 사용하되, 실제 경로 조립은 기존 로직 재사용
4. UI에는 후보점 대신 `Hub` 이름/타입 노출

## 4. Reliability Model 방향

### 4.1 평가 요소 분리

현재는 점수와 인사이트가 분리돼 있다.
다음 단계에서는 hard constraint와 soft penalty를 명확히 나누는 것이 좋다.

#### Hard Constraints

- 자전거 대여 정류소 존재
- 자전거 반납 정류소 존재
- 최대 이동수단 거리 초과 여부
- 최대 접근 도보 거리 초과 여부
- 배터리 하한 미달

#### Soft Penalties

- 총 소요시간
- 총 도보 거리
- 환승 수
- 접근 도보 길이
- 공유 이동수단 의존도
- 가용성 부족 리스크
- 반납 여유 부족

### 4.2 새 모델 제안

```text
ReliabilityScore
 - availabilityRisk
 - accessWalkPenalty
 - transferPenalty
 - dropoffConfidence
 - batteryConfidence
 - finalScore
```

또는:

```text
RouteEvaluation
 - timeScore
 - transferScore
 - effortScore
 - reliabilityScore
 - totalScore
 - reasons
 - risks
```

### 4.3 점수 계산 방향

현행:

- 시간
- 환승
- 비용
- effort placeholder

개선:

- 시간
- 환승
- 총 도보
- 접근 도보
- 공유수단 의존
- 대여/반납 안정성

즉 `RouteScoreCalculator`를 단순 weighted sum에서
`RouteEvaluator` 또는 `RouteEvaluationService` 형태로 확장하는 것이 좋다.

## 5. 추천 구조 제안

### 현행

- `RouteScoreCalculator`
- `RouteInsightFactory`

### 목표

```text
RouteEvaluator
 - evaluate(route, baseline, context) -> RouteEvaluation

RouteEvaluation
 - totalScore
 - recommendedReasons
 - riskBadges
 - metrics
```

장점:

- 점수와 설명의 근거를 동일한 모델에서 생성 가능
- 프론트 fallback 로직 제거 가능
- 실험 시 지표 추적이 쉬움

## 6. 코드 구조 제안

### Domain

추가 후보:

- `Hub`
- `HubType`
- `RouteEvaluation`
- `ReliabilityScore`

### Application

- `HubSelector`
- `HubSearchPort`
- `RouteEvaluator`
- `ReliabilityPolicy`

### Infra

- `TransitHubAdapter`
- `BikeStationHubAdapter`
- `CarshareHubAdapter`
- `ChargingStationHubAdapter`

## 7. 구현 단계 제안

### Step 1

- `Hub`, `HubType` 도메인 도입
- 자전거 정류소와 대중교통 후보를 `Hub`로 감싸기
- UI에 `허브 타입` 노출

### Step 2

- `RouteScoreCalculator`를 `RouteEvaluator`로 확장
- 접근 도보/가용성 리스크를 실제 점수에 반영
- `RouteInsightFactory`를 평가 결과 기반으로 단순화

### Step 3

- `CandidatePointSelector` -> `HubSelector` 치환
- last-mile / first-mile 후보를 허브 중심으로 탐색

### Step 4

- 실험 데이터셋 추가
- `최단시간 추천` vs `신뢰도 추천` 비교 리포트 작성

## 8. 자동차 회사 관점 의미

이 구조로 확장하면 프로젝트 설명이 아래처럼 바뀐다.

- 단순 길찾기 앱
- 대중교통 + 자전거 추천 서비스

가 아니라:

- 허브 기반 멀티모달 의사결정 엔진
- 불완전한 실시간 데이터 환경에서 신뢰도 높은 경로를 추천하는 MaaS 백엔드
- 향후 PBV / 카셰어 / EV charging hub로 확장 가능한 구조

이 점이 현대·기아 모빌리티/SDV/PBV 관련 포지션에서 가장 중요한 차별화 포인트가 된다.
