package com.grandis.nova.order.draw;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 럭키 드로우 계층 규칙(StockArchitectureTest 와 같은 모양). 운영 코드만 검사한다.
 */
@AnalyzeClasses(packages = "com.grandis.nova.order", importOptions = ImportOption.DoNotIncludeTests.class)
class DrawArchitectureTest {

    static final String DRAW = "com.grandis.nova.order.draw";

    /** 엔티티 · JPA 저장소는 영속 계층 밖으로 나가지 않는다. 밖에서는 포트(DrawCampaignStore)만 쓴다. */
    @ArchTest
    static final ArchRule persistenceIsEncapsulated = noClasses()
            .that().resideOutsideOfPackage(DRAW + ".persistence..")
            .should().dependOnClassesThat().resideInAPackage(DRAW + ".persistence..")
            .because("영속 계층을 직접 쓰면 저장소 포트의 규칙(넣자마자 flush · 정렬)을 건너뛴다");

    /** 도메인은 프레임워크를 모른다. */
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage(DRAW + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework..", "org.hibernate..", "io.swagger..", "org.springdoc..",
                    DRAW + ".persistence..");
}
