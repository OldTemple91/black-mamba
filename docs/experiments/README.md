# Experiments

## 목적

배치 O/D 샘플에 대해 `OPTIMAL` 탐색 결과를 수집하고,

- baseline `TRANSIT_ONLY`
- 실제 추천 경로
- best mixed alternative

를 비교하기 위한 실험 자산 모음이다.

## 입력 샘플

- [od-samples.seoul.json](<project-root>/docs/experiments/od-samples.seoul.json)
- [od-samples.mixed-opportunity.json](<project-root>/docs/experiments/od-samples.mixed-opportunity.json)
- [od-samples.mixed-winning.json](<project-root>/docs/experiments/od-samples.mixed-winning.json)
- [od-samples.no-mixed.json](<project-root>/docs/experiments/od-samples.no-mixed.json)
- [od-samples.personal-winning.json](<project-root>/docs/experiments/od-samples.personal-winning.json)
- mixed-winning 해석/확장 전략:
  - [2026-03-16-mixed-winning-playbook.md](<project-root>/docs/experiments/2026-03-16-mixed-winning-playbook.md)

## 실행 방법

백엔드가 `http://localhost:8081` 에서 실행 중이어야 한다.

```bash
<project-root>/scripts/evaluate_routes.py
```

옵션 예시:

```bash
<project-root>/scripts/evaluate_routes.py \
  --base-url http://localhost:8081 \
  --search-mode OPTIMAL \
  --recommendation-preference RELIABILITY \
  --input <project-root>/docs/experiments/od-samples.seoul.json
```

mixed 경로가 유리할 가능성이 있는 목적지 세트:

```bash
<project-root>/scripts/evaluate_routes.py \
  --base-url http://localhost:8081 \
  --search-mode OPTIMAL \
  --recommendation-preference TIME_PRIORITY \
  --input <project-root>/docs/experiments/od-samples.mixed-opportunity.json
```

현재 `TIME_PRIORITY`에서 mixed 추천이 실제로 확인된 대표 케이스만 보고 싶다면:

```bash
<project-root>/scripts/evaluate_routes.py \
  --base-url http://localhost:8081 \
  --search-mode OPTIMAL \
  --recommendation-preference TIME_PRIORITY \
  --input <project-root>/docs/experiments/od-samples.mixed-winning.json
```

mixed 자체가 생성되지 않는 케이스와 `reasonCode`를 보고 싶다면:

```bash
<project-root>/scripts/evaluate_routes.py \
  --base-url http://localhost:8081 \
  --search-mode OPTIMAL \
  --recommendation-preference RELIABILITY \
  --input <project-root>/docs/experiments/od-samples.no-mixed.json
```

개인 이동수단(`SPECIFIC + PERSONAL`)의 상한선 실험을 보고 싶다면:

```bash
<project-root>/scripts/evaluate_routes.py \
  --base-url http://localhost:8081 \
  --search-mode SPECIFIC \
  --recommendation-preference TIME_PRIORITY \
  --mobility PERSONAL \
  --input <project-root>/docs/experiments/od-samples.personal-winning.json
```

## 출력

- timestamped JSON: `output/experiments/route-eval-YYYYMMDD-HHMMSS-ffffff-<sample>-<preference>.json`
- timestamped Markdown: `output/experiments/route-eval-YYYYMMDD-HHMMSS-ffffff-<sample>-<preference>.md`
- latest JSON: `output/experiments/latest-route-eval.json`
- latest Markdown: `output/experiments/latest-route-eval.md`

## 현재 지표

- 추천 경로 타입 분포
- baseline 대비 시간/도보/비용/점수 차이
- 추천 경로 접근 도보
- best mixed alternative 기준 시간/도보/비용/점수 차이
- `navigation.cache.total` 기준 cache hit/miss delta
- `generationDiagnostics.reasonCode` 집계
  - 예: `NO_PICKUP`, `NO_DROPOFF`, `SAME_PICKUP_DROPOFF`
- `generationDiagnostics.phase` 집계
  - 예: `FIRST_MILE`, `LAST_MILE`, `DIRECT`

## 캐시 메트릭

실험 스크립트는 실행 전후의 actuator metric을 읽어 다음 캐시의 hit/miss delta를 같이 저장한다.

- `odsay_route`
- `ddareungi_snapshot`
- `kickboard_snapshot`
- `mobility_availability`
- `mobility_segment`
- `tmap_pedestrian_route`

백엔드가 최신 코드로 재시작되어 있어야 metric이 반영된다.

## 해석 주의

- 지금 샘플 기준 추천은 모두 `TRANSIT_ONLY`로 나올 수 있다.
- 이 경우 추천 경로와 baseline 차이는 0이 되므로,
  `best mixed alternative` 지표를 같이 봐야 엔진이 왜 혼합 경로를 배제했는지 해석할 수 있다.
