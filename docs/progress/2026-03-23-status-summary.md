# 2026-03-23 Development Summary

## 한 줄 요약

이 프로젝트는 현재 `Hub-Based Candidate Generation + Reliability-Aware Recommendation + Explainable MaaS Routing Engine` 구조까지 올라왔고, 최근에는 `허브 선택 품질`, `mixed 생성 실패 원인`, `외부 API 호출 최적화`를 중심으로 고도화됐다.

## 현재 프로젝트 포지셔닝

### 1. 기본 축: 현실적인 MaaS 추천
- 기본 `OPTIMAL` 모드는 `대중교통 + 공공/공유 모빌리티` 중심으로 추천한다.
- `PERSONAL`은 모든 사용자에게 일반화할 수 없는 옵션이므로, 기본 최적탐색에서는 제외했다.
- 즉 현재 `OPTIMAL`은 현실적인 MaaS 시나리오를 검증하는 모드다.

### 2. 별도 축: 개인 이동수단 상한선 실험
- `SPECIFIC + PERSONAL`은 사용자가 개인 이동수단을 실제로 보유하고 있다는 가정 하에 돌리는 실험 축이다.
- 이 축에서는 동일 비용으로 `10~20분` 단축되는 stronger case를 확보했다.
- 따라서 현재 프로젝트는
  - 현실적인 MaaS 추천
  - 사용자 보유 이동수단 기반 상한선 실험
  두 축으로 분리해 해석하고 있다.

## 지금까지 구현된 핵심 기능

### 경로 엔진
- 대중교통 baseline 경로 생성
- 퍼스트마일 / 라스트마일 / mixed 조합 생성
- 접근/이탈 도보 반영
- 연속 도보 병합
- 자전거 대여/반납 정류소 검증
- 경로 타입 지원
  - `TRANSIT_ONLY`
  - `TRANSIT_WITH_BIKE`
  - `TRANSIT_WITH_KICKBOARD`
  - `MOBILITY_FIRST_TRANSIT`
  - `MOBILITY_TRANSIT_MOBILITY`
  - `MOBILITY_ONLY`

### 추천/평가 엔진
- `RELIABILITY` / `TIME_PRIORITY` 추천 성향 분리
- `RouteEvaluator` 기반 점수/추천/이유/리스크 통합
- `RouteEvaluation` 응답 노출
- `RouteInsights` 응답 노출
- 추천 이유 / 리스크 배지 / 비교 정보 포함
- 비용 계산 포함
  - 대중교통 요금
  - 따릉이 추정 요금
  - `costBreakdown`

### 허브 구조
- `Hub`, `HubType`, `HubSelector` 도입
- `HubSearchPort` 도입으로 검색/선택 책임 분리 시작
- selected candidate 허브와 actual 허브를 함께 노출
- 허브 metadata 노출
  - `selectionPhase`
  - `selectionStrategy`
  - `selectionRank`
  - `candidateCount`
  - `distanceToAnchorMeters`
  - `pickupHintDistanceMeters`
  - `pickupHintStationName`
  - `pickupHintAvailableCount`

### 설명 가능한 결과
- 추천 이유 배지
- 리스크 배지
- generation diagnostics
- fallback diagnostics
- 허브 정보
- 디버그 모드
- 추천 성향 비교 UI

## 최근 허브/추천 로직 튜닝

### 1. same-station 조기 제외
- 동일 정류소 대여/반납 조합을 route build 전에 더 일찍 제외
- `mobility_segment` 캐시 추가
- `SAME_PICKUP_DROPOFF`가 주요 병목에서 사라짐

### 2. 허브 fallback 도입
- 기본 후보가 비면 nearest feasible stop을 `FALLBACK_NEAREST`로 사용
- 허브가 완전히 사라지는 케이스를 일부 완화

### 3. 최근접 대여소 힌트 기반 허브 정렬/필터
- 라스트마일 허브는 최근접 대여소 힌트가 좋은 후보를 우선
- 힌트가 충분히 좋은 허브가 하나라도 있으면, 나쁜 허브는 후보군에서 제외
- 이 과정에서 `NO_PICKUP`가 `6 -> 4`로 줄었고, `no-mixed` 세트 중 일부는 mixed 대안을 갖게 됐다.

### 4. relaxed fallback
- 전역 반경 확대 대신, fallback 단계에서만 최소 유효 거리 기준을 완화
- 목적은 `NO_CANDIDATE_HUB`를 줄이되 winner 세트는 깨지 않는 것
- 재검증 결과:
  - `samplesWithMixedAlternative: 1 -> 2`
  - `mixed-winning` 세트는 유지

### 5. 허브 우회 리스크 반영
- anchor에서 과도하게 먼 허브를 `weakHubDetour`로 분리
- `RouteEvaluation`에 아래 필드 추가
  - `weakHubDetour`
  - `maxHubAnchorDistanceMeters`
