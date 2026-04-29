# C-3: Accessibility 경로 (포용성)

> 작업일: 2026-04-20
> 담당 Phase: docs/research/2026-04-20-maas-strategy-analysis.md 방향 3
> 공수: 실측 2시간
> 커밋: TBD

---

## 1. 배경 (Why)

MaaS 전략 분석에서 정체성을 **"자가용 없이도 편한 도시 이동"** 으로 재정의했다. 이때 "자가용 없이 편함"의 범위에 **운전을 못 하는 사람들**까지 포함돼야 진정한 MaaS다.

### 대상 사용자
- **노인**: 평균 보행 속도 3 km/h (일반 4.5 km/h의 67%)
- **휠체어 사용자**: 엘리베이터 없는 지하철역 진입 불가
- **유모차 동반자**: 계단 많은 경로 사실상 불가능

### 현재 문제
- 모든 경로가 **성인 평균 기준**으로 계산됨
- 엘리베이터 없는 역을 환승 후보로 추천 → **사용 불가 경로**
- 이게 "MaaS"라고 말하기 어려운 수준의 포용성

---

## 2. 기존 구조 (Before)

### API 요청
```
GET /api/routes?originLat=...&originLng=...&destLat=...&destLng=...
  &searchMode=OPTIMAL&recommendationPreference=RELIABILITY
```
→ Accessibility 파라미터 없음. 무조건 성인 기준.

### 도보 시간 계산
```java
// MobilityTimeAdapter
private static final double WALKING_KMH = 4.5;  // 고정값, 사용자 의견 반영 불가
```

---

## 3. 개선 방향 (How)

### 원칙: **기존 라우팅 파이프라인에 침투하지 않기**

라우팅 알고리즘에 조건문 주입하면:
- 복잡도 급증
- 테스트 범위 확대
- 다른 옵션 추가 시 연쇄 수정

대신 **후처리(Post-Process) 레이어** 도입:

```
[Strategy가 경로 생성]
    ↓
[AccessibilityPostProcessor] ← 여기서 필터/재계산만
    ↓
[CarReferenceCalculator 첨부]
    ↓
[API 응답]
```

### 제공할 2가지 옵션
1. `wheelchairAccessible=true` → 엘리베이터 없는 역 포함 경로 **제외**
2. `walkingSpeedKmh=3.0` → WALK leg 시간을 **사용자 속도로 재계산**

---

## 4. 구현 (What)

### 4-1. 신규 파일
- `application/.../route/AccessibilityContext.java` — 요청 컨텍스트 record
- `application/.../route/AccessibilityStationRegistry.java` — 엘리베이터 미지원 역 레지스트리
- `application/.../route/AccessibilityPostProcessor.java` — 후처리 로직
- `application/src/test/.../AccessibilityPostProcessorTest.java` — 5 tests

### 4-2. 수정 파일
- `application/.../route/RouteOptimizationService.java` — findRoutes overload + post-process chain
- `api/.../route/RouteController.java` — `wheelchairAccessible`, `walkingSpeedKmh` 파라미터

### 4-3. 핵심 코드

**AccessibilityContext (record):**
```java
public record AccessibilityContext(
    boolean wheelchairAccessible,
    Double walkingSpeedKmh
) {
    public static final AccessibilityContext DEFAULT = new AccessibilityContext(false, null);
    public boolean hasAnyConstraint() {
        return wheelchairAccessible || walkingSpeedKmh != null;
    }
}
```

**엘리베이터 레지스트리 (정적 리스트):**
```java
@Component
public class AccessibilityStationRegistry {
    private static final Set<String> STATIONS_WITHOUT_ELEVATOR = Set.of(
        "남영", "신설동", "청량리"
        // 실운영 시 공공데이터 API로 대체
    );

    public boolean isWheelchairAccessible(String stationName) {
        if (stationName == null || stationName.isBlank()) return true;
        return STATIONS_WITHOUT_ELEVATOR.stream()
                .noneMatch(stationName::contains);
    }
}
```

**후처리 (핵심):**
```java
public List<Route> apply(List<Route> routes, AccessibilityContext ctx) {
    if (!ctx.hasAnyConstraint()) return routes;

    List<Route> result = new ArrayList<>();
    for (Route route : routes) {
        if (ctx.wheelchairAccessible() && containsInaccessibleStation(route)) continue;
        Route adjusted = ctx.walkingSpeedKmh() != null
                ? recomputeWalkingDuration(route, ctx.walkingSpeedKmh())
                : route;
        result.add(adjusted);
    }
    return result;
}

private Route recomputeWalkingDuration(Route route, double walkingSpeedKmh) {
    double ratio = 4.5 / walkingSpeedKmh;   // 3.0 kmh → 1.5배
    // WALK leg durationMinutes × ratio 재계산 + 총시간 업데이트
}
```

**RouteOptimizationService 체인:**
```java
return strategy.search(origin, destination)
        .map(routes -> accessibilityPostProcessor.apply(routes, accessibilityContext))
        .map(routes -> attachCarComparison(routes, origin, destination));
```

