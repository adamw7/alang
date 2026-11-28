package alang.interp;

import static org.junit.jupiter.api.Assertions.*;

import alang.diag.*;
import org.junit.jupiter.api.Test;

/** The step budget: how work is counted, where an exhausted budget traps, and how a host stops a run. */
class MeterTest {

    private static final Source SRC = new Source("m.al", "a\nb\nc\n");

    private static Span at(int line) {
        return new Span(line, 1, 1, SRC);
    }

    @Test
    void each_step_counts_and_the_one_past_the_budget_traps_where_it_is() {
        Meter m = new Meter(3);
        m.step(at(1));
        m.step(at(2));
        m.step(at(3));
        assertEquals(3, m.steps());
        Trap t = assertThrows(Trap.class, () -> m.step(at(2)));
        assertEquals(Code.R0011, t.code);
        assertEquals(at(2), t.span);
        assertTrue(t.getMessage().contains("3 steps"), t.getMessage());
    }

    @Test
    void zero_means_no_budget() {
        Meter m = new Meter(0);
        for (int i = 0; i < 100_000; i++) m.step(at(1));
        m.units(Long.MAX_VALUE / 2);
        assertEquals(100_000 + Long.MAX_VALUE / 2 / Meter.UNITS_PER_STEP, m.steps());
    }

    @Test
    void units_add_up_to_steps_and_keep_the_remainder() {
        Meter m = new Meter(0);
        m.units(Meter.UNITS_PER_STEP - 1);
        assertEquals(0, m.steps());
        m.units(1);
        assertEquals(1, m.steps());
        m.bytes(3 * Meter.UNITS_PER_STEP + 5);
        assertEquals(4, m.steps());
        m.slots(Meter.UNITS_PER_STEP - 5);
        assertEquals(5, m.steps());
        // An element weighs ELEMENT units: UNITS_PER_STEP / ELEMENT of them make a step.
        m.elements(Meter.UNITS_PER_STEP / Meter.ELEMENT);
        assertEquals(6, m.steps());
    }

    @Test
    void work_on_data_traps_at_the_statement_that_does_it() {
        Meter m = new Meter(2);
        m.step(at(1));
        m.at(at(3));
        m.units(Meter.UNITS_PER_STEP);
        assertEquals(2, m.steps());
        Trap t = assertThrows(Trap.class, () -> m.bytes(Meter.UNITS_PER_STEP));
        assertEquals(Code.R0011, t.code);
        assertEquals(at(3), t.span);
        assertEquals(at(3), m.at());
    }

    @Test
    void held_work_is_counted_but_traps_only_on_release() {
        Meter m = new Meter(1);
        m.step(at(1));
        m.hold();
        m.bytes(10 * Meter.UNITS_PER_STEP);
        assertEquals(11, m.steps());
        assertEquals(Code.R0011, assertThrows(Trap.class, m::release).code);
    }

    @Test
    void stop_ends_the_run_at_its_next_step_and_keeps_the_first_reason() {
        Meter m = new Meter(0);
        m.step(at(1));
        m.stop("timeout", "ran out of time");
        m.stop("cancelled", "no longer wanted");
        Stopped s = assertThrows(Stopped.class, () -> m.step(at(2)));
        assertEquals("timeout", s.code);
        assertEquals("ran out of time", s.getMessage());
        // It stays stopped: work on data stops it too.
        assertThrows(Stopped.class, () -> m.units(Meter.UNITS_PER_STEP));
    }

    @Test
    void a_stop_from_another_thread_is_seen() throws Exception {
        Meter m = new Meter(0);
        Thread t = new Thread(() -> m.stop("cancelled", "bye"));
        t.start();
        t.join(10_000);
        assertFalse(t.isAlive());
        assertEquals("cancelled", assertThrows(Stopped.class, () -> m.step(at(1))).code);
    }

    @Test
    void writing_a_float_costs_more_the_further_its_exponent_is_from_zero() {
        assertEquals(Meter.INT_TEXT, Meter.floatText(0.0));
        assertEquals(Meter.INT_TEXT, Meter.floatText(-0.0));
        assertEquals(Meter.INT_TEXT, Meter.floatText(Double.NaN));
        assertEquals(Meter.INT_TEXT, Meter.floatText(Double.NEGATIVE_INFINITY));
        assertEquals(2816, Meter.floatText(1.0));
        assertEquals(Meter.floatText(1e100), Meter.floatText(-1e100));
        assertTrue(Meter.floatText(1e300) > Meter.floatText(1e10));
        assertTrue(Meter.floatText(Double.MIN_VALUE) > Meter.floatText(1e-10));
        assertEquals(2816 + 22L * 1023, Meter.floatText(Double.MAX_VALUE));
    }
}
