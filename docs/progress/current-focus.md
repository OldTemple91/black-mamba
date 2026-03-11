# Current Focus

> 마지막 업데이트: 2026-03-11

## 현재 목표

프로젝트를 단순 멀티모달 경로 앱이 아니라 아래 구조로 끌어올리는 것.

- `Hub-Based Candidate Generation`
- `Reliability-Aware Recommendation`
- `Explainable MaaS Routing Engine`

## 현재 상태

완료된 핵심 단계:

- 대중교통 baseline 경로 생성
- 자전거 대여/반납 정류소 검증
- 접근/이탈 도보 구간 반영
- 연속 도보 병합
- 추천 이유/리스크 UI 추가
- `Hub`, `HubType`, `HubSelector` 도입
- `RouteReliabilityMetrics` 도입
- `RouteEvaluator`로 점수/비교/인사이트 통합

## 다음 우선순위

### 1. Hub 노출 확장

- API 응답 또는 디버그 정보에 허브 타입 노출
- 프론트 경로 카드에서 `BUS_STOP`, `SUBWAY_STATION`, `BIKE_STATION`을 직접 보여주기

### 2. RouteEvaluation 도메인화

- 점수 세부 항목을 별도 객체로 저장
- 실험 리포트, README, UI 디버그에 동일 데이터 재사용

### 3. 실험 지표 수집

- baseline 대비 시간 절감
- 도보 거리 변화
- 접근 도보 평균
- 반납 정류소 미존재 제외 비율
- 공유수단 의존 비율

## 주의할 점

- `OPTIMAL` 모드는 현재 따릉이 중심
- 공유 킥보드 데이터는 메인 축에서 제외된 상태
- 허브 구조는 도입됐지만 아직 후보점을 감싸는 수준

## 바로 이어서 할 일

다음 작업 시작 시 먼저 확인할 파일:

- `README.md`
- `docs/plans/2026-03-11-hub-reliability-design.md`
- `docs/progress/2026-03-11-daily-log.md`
