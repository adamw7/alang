package alang;

import static com.tngtech.archunit.core.domain.AccessTarget.Predicates.declaredIn;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Architecture rules for the toolchain, checked by ArchUnit on every {@code mvn test}.
 *
 * <p>The first rules fix the package layering. The rest turn the README's promises about the
 * toolchain itself into checks: the same bytes out on every run, machine and locale, and I/O only
 * at the command-line boundary. Every rule says why it exists and how to comply.
 */
@AnalyzeClasses(packages = "alang", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String CLI = "alang.cli..";

    // ---------------------------------------------------------------- structure

    /**
     * Who may depend on whom. Read bottom-up: util, diag, the model (AST and types), then the
     * parser, interpreter, checker and C backend, with the CLI on top. {@code ast} and
     * {@code types} share a layer because they refer to each other (an AST node stores its resolved
     * type; a struct or enum type points at its declaration). Every class must be in a layer, so a
     * new package has to be placed here first.
     */
    @ArchTest
    static final ArchRule layers = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .ensureAllClassesAreContainedInArchitecture()
            .layer("Cli").definedBy(CLI)
            .layer("Check").definedBy("alang.check..")
            .layer("Cgen").definedBy("alang.cgen..")
            .layer("Interp").definedBy("alang.interp..")
            .layer("Parse").definedBy("alang.parse..")
            .layer("GeneratedParser").definedBy("alang.parser..")
            .layer("Model").definedBy("alang.ast..", "alang.types..")
            .layer("Diag").definedBy("alang.diag..")
            .layer("Util").definedBy("alang.util..")
            .whereLayer("Cli").mayNotBeAccessedByAnyLayer()
            .whereLayer("Check").mayOnlyAccessLayers("Interp", "Model", "Diag", "Util")
            .whereLayer("Cgen").mayOnlyAccessLayers("Interp", "Model", "Diag", "Util")
            .whereLayer("Interp").mayOnlyAccessLayers("Model", "Diag", "Util")
            .whereLayer("Parse").mayOnlyAccessLayers("GeneratedParser", "Model", "Diag", "Util")
            .whereLayer("GeneratedParser").mayOnlyBeAccessedByLayers("Parse")
            .whereLayer("Model").mayOnlyAccessLayers("Diag", "Util")
            .whereLayer("Diag").mayOnlyAccessLayers("Util")
            .whereLayer("Util").mayNotAccessAnyLayer()
            .because("the layers keep the checker, interpreter and C backend independent of each "
                    + "other's internals and of the CLI");

    @ArchTest
    static final ArchRule antlr_stays_in_the_parser = noClasses()
            .that().resideOutsideOfPackages("alang.parse..", "alang.parser..")
            .should().dependOnClassesThat().resideInAPackage("org.antlr..")
            .because("alang.parse turns the parse tree into the AST; work on Ast nodes instead");

    // ---------------------------------------------------------------- I/O only at the boundary

    @ArchTest
    static final ArchRule io_only_in_the_cli = noClasses()
            .that().resideOutsideOfPackage(CLI)
            .should().dependOnClassesThat().resideInAnyPackage("java.nio.file..", "java.nio.channels..", "java.net..")
            .orShould().dependOnClassesThat().belongToAnyOf(File.class, FileDescriptor.class,
                    FileInputStream.class, FileOutputStream.class, FileReader.class, FileWriter.class,
                    RandomAccessFile.class, ProcessBuilder.class, Process.class)
            .because("the checker, interpreter and C backend receive sources and output streams from "
                    + "alang.cli; read or write files there and pass the contents in");

    @ArchTest
    static final ArchRule only_the_cli_exits = noClasses()
            .that().resideOutsideOfPackage(CLI)
            .should().callMethod(System.class, "exit", int.class)
            .because("failures are reported as diagnostics or traps; only alang.cli picks the exit code");

    @ArchTest
    static final ArchRule no_standard_streams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
            .because("System.out and System.err encode with the host charset; write to the UTF-8 "
                    + "PrintStream that alang.cli passes in");

    @ArchTest
    static final ArchRule errors_are_specific = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS
            .because("program errors are Traps or diagnostics with a stable Code; internal bugs use "
                    + "IllegalStateException or AssertionError");

    // ---------------------------------------------------------------- determinism

    @ArchTest
    static final ArchRule no_clock_randomness_or_environment = noClasses()
            .should().dependOnClassesThat(resideInAnyPackage("java.time..").or(assignableTo(RandomGenerator.class)))
            .orShould().callMethodWhere(calls(System.class,
                    "currentTimeMillis|nanoTime|getenv|getProperty|getProperties|identityHashCode"))
            .orShould().callMethodWhere(calls(Math.class, "random"))
            .orShould().callMethodWhere(calls(StrictMath.class, "random"))
            .because("the toolchain must produce the same bytes on every run and machine; take any "
                    + "such input as an explicit argument from alang.cli");

    @ArchTest
    static final ArchRule single_threaded = noClasses()
            .should().dependOnClassesThat(resideInAnyPackage("java.util.concurrent..").or(assignableTo(Thread.class)))
            .because("evaluation order is part of the determinism guarantee; keep the toolchain single-threaded");

    @ArchTest
    static final ArchRule locale_independent_text = noClasses()
            .should().callMethod(String.class, "format", String.class, Object[].class)
            .orShould().callMethod(String.class, "formatted", Object[].class)
            .orShould().callMethod(String.class, "toLowerCase")
            .orShould().callMethod(String.class, "toUpperCase")
            .orShould().callMethod(PrintStream.class, "printf", String.class, Object[].class)
            .orShould().callMethod(PrintStream.class, "format", String.class, Object[].class)
            .orShould().callMethodWhere(calls(Locale.class, "getDefault|setDefault"))
            .orShould().dependOnClassesThat().resideInAPackage("java.text..")
            .because("output must not change with the host locale; pass Locale.ROOT "
                    + "(String.format(Locale.ROOT, ...), s.toLowerCase(Locale.ROOT)) or concatenate");

    /** A call to a method of {@code owner} whose name matches {@code names}. */
    private static DescribedPredicate<JavaCall<?>> calls(Class<?> owner, String names) {
        return target(declaredIn(owner)).and(target(nameMatching(names)))
                .as("target is %s.(%s)", owner.getSimpleName(), names);
    }
}
