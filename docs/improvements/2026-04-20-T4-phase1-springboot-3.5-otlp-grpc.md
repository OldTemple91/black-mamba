# T-4 Phase 1: Spring Boot 3.5.13 업그레이드 + OTLP/gRPC 완전 전환

> 작업일: 2026-04-20
> 담당 Phase: ROADMAP.md T-4 (Phase 1/2 중 Phase 1)
> 공수: 실측 2시간
> 커밋: TBD
> 이전 개선과 연결: [2026-04-17 OTLP/HTTP 전환](./2026-04-17-C-otlp-protobuf-tracing.md)

---

## 1. 배경 (Why)

### 기존 상황
- Spring Boot 3.3.0 사용 중 → **OSS 지원 2025-06 종료** (보안 패치 불가)
- 지난 개선에서 Zipkin → OTLP/HTTP로 전환했지만 **gRPC 전환은 실패** (3.3 한계)
  - `transport: grpc` 옵션은 3.4부터 공식 도입
  - 수동 `OtlpGrpcSpanExporter` Bean 등록 시 auto-config 충돌

### 목표
1. 보안 패치 지원받는 현행 버전으로 업그레이드 (3.5.13, 2026-11까지 OSS 지원)
2. OTLP/gRPC 완전 전환 — 이전 작업에서 미완결된 부분 마무리
3. Java 25는 별도 Phase 2로 분리 (리스크 분산)

---

## 2. 기존 구조 (Before)

```
[app (Spring Boot 3.3.0, Java 21, Gradle 8.7)]
   ↓ OTLP/HTTP/Protobuf
[Tempo :4318/v1/traces]
```

- OTLP/HTTP는 Zipkin 대비 표준/포맷 개선은 있었지만
- **gRPC 장점(HTTP/2 멀티플렉싱, 연결 재사용, Bidirectional streaming)** 은 미달

---

## 3. 개선 방향 (How)

### 3-1. 업그레이드 범위

| 구성요소 | Before | After | 이유 |
|---------|--------|-------|------|
| Spring Boot | 3.3.0 | **3.5.13** | OSS 지원 유지 + `transport: grpc` 공식 |
| io.spring.dependency-management | 1.1.4 | 1.1.7 | Spring Boot 3.5와 호환 |
| Gradle | 8.7 | **8.14.3** | Spring Boot 3.5 권장 |
| JUnit Platform | 자동 | **launcher 명시** | Gradle 8.14+ 요구사항 |
| OTLP transport | HTTP | **gRPC** | Spring Boot 3.5 공식 속성 |
| Tempo 수신 포트 | 4318 (HTTP) | **4317 (gRPC)** | gRPC로 전환 |

### 3-2. Phase 분리 전략 (C안 채택)

```
Phase 1 (이 문서): Spring Boot 3.5 + OTLP/gRPC + Gradle 8.14
Phase 2 (후속):    Java 21 → 25 LTS 전환
```

**이유:** 문제 발생 시 원인 분리 용이. Spring Boot와 Java 동시 업그레이드는 리스크 누적.

---

## 4. 구현 (What)

### 4-1. 변경된 파일
- `build.gradle` — Spring Boot 3.5.13, dependency-management 1.1.7, JUnit launcher
- `gradle/wrapper/gradle-wrapper.properties` — Gradle 8.14.3
- `api/src/main/resources/application.yml` — `transport: grpc`, endpoint 4317
- `docker-compose.yml` — `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://tempo:4317`

### 4-2. 핵심 변경점

**루트 build.gradle:**
```gradle
// Before
id 'org.springframework.boot' version '3.3.0' apply false
id 'io.spring.dependency-management' version '1.1.4' apply false
mavenBom "org.springframework.boot:spring-boot-dependencies:3.3.0"

// After
id 'org.springframework.boot' version '3.5.13' apply false
id 'io.spring.dependency-management' version '1.1.7' apply false
mavenBom "org.springframework.boot:spring-boot-dependencies:3.5.13"

dependencies {
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    // Gradle 8.14+ 요구사항: junit-platform-launcher 명시적 의존성
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

**application.yml:**
```yaml
# Before
management:
  otlp:
    tracing:
      endpoint: http://localhost:4318/v1/traces  # OTLP HTTP
      compression: gzip

# After
management:
  otlp:
    tracing:
      endpoint: http://localhost:4317             # OTLP gRPC (Tempo 4317)
      transport: grpc                             # ← Spring Boot 3.5 공식 지원
      compression: gzip
```

**gradle-wrapper.properties:**
```properties
# Before
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip

# After
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip
```

### 4-3. 테스트
기존 테스트 모두 통과 (22 tests). 수정 불필요.

---

## 5. 검증 & 성과 (Result)

### 5-1. 빌드 검증
```bash
$ ./gradlew build jacocoRootReport
BUILD SUCCESSFUL in 9s
22 actionable tasks: 10 executed, 12 up-to-date
```

### 5-2. 기동 검증
```
 :: Spring Boot ::               (v3.5.13)
