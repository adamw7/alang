package alang.check;

import alang.ast.Ast.ConstDecl;
import alang.ast.Ast.FnDecl;
import alang.ast.Ast.LocalVar;
import alang.ast.Ast.Program;
import alang.ast.Ast.Routine;
import alang.ast.Ast.TestDecl;
import alang.util.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The static memory bound of a program. Because ALang has no heap and no recursion, the
 * worst-case memory of every entry point is a finite number computed here:
 * <pre>stack(f) = frame(f) + max(stack(g) for g called by f)</pre>
 * Frame sizes count parameters (aggregates and `mut` parameters by address), every local
 * variable, and the return value. Compiler temporaries and ABI overhead are not included,
 * so treat the figures as a close estimate of the value storage the program needs.
 */
public final class MemoryReport {
    public record Entry(String label, Routine routine, long frameBytes, long stackBytes) {}

    public final long staticBytes;
    public final List<Entry> routines = new ArrayList<>();
    /** Entry points: main, tools and tests. */
    public final List<Entry> roots = new ArrayList<>();

    private final Map<Routine, Long> frames = new HashMap<>();
    private final Map<Routine, Long> stacks = new HashMap<>();

    private MemoryReport(long staticBytes) {
        this.staticBytes = staticBytes;
    }

    static MemoryReport compute(Program program) {
        long statics = 0;
        for (ConstDecl c : program.itemsOf(ConstDecl.class)) statics = Layout.add(statics, Layout.size(c.type));
        MemoryReport r = new MemoryReport(statics);
        for (FnDecl f : program.itemsOf(FnDecl.class)) {
            Entry e = new Entry("fn " + f.name, f, r.frame(f), r.stack(f));
            r.routines.add(e);
            if (f.tool || f.name.equals("main")) r.roots.add(e);
        }
        for (TestDecl t : program.itemsOf(TestDecl.class)) {
            Entry e = new Entry("test \"" + t.name + "\"", t, r.frame(t), r.stack(t));
            r.routines.add(e);
            r.roots.add(e);
        }
        return r;
    }

    private long frame(Routine r) {
        Long cached = frames.get(r);
        if (cached != null) return cached;
        long bytes = 0;
        int params = r instanceof FnDecl f ? f.paramVars.size() : 0;
        for (int i = 0; i < r.locals.size(); i++) {
            LocalVar v = r.locals.get(i);
            bytes = Layout.add(bytes, i < params ? Layout.paramSize(v.type, v.byRef) : Layout.size(v.type));
        }
        if (r instanceof FnDecl f) bytes = Layout.add(bytes, Layout.size(f.ret));
        frames.put(r, bytes);
        return bytes;
    }

    private long stack(Routine r) {
        Long cached = stacks.get(r);
        if (cached != null) return cached;
        long deepest = 0;
        for (FnDecl c : r.callees) deepest = Math.max(deepest, stack(c));
        long total = Layout.add(frame(r), deepest);
        stacks.put(r, total);
        return total;
    }

    public long maxStack() {
        long m = 0;
        for (Entry e : roots) m = Math.max(m, e.stackBytes);
        return m;
    }

    public Map<String, Object> toJson() {
        List<Object> list = new ArrayList<>();
        for (Entry e : routines) {
            list.add(Json.obj("name", e.label, "frame_bytes", e.frameBytes, "stack_bytes", e.stackBytes,
                "entry_point", roots.contains(e)));
        }
        return Json.obj("heap_bytes", 0, "static_bytes", staticBytes, "max_stack_bytes", maxStack(), "routines", list);
    }
}
