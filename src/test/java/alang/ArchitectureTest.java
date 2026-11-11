package alang;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.AccessTarget.Predicates.declaredIn;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Architecture rules for the toolchain, checked by ArchUnit on every {@code mvn test}.
 *
 * <p>The first rules fix the package layering and who may change the model. The rest turn the
 * README's promises about the toolchain itself into checks: the same bytes out on every run,
 * machine and locale, and I/O only at the command-line boundary. Every rule says why it exists and
 * how to comply. The rules for the test code are in {@link TestConventionsArchitectureTest}.
 */
@AnalyzeClasses(packages = "alang", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String CLI = "alang.cli..";
    private static final String MAIN = "alang.cli.Main";
    private static final String GENERATED_PARSER = "alang.parser..";

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
            .layer("GeneratedParser").definedBy(GENERATED_PARSER)
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
            .that().resideOutsideOfPackages("alang.parse..", GENERATED_PARSER)
            .should().dependOnClassesThat().resideInAPackage("org.antlr..")
            .because("alang.parse turns the parse tree into the AST; work on Ast nodes instead");

    @ArchTest
    static final ArchRule only_the_jdk_and_antlr = noClasses()
            .should().dependOnClassesThat().resideOutsideOfPackages("java..", "alang..", "org.antlr.v4.runtime..")
            .because("target/alang.jar shades in every dependency, and the toolchain needs only the JDK and "
                    + "the ANTLR runtime; a new library is a decision for the pom, not a side effect of an import");

    @ArchTest
    static final ArchRule entry_point_is_cli_main = methods()
            .that().haveName("main").and().arePublic().and().areStatic()
            .should().beDeclaredInClassesThat().haveFullyQualifiedName(MAIN)
            .because("the jar's manifest and bin/alang start alang.cli.Main; a main method anywhere else "
                    + "is unreachable");

    // ---------------------------------------------------------------- the model

    /**
     * The parser builds the AST and the checker annotates it once: types, resolved names, constant
     * values. Everything after that reads those annotations, so the same checked program can be run,
     * tested and compiled any number of times with the same result.
     */
    @ArchTest
    static final ArchRule only_the_parser_and_checker_write_the_ast = noClasses()
            .that().resideOutsideOfPackages("alang.ast..", "alang.parse..", "alang.check..")
            .should().setFieldWhere(target(declaredIn(resideInAPackage("alang.ast.."))))
            .because("the interpreter, C backend and CLI read the checker's annotations; keep run-time "
                    + "state in the interpreter, not on the AST");

    @ArchTest
    static final ArchRule types_and_diagnostics_are_values = fields()
            .that().areDeclaredInClassesThat().resideInAnyPackage("alang.types..", "alang.diag..")
            .should().beFinal()
            .because("a Type is shared by every node the checker annotates with it, and a Diagnostic is "
                    + "what --json reports; build a new one instead of changing it");

    @ArchTest
    static final ArchRule no_mutable_static_state = fields()
            .that().areStatic()
            .and().areDeclaredInClassesThat().resideOutsideOfPackage(GENERATED_PARSER)
            .should().beFinal()
            .because("one JVM checks, runs and tests many programs (`alang test`, the test suites), so a "
                    + "static field that changes would carry one run into the next; pass state explicitly. "
                    + "The ANTLR-generated parser is exempt");

    @ArchTest
    static final ArchRule no_optional_fields = noFields()
            .should().haveRawType(Optional.class)
            .because("Optional models a possibly-absent return value, not a field; a field holds the value "
                    + "itself");

    // ---------------------------------------------------------------- I/O and errors only at the boundary

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
    static final ArchRule only_main_exits_or_reads_stdin = noClasses()
            .that().doNotHaveFullyQualifiedName(MAIN)
            .should().callMethod(System.class, "exit", int.class)
            .orShould().accessField(System.class, "in")
            .because("failures are reported as diagnostics or traps and input arrives as arguments; only "
                    + "alang.cli.Main owns the process, while Frontend and the rest run in-process under the "
                    + "test suites");

    @ArchTest
    static final ArchRule no_standard_streams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
            .because("System.out and System.err encode with the host charset; write to the UTF-8 "
                    + "PrintStream that alang.cli passes in");

    @ArchTest
    static final ArchRule no_stack_traces = noClasses()
            // Matched on any Throwable subtype: callMethod(Throwable.class, ...) would only match calls
            // whose receiver is declared as Throwable itself, missing e.printStackTrace() on an IOException.
            .should().callMethodWhere(target(declaredIn(assignableTo(Throwable.class)))
                    .and(target(nameMatching("printStackTrace|getStackTrace")))
                    .as("target is <a Throwable>.(printStackTrace|getStackTrace)"))
            .because("a stack trace is not a report: say what went wrong with a diagnostic or trap that "
                    + "has a stable Code");

    @ArchTest
    static final ArchRule no_logging = noClasses()
            .should().dependOnClassesThat().resideInAPackage("java.util.logging..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.System$Logger")
            .because("the toolchain's only output is what alang.cli writes; a logger adds timestamps and "
                    + "destinations chosen by the host");

    @ArchTest
    static final ArchRule errors_are_specific = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS
            .because("program errors are Traps or diagnostics with a stable Code; internal bugs use "
                    + "IllegalStateException or AssertionError");

    @ArchTest
    static final ArchRule checked_exceptions_stay_in_the_cli = noCodeUnits()
            .that().areDeclaredInClassesThat().resideOutsideOfPackage(CLI)
            .should().declareThrowableOfType(assignableTo(Exception.class)
                    .and(not(assignableTo(RuntimeException.class))).as("a checked exception"))
            .because("the core does no I/O and reports problems as Traps or diagnostics; an IOException "
                    + "from the stream alang.cli passed in is handled where the write happens");

    @ArchTest
    static final ArchRule exceptions_are_named_consistently = classes()
            .that().haveSimpleNameEndingWith("Exception")
            .should().beAssignableTo(Exception.class)
            .because("a type named *Exception must be one");

    // ---------------------------------------------------------------- determinism

    @ArchTest
    static final ArchRule no_clock_randomness_or_environment = noClasses()
            .should().dependOnClassesThat(resideInAnyPackage("java.time..")
                    .or(assignableTo(RandomGenerator.class))
                    .or(assignableTo(Date.class)).or(assignableTo(Calendar.class)).or(assignableTo(TimeZone.class)))
            .orShould().callMethodWhere(calls(System.class,
                    "currentTimeMillis|nanoTime|getenv|getProperty|getProperties|identityHashCode"))
            .orShould().callMethodWhere(calls(Math.class, "random"))
            .orShould().callMethodWhere(calls(StrictMath.class, "random"))
            .orShould().callMethodWhere(calls(UUID.class, "randomUUID"))
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

    @ArchTest
    static final ArchRule charset_independent_text = noClasses()
            .should().callMethod(String.class, "getBytes")
            .orShould().callConstructor(String.class, byte[].class)
            .orShould().callConstructor(String.class, byte[].class, int.class, int.class)
            .orShould().callMethod(ByteArrayOutputStream.class, "toString")
            .orShould().callConstructor(PrintStream.class, OutputStream.class)
            .orShould().callConstructor(PrintStream.class, OutputStream.class, boolean.class)
            .orShould().callConstructor(PrintWriter.class, OutputStream.class)
            .orShould().callConstructor(PrintWriter.class, OutputStream.class, boolean.class)
            .orShould().callConstructor(InputStreamReader.class, InputStream.class)
            .orShould().callConstructor(OutputStreamWriter.class, OutputStream.class)
            .orShould().callMethod(Charset.class, "defaultCharset")
            .because("bytes must not change with the host's default charset; name StandardCharsets.UTF_8 "
                    + "(s.getBytes(UTF_8), new String(bytes, UTF_8), new PrintStream(out, autoFlush, UTF_8))");

    @ArchTest
    static final ArchRule floats_are_correctly_rounded = noClasses()
            .should().callMethodWhere(calls(Math.class,
                    "sin|cos|tan|asin|acos|atan|atan2|sinh|cosh|tanh|exp|expm1|log|log10|log1p|pow|cbrt|hypot"))
            .orShould().dependOnClassesThat().belongToAnyOf(StrictMath.class)
            .because("float results are bit-exact between the interpreter and the C backend, so ALang has "
                    + "only correctly rounded operations (+ - * /, sqrt, floor, ceil, abs, min, max, "
                    + "conversions, and round and trunc built from them); the transcendental functions may differ in the last bit between JVMs "
                    + "and C libraries");

    @ArchTest
    static final ArchRule no_jvm_limits_or_gc = noClasses()
            .should().dependOnClassesThat(resideInAPackage("java.lang.ref..")
                    .or(belongToAnyOf(Runtime.class))
                    .or(assignableTo(VirtualMachineError.class)))
            .because("a program's limits are the step budget and the checker's memory bound, the same "
                    + "everywhere; heap size, stack depth and GC timing differ between machines, so never "
                    + "read them, catch StackOverflowError or OutOfMemoryError, or wait for a reference");

    @ArchTest
    static final ArchRule no_reflection = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage("java.lang.reflect..", "java.lang.invoke..")
            .orShould().callMethodWhere(calls(Class.class, "forName"))
            .because("reflection lists members in an unspecified order and bypasses the exhaustive switches "
                    + "over the sealed AST and type hierarchies; dispatch with a switch instead");

    /** An access to a member of {@code owner} whose name matches {@code names}. */
    private static DescribedPredicate<JavaAccess<?>> calls(Class<?> owner, String names) {
        return target(declaredIn(owner)).and(target(nameMatching(names)))
                .as("target is %s.(%s)", owner.getSimpleName(), names);
    }
}
