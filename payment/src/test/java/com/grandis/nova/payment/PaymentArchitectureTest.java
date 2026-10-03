package com.grandis.nova.payment;

import com.grandis.nova.payment.domain.repository.PaymentTransactionWriter;
import com.grandis.nova.payment.domain.repository.PaymentWriter;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 계층 규칙. 컴파일러가 막지 못하는 것(public 인 엔티티 · 저장소 · 쓰기 포트)을 여기서 막는다. 운영 코드만 검사한다.
 * 주문과의 경계는 모듈이 달라 컴파일러가 막으므로 여기 두지 않는다.
 *
 * 패키지 이름은 원장 클래스에서 끌어온다. 문자열로 적으면 오타 · 이동에도 규칙이 아무것도 고르지 않은 채 통과한다 —
 * 그래서 {@link #layersExist} 가 각 계층에 클래스가 있는지도 확인한다.
 */
@AnalyzeClasses(packagesOf = PaymentLedger.class, importOptions = ImportOption.DoNotIncludeTests.class)
class PaymentArchitectureTest {

    static final String PAYMENT = PaymentLedger.class.getPackageName();
    static final String DOMAIN = PAYMENT + ".domain..";
    static final String VO = PAYMENT + ".vo..";
    static final String PERSISTENCE = PAYMENT + ".persistence..";

    /** 거래 · 결제 기록을 쓰는 길은 원장 하나다. 다른 곳이 쓰기 포트를 쥐면 상태 머신 · 리스를 건너뛴 UPDATE 가 생긴다. */
    @ArchTest
    static final ArchRule onlyLedgerWritesPayments = noClasses()
            .that().doNotHaveFullyQualifiedName(PaymentLedger.class.getName())
            .and().resideOutsideOfPackage(PERSISTENCE)
            .should().dependOnClassesThat(assignableTo(PaymentTransactionWriter.class)
                    .or(assignableTo(PaymentWriter.class)))
            .because("결제 거래 · 결제 기록은 PaymentLedger 만 바꾼다");

    @ArchTest
    static final ArchRule persistenceIsEncapsulated = noClasses()
            .that().resideOutsideOfPackage(PERSISTENCE)
            .should().dependOnClassesThat().resideInAPackage(PERSISTENCE)
            .because("JPA 엔티티를 직접 쓰면 원장 · 도메인 규칙을 건너뛴다");

    @ArchTest
    static final ArchRule coreIsFrameworkFree = noClasses()
            .that().resideInAnyPackage(DOMAIN, VO)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.persistence..", "org.springframework..", "org.hibernate..", PERSISTENCE);

    /**
     * 결제 도메인은 결제사 클라이언트를 모른다. 응답 코드 → 도메인 사건(Outcome) 변환은 유스케이스 한 곳에서 한다.
     * 클라이언트 패키지는 NV-98 이 만든다 — 어느 상위 패키지에 놓이든 잡도록 "..client.toss.." 로 둔다.
     */
    @ArchTest
    static final ArchRule domainDoesNotKnowToss = noClasses()
            .that().resideInAnyPackage(DOMAIN, VO)
            .should().dependOnClassesThat().resideInAPackage("..client.toss..")
            .because("결제 도메인은 결제사 응답 타입이 아니라 도메인 사건(Outcome)을 받는다");

    /**
     * 리스를 쥔 거래는 원장의 start · claim 만 만든다. 생성자를 패키지 안으로 닫았지만, 같은 패키지에 새 클래스가 생기면
     * 컴파일러는 막지 않는다. 메서드 참조(ClaimedTransaction::new)도 생성자 접근으로 잡는다.
     */
    @ArchTest
    static final ArchRule onlyLedgerCreatesClaimedTransaction = noClasses()
            .that().doNotHaveFullyQualifiedName(PaymentLedger.class.getName())
            .should().accessTargetWhere(JavaAccess.Predicates.target(DescribedPredicate.describe(
                    "ClaimedTransaction 생성자", (AccessTarget target) -> target.getOwner().isEquivalentTo(
                            ClaimedTransaction.class) && target.getName().equals(JavaConstructor.CONSTRUCTOR_NAME))))
            .because("결과 반영은 선점한 작업자만 한다");

    @ArchTest
    static void layersExist(JavaClasses classes) {
        for (String layer : new String[]{DOMAIN, VO, PERSISTENCE}) {
            assertThat(classes.stream().filter(c -> c.getPackageName().startsWith(layer.replace("..", ""))))
                    .as("계층 %s 에 클래스가 없다 — 규칙이 아무것도 검사하지 않는다", layer).isNotEmpty();
        }
    }
}
