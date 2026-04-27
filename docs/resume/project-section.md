# 프로젝트 설명 — Black Mamba 프로젝트 섹션

> 용도: 프로젝트 설명에 붙여쓸 수 있는 "프로젝트 경력" 텍스트
> 최종 수정: 2026-04-24

---

## 📄 풀 버전 (한 페이지 프로젝트 섹션용)

```
Black Mamba — 설명 가능한 MaaS 라우팅 엔진 (개인 프로젝트, 2025.11 ~ 2026.04)
github.com/OldTemple91/black-mamba

대중교통/공공자전거/개인 이동수단을 결합한 멀티모달 경로 엔진에
Spring AI 기반 RAG 파이프라인 + Reactor SSE 실시간 스트림 + Resilience4j
장애 대응 + 4축 관측성(Logs↔Metrics↔Traces↔Alerts/SLO)을 통합한
"자가용 대체 가능한 경로" 백엔드.

── 핵심 기술 스택 ──────────────────────────────
• Java 21, Spring Boot 3.5.13, Gradle 8.14 (4-module: domain/application/infra/api)
• Spring AI 1.0.2 + Ollama(llama3.2:3b + bge-m3) + Qdrant v1.13
• Resilience4j 2.2, Reactor, WebClient
• Prometheus + Grafana + Loki + Tempo + Alertmanager (OTLP/gRPC)
• React 19 + Vite 7 + TailwindCSS v4 (Dark Mode, Glassmorphism)
• WireMock(HTTP 통합 테스트), JUnit 5, Playwright(E2E·스크린샷·비디오)

── 자체 설계한 핵심 알고리즘 ──────────────────────

L1 (도로 그래프 최단경로) 은 ODsay/Tmap 외부 엔진이 담당. 그 위에서
다중 이동수단을 재조합·평가·설명하는 Orchestration 층(L2)을 자체 설계.

• Baseline-Guided Multimodal Recomposition (ADR-007)
  - 대중교통 baseline 을 설계도 삼아 5패턴 (A/B/C/D/E) 병렬 생성
  - 자체 OSM 운영·전수 탐색·ML 재랭킹 등 대안과 비교 후 채택
• 6-Dimensional Weighted Scoring + 7-factor Reliability Penalty
  - time / transfer / cost / walk / accessWalk / reliability × 2 프로파일
• Two-Phase Hub Selection (Primary 이상 조건 → Fallback 60% 완화)
• 30~80% Candidate Window + 120m 중복 제거 정류장 후보 추출
• Two-Phase Walking (Haversine 필터 → Tmap 정밀)
• Geohash Spatial Cache (precision 7, 150m 격자)
• Post-Process Pattern (Accessibility / Weather / Carbon 3회 재사용)
• SSE Change Detection (30초 폴링 + 2분 임계값 push)

── 대표 구현 ─────────────────────────────────

• RAG 파이프라인 (Phase 1~6)
  - 자연어 경로 검색 (/api/nlp/routes) — Ollama llama3.2:3b 의도 파싱
  - "LLM 은 의도 파싱만, 경로 계산은 결정론적" 설계 분리
  - Qdrant 벡터 DB + bge-m3(1024차원) + 의미·공간(geohash)·이동수단
    payload 필터 3축 하이브리드 검색
  - LLM narrative 자동 생성 (R+A+G 파이프라인)
  - 할루시네이션 감지 레이어 — 숫자 정합성 검증 후 원본 폴백
    (실측 중 LLM 이 40분 경로를 4분으로 지어낸 것 자동 포착·증명)
  - Prometheus 8종 메트릭 (saved/rejected/hallucination/similar_hit)

• MaaS 정체성 (Post-Process 패턴 4종)
  - F-1 자가용 대비 비교 (시간/비용/CO₂ 3축 narrative)
  - C-2 Carbon Footprint (이동수단별 정밀 계수: 지하철 41 / 버스 68 /
    공유킥보드 22 / 전기자전거 10 g/km)
  - A-4 Weather-aware Routing (RAIN × 0.85, SNOW × 0.70 페널티)
  - C-3 Accessibility (휠체어 엘리베이터 역 필터 + 보행속도 재계산)

• 실시간 SSE 스트림 (A-1)
  - Reactor Flux 기반 /api/routes/stream (Spring MVC 유지하며 스트리밍)
  - 30초 재탐색 + 2분 임계값 변화 push + HEARTBEAT + 5분 자동 종료
  - sealed interface + pattern matching 으로 이벤트 타입 안전성

• 외부 API 장애 대응 (T-3 + T-7 + T-8)
  - 5개 외부 API 각각에 3층 방어선 (Retry → CircuitBreaker → Fallback)
  - T-7 TAGO 런타임 킬 스위치 (env 토글, 응답 500ms → <10ms)
  - T-8 RAG 킬 스위치 (@ConditionalOnBean + SPRING_AUTOCONFIGURE_EXCLUDE)
    → CI 에서 실제 사용해 Ollama/Qdrant 부재 시에도 앱 3초 기동 증명

• 4축 관측성 (M-1 + M-2 + M-3)
  - Logs(Loki) ↔ Metrics(Prometheus) ↔ Traces(Tempo) + Alerts/SLO
  - traceId 한 키로 3축 통합. Exemplars 로 p95 스파이크 → Tempo 점프
  - SLO Recording Rule + Burn Rate (Google SRE Workbook 다중 윈도우 패턴
    : Fast 1h × 14.4, Slow 6h × 6) → Alertmanager → Discord
  - cAdvisor + Node Exporter 호스트/컨테이너 메트릭

• 평가 방법론 (A-5 현실 시나리오 벤치마크)
  - 기존 평가의 "역 ↔ 역 좌표 편향" 을 스스로 발견·재정의
  - OD 30쌍 자동 배치(아파트/오피스/카페/공원)
  - 결과: Mixed 채택률 43%, 평균 3.4분 단축, 최대 8분 단축
    (서초아파트 → 성수 카페거리), 아파트 출발 70% Mixed 승리

── 성능 튜닝 ─────────────────────────────────
• Geohash 공간 캐싱 (precision 7, 150m 격자)
  ODsay 히트율 46.9% → 80.4% (히트수 1.71배)
• k6 부하 테스트 5종(smoke/load/stress/spike/cache) p95 < 2s SLO 충족

── 프론트엔드 ────────────────────────────────
• React 19 + Vite 7 + Tailwind v4 (@custom-variant dark 전략)
• 데스크톱 split layout(좌 검색 / 우 sticky 지도) + 모바일 vertical stack
• Citymapper 스타일 Route Timeline Bar (서울 지하철 15개 노선 공식색)
• Glassmorphism + Skeleton Loading + Hero CSS motion
  + prefers-reduced-motion 대응
• Playwright 자동 캡처 — 라이트/다크 × 데스크톱/모바일 = 8장 + 20초 데모 GIF

── 아키텍처 ─────────────────────────────────
• Clean Architecture + Hexagonal — Port/Adapter 엄격 분리
  : Qdrant → Milvus/pgvector / Ollama → OpenAI 교체 시 adapter 1개만 수정
• ADR 7건 문서화 (WebClient on MVC, Loki plain-text, Reactor Context
  Propagation, OTLP/gRPC, Baseline-Guided Recomposition 등)
• Core 개선기록 14건 (각 개선마다 배경→설계→구현→검증 문서)
• 자체 알고리즘 카탈로그 (의사코드 + 임계값 근거 + Design Non-Goals)

── 성과 지표 ─────────────────────────────────
• Core 14 + Supporting 9 = 총 23 개선 사이클 누적
• Prometheus 20+ 메트릭 + Grafana 4개 drill-down 대시보드
• 단위 테스트 88건, WireMock HTTP 통합 테스트
• Docker Compose 원클릭 실행 (10개 컨테이너 통합)
• GitHub Actions CI + Jacoco + Docker Build Verification

배포: Docker Compose 기반 로컬 실행
CI: GitHub Actions (Backend Test + Frontend Build + Docker Build Verification)
```

