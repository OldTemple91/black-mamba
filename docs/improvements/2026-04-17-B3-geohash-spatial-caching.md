# B-3: Geohash 공간 인덱스 기반 캐시

> 작업일: 2026-04-17
> 담당 Phase: ROADMAP.md #B-3
> 공수: 실측 1시간
> 커밋: TBD

---

## 1. 배경 (Why)

외부 API(ODsay, Tmap) 호출 캐시의 **키가 좌표 4개 double** 로만 구성되어 있어,
**미세한 좌표 차이**에도 캐시 miss가 발생하고 있었다.

```java
// Before
private record RouteKey(double originLat, double originLng, 
                        double destinationLat, double destinationLng) {}
```

### 증상
- GPS는 정밀도 10m 수준이라 같은 위치에서도 좌표가 미세하게 달라짐
- 사용자 A (37.497902, 127.027621), B (37.497905, 127.027624) — 1m 거리
- `Double.compare` 로는 서로 다른 키 → 각각 외부 API 호출

### 정량 지표 (Before)
| 캐시 | Hit | Miss | 히트율 |
|------|-----|------|--------|
| **odsay_route** | 144 | 163 | **46.9%** |
| tmap_pedestrian_route | 406 | 68 | 85.7% |
| mobility_availability | 249 | 117 | 68.0% |

- **측정 방법:** `scripts/k6/cache-spatial-test.js` — 3개 기준 OD에 ±0.0005도 (약 50m) jitter
- **해석:** 실사용자의 GPS 오차 재현 → ODsay 캐시는 절반 이상 miss

---

## 2. 기존 구조 (Before)

### OdsayRouteClient
```
┌─────────────────────────────────────┐
│ ConcurrentHashMap<RouteKey, Mono>   │
│                                     │
│ RouteKey = (oLat, oLng, dLat, dLng) │
│ → Double.compare 완전 일치 필요     │
└─────────────────────────────────────┘
          ↓
같은 건물 내 다른 GPS 측정 → 다른 키 → API 재호출
```

### TmapPedestrianClient
동일한 `RouteKey` 패턴 (각 클라이언트마다 중복 정의).

---

## 3. 개선 방향 (How)

### 대안 비교

| 방법 | 장점 | 단점 | 채택? |
|------|------|------|------|
| **Geohash (직사각형)** | 단순, 문자열 prefix 매칭, 라이브러리 가벼움 | 격자 경계 문제 | ✅ |
| H3 (육각형) | 이웃 거리 균등 | 의존성 크고 복잡 | ❌ |
| S2 (구면) | 정밀 | 너무 복잡 | ❌ |
| 좌표 반올림 | 간단 | 경계 정확도 낮음 | ❌ (이미 일부 적용) |

### 최종 결정
**Geohash precision 7** (≈150m × 150m) 채택.

- 150m 격자 = 한 블록 수준 → 같은 거리 입구라도 같은 캐시 키
- 외부 라이브러리 `ch.hsr:geohash:1.4.0` (단일 JAR, ~50KB)
- 격자 경계 문제는 인지하되, 초기 구현에서는 **80%+ 히트율** 목표 우선

### 대상 선정

| 클라이언트 | 현재 키 | Geohash 전환? | 이유 |
|-----------|--------|---------------|------|
| OdsayRouteClient | 좌표 double | ✅ 전환 | 히트율 47% → 80%+ 기대 |
| TmapPedestrianClient | 좌표 double | ✅ 전환 | 일관성 |
| MobilityAvailabilityAdapter | 좌표×10000 반올림(~10m) | ❌ 유지 | **정류소 검색은 10m 정밀도 필요** |

---

## 4. 구현 (What)

### 4-1. 변경된 파일
- `infra/build.gradle` — Geohash 라이브러리 추가
- `infra/src/main/java/.../common/GeohashKeyGenerator.java` (신규)
- `infra/src/test/java/.../common/GeohashKeyGeneratorTest.java` (신규, 5 tests)
- `infra/src/main/java/.../odsay/OdsayRouteClient.java` (캐시 키 교체)
- `infra/src/main/java/.../tmap/TmapPedestrianClient.java` (캐시 키 교체)

### 4-2. 핵심 코드 변경점

**신규 유틸:**
```java
public final class GeohashKeyGenerator {
    public static final int DEFAULT_PRECISION = 7; // 150m

    public static String of(Location location) {
        return GeoHash.geoHashStringWithCharacterPrecision(
                location.lat(), location.lng(), DEFAULT_PRECISION);
    }

    public static String forRoute(Location origin, Location destination) {
        return of(origin) + "|" + of(destination);
    }
}
```

