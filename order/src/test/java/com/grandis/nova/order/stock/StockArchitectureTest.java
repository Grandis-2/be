package com.grandis.nova.order.stock;

import com.grandis.nova.order.stock.domain.repository.StockWriter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 재고 계층 규칙. 주문의 같은 규칙(OrderArchitectureTest)은 order.order 만 덮어서 따로 둔다. 운영 코드만 검사한다.
 */
@AnalyzeClasses(packages = "com.grandis.nova.order", importOptions = ImportOption.DoNotIncludeTests.class)
class StockArchitectureTest {

    static final String STOCK = "com.grandis.nova.order.stock";

    /**
     * 재고 표를 바꾸는 길은 원장 하나다. 확보 · 확정 · 반환(주문 생성 · 결제)이 생겨도 원장을 거치게 해,
     * 조건부 UPDATE · 영향 행 판정 · 잠금 순서가 한곳에 모이게 한다.
     */
    @ArchTest
    static final ArchRule onlyLedgerWritesStock = noClasses()
            .that().doNotHaveFullyQualifiedName(StockLedger.class.getName())
            .and().resideOutsideOfPackage(STOCK + ".persistence..")
            .should().dependOnClassesThat().areAssignableTo(StockWriter.class)
            .because("재고 표는 StockLedger 만 바꾼다");

    /** 엔티티 · JPA 저장소 · catalog 표 읽기는 영속 계층 밖으로 나가지 않는다. 밖에서는 포트만 쓴다. */
    @ArchTest
    static final ArchRule persistenceIsEncapsulated = noClasses()
            .that().resideOutsideOfPackage(STOCK + ".persistence..")
            .should().dependOnClassesThat().resideInAPackage(STOCK + ".persistence..")
            .because("영속 계층을 직접 쓰면 원장을 건너뛴다");

    /** 도메인은 프레임워크를 모른다. */
    @ArchTest
    static final ArchRule domainIsFrameworkFree = noClasses()
            .that().resideInAPackage(STOCK + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework..", "org.hibernate..", "io.swagger..", "org.springdoc..",
                    STOCK + ".persistence..");
}