- 리스크 배지에 `허브 우회 큼` 추가
- 실제 런타임에서 `maxHubAnchorDistanceMeters = 2802`인 후보에 `허브 우회 큼`이 노출되는 것까지 확인했다.

### 6. 퍼스트마일 pickup preflight
- 퍼스트마일 패턴(B/D)에서는 출발지 주변 pickup 가능 여부를 먼저 본다.
- 출발지 근처에 탈 수단이 없으면 허브 조합 시도 자체를 생략한다.
- 목적:
  - 불필요한 외부 API 호출 감소
  - 퍼스트마일 `NO_PICKUP` 상황을 더 정직하게 처리

## 실험 자산

현재 실험 세트는 4종으로 정리돼 있다.

### 1. `mixed-winning`
- `TIME_PRIORITY`에서 실제로 mixed 추천이 나오는 샘플
- 현재 `7건`
- 최근 기준:
  - `TIME_PRIORITY`: mixed `7/7`
  - `RELIABILITY`: transit `7/7`
  - 평균 `3.857분` 단축
  - 평균 비용 `-57원`

### 2. `mixed-opportunity`
- mixed 대안은 있지만 추천까지는 안 가는 샘플
- 왜 mixed가 밀리는지 분석하는 용도

### 3. `no-mixed`
- mixed 자체가 생성되지 않거나, 생성돼도 추천 구조상 의미가 약한 샘플군
- 최근 기준:
  - `recommendedGenerationReasonCounts`: `NO_PICKUP: 4`
  - `samplesWithMixedAlternative: 2`
- 현재는 허브 부재보다 `pickup 접근성`이 더 큰 병목으로 좁혀지고 있다.

### 4. `personal-winning`
- `SPECIFIC + PERSONAL` 전용 실험 세트
- stronger case 확보:
  - `20분` 단축, 비용 동일
  - `12분` 단축, 비용 동일
  - `14분` 단축, 비용 `+50원`
  - `10분` 단축, 비용 동일

## 외부 API 최적화

### 현재 적용된 최적화
- `ODsay` route/time 캐시
- `TMAP` pedestrian route 캐시
- `따릉이` snapshot 캐시
- `킥보드` snapshot 캐시
- `mobility_availability` 캐시
- `mobility_segment` 캐시
- TTL 설정값 분리
- `navigation.cache.total{cache=...,result=hit|miss}` metric 수집
- TMAP `429` backoff
- 따릉이 timeout 시 `stale snapshot fallback`
- `ODsay` 700m 이하 검색 차단 및 사용자 안내

### 현재 판단
- 전역 반경 확대는 winner 품질을 해칠 가능성이 크다.
- 현재 최적화 방향은
  - 캐시 재사용
  - 허브 pruning
  - segment cache
  - preflight check
  중심으로 유지하는 것이 맞다.

## 현재 코드 기준 강점

- 단순 길찾기 앱이 아니라 `추천 정책`과 `실험 결과`를 함께 설명할 수 있다.
- `RELIABILITY`와 `TIME_PRIORITY`가 실제로 다른 결과를 만든다.
- `왜 mixed가 안 되는지`도 generation diagnostics로 설명 가능하다.
- 외부 API quota / timeout 상황을 fallback과 cache로 흡수하는 구조를 갖췄다.
- 허브 기반 구조라 향후 `CARSHARE_ZONE`, `CHARGING_STATION`, `PARKING` 같은 확장 서사를 자연스럽게 붙일 수 있다.

## 아직 남은 과제

### 1. Hub 도메인 고도화
- 아직 `HubSearchPort`는 baseline transit 기반 검색 구현 1종만 있다.
- 향후에는
  - `BikeHubSearch`
  - `CarshareHubSearch`
  - `ChargingHubSearch`
  같은 식으로 분리할 수 있다.

### 2. `NO_PICKUP` 병목 완화
- 현재 주 병목은 허브 부재보다 pickup 접근성 쪽이다.
- 다음 단계는 허브 후보 생성 단계에서 pickup 접근성을 더 직접 반영하는 것.

### 3. mixed-winning 유형 다양화
- 현재 대표 winner는 `북한산 / 월드컵공원` 축이 강하다.
- `캠퍼스 / 박물관 / 업무지구 내부` 같은 유형까지 winner를 더 넓히는 건 아직 과제다.

### 4. 문서/마감 정리
- README는 충분히 좋아졌지만, 최종 프로젝트용으론
  - 실험 표
  - 대표 케이스 스크린샷
  - 허브 아키텍처 도식
  을 더 강하게 배치할 수 있다.

## 지금 바로 이어서 보기 좋은 문서

- `<project-root>/README.md`
- `<project-root>/docs/experiments/README.md`
- `<project-root>/docs/experiments/2026-03-16-mixed-winning-playbook.md`
- `<project-root>/docs/progress/current-focus.md`
- `<project-root>/docs/progress/2026-03-20-daily-log.md`
