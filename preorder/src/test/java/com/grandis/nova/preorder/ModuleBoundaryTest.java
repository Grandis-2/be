package com.grandis.nova.preorder;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
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
 * 큰 모듈은 안을 api → application → domain 으로 나누고, 모듈 최상위 패키지에는 공개 API(서비스 · 스냅샷)만 둔다.
 * 하위 패키지는 public 이어도 모듈 밖에서 쓰지 않는다 — 다른 모듈은 공개 API 로만 주고받는다.
 */
@AnalyzeClasses(packages = ModuleBoundaryTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryTest {

    static final String ROOT = "com.grandis.nova.preorder";

    private static final String[] USE_CASES = modules("accept", "cancel", "query", "payability", "event", "deadletter");
    private static final String[] WIRING = modules("config", "metrics");
    /** 안을 api · application · domain 으로 나눈 모듈. 작은 모듈은 평평하게 둔다. */
    private static final String[] LAYERED =
            {"accept", "cancel", "query", "preorder", "campaign", "syncjob", "deadletter"};

    /** 나눈 모듈은 모듈 하나를, 그 밖(integration 의 catalog · order · sqs 등)은 패키지 하나를 한 조각으로 본다. */
    @ArchTest
    static final ArchRule 패키지_사이에_순환이_없다 = slices().assignedFrom(new SliceAssignment() {
        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            if (!javaClass.getPackageName().startsWith(ROOT)) {
                return SliceIdentifier.ignore();
            }
            String module = moduleOf(javaClass);
            return Arrays.asList(LAYERED).contains(module) ? SliceIdentifier.of(module)
                    : SliceIdentifier.of(javaClass.getPackageName());
        }

        @Override
        public String getDescription() {
            return "나눈 모듈은 모듈, 그 밖은 패키지";
        }
    }).should().beFreeOfCycles();

    @ArchTest
    static final ArchRule 나눈_모듈의_하위_패키지는_그_모듈_안에서만_쓴다 = classes()
            .that(resideInSubpackageOf(LAYERED))
            .should(onlyBeAccessedFromTheirOwnModule());

    @ArchTest
    static final ArchRule 도메인은_api_와_application_에_기대지_않는다 = noClasses()
            .that().resideInAnyPackage(layer("domain"))
            .should().dependOnClassesThat().resideInAnyPackage(concat(layer("api"), layer("application")));

    @ArchTest
    static final ArchRule application_은_api_에_기대지_않는다 = noClasses()
            .that().resideInAnyPackage(layer("application"))
            .should().dependOnClassesThat().resideInAnyPackage(layer("api"));

    /** 공개 API 가 api 타입을 드러내면 그 타입이 모듈 밖으로 샌다. 최상위가 application · domain 을 쓰는 것은 허용한다. */
    @ArchTest
    static final ArchRule 모듈_최상위는_자기_api_에_기대지_않는다 = noClasses()
            .that(resideInModuleRootOf(LAYERED))
            .should().dependOnClassesThat().resideInAnyPackage(layer("api"));

    @ArchTest
    static final ArchRule 나눈_모듈의_컨트롤러는_api_에_둔다 = classes()
            .that().areAnnotatedWith(RestController.class).and().resideInAnyPackage(modules(LAYERED))
            .should().resideInAnyPackage(layer("api"));

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

    /** 수신 어댑터(integration.sqs)는 이벤트 분배 · DLQ 적재로 넘기므로 event · deadletter 는 허용한다. */
    @ArchTest
    static final ArchRule 연동은_도메인에_기대지_않는다 = noClasses()
            .that().resideInAPackage(module("integration"))
            .should().dependOnClassesThat().resideInAnyPackage(concat(WIRING,
                    modules("preorder", "campaign", "syncjob", "accept", "cancel", "query", "payability")));

    @ArchTest
    static final ArchRule 기반_모듈은_도메인에_기대지_않는다 = noClasses()
            .that().resideInAnyPackage(modules("outbox", "admission", "web"))
            .should().dependOnClassesThat().resideInAnyPackage(concat(USE_CASES, WIRING,
                    modules("preorder", "campaign", "syncjob", "integration")));

    @ArchTest
    static final ArchRule 엔티티와_리포지토리는_소유_모듈_밖에서_쓰지_않는다 = classes()
            .that().areAnnotatedWith(Entity.class).or().areAssignableTo(Repository.class)
            .should(onlyBeAccessedFromTheirOwnModule());

    @ArchTest
    static final ArchRule 외부_서비스_클라이언트는_integration_안에서만_쓴다 = classes()
            .that().areAnnotatedWith(HttpExchange.class)
            .should().onlyHaveDependentClassesThat().resideInAPackage(module("integration"));

    @ArchTest
    static final ArchRule 컨트롤러는_기능_모듈에만_둔다 = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAnyPackage(modules("accept", "cancel", "query", "payability", "campaign", "syncjob",
                    "deadletter"));

    @ArchTest
    static final ArchRule 컨트롤러는_리포지토리를_직접_부르지_않는다 = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAssignableTo(Repository.class);

    private static ArchCondition<JavaClass> onlyBeAccessedFromTheirOwnModule() {
        return new ArchCondition<>("소유 모듈 안에서만 쓰인다") {
            @Override
            public void check(JavaClass owned, ConditionEvents events) {
                for (Dependency dependency : owned.getDirectDependenciesToSelf()) {
                    if (!moduleOf(dependency.getOriginClass()).equals(moduleOf(owned))) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** 모듈은 최상위 패키지다(deadletter.application 은 deadletter 모듈). */
    private static String moduleOf(JavaClass javaClass) {
        String relative = javaClass.getPackageName().substring(ROOT.length());
        return relative.isEmpty() ? "" : relative.substring(1).split("\\.")[0];
    }

    /** 나눈 모듈의 하위 패키지(최상위 공개 API 제외). */
    private static DescribedPredicate<JavaClass> resideInSubpackageOf(String... names) {
        return DescribedPredicate.describe("나눈 모듈의 하위 패키지", javaClass -> Arrays.stream(names)
                .anyMatch(name -> javaClass.getPackageName().startsWith(ROOT + "." + name + ".")));
    }

    /** 나눈 모듈의 최상위 패키지(공개 API). */
    private static DescribedPredicate<JavaClass> resideInModuleRootOf(String... names) {
        return DescribedPredicate.describe("나눈 모듈의 최상위 패키지", javaClass -> Arrays.stream(names)
                .anyMatch(name -> javaClass.getPackageName().equals(ROOT + "." + name)));
    }

    /** 나눈 모듈마다 같은 이름의 하위 패키지(api · application · domain). */
    private static String[] layer(String name) {
        return Arrays.stream(LAYERED).map(module -> ROOT + "." + module + "." + name + "..").toArray(String[]::new);
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
