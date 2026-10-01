package com.grandis.nova.waitingroom;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.grandis.nova.waitingroom", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    /** servlet 필터 · MVC 설정은 리액티브 앱에서 동작하지 않고, 블로킹 API 가 이벤트 루프를 막는다. */
    @ArchTest
    static final ArchRule servlet_과_MVC_에_기대지_않는다 = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.servlet..", "org.springframework.web.servlet..",
                    "com.grandis.nova.common.web..", "com.grandis.nova.common.security..");

    @ArchTest
    static final ArchRule 패키지_사이에_순환이_없다 = slices()
            .matching("com.grandis.nova.waitingroom.(*)..")
            .should().beFreeOfCycles();

    /** 판정 도메인은 Spring · Redis 없이 단위 테스트로 검증한다. */
    @ArchTest
    static final ArchRule 도메인은_JDK_와_자기_도메인에만_기댄다 = classes()
            .that().resideInAPackage("..waitingroom.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "..waitingroom.domain..")
            .allowEmptyShould(true);
}
