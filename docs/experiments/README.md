# Experiments

## 목적

배치 O/D 샘플에 대해 `OPTIMAL` 탐색 결과를 수집하고,

- baseline `TRANSIT_ONLY`
- 실제 추천 경로
- best mixed alternative

를 비교하기 위한 실험 자산 모음이다.

## 입력 샘플

- [od-samples.seoul.json](/Users/sjw/Desktop/black-mamba/docs/experiments/od-samples.seoul.json)

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
  --input /Users/sjw/Desktop/black-mamba/docs/experiments/od-samples.seoul.json
```

## 출력

- timestamped JSON: `output/experiments/route-eval-YYYYMMDD-HHMMSS.json`
- timestamped Markdown: `output/experiments/route-eval-YYYYMMDD-HHMMSS.md`
- latest JSON: `output/experiments/latest-route-eval.json`
- latest Markdown: `output/experiments/latest-route-eval.md`

## 현재 지표

- 추천 경로 타입 분포
- baseline 대비 시간/도보/비용/점수 차이
- 추천 경로 접근 도보
- best mixed alternative 기준 시간/도보/비용/점수 차이
- `navigation.cache.total` 기준 cache hit/miss delta

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
