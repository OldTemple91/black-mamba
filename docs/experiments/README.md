# Experiments

## 목적

배치 O/D 샘플에 대해 `OPTIMAL` 탐색 결과를 수집하고,

- baseline `TRANSIT_ONLY`
- 실제 추천 경로
- best mixed alternative

를 비교하기 위한 실험 자산 모음이다.

## 입력 샘플

- [od-samples.seoul.json](/Users/sjw/Desktop/black-mamba/docs/experiments/od-samples.seoul.json)
- [od-samples.mixed-opportunity.json](/Users/sjw/Desktop/black-mamba/docs/experiments/od-samples.mixed-opportunity.json)
- [od-samples.mixed-winning.json](/Users/sjw/Desktop/black-mamba/docs/experiments/od-samples.mixed-winning.json)

## 실행 방법

백엔드가 `http://localhost:8081` 에서 실행 중이어야 한다.

```bash
/Users/sjw/Desktop/black-mamba/scripts/evaluate_routes.py
```

옵션 예시:

```bash
/Users/sjw/Desktop/black-mamba/scripts/evaluate_routes.py \
  --base-url http://localhost:8081 \
  --search-mode OPTIMAL \
  --recommendation-preference RELIABILITY \
  --input /Users/sjw/Desktop/black-mamba/docs/experiments/od-samples.seoul.json
```

mixed 경로가 유리할 가능성이 있는 목적지 세트:

```bash
/Users/sjw/Desktop/black-mamba/scripts/evaluate_routes.py \
  --base-url http://localhost:8081 \
  --search-mode OPTIMAL \
  --recommendation-preference TIME_PRIORITY \
  --input /Users/sjw/Desktop/black-mamba/docs/experiments/od-samples.mixed-opportunity.json
```

현재 `TIME_PRIORITY`에서 mixed 추천이 실제로 확인된 대표 케이스만 보고 싶다면:

```bash
/Users/sjw/Desktop/black-mamba/scripts/evaluate_routes.py \
  --base-url http://localhost:8081 \
  --search-mode OPTIMAL \
  --recommendation-preference TIME_PRIORITY \
  --input /Users/sjw/Desktop/black-mamba/docs/experiments/od-samples.mixed-winning.json
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

## 캐시 메트릭

실험 스크립트는 실행 전후의 actuator metric을 읽어 다음 캐시의 hit/miss delta를 같이 저장한다.

- `odsay_route`
- `ddareungi_snapshot`
- `kickboard_snapshot`
- `mobility_availability`
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
- 2026-03-13 기준 `OPTIMAL`은 MaaS 추천 의미를 유지하기 위해 `PERSONAL`을 제외했고, `TIME_PRIORITY`에서는 아래 케이스가 mixed 추천으로 확인됐다.
  - `gupabal_res_to_bukhan_entrance_fast_1` -> `MOBILITY_ONLY` (`3분` 단축)
  - `gupabal_res_to_bukhan_entrance_fast_2` -> `MOBILITY_ONLY` (`3분` 단축)
  - `hapjeong_res_to_worldcup_park_fast_mobility` -> `MOBILITY_ONLY` (`4분` 단축)
  - `hapjeong_res_to_worldcup_park_fast_bike` -> `TRANSIT_WITH_BIKE` (`3분` 단축)
- mixed-winning 샘플 4건 기준 요약:
  - `TIME_PRIORITY` 평균 `3.25분` 단축
  - 평균 비용 변화 `-125원`
  - `RELIABILITY`는 같은 샘플에서 `TRANSIT_ONLY 4/4` 유지
  - `MOBILITY_ONLY`와 `TRANSIT_WITH_BIKE` 두 유형을 모두 포함
  - warm cache 상태에서는 `mobility_availability`, `odsay_route`, `tmap_pedestrian_route` miss가 `0`