---

## 5. 검증 & 성과 (Result)

### 5-1. 실측 (강남 → 홍대)

| 요청 | TRANSIT_ONLY | TRANSIT_WITH_BIKE #1 | TRANSIT_WITH_BIKE #2 |
|------|-------------|---------------------|---------------------|
| 기본 (4.5km/h) | 40분 | 69분 | 79분 |
| **walkingSpeedKmh=3.0** | **42분** (WALK 4분) | **78분** (WALK 25분) | **89분** (WALK 28분) |
| wheelchairAccessible=true | 40분 | 69분 | 79분 (모두 통과) |

**해석:**
- 노인 속도(3.0 km/h) 반영 시 WALK 구간이 늘어나 **총시간 증가** (2~10분)
- 휠체어 테스트 경로는 엘리베이터 있는 역만 거쳐 필터링 통과 (정상 동작)

### 5-2. 테스트
- **AccessibilityPostProcessorTest 5건 신규**
  - 제약 없으면 원본 반환
  - 휠체어 요청 시 엘리베이터 없는 역 경로 제외
  - walkingSpeedKmh 반영 시 WALK leg 시간 증가
  - 휠체어 요청 없으면 엘리베이터 없는 역도 유지
  - 레지스트리가 null/blank 안전 처리
- 기존 22 tests 전부 통과

---

## 6. 사이드 이펙트 & 한계

### ⚠️ 엘리베이터 데이터 정확도
- 현재 **3개 샘플 역만** 하드코딩 (실제 서울 수백 역 중 일부만)
- 공사/고장으로 일시적 미사용 상황 반영 불가
- **개선 방안**: 서울시 열린데이터 API 연동 (`공공_지하철 편의시설 정보`) — TAGO 경험상 품질 검증 필수

### ⚠️ 지하철 외 교통수단 미반영
- 버스/따릉이는 엘리베이터 개념 무의미 → 제외
- 하지만 **저상버스만 이용**, **자전거 경로 경사 회피** 같은 확장은 여지로 남김

### ⚠️ 보행 속도 단순화
- WALK leg `durationMinutes`만 비례 스케일
- 실제로는 경사/계단/신호대기 등 다양한 요소 있음
- 현재 구현은 "대략적 보정" 수준

### ⚠️ ROUTE 생성자 시그니처 또 깨졌음
- F-1 이후 두 번째. 앞으로도 이런 필드 추가 때마다 테스트 보정 필요
- **개선 방안**: `Route.Builder` 도입 검토 (record 유지하면서)

---

## 7. 사례 정리

> **"MaaS의 본질은 무엇인가?"**
>
> "자동차 없이도 자유롭게 이동할 수 있게 만드는 것이지만, 그 범위에 **운전을 못 하는 사람들**까지 포함돼야 진짜 MaaS입니다. 노인, 장애인, 유모차 동반자 같은.
>
> 그래서 `wheelchairAccessible=true` 파라미터로 엘리베이터 없는 역이 포함된 경로를 제외하고, `walkingSpeedKmh=3.0`으로 노인 보행 속도를 반영한 시간 재계산을 지원합니다.
>
> 설계 원칙은 **기존 라우팅 파이프라인에 침투하지 않고 후처리 레이어로 분리**한 것입니다. Strategy 패턴은 성인 기준으로 계산만 하고, `AccessibilityPostProcessor`가 결과를 필터/조정합니다. 이렇게 하면 나중에 저상버스 옵션, 경사 회피 같은 접근성 조건 추가할 때 **라우팅 코드는 그대로 두고 Post-Processor만 확장**하면 됩니다.
>
> UAM/자율주행이 준비되는 이유 역시 이 방향 — **운전 못 하는 사람까지 이동 자유 보장** — 에 있다고 봅니다."

### 설명 예상 질문
- **"엘리베이터 데이터는 어디서?"** → 현재 정적 샘플, 공공 API 연동 확장 여지. TAGO 경험으로 데이터 품질 검증 우선.
- **"후처리 방식의 단점?"** → 라우팅 단계에서 미리 배제할 수 있는 계산을 끝까지 한 후 제거. 오버헤드 있음. 다만 복잡도↓, 확장성↑ 트레이드오프 선택.
- **"휠체어 요청 시 경로가 없으면?"** → 현재 빈 목록 반환. UX 관점에서 "기본 옵션으로 재시도 제안" 프론트엔드 개선 여지.

---

## 8. 다음 단계
- [ ] 서울시 엘리베이터 공공 API 연동 (정적 목록 → 실시간)
- [ ] 저상버스 경로 선호 옵션 (버스 TransitInfo에 저상 여부 추가 필요)
- [ ] 경사 회피 (Tmap 경로의 elevation 활용)
- [ ] 프론트엔드 체크박스/슬라이더 UI (Phase 2)

---

## 9. 관련 문서
- [MaaS 전략 분석](../research/2026-04-20-maas-strategy-analysis.md)
- [F-1 vs 자가용 비교](./2026-04-20-F1-vs-car-comparison.md)
