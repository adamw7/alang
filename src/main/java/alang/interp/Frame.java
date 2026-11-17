package alang.interp;

import alang.ast.Ast.ConstDecl;
import alang.ast.Ast.Field;
import alang.ast.Ast.FnDecl;
import alang.ast.Ast.LocalVar;
import alang.ast.Ast.Routine;
import alang.diag.Span;
import alang.interp.Values.ArrV;
import alang.interp.Values.ListV;
import alang.interp.Values.MapV;
import alang.interp.Values.StrV;
import alang.interp.Values.StructV;
import alang.types.PrimKind;
import alang.types.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One call of a function, or the run of a test: its routine, the slots of its variables, and for a debugger
 * the statement it is at and the variables in scope there. A debugger reads it; only the interpreter changes it.
 */
public final class Frame {
    private final String name;
    private final Span site;
    private final Routine routine;
    private final Object[] slots;
    /** The locals that hold a value and are in scope, in the order they were declared; kept only with a debugger. */
    final List<LocalVar> live = new ArrayList<>();
    /** How many of {@link #live} there were when the statement being run started. */
    int mark;
    /** The statement being run: in an outer frame, the one making the call. */
    Span at;

    Frame(String name, Span site, Routine routine, Object[] slots) {
        this.name = name;
        this.site = site;
        this.routine = routine;
        this.slots = slots;
        this.at = site;
    }

    void bind(LocalVar v) {
        if (!live.contains(v)) live.add(v);
    }

    void truncate(int n) {
        while (live.size() > n) live.remove(live.size() - 1);
    }

    /** "fn name" or "test \"name\"", as a trap's trace names it. */
    public String name() {
        return name;
    }

    /** The function or test. */
    public Routine routine() {
        return routine;
    }

    /** Where it was called from: for the entry point, its own declaration. */
    public Span site() {
        return site;
    }

    /** The statement being run; in an outer frame, the one that made the call. */
    public Span at() {
        return at;
    }

    /** A variable's name, type and value, as a trap message writes the value. */
    public record Variable(String name, Type type, String value) {}

    /** The parameters, then each variable in scope that holds a value, in the order they were declared. */
    public List<Variable> variables() {
        List<Variable> out = new ArrayList<>();
        for (LocalVar v : visible()) out.add(new Variable(v.name, v.type, text(value(v), v.type)));
        return out;
    }

    private List<LocalVar> visible() {
        List<LocalVar> vars = new ArrayList<>();
        if (routine instanceof FnDecl f) {
            for (LocalVar p : f.paramVars) {
                if (slots[p.slot] != null) vars.add(p);
            }
        }
        for (LocalVar v : live) {
            if (!vars.contains(v) && slots[v.slot] != null) vars.add(v);
        }
        return vars;
    }

    private Object value(LocalVar v) {
        Object o = slots[v.slot];
        return v.byRef ? ((Interpreter.Ref) o).get() : o;
    }

    private static String text(Object v, Type t) {
        return new String(Format.toBytes(v, t), StandardCharsets.UTF_8);
    }

