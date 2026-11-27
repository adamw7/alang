package alang.api;

import alang.util.Json;
import java.util.List;
import java.util.Map;

/** Adapts a {@link Host} of the API to the interpreter's, which passes parsed JSON. */
final class HostBridge {
    private HostBridge() {}

    static alang.interp.Host of(Host h) {
        if (h == null) return alang.interp.Host.NONE;
        return (name, args) -> {
            String answer;
            try {
                answer = h.answer(name, Json.write(args));
            } catch (RuntimeException e) {
                // The host's failure, or a bug in it, fails the one call, as a trap the model can read, not the server.
                throw new alang.interp.Host.Failure(e.getMessage() != null ? e.getMessage() : e.toString());
            }
            if (answer == null) throw new alang.interp.Host.Failure("the host answered null, not JSON");
            try {
                return Json.parse(answer);
            } catch (Json.ParseException e) {
                throw new alang.interp.Host.Failure("the host's answer is not JSON: " + e.getMessage());
            }
        };
    }

    static Host replay(String recordingJson) {
        Object j;
        try {
            j = Json.parse(recordingJson);
        } catch (Json.ParseException e) {
            throw new IllegalArgumentException("the recording is not JSON: " + e.getMessage(), e);
        }
        List<?> calls;
        if (j instanceof Map<?, ?> m && m.get("host_calls") instanceof List<?> l) calls = l;
        else if (j instanceof Map<?, ?>) calls = List.of();
        else if (j instanceof List<?> l) calls = l;
        else throw new IllegalArgumentException("the recording is neither a report nor a list of host calls");
        alang.interp.Host inner = alang.interp.Host.replay(calls);
        return (function, argumentsJson) -> {
            try {
                return Json.write(inner.call(function, castArgs(Json.parse(argumentsJson))));
            } catch (alang.interp.Host.Failure e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castArgs(Object parsed) {
        return (Map<String, Object>) parsed;
    }
}
