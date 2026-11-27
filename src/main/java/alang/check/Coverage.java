package alang.check;

import alang.ast.Ast.*;
import alang.types.Type;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * What the arms of a match cover, for exhaustiveness (E0306) and unreachable arms (E0307): the usefulness algorithm
 * of Maranget, "Warnings for pattern matching" (2007), over patterns the checker has already typed.
 *
 * <p>A row is a list of patterns, one per column, each column of a known type. Enums, bool, tuples and structs have
 * a finite set of constructors; integers and strs are treated as having infinitely many, so only a pattern that
 * takes every value (`_` or a name) covers them. A range covers an integer constructor only when it contains all of
 * it, so an arm whose values are split among several ranges above it is not reported as unreachable: the check may
 * miss such an arm, but never reports a reachable one.
 *
 * <p>Arrays, lists and strs have one constructor per length, each with that many elements. The lengths that the list
 * patterns of a column tell apart are those below K, one more than the longest they name; every length from K up is
 * matched by the same patterns, so one constructor, "K or more", stands for all of them (as in Rust's exhaustiveness
 * check of slice patterns). An array has its length only.
 */
final class Coverage {
    private Coverage() {}

    /** The pattern that takes every value. */
    static final Pattern WILD = new WildPat(null);

    /** The one constructor of a tuple or struct. */
    private static final Object SINGLE = new Object();

    /** An integer constructor: the values lo..=hi. */
    private record IntRange(BigInteger lo, BigInteger hi) {}

    /**
     * A constructor of arrays, lists and strs: exactly {@code len} elements, or with {@code more} at least that many.
     * Its parts are {@code front} elements from the start and {@code back} from the end: all of them (front = len,
     * back = 0) unless an array is longer than any pattern of its column reaches, whose middle elements every pattern
     * takes with its `..`, and which are left out.
     */
    private record Len(int len, boolean more, int front, int back) {
        Len(int len, boolean more) {
            this(len, more, len, 0);
        }
    }

    /** Whether a value matched by {@code q} is matched by no row of {@code rows}: an arm with pattern q is reachable. */
    static boolean useful(List<Pattern> rows, Pattern q, Type t) {
        List<List<Pattern>> matrix = new ArrayList<>();
        for (Pattern r : rows) matrix.add(List.of(r));
        return useful(matrix, List.of(q), List.of(t));
    }

    /**
     * A value of type {@code t} that no row matches, as a pattern's text (`_` for any value of a type the rows do not
     * take apart), or null when the rows cover every value.
     */
    static String missing(List<Pattern> rows, Type t) {
        List<List<Pattern>> matrix = new ArrayList<>();
        for (Pattern r : rows) matrix.add(List.of(r));
        List<String> w = witness(matrix, List.of(t));
        return w == null ? null : w.get(0);
    }

    /** Whether a pattern takes every value of its type at the top: `_` or a name. */
    static boolean takesAll(Pattern p) {
        return p instanceof WildPat || p instanceof BindPat;
    }

    // ------------------------------------------------------------------ the algorithm

    private static boolean useful(List<List<Pattern>> rows, List<Pattern> q, List<Type> types) {
        if (q.isEmpty()) return rows.isEmpty();
        Pattern q0 = q.get(0);
        List<Pattern> qRest = q.subList(1, q.size());
        if (q0 instanceof OrPat o) {
            for (Pattern alt : o.alts) {
                if (useful(rows, prepend(List.of(alt), qRest), types)) return true;
            }
            return false;
        }
        rows = expand(rows);
        Type t = types.get(0);
        List<Type> tRest = types.subList(1, types.size());
        if (q0 instanceof ListPat) {
            // A pattern with `..` stands for several lengths: q is useful if it is for one of them.
            for (Object c : lengths(rows, q0, t)) {
                if (covers(q0, c) && useful(specialize(rows, c, t), prepend(args(q0, c, t), qRest), prepend(subTypes(c, t), tRest))) {
                    return true;
                }
            }
            return false;
        }
        if (!takesAll(q0)) {
            Object c = head(q0);
            return useful(specialize(rows, c, t), prepend(args(q0, c, t), qRest), prepend(subTypes(c, t), tRest));
        }
        List<Object> sigma = heads(rows);
        List<Object> ctors = byLength(rows, t) ? lengths(rows, null, t) : complete(sigma, t) ? allCtors(t) : null;
        if (ctors != null) {
            for (Object c : ctors) {
                if (useful(specialize(rows, c, t), prepend(wilds(arity(c, t)), qRest), prepend(subTypes(c, t), tRest))) {
                    return true;
                }
            }
            return false;
        }
        return useful(defaults(rows), qRest, tRest);
    }

