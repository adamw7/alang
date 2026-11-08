package alang;

import static com.tngtech.archunit.core.domain.AccessTarget.Predicates.declaredIn;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Duration;
import java.util.Properties;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;

/**
 * Rules for the test code itself, so the suites stay fast, honest and as reproducible as the
 * language they test. {@link ArchitectureTest} covers the toolchain; this class analyses only the
 * test classes.
 */
@AnalyzeClasses(packages = "alang", importOptions = ImportOption.OnlyIncludeTests.class)
class TestConventionsArchitectureTest {

    /** Meta-annotation of {@code @Test}, {@code @TestFactory}, {@code @ParameterizedTest} and {@code @ArchTest}. */
    private static final String TESTABLE = "org.junit.platform.commons.annotation.Testable";

    @ArchTest
    static final ArchRule test_methods_live_in_test_classes = methods()
            .that().areMetaAnnotatedWith(TESTABLE)
            .should().beDeclaredInClassesThat().haveSimpleNameEndingWith("Test")
            .because("surefire runs only *Test classes, so a test method anywhere else silently never runs");

    @ArchTest
    static final ArchRule test_methods_are_runnable = methods()
            .that().areMetaAnnotatedWith(TESTABLE)
            .should().notBePrivate()
            .andShould().notBeStatic()
            .because("JUnit Jupiter silently skips a private or static test method");

    @ArchTest
    static final ArchRule before_and_after_all_are_static = methods()
            .that().areAnnotatedWith(BeforeAll.class)
            .or().areAnnotatedWith(AfterAll.class)
            .should().beStatic()
            .because("JUnit Jupiter only runs a non-static @BeforeAll or @AfterAll under the PER_CLASS "
                    + "lifecycle and fails the class otherwise")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule arch_rules_are_constants = fields()
            .that().areAnnotatedWith(ArchTest.class)
            .should().beStatic()
            .andShould().beFinal()
            .because("a rule is a constant; a reassignable one could be replaced before it is checked");

    @ArchTest
    static final ArchRule no_disabled_test_classes = noClasses()
            .should().beAnnotatedWith(Disabled.class)
            .because("a disabled test gives false confidence; delete it or fix what it guards");

    @ArchTest
    static final ArchRule no_disabled_test_methods = noMethods()
            .should().beAnnotatedWith(Disabled.class)
            .because("a disabled test gives false confidence; delete it or fix what it guards");

    @ArchTest
    static final ArchRule tests_use_junit_jupiter = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("org.junit", "junit.framework..")
            .because("the suites use JUnit Jupiter (org.junit.jupiter); the JUnit 3 and 4 APIs must not "
                    + "creep back in");

    @ArchTest
    static final ArchRule tests_do_not_sleep = noClasses()
            .should().callMethod(Thread.class, "sleep", long.class)
            .orShould().callMethod(Thread.class, "sleep", long.class, int.class)
            .orShould().callMethod(Thread.class, "sleep", Duration.class)
            .orShould().callMethod(TimeUnit.class, "sleep", long.class)
            .because("a test that sleeps is slow and flaky; wait on a condition instead");

    @ArchTest
    static final ArchRule tests_do_not_print = noClasses()
            .should().accessField(System.class, "out")
            .orShould().accessField(System.class, "err")
            .because("a test that prints adds noise instead of asserting; capture the program's output "
                    + "with Harness and assert on it");

    @ArchTest
    static final ArchRule tests_do_not_write_system_properties = noClasses()
            .should().callMethod(System.class, "setProperty", String.class, String.class)
            .orShould().callMethod(System.class, "clearProperty", String.class)
            .orShould().callMethod(System.class, "setProperties", Properties.class)
            .because("a system property is JVM-wide and outlives the test that set it; read settings "
                    + "such as -Dalang.fuzz, never write them");

    @ArchTest
    static final ArchRule tests_are_reproducible = noClasses()
            .should().callMethod(System.class, "currentTimeMillis")
            .orShould().callMethod(System.class, "nanoTime")
            .orShould().callMethod(Math.class, "random")
            .orShould().callMethod(UUID.class, "randomUUID")
            .orShould().callConstructor(Random.class)
            .orShould().callConstructor(SplittableRandom.class)
            // By name: a class literal here would be a dependency of this very class.
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.concurrent.ThreadLocalRandom")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.security.SecureRandom")
            .orShould().callMethodWhere(target(declaredIn(resideInAPackage("java.time.."))).and(target(name("now"))))
            .because("ALang promises that a failing test fails every time, identically, and the suites "
                    + "keep the same promise: seed every generator explicitly, as FuzzTest seeds from the "
                    + "file name");
}
