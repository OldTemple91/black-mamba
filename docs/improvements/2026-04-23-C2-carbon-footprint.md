# C-2: 경로별 탄소 배출량 (Carbon Footprint)

> 작업일: 2026-04-23
> 담당 Phase: ROADMAP.md C-2
> 공수: 실측 약 1시간
> 커밋: TBD

---

## 1. 배경 (Why)

### 1-1. 기존 구현의 한계

F-1(자가용 비교) 작업 중에 경로 CO₂ 계산이 `CarReferenceCalculator` 의 **private 메서드**로 들어가 있었다.

```java
// 이전: CarReferenceCalculator.java#routeCo2Grams
return route.legs().stream()
    .mapToDouble(leg -> {
        double legKm = leg.distanceMeters() / 1_000.0;
        return switch (leg.type()) {
            case TRANSIT -> legKm * 68.0;  // 전부 버스 평균
            case BIKE, WALK -> 0.0;
            case KICKBOARD -> legKm * 0.5;  // 킥보드 0.5g?
        };
    }).sum();
```

문제:
- **재사용 불가** — 자가용 비교를 위해서만 계산됨.
- **대중교통 단순화 오류** — 지하철(실제 41 g/km)도 버스(68 g/km)로 계산.
- **킥보드 0.5 g/km 는 비현실적** — 공유 킥보드는 LCA 기준 22 g/km (회수차량 포함).
- **프론트가 쓸 수 없음** — Route 응답에 "이 경로 자체 CO₂ 값" 이 필드로 없음.

### 1-2. 목표

- **독립 컴포넌트** 로 추출해 도메인 이벤트/대시보드/리포트 어디서든 재사용
- **이동수단별 정밀 계수** — 지하철/버스/킥보드(공유/개인)/전기자전거 각각
- **Route 응답에 `carbon` 필드** 노출 → 프론트 친환경 뱃지 렌더링 가능
- **Prometheus 메트릭** — 평균 CO₂, 감축량 추적

---

## 2. 구현 (What)

### 2-1. 변경 파일

| 파일 | 변경 요지 |
|------|---------|
| `application/.../CarbonFootprintCalculator.java` (신규) | 이동수단별 정밀 계수 + 메트릭 |
| `domain/.../route/CarbonSummary.java` (신규) | 응답 스키마 (grams/gramsPerKm/eco/savedVsCarGrams) |
| `domain/.../route/Route.java` | `carbon` 필드 추가 + `withCarbon()` |
| `application/.../CarReferenceCalculator.java` | private `routeCo2Grams()` 제거, 새 Calculator 로 위임 |
| `application/.../RouteOptimizationService.java` | 후처리 파이프라인에 Carbon 부착 추가 |
| `application/.../CarbonFootprintCalculatorTest.java` (신규) | 11개 테스트 |

### 2-2. 이동수단별 계수 (1인 환산)

| 이동수단 | g CO₂/km | 출처 |
|---------|----------|-----|
| **지하철** | **41** | 한국철도 '2023 지속가능경영보고서' 1인km당 탄소강도 |
| **버스** | **68** | 서울시 버스운송사업 평균 (디젤/CNG 혼합) |
| 공유 킥보드 | 22 | LCA 기준 (Hollingsworth 2019) — 배터리 + 회수차량 왕복 |
| 개인 킥보드 | 14 | 배터리 전력만 |
| 개인 전기자전거 | 10 | 전력 소비 극소 |
| 따릉이/도보/자전거 | **0** | |
| **자가용 (비교)** | **171** | 환경부 '2023 국가 온실가스 인벤토리' 승용 휘발유 평균 |

### 2-3. LegType 의 한계 극복

도메인 `LegType` 은 단순 enum (TRANSIT/WALK/BIKE/KICKBOARD).
지하철 vs 버스 구분은 `leg.mode()` 문자열("SUBWAY"/"BUS") 로, 개인/공유 킥보드는 `mode` 이름 ("PERSONAL_EBIKE"/"PERSONAL_KICKBOARD"/"KICKBOARD_SHARED") 로 역추론.

```java
private double mobilityCoefficientByMode(Leg leg) {
    return switch (leg.mode()) {
        case "PERSONAL_EBIKE"     -> EBIKE_G_PER_KM;      // 10
        case "PERSONAL_KICKBOARD" -> PERSONAL_KICKBOARD_G_PER_KM;  // 14
        case "KICKBOARD_SHARED"   -> SHARED_KICKBOARD_G_PER_KM;    // 22
        default                   -> PERSONAL_KICKBOARD_G_PER_KM;
    };
}
```

### 2-4. 응답 스키마

```json
{
  "routeId": "abc-123",
  "type": "TRANSIT_ONLY",
  "totalMinutes": 40,
  "carbon": {
    "grams": 906.1,
    "gramsPerKm": 40.9,
    "eco": false,
    "savedVsCarGrams": 1590.5
  },
  ...
}
```

