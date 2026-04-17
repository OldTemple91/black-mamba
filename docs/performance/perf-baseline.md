# Black Mamba — 성능 측정 기준

> 측정 도구: k6 (`scripts/k6/`)
> 측정 스크립트: `./scripts/k6/run.sh <scenario>`

---

## 측정 환경

| 항목 | 값 |
|------|-----|
| 머신 | MacBook (M-series, 로컬) |
| JDK | Temurin 21 |
| Spring Boot | 3.3.0 |
| 외부 API | ODsay, 따릉이, Tmap (실 호출) |

---

## 성능 목표 (SLI/SLO)

### 평시 (50 VU 동시 접속 기준)

| 지표 | 목표 | 근거 |
|------|------|------|
| p95 응답시간 | < 2,000ms | 사용자 체감 한계 (Nielsen 2s rule) |
| p99 응답시간 | < 5,000ms | 외부 API 3~4개 병렬 호출 허용치 |
| 에러율 | < 2% | 외부 API 간헐적 실패 감안 |
| 처리량 | ≥ 30 RPS | 평시 트래픽 여유 확보 |

### 캐시 효과

| 지표 | 목표 |
|------|------|
| Cold vs Warm 응답시간 개선 | ≥ 60% |
| 동일 OD 반복 시 외부 API 호출 | 0건 (완전 캐시 적중) |

---

## 부하 테스트 시나리오

| 시나리오 | VU | 시간 | 목적 |
|---------|-----|------|------|
| **smoke** | 1 | 30초 | 기본 정상성 (CI 포함 가능) |
| **load** | 50 | 7분 | 평시 트래픽 시뮬레이션, SLO 검증 |
| **stress** | 50→100→200 | 10분 | 한계 탐색, 병목 지점 관찰 |
| **spike** | 50→300→50 | 3분 | 급증 트래픽 대응력 검증 |
| **cache** | 1 | 가변 | Cold vs Warm 비교, 캐시 적중률 |

---

## 캐시 전략별 TTL

| 캐시 대상 | TTL | 근거 |
|-----------|-----|------|
| ODsay 대중교통 경로 | 30초 | 시간표 변경 빈도 낮음, 동일 OD 반복 요청 다수 |
| 따릉이 정류소 스냅샷 | 30초 | 실시간 재고 변동, 세션 내 유효성 유지 |
| TMAP 보행자 경로 | 5분 | 도로 경로 변경 거의 없음, API quota 절감 |
| 이동수단 가용성 | 20초 | 따릉이 재고/반납 확인, 짧은 TTL로 신선도 |
| TMAP Rate Limit 백오프 | 1시간 | 429 응답 후 하버사인 fallback 전환 |

---

## 실행 방법

```bash
# 1. 서버 실행 (터미널 A)
./gradlew :api:bootRun

# 2. 부하 테스트 실행 (터미널 B)
./scripts/k6/run.sh smoke    # 기본 검증
./scripts/k6/run.sh load     # 평시 부하
./scripts/k6/run.sh stress   # 한계 탐색
./scripts/k6/run.sh spike    # 급증 대응
./scripts/k6/run.sh cache    # 캐시 효과

# 3. 원격 서버 측정
BASE_URL=https://my-server.com ./scripts/k6/run.sh load
```

k6가 로컬에 없으면 자동으로 Docker(`grafana/k6`)를 사용합니다.

---

## 결과 해석 포인트

### Cold 대비 Warm 개선율이 낮다면?
- ODsay/따릉이 캐시 TTL이 너무 짧음 → `navigation.cache.odsay-route-ttl-ms` 조정
- 동일 OD 요청이 적음 (시나리오 다양성 과다)

### p95가 목표 초과라면?
- 외부 API 호출 병렬화 확인 (`Mono.zip` 사용 여부)
- 허브 후보 수가 과다 → `CandidatePointSelector` 상한 조정
- Thread pool 부족 (WebClient reactor 스레드 확인)

### 에러율이 5% 이상이라면?
- 외부 API quota 초과 → 429 응답 비율 확인
- 타임아웃 (30초) 도달 → `ROUTE_SEARCH_TIMEOUT` 튜닝

---

## 발표 설명 포인트

```
"k6로 smoke/load/stress/spike/cache 5종 시나리오를 구성했습니다.
 평시 50 VU 기준 p95 < 2초를 SLO로 잡고, 캐시 효과는 cache 시나리오로
 cold 대비 warm 응답시간 60% 이상 개선을 검증합니다.
 외부 API quota 제약 때문에 Testcontainers 대신 실 API 호출로 측정하고,
 실패 시 하버사인 fallback으로 응답 연속성을 확보합니다."
```

---

## 측정 결과 (TODO: 실행 후 갱신)

### smoke — 기본 정상성
```
./scripts/k6/run.sh smoke
실행 후 결과 붙여넣기
```

### load — 평시 SLO 검증
```
./scripts/k6/run.sh load
실행 후 결과 붙여넣기
```

### cache — 캐시 효과
```
./scripts/k6/run.sh cache
실행 후 결과 붙여넣기
```
