# 프로젝트 설명 — Black Mamba 프로젝트 섹션

> 용도: 프로젝트 설명에 붙여쓸 수 있는 "프로젝트 경력" 텍스트
> 최종 수정: 2026-04-22

---

## 📄 풀 버전 (한 페이지 프로젝트 섹션용)

```
Black Mamba — AI 기반 MaaS 라우팅 엔진 (개인 프로젝트, 2025.11 ~ 2026.04)
github.com/OldTemple91/black-mamba

대중교통/공공자전거/개인 이동수단을 결합한 멀티모달 경로 엔진에
Spring AI 기반 RAG 파이프라인 + Reactor SSE 실시간 스트림 + Resilience4j
장애 대응 + 3축 관측성을 통합한 "설명 가능한 MaaS" 백엔드.

── 핵심 기술 스택 ──────────────────────────────
• Java 21, Spring Boot 3.5.13, Gradle 8.14 (4-module: domain/application/infra/api)
• Spring AI 1.0.2 + Ollama(llama3.2:3b + bge-m3) + Qdrant v1.13
• Resilience4j 2.2, Reactor, WebClient
• Prometheus + Grafana + Loki + Tempo (OTLP/gRPC)
• WireMock(HTTP 통합 테스트), JUnit 5

── 대표 구현 ─────────────────────────────────

• RAG 파이프라인 (Phase 1~6, 10+ 커밋)
  - 자연어 경로 검색 (/api/nlp/routes) — Ollama llama3.2:3b 의도 파싱
  - Qdrant 벡터 DB + bge-m3(1024차원) + 3축 하이브리드 검색
    (의미 + geohash + 이동수단 payload 필터)
  - LLM narrative 자동 생성 ("비슷한 이력 N건 중 M건이 이 경로")
  - 할루시네이션 감지 레이어 — 숫자 정합성 검증 후 원본 템플릿 폴백
    (실측 중 LLM 이 40분 경로를 4분으로 지어낸 것 자동 포착)
  - Prometheus 8종 메트릭 (saved/rejected/hallucination/similar_hit)

• 실시간 SSE 스트림 (A-1)
  - Reactor Flux 기반 `/api/routes/stream` (Spring MVC 유지)
  - 30초 재탐색 + 변화 감지 UPDATE push + HEARTBEAT + 5분 자동 종료
  - sealed interface + pattern matching 으로 이벤트 타입 안전성
  - Gauge/Counter 메트릭 + @Observed 자동 span

• 외부 API 장애 대응 (T-3)
  - 5개 외부 API(ODsay/TMAP/따릉이/네이버x2) 각각에 3층 방어선
    : Retry(지수 백오프) → CircuitBreaker(실패율 50% OPEN) → Fallback
  - Reactor Operator 방식 (vs @CircuitBreaker AOP) 로 Mono 체인 자연 결합
  - /actuator/circuitbreakers + Prometheus 메트릭 자동 노출

• 3축 관측성 (Logs ↔ Metrics ↔ Traces)
  - OTLP/gRPC + Protobuf 전환으로 페이로드 약 60% 감소
  - Exemplars 로 메트릭 스파이크 → 트레이스 드릴다운
  - 10+ @Observed 지점 (외부 API, RAG 검색, 경로 탐색, narrative 생성 등)

• 성능 튜닝
  - Geohash 공간 캐싱 (precision 7, 150m 격자) 도입
  - ODsay 히트율 46.9% → 80.4% (히트수 1.71배)
  - k6 부하 테스트 5종 (smoke/load/stress/spike/cache) p95 < 2s SLO

── 아키텍처 ─────────────────────────────────

• Clean Architecture + Hexagonal — Port/Adapter 엄격 분리
  : Qdrant → Milvus/pgvector / Ollama → OpenAI 교체 시 adapter 1개만 수정
• ADR 6건 문서화 (WebClient on MVC, Loki plain-text, Reactor Context
  Propagation, GlobalExceptionHandler 등 핵심 결정)
• 개선 기록 11건 (각 개선마다 배경→설계→구현→검증 문서)

── 성과 지표 ─────────────────────────────────
• 11개 개선 사이클 (RAG-1~6, A-1, T-2/T-3, F-1, C-3, B-3)
• 단위 테스트 기존 테스트 포함 전 모듈 통과
• Prometheus 20+ 메트릭 + Grafana 3개 drill-down 대시보드
• Docker Compose 원클릭 실행 (6개 컨테이너 통합)

배포: Docker Compose 기반 로컬 실행 (GitHub Actions CI + Jacoco)
```

---

## 📄 짧은 버전 (여러 프로젝트 나열 시 한 블럭용, ~15줄)

```
Black Mamba — AI 기반 MaaS 라우팅 엔진 (개인 프로젝트, 2025.11~2026.04)
github.com/OldTemple91/black-mamba

멀티모달 경로 엔진에 AI/RAG + 관측성 + 장애대응을 통합한 포트폴리오.
• Spring Boot 3.5 / Java 21 / Spring AI / Qdrant / Resilience4j
• RAG 6단계: 자연어 경로 검색, bge-m3(1024차원) 벡터 DB, 3축 하이브리드
  검색, LLM narrative 생성 + 할루시네이션 감지 (실측 포착)
• SSE 실시간 재탐색 (Reactor Flux) — 30초 주기 변화 push
• 외부 API 5개 Resilience4j 3층 방어 (Retry/CircuitBreaker/Fallback)
• 3축 관측성 (Loki/Tempo/Prometheus + OTLP/gRPC + Exemplars)
• Clean/Hexagonal 4-module 아키텍처 — 교체 가능한 adapter 설계
• Geohash 공간 캐싱으로 ODsay 히트율 46.9%→80.4%
• 개선 기록 11건 + ADR 6건 문서화
```

---

## 📎 참고

- 프로젝트 날짜는 실제 작업 시작일에 맞춰 수정하세요 (예: `2025.11 ~ 2026.04`)
- 본업 경력의 기술(DB/트랜잭션/인증 등)은 별도 프로젝트 설명 섹션에 기재해
  이 프로젝트의 "약점 영역" 을 보완
- 지원 회사별 맞춤이 필요하면 `docs/resume/` 에 별도 파일로 관리
  (예: `project-section-(자동차 제조사).md` 등)
