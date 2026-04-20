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
- `E` ML·AI / `M` 모니터링 / `T` 테스트·설계

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

---

## 🔗 관련 문서

- [ROADMAP.md](../roadmap/ROADMAP.md) — 전체 개선 계획
- [observability-stack.md](../monitoring/observability-stack.md) — 모니터링 인프라
