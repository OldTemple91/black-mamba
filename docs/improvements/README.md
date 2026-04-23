# 개선 이력 (Improvements Log)

> Black Mamba 프로젝트의 리팩토링/고도화 작업 기록.
> 각 개선마다 **배경 → 구현 → 성과 → 배운 점**을 일관된 형식으로 남깁니다.

---

## 📁 파일 명명 규칙

```
YYYY-MM-DD-<카테고리>-<제목>.md

예시:
2026-04-18-B3-geohash-spatial-caching.md
2026-04-20-C1-ev-charging-integration.md
2026-04-22-M1-alertmanager-discord.md
```

**카테고리 약어:**
- `A` 기능 / `B` 아키텍처 / `C` 모빌리티 / `D` 확장성
- `E` ML·AI / `F` MaaS 정체성 / `M` 모니터링 / `RAG` 생성형 AI · 검색 / `T` 테스트·설계

---

## 📝 글 작성 템플릿

각 개선 문서는 아래 섹션을 포함합니다.
실제 작업 전·후의 **"무엇을 why로 바꿨고, 수치로 얼마나 개선됐는지"** 를 남깁니다.

```markdown
# <카테고리>-<번호>: <제목>

> 작업일: YYYY-MM-DD ~ YYYY-MM-DD
> 담당 Phase: ROADMAP.md #<번호>
> 공수: 실측 X시간
> 커밋: abc123 ~ def456

## 1. 배경 (Why)
- 현재 무엇이 문제였나?
- 정량적 지표로 표현 (p95 = X초, 히트율 Y%, ...)

## 2. 기존 구조 (Before)
- 아키텍처 다이어그램 / 코드 스니펫
- 한계가 어떤 식으로 드러났는지

## 3. 개선 방향 (How)
- 설계 결정 근거
- 대안 후보와 트레이드오프
- 최종 채택 이유

## 4. 구현 (What)
### 4-1. 변경된 파일
### 4-2. 핵심 코드 변경점
### 4-3. 테스트 추가/수정

## 5. 검증 & 성과 (Result)
### Before vs After 지표
| 지표 | Before | After | 개선율 |
|------|--------|-------|-------|
| p95 응답시간 | ... | ... | ... |

### 측정 방법
- 어떤 쿼리/스크립트로 측정했나?

## 6. 사이드 이펙트 & 한계
- 예상 못 한 문제
- 남은 개선 여지

## 7. 발표 스토리텔링
- 이 개선을 1분 안에 설명할 수 있는 버전
```

---

## 📚 개선 이력 목록

| # | 날짜 | 카테고리 | 제목 | 성과 요약 |
|---|------|---------|------|----------|
| 1 | 2026-04-17 | B-3 | [Geohash 공간 인덱스 캐시](./2026-04-17-B3-geohash-spatial-caching.md) | ODsay 히트율 **46.9% → 80.4%** (1.71배) |
| 2 | 2026-04-17 | OTel | [Zipkin/JSON → OTLP/Protobuf 트레이스 전송](./2026-04-17-C-otlp-protobuf-tracing.md) | OpenTelemetry 표준 준수 + Protobuf 직렬화 전환 |
| 3 | 2026-04-20 | T-4 Phase 1 | [Spring Boot 3.5.13 + OTLP/gRPC 완전 전환](./2026-04-20-T4-phase1-springboot-3.5-otlp-grpc.md) | OSS 지원 유지 + gRPC transport 공식 활성화 + Gradle 8.14 |
| 4 | 2026-04-20 | F-1 | [vs 자가용 비교 응답 (MaaS 정체성 전환)](./2026-04-20-F1-vs-car-comparison.md) | 경로별 시간/비용/탄소 3축 자가용 비교 + narrative 생성 |
| 5 | 2026-04-20 | C-3 | [Accessibility 경로 (포용성)](./2026-04-20-C3-accessibility.md) | 휠체어/노인 옵션 + Post-Process 패턴 도입 |
| 6 | 2026-04-20 | RAG-1 | [자연어 경로 검색 (Ollama + Spring AI)](./2026-04-20-RAG1-nlp-route-search.md) | LLM 의도 파싱 + 기존 엔진 결합, 자연어 진입점 추가 |
| 7 | 2026-04-22 | RAG-2 | [Qdrant 벡터 DB + 유사 경로 검색](./2026-04-22-RAG2-qdrant-similar-routes.md) | bge-m3 임베딩(1024차원) + 의미/공간/이동수단 **3축 하이브리드 검색** + @Observed 관측성 통합 |
| 8 | 2026-04-22 | RAG-4 | [LLM narrative 생성 (RAG 시리즈 완결)](./2026-04-22-RAG4-llm-narrative.md) | `/api/routes` 추천 경로에 **Retrieval + Augmented + Generation** 자동 적용, 블랙박스 추천 → 설명 가능한 MaaS |
| 9 | 2026-04-22 | RAG-5 | [RAG 품질 보강 (데이터 게이트/서술 다양화/할루시네이션 감지)](./2026-04-22-RAG5-quality-reinforcement.md) | 3가지 방어선 추가 — **실측 중 LLM 이 40분 경로를 4분으로 지어낸 것을 자동 포착해 폴백** 동작 증명 |
| 10 | 2026-04-22 | RAG-6 | [데이터 규모 확장 + 운영 메트릭](./2026-04-22-RAG6-data-scale-and-metrics.md) | 시드 20 → **200건** (OD×시간대×선호도 3차원), 유사도 score 0.56 → **0.72**, Prometheus 메트릭 8종 노출 |
| 11 | 2026-04-22 | A-1 | [경로 탐색 실시간 SSE 스트림](./2026-04-22-A1-sse-route-stream.md) | Reactor Flux 기반 SSE — 30초 재탐색 + 변화 감지 push + HEARTBEAT + 자동 종료 (5m). Spring MVC 유지하며 스트리밍 |
| 12 | 2026-04-23 | A-5 | [현실 시나리오 벤치마크 (역 편향 제거)](./2026-04-23-A5-real-user-benchmark.md) | OD 30쌍 자동 배치 — **Mixed 채택률 43%, 평균 3.4분 단축, 최대 8분.** 아파트 출발 70% Mixed 승리 |
| 13 | 2026-04-23 | A-6 | [장소 자동완성 POI+주소 2단계 폴백](./2026-04-23-A6-place-autocomplete-fallback.md) | `/api/places` 에 Geocoding 폴백 체인 추가 — 프론트 수정 0줄, 자동완성 UX 가 결과 페이지와 일관 |
| 14 | 2026-04-23 | T-7 | [TAGO API 런타임 킬 스위치](./2026-04-23-T7-tago-kill-switch.md) | 서울 미제공 엔드포인트 외부 호출 스킵 — 응답 500ms → **<10ms**, 로그 노이즈 제거, 재개 시 env 한 줄로 복구 |
| 15 | 2026-04-23 | M-1/M-2/M-3 | [관측성 4축 완성 (Alertmanager + SLO + 호스트 메트릭)](./2026-04-23-M1-M2-M3-alerts-slo.md) | 3축(Logs/Metrics/Traces) → **4축(+Alerts, +SLO)**. Alert 7 + Recording 9 룰 + Burn Rate 다중 윈도우 + cAdvisor/Node Exporter |

---

## 🔗 관련 문서

- [ROADMAP.md](../roadmap/ROADMAP.md) — 전체 개선 계획
- [observability-stack.md](../monitoring/observability-stack.md) — 모니터링 인프라
