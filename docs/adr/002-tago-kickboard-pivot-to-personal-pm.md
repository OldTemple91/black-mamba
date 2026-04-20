# ADR-002: TAGO 공유킥보드 API 포기 → Personal PM으로 pivot

## Status
Accepted (2026-03-10)

## Context

초기 설계에서 공유 킥보드는 **국토부 TAGO API** (`GetPMListByProvider`)를 사용 계획이었다.
그러나 실제 연동 시 다음 문제가 발견됐다:

1. **서울 지역 데이터 미제공**: cityCode=11(서울) 요청에도 차량 목록 비어 반환
2. **응답 포맷 불일치**: `_type=json` 파라미터 무시하고 XML 반환 (`<response>...</response>`)
3. **엔드포인트 문서 혼동**: `getPMProvider`(운영사 목록)과 `GetPMListByProvider`(차량 위치) 혼재

초기엔 API 사용 실패 시 **가상 데이터 fallback**(`syntheticKickboard`)으로 임시 대응했으나,
경로 탐색 결과에 **가상 데이터 기반 경로가 섞여 들어가서** 포트폴리오 품질에 문제가 생겼다.

## Decision

**공유 킥보드 모델 포기. `KICKBOARD_SHARED` 호출 경로만 차단하고,
대신 `PERSONAL_EBIKE` / `PERSONAL_KICKBOARD` 타입 신설**.

### 변경 내역

1. `MobilityType.KICKBOARD_SHARED` 는 enum에 **남겨둠** (코드 보존)
2. `OptimalSearchStrategy.ALL_TYPES` 에서 KICKBOARD_SHARED **제거**
3. `MobilitySelector.jsx` 프론트엔드에서 "공유킥보드" 옵션 `unavailable: true`
4. **개인 이동수단 신규 도입**:
   - `PERSONAL_EBIKE`: 22 km/h (전기자전거)
   - `PERSONAL_KICKBOARD`: 20 km/h (개인 전동킥보드)
5. API 호출 없이 **현 위치에서 즉시 탑승** 전제 → 픽업 대기시간/가용성 걱정 없음

## Consequences

**Pro:**
- 경로 탐색에 **허구 데이터 섞이지 않음** (신뢰도↑)
- 개인 PM은 API 의존 없어 **장애에 강함**
- EV/자동차 제조사 관점: "사용자가 소유한 PM" 시나리오가 더 현실적
- 경로 품질 평가 가능 (실측 데이터만 사용)

**Con:**
- 공유 킥보드 사용 시나리오는 커버 불가
- 향후 카셰어 사업자/SWING 등 **파트너십 API 연동 시 재작업** 필요
- 프론트엔드에 "준비중" UI 처리 비용

## Alternatives Considered

### 대안 A: 따릉이 정류소를 킥보드 대체 proxy로 사용
- 접근: 따릉이 정류소 근처에 공유킥보드도 있다는 가정
- 장점: 구현 간단
- 단점:
  - **실데이터 아닌 추정** → 포트폴리오 품질 저하
  - "가정 기반 추천"은 발표에서 설명하기 어색
  - 따릉이는 도심, 킥보드는 주거지 배치가 달라 상관관계 약함
- **채택 안 함**

### 대안 B: 민간 킥보드 업체 직접 연동 (SWING, Kickgoing 등)
- 장점: 실시간 실데이터
- 단점:
  - 공개 API 없음 (파트너십 계약 필수)
  - 각 업체별 인증/rate limit 관리
  - 포트폴리오 범위 초과
- **채택 안 함**

### 대안 C: 공유킥보드 기능 전면 제거
- 장점: 가장 깔끔
- 단점: `MobilityType.KICKBOARD_SHARED` enum 삭제 시 DB/기존 코드 영향
- **부분 채택**: enum은 남기고 호출만 차단

## Related
- Commit: `6fce94e` (호출 차단), `e1def1c` (PERSONAL_EBIKE/KICKBOARD 분리)
- 파일:
  - `domain/.../route/MobilityType.java`
  - `application/.../strategy/OptimalSearchStrategy.java` (ALL_TYPES)
  - `infra/.../adapter/MobilityAvailabilityAdapter.java` (syntheticKickboard)
  - `frontend/src/components/search/MobilitySelector.jsx`
- Docs: `docs/plans/2026-03-10-next-steps.md`
- See also: ADR-003 (카셰어도 유사한 "외부 API 한계" 사례)
