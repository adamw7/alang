package alang.interp;

import alang.ast.Ast.*;
import alang.ast.Builtin;
import alang.diag.Code;
import alang.diag.Source;
import alang.diag.Span;
import alang.interp.Values.ArrV;
import alang.interp.Values.EnumV;
import alang.interp.Values.ListV;
import alang.interp.Values.MapV;
import alang.interp.Values.StrV;
import alang.interp.Values.StructV;
import alang.types.PrimKind;
import alang.types.Type;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

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
    private final long maxSteps;
    private long steps;
    private Object retVal;

    private record CallSite(String name, Span site) {}

    private final Deque<CallSite> callStack = new ArrayDeque<>();

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
        this.source = source;
        this.out = out;
        this.maxSteps = maxSteps;
    }

    /** Evaluates a constant expression (the checker guarantees it needs no frame). */
    public static Object evalConst(Expr e) {
        Interpreter in = new Interpreter(null, OutputStream.nullOutputStream(), 0);
        return in.owned(e, null);
    }

    public long steps() {
        return steps;
    }

    // =================================================================== entry points

    /** Calls a function with already-converted argument values. */
    public Object call(FnDecl f, Object[] args, Span site) {
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
                callStack.push(new CallSite("fn main", main.nameSpan));
                fillTrace(t);
                throw t;
            }
            args[0] = new StrV(Arrays.copyOf(input, cap), input.length);
        }
        call(main, args, main.nameSpan);
    }

    public void runTest(TestDecl t) {
        try {
            Object[] slots = new Object[t.locals.size()];
            callStack.push(new CallSite("test \"" + t.name + "\"", t.span));
            execBlock(t.body, slots);
            callStack.pop();
        } catch (Trap trap) {
            fillTrace(trap);
            throw trap;
        } finally {
            flush();
        }
    }

    private void fillTrace(Trap t) {
        if (!t.trace.isEmpty()) return;
        int i = 0;
        for (CallSite c : callStack) {
            boolean outermost = ++i == callStack.size();
            // A call in an imported file names the file; the entry point is always in the main one.
            String where = outermost ? " (entry point, line " + c.site.line() + ")"
                : " (called at " + c.site.where(source) + ")";
            t.trace.add(c.name + where);
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
        if (++steps > maxSteps && maxSteps > 0) {
            throw new Trap(Code.R0011, at, "step budget of " + maxSteps + " statements exhausted");
        }
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
        callStack.push(new CallSite("fn " + f.name, site));
        for (Expr r : f.requires) {
            if (!(Boolean) eval(r, slots)) {
                throw new Trap(Code.R0006, site, "precondition of `" + f.name + "` failed: requires " + text(r.span));
            }
        }
        retVal = null;
        execBlock(f.body, slots);
        Object result = retVal == null ? null : Values.coerce(retVal, f.ret);
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
        for (Stmt s : b.stmts()) {
            int r = exec(s, slots);
            if (r != NORMAL) return r;
        }
        return NORMAL;
    }

    private int exec(Stmt s, Object[] slots) {
        step(s.span);
        return switch (s) {
            case Let l -> {
                slots[l.var.slot] = Values.coerce(owned(l.init, slots), l.var.type);
                yield NORMAL;
            }
            case Assign a -> {
                assign(a, slots);
                yield NORMAL;
            }
            case Destructure d -> {
                StructV t = (StructV) Values.coerce(owned(d.init, slots), d.init.type);
                for (int i = 0; i < d.vars.size(); i++) {
                    if (d.vars.get(i) != null) slots[d.vars.get(i).slot] = t.fields[i];
                }
                yield NORMAL;
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
            Object v = Values.coerce(owned(a.value, slots), t);
            ref(a.target, slots).set(v);
            return;
        }
        Object rhs = eval(a.value, slots);
        Ref r = ref(a.target, slots);
        r.set(arith(a.op, Type.kind(t), Type.kind(a.value.type), r.get(), rhs, a.span));
    }

    /** What a loop does after its body: go on with the next iteration, leave the loop, or pass the result out. */
    private static final int GO = 0;
    private static final int LEAVE = 1;
    private static final int OUT = 2;

    /**
     * The loop that a `break label;` or `continue label;` being carried out jumps to; null for one without a
     * label, which jumps to the innermost loop.
     */
    private Loop jumpTarget;

    private int after(Loop l, int r) {
        if (r == NORMAL) return GO;
        if (r == RETURN || jumpTarget != null && jumpTarget != l) return OUT;
        jumpTarget = null;
        return r == BREAK ? LEAVE : GO;
    }

    private int whileLoop(While w, Object[] slots) {
        Object lim = eval(w.limit, slots);
        PrimKind lk = Type.kind(w.limit.type);
        long count = 0;
        while ((Boolean) eval(w.cond, slots)) {
            boolean exceeded = lk == PrimKind.U64 ? Long.compareUnsigned(count, (Long) lim) >= 0 : count >= (Long) lim;
            if (exceeded) {
                throw new Trap(Code.R0008, w.span, "while loop exceeded its limit of " + IntOps.toString(lk, (Long) lim)
                    + " iterations");
            }
            count++;
            step(w.span);
            int r = execBlock(w.body, slots);
            int a = after(w, r);
            if (a == LEAVE) break;
            if (a == OUT) return r;
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
            int r = execBlock(f.body, slots);
            int a = after(f, r);
            if (a == LEAVE) break;
            if (a == OUT) return r;
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
        if (coll instanceof MapV m) {
            // A map gives its keys in order, or with `for k, v in m` each key with its value.
            for (int k = 0; k < m.len; k++) {
                int i = f.rev ? m.len - 1 - k : k;
                step(f.span);
                if (f.indexVar != null) {
                    slots[f.indexVar.slot] = m.keys[i];
                    slots[f.var.slot] = m.vals[i];
                } else {
                    slots[f.var.slot] = m.keys[i];
                }
                int r = execBlock(f.body, slots);
                int a = after(f, r);
                if (a == LEAVE) break;
                if (a == OUT) return r;
            }
            return NORMAL;
        }
        Object[] items = coll instanceof ArrV a ? a.items : coll instanceof ListV l ? l.items : null;
        int n = length(coll);
        for (int k = 0; k < n; k++) {
            int i = f.rev ? n - 1 - k : k;
            step(f.span);
            if (f.indexVar != null) slots[f.indexVar.slot] = (long) i;
            slots[f.var.slot] = items != null ? items[i] : (Object) (long) (((StrV) coll).b[i] & 0xFF);
            int r = execBlock(f.body, slots);
            int a = after(f, r);
            if (a == LEAVE) break;
            if (a == OUT) return r;
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
                int r = execBlock(f.body, slots);
                int a = after(f, r);
                if (a == LEAVE) break;
                if (a == OUT) return r;
            }
            return NORMAL;
        }
        byte[] sep = c.builtin == Builtin.SPLIT ? Stdlib.pattern(eval(c.args.get(1).expr(), slots)) : null;
        if (sep != null && sep.length == 0) throw new Trap(Code.R0013, c.span, "split separator is empty");
        StrV piece = new StrV(new byte[((Type.Str) src.type).cap()], 0);
        int pos = 0;
        while (true) {
            int start;
            int end;
            if (sep != null) {
                int hit = Stdlib.find(s.b, s.len, sep, pos);
                start = pos;
                end = hit < 0 ? s.len : hit;
                pos = hit < 0 ? -1 : hit + sep.length;
            } else {
                while (pos < s.len && Stdlib.isSpace(s.b[pos] & 0xFF)) pos++;
                if (pos == s.len) break;
                start = pos;
                while (pos < s.len && !Stdlib.isSpace(s.b[pos] & 0xFF)) pos++;
                end = pos;
            }
            step(f.span);
            int before = piece.len;
            piece.len = end - start;
            System.arraycopy(s.b, start, piece.b, 0, piece.len);
            if (before > piece.len) Arrays.fill(piece.b, piece.len, before, (byte) 0);
            if (f.indexVar != null) slots[f.indexVar.slot] = index++;
            slots[f.var.slot] = piece;
            int r = execBlock(f.body, slots);
            int a = after(f, r);
            if (a == LEAVE) break;
            if (a == OUT) return r;
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

    private boolean matches(Pattern p, Object v, Object[] slots) {
        return switch (p) {
            case WildPat w -> true;
            case VariantPat vp -> {
                EnumV e = (EnumV) v;
                if (e.tag != vp.variant.index) yield false;
                for (int i = 0; i < vp.vars.size(); i++) {
                    LocalVar lv = vp.vars.get(i);
                    if (lv != null) slots[lv.slot] = e.payload[i];
                }
                yield true;
            }
            case IntPat ip -> ((Long) v) == ip.value.longValue();
            case StrPat sp -> {
                StrV s = (StrV) v;
                yield s.len == sp.value.length && Arrays.equals(s.b, 0, s.len, sp.value, 0, sp.value.length);
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

    /** m[k] as a place: reading it needs the key (R0003), storing to it adds the key if it is new (R0004 when full). */
    private record MapRef(MapV m, Object key, Type keyType, Span at) implements Ref {
        public Object get() {
            return Stdlib.mapGet(m, key, keyType, at);
        }

        public void set(Object v) {
            Stdlib.mapPut(m, key, v, keyType, at);
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
                    Object key = Values.coerce(owned(ix.index, slots), mt.key());
                    return new MapRef((MapV) container, key, mt.key(), ix.span);
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
        return e.isPlace() && Type.isAggregate(e.type) ? Values.copy(v) : v;
    }

    private Object eval(Expr e, Object[] slots) {
        return switch (e) {
            case IntLit i -> Type.isFloat(i.type) ? (Object) i.floatValue(Type.kind(i.type)) : (Object) i.value.longValue();
            case FloatLit f -> f.valueAs(Type.kind(f.type));
            case StrLit s -> new StrV(s.value.clone(), s.value.length);
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
                    yield Stdlib.mapGet((MapV) container, eval(ix.index, slots), mt.key(), ix.span);
                }
                long i = (Long) eval(ix.index, slots);
                yield elementRef(container, i, Type.kind(ix.index.type), ix.span).get();
            }
            case Call c -> c.builtin != null ? builtin(c, slots) : callUser(c, slots);
            case VariantCall v -> {
                Object[] payload = new Object[v.args.size()];
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = Values.coerce(owned(v.args.get(i).expr(), slots), v.variant.payload.get(i));
                }
                yield new EnumV(v.variant.index, payload);
            }
            case StructLit s -> {
                Object[] given = new Object[s.fields.size()];
                for (int i = 0; i < given.length; i++) given[i] = owned(s.fields.get(i).value(), slots);
                Object[] fields = new Object[s.decl.fields.size()];
                for (int i = 0; i < fields.length; i++) {
                    fields[i] = Values.coerce(given[s.order[i]], s.decl.fields.get(i).type);
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
            case ArrayLit a when a.type instanceof Type.MapT mt -> {
                MapV m = new MapV(new Object[mt.cap()], new Object[mt.cap()], 0);
                for (Expr x : a.elems) {
                    StructV pair = (StructV) owned(x, slots);
                    Stdlib.mapPut(m, Values.coerce(pair.fields[0], mt.key()), Values.coerce(pair.fields[1], mt.value()), mt.key(), a.span);
                }
                yield m;
            }
            case ArrayLit a when a.type instanceof Type.SetT st -> {
                ListV s = new ListV(new Object[st.cap()], 0);
                for (Expr x : a.elems) Stdlib.setInsert(s, Values.coerce(owned(x, slots), st.elem()), st.elem(), a.span);
                yield s;
            }
            case ArrayLit a -> {
                Type elem = Type.elementOf(a.type);
                Object[] vals = new Object[a.elems.size()];
                for (int i = 0; i < vals.length; i++) vals[i] = Values.coerce(owned(a.elems.get(i), slots), elem);
                yield a.type instanceof Type.ListT l ? new ListV(Arrays.copyOf(vals, l.cap()), vals.length) : new ArrV(vals);
            }
            case IfExpr i -> Values.coerce(owned((Boolean) eval(i.cond, slots) ? i.then : i.otherwise, slots), i.type);
            case SliceExpr s -> {
                Object xs = eval(s.target, slots);
                long from = s.from == null ? 0L : (Long) eval(s.from, slots);
                long to = s.to == null ? (long) length(xs) : (Long) eval(s.to, slots);
                PrimKind fk = s.from == null ? PrimKind.I64 : Type.kind(s.from.type);
                PrimKind tk = s.to == null ? PrimKind.I64 : Type.kind(s.to.type);
                yield Stdlib.slice(xs, s.type, from, fk, to, tk, s.span);
            }
            case TupleLit t -> {
                List<Type> elems = ((Type.TupleT) t.type).elems();
                Object[] vals = new Object[t.elems.size()];
                for (int i = 0; i < vals.length; i++) vals[i] = Values.coerce(owned(t.elems.get(i), slots), elems.get(i));
                yield new StructV(vals);
            }
            case Repeat r -> {
                Type elem = Type.elementOf(r.type);
                Object v = Values.coerce(owned(r.value, slots), elem);
                int cap = r.type instanceof Type.ListT l ? l.cap() : r.n;
                Object[] items = new Object[cap];
                for (int i = 0; i < r.n; i++) items[i] = i == 0 ? v : Values.copy(v);
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
            args[i] = p.byRef ? ref(a, slots) : Values.coerce(eval(a, slots), p.type);
        }
        return invoke(f, args, c.span);
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
    private static StrV text(Call c, byte[] bytes) {
        if (c.asText) return new StrV(bytes, bytes.length);
        int cap = ((Type.Str) c.type).cap();
        if (bytes.length > cap) {
            throw new Trap(Code.R0004, c.span, c.name + " needs " + bytes.length + " bytes, more than str capacity " + cap);
        }
        return new StrV(Arrays.copyOf(bytes, cap), bytes.length);
    }

    private boolean compare(Binary b, Object l, Object r) {
        Type t = b.left.type;
        if (b.op.isEquality()) return Values.equal(l, r, t) == (b.op == BinOp.EQ);
        if (t instanceof Type.Str) {
            int c = Stdlib.compare(l, r, t);
            return switch (b.op) {
                case LT -> c < 0;
                case LE -> c <= 0;
                case GT -> c > 0;
                default -> c >= 0;
            };
        }
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
        int c = IntOps.compare(Type.kind(t), (Long) l, (Long) r);
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
    private static Object resize(Resize r, Object v) {
        int n = length(v);
        int cap = r.type instanceof Type.Str s ? s.cap() : ((Type.ListT) r.type).cap();
        if (n > cap) {
            String what = v instanceof StrV ? n + " bytes" : n + " elements";
            throw new Trap(Code.R0004, r.span, "a " + (v instanceof StrV ? "str" : "list") + " of " + what
                + " does not fit in " + r.type);
        }
        if (v instanceof StrV s) return new StrV(Arrays.copyOf(s.b, cap), n);
        ListV l = (ListV) v;
        Object[] items = new Object[cap];
        for (int i = 0; i < n; i++) items[i] = Values.copy(l.items[i]);
        return new ListV(items, n);
    }

    private Object convert(Convert c, Object v) {
        if (v instanceof StrV s) {
            // Not a ?: expression, which would turn the long of an integer into a double.
            if (c.target.isFloat) return Stdlib.parseFloat(s, c.span);
            return Stdlib.parseInt(s, c.target, c.span);
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
                // Every argument is evaluated before anything is written, so a trap prints nothing.
                Object[] values = new Object[args.size()];
                for (int i = 0; i < values.length; i++) values[i] = eval(args.get(i).expr(), slots);
                for (int i = 0; i < values.length; i++) Format.format(printSink, values[i], args.get(i).expr().type, false);
                printSink.write('\n');
                printSink.drain();
                return null;
            }
            case LEN -> {
                return (long) length(eval(args.get(0).expr(), slots));
            }
            case CAP -> {
                Type t = args.get(0).expr().type;
                return (long) switch (t) {
                    case Type.Str s -> s.cap();
                    case Type.MapT m -> m.cap();
                    case Type.SetT s -> s.cap();
                    default -> ((Type.ListT) t).cap();
                };
            }
            case PUSH -> {
                Object container = ref(args.get(0).expr(), slots).get();
                Type elem = Type.elementOf(args.get(0).expr().type);
                Object v = Values.coerce(owned(args.get(1).expr(), slots), elem);
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
                    Arrays.fill(m.keys, null);
                    Arrays.fill(m.vals, null);
                    m.len = 0;
                } else if (container instanceof ListV l) {
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
                    Format.format(part, values[i], args.get(i).expr().type, false);
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
                Object a = eval(args.get(0).expr(), slots);
                Object b = eval(args.get(1).expr(), slots);
                // b < a and b > a, so that a NaN operand gives a, as in the C backend.
                boolean min = c.builtin == Builtin.MIN;
                boolean takeB = Type.isFloat(c.type) ? (min ? (Double) b < (Double) a : (Double) b > (Double) a)
                    : Integer.signum(IntOps.compare(Type.kind(c.type), (Long) b, (Long) a)) == (min ? -1 : 1);
                return takeB ? b : a;
            }
            case SQRT, FLOOR, CEIL, ROUND, TRUNC, EXP, LOG, LOG2, LOG10, SIN, COS, TAN, ASIN, ACOS, ATAN -> {
                double d = (Double) eval(args.get(0).expr(), slots);
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
                if (v instanceof StrV s) return Stdlib.mapCase(s, upper);
                return upper ? Stdlib.toUpper((Long) v) : Stdlib.toLower((Long) v);
            }
            case CONTAINS, INDEX_OF -> {
                Object xs = eval(args.get(0).expr(), slots);
                Object x = eval(args.get(1).expr(), slots);
                Type ct = args.get(0).expr().type;
                if (ct instanceof Type.MapT mt) return Stdlib.find(((MapV) xs).keys, ((MapV) xs).len, x, mt.key()) >= 0;
                if (ct instanceof Type.SetT st) return Stdlib.find(((ListV) xs).items, ((ListV) xs).len, x, st.elem()) >= 0;
                long at = xs instanceof StrV s ? Stdlib.find(s.b, s.len, Stdlib.pattern(x), 0)
                    : Stdlib.indexOf(xs, x, Type.elementOf(args.get(0).expr().type));
                return c.builtin == Builtin.CONTAINS ? (Object) (at >= 0) : (Object) at;
            }
            case COUNT -> {
                Object xs = eval(args.get(0).expr(), slots);
                Object x = eval(args.get(1).expr(), slots);
                return xs instanceof StrV s ? Stdlib.count(s, Stdlib.pattern(x), c.span)
                    : Stdlib.count(xs, x, Type.elementOf(args.get(0).expr().type));
            }
            case STARTS_WITH, ENDS_WITH -> {
                StrV s = (StrV) eval(args.get(0).expr(), slots);
                byte[] p = Stdlib.pattern(eval(args.get(1).expr(), slots));
                return c.builtin == Builtin.STARTS_WITH ? Stdlib.startsWith(s, p) : Stdlib.endsWith(s, p);
            }
            case SLICE -> {
                Object xs = eval(args.get(0).expr(), slots);
                long from = (Long) eval(args.get(1).expr(), slots);
                long to = (Long) eval(args.get(2).expr(), slots);
                return Stdlib.slice(xs, c.type, from, Type.kind(args.get(1).expr().type), to,
                    Type.kind(args.get(2).expr().type), c.span);
            }
            case TRIM -> {
                return Stdlib.trim((StrV) eval(args.get(0).expr(), slots));
            }
            case IS_UTF8 -> {
                return Stdlib.isUtf8((StrV) eval(args.get(0).expr(), slots));
            }
            case CHAR_COUNT -> {
                return Stdlib.charCount((StrV) eval(args.get(0).expr(), slots));
            }
            case CHARS -> {
                return Stdlib.chars((StrV) eval(args.get(0).expr(), slots), (Type.ListT) c.type, c.span);
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
                    c.span);
            }
            case TRY_SPLIT, TRY_WORDS -> {
                ListV parts = (ListV) ref(args.get(0).expr(), slots).get();
                StrV s = (StrV) eval(args.get(1).expr(), slots);
                byte[] sep = c.builtin == Builtin.TRY_SPLIT ? Stdlib.pattern(eval(args.get(2).expr(), slots)) : null;
                return Stdlib.trySplit(parts, s, sep, (Type.ListT) args.get(0).expr().type, c.span);
            }
            case FIXED -> {
                double x = (Double) eval(args.get(0).expr(), slots);
                long d = (Long) eval(args.get(1).expr(), slots);
                PrimKind k = Type.kind(args.get(1).expr().type);
                if (!inRange(k, d, Format.MAX_DECIMALS)) {
                    throw new Trap(Code.R0013, c.span, "fixed() needs 0 to " + Format.MAX_DECIMALS + " decimals, found "
                        + IntOps.toString(k, d));
                }
                return text(c, Format.formatFixed(x, (int) d).getBytes(StandardCharsets.US_ASCII));
            }
            case PAD_LEFT, PAD_RIGHT -> {
                Expr value = args.get(0).expr();
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                Format.format(ByteSink.of(text), eval(value, slots), value.type, false);
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
                return Stdlib.isFloat((StrV) eval(args.get(0).expr(), slots));
            }
            case IS_INT -> {
                return Stdlib.isInt((StrV) eval(args.get(0).expr(), slots));
            }
            case SPLIT, WORDS -> {
                StrV s = (StrV) eval(args.get(0).expr(), slots);
                byte[] sep = c.builtin == Builtin.SPLIT ? Stdlib.pattern(eval(args.get(1).expr(), slots)) : null;
                return Stdlib.split(c.name, s, sep, (Type.ListT) c.type, c.span);
            }
            case JOIN -> {
                Object parts = eval(args.get(0).expr(), slots);
                byte[] sep = Stdlib.pattern(eval(args.get(1).expr(), slots));
                return Stdlib.join(parts, sep, (Type.Str) c.type, c.span);
            }
            case REPLACE -> {
                StrV s = (StrV) eval(args.get(0).expr(), slots);
                byte[] from = Stdlib.pattern(eval(args.get(1).expr(), slots));
                byte[] to = Stdlib.pattern(eval(args.get(2).expr(), slots));
                return Stdlib.replace(s, from, to, (Type.Str) c.type, c.span);
            }
            case SORT, REVERSE -> {
                Object xs = ref(args.get(0).expr(), slots).get();
                if (c.builtin == Builtin.SORT) Stdlib.sort(xs, args.get(0).expr().type);
                else Stdlib.reverse(xs);
                return null;
            }
            case INSERT -> {
                if (args.get(0).expr().type instanceof Type.MapT mt) {
                    MapV m = (MapV) ref(args.get(0).expr(), slots).get();
                    Object k = Values.coerce(owned(args.get(1).expr(), slots), mt.key());
                    Object v = Values.coerce(owned(args.get(2).expr(), slots), mt.value());
                    Stdlib.mapPut(m, k, v, mt.key(), c.span);
                    return null;
                }
                if (args.get(0).expr().type instanceof Type.SetT st) {
                    ListV s = (ListV) ref(args.get(0).expr(), slots).get();
                    Stdlib.setInsert(s, Values.coerce(owned(args.get(1).expr(), slots), st.elem()), st.elem(), c.span);
                    return null;
                }
                Object xs = ref(args.get(0).expr(), slots).get();
                long i = (Long) eval(args.get(1).expr(), slots);
                Type elem = Type.elementOf(args.get(0).expr().type);
                Object v = Values.coerce(owned(args.get(2).expr(), slots), elem);
                Stdlib.insert(xs, i, Type.kind(args.get(1).expr().type), v, c.span);
                return null;
            }
            case REMOVE -> {
                if (args.get(0).expr().type instanceof Type.MapT mt) {
                    MapV m = (MapV) ref(args.get(0).expr(), slots).get();
                    return Stdlib.mapRemove(m, eval(args.get(1).expr(), slots), mt.key());
                }
                if (args.get(0).expr().type instanceof Type.SetT st) {
                    ListV s = (ListV) ref(args.get(0).expr(), slots).get();
                    return Stdlib.setRemove(s, eval(args.get(1).expr(), slots), st.elem());
                }
                Object xs = ref(args.get(0).expr(), slots).get();
                long i = (Long) eval(args.get(1).expr(), slots);
                return Stdlib.remove(xs, i, Type.kind(args.get(1).expr().type), c.span);
            }
            case COMPARE -> {
                Object a = eval(args.get(0).expr(), slots);
                Object b = eval(args.get(1).expr(), slots);
                return (long) Integer.signum(Stdlib.compare(a, b, args.get(0).expr().type));
            }
            case GET_OR -> {
                Type.MapT mt = (Type.MapT) args.get(0).expr().type;
                MapV m = (MapV) eval(args.get(0).expr(), slots);
                Object k = eval(args.get(1).expr(), slots);
                Object d = eval(args.get(2).expr(), slots);
                int i = Stdlib.find(m.keys, m.len, k, mt.key());
                return Values.coerce(i >= 0 ? m.vals[i] : d, mt.value());
            }
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
}
