# 운영 마감 Polish Pack (M-4/M-5/M-6 + ADR-007 + 프론트 렌더링)

> 작업일: 2026-04-23
> 담당 Phase: ROADMAP.md M-4, M-5, M-6 + ADR 신설 + 프론트 통합
> 공수: 실측 약 2시간
> 커밋: TBD

---

## 1. 배경 (Why)

Tier 1(관측성 4축) + Tier 2(Carbon/Weather) 까지 끝낸 상태에서 **프로젝트 완성도** 를 올리는 3종 세트를 한 번에 정리.

| 구멍 | 왜 메꿔야 하는가 |
|------|---------------|
| 알고리즘 설계 결정이 ADR 에 없음 | 새 기여자가 "왜 A*/Dijkstra 를 안 썼나" 물어봤을 때 문서 근거가 없음 |
| 운영 환경에서도 DEBUG 로그 | Loki 스토리지 무의미한 노이즈 |
| Grafana 익명 = Admin | 누구나 대시보드 수정/삭제 가능 |
| Tempo/Loki 보존 정책 미설정 | 재기동 시 트레이스 전부 날아감 / 로그 무한 누적 |
| 백엔드에 있는 carbon/weather 필드를 프론트가 무시 | "MaaS 정체성" 이 화면에 안 보임 |

---

## 2. 구현 (What)

### 2-1. ADR-007 Baseline-Guided Multimodal Recomposition

`docs/adr/007-baseline-guided-recomposition.md` 신설.

설계 결정 3가지 공식화:
- Recomposition 채택 vs Graph Search (A\*/Dijkstra) / Brute Force / Single Pattern / ML 기반 비교
- 각 대안의 장단점 + 채택 안 한 이유 명시
- Related 에 알고리즘 카탈로그 + 개선기록 링크

**설명 질문 직격**: "왜 표준 알고리즘 안 썼어요?" → ADR-007 을 보여준다.

### 2-2. M-4 — 로그 레벨 프로파일 분리

`logback-spring.xml` 에서 `com.blackmamba.navigation` 로거가 **전 프로파일 DEBUG** 였던 것을 분리:

```xml
<!-- local 프로파일: DEBUG 유지 (개발 중 상세 추적) -->
<springProfile name="local">
    <logger name="com.blackmamba.navigation" level="DEBUG"/>
</springProfile>
<!-- docker/prod 프로파일: INFO (Loki 스토리지 절감 + 노이즈 감소) -->
<springProfile name="docker,prod">
    <logger name="com.blackmamba.navigation" level="INFO"/>
</springProfile>
```

Docker 환경에서 일시적으로 DEBUG 가 필요하면 `LOGGING_LEVEL_COM_BLACKMAMBA_NAVIGATION=DEBUG` 환경변수로 override 가능.

### 2-3. M-5 — Grafana 익명 Viewer 제한

`docker-compose.yml`:
```yaml
# Before
- GF_AUTH_ANONYMOUS_ORG_ROLE=Admin

# After
- GF_AUTH_ANONYMOUS_ORG_ROLE=Viewer
- GF_SECURITY_ADMIN_PASSWORD=${GRAFANA_ADMIN_PASSWORD:-admin}
```

**검증**:
```bash
curl -X POST http://localhost:3000/api/dashboards/db \
  -H "Content-Type: application/json" \
  -d '{"dashboard":{"title":"test"}}'
# Response:
# 403 "You'll need additional permissions: dashboards:create"
```

이전엔 익명이 Admin 권한으로 대시보드 수정 가능했음.

### 2-4. M-6 — Tempo 볼륨 + 보존 정책

**Tempo**:
```yaml
compactor:
  compaction:
    block_retention: 24h   # 1h → 24h
    compacted_block_retention: 1h

storage:
  trace:
    wal:   { path: /var/tempo/wal }     # /tmp → /var/tempo
    local: { path: /var/tempo/blocks }

# docker-compose.yml
tempo:
  volumes:
    - tempo-data:/var/tempo   # 볼륨 영속화 추가
```

**Loki**:
```yaml
limits_config:
  retention_period: 168h   # 7일 명시

compactor:
  working_directory: /loki/compactor
  retention_enabled: true
  retention_delete_delay: 2h

# docker-compose.yml
loki:
  volumes:
    - loki-data:/loki   # 볼륨 영속화 추가
```

### 2-5. 프론트 Carbon 배지 + Weather 옵션 UI

**RouteCard.jsx** — carbon 배지 추가:
```jsx
{route.carbon && (
  <div className="mt-3 flex flex-wrap items-center gap-2">
    <span className={eco ? 'green-badge' : 'slate-badge'}>
      🌱 {grams}g CO₂
    </span>
    {route.carbon.eco && <span>🌿 친환경 경로</span>}
    {savedVsCarGrams > 100 && <span>자가용 대비 −{saved}g 감축</span>}
  </div>
)}
```

