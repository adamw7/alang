package alang.interp;

import alang.ast.Ast.*;
import alang.ast.Builtin;
import alang.diag.*;
import alang.interp.Values.*;
import alang.types.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A tree-walking reference interpreter. It implements ALang semantics exactly: value
 * semantics for aggregates, checked arithmetic, bounds checks, contracts, loop limits.
 */
public final class Interpreter {
    public static final long DEFAULT_MAX_STEPS = 100_000_000L;

    private static final int NORMAL = 0;
    private static final int BREAK = 1;
    private static final int CONTINUE = 2;
    private static final int RETURN = 3;

    private final Source source;
    private final OutputStream out;
    /** The run's cost and budget: every statement, and all work on data, is charged to it. */
    private final Meter meter;
    private Object retVal;

    /** The call stack, innermost first: the routine of each frame, its slots, and with a debugger where it is. */
    private final Deque<Frame> callStack = new ArrayDeque<>();

    /** Told of each statement and trap; null when no debugger is attached, as for every run but `alang debug`. */
    private Debugger debugger;
    /** The trap the debugger was last told of, which every statement it unwinds through rethrows. */
    private Trap reported;

    /** print() formats into this fixed buffer, which goes to {@link #out} whenever it fills up. */
    private final class PrintSink implements ByteSink {
        private final byte[] buf = new byte[8192];
        private int len;

