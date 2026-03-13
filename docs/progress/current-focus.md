# Current Focus

> 마지막 업데이트: 2026-03-13

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
- 외부 API 호출 절감을 위한 `ODsay`, `따릉이`, `킥보드`, `TMAP`, `모빌리티 가용성 조회` TTL 캐시 도입
- 캐시 TTL을 설정값으로 분리하고 batch 실험에서 cache metric delta 수집 가능
- 목적지 기준 라스트마일 허브 pruning 및 근접 정류소 dedup 적용
- `RecommendationPreference(RELIABILITY / TIME_PRIORITY)` 추가
- API / 프론트 / 실험 스크립트에서 추천 성향 선택 가능

## 다음 우선순위

### 1. Hub 의미 고도화

- 현재 `selected-candidate` metadata까지 응답 노출 완료
- 다음 단계는 허브 좌표/반경과 실제 경로 leg를 더 정교하게 연결

### 2. 평가 지표 실험화

- `RouteEvaluation` 기반 batch 비교 리포트 생성 완료
- `RELIABILITY`와 `TIME_PRIORITY` 비교 실험으로 추천 차이가 나는 케이스 확보
- 현재 `TIME_PRIORITY`에서 mixed 추천이 확인된 대표 케이스:
  - `gupabal_res_to_bukhan_entrance_fast_1`
  - `gupabal_res_to_bukhan_entrance_fast_2`
  - `hapjeong_res_to_worldcup_park_fast_mobility`
  - `hapjeong_res_to_worldcup_park_fast_bike`
- mixed-winning 샘플은 현재 `3~4분` 단축 케이스까지 확보
- stronger mixed-winning 샘플 4건 기준:
  - `TIME_PRIORITY`는 mixed 4/4 추천
  - `RELIABILITY`는 대중교통 4/4 유지

### 3. 호출 최적화 고도화

- 현재는 TTL 캐시로 중복 호출을 줄인 상태
- 다음 단계는 후보 허브 pruning과 부분 경로 재사용으로 총 호출 수 자체를 더 줄이기

### 4. README / 실험 문서 보강

- 비용 정책 가정 문서화
- 허브/평가 도메인 구조 다이어그램 반영
- 실제 응답 예시 JSON 추가
- 실험 결과 요약 섹션 추가

## 주의할 점

- `OPTIMAL` 모드는 현재 따릉이 중심
- `PERSONAL`은 사용자 명시 선택 시에만 탐색하고, 기본 `OPTIMAL` 추천에는 포함하지 않음
- 공유 킥보드 데이터는 메인 축에서 제외된 상태
- 허브 타입은 응답에 노출되고, 선택된 candidate metadata도 포함됨
- 다만 현재 허브 추출은 `actual leg + selected candidate` 병합 수준
- 따릉이 요금은 `1h/2h/3h + 초과 5분당 200원` 정책 기반의 추정치
- 현재 비용 상세는 `대중교통`, `따릉이`만 분리됨
- 현재 서울 샘플 8건에서는 추천이 모두 `TRANSIT_ONLY`
- mixed alternative 분석은 가능하지만 샘플 다양화가 더 필요함
- 다만 2026-03-13 실험 기준 `TIME_PRIORITY`에서는 일부 mixed 추천이 실제로 발생함
- TTL 캐시는 인메모리 기반이라 서버 재시작 시 초기화됨
- 따릉이 API timeout에 대해서는 stale snapshot fallback + refresh backoff를 추가한 상태
- 현재 캐시 TTL은 `ODsay 30초`, `따릉이 snapshot 30초`, `킥보드 snapshot 30초`, `가용성 조회 20초`, `TMAP 5분`
- `Specific` 전략의 라스트마일 후보는 최대 5개로 제한
- `navigation.cache.total{cache=...,result=hit|miss}` metric으로 캐시 효과 측정 가능
- 실험 스크립트가 `cacheMetrics`를 JSON/Markdown 결과에 같이 저장
- 현재 8081에서 떠 있는 서버가 이전 프로세스면 `recommendationPreference` 파라미터가 무시될 수 있어 재시작 확인이 필요함

## 바로 이어서 할 일

다음 작업 시작 시 먼저 확인할 파일:

- `README.md`
- `docs/experiments/README.md`
- `docs/experiments/od-samples.mixed-winning.json`
- `scripts/evaluate_routes.py`
- `output/experiments/latest-route-eval.md`
- `docs/progress/2026-03-13-daily-log.md`
- `docs/progress/2026-03-12-daily-log.md`
- `docs/plans/2026-03-11-hub-reliability-design.md`
