package alang.interp;

import alang.ast.Ast.Stmt;
import java.util.List;

/**
 * What `alang debug` attaches to an {@link Interpreter}: it is told of each statement before it runs, and of each
 * trap before the stack unwinds, and may stop the run there for as long as it likes, reading its {@link Frame}s.
 * It runs on the run's own thread, so a run is still one thread, and it changes nothing the run computes.
 */
public interface Debugger {
    /**
     * Before statement {@code s} runs. {@code lineStart} is false for a statement that follows another of its
     * block on the same line, such as the second of `a; b;`. {@code top} is the frame it runs in, and {@code depth}
     * the number of frames; {@link Interpreter#frames()} has them all.
     */
    void statement(Stmt s, boolean lineStart, Frame top, int depth);

    /** Where trap {@code t} happened, before the stack unwinds; the run then ends with it. */
    void trap(Trap t, List<Frame> stack);
}