    /** A name that is not in scope, a field the value does not have, an index out of range: what went wrong. */
    public static final class LookupException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        LookupException(String m) {
            super(m, null, false, false);
        }
    }

    /**
     * The value of {@code path}: a variable in scope, or one of {@code constants}, then any number of `.field`,
     * `.0` (of a tuple) and `[i]` (of an array, list or str; for a map, its key: a number, "text", 'c',
     * true or false). Throws a LookupException that says what is wrong.
     */
    public Variable lookup(String path, List<ConstDecl> constants) {
        Path p = new Path(path.strip());
        String head = p.name();
        if (head.isEmpty()) throw new LookupException("expected a variable name, found `" + path.strip() + "`");
        Object v = null;
        Type t = null;
        List<LocalVar> vars = visible();
        for (int i = vars.size() - 1; i >= 0 && t == null; i--) {
            if (vars.get(i).name.equals(head)) {
                v = value(vars.get(i));
                t = vars.get(i).type;
            }
        }
        for (int i = 0; i < constants.size() && t == null; i++) {
            ConstDecl c = constants.get(i);
            if (c.name.equals(head) && c.defaultOf == null && c.value != null) {
                v = c.value;
                t = c.type;
            }
        }
        if (t == null) {
            List<String> names = new ArrayList<>();
            for (LocalVar lv : vars) names.add(lv.name);
            throw new LookupException("`" + head + "` is not a variable in scope here"
                + (names.isEmpty() ? "; none is" : "; these are: " + String.join(", ", names)));
        }
        String done = head;
        while (!p.atEnd()) {
            char c = p.peek();
            if (c == '.') {
                p.pos++;
                String f = p.name();
                if (f.isEmpty()) f = p.digits();
                if (f.isEmpty()) throw new LookupException("expected a field name or a number after `" + done + ".`");
                Object[] got = field(v, t, f, done);
                v = got[0];
                t = (Type) got[1];
                done += "." + f;
            } else if (c == '[') {
                p.pos++;
                String key = p.until(']');
                Object[] got = index(v, t, key.strip(), done);
                v = got[0];
                t = (Type) got[1];
                done += "[" + key.strip() + "]";
            } else {
                throw new LookupException("unexpected `" + p.rest() + "` after `" + done + "`; write `name`, `name.field`, "
                    + "`name.0` or `name[i]`");
            }
        }
        return new Variable(done, t, text(v, t));
    }

    private static Object[] field(Object v, Type t, String f, String done) {
        if (t instanceof Type.StructT st) {
            List<Field> fields = st.decl().fields;
            for (int i = 0; i < fields.size(); i++) {
                if (fields.get(i).name.equals(f)) return new Object[] {((StructV) v).fields[i], fields.get(i).type};
            }
            List<String> names = new ArrayList<>();
            for (Field fd : fields) names.add(fd.name);
            throw new LookupException("`" + done + "` (" + t + ") has no field `" + f + "`; its fields are: "
                + String.join(", ", names));
        }
        if (t instanceof Type.TupleT tt && !f.isEmpty() && Character.isDigit(f.charAt(0))) {
            int i = Integer.parseInt(f);
            if (i < tt.elems().size()) return new Object[] {((StructV) v).fields[i], tt.elems().get(i)};
            throw new LookupException("`" + done + "` (" + t + ") has no element " + f);
        }
        throw new LookupException("`" + done + "` (" + t + ") has no field `" + f + "`");
    }

    private static Object[] index(Object v, Type t, String key, String done) {
        if (t instanceof Type.MapT mt) {
            Object k = key(key, mt.key(), done);
            MapV m = (MapV) v;
            try {
                int i = Stdlib.mapIndex(m, k, mt.key(), null, new Meter(0));
                return new Object[] {m.vals[i], mt.value()};
            } catch (Trap e) {
                throw new LookupException("`" + done + "` has no key " + key);
            }
        }
        long i;
        try {
            i = Long.parseLong(key);
        } catch (NumberFormatException e) {
            throw new LookupException("expected a number in `" + done + "[...]`, found `" + key + "`");
        }
        int n;
        Object elem;
        Type et;
        switch (t) {
            case Type.ArrayT a -> {
                n = ((ArrV) v).items.length;
                elem = i >= 0 && i < n ? ((ArrV) v).items[(int) i] : null;
                et = a.elem();
            }
            case Type.ListT l -> {
                n = ((ListV) v).len;
                elem = i >= 0 && i < n ? ((ListV) v).items[(int) i] : null;
                et = l.elem();
            }
            case Type.SetT st -> {
                n = ((ListV) v).len;
                elem = i >= 0 && i < n ? ((ListV) v).items[(int) i] : null;
                et = st.elem();
            }
            case Type.Str s -> {
                n = ((StrV) v).len;
                elem = i >= 0 && i < n ? (Object) (long) (((StrV) v).b[(int) i] & 0xFF) : null;
                et = Type.U8;
            }
            default -> throw new LookupException("`" + done + "` (" + t + ") cannot be indexed");
        }
        if (elem == null) {
            throw new LookupException("index " + key + " is out of range: `" + done + "` has " + n + " element(s)");
        }
        return new Object[] {elem, et};
    }

    /** A map key written in a command: a number, "text", 'c', true or false, of the map's key type. */
    private static Object key(String key, Type kt, String done) {
        if (kt instanceof Type.Str s) {
            if (key.length() < 2 || !key.startsWith("\"") || !key.endsWith("\"")) {
                throw new LookupException("the keys of `" + done + "` are strs: write one in double quotes, as `"
                    + done + "[\"key\"]`");
            }
            byte[] b = key.substring(1, key.length() - 1).getBytes(StandardCharsets.UTF_8);
            if (b.length > s.cap()) throw new LookupException("`" + done + "` has no key " + key);
            return new StrV(Arrays.copyOf(b, s.cap()), b.length);
        }
        if (kt instanceof Type.Prim p) {
            if (p.kind() == PrimKind.BOOL && (key.equals("true") || key.equals("false"))) return key.equals("true");
            if (key.length() == 3 && key.charAt(0) == '\'' && key.charAt(2) == '\'' && key.charAt(1) < 128) {
                return (long) key.charAt(1);
            }
            if (!p.kind().isFloat && p.kind() != PrimKind.BOOL) {
                try {
                    return Long.parseLong(key);
                } catch (NumberFormatException e) {
                    // reported below
                }
            }
        }
        throw new LookupException("cannot look up a key of type " + kt + " in `" + done + "` from `" + key + "`");
    }

    /** A cursor over the text of a path. */
    private static final class Path {
        final String s;
        int pos;

        Path(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        char peek() {
            return s.charAt(pos);
        }

        String rest() {
            return s.substring(pos);
        }

        String name() {
            int start = pos;
            if (pos < s.length() && (Character.isLetter(s.charAt(pos)) || s.charAt(pos) == '_')) {
                while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) pos++;
            }
            return s.substring(start, pos);
        }

        String digits() {
            int start = pos;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            return s.substring(start, pos);
        }

        String until(char end) {
            int start = pos;
            boolean quoted = false;
            while (pos < s.length() && (quoted || s.charAt(pos) != end)) {
                if (s.charAt(pos) == '"') quoted = !quoted;
                pos++;
            }
            if (pos >= s.length()) throw new LookupException("missing `" + end + "` in `" + s + "`");
            String inner = s.substring(start, pos);
            pos++;
            return inner;
        }
    }
}
