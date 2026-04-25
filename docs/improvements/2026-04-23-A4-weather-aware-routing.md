# A-4: 날씨 인식 경로 (Weather-aware Routing)

> 작업일: 2026-04-23
> 담당 Phase: ROADMAP.md A-4
> 공수: 실측 약 1시간
> 커밋: TBD

---

## 1. 배경 (Why)

### 1-1. 날씨 무관한 동일 추천의 문제

기존: 맑은 날 점심과 비 오는 저녁에 **정확히 같은 "따릉이 + 지하철" 경로를 추천.**
현실: 비 오면 공유 자전거 타기 어려움, 눈 오면 킥보드 위험, 폭염/혹한엔 장거리 도보 기피.

### 1-2. 목표

- 날씨에 따라 **이동수단 선호도를 자동 조정**
- 추천 알고리즘 **내부에 침투하지 않고** 후처리로 해결 (C-3 Accessibility 와 동일 패턴)
- KMA(기상청) API 없이도 **API 쿼리로 수동 강제** 가능 (`?weather=RAIN`) — 데모/테스트 용이

---

## 2. 구현 (What)

### 2-1. 변경 파일

| 파일 | 변경 요지 |
|------|---------|
| `domain/.../weather/WeatherCondition.java` (신규) | CLEAR/RAIN/SNOW/HEAT/COLD/UNKNOWN enum |
| `application/.../WeatherContext.java` (신규) | 컨트롤러 → 서비스 전달 컨텍스트 |
| `application/.../WeatherAwareRouteAdjuster.java` (신규) | 후처리 스코어 조정 + 재정렬 |
| `application/.../RouteOptimizationService.java` | 파이프라인에 weather adjuster 추가 |
| `api/.../RouteController.java` | `?weather=RAIN` 쿼리 파라미터 |
| `application/.../WeatherAwareRouteAdjusterTest.java` (신규) | 9개 테스트 |

### 2-2. 후처리 파이프라인 (C-3 패턴 재사용)

```
[Strategy.search]
    ↓ List<Route>
[Accessibility 후처리]  ← 휠체어/노인 필터 + 보행속도 재계산
    ↓
[Weather 후처리]        ← 스코어 조정 + 재정렬 (이번 추가)
    ↓
[Carbon + CarReference 첨부]
    ↓
[History 비동기 저장]
    → 응답
```

Weather adjuster 는 `WeatherContext.hasImpact()` 가 false (CLEAR/UNKNOWN) 이면 원본 그대로 통과. 알고리즘에 0 영향.

### 2-3. 페널티 정책

| 날씨 | 공유 이동수단 (BIKE/KICKBOARD) | 장거리 도보 (≥300m) |
|------|------------------------------|---------------------|
| **RAIN** | × 0.85 | × 0.92 |
| **SNOW** | × 0.70 (강한 감점) | × 0.92 |
| HEAT (35℃+) | — | × 0.92 |
| COLD (-5℃-) | — | × 0.92 |
| CLEAR | — | — |

둘이 겹치면 **곱셈** 으로 누적 (완화하지 않음). 예: RAIN + 장거리 도보 = 0.85 × 0.92 = 0.782.

### 2-4. API 예시

```bash
# 기본 (CLEAR 동일)
curl "/api/routes?originLat=...&destLat=..."

# 비 예보 반영
curl "/api/routes?originLat=...&destLat=...&weather=RAIN"

# 폭설
curl "/api/routes?...&weather=SNOW"
```

---

## 3. 검증 & 성과 (Result)

### 3-1. 강남역 → 홍대입구 실측

**CLEAR:**
```
[0] TRANSIT_ONLY       score=0.785 min=40  recommended=True
[1] TRANSIT_WITH_BIKE  score=0.422 min=69
[2] TRANSIT_WITH_BIKE  score=0.370 min=76
[3] TRANSIT_WITH_BIKE  score=0.365 min=79
```

