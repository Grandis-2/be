package com.grandis.nova.payment.client.toss;

import com.grandis.nova.payment.PaymentApplication;
import com.grandis.nova.payment.config.HttpClientConfig;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 토스 클라이언트는 결제 서비스의 다른 패키지(도메인 · 값 · 영속 · 원장)를 모른다. 결과는 client.toss 안의 타입으로만 돌려주고,
 * 도메인 사건으로 옮기는 매핑은 결제 유스케이스(NV-101)가 한다 — 도메인과 병렬로 만들고, 결제사를 바꿀 때 여기만 바꾸게.
 * 반대 방향(도메인이 토스를 모름)은 PaymentArchitectureTest 가 막는다. 운영 코드만 본다(설정 클래스가 여기를 조립하는 것은 정상).
 *
 * 패키지 이름은 클래스에서 얻는다 — 문자열로 적으면 패키지를 옮길 때 규칙이 조용히 빈 규칙이 된다. 경계는 resideInAPackage 의
 * ".." 패턴으로 긋는다(문자열 접두어 비교는 client.tossx 같은 이웃 패키지까지 같은 것으로 본다).
 */
@AnalyzeClasses(packagesOf = PaymentApplication.class, importOptions = ImportOption.DoNotIncludeTests.class)
class TossClientArchitectureTest {

    static final String PAYMENT = PaymentApplication.class.getPackageName();
    static final String TOSS = TossPaymentClient.class.getPackageName();

    @ArchTest
    static final ArchRule tossClientDependsOnNothingElseInPayment = noClasses()
            .that().resideInAPackage(TOSS + "..")
            .should().dependOnClassesThat(resideInAPackage(PAYMENT + "..").and(resideOutsideOfPackage(TOSS + "..")))
            .because("토스 결과는 클라이언트 쪽 타입으로만 돌려주고, 도메인 매핑은 유스케이스가 한다");

    /**
     * 선언형 인터페이스를 직접 부르면 코드별 분류(D8)를 건너뛰고 RestClient 예외를 받는다 — 결과 불명을 실패로 다루는 길이 열린다.
     * 밖에서는 TossPaymentClient 만 쓴다. 조립하는 설정 클래스 하나만 예외다(설정 패키지 전체가 아니라).
     */
    @ArchTest
    static final ArchRule onlyTossClientCallsTheApi = noClasses()
            .that().resideOutsideOfPackage(TOSS + "..")
            .and().doNotHaveFullyQualifiedName(HttpClientConfig.class.getName())
            .should().dependOnClassesThat().areAssignableTo(TossPaymentsApi.class)
            .because("토스 호출은 응답 분류를 거치는 TossPaymentClient 로만 한다");
}
