package alang.api;

import alang.check.Checker;

/** How a program is compiled. Immutable: each setter returns a new instance. */
public final class CompileOptions {
    private final long maxStackBytes;

    private CompileOptions(long maxStackBytes) {
        this.maxStackBytes = maxStackBytes;
    }

    /** The defaults of `alang check`: a memory budget of 8 MiB. */
    public static CompileOptions defaults() {
        return new CompileOptions(Checker.DEFAULT_MAX_STACK);
    }

    /**
     * The memory budget: a program whose worst-case stack, for any entry point, or whose constants exceed it
     * does not compile (E0402, E0405). 0 means no limit.
     */
    public CompileOptions maxStackBytes(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("maxStackBytes must be 0 (no limit) or more");
        return new CompileOptions(bytes);
    }

    public long maxStackBytes() {
        return maxStackBytes;
    }
}
