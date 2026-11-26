package alang.interp;

/**
 * The regular expressions of re_match and re_find: a byte-level pattern compiled to a small program and run by a
 * Pike VM (Thompson's simulation of an NFA, with priorities), so that matching takes time linear in the text for
 * a given pattern and memory fixed by the limits below. The C prelude has the same compiler and machine, step for
 * step, so that both backends accept the same patterns, give the same matches and report the same errors.
 *
 * <p>The syntax: a byte matches itself; `.` any byte but '\n'; `[abc]`, `[a-z]`, `[^...]` a class; `\d \w \s` and
 * `\D \W \S` the usual classes (ASCII); `\n \t \r \0` and `\` before any other character that is not a letter or a
 * digit that character; `^` and `$` the start and end of the text; `( )` a group; `|` alternatives; `*`, `+`, `?`
 * and `{m}`, `{m,}`, `{m,n}` (m and n at most 100) repetition, greedy. A match is leftmost, and among the matches
 * that start there the one that prefers the left alternative and repeats as often as it can, as in Perl; as in
 * Perl, too, an iteration that matches the empty text ends its repetition. One case differs from Perl: a later
 * iteration that would match the empty text through an inner repetition that an earlier one already left at the
 * same byte is not tried first, as each instruction runs once per byte ({@code (a*|b)*} finds 0..2 in "ab", Perl 0..1).
 */
public final class Regex {
    private Regex() {}

    /** The longest pattern, in bytes. */
    public static final int MAX_PATTERN = 256;
    /** The most instructions a pattern compiles to, with its repetitions written out. */
    public static final int MAX_PROGRAM = 1024;
    /** The most character classes a pattern has (each \d and the like counts as one). */
    public static final int MAX_CLASSES = 64;
    /** The deepest nesting of groups. */
    public static final int MAX_DEPTH = 32;
    /** The largest count of a repetition {m,n}. */
    public static final int MAX_REPEAT = 100;

    // Instructions.
    static final int CHAR = 0;
    static final int ANY = 1;
    static final int CLASS = 2;
    static final int SPLIT = 3;
    static final int JMP = 4;
    static final int MATCH = 5;
    static final int BOL = 6;
    static final int EOL = 7;

    // Nodes of the parsed pattern.
    private static final int N_LIT = 0;
    private static final int N_ANY = 1;
    private static final int N_CLASS = 2;
    private static final int N_BOL = 3;
    private static final int N_EOL = 4;
    private static final int N_CAT = 5;
    private static final int N_ALT = 6;
    private static final int N_REPEAT = 7;
    private static final int N_EMPTY = 8;

    /** A pattern that is not valid: why, and the byte of the pattern where that was found. */
    public static final class BadPattern extends RuntimeException {
        public final int at;

        BadPattern(String why, int at) {
            super(why, null, false, false);
            this.at = at;
        }
    }

    /** A compiled pattern: its instructions (op, x, y) and its classes, 256 bits each. */
    public static final class Program {
        final int[] op;
        final int[] x;
        final int[] y;
        final int n;
        final long[][] classes;

        Program(int[] op, int[] x, int[] y, int n, long[][] classes) {
            this.op = op;
            this.x = x;
            this.y = y;
            this.n = n;
            this.classes = classes;
        }
    }

    // ------------------------------------------------------------------ parsing

    private static final class Parser {
        final byte[] p;
        final int len;
        int pos;
        int depth;
        // Nodes: kind, a, b, and for a repetition its counts.
        final int[] kind;
        final int[] a;
        final int[] b;
        final int[] min;
        final int[] max;
        /**
         * Whether a node can only ever compile to instructions that consume nothing and lead to the next one: the
         * empty pattern, a sequence of such nodes, a repetition of one, x{0}. The emitter leaves such a node out, which
         * matches the same, and so every node it does write out adds an instruction: compiling takes time bounded
         * by {@link #MAX_PROGRAM}, where ((((){100}){100}){100}){100} would otherwise loop 100^4 times writing
         * out nothing.
         */
        final boolean[] blank;
        int nodes;
        final long[][] classes = new long[MAX_CLASSES][];
        int nclasses;

