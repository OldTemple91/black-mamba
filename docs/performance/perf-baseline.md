# Black Mamba — 성능 측정 기록

> 측정 방법: `./scripts/perf-test.sh` 실행

---

## 측정 환경

| 항목 | 값 |
|------|-----|
| 머신 | MacBook (M-series, 로컬) |
| JDK | Temurin 21 |
| Spring Boot | 3.3.0 |
| 외부 API | ODsay, 따릉이, Tmap (실 호출) |

---

## 기대 응답 시간 (설계 기준)

| 구간 | 목표 | 비고 |
|------|------|------|
| Cold (첫 요청) | < 5,000ms | 외부 API 3~4개 병렬 호출 포함 |
| Warm (캐시 hit) | < 1,000ms | ODsay/따릉이/Tmap 캐시 적중 |
| 타임아웃 | 30,000ms | RouteController 타임아웃 설정 |

---

## 캐시 전략별 TTL

| 캐시 대상 | TTL | 근거 |
|-----------|-----|------|
| ODsay 대중교통 경로 | 30초 | 대중교통 시간표 변경 빈도 낮음, 동일 OD 반복 요청 다수 |
| 따릉이 정류소 스냅샷 | 30초 | 실시간 재고 변동, 30초면 사용자 세션 내 유효 |
| TMAP 보행자 경로 | 5분 | 도로 경로 변경 거의 없음, API quota 절감 |
| 이동수단 가용성 | 20초 | 따릉이 재고/반납 확인, 짧은 TTL로 신선도 유지 |
| TMAP Rate Limit 백오프 | 1시간 | 429 응답 후 하버사인 fallback 전환 |

---

## 측정 결과 (TODO: perf-test.sh 실행 후 갱신)

```
./scripts/perf-test.sh 실행 후 output/perf-results-*.md 참조
```

---

## 캐시 효과 분석 포인트

1. **Cold → Warm 개선율**: 첫 요청 대비 2회차 응답시간 몇 % 감소?
2. **TMAP miss → 0**: Warm 상태에서 TMAP 캐시 miss가 0으로 수렴하는지?
3. **따릉이 스냅샷**: 동일 반경 반복 호출 시 API 재호출 없이 필터링만?
4. **API 호출 횟수**: 경로 탐색 1건당 외부 API 호출 몇 건?

---

## 발표 설명 포인트

```
"Cold 상태에서 3~5초, Warm 상태에서 1초 미만으로 응답합니다.
 ODsay, 따릉이, TMAP 각각 TTL 기반 캐시를 적용하고,
 TMAP 429 응답 시 1시간 백오프 후 하버사인 fallback으로 전환합니다.
 캐시 hit/miss 비율은 Micrometer 메트릭으로 모니터링합니다."
```