        @Override
        public void write(int b) {
            if (len == buf.length) drain();
            buf[len++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int n) {
            while (n > 0) {
                if (len == buf.length) drain();
                int k = Math.min(n, buf.length - len);
                System.arraycopy(b, off, buf, len, k);
                len += k;
                off += k;
                n -= k;
            }
        }

        void drain() {
            try {
                out.write(buf, 0, len);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            len = 0;
        }
    }

    private final PrintSink printSink = new PrintSink();

    /**
     * append() formats into the target's spare capacity. Bytes past the capacity are only counted, so
     * an append that does not fit is reported with its full length without its text being built.
     */
    private static final class StrSink implements ByteSink {
        private final byte[] dest;
        private int pos;
        long count;

        StrSink(byte[] dest, int start) {
            this.dest = dest;
            this.pos = start;
        }

        @Override
        public void write(int b) {
            if (pos < dest.length) dest[pos++] = (byte) b;
            count++;
        }

        @Override
        public void write(byte[] b, int off, int n) {
            int k = Math.min(n, dest.length - pos);
            if (k > 0) {
                System.arraycopy(b, off, dest, pos, k);
                pos += k;
            }
            count += n;
        }
    }

    public Interpreter(Source source, OutputStream out, long maxSteps) {
        this(source, out, new Meter(maxSteps));
    }

    /** An interpreter whose run is charged to {@code meter}, which the host can also stop it with. */
    public Interpreter(Source source, OutputStream out, Meter meter) {
        this.source = source;
        this.out = out;
        this.meter = meter;
    }

    /**
     * Attaches a debugger, which is told of each statement before it runs and of each trap before the stack
     * unwinds. It reads the run's frames and does not change them: the run computes the same values and steps.
     */
    public void attach(Debugger d) {
        this.debugger = d;
    }

    /** Writes out what print() has formatted so far: a debugger calls it before it shows where the run stopped. */
    public void flushOutput() {
        printSink.drain();
        flush();
    }

    /**
     * Evaluates {@code e}, checked for a debugger, in a copy of {@code slots}: the frame of a stopped run, which it
     * cannot change. It has a budget of its own and writes what it prints to {@code out}.
     */
    public static Object evaluate(Source source, Expr e, Object[] slots, long maxSteps, OutputStream out) {
        Interpreter in = new Interpreter(source, out, maxSteps);
        in.callStack.push(new Frame("debugger", e.span, null, slots));
        try {
            return in.owned(e, slots);
        } finally {
            in.flushOutput();
        }
    }

    /** Evaluates a constant expression (the checker guarantees it needs no frame). */
    public static Object evalConst(Expr e) {
        Interpreter in = new Interpreter(null, OutputStream.nullOutputStream(), 0);
        return in.owned(e, null);
    }

    /** What the run has cost so far, in steps (see {@link Meter}). */
    public long steps() {
        return meter.steps();
    }

    // =================================================================== entry points

    /** Calls a function with already-converted argument values. */
    public Object call(FnDecl f, Object[] args, Span site) {
        meter.at(site);
        try {
            return invoke(f, args, site);
        } catch (Trap t) {
            fillTrace(t);
            throw t;
        } finally {
            flush();
        }
    }

    /**
     * Runs fn main. A main that takes a str receives {@code input}, the program's standard input; input
     * longer than its capacity traps (R0004) before main starts.
     */
    public void runMain(FnDecl main, byte[] input) {
        Object[] args = new Object[main.paramVars.size()];
        if (args.length == 1) {
            LocalVar p = main.paramVars.get(0);
            int cap = ((Type.Str) p.type).cap();
            if (input.length > cap) {
                Trap t = new Trap(Code.R0004, p.span, "standard input exceeds str capacity " + cap + " of `" + p.name + "`");
                callStack.push(new Frame("fn main", main.nameSpan, main, new Object[main.locals.size()]));
                fillTrace(t);
                throw t;
            }
            args[0] = new StrV(Arrays.copyOf(input, cap), input.length);
        }
        call(main, args, main.nameSpan);
    }

    /**
     * Runs a test; a failure is a trap. A test written `traps R0006` passes only if its body stops with that
     * trap: a body that runs to its end, or stops with another trap, fails with R0005, as a failed assert does.
     */
    public void runTest(TestDecl t) {
        String entry = "test \"" + t.name + "\"";
        Trap got = null;
        try {
            Object[] slots = new Object[t.locals.size()];
            callStack.push(new Frame(entry, t.span, t, slots));
            execBlock(t.body, slots);
            callStack.pop();
        } catch (Trap trap) {
            fillTrace(trap);
            got = trap;
        } finally {
            flush();
        }
        Code want = t.expectedTrap;
        if (want == null) {
            if (got != null) throw got;
            return;
        }
        String expected = "expected trap " + want.name() + " (" + want.title + ")";
        if (got == null) {
            Trap missing = new Trap(Code.R0005, t.trapSpan, expected + ", but the test ran to its end without a trap");
            missing.trace.add(entry + " (entry point, line " + t.span.line() + ")");
            throw missing;
        }
        if (got.code != want) {
            java.io.ByteArrayOutputStream msg = new java.io.ByteArrayOutputStream();
            msg.writeBytes((expected + ", but got " + got.code.name() + " (" + got.code.title + "): ").getBytes(StandardCharsets.UTF_8));
            msg.writeBytes(got.messageBytes());
            Trap wrong = new Trap(Code.R0005, got.span, msg.toByteArray());
            wrong.trace.addAll(got.trace);
            throw wrong;
        }
    }

    private void fillTrace(Trap t) {
        if (!t.trace.isEmpty()) return;
        int i = 0;
        for (Frame c : callStack) {
            boolean outermost = ++i == callStack.size();
            // A call in an imported file names the file; the entry point is always in the main one.
            String where = outermost ? " (entry point, line " + c.site().line() + ")"
                : " (called at " + c.site().where(source) + ")";
            t.trace.add(c.name() + where);
        }
        callStack.clear();
    }

    private void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Counts a step, also without a budget (`--max-steps 0`), so that `run --json` reports the real count. */
    private void step(Span at) {
        meter.step(at);
    }

    private String text(Span s) {
        Source file = s.file() != null ? s.file() : source;
        String t = file == null ? "" : file.text(s);
        return t.isEmpty() ? "?" : t;
    }

    // =================================================================== functions

    private Object invoke(FnDecl f, Object[] args, Span site) {
        Object[] slots = new Object[f.locals.size()];
        System.arraycopy(args, 0, slots, 0, args.length);
        callStack.push(new Frame("fn " + f.name, site, f, slots));
        for (Expr r : f.requires) {
            if (!(Boolean) eval(r, slots)) {
                throw new Trap(Code.R0006, site, "precondition of `" + f.name + "` failed: requires " + text(r.span));
            }
        }
        retVal = null;
        try {
            execBlock(f.body, slots);
        } catch (Escape e) {
            // `e?` met an Option.None or a Result.Err: the function returns it.
            retVal = e.value;
        }
        Object result = retVal == null ? null : Values.coerce(retVal, f.ret, meter);
        retVal = null;
        if (f.resultVar != null) slots[f.resultVar.slot] = result;
        for (Expr e : f.ensures) {
            if (!(Boolean) eval(e, slots)) {
                ByteArrayOutputStream msg = new ByteArrayOutputStream();
                msg.writeBytes(utf8("postcondition of `" + f.name + "` failed: ensures " + text(e.span)));
                if (f.resultVar != null) {
                    msg.writeBytes(utf8(" (result = "));
                    msg.writeBytes(Format.toBytes(result, f.ret));
                    msg.writeBytes(utf8(")"));
                }
                throw new Trap(Code.R0007, e.span, msg.toByteArray());
            }
        }
        callStack.pop();
        return result;
    }

    // =================================================================== statements

    private int execBlock(Block b, Object[] slots) {
        if (debugger != null) return debugBlock(b, slots);
        for (Stmt s : b.stmts()) {
            int r = exec(s, slots);
            if (r != NORMAL) return r;
        }
        return NORMAL;
    }

    /**
     * execBlock with a debugger attached: it is told of each statement first, and the variables declared in the
     * block go out of scope when it ends.
     */
    private int debugBlock(Block b, Object[] slots) {
        Frame frame = callStack.peek();
        int live = frame.live.size();
        List<Stmt> stmts = b.stmts();
        int r = NORMAL;
        for (int k = 0; k < stmts.size(); k++) {
            Stmt s = stmts.get(k);
            // The run reaches a line anew unless the statement before it in this frame is on the same line, further
            // left: `a; b;` and `if c { a; }` reach their line once, and a loop on one line each time it goes round.
            Span prev = frame.last;
            boolean sameLine = prev != null && prev.line() == s.span.line() && prev.file() == s.span.file()
                && prev.col() < s.span.col();
            frame.at = s.span;
            frame.last = s.span;
            debugger.statement(s, !sameLine, frame, callStack.size());
            int mark = frame.live.size();
            frame.mark = mark;
            try {
                r = exec(s, slots);
            } catch (Trap t) {
                if (t != reported) {
                    reported = t;
                    frame.at = s.span;
                    debugger.trap(t, frames());
                }
                throw t;
            }
            // A loop's variables, and the names an arm of a match binds, end with the statement.
            if (!(s instanceof Let || s instanceof Destructure)) frame.truncate(mark);
            if (r != NORMAL) break;
        }
        frame.truncate(live);
        return r;
    }

    /** The frames of the run, innermost first: for a debugger, which reads them where it stops. */
    public List<Frame> frames() {
        return List.copyOf(callStack);
    }

    /** With a debugger attached, notes that {@code v} now holds a value in the innermost frame. */
    private void bound(LocalVar v) {
        if (debugger != null && v != null) callStack.peek().bind(v);
    }

    private int exec(Stmt s, Object[] slots) {
        step(s.span);
        return switch (s) {
            case Let l -> {
                slots[l.var.slot] = Values.coerce(owned(l.init, slots), l.var.type, meter);
                if (debugger != null) {
                    // Names a match in the initializer bound are gone once it has its value.
                    callStack.peek().truncate(callStack.peek().mark);
                    bound(l.var);
                }
                yield NORMAL;
            }
            case Assign a -> {
                assign(a, slots);
                yield NORMAL;
            }
            case Destructure d -> {
                // Matching the pattern binds its names; one that can fail has an else block, which leaves.
                Object v = Values.coerce(owned(d.init, slots), d.init.type, meter);
                if (debugger != null) callStack.peek().truncate(callStack.peek().mark);
                if (matches(d.pattern, v, slots) || d.elseBlock == null) yield NORMAL;
                int r = execBlock(d.elseBlock, slots);
                if (r == NORMAL) throw new IllegalStateException("the else block of a let ran to its end");
                yield r;
            }
            case If i -> {
                for (int k = 0; k < i.conds.size(); k++) {
                    if ((Boolean) eval(i.conds.get(k), slots)) yield execBlock(i.blocks.get(k), slots);
                }
                yield i.elseBlock != null ? execBlock(i.elseBlock, slots) : NORMAL;
            }
            case While w -> whileLoop(w, slots);
            case For f -> f.isRange() ? rangeLoop(f, slots) : eachLoop(f, slots);
            case Match m -> match(m, slots);
            case Break b -> {
                jumpTarget = b.target;
                yield BREAK;
            }
            case Continue c -> {
                jumpTarget = c.target;
                yield CONTINUE;
            }
            case Return r -> {
                retVal = r.value == null ? null : owned(r.value, slots);
                yield RETURN;
            }
            case Assert a -> {
                assertStmt(a, slots);
                yield NORMAL;
            }
            case ExprStmt e -> {
                eval(e.expr, slots);
                yield NORMAL;
            }
        };
    }

    private void assign(Assign a, Object[] slots) {
        Type t = a.target.type;
        if (a.op == null) {
            Object v = Values.coerce(owned(a.value, slots), t, meter);
            if (a.target instanceof Index ix && ix.target.type instanceof Type.MapT mt) {
                // m[k] = v adds k when it is new: the one place where k need not be in the map yet.
                MapV m = (MapV) ref(ix.target, slots).get();
                Object key = Values.coerce(owned(ix.index, slots), mt.key(), meter);
                Stdlib.mapPut(m, key, v, mt.key(), ix.span, meter);
                return;
            }
            ref(a.target, slots).set(v);
            return;
        }
        Object rhs = eval(a.value, slots);
        Ref r = ref(a.target, slots);
        r.set(arith(a.op, Type.kind(t), Type.kind(a.value.type), r.get(), rhs, a.span));
    }

    /** What {@link #body} returns when the loop goes on with its next iteration. */
    private static final int GO = -1;

    /**
     * The loop that a `break label;` or `continue label;` being carried out jumps to; null for one without a
     * label, which jumps to the innermost loop.
     */
    private Loop jumpTarget;

    /** Runs the body of loop l once: GO when the loop goes on, else what the loop statement returns. */
    private int body(Loop l, Block b, Object[] slots) {
        int r = execBlock(b, slots);
        if (r == NORMAL) return GO;
        if (r == RETURN || jumpTarget != null && jumpTarget != l) return r;
        jumpTarget = null;
        return r == BREAK ? NORMAL : GO;
    }

    private int whileLoop(While w, Object[] slots) {
        Object lim = eval(w.limit, slots);
        PrimKind lk = Type.kind(w.limit.type);
        long count = 0;
        while (w.pattern == null ? (Boolean) eval(w.cond, slots) : matches(w.pattern, eval(w.cond, slots), slots)) {
            boolean exceeded = lk == PrimKind.U64 ? Long.compareUnsigned(count, (Long) lim) >= 0 : count >= (Long) lim;
            if (exceeded) {
                throw new Trap(Code.R0008, w.span, "while loop exceeded its limit of " + IntOps.toString(lk, (Long) lim)
                    + " iterations");
            }
            count++;
            step(w.span);
            int r = body(w, w.body, slots);
            if (r != GO) return r;
        }
        return NORMAL;
    }

    /**
     * `for i in a..b`, `a..=b`, either with `step k`, and with `rev` the same values last first. The differences
     * that decide when to stop are unsigned 64-bit, so nothing overflows near the ends of the type.
     */
    private int rangeLoop(For f, Object[] slots) {
        PrimKind k = Type.kind(f.var.type);
        long from = (Long) eval(f.from, slots);
        long to = (Long) eval(f.to, slots);
        long step = 1;
        if (f.step != null) {
            step = (Long) eval(f.step, slots);
            if (k.signed ? step <= 0 : step == 0) {
                throw new Trap(Code.R0013, f.step.span, "the step of a range must be positive, found " + IntOps.toString(k, step));
            }
        }
        int cmp = IntOps.compare(k, from, to);
        if (f.inclusive ? cmp > 0 : cmp >= 0) return NORMAL;
        long i = from;
        if (f.rev) {
            long span = to - from;
            i = from + (f.inclusive ? Long.divideUnsigned(span, step) : Long.divideUnsigned(span - 1, step)) * step;
        }
        while (true) {
            step(f.span);
            slots[f.var.slot] = i;
            bound(f.var);
            int r = body(f, f.body, slots);
            if (r != GO) return r;
            if (f.rev) {
                if (Long.compareUnsigned(i - from, step) < 0) break;
                i -= step;
            } else {
                int c = Long.compareUnsigned(to - i, step);
                if (f.inclusive ? c < 0 : c <= 0) break;
                i += step;
            }
        }
        return NORMAL;
    }

    private int eachLoop(For f, Object[] slots) {
        if (f.pieces() != null) return pieceLoop(f, f.pieces(), slots);
        // The collection is evaluated once and iterated as a snapshot: a copy, when it is a variable the body
        // may change, else the value itself.
        Object coll = f.snapshot ? owned(f.from, slots) : eval(f.from, slots);
        // A map gives its keys in order, or with `for k, v in m` each key with its value.
        MapV m = coll instanceof MapV mv ? mv : null;
        Object[] items = coll instanceof ArrV a ? a.items : coll instanceof ListV l ? l.items : null;
        int n = m != null ? m.len : length(coll);
        for (int k = 0; k < n; k++) {
            int i = f.rev ? n - 1 - k : k;
            step(f.span);
            if (m != null) {
                if (f.indexVar != null) slots[f.indexVar.slot] = m.keys[i];
                slots[f.var.slot] = f.indexVar != null ? m.vals[i] : m.keys[i];
            } else {
                if (f.indexVar != null) slots[f.indexVar.slot] = (long) i;
                slots[f.var.slot] = items != null ? items[i] : (Object) (long) (((StrV) coll).b[i] & 0xFF);
            }
            bound(f.indexVar);
            bound(f.var);
            int r = body(f, f.body, slots);
            if (r != GO) return r;
        }
        return NORMAL;
    }

    /**
     * `for x in split(s, sep)`, `for x in words(s)` and `for c in chars(s)`: the pieces of s, made one at a time
     * into a str of the capacity of s, which no piece can outgrow, or its code points. The loop variable is
     * read-only and anything that stores it copies it, so one buffer serves every piece.
     */
    private int pieceLoop(For f, Call c, Object[] slots) {
        Expr src = c.args.get(0).expr();
        StrV s = (StrV) (f.snapshot ? owned(src, slots) : eval(src, slots));
        long index = 0;
        if (c.builtin == Builtin.CHARS) {
            for (int pos = 0; pos < s.len; ) {
                long d = Stdlib.utf8Next(s.b, s.len, pos);
                pos += (int) (d & 7);
                step(f.span);
                if (f.indexVar != null) slots[f.indexVar.slot] = index++;
                slots[f.var.slot] = d >>> 8;
                bound(f.indexVar);
                bound(f.var);
                int r = body(f, f.body, slots);
                if (r != GO) return r;
            }
            return NORMAL;
        }
        byte[] sep = c.builtin == Builtin.SPLIT ? Stdlib.pattern(eval(c.args.get(1).expr(), slots), meter) : null;
        if (sep != null && sep.length == 0) throw new Trap(Code.R0013, c.span, "split separator is empty");
        meter.bytes(((Type.Str) src.type).cap());
        StrV piece = new StrV(new byte[((Type.Str) src.type).cap()], 0);
        int pos = 0;
        while (true) {
            int start;
            int end;
            if (sep != null) {
                int hit = Stdlib.find(s.b, s.len, sep, pos, meter);
                start = pos;
                end = hit < 0 ? s.len : hit;
                pos = hit < 0 ? -1 : hit + sep.length;
            } else {
                int from = pos;
                while (pos < s.len && Stdlib.isSpace(s.b[pos] & 0xFF)) pos++;
                if (pos == s.len) {
                    meter.bytes(pos - from);
                    break;
                }
                start = pos;
                while (pos < s.len && !Stdlib.isSpace(s.b[pos] & 0xFF)) pos++;
                end = pos;
                meter.bytes(pos - from);
            }
            step(f.span);
            int before = piece.len;
            meter.bytes(Math.max(before, end - start));
            piece.len = end - start;
            System.arraycopy(s.b, start, piece.b, 0, piece.len);
            if (before > piece.len) Arrays.fill(piece.b, piece.len, before, (byte) 0);
            if (f.indexVar != null) slots[f.indexVar.slot] = index++;
            slots[f.var.slot] = piece;
            bound(f.indexVar);
            bound(f.var);
            int r = body(f, f.body, slots);
            if (r != GO) return r;
            if (pos < 0) break;
        }
        return NORMAL;
    }

    private int match(Match m, Object[] slots) {
        Object v = eval(m.subject, slots);
        for (Arm arm : m.arms) {
            if (matches(arm.pattern(), v, slots) && (arm.guard() == null || (Boolean) eval(arm.guard(), slots))) {
                return execBlock(arm.body(), slots);
            }
        }
        throw new IllegalStateException("non-exhaustive match passed the checker");
    }

    /** Whether the value {@code v} matches the pattern; the names of a pattern that matches are bound in {@code slots}. */
    private boolean matches(Pattern p, Object v, Object[] slots) {
        return switch (p) {
            case WildPat w -> true;
            case BindPat b -> {
                // A copy, as the C backend makes: the arm may change the value it was taken from.
                slots[b.var.slot] = Values.copy(v, meter);
                bound(b.var);
                yield true;
            }
            case VariantPat vp -> {
                EnumV e = (EnumV) v;
                if (e.tag != vp.variant.index) yield false;
                if (vp.args != null) {
                    for (int i = 0; i < vp.args.size(); i++) {
                        if (!matches(vp.args.get(i), e.payload[i], slots)) yield false;
                    }
                }
                yield true;
            }
            case TuplePat tp -> {
                StructV t = (StructV) v;
                for (int i = 0; i < tp.elems.size(); i++) {
                    if (!matches(tp.elems.get(i), t.fields[i], slots)) yield false;
                }
                yield true;
            }
            case StructPat sp -> {
                StructV t = (StructV) v;
                for (int i = 0; i < sp.fields.size(); i++) {
                    if (!matches(sp.fields.get(i), t.fields[sp.fieldIndexes[i]], slots)) yield false;
                }
                yield true;
            }
            case OrPat o -> {
                for (Pattern alt : o.alts) {
                    if (matches(alt, v, slots)) yield true;
                }
                yield false;
            }
            case RangePat r -> {
                long x = (Long) v;
                // A u64 above 2^63 is a negative long: its order is the unsigned one.
                yield r.kind == PrimKind.U64
                    ? Long.compareUnsigned(x, r.lo.longValue()) >= 0 && Long.compareUnsigned(x, r.hi.longValue()) <= 0
                    : x >= r.lo.longValue() && x <= r.hi.longValue();
            }
            case IntPat ip -> ((Long) v) == ip.value.longValue();
            case StrPat sp -> {
                StrV s = (StrV) v;
                if (s.len != sp.value.length) yield false;
                meter.bytes(s.len);
                yield Arrays.equals(s.b, 0, s.len, sp.value, 0, sp.value.length);
            }
            case BoolPat bp -> (Boolean) v == bp.value;
        };
    }

    private void assertStmt(Assert a, Object[] slots) {
        Expr c = a.cond;
        if (c instanceof Binary b && (b.op.isEquality() || b.op.isOrdering())) {
            Object l = eval(b.left, slots);
            Object r = eval(b.right, slots);
            if (compare(b, l, r)) return;
            ByteArrayOutputStream msg = assertMessage(a);
            msg.writeBytes(utf8(" (left: "));
            msg.writeBytes(Format.toBytes(l, b.left.type));
            msg.writeBytes(utf8(", right: "));
            msg.writeBytes(Format.toBytes(r, b.right.type));
            msg.writeBytes(utf8(")"));
            throw new Trap(Code.R0005, a.span, msg.toByteArray());
        }
        if (!(Boolean) eval(c, slots)) throw new Trap(Code.R0005, a.span, assertMessage(a).toByteArray());
    }

    /** "assertion failed: ", the message's bytes if there is one, and the condition's source text. */
    private ByteArrayOutputStream assertMessage(Assert a) {
        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        msg.writeBytes(utf8("assertion failed: "));
        if (a.message != null) {
            msg.writeBytes(a.message);
            msg.writeBytes(utf8(": "));
        }
        msg.writeBytes(utf8(text(a.cond.span)));
        return msg;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // =================================================================== places

    /** A reference to storage: a variable slot, an element, a field or a string byte. */
    interface Ref {
        Object get();

        void set(Object v);
    }

    private record SlotRef(Object[] slots, int i) implements Ref {
        public Object get() {
            return slots[i];
        }

        public void set(Object v) {
            slots[i] = v;
        }
    }

    private record ByteRef(StrV s, int i) implements Ref {
        public Object get() {
            return (long) (s.b[i] & 0xFF);
        }

        public void set(Object v) {
            s.b[i] = (byte) (long) (Long) v;
        }
    }

    private Ref ref(Expr place, Object[] slots) {
        switch (place) {
            case Name n -> {
                if (n.local.byRef) return (Ref) slots[n.local.slot];
                return new SlotRef(slots, n.local.slot);
            }
            case FieldAccess f -> {
                StructV sv = (StructV) ref(f.target, slots).get();
                return new SlotRef(sv.fields, f.fieldIndex);
            }
            case Index ix -> {
                Object container = ref(ix.target, slots).get();
                if (ix.target.type instanceof Type.MapT mt) {
                    // m[k] as a place is the value k has, so k must be there (R0003), as when it is read; it is looked up
                    // now, where the place is evaluated. Only `m[k] = v` adds a key (see assign). Nothing can add or remove
                    // a key while the place is in use: a `mut` argument's map is in no other argument (E0208).
                    MapV m = (MapV) container;
                    return new SlotRef(m.vals, Stdlib.mapIndex(m, eval(ix.index, slots), mt.key(), ix.span, meter));
                }
                long i = (Long) eval(ix.index, slots);
                return elementRef(container, i, Type.kind(ix.index.type), ix.span);
            }
            default -> throw new IllegalStateException("not a place");
        }
    }

    private static int length(Object container) {
        return switch (container) {
            case ArrV a -> a.items.length;
            case ListV l -> l.len;
            case StrV s -> s.len;
            case MapV m -> m.len;
            default -> throw new IllegalStateException();
        };
    }

    private Ref elementRef(Object container, long i, PrimKind ik, Span at) {
        int len = length(container);
        if ((ik == PrimKind.U64 ? Long.compareUnsigned(i, len) >= 0 : (i < 0 || i >= len))) {
            throw new Trap(Code.R0003, at, "index " + IntOps.toString(ik, i) + " is out of bounds for length " + len);
        }
        return switch (container) {
            case ArrV a -> new SlotRef(a.items, (int) i);
            case ListV l -> new SlotRef(l.items, (int) i);
            default -> new ByteRef((StrV) container, (int) i);
        };
    }

    // =================================================================== expressions


    /** Evaluates an expression to a value that the caller may store (aggregates are copied). */
    private Object owned(Expr e, Object[] slots) {
        Object v = eval(e, slots);
        return stored(e) && Type.isAggregate(e.type) ? Values.copy(v, meter) : v;
    }

    /**
     * Whether {@code e} evaluates to storage that something else keeps, so that a value taken from it must be copied:
     * a place, or a constant of a file imported under a name (`ns.C`), which is no place, as nothing can assign to it.
     */
    private static boolean stored(Expr e) {
        return e.isPlace() || e instanceof FieldAccess f && f.constant != null;
    }

    private Object eval(Expr e, Object[] slots) {
        return switch (e) {
            case IntLit i -> Type.isFloat(i.type) ? (Object) i.floatValue(Type.kind(i.type)) : (Object) i.value.longValue();
            case FloatLit f -> f.valueAs(Type.kind(f.type));
            case StrLit s -> {
                meter.bytes(s.value.length);
                yield new StrV(s.value.clone(), s.value.length);
            }
            case BoolLit b -> b.value;
            case Name n -> {
                if (n.local == null) yield n.constant.value;
                Object v = slots[n.local.slot];
                yield n.local.byRef ? ((Ref) v).get() : v;
            }
            case FieldAccess f when f.constant != null -> f.constant.value;
            case VariantCall v when v.asCall != null -> callUser(v.asCall, slots);
            case FieldAccess f -> f.variant != null ? new EnumV(f.variant.index, new Object[0])
                : ((StructV) eval(f.target, slots)).fields[f.fieldIndex];
            case Index ix -> {
                Object container = eval(ix.target, slots);
                if (ix.target.type instanceof Type.MapT mt) {
                    yield Stdlib.mapGet((MapV) container, eval(ix.index, slots), mt.key(), ix.span, meter);
                }
                long i = (Long) eval(ix.index, slots);
                yield elementRef(container, i, Type.kind(ix.index.type), ix.span).get();
            }
            case Call c when c.asStruct != null -> eval(c.asStruct, slots);
            case Call c -> c.builtin != null ? builtin(c, slots) : callUser(c, slots);
            case VariantCall v -> {
                Object[] payload = new Object[v.args.size()];
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = Values.coerce(owned(v.args.get(i).expr(), slots), v.variant.payload.get(i), meter);
                }
                yield new EnumV(v.variant.index, payload);
            }
            case StructLit s -> {
                Object[] given = new Object[s.fields.size()];
                for (int i = 0; i < given.length; i++) given[i] = owned(s.fields.get(i).value(), slots);
                Object[] fields = new Object[s.decl.fields.size()];
                for (int i = 0; i < fields.length; i++) {
                    fields[i] = Values.coerce(given[s.order[i]], s.decl.fields.get(i).type, meter);
                }
                yield new StructV(fields);
            }
            case Convert c -> convert(c, eval(c.arg, slots));
            case Resize r -> resize(r, eval(r.arg, slots));
            case Unary u -> {
                Object v = eval(u.operand, slots);
                yield switch (u.op) {
                    case NOT -> !(Boolean) v;
                    // Explicit boxing: a plain ?: would promote the long branch to double.
                    case NEG -> Type.isFloat(u.type) ? (Object) round(-(Double) v, u.type)
                        : (Object) IntOps.neg(Type.kind(u.type), (Long) v, u.span);
                    case BNOT -> IntOps.bnot(Type.kind(u.type), (Long) v);
                };
            }
            case Binary b -> binary(b, slots);
            case Try t -> tryValue(t, slots);
            case ArrayLit a when a.type instanceof Type.MapT mt -> {
                meter.slots(2L * mt.cap());
                MapV m = new MapV(new Object[mt.cap()], new Object[mt.cap()], 0);
                for (Expr x : a.elems) {
                    StructV pair = (StructV) owned(x, slots);
                    Stdlib.mapPut(m, Values.coerce(pair.fields[0], mt.key(), meter), Values.coerce(pair.fields[1], mt.value(), meter),
                        mt.key(), a.span, meter);
                }
                yield m;
            }
            case ArrayLit a when a.type instanceof Type.SetT st -> {
                meter.slots(st.cap());
                ListV s = new ListV(new Object[st.cap()], 0);
                for (Expr x : a.elems) Stdlib.setInsert(s, Values.coerce(owned(x, slots), st.elem(), meter), st.elem(), a.span, meter);
                yield s;
            }
            case ArrayLit a -> {
                Type elem = Type.elementOf(a.type);
                Object[] vals = new Object[a.elems.size()];
                for (int i = 0; i < vals.length; i++) vals[i] = Values.coerce(owned(a.elems.get(i), slots), elem, meter);
                meter.slots(a.type instanceof Type.ListT l ? l.cap() : vals.length);
                yield a.type instanceof Type.ListT l ? new ListV(Arrays.copyOf(vals, l.cap()), vals.length) : new ArrV(vals);
            }
            case IfExpr i -> Values.coerce(owned((Boolean) eval(i.cond, slots) ? i.then : i.otherwise, slots), i.type, meter);
            case MatchExpr m -> {
                Object v = eval(m.subject, slots);
                for (ExprArm arm : m.arms) {
                    if (matches(arm.pattern(), v, slots) && (arm.guard() == null || (Boolean) eval(arm.guard(), slots))) {
                        yield Values.coerce(owned(arm.value(), slots), m.type, meter);
                    }
                }
                throw new IllegalStateException("non-exhaustive match passed the checker");
            }
            case SliceExpr s -> {
                Object xs = eval(s.target, slots);
                long from = s.from == null ? 0L : (Long) eval(s.from, slots);
                long to = s.to == null ? (long) length(xs) : (Long) eval(s.to, slots);
                PrimKind fk = s.from == null ? PrimKind.I64 : Type.kind(s.from.type);
                PrimKind tk = s.to == null ? PrimKind.I64 : Type.kind(s.to.type);
                yield Stdlib.slice(xs, s.type, from, fk, to, tk, s.span, meter);
            }
            case TupleLit t -> {
                List<Type> elems = ((Type.TupleT) t.type).elems();
                Object[] vals = new Object[t.elems.size()];
                for (int i = 0; i < vals.length; i++) vals[i] = Values.coerce(owned(t.elems.get(i), slots), elems.get(i), meter);
                yield new StructV(vals);
            }
            case Repeat r -> {
                Type elem = Type.elementOf(r.type);
                Object v = Values.coerce(owned(r.value, slots), elem, meter);
                int cap = r.type instanceof Type.ListT l ? l.cap() : r.n;
                Object[] items = new Object[cap];
                meter.slots(cap);
                for (int i = 0; i < r.n; i++) items[i] = i == 0 ? v : Values.copy(v, meter);
                yield r.type instanceof Type.ListT ? new ListV(items, r.n) : new ArrV(items);
            }
        };
    }

    private static double round(double d, Type t) {
        return Type.kind(t) == PrimKind.F32 ? (double) (float) d : d;
    }

    private Object callUser(Call c, Object[] slots) {
        FnDecl f = c.fn;
        Object[] args = new Object[f.params.size()];
        for (int i = 0; i < args.length; i++) {
            LocalVar p = f.paramVars.get(i);
            Expr a = c.args.get(i).expr();
            // A function argument is not a value: the instance calls the function it was made for.
            if (p.type instanceof Type.FnT) continue;
            args[i] = p.byRef ? ref(a, slots) : Values.coerce(eval(a, slots), p.type, meter);
        }
        // What the caller does with the result is charged at the caller's statement, not the callee's last.
        Span caller = meter.at();
        Object result = invoke(f, args, c.span);
        meter.at(caller);
        return result;
    }

    private Object binary(Binary b, Object[] slots) {
        if (b.op == BinOp.AND) return (Boolean) eval(b.left, slots) && (Boolean) eval(b.right, slots);
        if (b.op == BinOp.OR) return (Boolean) eval(b.left, slots) || (Boolean) eval(b.right, slots);
        Object l = eval(b.left, slots);
        Object r = eval(b.right, slots);
        if (b.op.isEquality() || b.op.isOrdering()) return compare(b, l, r);
        return arith(b.op, Type.kind(b.left.type), Type.kind(b.right.type), l, r, b.span);
    }

    /**
     * Whether the integer {@code v} of kind {@code k} is between 0 and {@code max}; a u64 above 2^63 is negative
     * as a long, and so out of range too.
     */
    private static boolean inRange(PrimKind k, long v, long max) {
        return v >= 0 && v <= max;
    }

    /**
     * The text fixed, pad_left or pad_right made: as it is when it is written out (see {@link Call#asText}),
     * otherwise a str of the capacity of where it is stored, which it must fit.
     */
    /** The base of to_base or parse_i64, 2 to 36; traps (R0013) otherwise. */
    private static int radix(Call c, Type t, long base) {
        PrimKind k = Type.kind(t);
        if (!inRange(k, base, 36) || base < 2) {
            throw new Trap(Code.R0013, c.span, c.name + "() needs a base of 2 to 36, found " + IntOps.toString(k, base));
        }
        return (int) base;
    }

    private StrV text(Call c, byte[] bytes) {
        meter.bytes(bytes.length);
        if (c.asText) return new StrV(bytes, bytes.length);
        int cap = ((Type.Str) c.type).cap();
        if (bytes.length > cap) {
            throw new Trap(Code.R0004, c.span, c.name + " needs " + bytes.length + " bytes, more than str capacity " + cap);
        }
        meter.bytes(cap);
        return new StrV(Arrays.copyOf(bytes, cap), bytes.length);
    }

    private boolean compare(Binary b, Object l, Object r) {
        Type t = b.left.type;
        if (b.op.isEquality()) return Values.equal(l, r, t, meter) == (b.op == BinOp.EQ);
        if (Type.isFloat(t)) {
            double x = (Double) l;
            double y = (Double) r;
            return switch (b.op) {
                case LT -> x < y;
                case LE -> x <= y;
                case GT -> x > y;
                default -> x >= y;
            };
        }
        int c = t instanceof Type.Str ? Stdlib.compare(l, r, t, meter) : IntOps.compare(Type.kind(t), (Long) l, (Long) r);
        return switch (b.op) {
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            default -> c >= 0;
        };
    }

    private Object arith(BinOp op, PrimKind k, PrimKind rk, Object l, Object r, Span at) {
        if (k.isFloat) {
            double x = (Double) l;
            double y = (Double) r;
            double v = switch (op) {
                case ADD -> x + y;
                case SUB -> x - y;
                case MUL -> x * y;
                case DIV -> x / y;
                default -> throw new IllegalStateException(op.name());
            };
            return k == PrimKind.F32 ? (double) (float) v : v;
        }
        long x = (Long) l;
        long y = (Long) r;
        return switch (op) {
            case ADD -> IntOps.add(k, x, y, at);
            case SUB -> IntOps.sub(k, x, y, at);
            case MUL -> IntOps.mul(k, x, y, at);
            case DIV -> IntOps.div(k, x, y, at);
            case MOD -> IntOps.rem(k, x, y, at);
            case BAND -> x & y;
            case BOR -> x | y;
            case BXOR -> x ^ y;
            case SHL -> IntOps.shl(k, rk, x, y, at);
            case SHR -> IntOps.shr(k, rk, x, y, at);
            default -> throw new IllegalStateException(op.name());
        };
    }

    /**
     * `str[N](s)` or `list[T; N](xs)`: a new value of capacity N holding v's bytes or (copies of its)
     * elements, or R0004 if v is longer. Only what v holds is copied, not the rest of its capacity.
     */
    private Object resize(Resize r, Object v) {
        if (!(v instanceof StrV) && !(v instanceof ListV)) {
            // str[N](x) of a number or bool: its text, as print writes it.
            int cap = Type.capacity(r.type);
            meter.bytes(cap);
            StrV s = new StrV(new byte[cap], 0);
            StrSink part = new StrSink(s.b, 0);
            Format.format(part, v, r.arg.type, false, meter);
            if (part.count > cap) {
                throw new Trap(Code.R0004, r.span, "a str of " + part.count + " bytes does not fit in " + r.type);
            }
            s.len = (int) part.count;
            return s;
        }
        int n = length(v);
        int cap = Type.capacity(r.type);
        if (n > cap) {
            String what = v instanceof StrV ? n + " bytes" : n + " elements";
            throw new Trap(Code.R0004, r.span, "a " + (v instanceof StrV ? "str" : "list") + " of " + what
                + " does not fit in " + r.type);
        }
        if (v instanceof StrV s) {
            meter.bytes(cap);
            return new StrV(Arrays.copyOf(s.b, cap), n);
        }
        ListV l = (ListV) v;
        meter.slots(cap);
        meter.elements(n);
        Object[] items = new Object[cap];
        for (int i = 0; i < n; i++) items[i] = Values.copy(l.items[i], meter);
        return new ListV(items, n);
    }

    private Object convert(Convert c, Object v) {
        if (v instanceof StrV s) {
            // Not a ?: expression, which would turn the long of an integer into a double.
            if (c.target.isFloat) return Stdlib.parseFloat(s, c.span, meter);
            return Stdlib.parseInt(s, c.target, c.span, meter);
        }
        PrimKind from = Type.kind(c.arg.type);
        PrimKind to = c.target;
        if (from == to) return v;
        if (from == PrimKind.BOOL) return (Boolean) v ? 1L : 0L;
        if (from.isFloat) {
            double d = (Double) v;
            if (to == PrimKind.F64) return d;
            if (to == PrimKind.F32) return (double) (float) d;
            return IntOps.fromDouble(d, to, c.span);
        }
        long n = (Long) v;
        if (to == PrimKind.F64) return IntOps.toDouble(from, n);
        if (to == PrimKind.F32) return (double) IntOps.toFloat(from, n);
        return IntOps.convertInt(from, to, n, c.span);
    }

    // =================================================================== builtins

    private Object builtin(Call c, Object[] slots) {
        List<Arg> args = c.args;
        switch (c.builtin) {
            case PRINT -> {
                // Every argument is evaluated before anything is written, so a trap prints nothing; and the line is
                // written whole before the budget applies to its cost.
                Object[] values = new Object[args.size()];
                for (int i = 0; i < values.length; i++) values[i] = eval(args.get(i).expr(), slots);
                meter.hold();
                for (int i = 0; i < values.length; i++) Format.format(printSink, values[i], args.get(i).expr().type, false, meter);
                printSink.write('\n');
                printSink.drain();
                meter.release();
                return null;
            }
            case LEN -> {
                return (long) length(eval(args.get(0).expr(), slots));
            }
            case CAP -> {
                // The capacity is the type's, but the argument is evaluated all the same, as every argument is: cap(xs[9])
                // traps on a bad index, and cap(m[k]) on a missing key, in both backends.
                eval(args.get(0).expr(), slots);
                return (long) Type.capacity(args.get(0).expr().type);
            }
            case PUSH -> {
                Object container = ref(args.get(0).expr(), slots).get();
                Type elem = Type.elementOf(args.get(0).expr().type);
                Object v = Values.coerce(owned(args.get(1).expr(), slots), elem, meter);
                if (container instanceof ListV l) {
                    if (l.len == l.items.length) {
                        throw new Trap(Code.R0004, c.span, "push to a full list (capacity " + l.items.length + ")");
                    }
                    l.items[l.len++] = v;
                } else {
                    StrV s = (StrV) container;
                    if (s.len == s.b.length) {
                        throw new Trap(Code.R0004, c.span, "push to a full str (capacity " + s.b.length + ")");
                    }
                    s.b[s.len++] = (byte) (long) (Long) v;
                }
                return null;
            }
            case POP -> {
                Object container = ref(args.get(0).expr(), slots).get();
                if (container instanceof ListV l) {
                    if (l.len == 0) throw new Trap(Code.R0012, c.span, "pop from an empty list");
                    Object v = l.items[--l.len];
                    l.items[l.len] = null;
                    return v;
                }
                StrV s = (StrV) container;
                if (s.len == 0) throw new Trap(Code.R0012, c.span, "pop from an empty str");
                return (long) (s.b[--s.len] & 0xFF);
            }
            case CLEAR -> {
                Object container = ref(args.get(0).expr(), slots).get();
                if (container instanceof MapV m) {
                    meter.slots(2L * m.keys.length);
                    Arrays.fill(m.keys, null);
                    Arrays.fill(m.vals, null);
                    m.len = 0;
                } else if (container instanceof ListV l) {
                    meter.slots(l.items.length);
                    Arrays.fill(l.items, null);
                    l.len = 0;
                } else {
                    ((StrV) container).len = 0;
                }
                return null;
            }
            case APPEND -> {
                Ref target = ref(args.get(0).expr(), slots);
                Object[] values = new Object[args.size()];
                for (int i = 1; i < values.length; i++) values[i] = eval(args.get(i).expr(), slots);
                StrV s = (StrV) target.get();
                for (int i = 1; i < values.length; i++) {
                    // The text goes straight into the spare capacity; only its length is kept beyond that.
                    StrSink part = new StrSink(s.b, s.len);
                    Format.format(part, values[i], args.get(i).expr().type, false, meter);
                    if (part.count > s.b.length - s.len) {
                        throw new Trap(Code.R0004, c.span, "append exceeds str capacity " + s.b.length
                            + " (length " + s.len + ", appending " + part.count + " bytes)");
                    }
                    s.len += (int) part.count;
                }
                return null;
            }
            case ABS -> {
                Object v = eval(args.get(0).expr(), slots);
                Type t = c.type;
                if (Type.isFloat(t)) return Math.abs((Double) v);
                return IntOps.abs(Type.kind(t), (Long) v, c.span);
            }
            case MIN, MAX -> {
                if (args.size() == 1) {
                    Object xs = eval(args.get(0).expr(), slots);
                    Object[] items = xs instanceof ListV l ? l.items : ((ArrV) xs).items;
                    int n = xs instanceof ListV l ? l.len : items.length;
                    if (n == 0) {
                        throw new Trap(Code.R0013, c.span, c.builtin.name + "() of an empty "
                            + (xs instanceof ListV ? "list" : "array"));
                    }
                    Object best = items[0];
                    for (int i = 1; i < n; i++) {
                        meter.elements(1);
                        if (takesSecond(c.builtin, c.type, best, items[i])) best = items[i];
                    }
                    return best;
                }
                Object a = eval(args.get(0).expr(), slots);
                Object b = eval(args.get(1).expr(), slots);
                return takesSecond(c.builtin, c.type, a, b) ? b : a;
            }
            case SUM -> {
                Object xs = eval(args.get(0).expr(), slots);
                Object[] items = xs instanceof ListV l ? l.items : ((ArrV) xs).items;
                int n = xs instanceof ListV l ? l.len : items.length;
                PrimKind k = Type.kind(c.type);
                Object total = k.isFloat ? (Object) 0.0 : (Object) 0L;
                for (int i = 0; i < n; i++) {
                    meter.elements(1);
                    total = arith(BinOp.ADD, k, k, total, items[i], c.span);
                }
                return total;
            }
            case SQRT, FLOOR, CEIL, ROUND, TRUNC, EXP, LOG, LOG2, LOG10, SIN, COS, TAN, ASIN, ACOS, ATAN -> {
                double d = (Double) eval(args.get(0).expr(), slots);
                meter.units(Meter.FLOAT_MATH);
                double r = switch (c.builtin) {
                    case SQRT -> Math.sqrt(d);
                    case FLOOR -> Math.floor(d);
                    case CEIL -> Math.ceil(d);
                    case ROUND -> Stdlib.round(d);
                    case EXP -> FloatMath.exp(d);
                    case LOG -> FloatMath.log(d);
                    case LOG2 -> FloatMath.log2(d);
                    case LOG10 -> FloatMath.log10(d);
                    case SIN -> FloatMath.sin(d);
                    case COS -> FloatMath.cos(d);
                    case TAN -> FloatMath.tan(d);
                    case ASIN -> FloatMath.asin(d);
                    case ACOS -> FloatMath.acos(d);
                    case ATAN -> FloatMath.atan(d);
                    default -> Stdlib.trunc(d);
                };
                return round(r, c.type);
            }
            case ATAN2 -> {
                double y = (Double) eval(args.get(0).expr(), slots);
                double x = (Double) eval(args.get(1).expr(), slots);
                meter.units(Meter.FLOAT_MATH);
                return round(FloatMath.atan2(y, x), c.type);
            }
            case IS_NAN, IS_FINITE -> {
                double d = (Double) eval(args.get(0).expr(), slots);
                return c.builtin == Builtin.IS_NAN ? Double.isNaN(d) : Double.isFinite(d);
            }
            case CLAMP -> {
                Object x = eval(args.get(0).expr(), slots);
                Object lo = eval(args.get(1).expr(), slots);
                Object hi = eval(args.get(2).expr(), slots);
                return Stdlib.clamp(c.type, x, lo, hi, c.span);
            }
            case POW -> {
                if (Type.isFloat(c.type)) {
                    double x = (Double) eval(args.get(0).expr(), slots);
                    double n = (Double) eval(args.get(1).expr(), slots);
                    meter.units(Meter.FLOAT_MATH);
                    return round(FloatMath.pow(x, n), c.type);
                }
                long x = (Long) eval(args.get(0).expr(), slots);
                long n = (Long) eval(args.get(1).expr(), slots);
                return Stdlib.pow(Type.kind(c.type), x, Type.kind(args.get(1).expr().type), n, c.span);
            }
            case GCD, LCM -> {
                long a = (Long) eval(args.get(0).expr(), slots);
                long b = (Long) eval(args.get(1).expr(), slots);
                PrimKind k = Type.kind(c.type);
                return c.builtin == Builtin.GCD ? Stdlib.gcd(k, a, b, c.span) : Stdlib.lcm(k, a, b, c.span);
            }
            case WRAPPING_ADD, WRAPPING_SUB, WRAPPING_MUL, SATURATING_ADD, SATURATING_SUB, SATURATING_MUL -> {
                long a = (Long) eval(args.get(0).expr(), slots);
                long b = (Long) eval(args.get(1).expr(), slots);
                return Stdlib.wrapOrSaturate(c.builtin, Type.kind(c.type), a, b);
            }
            case COUNT_ONES, LEADING_ZEROS, TRAILING_ZEROS -> {
                long a = (Long) eval(args.get(0).expr(), slots);
                return Stdlib.bits(c.builtin, Type.kind(args.get(0).expr().type), a);
            }
            case ROTL, ROTR -> {
                long a = (Long) eval(args.get(0).expr(), slots);
                long n = (Long) eval(args.get(1).expr(), slots);
                return Stdlib.rotate(c.builtin == Builtin.ROTL, Type.kind(c.type), a, Type.kind(args.get(1).expr().type), n);
            }
            case CHECKED_ADD, CHECKED_SUB, CHECKED_MUL -> {
                long a = (Long) eval(args.get(0).expr(), slots);
                long b = (Long) eval(args.get(1).expr(), slots);
                Long r = Stdlib.checked(c.builtin, Type.kind(args.get(0).expr().type), a, b);
                return option(r);
            }
            case REM_EUCLID, DIV_EUCLID -> {
                long a = (Long) eval(args.get(0).expr(), slots);
                long m = (Long) eval(args.get(1).expr(), slots);
                PrimKind k = Type.kind(c.type);
                return c.builtin == Builtin.REM_EUCLID ? Stdlib.remEuclid(k, a, m, c.span) : Stdlib.divEuclid(k, a, m, c.span);
            }
            case MUL_MOD -> {
                long a = (Long) eval(args.get(0).expr(), slots);
                long b = (Long) eval(args.get(1).expr(), slots);
                long m = (Long) eval(args.get(2).expr(), slots);
                return Stdlib.mulMod(Type.kind(c.type), a, b, m, c.span);
            }
            case PARSE_I64 -> {
                StrV s = (StrV) eval(args.get(0).expr(), slots);
                if (args.size() == 1) return option(Stdlib.parseIntOrNull(s, meter));
                long base = (Long) eval(args.get(1).expr(), slots);
                int radix = radix(c, args.get(1).expr().type, base);
                return option(Stdlib.parseIntOrNull(s, radix, meter));
            }
            case PARSE_F64 -> {
                return option(Stdlib.parseFloatOrNull((StrV) eval(args.get(0).expr(), slots), meter));
            }
            case ANY, ALL, COUNT_IF, FIND_INDEX -> {
                Object xs = eval(args.get(0).expr(), slots);
                Object[] items = xs instanceof ListV l ? l.items : ((ArrV) xs).items;
                int n = xs instanceof ListV l ? l.len : items.length;
                long count = 0;
                for (int i = 0; i < n; i++) {
                    meter.elements(1);
                    boolean yes = test(c, items[i]);
                    switch (c.builtin) {
                        case ANY -> {
                            if (yes) return true;
                        }
                        case ALL -> {
                            if (!yes) return false;
                        }
                        case FIND_INDEX -> {
                            if (yes) return (long) i;
                        }
                        default -> {
                            if (yes) count++;
                        }
                    }
                }
                return switch (c.builtin) {
                    case ANY -> false;
                    case ALL -> true;
                    case FIND_INDEX -> -1L;
                    default -> count;
                };
            }
            case RETAIN -> {
                ListV l = (ListV) ref(args.get(0).expr(), slots).get();
                int w = 0;
                for (int i = 0; i < l.len; i++) {
                    meter.elements(1);
                    if (test(c, l.items[i])) l.items[w++] = l.items[i];
                }
                Arrays.fill(l.items, w, l.len, null);
                l.len = w;
                return null;
            }
            case SORT_BY -> {
                Object xs = ref(args.get(0).expr(), slots).get();
                Object[] items = xs instanceof ListV l ? l.items : ((ArrV) xs).items;
                int n = xs instanceof ListV l ? l.len : items.length;
                // A binary insertion sort, as the C backend's: each element goes after every one it does not come
                // before, so equal ones keep their order; before is called in the same order in both.
                for (int i = 1; i < n; i++) {
                    Object x = items[i];
                    int lo = 0;
                    int hi = i;
                    while (lo < hi) {
                        int mid = lo + (hi - lo) / 2;
                        meter.elements(1);
                        if (test(c, x, items[mid])) hi = mid;
                        else lo = mid + 1;
                    }
                    if (lo < i) {
                        meter.elements(i - lo);
                        System.arraycopy(items, lo, items, lo + 1, i - lo);
                        items[lo] = x;
                    }
                }
                return null;
            }
            case IS_DIGIT, IS_ALPHA, IS_ALNUM, IS_SPACE, IS_UPPER, IS_LOWER -> {
                long ch = (Long) eval(args.get(0).expr(), slots);
                return switch (c.builtin) {
                    case IS_DIGIT -> Stdlib.isDigit(ch);
                    case IS_ALPHA -> Stdlib.isAlpha(ch);
                    case IS_ALNUM -> Stdlib.isAlpha(ch) || Stdlib.isDigit(ch);
                    case IS_SPACE -> Stdlib.isSpace(ch);
                    case IS_UPPER -> Stdlib.isUpper(ch);
                    default -> Stdlib.isLower(ch);
                };
            }
            case TO_UPPER, TO_LOWER -> {
                Object v = eval(args.get(0).expr(), slots);
                boolean upper = c.builtin == Builtin.TO_UPPER;
                if (v instanceof StrV s) return Stdlib.mapCase(s, upper, meter);
                return upper ? Stdlib.toUpper((Long) v) : Stdlib.toLower((Long) v);
            }
            case LAST_INDEX_OF -> {
                Object xs = eval(args.get(0).expr(), slots);
                Object x = eval(args.get(1).expr(), slots);
                return xs instanceof StrV s ? (long) Stdlib.findLast(s.b, s.len, Stdlib.pattern(x, meter), meter)
                    : Stdlib.lastIndexOf(xs, x, Type.elementOf(args.get(0).expr().type), meter);
            }
            case CONTAINS, INDEX_OF -> {
                Object xs = eval(args.get(0).expr(), slots);
                Object x = eval(args.get(1).expr(), slots);
                Type ct = args.get(0).expr().type;
                if (ct instanceof Type.MapT mt) return Stdlib.find(((MapV) xs).keys, ((MapV) xs).len, x, mt.key(), meter) >= 0;
                if (ct instanceof Type.SetT st) return Stdlib.find(((ListV) xs).items, ((ListV) xs).len, x, st.elem(), meter) >= 0;
                long at = xs instanceof StrV s ? Stdlib.find(s.b, s.len, Stdlib.pattern(x, meter), 0, meter)
                    : Stdlib.indexOf(xs, x, Type.elementOf(args.get(0).expr().type), meter);
                return c.builtin == Builtin.CONTAINS ? (Object) (at >= 0) : (Object) at;
            }
            case COUNT -> {
                Object xs = eval(args.get(0).expr(), slots);
                Object x = eval(args.get(1).expr(), slots);
                return xs instanceof StrV s ? Stdlib.count(s, Stdlib.pattern(x, meter), c.span, meter)
                    : Stdlib.count(xs, x, Type.elementOf(args.get(0).expr().type), meter);
            }
            case STARTS_WITH, ENDS_WITH -> {
                StrV s = (StrV) eval(args.get(0).expr(), slots);
                byte[] p = Stdlib.pattern(eval(args.get(1).expr(), slots), meter);
                return c.builtin == Builtin.STARTS_WITH ? Stdlib.startsWith(s, p, meter) : Stdlib.endsWith(s, p, meter);
            }
            case SLICE -> {
                Object xs = eval(args.get(0).expr(), slots);
                long from = (Long) eval(args.get(1).expr(), slots);
                long to = (Long) eval(args.get(2).expr(), slots);
                return Stdlib.slice(xs, c.type, from, Type.kind(args.get(1).expr().type), to,
                    Type.kind(args.get(2).expr().type), c.span, meter);
            }
            case TRIM -> {
                return Stdlib.trim((StrV) eval(args.get(0).expr(), slots), meter);
            }
            case IS_UTF8 -> {
                return Stdlib.isUtf8((StrV) eval(args.get(0).expr(), slots), meter);
            }
            case CHAR_COUNT -> {
                return Stdlib.charCount((StrV) eval(args.get(0).expr(), slots), meter);
            }
            case CHARS -> {
                return Stdlib.chars((StrV) eval(args.get(0).expr(), slots), (Type.ListT) c.type, c.span, meter);
            }
            case PUSH_CHAR -> {
                Ref target = ref(args.get(0).expr(), slots);
                long cp = (Long) eval(args.get(1).expr(), slots);
                Stdlib.pushChar((StrV) target.get(), cp, Type.kind(args.get(1).expr().type), c.span);
                return null;
            }
            case SLICE_CHARS -> {
                StrV xs = (StrV) eval(args.get(0).expr(), slots);
                long from = (Long) eval(args.get(1).expr(), slots);
                long to = (Long) eval(args.get(2).expr(), slots);
                return Stdlib.sliceChars(xs, from, Type.kind(args.get(1).expr().type), to, Type.kind(args.get(2).expr().type),
                    c.span, meter);
            }
            case TRY_SPLIT, TRY_WORDS -> {
                ListV parts = (ListV) ref(args.get(0).expr(), slots).get();
                StrV s = (StrV) eval(args.get(1).expr(), slots);
                byte[] sep = c.builtin == Builtin.TRY_SPLIT ? Stdlib.pattern(eval(args.get(2).expr(), slots), meter) : null;
                return Stdlib.trySplit(parts, s, sep, (Type.ListT) args.get(0).expr().type, c.span, meter);
            }
            case FIXED -> {
                double x = (Double) eval(args.get(0).expr(), slots);
                long d = (Long) eval(args.get(1).expr(), slots);
                PrimKind k = Type.kind(args.get(1).expr().type);
                if (!inRange(k, d, Format.MAX_DECIMALS)) {
                    throw new Trap(Code.R0013, c.span, "fixed() needs 0 to " + Format.MAX_DECIMALS + " decimals, found "
                        + IntOps.toString(k, d));
                }
                meter.units(Meter.floatText(x));
                return text(c, Format.formatFixed(x, (int) d).getBytes(StandardCharsets.US_ASCII));
            }
            case TO_BASE -> {
                long n = (Long) eval(args.get(0).expr(), slots);
                long base = (Long) eval(args.get(1).expr(), slots);
                int radix = radix(c, args.get(1).expr().type, base);
                String t = IntOps.toBig(Type.kind(args.get(0).expr().type), n).toString(radix);
                return text(c, t.getBytes(StandardCharsets.US_ASCII));
            }
            case PAD_LEFT, PAD_RIGHT -> {
                Expr value = args.get(0).expr();
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                Format.format(ByteSink.of(text), eval(value, slots), value.type, false, meter);
                long width = (Long) eval(args.get(1).expr(), slots);
                byte fill = (byte) (long) (Long) eval(args.get(2).expr(), slots);
                PrimKind k = Type.kind(args.get(1).expr().type);
                if (!(k.signed && width < 0) && !inRange(k, width, Format.MAX_PAD)) {
                    throw new Trap(Code.R0013, c.span, c.name + "() width must be at most " + Format.MAX_PAD + ", found "
                        + IntOps.toString(k, width));
                }
                byte[] t = text.toByteArray();
                int pad = (int) Math.max(0, width - t.length);
                byte[] out = new byte[t.length + pad];
                int at = c.builtin == Builtin.PAD_LEFT ? pad : 0;
                System.arraycopy(t, 0, out, at, t.length);
                Arrays.fill(out, at == 0 ? t.length : 0, at == 0 ? out.length : pad, fill);
                return text(c, out);
            }
            case IS_FLOAT -> {
                return Stdlib.isFloat((StrV) eval(args.get(0).expr(), slots), meter);
            }
            case IS_INT -> {
                return Stdlib.isInt((StrV) eval(args.get(0).expr(), slots), meter);
            }
            case SPLIT, WORDS -> {
                StrV s = (StrV) eval(args.get(0).expr(), slots);
                byte[] sep = c.builtin == Builtin.SPLIT ? Stdlib.pattern(eval(args.get(1).expr(), slots), meter) : null;
                return Stdlib.split(c.name, s, sep, (Type.ListT) c.type, c.span, meter);
            }
            case JOIN -> {
                Object parts = eval(args.get(0).expr(), slots);
                byte[] sep = Stdlib.pattern(eval(args.get(1).expr(), slots), meter);
                return Stdlib.join(parts, sep, (Type.Str) c.type, c.span, meter);
            }
            case REPLACE -> {
                StrV s = (StrV) eval(args.get(0).expr(), slots);
                byte[] from = Stdlib.pattern(eval(args.get(1).expr(), slots), meter);
                byte[] to = Stdlib.pattern(eval(args.get(2).expr(), slots), meter);
                return Stdlib.replace(s, from, to, (Type.Str) c.type, c.span, meter);
            }
            case SORT, REVERSE -> {
                Object xs = ref(args.get(0).expr(), slots).get();
                if (c.builtin == Builtin.SORT) Stdlib.sort(xs, args.get(0).expr().type, meter);
                else Stdlib.reverse(xs, meter);
                return null;
            }
            case DEDUP -> {
                Stdlib.dedup(ref(args.get(0).expr(), slots).get(), args.get(0).expr().type, meter);
                return null;
            }
            case BINARY_SEARCH -> {
                Object xs = eval(args.get(0).expr(), slots);
                Type elem = Type.elementOf(args.get(0).expr().type);
                Object x = eval(args.get(1).expr(), slots);
                // A str is compared as it is, whatever its capacity: copying it to the element's could cut it short.
                if (!(elem instanceof Type.Str)) x = Values.coerce(x, elem, meter);
                return Stdlib.binarySearch(xs, x, elem, meter);
            }
            case INSERT -> {
                if (args.get(0).expr().type instanceof Type.MapT mt) {
                    MapV m = (MapV) ref(args.get(0).expr(), slots).get();
                    Object k = Values.coerce(owned(args.get(1).expr(), slots), mt.key(), meter);
                    Object v = Values.coerce(owned(args.get(2).expr(), slots), mt.value(), meter);
                    Stdlib.mapPut(m, k, v, mt.key(), c.span, meter);
                    return null;
                }
                if (args.get(0).expr().type instanceof Type.SetT st) {
                    ListV s = (ListV) ref(args.get(0).expr(), slots).get();
                    Stdlib.setInsert(s, Values.coerce(owned(args.get(1).expr(), slots), st.elem(), meter), st.elem(), c.span, meter);
                    return null;
                }
                Object xs = ref(args.get(0).expr(), slots).get();
                long i = (Long) eval(args.get(1).expr(), slots);
                Type elem = Type.elementOf(args.get(0).expr().type);
                Object v = Values.coerce(owned(args.get(2).expr(), slots), elem, meter);
                Stdlib.insert(xs, i, Type.kind(args.get(1).expr().type), v, c.span, meter);
                return null;
            }
            case REMOVE -> {
                if (args.get(0).expr().type instanceof Type.MapT mt) {
                    MapV m = (MapV) ref(args.get(0).expr(), slots).get();
                    return Stdlib.mapRemove(m, eval(args.get(1).expr(), slots), mt.key(), meter);
                }
                if (args.get(0).expr().type instanceof Type.SetT st) {
                    ListV s = (ListV) ref(args.get(0).expr(), slots).get();
                    return Stdlib.setRemove(s, eval(args.get(1).expr(), slots), st.elem(), meter);
                }
                Object xs = ref(args.get(0).expr(), slots).get();
                long i = (Long) eval(args.get(1).expr(), slots);
                return Stdlib.remove(xs, i, Type.kind(args.get(1).expr().type), c.span, meter);
            }
            case COMPARE -> {
                Object a = eval(args.get(0).expr(), slots);
                Object b = eval(args.get(1).expr(), slots);
                return (long) Integer.signum(Stdlib.compare(a, b, args.get(0).expr().type, meter));
            }
            case KEYS, VALUES -> {
                Type.MapT mt = (Type.MapT) args.get(0).expr().type;
                MapV m = (MapV) eval(args.get(0).expr(), slots);
                Object[] from = c.builtin == Builtin.KEYS ? m.keys : m.vals;
                meter.slots(mt.cap());
                Object[] out = new Object[mt.cap()];
                for (int i = 0; i < m.len; i++) out[i] = Values.copy(from[i], meter);
                return new ListV(out, m.len);
            }
            case HASH -> {
                Object v = eval(args.get(0).expr(), slots);
                long h = 0xcbf29ce484222325L;
                if (v instanceof StrV s) {
                    meter.bytes(s.len);
                    for (int i = 0; i < s.len; i++) h = (h ^ (s.b[i] & 0xff)) * 0x100000001b3L;
                } else {
                    Object[] items = v instanceof ListV l ? l.items : ((ArrV) v).items;
                    int n = v instanceof ListV l ? l.len : items.length;
                    meter.bytes(n);
                    for (int i = 0; i < n; i++) h = (h ^ ((Long) items[i] & 0xff)) * 0x100000001b3L;
                }
                return h;
            }
            case VARIANTS -> {
                Object[] vals = new Object[args.size()];
                for (int i = 0; i < vals.length; i++) vals[i] = eval(args.get(i).expr(), slots);
                meter.slots(vals.length);
                return new ArrV(vals);
            }
            case GET_OR -> {
                Type.MapT mt = (Type.MapT) args.get(0).expr().type;
                MapV m = (MapV) eval(args.get(0).expr(), slots);
                Object k = eval(args.get(1).expr(), slots);
                Object d = eval(args.get(2).expr(), slots);
                int i = Stdlib.find(m.keys, m.len, k, mt.key(), meter);
                // The value found is the map's own, and a default in a variable or constant is that one's: the result is
                // a copy of either, as the C backend makes, so that storing it and changing it changes neither.
                Object v = i >= 0 ? m.vals[i] : d;
                if (i >= 0 || stored(args.get(2).expr())) v = Values.copy(v, meter);
                return Values.coerce(v, mt.value(), meter);
            }
            case UNWRAP_OR -> {
                Expr o = args.get(0).expr();
                EnumV e = (EnumV) eval(o, slots);
                Expr d = args.get(1).expr();
                Object dv = eval(d, slots);
                boolean ok = succeeded(o.type, e);
                // As get_or: the value is the variable's or the default's own, so the result is a copy of either.
                Object v = ok ? e.payload[0] : dv;
                if (stored(ok ? o : d)) v = Values.copy(v, meter);
                return Values.coerce(v, c.type, meter);
            }
            case IS_SOME, IS_NONE, IS_OK, IS_ERR -> {
                Expr o = args.get(0).expr();
                boolean ok = succeeded(o.type, (EnumV) eval(o, slots));
                return c.builtin == Builtin.IS_SOME || c.builtin == Builtin.IS_OK ? ok : !ok;
            }
            case STRIP_PREFIX, STRIP_SUFFIX -> {
                StrV s = (StrV) eval(args.get(0).expr(), slots);
                byte[] p = Stdlib.pattern(eval(args.get(1).expr(), slots), meter);
                boolean prefix = c.builtin == Builtin.STRIP_PREFIX;
                boolean has = prefix ? Stdlib.startsWith(s, p, meter) : Stdlib.endsWith(s, p, meter);
                int from = has && prefix ? p.length : 0;
                int to = has && !prefix ? s.len - p.length : s.len;
                meter.bytes(s.b.length);
                byte[] b = new byte[s.b.length];
                System.arraycopy(s.b, from, b, 0, to - from);
                return new StrV(b, to - from);
            }
            case SPLIT_ONCE -> {
                StrV s = (StrV) eval(args.get(0).expr(), slots);
                byte[] sep = Stdlib.pattern(eval(args.get(1).expr(), slots), meter);
                if (sep.length == 0) throw new Trap(Code.R0013, c.span, "split_once separator is empty");
                int at = Stdlib.find(s.b, s.len, sep, 0, meter);
                if (at < 0) return option(null);
                // Each piece has the capacity of its element of the result, at least that of s.
                List<Type> caps = ((Type.TupleT) ((Type.EnumT) c.type).decl().variants.get(1).payload.get(0)).elems();
                int cap0 = ((Type.Str) caps.get(0)).cap();
                int cap1 = ((Type.Str) caps.get(1)).cap();
                meter.bytes((long) cap0 + cap1);
                byte[] before = new byte[cap0];
                System.arraycopy(s.b, 0, before, 0, at);
                byte[] after = new byte[cap1];
                int rest = s.len - at - sep.length;
                System.arraycopy(s.b, at + sep.length, after, 0, rest);
                meter.elements(1);
                return option(new StructV(new Object[] {new StrV(before, at), new StrV(after, rest)}));
            }
            case REPEAT -> {
                byte[] p = Stdlib.pattern(eval(args.get(0).expr(), slots), meter);
                long n = (Long) eval(args.get(1).expr(), slots);
                PrimKind k = Type.kind(args.get(1).expr().type);
                if (!inRange(k, n, Format.MAX_PAD)) {
                    throw new Trap(Code.R0013, c.span, "repeat() needs a count of 0 to " + Format.MAX_PAD + ", found "
                        + IntOps.toString(k, n));
                }
                long total = p.length * n;
                if (total > Format.MAX_PAD) {
                    throw new Trap(Code.R0013, c.span, "repeat() would make " + total + " bytes, more than " + Format.MAX_PAD);
                }
                byte[] out = new byte[(int) total];
                for (int i = 0; i < n; i++) System.arraycopy(p, 0, out, i * p.length, p.length);
                return text(c, out);
            }
            case MIN_BY, MAX_BY -> {
                Expr xs = args.get(0).expr();
                Object v = eval(xs, slots);
                Object[] items = v instanceof ListV l ? l.items : ((ArrV) v).items;
                int n = v instanceof ListV l ? l.len : items.length;
                if (n == 0) {
                    throw new Trap(Code.R0013, c.span, c.builtin.name + "() of an empty " + (v instanceof ListV ? "list" : "array"));
                }
                boolean min = c.builtin == Builtin.MIN_BY;
                Object best = items[0];
                for (int i = 1; i < n; i++) {
                    meter.elements(1);
                    // The first of the smallest: x replaces it only when x comes before it; of the largest, when it comes
                    // before x.
                    if (min ? test(c, items[i], best) : test(c, best, items[i])) best = items[i];
                }
                return stored(xs) ? Values.copy(best, meter) : best;
            }
            case POP_FRONT -> {
                Object xs = ref(args.get(0).expr(), slots).get();
                int n = xs instanceof StrV s ? s.len : ((ListV) xs).len;
                if (n == 0) {
                    throw new Trap(Code.R0012, c.span, "pop_front from an empty " + (xs instanceof StrV ? "str" : "list"));
                }
                return Stdlib.remove(xs, 0, PrimKind.I64, c.span, meter);
            }
            case SWAP -> {
                if (args.size() == 2) {
                    Ref a = ref(args.get(0).expr(), slots);
                    Ref b = ref(args.get(1).expr(), slots);
                    Object x = a.get();
                    a.set(b.get());
                    b.set(x);
                    return null;
                }
                Object xs = ref(args.get(0).expr(), slots).get();
                long i = (Long) eval(args.get(1).expr(), slots);
                long j = (Long) eval(args.get(2).expr(), slots);
                Stdlib.swap(xs, i, Type.kind(args.get(1).expr().type), j, Type.kind(args.get(2).expr().type), c.span, meter);
                return null;
            }
            case UNION, INTERSECTION, DIFFERENCE -> {
                Type.SetT st = (Type.SetT) c.type;
                ListV a = (ListV) eval(args.get(0).expr(), slots);
                ListV b = (ListV) eval(args.get(1).expr(), slots);
                return Stdlib.setOp(c.builtin, a, b, st, c.span, meter);
            }
            case ASSERT_EQ -> throw new IllegalStateException("assert_eq is an assert statement");
            case PANIC -> {
                StrV s = (StrV) eval(args.get(0).expr(), slots);
                ByteArrayOutputStream msg = new ByteArrayOutputStream();
                msg.writeBytes(utf8("panic: "));
                msg.write(s.b, 0, s.len);
                throw new Trap(Code.R0010, c.span, msg.toByteArray());
            }
        }
        throw new IllegalStateException();
    }

    /**
     * `e?` meeting an Option.None or a Result.Err, on its way out of the function, which {@link #invoke} returns: thrown
     * from inside an expression, which statements do not return from.
     */
    private static final class Escape extends RuntimeException {
        final transient Object value;

        Escape(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    /** `e?`: the value in the Option or Result e, or its Option.None or Result.Err returned from the function. */
    private Object tryValue(Try t, Object[] slots) {
        EnumV e = (EnumV) eval(t.inner, slots);
        boolean copy = stored(t.inner);
        if (succeeded(t.inner.type, e)) return copy ? Values.copy(e.payload[0], meter) : e.payload[0];
        Variant fail = ((Type.EnumT) t.ret).decl().variants.get(e.payload.length == 0 ? 0 : 1);
        if (fail.payload.isEmpty()) throw new Escape(new EnumV(fail.index, new Object[0]));
        Object err = copy ? Values.copy(e.payload[0], meter) : e.payload[0];
        throw new Escape(new EnumV(fail.index, new Object[] {Values.coerce(err, fail.payload.get(0), meter)}));
    }

    /** Whether e, an Option or Result (of type t), is Option.Some or Result.Ok. */
    private static boolean succeeded(Type t, EnumV e) {
        String name = ((Type.EnumT) t).decl().variants.get(e.tag).name;
        return name.equals("Some") || name.equals("Ok");
    }

    /** Option.Some(v), or Option.None for null. */
    private static EnumV option(Object v) {
        return v == null ? new EnumV(0, new Object[0]) : new EnumV(1, new Object[] {v});
    }

    /**
     * Calls the function passed to a builtin such as any or sort_by (c.fnArg) with the given elements, and returns its
     * result. Like a call written in the program, it is charged where it is made.
     */
    private boolean test(Call c, Object... values) {
        FnDecl f = c.fnArg;
        Object[] a = new Object[f.params.size()];
        for (int i = 0; i < a.length; i++) a[i] = Values.coerce(values[i], f.paramVars.get(i).type, meter);
        Span caller = meter.at();
        Object result = invoke(f, a, c.span);
        meter.at(caller);
        return (Boolean) result;
    }

    /**
     * Whether min or max of a and b is b: b < a or b > a, so that a NaN, or an equal value, gives a, as in the C
     * backend.
     */
    private static boolean takesSecond(Builtin which, Type t, Object a, Object b) {
        boolean min = which == Builtin.MIN;
        return Type.isFloat(t) ? (min ? (Double) b < (Double) a : (Double) b > (Double) a)
            : Integer.signum(IntOps.compare(Type.kind(t), (Long) b, (Long) a)) == (min ? -1 : 1);
    }
}
