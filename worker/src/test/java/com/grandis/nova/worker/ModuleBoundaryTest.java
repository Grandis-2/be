package com.grandis.nova.worker;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** 모듈 경계. 모듈은 최상위 패키지다. worker 는 DB 를 쓰지 않고, 다른 서비스와는 메시지로만 주고받는다. */
@AnalyzeClasses(packages = ModuleBoundaryTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    static final String ROOT = "com.grandis.nova.worker";

    @ArchTest
    static final ArchRule 패키지_사이에_순환이_없다 = slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule DB_를_쓰지_않는다 = noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "org.springframework.data..", "java.sql..", "javax.sql..",
                    "org.springframework.jdbc..", "org.springframework.transaction..");

    @ArchTest
    static final ArchRule 다른_서비스의_코드를_쓰지_않는다 = noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("com.grandis.nova.preorder..", "com.grandis.nova.order..", "com.grandis.nova.payment..",
                    "com.grandis.nova.catalog..", "com.grandis.nova.member..", "com.grandis.nova.waitingroom..",
                    "com.grandis.nova.batch..");

    /** 메시지 · 이벤트의 모양은 운영값(설정)과 외부 호출에 기대지 않는다. */
    @ArchTest
    static final ArchRule 메시지와_이벤트는_설정과_외부_호출에_기대지_않는다 = noClasses()
            .that().resideInAnyPackage(ROOT + ".job..", ROOT + ".event..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".config..", ROOT + ".mock..");
}