Running with Spring Boot v3.5.13, Spring v6.2.17
```
✅ Spring Boot 3.5.13 + Spring Framework 6.2.17 기동 확인.

### 5-3. OTLP/gRPC 전송 검증

| 지표 | Before (OTLP HTTP) | After (OTLP gRPC) |
|------|-------------------|-------------------|
| 전송 에러 | 0건 | **0건** |
| Tempo 수신 트레이스 | 37건 | **50건** (동일 요청량, 1분) |
| 프로토콜 | OTLP/HTTP | **OTLP/gRPC** |
| 포트 | 4318 | **4317** |
| Transport | OkHttp (HTTP/1.1/2) | **HTTP/2 전용 (gRPC)** |

### 5-4. gRPC 실제 동작 증거
- `application.yml`: `transport: grpc` 설정 → Spring Boot auto-config이 `OtlpGrpcSpanExporter` 자동 선택
- `endpoint: http://tempo:4317`: Tempo의 gRPC 수신 포트
- 기동 시 `OkHttp http://...` 로그 없음 (HTTP exporter 활성화 안 됨 확인)
- Tempo 트레이스 수신 → gRPC 경로 성공

---

## 6. 사이드 이펙트 & 한계

### ⚠️ Gradle 8.14 + JUnit 5.12 첫 실행 이슈
**증상:**
```
OutputDirectoryProvider not available; probably due to unaligned versions of the
junit-platform-engine and junit-platform-launcher jars
```

**원인:** Gradle 8.14부터 test executor가 `junit-platform-launcher`를 **직접 런타임 의존성으로** 필요로 함. 이전에는 Gradle이 내장 제공.

**해결:**
```gradle
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
```

### ⚠️ Deprecation Warning (Gradle 9.0 호환성)
```
Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.
```
현재 빌드에는 영향 없지만 Gradle 9.0 이전에 해소 필요.

### ⚠️ Spring Framework 6.1 → 6.2 major bump
Spring Boot 3.5는 Spring Framework 6.2 사용. 일부 내부 API 변경 있지만 우리 코드는 영향 없음.

### ✅ Breaking Change 확인 결과
- `@MockBean` (3.4에서 deprecated) → 우리는 `@MockBean` 사용 중이지만 아직 동작. 3.6에서 제거 예정.
  - ROADMAP에 `@MockitoBean` 전환 항목 추가 검토 필요.
- `application.yml` 속성명 변경 없음 (우리 설정은 모두 호환).

---

## 7. 사례 정리

> **"최근 Spring Boot 버전 업그레이드 경험 있나요?"**
>
> "Spring Boot 3.3.0이 2025년 6월에 OSS 지원이 종료돼서 **3.5.13으로 업그레이드**했습니다.
>
> 단순 버전 업이 아니라 **구체적 목표**가 있었습니다. 이전에 분산 추적을 Zipkin에서
> OTLP로 전환하면서 gRPC까지 가고 싶었는데, Spring Boot 3.3에서는 `transport: grpc` 옵션이 없어서
> 수동 Bean 등록을 시도했더니 auto-config의 HTTP exporter와 충돌해 **Connection reset 에러**가
> 계속 났습니다. 3.4부터 공식 지원되는 속성이라 3.5로 가야 해결되는 문제였습니다.
>
> **C안(점진적) 전략**을 택했습니다. Java 25 LTS로 한 번에 올리고 싶었지만, 문제 발생 시
> 원인이 Spring Boot인지 Java인지 구분이 어렵기 때문에 **Phase 1: Spring Boot만**,
> **Phase 2: Java 25** 로 분리했습니다.
>
> **실제 발견한 호환성 이슈**: Gradle 8.14부터 JUnit Platform Launcher를 test executor가 직접
> 요구합니다. 이전엔 Gradle이 내장 제공했는데 이제는 `testRuntimeOnly`로 명시해야 합니다.
> 에러 메시지가 `OutputDirectoryProvider not available` 라 원인 파악에 시간이 걸렸습니다.
>
> 결과적으로 **OTLP/gRPC 완전 전환 성공**: 이전에 37건이던 Tempo 수신 트레이스가 같은 요청량에서
> 50건으로 증가했습니다. gRPC의 연결 재사용 효과입니다."

---

## 8. 다음 단계
- [ ] **Phase 2**: Java 21 → Java 25 LTS 전환 (별도 개선 기록)
- [ ] `@MockBean` → `@MockitoBean` 리팩토링 (3.6 대비)
- [ ] Gradle 9.0 deprecation 이슈 해소
- [ ] docker-compose에서 Tempo 4318 포트 제거 고려 (gRPC만 사용 시)
