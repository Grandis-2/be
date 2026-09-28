package com.grandis.nova.preorder;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import jakarta.persistence.Entity;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.Arrays;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 모듈 경계. 모듈은 최상위 패키지이고, 유스케이스 → aggregate → 기반 방향으로만 기댄다.
 * 아직 고치지 않은 기존 위반은 src/test/resources/archunit_store 에 기록해 두고 새 위반만 막는다.
 */
@AnalyzeClasses(packages = ModuleBoundaryTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    static final String ROOT = "com.grandis.nova.preorder";

    private static final String[] USE_CASES = modules("accept", "cancel", "query", "payability", "event");
    private static final String[] WIRING = modules("config", "metrics");

    @ArchTest
    static final ArchRule 패키지_사이에_순환이_없다 = slices().matching(ROOT + ".(**)").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule 예약_aggregate_는_다른_모듈에_기대지_않는다 = noClasses()
            .that().resideInAPackage(module("preorder"))
            .should().dependOnClassesThat().resideInAnyPackage(concat(USE_CASES, WIRING,
                    modules("campaign", "syncjob", "outbox", "admission", "integration", "web")));

    @ArchTest
    static final ArchRule 회차와_동기화_작업은_유스케이스에_기대지_않는다 = noClasses()
            .that().resideInAnyPackage(modules("campaign", "syncjob"))
            .should().dependOnClassesThat().resideInAnyPackage(concat(USE_CASES, WIRING));

    @ArchTest
    static final ArchRule 회차는_동기화_작업에_기대지_않는다 = noClasses()
            .that().resideInAPackage(module("campaign")).should().dependOnClassesThat().resideInAPackage(module("syncjob"));

    @ArchTest
    static final ArchRule 동기화_작업은_회차에_기대지_않는다 = noClasses()
            .that().resideInAPackage(module("syncjob")).should().dependOnClassesThat().resideInAPackage(module("campaign"));

    /** 수신 어댑터(integration.sqs)는 이벤트 분배로 넘기므로 event 는 허용한다. */
    @ArchTest
    static final ArchRule 연동은_도메인에_기대지_않는다 = noClasses()
            .that().resideInAPackage(module("integration"))
            .should().dependOnClassesThat().resideInAnyPackage(concat(WIRING,
                    modules("preorder", "campaign", "syncjob", "accept", "cancel", "query", "payability")));

    @ArchTest
    static final ArchRule 기반_모듈은_도메인에_기대지_않는다 = FreezingArchRule.freeze(noClasses()
            .that().resideInAnyPackage(modules("outbox", "admission", "web"))
            .should().dependOnClassesThat().resideInAnyPackage(concat(USE_CASES, WIRING,
                    modules("preorder", "campaign", "syncjob", "integration"))));

    @ArchTest
    static final ArchRule 엔티티와_리포지토리는_소유_모듈_밖에서_쓰지_않는다 = FreezingArchRule.freeze(classes()
            .that().areAnnotatedWith(Entity.class).or().areAssignableTo(Repository.class)
            .should(onlyBeAccessedFromTheirOwnPackage()));

    @ArchTest
    static final ArchRule 외부_서비스_클라이언트는_integration_안에서만_쓴다 = classes()
            .that().areAnnotatedWith(HttpExchange.class)
            .should().onlyHaveDependentClassesThat().resideInAPackage(module("integration"));

    @ArchTest
    static final ArchRule 컨트롤러는_기능_모듈에만_둔다 = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAnyPackage(modules("accept", "cancel", "query", "payability", "campaign", "syncjob"));

    @ArchTest
    static final ArchRule 컨트롤러는_리포지토리를_직접_부르지_않는다 = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAssignableTo(Repository.class);

    private static ArchCondition<JavaClass> onlyBeAccessedFromTheirOwnPackage() {
        return new ArchCondition<>("소유 패키지 안에서만 쓰인다") {
            @Override
            public void check(JavaClass owned, ConditionEvents events) {
                for (Dependency dependency : owned.getDirectDependenciesToSelf()) {
                    if (!dependency.getOriginClass().getPackageName().equals(owned.getPackageName())) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static String module(String name) {
        return ROOT + "." + name + "..";
    }

    private static String[] modules(String... names) {
        return Arrays.stream(names).map(ModuleBoundaryTest::module).toArray(String[]::new);
    }

    private static String[] concat(String[]... groups) {
        return Arrays.stream(groups).flatMap(Arrays::stream).toArray(String[]::new);
    }
}
