package com.grandis.nova.order.cart;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 장바구니 계층 규칙(StockArchitectureTest 와 같은 모양). 운영 코드만 검사한다.
 */
@AnalyzeClasses(packages = "com.grandis.nova.order", importOptions = ImportOption.DoNotIncludeTests.class)
class CartArchitectureTest {

    static final String CART = "com.grandis.nova.order.cart";

    /** 엔티티 · JPA 저장소는 영속 계층 밖으로 나가지 않는다. 밖에서는 포트(CartStore)만 쓴다. */
    @ArchTest
    static final ArchRule persistenceIsEncapsulated = noClasses()
            .that().resideOutsideOfPackage(CART + ".persistence..")
            .should().dependOnClassesThat().resideInAPackage(CART + ".persistence..")
            .because("영속 계층을 직접 쓰면 잠금 · 유일 키 판정을 건너뛴다");

    /** 도메인은 프레임워크를 모른다. */
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage(CART + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework..", "org.hibernate..", "io.swagger..", "org.springdoc..",
                    CART + ".persistence..");
}