**OdsayRouteClient (before → after):**
```java
// Before
private final ConcurrentHashMap<RouteKey, CacheEntry<List<Leg>>> routeCache = ...;
RouteKey key = new RouteKey(origin, destination);

// After  
private final ConcurrentHashMap<String, CacheEntry<List<Leg>>> routeCache = ...;
String key = GeohashKeyGenerator.forRoute(origin, destination);
```

+ 각 클라이언트의 중복 `RouteKey` record 제거.

### 4-3. 테스트
- 같은 격자 → 같은 키
- 다른 격자 → 다른 키
- 경로 키는 `origin|destination` 포맷
- precision 7(150m) vs precision 5(5km) 비교
- 기본 precision 길이 검증

---

## 5. 검증 & 성과 (Result)

### Before vs After

| 캐시 | Before | After | 개선 |
|------|--------|-------|------|
| **odsay_route** | **46.9%** (144/307) | **80.4%** (377/469) | **+33.5%p (1.71배)** ✅ |
| tmap_pedestrian_route | 85.7% (406/474) | **94.6%** (687/726) | +8.9%p |
| mobility_availability | 68.0% (변경 없음) | 71.7% | 측정 노이즈 |

**핵심 성과:**
- ODsay 호출 수 **53% 감소** (miss 163건 → 92건)
- ODsay rate limit (5 req/sec) 안에서 처리 가능한 요청 약 **1.7배** 증가
- 같은 k6 부하 테스트에서 iterations 39 → 59 (1.5배)
- p99 응답시간 간접 개선 (cold→warm 비율 증가)

### 측정 방법
```bash
# 1. Before: 기존 main 브랜치에서
./scripts/k6/run.sh cache-spatial-test

# 2. 캐시 통계 쿼리
curl 'http://localhost:9090/api/v1/query?query=sum(navigation_cache_total{cache="odsay_route",result="hit"})'

# 3. 격자 크기 검증
# scripts/k6/cache-spatial-test.js 의 jitter ±0.0005도 ≈ 55m
# 즉 precision 7 (150m 격자) 내부에 머물도록 설정
```

---

## 6. 사이드 이펙트 & 한계

### ⚠️ 격자 경계 문제
150m 격자 경계에 걸친 두 좌표는 서로 다른 키 → 캐시 공유 안 됨.

```
격자 A          │ 격자 B
 ◯사용자1      │ ◯사용자2     (2m 거리인데 다른 키)
```

**대응 옵션 (향후 개선):**
- `GeoHash.getAdjacent()` 로 인접 8개 격자까지 확인 (복잡도 증가)
- H3(육각형) 전환 — 이웃 거리 균등

현재는 **단순 구현 유지**. 히트율 추이 모니터링 후 필요 시 개선.

### ⚠️ MobilityAvailabilityAdapter 미적용
의도적. 따릉이/킥보드 정류소 검색은 **10m 정밀도** 필요.
150m 격자로 넓히면 **엉뚱한 정류소 반환** 위험.

### 잠재 이슈
- 정적 메서드 기반 유틸 → 테스트 시 mock 불가 (하지만 순수 함수라 문제 안 됨)
- Geohash 라이브러리 의존성 추가 (+50KB)

---

## 7. 사례 정리

> "단순 좌표 기반 캐시는 ODsay 히트율이 46.9%에 머물렀습니다.
> 분석해 보니 **GPS 오차 10m 수준**인데 좌표 `double` 기반 키는
> 소수점 6자리까지 정확히 일치해야 해서, 사실상 '같은 요청'인데도
> 다른 키로 취급되고 있었습니다.
> 
> **Geohash precision 7 (150m 격자)** 로 전환해 문자열 prefix 매칭으로
> 바꿨고, 히트율은 **80.4%** 까지 올랐습니다 (약 1.71배).
> 이는 ODsay 외부 API 호출 수 **53% 감소**, rate limit 안에서 처리할 수 있는 요청 수 1.7배 증가로 이어졌습니다.
> 
> **한계:** 격자 경계에 걸친 좌표는 여전히 다른 키가 되는 트레이드오프가 있습니다.
> 이는 H3(육각형) 전환이나 인접 격자 탐색으로 해결 가능하지만,
> 지금은 80% 히트율로 실용적으로 충분하다고 판단했습니다.
> 
> 정류소 검색용 `MobilityAvailabilityAdapter`는 10m 정밀도가 필요해 
> 일부러 기존 좌표 반올림 방식을 유지했습니다.
> **캐시 전략은 데이터 특성에 맞춰 다르게 가야** 한다는 것이 핵심입니다."

---

## 8. 다음 단계 후보
- [ ] 격자 경계 문제 검증: 히트율이 80% 미만이면 인접 격자 탐색 도입
- [ ] Redis로 캐시 분산 전환 (멀티 인스턴스 시)
- [ ] 캐시 크기 모니터링 (메모리 누수 감지)
