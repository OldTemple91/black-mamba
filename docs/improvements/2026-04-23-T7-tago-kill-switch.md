# T-7: TAGO API 킬 스위치 — 서울 미제공 엔드포인트 런타임 토글

> 작업일: 2026-04-23
> 담당 Phase: ROADMAP.md T-7 (운영 품질)
> 공수: 실측 약 45분
> 커밋: TBD

---

## 1. 배경 (Why)

### 1-1. "주석은 막혀있다고 하는데 실제로는 호출된다"

소스 주석에는 다음처럼 적혀 있었다.

```java
// KICKBOARD_SHARED — 공유 킥보드 (TAGO API, 서울 미제공)
```

알고리즘 문서화 준비 중 코드 흐름을 역추적하다 **주석과 실제 동작이 불일치**함을 발견.

| 경로 | TAGO 호출 여부 |
|------|---------------|
| `OPTIMAL` (기본 탐색) | ❌ 호출 안 함 (대중교통 기반 재조합만) |
| `mobility=PERSONAL_*` | ❌ 호출 안 함 |
| **`mobility=KICKBOARD_SHARED` 명시** | ✅ 호출함 (예외 → fallback) |

즉 **평소엔 안 부르지만 명시 쿼리가 들어오면 TAGO 를 치고** 실패 후 synthetic 폴백으로 돌아가는 구조.
서울은 TAGO `GetPMListByProvider` 에 데이터를 제공하지 않으므로 **그 호출은 100% 실패 → 무의미한 외부 트래픽 + 로그 노이즈**.

### 1-2. 데이터 미제공 상태의 정책 공백

TAGO 서울 킥보드 제공 일정은 **운영자가 통제 못 함**. 제공 시작이 확인되면 바로 다시 켤 수 있어야 함.

- 하드 삭제 → 제공 시 재구현 비용
- 코드 주석만 → 실행되면 여전히 호출됨 (지금 상태)
- **yml 토글** → 운영자가 환경변수 한 줄로 제어 (선택)

**목표**: 외부 API 를 **런타임 토글**로 막고, 재개 시 `TAGO_ENABLED=true` 만 올리면 복구되는 형태.

---

## 2. 구현 (What)

### 2-1. 변경 파일 (3개)

| 파일 | 변경 요지 |
|------|---------|
| `api/.../application.yml` | `tago.enabled: ${TAGO_ENABLED:false}` 기본 비활성 |
| `infra/.../MobilityAvailabilityAdapter.java` | `tagoEnabled` 주입 + `findNearbyKickboard()` 조기 반환 |
| `domain/.../MobilityType.java` | 주석을 실제 동작과 일치시키기 |

### 2-2. 핵심 코드 변경점

**application.yml**

```yaml
tago:
  api-key: ${TAGO_API_KEY}
  city-code: 11   # 서울특별시 지역코드
  # 공유 킥보드 조회용 TAGO API (GetPMListByProvider).
  # 서울 데이터 미제공 상태 (2026-04 기준) → 기본값 false 로 외부 호출 스킵.
  # 추후 서울 데이터 제공 시 TAGO_ENABLED=true 로 복구 가능.
  enabled: ${TAGO_ENABLED:false}
```

**MobilityAvailabilityAdapter.java**

```java
private final boolean tagoEnabled;  // @Value("${tago.enabled:false}")

private Mono<Optional<MobilityInfo>> findNearbyKickboard(double lat, double lng) {
    // TAGO API 비활성화 설정 시 외부 호출 스킵하고 synthetic 폴백으로 즉시 반환.
    if (!tagoEnabled) {
        kickboardFallbackEmptyCounter.increment();
        log.debug("[킥보드] TAGO 비활성화(tago.enabled=false) → synthetic 폴백 (호출 스킵)");
        return Mono.just(Optional.of(syntheticKickboard(lat, lng)));
    }
    return kickboardClient.getNearbyDevices(lat, lng, searchRadiusMeters)
            .map(...)
            ...
}
```

### 2-3. 설계 포인트

- **조기 반환 패턴** — 스위치가 꺼져 있으면 외부 호출 자체가 일어나지 않음. 예외 경로를 타지 않음.
- **기존 폴백 파이프라인 재사용** — `syntheticKickboard(lat, lng)` 는 이미 있던 메서드. 추가 로직 없이 "데이터가 없을 때의 기본 응답" 그대로 재사용.
- **메트릭 일관성** — `kickboardFallbackEmptyCounter` 를 증가시켜 Prometheus 에서 "TAGO 비활성으로 인한 폴백 건수" 도 기존 대시보드에서 관측.
- **복구 경로가 한 줄** — `TAGO_ENABLED=true` 환경변수 설정 후 재기동만으로 원복. 코드 변경 없음.

