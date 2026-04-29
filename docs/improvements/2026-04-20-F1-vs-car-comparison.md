# F-1: "vs 자가용" 비교 응답 (MaaS 정체성 전환)

> 작업일: 2026-04-20
> 담당 Phase: docs/research/2026-04-20-maas-strategy-analysis.md 방향 1
> 공수: 실측 4시간
> 커밋: TBD

---

## 1. 배경 (Why)

MaaS 전략 분석 결과, 지금까지의 프로젝트 정체성이 **"경로 탐색 앱"** 수준에서 머물러 있었다. 사용자가 경로 결과를 봐도:

```
"지하철 2호선 + 도보, 40분, 1,650원"
```

**비교 기준이 없어** "그냥 차 타고 가자" 라는 결론으로 빠지기 쉽다.
자동차 제조사가 MaaS에 투자하는 본질은 **"차를 덜 팔고 이동 서비스를 파는"** 전환인데, 그 전환을 위해선 **"자가용 없이도 이 경로면 충분하다"** 는 정량 근거가 필요하다.

---

## 2. 기존 구조 (Before)

### API 응답
```json
{
  "routes": [
    { "type": "TRANSIT_ONLY", "totalMinutes": 40, "totalCostWon": 1650 }
  ]
}
```
→ 사용자 판단에 필요한 **비교 기준 없음**.

### `Route` 도메인
```java
public record Route(
    // ... 기존 필드 ...
    Comparison comparison,       // 대중교통 기준 비교만
    RouteInsights insights
) {}
```
`Comparison`은 "기존 대중교통 vs 혼합 경로" 비교에 국한 — **자가용 축이 없음**.

---

## 3. 개선 방향 (How)

### 추가할 것: **자가용 기준값 계산 + 비교 생성**

#### 계산 가정 (한국 평균 승용차 기준)
| 항목 | 값 | 근거 |
|------|-----|------|
| 우회 계수 | 1.3 | 직선 → 실 도로 |
| 속도 | 도심 25 / 외곽 40 / 고속 70 km/h | 도심 혼잡 반영 |
| 연비 | 12 km/L | 국내 휘발유 차 평균 |
| 휘발유가 | 1,700원/L | 시가 |
| 주차비 | 기본 3,000원 | 1시간 기준 |
| 톨게이트 | 30km 초과분 × 100원 | 단순 선형 |
| **CO₂ 배출** | **171 g/km** | 환경부 공식 평균 |

#### narrative 템플릿 예시
```
"자가용보다 4분 더 걸리지만 3,418원 절약, 탄소 994g 감소."
"자가용보다 15분 빠르며 1,200원 절약, 탄소 1.3kg 감소."
```

---

## 4. 구현 (What)

### 4-1. 변경 파일
- **도메인 신규**
  - `domain/.../route/CarReference.java`
  - `domain/.../route/RouteComparison.java`
- **도메인 수정**
  - `domain/.../route/Route.java` (필드 추가 `carComparison`)
- **애플리케이션 신규**
  - `application/.../route/CarReferenceCalculator.java`
- **애플리케이션 수정**
  - `application/.../route/RouteOptimizationService.java` (주입 + 일괄 첨부)
- **테스트 신규**
  - `application/src/test/.../CarReferenceCalculatorTest.java` (6 tests)
- **기존 테스트 수정**
  - `RouteScoreCalculatorTest` 3곳 + `RouteControllerTest` 4곳 + `RouteOptimizationServiceTest` Spy 추가
- **프론트엔드**
  - `frontend/src/components/route/RouteCard.jsx` (배지 + narrative)

### 4-2. 핵심 코드

**CarReferenceCalculator:**
```java
@Component
public class CarReferenceCalculator {
    public CarReference estimate(Location origin, Location destination) {
        double distKm = haversineKm(origin, destination) * DETOUR_FACTOR;
        int minutes = estimateMinutes(distKm);
        int fuel = (int)(distKm / 12.0 * 1700);
        int parking = 3000;
        int toll = distKm > 30 ? (int)((distKm - 30) * 100) : 0;
        double co2 = distKm * 171.0;
        // ...
    }

    public RouteComparison compareWithRoute(Route route, Location origin, Location destination) {
        CarReference car = estimate(origin, destination);
        int timeDiff = route.totalMinutes() - car.estimatedMinutes();
        int costSaved = car.estimatedCostWon() - route.totalCostWon();
        double co2Reduced = car.estimatedCo2Grams() - routeCo2Grams(route);
        String narrative = buildNarrative(timeDiff, costSaved, co2Reduced);
        return new RouteComparison(car, timeDiff, costSaved, co2Reduced, narrative);
    }
}
```

**Service에서 일괄 첨부:**
```java
return strategy.search(origin, destination)
        .map(routes -> attachCarComparison(routes, origin, destination));
```

**프론트엔드 배지:**
```jsx
{route.carComparison && (
  <div className="vs-car-badge">
    🚗 자가용 대비: +{timeDiff}분 | -{saved}원 | -{co2}g CO₂
    <p>{narrative}</p>
  </div>
)}
```