**MainPage.jsx** — 날씨 옵션 5개 버튼:
```jsx
{[
  { key: '',     emoji: '—',   label: '기본' },
  { key: 'RAIN', emoji: '🌧',  label: '비'   },
  { key: 'SNOW', emoji: '❄️', label: '눈'   },
  { key: 'HEAT', emoji: '☀️', label: '폭염' },
  { key: 'COLD', emoji: '🥶', label: '혹한' },
].map(opt => <button ...>{opt.emoji} {opt.label}</button>)}
```

**routeApi.js / RouteListPage.jsx** — weather 파라미터가 URL 쿼리를 통해 백엔드까지 전달되도록 흐름 완성.

---

## 3. 검증 & 성과 (Result)

### 3-1. Playwright 스크린샷 4종 재생성

- `output/playwright/main-page.png` — 날씨 옵션 5개 버튼 노출 ✓
- `output/playwright/routes-page.png` — 경로 카드에 🌱 CO₂ 배지 노출 ✓
- `output/playwright/routes-clear.png` — CLEAR 기준 (탑 1위: TRANSIT_WITH_BIKE 28분)
- `output/playwright/routes-rain.png` — RAIN 시나리오 (탑 2위로 TRANSIT_ONLY 진입 — 비로 자전거 감점)

### 3-2. 관측성 스택 검증

```
Tempo:       http://localhost:3200/ready   → ready
Loki:        http://localhost:3100/ready   → ready
Grafana 익명: 403 on /api/dashboards/db     → Viewer 확정
```

### 3-3. ADR-007 링크 체인

- `docs/adr/README.md` ADR 목록에 추가 (6건 → 7건)
- `docs/architecture/routing-algorithm.md` 와 상호 참조
- 5개 개선기록 (B-3, C-3, A-4, A-5) 을 ADR 의 Related 에 연결

---

## 4. 사이드 이펙트 & 한계

### 4-1. Docker 프로파일로 전환 시 DEBUG 로그 보고 싶을 때
환경변수로 override 가능하지만, 초심자는 모를 수 있음. README 에 "디버그 임시 활성화" 섹션 필요.

### 4-2. Grafana 익명 Viewer 도 전체 대시보드 조회 가능
운영 배포 시엔 `GF_AUTH_ANONYMOUS_ENABLED=false` 로 완전 차단 권장. 현 설정은 프로젝트/데모 편의 우선.

### 4-3. Loki 보존 7일 / Tempo 24h 는 스토리지 vs 분석 범위 트레이드오프
장기 분석 (월별 추이) 은 불가 → S3 등 장기 스토리지 백엔드 연동 필요 (운영 단계).

### 4-4. 프론트 Carbon 배지는 텍스트 기반
바 그래프나 아이콘 기반이면 더 직관적. 현재는 "부가 정보" 성격이라 미니멀 디자인.

---

## 5. 기록

> "Tier 1·2 로 관측성 4축과 MaaS 정체성을 갖춘 후, **프로젝트 완성도를 결정짓는 세 구멍** 을 한 번에 메웠습니다.
>
> 하나 — **ADR-007** 로 'A\*/Dijkstra 를 왜 안 썼나' 를 공식 문서로 답변. 대안 4개를 비교한 Decision Record 는 '생각한 걸 남기는 엔지니어' 라는 시그널입니다.
>
> 둘 — **M-4/5/6 운영 마감** 으로 Loki 노이즈 / Grafana 권한 / Tempo·Loki 재기동 데이터 소실을 한 번에 정리. 평소 간과되는 디테일이 설명에서 '이 사람 운영 안 해본 게 아니구나' 를 증명합니다.
>
> 셋 — **프론트 Carbon 배지 + Weather 옵션 UI** — 백엔드에서 계산한 CO₂ 와 날씨 페널티가 **실제 화면에 보이는 가치** 로 변환. 스크린샷 한 장만 봐도 MaaS 메시지가 전달됩니다."

---

## 6. 관련 문서
- [ADR-007 Baseline-Guided Recomposition](../adr/007-baseline-guided-recomposition.md)
- [M-1/M-2/M-3 관측성 4축](./2026-04-23-M1-M2-M3-alerts-slo.md) — 확장 전 상태
- [C-2 Carbon Footprint](./2026-04-23-C2-carbon-footprint.md)
- [A-4 Weather-aware Routing](./2026-04-23-A4-weather-aware-routing.md)
- `frontend/src/components/route/RouteCard.jsx` — 탄소 배지
- `frontend/src/pages/MainPage.jsx` — 날씨 옵션
- `scripts/screenshots/capture-rain-scenario.mjs` — A-4 시각 증거 캡처
