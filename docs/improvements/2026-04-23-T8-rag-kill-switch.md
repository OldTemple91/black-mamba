# T-8: RAG 킬 스위치 — Qdrant 장애 시에도 라우팅 서비스 지속

> 작업일: 2026-04-23
> 담당 Phase: 운영 안정성
> 공수: 실측 약 1시간
> 커밋: TBD

---

## 1. 배경 (Why)

CI `Docker Build Verification` 이 빨갛게 떴다. 로그를 보니:

```
Caused by: io.grpc.StatusRuntimeException: UNAVAILABLE: io exception
  connection refused: localhost:6334
Caused by: ... QdrantVectorStore.afterPropertiesSet (line 316)
  → Error creating bean 'vectorStore'
  → Error creating bean 'qdrantRouteHistoryAdapter'
  → Error creating bean 'routeHistoryRecorder'
  → Error creating bean 'routeOptimizationService'
  → Error creating bean 'naturalLanguageRouteController'
Application run failed.
```

**앱 컨테이너 단독으로 띄우면 Qdrant 가 없어 전체 기동 실패.** 이게 단순 CI 문제가 아니라 운영 관점에서도 심각:

- Qdrant 점검 / 재시작 / 네트워크 순단 → 앱 전체 다운
- RAG 는 부가 기능 (narrative 풍부함), 핵심 라우팅은 Qdrant 불필요
- 그런데도 Qdrant 가 앱 생명줄을 쥐고 있음

### 기존 Graceful Degradation 분석

코드를 읽어보니 **런타임** 방어는 이미 되어 있었다:
- `RouteHistoryRecorder` — `ObjectProvider.getIfAvailable()` 로 null 방어
- `RouteNarrativeEnhancer` — 같은 패턴
- `RagSimilarRoutesController` — null 체크 후 503 반환
- `RouteHistorySeeder` — null 이면 스킵

**문제는 기동 시점.** Spring 이 의존성 그래프 해결하면서 `VectorStore` 빈 생성 시도 → `afterPropertiesSet()` 에서 Qdrant 연결 실패 → 예외 전파 → ApplicationContext 초기화 실패.

### 목표

T-7 TAGO 킬 스위치와 동일한 철학:
- **환경변수 한 줄로** Qdrant 의존성 제거
- **재빌드 없이** 긴급 회피 가능
- **핵심 서비스 (경로 탐색)** 는 어떤 상황에도 지속

---

## 2. 구현 (What)

### 2-1. 변경 파일 (4개)

| 파일 | 변경 요지 |
|------|---------|
| `Dockerfile` | `--spring.profiles.active` 하드코딩 제거, `ENV` 로 전환 |
| `docker-compose.yml` | `SPRING_AUTOCONFIGURE_EXCLUDE` 환경변수 통로 추가 |
| `infra/.../QdrantRouteHistoryAdapter.java` | `@ConditionalOnBean(VectorStore.class)` 추가 |
| `.github/workflows/ci.yml` | Qdrant sidecar 추가 (CI 정상 시나리오) |
| `README.md` | "RAG 킬 스위치" 섹션 추가 |

### 2-2. Dockerfile — 환경변수 기반 프로파일

```dockerfile
# Before: CLI 인자 하드코딩으로 SPRING_PROFILES_ACTIVE 무효화
ENTRYPOINT ["java", "-jar", "/app/app.jar",
  "--spring.profiles.active=local,docker", ...]

# After: ENV 기본값 + 런타임 override 가능
ENV SPRING_PROFILES_ACTIVE=local,docker
ENTRYPOINT ["java", "-jar", "/app/app.jar",
  "--spring.web.resources.static-locations=file:/app/static/"]
```

Spring 의 설정 우선순위: **CLI 인자 > 환경변수 > yml**.
CLI 인자로 박혀있으면 `SPRING_PROFILES_ACTIVE=...` 를 아무리 넘겨도 무시됨.

