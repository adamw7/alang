package alang.cli;

import alang.interp.Meter;
import java.math.BigDecimal;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Stops tool calls that run longer than `--timeout`: a backstop to the step budget, so that a host gets an
 * answer within that time whatever a call does. With {@link McpServer} it is the only code that uses threads
 * or the clock (see ArchitectureTest): one timer thread that calls {@link Meter#stop}. The call itself runs on
 * one thread, as every run does, and a timeout changes only where it ends.
 */
final class Watchdog implements AutoCloseable {
    /** The time limit of `call` and `serve` unless --timeout sets another: well past the default step budget. */
    static final long DEFAULT_MILLIS = 60_000;

    private final long millis;
    private final ScheduledThreadPoolExecutor timer;

    /** A watchdog that stops each call it watches after {@code millis} ms; 0 means never. */
    Watchdog(long millis) {
        this.millis = millis;
        if (millis > 0) {
            timer = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "alang-timeout");
                t.setDaemon(true);
                return t;
            });
            // A call that ends in time takes its alarm with it, so a long session keeps no stale alarms.
            timer.setRemoveOnCancelPolicy(true);
        } else {
            timer = null;
        }
    }

    /** Starts the clock for a call: {@code meter} is stopped when its time is up, unless the alarm is put away first. */
    Alarm watch(Meter meter) {
        if (timer == null) return () -> { };
        String message = "the call ran longer than its time limit of " + BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString()
            + " s (--timeout)";
        ScheduledFuture<?> alarm = timer.schedule(() -> meter.stop("timeout", message), millis, TimeUnit.MILLISECONDS);
        return () -> alarm.cancel(false);
    }

    /** A call's alarm; {@link #off} once the call has ended. */
    interface Alarm {
        void off();
    }

    @Override
    public void close() {
        if (timer != null) timer.shutdownNow();
    }
}