---

## 📄 짧은 버전 (여러 프로젝트 나열 시 한 블럭용, ~15줄)

```
Black Mamba — 설명 가능한 MaaS 라우팅 엔진 (개인 프로젝트, 2025.11~2026.04)
github.com/OldTemple91/black-mamba

멀티모달 경로 엔진에 AI/RAG + 4축 관측성 + 장애대응을 통합한 포트폴리오.
• Spring Boot 3.5 / Java 21 / Spring AI / Qdrant / Resilience4j
• RAG 1~6 단계: 자연어 검색, bge-m3 1024차원 벡터 DB, 3축 하이브리드
  검색, LLM narrative + 할루시네이션 감지 (실측 40분→4분 자동 폴백 포착)
• SSE 실시간 재탐색 (Reactor Flux) — 30초 주기 변화 push
• 외부 API 5개 Resilience4j 3층 방어 + 런타임 킬 스위치 2종 (T-7, T-8)
• 4축 관측성 (Loki/Tempo/Prometheus + Alertmanager + SLO Burn Rate)
• MaaS 정체성: 자가용 비교 + Carbon Footprint + 날씨 인식 + 접근성
  (모두 Post-Process 패턴으로 통일 — 탐색 알고리즘 비침투)
• Clean/Hexagonal 4-module 아키텍처 — 교체 가능한 adapter 설계
• Geohash 공간 캐싱으로 ODsay 히트율 46.9%→80.4%
• 자체 알고리즘 카탈로그 8종 + ADR 7건 + Core 개선기록 14건
```

