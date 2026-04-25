# 개선 이력 (Improvements Log)

> Black Mamba 프로젝트의 설계·개선 기록.
> **Core** 는 설명에서 1분 안에 설명할 수 있는 기술적 의사결정.
> **Supporting** 은 UI/UX · 문서화 · 자동화 등 완성도 보강 작업.

---

## ⭐ Core Engineering Decisions

실제 **설계 원리 · 대안 비교 · 정량 개선** 이 담긴 14건.

| # | 분류 | 제목 | 핵심 성과 |
|---|-----|------|----------|
| 1 | 성능 | [B-3 Geohash 공간 캐싱](./2026-04-17-B3-geohash-spatial-caching.md) | 좌표를 150m 격자로 양자화 → ODsay 히트율 **46.9% → 80.4%** (1.71×) |
| 2 | 도메인 | [F-1 vs 자가용 비교](./2026-04-20-F1-vs-car-comparison.md) | "빠른 경로" → **"자가용 대체"** 로 MaaS 정체성 전환 (시간/비용/탄소 3축) |
| 3 | 설계 패턴 | [C-3 Accessibility 경로](./2026-04-20-C3-accessibility.md) | **Post-Process 패턴** 도입 — 탐색 로직 비침투 옵션 확장 |
| 4 | AI | [RAG-1 자연어 경로 검색](./2026-04-20-RAG1-nlp-route-search.md) | "LLM 은 의도 파싱만, 경로 계산은 결정론적" 설계 — 설명 가능성 + 테스트 용이성 |
| 5 | AI | [RAG-2 Qdrant 하이브리드 검색](./2026-04-22-RAG2-qdrant-similar-routes.md) | bge-m3 1024차원 + **의미·공간·이동수단 3축 하이브리드** 필터 |
| 6 | AI | [RAG-4 LLM narrative (R+A+G 완결)](./2026-04-22-RAG4-llm-narrative.md) | `/api/routes` 추천 경로에 Retrieval + Augmented + Generation 자동 적용 |
| 7 | AI | [RAG-5 할루시네이션 감지](./2026-04-22-RAG5-quality-reinforcement.md) | 실측에서 **LLM 이 40분 경로를 4분으로 지어낸 것을 자동 포착 · 폴백** 증명 |
| 8 | 실시간 | [A-1 SSE 스트림](./2026-04-22-A1-sse-route-stream.md) | Reactor Flux — 30초 재탐색 + 2분 임계값 변화 push + HEARTBEAT + 5분 자동 종료 |
| 9 | 방법론 | [A-5 역 편향 제거 벤치마크](./2026-04-23-A5-real-user-benchmark.md) | OD 30쌍 현실 시나리오 — **Mixed 채택률 43%, 평균 3.4분 / 최대 8분 단축** (아파트 출발 70% 승리) |
| 10 | 운영 | [T-7 TAGO 런타임 킬 스위치](./2026-04-23-T7-tago-kill-switch.md) | 외부 API 장애/미제공 회피책 — 응답 500ms → **<10ms**, `TAGO_ENABLED` env 한 줄로 복구 |
| 11 | 관측성 | [M-1/M-2/M-3 관측성 4축](./2026-04-23-M1-M2-M3-alerts-slo.md) | Logs/Metrics/Traces → **+Alerts +SLO**. Burn Rate 다중 윈도우 (Google SRE Workbook 패턴) |
| 12 | 도메인 | [C-2 Carbon Footprint](./2026-04-23-C2-carbon-footprint.md) | 이동수단별 정밀 계수 (지하철 41 / 버스 68 / 공유킥보드 22 g/km) + Prometheus 히스토그램 |
| 13 | 설계 패턴 | [A-4 Weather-aware Routing](./2026-04-23-A4-weather-aware-routing.md) | C-3 **후처리 패턴 재사용 증명** — RAIN × 0.85, SNOW × 0.70, 실측 계수 정확 적용 |
| 14 | 운영 | [T-8 RAG 킬 스위치 + Graceful Degradation](./2026-04-23-T8-rag-kill-switch.md) | `@ConditionalOnBean` + `SPRING_AUTOCONFIGURE_EXCLUDE` — Qdrant/Ollama 장애 시 3초 기동, 핵심 라우팅 정상, RAG 만 비활성 |

**관련 ADR**: [ADR-007 Baseline-Guided Multimodal Recomposition](../adr/007-baseline-guided-recomposition.md) — 알고리즘 설계 결정을 공식 기록으로.

---

## 📋 Supporting Work

완성도 · 통신력 · UX 보강 작업. 히스토리 보존용.

| 분류 | 작업 | 기록 |
|-----|-----|-----|
| 관측성 | OTLP/Protobuf 트레이스 전송 표준화 | [2026-04-17](./2026-04-17-C-otlp-protobuf-tracing.md) |
| 업그레이드 | Spring Boot 3.5.13 + OTLP/gRPC + Gradle 8.14 | [2026-04-20 T-4](./2026-04-20-T4-phase1-springboot-3.5-otlp-grpc.md) |
| RAG 데이터 | 시드 20 → 200건 (3축) + Prometheus 메트릭 | [2026-04-22 RAG-6](./2026-04-22-RAG6-data-scale-and-metrics.md) |
| UX | 장소 자동완성 POI + 주소 2단계 폴백 | [2026-04-23 A-6](./2026-04-23-A6-place-autocomplete-fallback.md) |
| 운영 마감 | 로그레벨 / Grafana 보안 / Tempo·Loki 보존 정책 | [2026-04-23 Polish Pack](./2026-04-23-polish-pack.md) |
| UI/UX | Split layout + Glassmorphism + Hero motion | [2026-04-23 Polish 1~2차](./2026-04-23-frontend-polish.md) |
| UI/UX | Route Timeline Bar + Dark Mode + Skeleton | [2026-04-23 Advanced](./2026-04-23-frontend-advanced.md) |
| UI/UX | 지도 TRANSIT passThroughStations 연결 + 다크 전수 완결 | [2026-04-23 Audit](./2026-04-23-frontend-audit.md) |
| 통신력 | Mermaid 다이어그램 + 모바일 뷰 + 20초 데모 GIF | [2026-04-23 Tier S](./2026-04-23-tier-s-communication.md) |

---

## 📐 작성 템플릿

각 개선기록은 **배경(Why) → 기존 구조(Before) → 개선 방향(How) → 구현(What) → 검증/성과(Result) → 한계 → 기록** 의 일관된 형식.

핵심은 **"무엇을 why 로 바꿨고, 수치로 얼마나 개선됐는지"** — 단순 "기능 추가함" 이 아닌 **의사결정의 근거**.

---

## 🔗 관련 문서

- [ROADMAP.md](../roadmap/ROADMAP.md) — 전체 개선 계획
- [ADR 7건](../adr/) — 설계 결정 공식 기록
- [자체 알고리즘 카탈로그](../architecture/routing-algorithm.md) — Orchestration 8종 의사코드
- [observability-stack.md](../monitoring/observability-stack.md) — 모니터링 인프라
