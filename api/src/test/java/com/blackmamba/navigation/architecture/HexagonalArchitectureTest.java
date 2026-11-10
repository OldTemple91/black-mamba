package com.blackmamba.navigation.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Hexagonal 의존 방향을 빌드 게이트로 강제한다.
 *
 * <pre>
 *   api → application → domain ← infra
 *   api -→ infra (모듈 의존은 빈 와이어링용. 코드 참조는 금지 — 아래 규칙)
 * </pre>
 *
 * 규칙 위반 시 CI 가 실패하므로, "아키텍처를 지킨다" 가
 * 리뷰어의 조심성이 아닌 컴파일 수준 보장이 된다.
 */
class HexagonalArchitectureTest {

    private static final String DOMAIN = "com.blackmamba.navigation.domain..";
    private static final String APPLICATION = "com.blackmamba.navigation.application..";
    private static final String INFRA = "com.blackmamba.navigation.infra..";
    private static final String API = "com.blackmamba.navigation.api..";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.blackmamba.navigation");
    }

    @Test
    void domain_은_다른_레이어를_의존하지_않는다() {
        noClasses().that().resideInAPackage(DOMAIN)
                .should().dependOnClassesThat()
                .resideInAnyPackage(APPLICATION, INFRA, API)
                .because("domain 은 순수 모델 — 바깥 레이어를 알면 의존 역전이 깨진다")
                .check(classes);
    }

    @Test
    void api_는_infra_를_직접_참조하지_않는다() {
        noClasses().that().resideInAPackage(API)
                .should().dependOnClassesThat()
                .resideInAPackage(INFRA)
                .because("컨트롤러는 application 의 포트만 안다 — infra 구현은 빈 와이어링(런타임)으로만 만난다")
                .check(classes);
    }

    @Test
    void domain_은_프레임워크에_의존하지_않는다() {
        noClasses().that().resideInAPackage(DOMAIN)
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "org.springframework..",
                        "reactor..",
                        "io.micrometer..",
                        "jakarta..",
                        "lombok..")
                .because("domain 의 '외부 의존성 0' 원칙 — Spring/Reactor 없이 어디서든 재사용 가능해야 한다")
                .check(classes);
    }

    @Test
    void application_은_infra_를_의존하지_않는다() {
        noClasses().that().resideInAPackage(APPLICATION)
                .should().dependOnClassesThat().resideInAPackage(INFRA)
                .because("application 은 Port 인터페이스만 알고, 구현은 infra 가 제공한다 (의존성 역전)")
                .check(classes);
    }

    @Test
    void application_은_api_를_의존하지_않는다() {
        noClasses().that().resideInAPackage(APPLICATION)
                .should().dependOnClassesThat().resideInAPackage(API)
                .because("유스케이스가 표현 계층을 알면 호출 방향이 역전된다")
                .check(classes);
    }

    @Test
    void infra_는_api_를_의존하지_않는다() {
        noClasses().that().resideInAPackage(INFRA)
                .should().dependOnClassesThat().resideInAPackage(API)
                .because("어댑터는 표현 계층과 무관해야 교체 가능하다")
                .check(classes);
    }

    @Test
    void api_는_LLM_벡터DB_SDK_를_직접_의존하지_않는다() {
        noClasses().that().resideInAPackage(API)
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.ai..", "io.qdrant..", "ch.hsr.geohash..")
                .because("모델/벡터DB 접근은 infra 어댑터 책임 — 표현 계층은 Port 만 안다")
                .check(classes);
    }

    @Test
    void application_은_웹_스케줄링_기술에_의존하지_않는다() {
        noClasses().that().resideInAPackage(APPLICATION)
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework.web..", "org.springframework.scheduling..", "jakarta.servlet..")
                .because("유스케이스는 전송 프로토콜과 타이머를 모른다 — 트리거는 api/infra 책임")
                .check(classes);
    }

    @Test
    void domain_은_불변이다() {
        classes().that().resideInAPackage(DOMAIN)
                .should().haveOnlyFinalFields()
                .because("도메인 상태는 record/enum 기반 불변 — 가변 필드는 동시성·추론 비용을 만든다")
                .check(classes);
    }
}