    private static List<String> witness(List<List<Pattern>> rows, List<Type> types) {
        if (types.isEmpty()) return rows.isEmpty() ? new ArrayList<>() : null;
        rows = expand(rows);
        Type t = types.get(0);
        List<Type> tRest = types.subList(1, types.size());
        List<Object> sigma = heads(rows);
        boolean byLength = byLength(rows, t);
        if (byLength || complete(sigma, t)) {
            for (Object c : byLength ? lengths(rows, null, t) : allCtors(t)) {
                int n = arity(c, t);
                List<String> w = witness(specialize(rows, c, t), prepend(subTypes(c, t), tRest));
                if (w != null) {
                    List<String> out = new ArrayList<>();
                    out.add(render(c, t, w.subList(0, n)));
                    out.addAll(w.subList(n, w.size()));
                    return out;
                }
            }
            return null;
        }
        List<String> w = witness(defaults(rows), tRest);
        if (w == null) return null;
        String head = "_";
        if (!sigma.isEmpty()) {
            for (Object c : allCtors(t)) {
                if (!sigma.contains(c)) {
                    head = render(c, t, java.util.Collections.nCopies(arity(c, t), "_"));
                    break;
                }
            }
        }
        List<String> out = new ArrayList<>();
        out.add(head);
        out.addAll(w);
        return out;
    }

    // ------------------------------------------------------------------ rows

    /** The rows with each or-pattern at the head replaced by one row per alternative. */
    private static List<List<Pattern>> expand(List<List<Pattern>> rows) {
        List<List<Pattern>> out = new ArrayList<>();
        for (List<Pattern> r : rows) expandInto(r, out);
        return out;
    }

    private static void expandInto(List<Pattern> row, List<List<Pattern>> out) {
        if (!row.isEmpty() && row.get(0) instanceof OrPat o) {
            for (Pattern alt : o.alts) expandInto(prepend(List.of(alt), row.subList(1, row.size())), out);
        } else {
            out.add(row);
        }
    }

    /** The rows whose head matches constructor {@code c}, with the head replaced by its parts. */
    private static List<List<Pattern>> specialize(List<List<Pattern>> rows, Object c, Type t) {
        List<List<Pattern>> out = new ArrayList<>();
        for (List<Pattern> r : rows) {
            Pattern h = r.get(0);
            List<Pattern> rest = r.subList(1, r.size());
            if (takesAll(h)) out.add(prepend(wilds(arity(c, t)), rest));
            else if (covers(h, c)) out.add(prepend(args(h, c, t), rest));
        }
        return out;
    }

    /** The rows whose head takes every value, without it. */
    private static List<List<Pattern>> defaults(List<List<Pattern>> rows) {
        List<List<Pattern>> out = new ArrayList<>();
        for (List<Pattern> r : rows) {
            if (takesAll(r.get(0))) out.add(r.subList(1, r.size()));
        }
        return out;
    }