---

## 5. 검증 & 성과 (Result)

### 5-1. 실측 응답 (강남 → 홍대)

```
총 경로: 3개

[#1] TRANSIT_ONLY: 40분 / 1,650원
  🚗 자가용: 36분 / 5,068원 / 2,497g CO₂
  ⏱ 시간차: +4분
  💰 절약:  3,418원
  🌱 탄소:  994g 감소
  💬 "자가용보다 4분 더 걸리지만 3,418원 절약, 탄소 994g 감소."

[#2] TRANSIT_WITH_BIKE: 69분 / 2,650원
  💬 "자가용보다 33분 더 걸리지만 2,418원 절약, 탄소 1.3kg 감소."

[#3] TRANSIT_WITH_BIKE: 79분 / 2,650원
  💬 "자가용보다 43분 더 걸리지만 2,418원 절약, 탄소 1.6kg 감소."
```

### 5-2. Before vs After (응답 품질)

| 항목 | Before | After |
|------|--------|-------|
| 경로당 정보량 | 시간 + 비용 2개 | **+ 자가용 기준값 + 차이 + narrative** |
| 사용자 의사결정 | "차 탈까?" 고민 | **"4분 더 걸리지만 3,418원 절약"** 명확 |
| 탄소 정보 | 없음 | **있음** (ESG 설득) |
| MaaS 정체성 | "경로 앱" | **"자가용 대체 설득 엔진"** |

### 5-3. 테스트 통과
- CarReferenceCalculatorTest **6 tests** 통과
- 기존 테스트 전부 통과 (Route 생성자 변경 대응)
- 프론트엔드 lint + build OK

---

## 6. 사이드 이펙트 & 한계

### ⚠️ Route 생성자 시그니처 변경
`carComparison` 필드 추가로 모든 `new Route(...)` 호출이 깨짐.
- 7곳 테스트 + Route 내부 메서드 5곳 수정
- `withCarComparison(...)` helper 추가
- Breaking change지만 domain 모듈이라 영향 범위 예측 가능

### ⚠️ 단순화된 가정
- 자가용 속도/비용은 **고정 상수** (차종/지역 무관)
- 실제로는 경차 vs SUV, 서울 vs 부산 차이 큼
- 확장 여지: `application.yml`에 차종별/지역별 상수 분리 가능

### ⚠️ 탄소 계산 단순화
- 현재 대중교통은 km당 68g (버스 기준)
- 지하철은 실제 41g/km로 더 낮은데 반영 안 됨
- 정확한 수치는 환경부 모드별 평균 적용 필요 (향후 개선)

### ⚠️ 단거리 비교
700m 이내는 `SHORT_DISTANCE` 에러로 경로 반환 안 됨 → carComparison 문제 없음.

---

## 7. 사례 정리

> **"자동차 회사의 미래는 무엇인가?"**
>
> "**차를 덜 팔고 이동 서비스를 파는 방향**이라고 이해했습니다.
> 그러려면 사용자가 **'자가용 없이도 이 경로면 충분하다'** 는 설득 근거를 가져야 합니다.
>
> 제 엔진은 단순 경로 정보를 넘어서 **자가용 기준 시간/비용/탄소를 실시간 계산**하고 경로별 비교 결과를 첨부합니다. 강남↔홍대 실제 응답을 보면:
> - 대중교통 40분 / 1,650원
> - **자가용 36분 / 5,068원 / 2,497g CO₂**
> - **"자가용보다 4분 더 걸리지만 3,418원 절약, 탄소 994g 감소"**
>
> 이 narrative가 자가용 대체 의사결정을 돕는 핵심입니다. 자동차 제조사가 ESG와 MaaS에 투자하는 이유와 정확히 맞닿아 있다고 봅니다."

### 설명 예상 질문 대응
- **"자가용 기준값은 어떻게 산정했나?"** → 환경부 공식(171g CO₂/km) + 도심 평균 속도/연비 + 주차비/톨게이트 단순 모델. application.yml로 지역별 조정 확장 여지 있음.
- **"왜 시간은 자가용이 더 빠른데 경로가 추천되나?"** → 비용/탄소 2축이 더 크게 작용. 사용자가 직접 판단할 수 있도록 **3축 모두 노출**.
- **"EV는 왜 안 다뤘나?"** → ADR-003에서 결정한 대로 **자가용 전제인 EV**는 "차 덜 쓰게 만드는" MaaS 정체성과 충돌. 제외.

---

## 8. 다음 단계
- [ ] **C-3 Accessibility** 다음 작업 (포용성 보완)
- [ ] 자가용 상수를 application.yml로 분리 (차종/지역 확장성)
- [ ] 경로별 정확한 탄소 계산 (지하철/버스/따릉이 분리)
- [ ] Grafana 대시보드에 "자가용 대비 평균 절약액/탄소감소" 메트릭 추가

---

## 9. 관련 문서
- [MaaS 전략 분석](../research/2026-04-20-maas-strategy-analysis.md)
- [ADR-003: Carshare as MobilityHub](../adr/003-carshare-as-mobility-hub-not-type.md)