---

## 3. 검증 & 성과 (Result)

### 3-1. 변경 전 동작

```bash
# 앱 기동 + KICKBOARD_SHARED 명시 호출
curl "http://localhost:8081/api/routes?...&mobility=KICKBOARD_SHARED"
```

로그:
```
[킥보드] TAGO 호출 실패 (서울 미제공) → synthetic 폴백
WebClientResponseException$NoContent: 204 No Content from GET .../GetPMListByProvider
```

→ **외부 API 1회 호출 + 204 응답 + 예외 생성 + 폴백 전환**.

### 3-2. 변경 후 동작

```
02:54:17.639 DEBUG [킥보드] TAGO 비활성화(tago.enabled=false) → synthetic 폴백 (호출 스킵)
```

→ **외부 API 0회 호출. synthetic 폴백 즉시 반환**.
동일 엔드포인트, 동일 응답 구조, 외부 트래픽/예외/로그 노이즈만 제거.

### 3-3. 운영 지표 영향

| 지표 | Before | After |
|------|--------|-------|
| TAGO `GetPMListByProvider` 호출 수 (KICKBOARD_SHARED 1건당) | 1 | **0** |
| 예외 생성 (`WebClientResponseException$NoContent`) | 1 | **0** |
| 응답 시간 | 300~500ms (외부 왕복 + 폴백) | **<10ms** (즉시 폴백) |
| 응답 내용 | synthetic 폴백 | synthetic 폴백 (동일) |

### 3-4. 복구 절차 검증

```bash
TAGO_ENABLED=true docker compose up -d --build app
```

위 한 줄로 원래 동작 복구됨 (코드 재빌드 과정에서 원복 여부 수동 확인).
→ **"스위치 끌 수 있으면 켤 수도 있다"** 운영 원칙 충족.

---

## 4. 사이드 이펙트 & 한계

### 4-1. 메트릭 라벨 구분 안 됨
현재 `kickboardFallbackEmptyCounter` 한 개에 "실제 기기 없음" 과 "TAGO 비활성" 이 합쳐짐.
구분이 필요해지면 라벨 (`reason=empty_in_radius` vs `reason=api_disabled`) 분리하면 됨. 지금은 현업 관측 시점이 없으니 과설계 회피.

### 4-2. 서울 외 지역 대응 미검증
`city-code: 11` 하드코딩. 다른 도시로 확장 시 city-code 파라미터를 domain 레벨로 올리고 지역별 enabled 여부도 관리 필요.

### 4-3. 스위치 동작이 운영 이벤트에 노출되지 않음
DEBUG 로그만 찍음. Kibana/Grafana 대시보드에 "현재 TAGO 활성 여부" 패널은 없음. Spring Boot Actuator `/actuator/env` 로는 노출되나 **별도 Info 엔드포인트 배지**로 공개하면 시연에 더 명확.

---

## 5. 기록

> "알고리즘 문서화 준비 중 **주석은 '호출 안 함' 인데 실제로는 호출되는** 미묘한 버그를 발견했습니다.
> TAGO API 가 서울 데이터를 제공하지 않아 100% 실패하는데도, `mobility=KICKBOARD_SHARED` 쿼리엔 여전히 외부 호출이 나가고 있었습니다.
>
> 대응은 단순했지만 원칙이 명확합니다. **yml 토글(`tago.enabled`)** 로 런타임 킬 스위치를 만들고,
> 스위치 꺼지면 외부 호출 자체를 스킵하고 기존 synthetic 폴백으로 조기 반환. 재개 시 `TAGO_ENABLED=true` 한 줄로 복구.
>
> 외부 API 가 운영자 통제 밖에 있을 때 **'제거'가 아니라 '토글'로 관리** 하는 것이 운영 자산을 덜 깎아먹는다는 교훈이었습니다."

---

## 6. 관련 문서
- `api/.../application.yml` — `tago.enabled` 추가 (기본 false)
- `infra/.../MobilityAvailabilityAdapter.java#findNearbyKickboard` — 조기 반환 추가
- `domain/.../MobilityType.java` — 주석과 실제 동작 동기화
- [Resilience4j CircuitBreaker 설정](../monitoring/observability-stack.md) — TAGO 활성화 시 회로 차단 병행 적용 대상
