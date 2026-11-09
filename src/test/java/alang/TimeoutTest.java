package alang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * CI stays fast because every test, and the whole run, has a time limit: JUnit's default timeout
 * for test methods, {@link Harness#dynamicTest} for dynamic tests, and Surefire's budget for the
 * whole run (pom.xml). These checks keep those limits from being dropped by accident.
 */
class TimeoutTest {

    @Test
    void everyTestMethodHasAPreemptiveTimeout() throws IOException {
        Properties junit = new Properties();
        try (InputStream in = TimeoutTest.class.getResourceAsStream("/junit-platform.properties")) {
            assertNotNull(in, "src/test/resources/junit-platform.properties is missing");
            junit.load(in);
        }
        assertEquals(Harness.TEST_TIMEOUT.toSeconds() + " s", junit.getProperty("junit.jupiter.execution.timeout.default"),
            "test methods and dynamic tests should share one limit, Harness.TEST_TIMEOUT");
        assertEquals("enabled", junit.getProperty("junit.jupiter.execution.timeout.mode"));
        assertEquals("SEPARATE_THREAD", junit.getProperty("junit.jupiter.execution.timeout.thread.mode.default"),
            "only a test on its own thread can be stopped when it ignores interrupts");
    }

    @Test
    void dynamicTestsStopOnTimeEvenWhenTheyIgnoreInterrupts() {
        AtomicBoolean done = new AtomicBoolean();
        DynamicTest spin = Harness.dynamicTest("spin", Duration.ofMillis(100), () -> {
            while (!done.get()) {
                Thread.onSpinWait();
            }
        });
        try {
            AssertionFailedError e = assertThrows(AssertionFailedError.class, spin.getExecutable()::execute);
            assertTrue(e.getMessage().startsWith("spin ==> execution timed out after 100 ms"), e.getMessage());
        } finally {
            done.set(true);  // release the abandoned thread
        }
    }

    @Test
    void theWholeRunHasALimit() {
        String pom = Harness.read(Harness.ROOT.resolve("pom.xml"));
        assertTrue(pom.contains("<forkedProcessTimeoutInSeconds>${surefire.timeout}</forkedProcessTimeoutInSeconds>"),
            "Surefire must fail a test run that takes too long");
        assertTrue(pom.matches("(?s).*<surefire\\.timeout>[1-9][0-9]*</surefire\\.timeout>.*"),
            "surefire.timeout must default to a positive number of seconds");
    }
}
