package com.grandis.nova.order.order;

import com.grandis.nova.order.order.domain.repository.OrderWriter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 주문 계층 규칙. 컴파일러가 막지 못하는 것(public 이 된 엔티티 · 저장소 · 쓰기 포트)을 여기서 막는다.
 * 운영 코드만 검사한다 — 테스트는 픽스처 · 검증을 위해 계층을 넘나든다.
 */
@AnalyzeClasses(packages = "com.grandis.nova.order", importOptions = ImportOption.DoNotIncludeTests.class)
class OrderArchitectureTest {

    static final String ORDER = "com.grandis.nova.order.order";

    /** 상태 · 이력을 쓰는 길은 원장 하나다. 다른 곳이 쓰기 포트를 쥐면 이력 없는 전이 · 사유 없는 관리자 전이가 생긴다. */
    @ArchTest
    static final ArchRule onlyLedgerWritesOrders = noClasses()
            .that().doNotHaveFullyQualifiedName(OrderLedger.class.getName())
            .and().resideOutsideOfPackage(ORDER + ".persistence..")
            .should().dependOnClassesThat().areAssignableTo(OrderWriter.class)
            .because("주문 상태 · 이력은 OrderLedger 만 바꾼다");

    /** 엔티티 · JPA 저장소 · 매퍼는 영속 계층 밖으로 나가지 않는다. 밖에서는 포트(OrderReader · OrderWriter)만 쓴다. */
    @ArchTest
    static final ArchRule persistenceIsEncapsulated = noClasses()
            .that().resideOutsideOfPackage(ORDER + ".persistence..")
            .should().dependOnClassesThat().resideInAPackage(ORDER + ".persistence..")
            .because("JPA 엔티티를 직접 쓰면 원장 · 도메인 규칙을 건너뛴다");

    /**
     * 아웃박스는 주문 도메인을 모른다. 결제 등 다른 유스케이스도 쓰고, 발행 계층을 공통 모듈로 옮길 때 떼어낼 수 있어야 한다.
     * 주문 상태 → 메시지 결과 변환은 부르는 유스케이스가 한다.
     */
    @ArchTest
    static final ArchRule outboxIsIndependentOfOrderDomain = noClasses()
            .that().resideInAPackage("com.grandis.nova.order.outbox..")
            .should().dependOnClassesThat().resideInAPackage(ORDER + "..")
            .because("아웃박스는 주문 도메인과 따로 움직이는 기록 계층이다");

    /** 도메인 · 값 · 명령은 프레임워크를 모른다. */
    @ArchTest
    static final ArchRule coreIsFrameworkFree = noClasses()
            .that().resideInAnyPackage(ORDER + ".domain..", ORDER + ".vo..", ORDER + ".command..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework..", "org.hibernate..", ORDER + ".persistence..");
}