        Parser(byte[] p, int len) {
            this.p = p;
            this.len = len;
            int cap = 2 * len + 2;
            kind = new int[cap];
            a = new int[cap];
            b = new int[cap];
            min = new int[cap];
            max = new int[cap];
            blank = new boolean[cap];
        }

        int node(int k, int na, int nb) {
            kind[nodes] = k;
            a[nodes] = na;
            b[nodes] = nb;
            blank[nodes] = k == N_EMPTY || k == N_CAT && blank[na] && blank[nb] || k == N_REPEAT && blank[na];
            return nodes++;
        }

        int newClass(int at) {
            if (nclasses == MAX_CLASSES) throw new BadPattern("more than " + MAX_CLASSES + " classes", at);
            classes[nclasses] = new long[4];
            return nclasses++;
        }

        int alt() {
            int left = concat();
            while (pos < len && p[pos] == '|') {
                pos++;
                int right = concat();
                left = node(N_ALT, left, right);
            }
            return left;
        }

        int concat() {
            int left = -1;
            while (pos < len && p[pos] != '|' && p[pos] != ')') {
                int r = repeat();
                left = left < 0 ? r : node(N_CAT, left, r);
            }
            return left < 0 ? node(N_EMPTY, 0, 0) : left;
        }

        int repeat() {
            int start = pos;
            int atom = atom();
            if (pos >= len) return atom;
            int c = p[pos] & 0xFF;
            int lo;
            int hi;
            if (c == '*') {
                lo = 0;
                hi = -1;
                pos++;
            } else if (c == '+') {
                lo = 1;
                hi = -1;
                pos++;
            } else if (c == '?') {
                lo = 0;
                hi = 1;
                pos++;
            } else if (c == '{') {
                int at = pos;
                pos++;
                lo = number(at);
                hi = lo;
                if (pos < len && p[pos] == ',') {
                    pos++;
                    hi = pos < len && p[pos] == '}' ? -1 : number(at);
                }
                if (pos >= len || p[pos] != '}' || hi >= 0 && hi < lo) throw new BadPattern("bad repetition count", at);
                pos++;
            } else {
                return atom;
            }
            int k = kind[atom];
            if (k == N_BOL || k == N_EOL || k == N_EMPTY && start == pos) throw new BadPattern("nothing to repeat", start);
            if (pos < len && (p[pos] == '*' || p[pos] == '+' || p[pos] == '?' || p[pos] == '{')) {
                throw new BadPattern("nothing to repeat", pos);
            }
            int r = node(N_REPEAT, atom, 0);
            min[r] = lo;
            max[r] = hi;
            if (hi == 0) blank[r] = true;  // x{0} is written out as nothing
            return r;
        }

        int number(int at) {
            int n = 0;
            int digits = 0;
            while (pos < len && p[pos] >= '0' && p[pos] <= '9') {
                n = n * 10 + (p[pos] - '0');
                if (n > MAX_REPEAT) throw new BadPattern("bad repetition count", at);
                pos++;
                digits++;
            }
            if (digits == 0) throw new BadPattern("bad repetition count", at);
            return n;
        }

