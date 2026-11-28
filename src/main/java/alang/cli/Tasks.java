package alang.cli;

import alang.interp.Meter;
import alang.util.Json;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * The tasks of one MCP session, for the Tasks extension ({@value #EXTENSION}): calls still running when their
 * request had to be answered, which the client polls with tasks/get and stops with tasks/cancel. A task holds
 * its call's answer once it ends, for at least {@link #KEEP_MILLIS} and for its ttlMs from its creation; then
 * it is forgotten, as are the oldest finished ones beyond {@link #MAX_FINISHED}.
 *
 * <p>It reads the clock, for the timestamps a task carries and to know when to forget one; no clock reaches a
 * program (see ArchitectureTest). Its lock is taken before a task's, never after.
 */
final class Tasks {
    /** The extension's name, in the client's capabilities and in this server's. */
    static final String EXTENSION = "io.modelcontextprotocol/tasks";

    /** How often a client should ask about a running task: calls end within --timeout, a minute by default. */
    static final long POLL_MILLIS = 2000;

    /** How long a finished task is kept, at least. */
    static final long KEEP_MILLIS = 3_600_000;

    /** The finished tasks kept at most, so that a client that never asks cannot fill the heap. */
    static final int MAX_FINISHED = 1024;

    /** The wall clock, in ms since 1970. */
    static final LongSupplier SYSTEM_CLOCK = System::currentTimeMillis;

    private final LongSupplier clock;
    /** By id, oldest first. */
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private long made;

    Tasks(LongSupplier clock) {
        this.clock = clock;
    }

    /** A new running task for the call {@code meter} measures; {@code ttl} ms from now, or null for no limit. */
    synchronized Task add(Meter meter, Long ttl) {
        forget();
        made++;
        Task t = new Task("task-" + made, meter, clock.getAsLong(), ttl);
        tasks.put(t.id, t);
        return t;
    }

    /** The task with this id, or null: unknown, or forgotten. */
    synchronized Task get(String id) {
        forget();
        return tasks.get(id);
    }

    /** Whether a task is still running. */
    synchronized boolean running() {
        for (Task t : tasks.values()) {
            if (t.working()) return true;
        }
        return false;
    }

    /** Stops every running task, e.g. when the session ends. */
    synchronized void stopAll(String why) {
        for (Task t : tasks.values()) t.cancel(why);
    }

    /** Drops the tasks whose time is up, then the oldest finished ones beyond {@link #MAX_FINISHED}. */
    private void forget() {
        long now = clock.getAsLong();
        tasks.values().removeIf(t -> t.expired(now));
        int finished = 0;
        for (Task t : tasks.values()) {
            if (!t.working()) finished++;
        }
        for (Iterator<Task> it = tasks.values().iterator(); finished > MAX_FINISHED && it.hasNext(); ) {
            if (!it.next().working()) {
                it.remove();
                finished--;
            }
        }
    }

    /** One call run as a task: working until it completes, fails or is cancelled. */
    final class Task {
        final String id;
        private final Meter meter;
        private final long created;
        private final Long ttl;
        private String status = "working";
        private String message;
        private long updated;
        private long finished;
        private Object result;
        private Object error;

        private Task(String id, Meter meter, long created, Long ttl) {
            this.id = id;
            this.meter = meter;
            this.created = created;
            this.updated = created;
            this.ttl = ttl;
        }

        synchronized boolean working() {
            return status.equals("working");
        }

        /**
         * The call ended with {@code answer}, the JSON-RPC message it would have been answered with: its result
         * completes the task and its error fails it; null means it was cancelled. A cancelled task stays so.
         */
        synchronized void finish(Map<String, Object> answer) {
            if (!working()) return;
            if (answer == null) {
                end("cancelled", "the call was cancelled");
            } else if (answer.containsKey("error")) {
                error = answer.get("error");
                end("failed", null);
            } else {
                result = answer.get("result");
                end("completed", null);
            }
        }

        /** Stops the call, if it is still running: the task is cancelled now, and its call ends soon after. */
        synchronized void cancel(String why) {
            if (!working()) return;
            end("cancelled", why);
            meter.stop("cancelled", why);
        }

        private void end(String status, String message) {
            this.status = status;
            this.message = message;
            this.updated = clock.getAsLong();
            this.finished = updated;
        }

        private synchronized boolean expired(long now) {
            return !working() && now - finished >= KEEP_MILLIS && (ttl == null || now - created >= ttl);
        }

        /**
         * The task as the extension writes it, with {@code resultType} "task" in the answer to tools/call and
         * "complete" in the answers to tasks/get and tasks/cancel.
         */
        synchronized Map<String, Object> view(String resultType) {
            Map<String, Object> v = Json.obj("resultType", resultType, "taskId", id, "status", status);
            if (message != null) v.put("statusMessage", message);
            v.put("createdAt", Instant.ofEpochMilli(created).toString());
            v.put("lastUpdatedAt", Instant.ofEpochMilli(updated).toString());
            v.put("ttlMs", ttl);
            if (working()) v.put("pollIntervalMs", POLL_MILLIS);
            if (result != null) v.put("result", result);
            if (error != null) v.put("error", error);
            return v;
        }
    }
}