    private static List<Object> heads(List<List<Pattern>> rows) {
        List<Object> out = new ArrayList<>();
        for (List<Pattern> r : rows) {
            Pattern h = r.get(0);
            if (!takesAll(h)) {
                Object c = head(h);
                if (!out.contains(c)) out.add(c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ constructors

    /**
     * Whether a column of type t is taken apart by length: an array, list or str one where a list pattern heads a row.
     * Without one only `_` or a name takes its values, and str patterns alone treat strs as having infinitely many
     * constructors.
     */
    private static boolean byLength(List<List<Pattern>> rows, Type t) {
        return (t instanceof Type.ArrayT || t instanceof Type.ListT || t instanceof Type.Str)
            && rows.stream().anyMatch(r -> r.get(0) instanceof ListPat);
    }

    /**
     * The length constructors of a column of type t whose heads are those of {@code rows} and {@code q0} (when not
     * null): each length below K, and "K or more", where K is one more than the most elements a pattern without `..`
     * names, and at least as many as one with `..` names; none longer than the type holds.
     */
    private static List<Object> lengths(List<List<Pattern>> rows, Pattern q0, Type t) {
        List<Pattern> heads = new ArrayList<>();
        for (List<Pattern> r : rows) heads.add(r.get(0));
        if (q0 != null) heads.add(q0);
        if (t instanceof Type.ArrayT a) {
            // The elements any pattern names from the front (all of them, without `..`) and from the back.
            int front = 0;
            int back = 0;
            for (Pattern h : heads) {
                if (h instanceof ListPat l) {
                    front = Math.max(front, l.rest ? l.prefix.size() : l.named());
                    if (l.rest) back = Math.max(back, l.suffix.size());
                }
            }
            return List.of(a.len() <= front + back ? new Len(a.len(), false) : new Len(a.len(), false, front, back));
        }
        int most = Type.capacity(t);
        int k = 0;
        for (Pattern h : heads) {
            if (h instanceof ListPat l) k = Math.max(k, l.rest ? l.named() : l.named() + 1);
        }
        List<Object> out = new ArrayList<>();
        for (int n = 0; n < k && n <= most; n++) out.add(new Len(n, false));
        if (k <= most) out.add(new Len(k, true));
        return out;
    }

    /** The constructor a pattern that does not take every value starts with. */
    private static Object head(Pattern p) {
        return switch (p) {
            case VariantPat v -> v.variant;
            case BoolPat b -> b.value;
            case TuplePat tp -> SINGLE;
            case StructPat sp -> SINGLE;
            case IntPat i -> new IntRange(i.value, i.value);
            case RangePat r -> new IntRange(r.lo, r.hi);
            case StrPat s -> new String(s.value, StandardCharsets.ISO_8859_1);
            case ListPat l -> new Len(l.named(), l.rest);
            default -> throw new IllegalStateException("no constructor: " + p);
        };
    }

    /** Whether the constructor pattern {@code p} matches every value of constructor {@code c}. */
    private static boolean covers(Pattern p, Object c) {
        return switch (p) {
            case IntPat i -> c instanceof IntRange r && r.lo.equals(i.value) && r.hi.equals(i.value);
            case RangePat rp -> c instanceof IntRange r && rp.lo.compareTo(r.lo) <= 0 && r.hi.compareTo(rp.hi) <= 0;
            // Without `..` exactly its length; with it, every length from what it names up.
            case ListPat l -> c instanceof Len n && (l.rest ? n.len() >= l.named() : !n.more() && n.len() == l.named());
            // A str pattern is a str constructor; of the lengths a column of list patterns has, it takes all of the
            // empty one only, "" being the only str of no bytes.
            case StrPat s -> c instanceof Len n ? s.value.length == 0 && n.len() == 0 && !n.more() : head(p).equals(c);
            default -> head(p).equals(c);
        };
    }

    /** Whether the constructors {@code sigma} are all a type has: integers and strs never are. */
    private static boolean complete(List<Object> sigma, Type t) {
        return switch (t) {
            case Type.EnumT e -> sigma.size() == e.decl().variants.size();
            case Type.TupleT tt -> !sigma.isEmpty();
            case Type.StructT st -> !sigma.isEmpty();
            default -> Type.isBool(t) && sigma.size() == 2;
        };
    }

    /** Every constructor of a type that has finitely many: none for integers and strs. */
    private static List<Object> allCtors(Type t) {
        return switch (t) {
            case Type.EnumT e -> new ArrayList<>(e.decl().variants);
            case Type.TupleT tt -> List.of(SINGLE);
            case Type.StructT st -> List.of(SINGLE);
            default -> Type.isBool(t) ? List.of(true, false) : List.of();
        };
    }

    private static int arity(Object c, Type t) {
        return subTypes(c, t).size();
    }

    private static List<Type> subTypes(Object c, Type t) {
        if (c instanceof Variant v) return v.payload;
        if (c instanceof Len n) return java.util.Collections.nCopies(n.front() + n.back(), Type.elementOf(t));
        if (c == SINGLE && t instanceof Type.TupleT tt) return tt.elems();
        if (c == SINGLE && t instanceof Type.StructT st) return st.decl().fields.stream().map((Field f) -> f.type).toList();
        return List.of();
    }

    /** The parts of a constructor pattern: a variant's payload, a tuple's elements, every field of a struct. */
    private static List<Pattern> args(Pattern p, Object c, Type t) {
        return switch (p) {
            case VariantPat v -> v.args == null ? wilds(arity(c, t)) : v.args;
            case TuplePat tp -> tp.elems;
            // The prefix, an element for each the `..` stands for, and the suffix.
            case ListPat l -> {
                Len n = (Len) c;
                List<Pattern> out = new ArrayList<>(l.prefix);
                if (n.front() + n.back() == n.len()) {
                    out.addAll(wilds(n.len() - l.named()));
                } else {
                    out.addAll(wilds(n.front() - l.prefix.size()));
                    out.addAll(wilds(n.back() - l.suffix.size()));
                }
                out.addAll(l.suffix);
                yield out;
            }
            case StructPat sp -> {
                List<Pattern> out = new ArrayList<>(wilds(arity(c, t)));
                for (int i = 0; i < sp.fields.size(); i++) out.set(sp.fieldIndexes[i], sp.fields.get(i));
                yield out;
            }
            default -> List.of();
        };
    }

    private static String render(Object c, Type t, List<String> parts) {
        if (c instanceof Variant v && t instanceof Type.EnumT e) {
            return e.decl().name + "." + v.name + (parts.isEmpty() ? "" : "(" + String.join(", ", parts) + ")");
        }
        if (c instanceof Boolean b) return b.toString();
        if (c instanceof Len n) {
            List<String> elems = new ArrayList<>(parts.subList(0, n.front()));
            if (n.more() || n.front() + n.back() < n.len()) elems.add("..");
            elems.addAll(parts.subList(n.front(), parts.size()));
            return "[" + String.join(", ", elems) + "]";
        }
        if (t instanceof Type.TupleT) return "(" + String.join(", ", parts) + ")";
        if (t instanceof Type.StructT st) {
            List<String> fields = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                if (!parts.get(i).equals("_")) fields.add(st.decl().fields.get(i).name + ": " + parts.get(i));
            }
            return fields.isEmpty() ? "_" : st.decl().name + "(" + String.join(", ", fields) + ")";
        }
        return "_";
    }

    private static List<Pattern> wilds(int n) {
        List<Pattern> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(WILD);
        return out;
    }

    private static <T> List<T> prepend(List<T> front, List<T> rest) {
        List<T> out = new ArrayList<>(front.size() + rest.size());
        out.addAll(front);
        out.addAll(rest);
        return out;
    }
}
