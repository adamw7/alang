package alang.api;

/**
 * Answers the calls of a program's `host fn`s, the one way a tool reaches outside data. Each call passes the host
 * fn's name and its arguments, a JSON object with one entry per parameter; the answer is the JSON of the host fn's
 * result type (null for one without a result), which the call checks against that type. A tool call records every
 * question and answer ({@link CallResult#hostCallsJson}), and {@link #replay} answers them again, so a call can be
 * repeated exactly.
 *
 * <p>Several tool calls may ask one host at once, from threads of their own.
 */
@FunctionalInterface
public interface Host {
    /**
     * The JSON of the result of host fn {@code function} called with {@code argumentsJson}. To fail the call, throw an
     * exception: the tool call then ends with trap R0015 and the exception's message, which the model reads.
     */
    String answer(String function, String argumentsJson);

    /**
     * A host that answers from a recording: the "host_calls" of a report ({@link CallResult#toJson}), or the array
     * {@link CallResult#hostCallsJson} gives. Each call must be the next one recorded, with the same name and
     * arguments. It keeps its place, so use one for one tool call.
     *
     * @throws IllegalArgumentException if {@code recordingJson} is neither
     */
    static Host replay(String recordingJson) {
        return HostBridge.replay(recordingJson);
    }
}