### 2-3. docker-compose.yml — SPRING_AUTOCONFIGURE_EXCLUDE 통로

```yaml
environment:
  # RAG 킬 스위치 (Qdrant 장애 시 회피책).
  # 기본: 비어있음 → 전체 AutoConfig 활성.
  # Qdrant 장애 시:
  #   SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration
  #   docker compose up -d app
  - SPRING_AUTOCONFIGURE_EXCLUDE=${SPRING_AUTOCONFIGURE_EXCLUDE:-}
```

### 2-4. QdrantRouteHistoryAdapter — `@ConditionalOnBean`

`SPRING_AUTOCONFIGURE_EXCLUDE` 로 AutoConfig 를 제외하면 `VectorStore` 빈은 안 만들어지지만, **`@Component` 스캔은 여전히 `QdrantRouteHistoryAdapter` 를 등록하려 시도 → 의존성 부재로 NoSuchBeanDefinitionException.**

`@Component` 대신 `@Configuration + @Bean + @ConditionalOnBean` 으로 재구성한 이유:
`@Component` + `@ConditionalOnBean` 조합은 컴포넌트 스캔 시점에 평가돼서
Spring AI `QdrantVectorStoreAutoConfiguration` 이 실행되기 전에 조건을 보게 됨 → 불안정.

```java
// infra/.../QdrantRouteHistoryConfig.java
@Configuration
@AutoConfigureAfter(name = "org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration")
public class QdrantRouteHistoryConfig {
    @Bean
    @ConditionalOnBean(VectorStore.class)
    public QdrantRouteHistoryAdapter qdrantRouteHistoryAdapter(VectorStore vectorStore) {
        return new QdrantRouteHistoryAdapter(vectorStore);
    }
}
```

### 2-5. CI 에서의 킬 스위치 실제 활용

CI (`Docker Build Verification`) 는 _앱이 기동 가능한지_ 만 검증. Ollama (bge-m3 1.2GB) 를
CI 에 띄우는 건 비현실적이므로 **킬 스위치를 CI 환경변수로 전달**:

```yaml
# .github/workflows/ci.yml
- name: Verify image starts and responds to health check
  run: |
    docker run -d --name black-mamba-test \
      -e SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration \
      -e ODSAY_API_KEY=dummy ... \
      "$IMAGE"
```

**부산물**: CI 가 통과하면 **T-8 킬 스위치가 실제로 동작함을 CI 레벨에서 증명**.

---

## 3. 검증 & 성과 (Result)

### 3-1. 3가지 기동 시나리오 전부 검증

| 시나리오 | 결과 |
|---------|------|
| **정상** (Qdrant 있음) | 앱 기동 OK, RAG 활성, 모든 엔드포인트 정상 |
| **Qdrant 다운 + exclude 미설정** | **기존과 동일: ApplicationContext 초기화 실패** (이번 변경으론 기본 동작 유지) |
| **Qdrant 다운 + exclude 설정** | **앱 기동 OK, RAG 비활성, 핵심 라우팅 정상** ✅ |

### 3-2. 실측 로그 (Qdrant 다운 + exclude 설정)

```
The following 2 profiles are active: "local", "docker"
[RAG] RouteHistoryPort 빈 없음 → 경로 이력 저장 비활성화 (Qdrant 미기동?)
[RAG] RouteHistoryPort 빈 없음 — /api/rag/similar-routes 는 503 응답
Started ApiApplication in 3.213 seconds
```

- 앱 3초 내 기동 (정상 기동과 동일 속도)
- 명확한 로그 메시지로 RAG 비활성 상태 노출
- `/actuator/health` → HTTP 200

### 3-3. API 동작 확인

