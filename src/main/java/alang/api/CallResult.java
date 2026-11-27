package alang.api;

import alang.util.Json;
import java.util.List;
import java.util.Map;

/**
 * The outcome of a tool call: its result, or the error that ended it, with what it printed and its cost.
 * {@link #toJson} is the report `alang call` prints, ready to hand back to a model as a tool result.
 */
public final class CallResult {
    private final Map<String, Object> report;

    CallResult(Map<String, Object> report) {
        this.report = report;
    }

    /** Whether the call returned: no trap, no bad arguments, no time limit or cancellation. */
    public boolean ok() {
        return report.get("ok") == Boolean.TRUE;
    }

    /** The result as JSON, or null when the call failed or the tool returns nothing. */
    public String resultJson() {
        return ok() && report.get("result") != null ? Json.write(report.get("result")) : null;
    }

    /**
     * Why the call failed, or null when it did not: a trap code such as "R0006" or R0011 for the step budget
     * (`alang explain R0006`), or "bad_tool", "bad_json", "bad_arguments", "bad_result", "timeout" or "cancelled".
     */
    public String errorCode() {
        return report.get("error") instanceof Map<?, ?> e ? String.valueOf(e.get("code")) : null;
    }

    /** The error's message, or null when the call did not fail. */
    public String errorMessage() {
        return report.get("error") instanceof Map<?, ?> e && e.get("message") != null ? String.valueOf(e.get("message")) : null;
    }

    /** What the call printed (the first 1 MiB of it). */
    public String stdout() {
        return report.get("stdout") instanceof String s ? s : "";
    }

    /** The call's cost in steps: the statements it ran and the data they went through. */
    public long steps() {
        return report.get("steps") instanceof Number n ? n.longValue() : 0;
    }

    /**
     * The calls of host fns the call made, as a JSON array of {"fn": name, "args": {...}, "result": answer} ("error"
     * in place of "result" for one with no answer), in order: "[]" when it made none. {@link Host#replay} answers them
     * again.
     */
    public String hostCallsJson() {
        return Json.write(report.get("host_calls") instanceof List<?> l ? l : List.of());
    }

    /** The whole report as JSON: "ok", then "result" or "error", "host_calls" if any, "stdout" and "steps". */
    public String toJson() {
        return Json.write(report);
    }

    @Override
    public String toString() {
        return toJson();
    }
}
