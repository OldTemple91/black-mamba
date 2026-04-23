# Architecture Decision Records (ADR)

> Black Mamba 프로젝트의 **중요한 설계 결정**을 기록합니다.
> 코드/커밋으로는 드러나지 않는 **"왜"** 와 **"고려했다가 포기한 대안"** 을 남깁니다.

---

## 📝 왜 ADR?

- 시간이 지나면 **"왜 그렇게 했는지"** 를 본인도 잊음
- 새로 합류한 팀원/발표관이 **설계 맥락**을 빠르게 이해
- **대안을 검토했다는 증거** → 엔지니어링 성숙도

---

## 📋 ADR 목록

| # | 제목 | 상태 | 날짜 |
|---|------|------|------|
| [001](./001-webclient-on-spring-mvc.md) | WebClient on Spring MVC (not WebFlux) | Accepted | 2026-03-04 |
| [002](./002-tago-kickboard-pivot-to-personal-pm.md) | TAGO 공유킥보드 포기 → Personal PM pivot | Accepted | 2026-03-10 |
| [003](./003-carshare-as-mobility-hub-not-type.md) | 카셰어존은 MobilityType이 아닌 MobilityHub | Accepted | 2026-03-10 |
| [004](./004-loki-plaintext-with-structured-metadata.md) | Loki 평문 1라인 + Structured Metadata 분리 | Accepted | 2026-04-17 |
| [005](./005-reactor-context-propagation-traceid.md) | Reactor Context Propagation 활성화 | Accepted | 2026-04-17 |
| [006](./006-otlp-grpc-over-http.md) | OTLP/gRPC over OTLP/HTTP (표준 전송) | Accepted | 2026-04-20 |
| [007](./007-baseline-guided-recomposition.md) | **Baseline-Guided Multimodal Recomposition** (A\*/Dijkstra 대신) | Accepted | 2026-04-23 |

---

## 📐 ADR 작성 템플릿

```markdown
# ADR-XXX: 짧은 제목

## Status
Accepted | Superseded by ADR-YYY | Deprecated | Proposed
(날짜)

## Context
- 어떤 문제가 있었나?
- 어떤 제약이 있었나?
- 배경 정리 (3~5문장)

## Decision
- 무엇을 선택했나?
- 핵심 변경점 (3~5문장)

## Consequences
**Pro:**
- 이 선택의 장점

**Con:**
- 이 선택이 만든 새로운 문제/부담

## Alternatives Considered
### 대안 A
- 특성
- 채택 안 한 이유

### 대안 B
- 특성
- 채택 안 한 이유

## Related
- Commits: abc123
- See also: ADR-YYY
- Docs: docs/improvements/...
```

---

## 🔖 상태 정의
- **Proposed**: 논의 중
- **Accepted**: 결정 완료 + 구현됨
- **Superseded**: 다른 ADR로 대체됨
- **Deprecated**: 폐기됨 (이유 설명 필수)
