# Current Focus

> 마지막 업데이트: 2026-03-12

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
- 대중교통 요금 / 따릉이 추정 요금 계산 반영
- `costBreakdown` 및 leg pricing policy 분리
- `RouteEvaluation` / `RouteHub` 응답 노출

## 다음 우선순위

### 1. Hub 의미 고도화

- 현재 `selected-candidate` metadata까지 응답 노출 완료
- 다음 단계는 허브 좌표/반경과 실제 경로 leg를 더 정교하게 연결

### 2. 평가 지표 실험화

- `RouteEvaluation` 기반 batch 비교 리포트 생성
- baseline 대비 시간/도보/비용/신뢰도 비교 자동화

### 3. README / 실험 문서 보강

- 비용 정책 가정 문서화
- 허브/평가 도메인 구조 다이어그램 반영
- 실제 응답 예시 JSON 추가

## 주의할 점

- `OPTIMAL` 모드는 현재 따릉이 중심
- 공유 킥보드 데이터는 메인 축에서 제외된 상태
- 허브 타입은 응답에 노출되고, 선택된 candidate metadata도 포함됨
- 다만 현재 허브 추출은 `actual leg + selected candidate` 병합 수준
- 따릉이 요금은 `1h/2h/3h + 초과 5분당 200원` 정책 기반의 추정치
- 현재 비용 상세는 `대중교통`, `따릉이`만 분리됨

## 바로 이어서 할 일

다음 작업 시작 시 먼저 확인할 파일:

- `README.md`
- `docs/progress/2026-03-12-daily-log.md`
- `docs/plans/2026-03-11-hub-reliability-design.md`