- `grams` — 경로 전체 CO₂
- `gramsPerKm` — 평균 탄소강도 (프론트 배지에 "41 g/km" 표기)
- `eco` — < 20 g/km 기준 친환경 판정 (자전거/도보 위주 경로)
- `savedVsCarGrams` — 동일 OD 자가용 대비 감축량

### 2-5. Prometheus 메트릭

```
navigation_route_carbon_grams         (히스토그램, p50/p95/p99)
navigation_route_carbon_saved_grams   (감축량 누적)
```

---

## 3. 검증 & 성과 (Result)

### 3-1. 실측 (강남역 → 홍대입구)

```json
[
  {"type":"TRANSIT_ONLY",      "carbon": {"grams":906.1, "gramsPerKm":40.9, "eco":false, "savedVsCarGrams":1590.5}},
  {"type":"TRANSIT_WITH_BIKE", "carbon": {"grams":697.0, "gramsPerKm":28.9, "eco":false, "savedVsCarGrams":1799.6}},
  {"type":"TRANSIT_WITH_BIKE", "carbon": {"grams":647.8, "gramsPerKm":26.4, "eco":false, "savedVsCarGrams":1848.8}}
]
```

지하철 41 g/km 계수 정확 적용 — 22.1km × 41 = 906.1g ✓.

### 3-2. Prometheus 메트릭 노출 확인

```
navigation_route_carbon_grams{quantile="0.5"}  = 640.0
navigation_route_carbon_grams{quantile="0.95"} = 896.0
navigation_route_carbon_grams_count            = 36
navigation_route_carbon_saved_grams_sum        = 21,619g  (12건 누적)
```

→ Grafana 에서 "시간대별 평균 탄소 강도" 패널 추가 가능.

### 3-3. 단위 테스트

`CarbonFootprintCalculatorTest` 11개:
- 지하철/버스 계수 각각 확인
- 도보/자전거 0g
- 킥보드 3종 구분 (공유 22, 개인 14, 전기자전거 10)
- 혼합 경로 합계
- 친환경 판정 (eco < 20 g/km)
- null/빈 경로 안전성
- 자가용 비교 계수 171

---

## 4. 사이드 이펙트 & 한계

### 4-1. 계수는 "1인 환산 평균"
만원 버스와 빈 버스가 같은 계수. 실제론 탑승자 수에 반비례. 대중교통 **시간대별 탑승률** 데이터가 있으면 정교화 가능 (KCTS 데이터 활용).

### 4-2. EV 차량 비교는 미포함
현재 "자가용 171 g/km" 은 휘발유 기준. EV 비교는 한국 전력 믹스 기준 ~60 g/km 인데, **별도 파라미터화 필요** (확장용).

### 4-3. 전기자전거가 LegType.KICKBOARD 로 매핑
`MobilitySegmentBuilder` 에서 `PERSONAL_EBIKE` 도 KICKBOARD 로 매핑됨. 계수는 `leg.mode()` 로 정확히 분리되지만 **타입 표현이 부정확**. 향후 LegType 에 `EBIKE` 추가 고려.

### 4-4. `eco` 플래그 20 g/km 임계값은 임의
"친환경" 정의가 관청마다 다름. 향후 yml 로 설정 가능하도록 분리.

---

## 5. 기록

> "F-1 자가용 비교에서 CO₂ 를 계산하던 private 메서드가 있었는데, 세 가지 문제가 있었습니다 — 재사용 불가, 대중교통을 전부 버스로 단순화, 그리고 `carbon` 필드가 응답에 없어 프론트가 친환경 뱃지를 표시할 수 없었습니다.
>
> `CarbonFootprintCalculator` 를 독립 컴포넌트로 추출하면서 **이동수단별 정밀 계수** (지하철 41, 버스 68, 공유 킥보드 22, 개인 10-14) 를 환경부·한국철도·LCA 연구 기반으로 적용했습니다. Prometheus 히스토그램 2종으로 시간대별 탄소 강도 추이도 관측 가능합니다.
>
> 설계의 핵심은 **'경로 탐색 로직에 침투하지 않는' 후처리**입니다. Route 가 만들어진 후 `withCarbon()` 으로 첨부되므로 탐색 알고리즘엔 0 영향. 다음 단계로 EV 차량 비교 계수 (한전 전력 믹스 60 g/km) 를 추가하면 EV 사용자에게 더 맞춤화된 비교가 가능합니다."

---

## 6. 관련 문서
- [F-1 자가용 비교](./2026-04-20-F1-vs-car-comparison.md) — CO₂ 가 이미 내부에 있었지만 재사용 불가했던 원본
- `application/.../CarbonFootprintCalculator.java` — 신규 독립 컴포넌트
- `domain/.../route/CarbonSummary.java` — 응답 스키마