---

## 📄 한 줄 (프로젝트 설명 한 칸용, ~150자)

```
Spring Boot + Spring AI + RAG(Ollama/Qdrant) + 4축 관측성 + Resilience4j 를
통합한 MaaS 라우팅 엔진. 자체 알고리즘 8종(ADR-007 Baseline-Guided
Recomposition), 런타임 킬 스위치, 할루시네이션 자동 폴백 등 설명 가능한
백엔드.
```

---

## 🎤 프로젝트 소개 단락 (300~400자)

```
Black Mamba 는 단순 "빠른 경로" 가 아닌 "자가용 대체 가능한 경로" 를
설명 가능하게 추천하는 MaaS 라우팅 엔진입니다. ODsay/Tmap 같은 외부
엔진이 처리하는 도로 그래프 최단경로 위에서, 5패턴 병렬 재조합과
6차원 가중 스코어링을 자체 설계했습니다. AI 측면에서는 Spring AI
+Qdrant 로 RAG 1~6 단계를 구축해 LLM 의 환각을 자동 감지·폴백하는
방어 레이어까지 만들었고(실측 중 40분 경로를 4분으로 지어낸 것을 자동
포착), 운영 측면에서는 Logs/Metrics/Traces 3축에 Alertmanager 와 SLO
Burn Rate 를 더해 4축 관측성을 구성했습니다. 외부 의존성은 언제든
끌 수 있어야 한다는 원칙으로 TAGO·RAG 두 종류의 런타임 킬 스위치를
설계했고, 그중 RAG 킬 스위치는 CI 에서 실제 사용되어 작동을 증명했습니다.
모든 변경에는 Before/After 정량 지표를 기록했고, 알고리즘 설계 결정은
ADR 로 공식화했습니다.
```

---

## 📎 참고

- 프로젝트 날짜는 실제 작업 시작일에 맞춰 수정 (예: `2025.11 ~ 2026.04`)
- 본업 경력의 기술(DB/트랜잭션/인증 등)은 별도 프로젝트 설명 섹션에 기재해
  이 프로젝트의 "약점 영역" 을 보완
- 지원 회사별 맞춤이 필요하면 `docs/resume/` 에 별도 파일로 관리
  (예: `project-section-(자동차 제조사).md` 등)
- 발표 질문 대비 1분 스토리: [Core 개선기록 14건](../improvements/README.md#-core-engineering-decisions)
