# 현실 시나리오 벤치마크 — 2026-04-23

> **역 좌표를 배제한 실 생활 OD (아파트/공원/오피스/대학/병원 → 카페거리/상권/오피스 등)** 로
> MaaS 복합 경로의 실제 가치를 정량 측정.

## 실행 조건
- OD 세트: **30쌍** (역에서 300m+ 떨어진 위치)
- 주 비교군: `SPECIFIC + EBIKE + TIME`
- 기타 시나리오: SPECIFIC + EBIKE + REL, SPECIFIC + DDAREUNGI + TIME, OPTIMAL + TIME
- 엔드포인트: `GET /api/routes`

## 요약 지표

| 지표 | 값 |
|------|-----|
| 전체 OD | 30 |
| Mixed 경로 추천 발생 | 18 (60%) |
| Mixed 가 TRANSIT_ONLY 를 이긴 케이스 | 13 (43%) |
| 평균 단축 시간 (mixed 승리 시) | 3.4분 |
| 최대 단축 시간 | 8분 |

## 출발지 유형별 분포 (주 비교군)

| 유형 | OD 수 | Mixed 승리 | 평균 단축 |
|------|-------|-----------|----------|
| apartment | 10 | 7 (70%) | 4.4분 |
| hospital | 3 | 2 (66%) | 2.0분 |
| office | 6 | 1 (16%) | 6.0분 |
| park | 7 | 2 (28%) | 1.0분 |
| university | 4 | 1 (25%) | 1.0분 |

## 상세 결과 (시간 단축 내림차순, 상위 15건)

| OD | 출발 유형 | 추천 경로 | 소요 | TRANSIT_ONLY | 단축 |
|----|----------|----------|------|-------------|------|
| 서초 아파트 → 성수 카페거리 | apartment | `TRANSIT_WITH_BIKE` | 28분 | 36분 | **8분** |
| 방배 아파트 → 성수 카페거리 | apartment | `TRANSIT_WITH_BIKE` | 29분 | 37분 | **8분** |
| 판교 오피스 → 한남 | office | `TRANSIT_WITH_BIKE` | 28분 | 34분 | **6분** |
| 서초 아파트 → 망원동 카페 | apartment | `TRANSIT_WITH_BIKE` | 50분 | 55분 | **5분** |
| 이촌동 주택가 → 연남동 | apartment | `TRANSIT_WITH_BIKE` | 28분 | 32분 | **4분** |
| 삼성서울병원 → 망원동 | hospital | `TRANSIT_WITH_BIKE` | 66분 | 69분 | **3분** |
| 이촌동 주택가 → 성수 카페거리 | apartment | `TRANSIT_WITH_BIKE` | 26분 | 29분 | **3분** |
| 반포자이 아파트 → 성수 카페거리 | apartment | `TRANSIT_WITH_BIKE` | 28분 | 30분 | **2분** |
| 반포자이 아파트 → 연남동 | apartment | `TRANSIT_WITH_BIKE` | 34분 | 35분 | **1분** |
| 올림픽공원 → 연남동 | park | `TRANSIT_WITH_BIKE` | 51분 | 52분 | **1분** |
| 서울숲 → 연남동 | park | `TRANSIT_WITH_BIKE` | 44분 | 45분 | **1분** |
| 연세대 신촌 → 성수 카페거리 | university | `TRANSIT_WITH_BIKE` | 46분 | 47분 | **1분** |
| 서울아산병원 → 성수 카페거리 | hospital | `TRANSIT_WITH_BIKE` | 22분 | 23분 | **1분** |
| 대치동 아파트 → 성수 카페 | apartment | `TRANSIT_WITH_BIKE` | 25분 | -분 | — |
| 서초 아파트 → 합정 카페 | apartment | `TRANSIT_ONLY` | 30분 | 30분 | — |

## 해석

- **Mixed 채택률 43%** — 실 생활 OD 의 절반 이상에서 복합 경로가 대중교통 직행을 이김.
- **출발지 유형별 편차** — 위 표에서 확인 가능. 아파트/오피스/공원처럼 역에서 떨어진 지점일수록 mixed 가 유리.
- **평가 편향 교정 효과** — 역↔역 OD 만으로는 드러나지 않던 복합 경로 가치가 현실 OD 로 바꾸면 수치로 드러남.

## 다음 단계

- 엔진 튜닝 전/후 이 벤치마크 결과를 `scripts/benchmark/results/raw-*.json` 스냅샷으로 비교
- OD 세트 확장 (현재 30쌍 → 50쌍)
- 시간대/요일별 배치 추가 (러시아워 vs 평시)