        int atom() {
            int at = pos;
            int c = p[pos++] & 0xFF;
            switch (c) {
                case '(' -> {
                    if (++depth > MAX_DEPTH) throw new BadPattern("groups nested more than " + MAX_DEPTH + " deep", at);
                    int inner = alt();
                    if (pos >= len || p[pos] != ')') throw new BadPattern("missing )", at);
                    pos++;
                    depth--;
                    return inner;
                }
                case ')' -> throw new BadPattern("unmatched )", at);
                case '*', '+', '?', '{' -> throw new BadPattern("nothing to repeat", at);
                case '.' -> {
                    return node(N_ANY, 0, 0);
                }
                case '^' -> {
                    return node(N_BOL, 0, 0);
                }
                case '$' -> {
                    return node(N_EOL, 0, 0);
                }
                case '[' -> {
                    return node(N_CLASS, bracket(at), 0);
                }
                case '\\' -> {
                    if (pos >= len) throw new BadPattern("escape at the end of the pattern", at);
                    int e = p[pos++] & 0xFF;
                    int cls = shorthand(e, at);
                    if (cls >= 0) return node(N_CLASS, cls, 0);
                    return node(N_LIT, escaped(e, at), 0);
                }
                default -> {
                    return node(N_LIT, c, 0);
                }
            }
        }

        /** The class of `\d \w \s \D \W \S`, made now; -1 for any other escape. */
        int shorthand(int e, int at) {
            int lower = e | 0x20;
            if (lower != 'd' && lower != 'w' && lower != 's') return -1;
            int k = newClass(at);
            addShorthand(classes[k], lower);
            if (e != lower) invert(classes[k]);
            return k;
        }

        /** The byte an escape other than a class stands for. */
        static int escaped(int e, int at) {
            return switch (e) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case '0' -> 0;
                default -> {
                    if (e >= '0' && e <= '9' || e >= 'a' && e <= 'z' || e >= 'A' && e <= 'Z') {
                        throw new BadPattern("unknown escape \\" + (char) e, at);
                    }
                    yield e;
                }
            };
        }

        /** `[...]`, the `[` at {@code at} read already: the index of its class. */
        int bracket(int at) {
            int k = newClass(at);
            long[] set = classes[k];
            boolean negate = pos < len && p[pos] == '^';
            if (negate) pos++;
            boolean first = true;
            while (true) {
                if (pos >= len) throw new BadPattern("missing ]", at);
                int c = p[pos] & 0xFF;
                if (c == ']' && !first) {
                    pos++;
                    break;
                }
                first = false;
                int itemAt = pos;
                pos++;
                int lo;
                if (c == '\\') {
                    if (pos >= len) throw new BadPattern("escape at the end of the pattern", itemAt);
                    int e = p[pos++] & 0xFF;
                    int lower = e | 0x20;
                    if (lower == 'd' || lower == 'w' || lower == 's') {
                        long[] sh = new long[4];
                        addShorthand(sh, lower);
                        if (e != lower) invert(sh);
                        for (int i = 0; i < 4; i++) set[i] |= sh[i];
                        continue;
                    }
                    lo = escaped(e, itemAt);
                } else {
                    lo = c;
                }
                int hi = lo;
                if (pos + 1 < len && p[pos] == '-' && p[pos + 1] != ']') {
                    pos++;
                    int d = p[pos++] & 0xFF;
                    if (d == '\\') {
                        if (pos >= len) throw new BadPattern("escape at the end of the pattern", pos - 1);
                        int e = p[pos++] & 0xFF;
                        if (((e | 0x20) == 'd' || (e | 0x20) == 'w' || (e | 0x20) == 's')) throw new BadPattern("bad range in a class", itemAt);
                        d = escaped(e, pos - 2);
                    }
                    hi = d;
                    if (hi < lo) throw new BadPattern("bad range in a class", itemAt);
                }
                for (int v = lo; v <= hi; v++) set[v >> 6] |= 1L << (v & 63);
            }
            if (negate) invert(set);
            return k;
        }

        static void addShorthand(long[] set, int kind) {
            for (int v = 0; v < 256; v++) {
                boolean in = switch (kind) {
                    case 'd' -> v >= '0' && v <= '9';
                    case 'w' -> v >= '0' && v <= '9' || v >= 'a' && v <= 'z' || v >= 'A' && v <= 'Z' || v == '_';
                    default -> v == ' ' || v == '\t' || v == '\n' || v == '\r' || v == 0x0B || v == 0x0C;
                };
                if (in) set[v >> 6] |= 1L << (v & 63);
            }
        }

