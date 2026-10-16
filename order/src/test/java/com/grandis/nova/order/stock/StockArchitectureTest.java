package com.grandis.nova.order.stock;

import com.grandis.nova.order.stock.domain.repository.StockWriter;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.Set;

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

    /**
     * 재고를 옮길 수 있는 곳(원장을 부르는 클래스)의 허용 목록. 재고 장부 대조는 "재고는 주문 · 드로우 회차 행과 같은 트랜잭션에서만 바뀐다" 에
     * 기대어 주문 기준 수량과 장부를 맞댄다(OrderStatusStockHoldTest · JdbcStockExpectations). 새 길이 생기면 여기서 깨진다 — 그 길이 같은
     * 트랜잭션에서 주문 · 회차 행을 바꾸는지, 대조 식에 들어가는지 확인하고 목록에 더한다.
     */
    static final Set<String> LEDGER_CALLERS = Set.of(
            "com.grandis.nova.order.order.place.PlaceCartOrderService",         // 장바구니 주문 생성 · 앞 주문 교체(확보 · 반환)
            "com.grandis.nova.order.order.cancel.UnpaidCartOrderRelease",       // 미결제 취소 · 만료(반환)
            "com.grandis.nova.order.order.pay.CartOrderFulfillment",            // 결제 승인(판매 확정)
            "com.grandis.nova.order.draw.AdminDrawService",                     // 드로우 회차 생성(당첨 인원 확보)
            "com.grandis.nova.order.draw.DrawSettlement",                       // 드로우 결과 반영(판매 확정 · 남은 확보 반납)
            "com.grandis.nova.order.stock.admin.AdminStockService",             // 관리자 총량 설정 · 초기화(확보 · 판매는 그대로)
            "com.grandis.nova.order.stock.admin.StockCorrectionService");       // 관리자 보정(주문 기준으로 덮기)

    @ArchTest
    static final ArchRule onlyListedClassesMoveStock = noClasses()
            .that().doNotHaveFullyQualifiedName(StockLedger.class.getName())
            .and(DescribedPredicate.describe("재고 이동 허용 목록 밖", (JavaClass c) -> !LEDGER_CALLERS.contains(c.getName().split("\\$")[0])))
            .should().dependOnClassesThat().areAssignableTo(StockLedger.class)
            .because("재고 장부 대조의 불변식이 모든 재고 이동 경로를 알아야 한다");

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