**RAIN:**
```
[0] TRANSIT_ONLY       score=0.785 min=40  ← 변화 없음 (대중교통만)
[1] TRANSIT_WITH_BIKE  score=0.330 min=69  ← 0.422 × 0.782 (0.85 × 0.92)
[2] TRANSIT_WITH_BIKE  score=0.290 min=76  ← 정확히 계수 적용
[3] TRANSIT_WITH_BIKE  score=0.286 min=79
```

- TRANSIT_ONLY 는 공유 모빌리티 없어 감점 0 (의도대로)
- TRANSIT_WITH_BIKE 는 0.422 × (0.85 × 0.92) = 0.330, 4자리 정확 일치
- 순위 유지됨 — 추천 경로(TRANSIT_ONLY)는 그대로, 2등 경로들은 내부적으로 감점

### 3-2. SNOW vs RAIN 비교 (단위 테스트)

```
RAIN:  자전거 0.8 → 0.680 (× 0.85)
SNOW:  자전거 0.8 → 0.560 (× 0.70)
```

→ SNOW 가 RAIN 보다 더 강하게 감점되어 **폭설 시엔 지하철이 더 명확히 선호**.

### 3-3. 단위 테스트 9개

- CLEAR 는 원본 그대로 반환
- RAIN 에서 자전거 감점, 순위 역전 검증
- SNOW > RAIN (감점 강도)
- 킥보드도 동일 감점
- 대중교통만 경로는 영향 없음
- HEAT 는 장거리 도보만 감점
- 짧은 도보 (<300m) 는 감점 없음
- RAIN + 장거리도보 = 누적 곱셈
- UNKNOWN/null 안전

---

## 4. 사이드 이펙트 & 한계

### 4-1. 실제 기상 데이터 연동은 미완
현재는 `?weather=RAIN` 쿼리 파라미터로 수동 주입.
실제 KMA(기상청) 단기예보 API 연동은 별도 `WeatherPort` 인터페이스로 추후 확장 가능 (현재는 stub 없이 파라미터만).

### 4-2. 위치별 날씨 차이 없음
서울 시내 다 같은 날씨 가정. 대형 OD (서울 → 부산) 처럼 구간별 차이 큰 경우 대응 불가.

### 4-3. 페널티 계수는 경험적 값
0.85 / 0.70 / 0.92 는 "공유 자전거를 얼마나 기피할지" 의 직관에 기반. **사용자 클릭 로그로 학습** (A/B 테스트 프레임워크 D-2 필요) 할 가치.

### 4-4. 이동수단별 민감도 차이 미반영
전기자전거는 자전거보다 빗속 주행이 편하지만 지금은 동일 감점. 세밀화 필요.

---

## 5. 기록

> "기존엔 맑은 날이든 비 오는 날이든 **같은 따릉이 경로**를 추천하고 있었습니다. C-3 Accessibility 에서 쓴 '후처리 패턴' 을 그대로 적용해 날씨 페널티를 추가했습니다.
>
> 핵심 설계는 **알고리즘에 침투하지 않는다**. `WeatherAwareRouteAdjuster` 가 탐색 완료된 Route 리스트를 받아 `score` 를 조정하고 재정렬만 합니다. CLEAR 이면 원본 통과라서 기본 동작엔 0 영향.
>
> 실측으로 강남→홍대 RAIN 쿼리 시 자전거 경로 score 가 0.422 → 0.330 으로 **정확히 계수 × 0.782 (0.85 자전거 감점 × 0.92 장거리도보 감점) 적용**됨을 확인했습니다. SNOW 는 0.70 으로 더 강해서 폭설 시엔 자전거가 훨씬 밀려나는 구조입니다.
>
> 다음 단계로 KMA API 연동과 A/B 테스트 기반 계수 학습을 계획하고 있습니다."

---

## 6. 관련 문서
- [C-3 Accessibility](./2026-04-20-C3-accessibility.md) — 후처리 패턴 원조
- [ROADMAP A-4](../roadmap/ROADMAP.md)
- `domain/.../weather/WeatherCondition.java` — 5단계 날씨 enum
- `application/.../WeatherAwareRouteAdjuster.java` — 페널티 계산 + 재정렬
