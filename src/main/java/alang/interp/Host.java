package alang.interp;

import alang.util.Json;
import java.util.*;

/**
 * Answers the calls of a program's `host fn`s: the one way an ALang run reaches the world. A call sends the host fn's
 * name and its arguments, as a JSON object of the parameters, and gets back the JSON of its result, which the
 * interpreter checks against the host fn's type. A run records every call it makes ({@link Interpreter#hostCalls}), so
 * the same arguments and the same replies give the same run: {@link #replay} answers from such a record.
 */
@FunctionalInterface
public interface Host {
    /**
     * The reply to a call: parsed JSON ({@link Json#parse}) of the host fn's result type, or null for one without a
     * result.
     *
     * @param name the host fn
     * @param args the arguments, parsed JSON: an object with one entry per parameter, in order
     * @throws Failure when there is no reply; the call traps with R0015 and the message
     */
    Object call(String name, Map<String, Object> args);

    /** A call that got no reply. */
    final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Failure(String message) {
            super(message);
        }
    }

    /** A host that answers no call: the default, where nothing provides one. */
    Host NONE = (name, args) -> {
        throw new Failure("nothing answers host fn `" + name + "` here: in a test, bind it with `test \"...\" with " + name
            + " = some_fn { }`; for run, call or serve, pass --hosts FILE or --replay FILE");
    };

    /**
     * A host that answers from {@code calls}, the "host_calls" a run recorded: each call must be the next one recorded,
     * with the same name and arguments, and gets its reply (or its recorded failure). Not thread-safe: one per run.
     */
    static Host replay(List<?> calls) {
        int[] next = {0};
        return (name, args) -> {
            int i = next[0]++;
            String call = name + "(" + Json.write(args) + ")";
            if (i >= calls.size()) {
                throw new Failure("the recording has " + calls.size() + " call(s), and this run makes more: call " + (i + 1)
                    + " is " + call);
            }
            if (!(calls.get(i) instanceof Map<?, ?> rec) || !(rec.get("fn") instanceof String fn)) {
                throw new Failure("call " + (i + 1) + " of the recording is not {\"fn\": ..., \"args\": ..., \"result\": ...}");
            }
            String recorded = fn + "(" + Json.write(rec.get("args")) + ")";
            if (!recorded.equals(call)) {
                throw new Failure("call " + (i + 1) + " is " + call + ", but the recording has " + recorded);
            }
            if (rec.get("error") instanceof String error) throw new Failure(error);
            return rec.get("result");
        };
    }
}