- 따릉이 비용은 실제 결제 연동이 아니라 정책 기반 추정값이다.
- `od-samples.mixed-opportunity.json` 실험에서도 mixed가 항상 추천되진 않는다.
- 예를 들어 `hapjeong_res_to_worldcup_park`는 mixed가 시간 기준 `2분` 더 빠르지만,
  접근 도보(`369m`), 공유수단 의존, 비용 증가 때문에 총점은 baseline보다 낮았다.
- `--recommendation-preference` 옵션으로 `RELIABILITY`와 `TIME_PRIORITY`를 나눠 비교할 수 있다.
- 2026-03-16 기준 `OPTIMAL`은 MaaS 추천 의미를 유지하기 위해 `PERSONAL`을 제외했고, `TIME_PRIORITY`에서는 아래 케이스들이 mixed 추천으로 확인됐다.
  - `gupabal_res_to_bukhan_entrance_fast_1` -> `MOBILITY_ONLY` (`3분` 단축)
  - `gupabal_res_to_bukhan_entrance_fast_2` -> `MOBILITY_ONLY` (`3분` 단축)
  - `hapjeong_res_to_worldcup_park_fast_mobility` -> `MOBILITY_ONLY` (`4분` 단축)
  - `hapjeong_res_to_worldcup_park_fast_bike` -> `TRANSIT_WITH_BIKE` (`3분` 단축)
- `mangwon_res_to_worldcup_park_fast_2` -> `MOBILITY_ONLY` (`5분` 단축)
- `mangwon_res_to_worldcup_park_fast_4` -> `MOBILITY_ONLY` (`4분` 단축)
- `mangwon_res_to_worldcup_park_fast_5` -> `TRANSIT_WITH_BIKE` (`5분` 단축)
- mixed-winning 샘플 7건 기준 요약:
  - `TIME_PRIORITY` 평균 `3.857분` 단축
  - 평균 비용 변화 `-57원`
  - `RELIABILITY`는 같은 샘플에서 `TRANSIT_ONLY 7/7` 유지
  - `MOBILITY_ONLY`와 `TRANSIT_WITH_BIKE` 두 유형을 모두 포함
  - warm cache 상태에서는 특히 `tmap_pedestrian_route`가 빠르게 `0 miss`에 수렴하고, `mobility_availability`/`mobility_segment`는 세그먼트 조합 다양성 때문에 일부 miss가 남을 수 있음
- mixed-winning 표본 수는 목표 범위의 하한선(`7건`)에 도달했지만, 아직 목적지 유형이 `북한산/월드컵공원` 축에 몰려 있어 데이터 다양성은 더 보강해야 한다.
- `od-samples.no-mixed.json` 4건은 최신 fallback hub 규칙까지 반영해도 mixed 대안이 없는 샘플로 다시 정리됨
- 같은 `no-mixed` 세트에서 `recommendedGenerationPhaseCounts`는 `FIRST_MILE 4`, `LAST_MILE 4`로 집계되어 어느 구간에서 병목이 큰지 바로 확인 가능
- 최신 코드(`mobility_segment` 캐시 + fallback hub`) 기준 `no-mixed` 재실행에서는 `SAME_PICKUP_DROPOFF`가 사라지고 `NO_PICKUP 6`, `NO_CANDIDATE_HUB 2`로 재분류됨
- 최신 warm second-pass 실험에서는 `tmap_pedestrian_route` miss가 `0`으로 관찰됐고, `mobility_availability`/`mobility_segment`는 허브·세그먼트 조합 다양성 때문에 miss가 일부 유지됨
- 2026-03-18 기준 개인 이동수단 실험(`SPECIFIC + PERSONAL`)에서는 다음과 같은 stronger case를 확보했다.
  - `jamwon_personal_to_banpo_hangang` -> `TRANSIT_WITH_KICKBOARD`, `20분` 단축, 비용 동일
  - `banpo_personal_to_jamwon_hangang` -> `TRANSIT_WITH_KICKBOARD`, `12분` 단축, 비용 동일
  - `mangwon_personal_to_worldcup_park` -> `TRANSIT_WITH_KICKBOARD`, `14분` 단축, 비용 `+50원`
  - `mapo_personal_to_nanji_camp` -> `TRANSIT_WITH_KICKBOARD`, `10분` 단축, 비용 동일
- 이는 개인 이동수단이 기본 `OPTIMAL`에서는 제외되어도, 사용자 보유를 전제로 한 별도 실험 축에서는 훨씬 큰 시간 단축 잠재력이 있음을 보여준다.
- 따라서 포트폴리오에서는 `mixed가 실제로 이기는 사례를 확보했다` 수준으로 표현하고, 일반성을 주장하기보다 샘플 확장 계획을 함께 제시하는 것이 좋다.