        static void invert(long[] set) {
            for (int i = 0; i < 4; i++) set[i] = ~set[i];
        }
    }

    // ------------------------------------------------------------------ compiling

    private static final class Emitter {
        final Parser t;
        final int[] op = new int[MAX_PROGRAM];
        final int[] x = new int[MAX_PROGRAM];
        final int[] y = new int[MAX_PROGRAM];
        int n;

        Emitter(Parser t) {
            this.t = t;
        }

        int emit(int o, int a, int b) {
            if (n == MAX_PROGRAM) {
                throw new BadPattern("more than " + MAX_PROGRAM + " instructions once its repetitions are written out", 0);
            }
            op[n] = o;
            x[n] = a;
            y[n] = b;
            return n++;
        }

        /** Emits node k; an explicit stack would do the same, but a pattern nests at most a few hundred nodes. */
        void node(int k) {
            if (t.blank[k]) return;
            switch (t.kind[k]) {
                case N_LIT -> emit(CHAR, t.a[k], 0);
                case N_ANY -> emit(ANY, 0, 0);
                case N_CLASS -> emit(CLASS, t.a[k], 0);
                case N_BOL -> emit(BOL, 0, 0);
                case N_EOL -> emit(EOL, 0, 0);
                case N_CAT -> {
                    node(t.a[k]);
                    node(t.b[k]);
                }
                case N_ALT -> {
                    int split = emit(SPLIT, 0, 0);
                    x[split] = n;
                    node(t.a[k]);
                    int jmp = emit(JMP, 0, 0);
                    y[split] = n;
                    node(t.b[k]);
                    x[jmp] = n;
                }
                default -> {
                    int sub = t.a[k];
                    for (int i = 0; i < t.min[k]; i++) node(sub);
                    if (t.max[k] < 0) {
                        // x*: L1: split L2, L3; L2: x; jmp L1; L3:
                        int split = emit(SPLIT, 0, 0);
                        x[split] = n;
                        node(sub);
                        emit(JMP, split, 0);
                        y[split] = n;
                    } else {
                        for (int i = t.min[k]; i < t.max[k]; i++) {
                            // x?: split L1, L2; L1: x; L2:
                            int split = emit(SPLIT, 0, 0);
                            x[split] = n;
                            node(sub);
                            y[split] = n;
                        }
                    }
                }
            }
        }
    }

    /** Compiles the first {@code len} bytes of a pattern, or throws why it is not valid. */
    public static Program compile(byte[] p, int len) {
        if (len > MAX_PATTERN) throw new BadPattern("longer than " + MAX_PATTERN + " bytes", 0);
        Parser parser = new Parser(p, len);
        int root = parser.alt();
        if (parser.pos < len) throw new BadPattern("unmatched )", parser.pos);
        Emitter e = new Emitter(parser);
        e.node(root);
        e.emit(MATCH, 0, 0);
        return new Program(e.op, e.x, e.y, e.n, parser.classes);
    }

    // ------------------------------------------------------------------ matching

    /**
     * Runs a program on the first {@code len} bytes of {@code s}. With {@code whole}, whether it matches all of them;
     * otherwise where its leftmost match starts and ends, as start * 2^32 + end, or -1 for none. Each thread it
     * steps costs an element of the meter.
     */
    public static long run(Program prog, byte[] s, int len, boolean whole, Meter meter) {
        return run(prog, s, len, whole, 0, -1, meter);
    }

    /**
     * As {@link #run}, looking for a match that starts at {@code begin} or after it (^ still matches only at 0), and
     * passing over an empty match at {@code noEmptyAt}, as re_replace and re_split do after an empty match there: the
     * thread that made it is dropped, so the match of the next priority, or one further on, is found instead.
     */
    public static long run(Program prog, byte[] s, int len, boolean whole, int begin, int noEmptyAt, Meter meter) {
        int n = prog.n;
        int[] cpc = new int[n];
        int[] cstart = new int[n];
        int[] npc = new int[n];
        int[] nstart = new int[n];
        int[] mark = new int[n];
        int[] stack = new int[2 * n + 1];
        long best = -1;
        boolean matched = false;
        int cn = 0;
        for (int pos = begin; pos <= len; pos++) {
            if (!matched && (!whole || pos == 0)) cn = add(prog, cpc, cstart, cn, 0, pos, pos, len, mark, stack, meter);
            if (cn == 0) {
                if (matched || whole) break;
                continue;
            }
            int nn = 0;
            for (int i = 0; i < cn; i++) {
                int pc = cpc[i];
                meter.elements(1);
                switch (prog.op[pc]) {
                    case MATCH -> {
                        if (whole && pos != len) continue;
                        if (pos == noEmptyAt && cstart[i] == pos) continue;
                        best = ((long) cstart[i] << 32) | pos;
                        matched = true;
                        // The threads after this one have lower priority: drop them.
                        i = cn;
                    }
                    case CHAR -> {
                        if (pos < len && (s[pos] & 0xFF) == prog.x[pc]) {
                            nn = add(prog, npc, nstart, nn, pc + 1, cstart[i], pos + 1, len, mark, stack, meter);
                        }
                    }
                    case ANY -> {
                        if (pos < len && s[pos] != '\n') nn = add(prog, npc, nstart, nn, pc + 1, cstart[i], pos + 1, len, mark, stack, meter);
                    }
                    default -> {
                        int c = pos < len ? s[pos] & 0xFF : -1;
                        if (c >= 0 && (prog.classes[prog.x[pc]][c >> 6] >>> (c & 63) & 1) != 0) {
                            nn = add(prog, npc, nstart, nn, pc + 1, cstart[i], pos + 1, len, mark, stack, meter);
                        }
                    }
                }
            }
            int[] t = cpc;
            cpc = npc;
            npc = t;
            t = cstart;
            cstart = nstart;
            nstart = t;
            cn = nn;
            if (matched && cn == 0) break;
        }
        if (whole) return matched ? 1 : -1;
        return best;
    }

    /**
     * Adds the thread at {@code pc} that started at {@code start} to the list of position {@code pos}, following jumps,
     * splits (the first branch first) and anchors, each instruction at most once per position, and leaving a loop whose
     * iteration matched the empty text; returns the list's new length.
     */
    private static int add(Program prog, int[] lpc, int[] lstart, int ln, int pc0, int start, int pos, int len, int[] mark,
                           int[] stack, Meter meter) {
        int gen = pos + 1;
        int sp = 0;
        stack[sp++] = pc0;
        while (sp > 0) {
            int pc = stack[--sp];
            if (mark[pc] == gen) continue;
            mark[pc] = gen;
            meter.elements(1);
            switch (prog.op[pc]) {
                case JMP -> {
                    // A jump back is the end of an iteration of x*. If its loop was already entered at this
                    // position, this iteration matched the empty text: as in Perl, the loop ends here, so the
                    // thread goes on after it, at the priority it has now, rather than dying (when the loop was
                    // entered by an earlier thread, what follows it is already on the list, and this adds nothing).
                    int to = prog.x[pc];
                    stack[sp++] = to < pc && mark[to] == gen ? prog.y[to] : to;
                }
                case SPLIT -> {
                    // The second branch waits under the first, which is followed first.
                    stack[sp++] = prog.y[pc];
                    stack[sp++] = prog.x[pc];
                }
                case BOL -> {
                    if (pos == 0) stack[sp++] = pc + 1;
                }
                case EOL -> {
                    if (pos == len) stack[sp++] = pc + 1;
                }
                default -> {
                    lpc[ln] = pc;
                    lstart[ln] = start;
                    ln++;
                }
            }
        }
        return ln;
    }
}