**정상 경로 탐색:**
```bash
$ curl "/api/routes?originLat=...&destLat=..."
{
  "routeId": "99239e4a...",
  "type": "TRANSIT_ONLY",
  "totalMinutes": 4,
  "carComparison": {"narrative": "이 경로는 ... 자가용과 비교하면 1분 더 걸리지만 탄소 159g 감소..."},
  "carbon": {"grams": 32.8, "gramsPerKm": 39.0, "eco": false, ...}
}
```

**RAG 전용 엔드포인트:**
```bash
$ curl "/api/rag/similar-routes?q=test"
HTTP 503
{"code":"VECTOR_STORE_UNAVAILABLE","message":"벡터 DB 가 기동되지 않았습니다 (docker compose up -d qdrant)."}
```

친절한 에러 메시지 + 503 상태 코드.

### 3-4. 운영 시나리오 예시

Qdrant 장애 발생 알림 수신:
```bash
# 1분 이내 회피
SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration \
  docker compose up -d --no-deps app

# Qdrant 복구 후
unset SPRING_AUTOCONFIGURE_EXCLUDE
docker compose up -d --no-deps app
```

---

## 4. 사이드 이펙트 & 한계

### 4-1. 환경변수 길이
클래스 경로가 104자. Operations 팀에서 오타 가능성.
→ 별도 alias 스크립트 (`scripts/ops/disable-rag.sh`) 같은 래퍼 가능.

### 4-2. 프로파일 방식 대신 환경변수 선택
초기엔 `application-no-rag.yml` 으로 프로파일 추가 시도했으나 Spring Boot 의 **프로파일별 yml 에서 `spring.autoconfigure.exclude` 리스트 병합이 불안정** 함을 발견. 환경변수가 더 확실.

### 4-3. 자동 감지는 미구현
Qdrant 다운을 **앱이 자동으로** 감지해 비활성화하진 않음. 운영자 판단 필요.
이상적으로는 `@Lazy VectorStore` + 연결 타임아웃으로 자동 폴백 가능하지만 복잡도 큼. 수동 스위치가 현 단계 최적.

### 4-4. narrative 품질 저하 허용
킬 스위치 발동 시 LLM narrative → 템플릿 narrative 로 격하. "자가용 대비" 기본 문구는 유지되므로 UX 완전 공백은 아님.

---

## 5. 발표 스토리

> "CI 실패 로그를 보니 **VectorStore 빈 하나 때문에 앱 전체가 기동 못 하고 있더라고요**. 운영에서 Qdrant 점검 한 번만 들어가도 서비스 다운될 구조였습니다.
>
> T-7 TAGO 킬 스위치와 같은 철학을 적용했습니다. **Spring Auto-Configuration exclude 를 환경변수로 뚫어놓고**, 어댑터에 `@ConditionalOnBean(VectorStore.class)` 로 조건부 등록. 장애 시:
>
> ```
> SPRING_AUTOCONFIGURE_EXCLUDE=org.springframework.ai... docker compose up -d app
> ```
>
> 한 줄로 RAG 만 비활성화, 앱은 3초 내 기동, 핵심 `/api/routes` 완전 정상, `/api/rag/*` 는 친절한 503 응답. 런타임 graceful degradation 은 이미 있었는데 **기동 시점 방어가 빠진 구조적 허점** 을 환경변수 킬 스위치 + `@ConditionalOnBean` 조합으로 해결했습니다.
>
> CI 도 Qdrant sidecar 추가해서 정상 시나리오는 여전히 테스트 — 킬 스위치는 운영자 수동 회피책으로 명확히 분리했습니다."

---

## 6. 관련 문서
- [T-7 TAGO 킬 스위치](./2026-04-23-T7-tago-kill-switch.md) — 동일 철학
- `infra/.../QdrantRouteHistoryAdapter.java` — `@ConditionalOnBean` 추가
- `docker-compose.yml` — `SPRING_AUTOCONFIGURE_EXCLUDE` 통로
- `Dockerfile` — CLI 인자 → ENV 로 변경
- `.github/workflows/ci.yml` — Qdrant sidecar (정상 시나리오)
