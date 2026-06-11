package com.blackmamba.navigation.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Hexagonal 의존 방향을 빌드 게이트로 강제한다.
 *
 * <pre>
 *   api → application → domain ← infra
 *   api -→ infra (빈 와이어링용 허용 — 모듈 그래프의 점선)
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
}
