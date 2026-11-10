package alang.parse;

import alang.diag.Code;
import alang.diag.Diagnostics;
import alang.parser.ALangLexer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.Token;

/**
 * The nesting limit (E0404). Parsing, checking, running and compiling a program all recurse over
 * it, so how deeply it nests decides how much stack they need. So that the stack size of the
 * machine never decides whether a program works, every program must nest at most {@link #MAX_DEPTH}
 * levels deep, measured on the tokens before anything recurses:
 *
 * <ul>
 *   <li>Inside a declaration, each open bracket counts 1, and so does each operator of the
 *       expression being read at each open bracket ({@code a + b + c} nests like
 *       {@code (a + b) + c}). A field access {@code .f} and an index {@code [i]} count as operators:
 *       {@code x[0][0][0]} nests like {@code x.a.b.c}, though no two of its brackets are open at
 *       once. A {@code ;}, a {@code ,} or a block ends an expression.
 *   <li>A declaration weighs its deepest point (at least 1), plus the weight of the heaviest chain
 *       of declarations it names: the functions it calls and the constants and types it uses.
 *       Names are matched as plain identifiers, which can only overestimate.
 * </ul>
 *
 * At {@value #MAX_DEPTH}, the deepest construct (parentheses) needs a fifth of the stack that
 * overflows with Java's default thread stack; CliTest runs programs at the limit.
 */
final class Nesting {
    static final int MAX_DEPTH = 128;

    private Nesting() {}

    /** A top-level declaration, as seen in the tokens. */
    private static final class Item {
        final Token start;
        final Token name;      // null for a test
        final boolean endsWithSemicolon;
        final Set<String> names = new HashSet<>();
        int weight = 1;

        Item(Token start, Token name, boolean endsWithSemicolon) {
            this.start = start;
            this.name = name;
            this.endsWithSemicolon = endsWithSemicolon;
        }

        String label() {
            return name != null ? "`" + name.getText() + "`" : "this test";
        }
    }

    private final List<Item> items = new ArrayList<>();

    /**
     * Reports E0404 and returns false if a single place nests too deeply. Run before parsing: the
     * parser itself recurses on brackets and prefix operators.
     */
    static Nesting scan(List<Token> tokens, Diagnostics diags) {
        Nesting n = new Nesting();
        return n.scanTokens(tokens, diags) ? n : null;
    }

    private boolean scanTokens(List<Token> tokens, Diagnostics diags) {
        int[] ops = new int[16];  // operators of the current expression, per open bracket (0 = top level)
        int open = 0;
        int depth = 0;            // open brackets + all their operators
        Item item = null;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            int type = t.getType();
            if (open == 0 && item == null && startsItem(type)) {  // `test` and `tool` can also be names
                item = startItem(tokens, i);
            }
            switch (type) {
                case ALangLexer.LBRACE, ALangLexer.LPAREN, ALangLexer.LBRACK -> {
                    if (type == ALangLexer.LBRACE) {  // a block starts after its condition
                        depth -= ops[open];
                        ops[open] = 0;
                    }
                    if (type == ALangLexer.LBRACK && i > 0 && endsValue(tokens.get(i - 1).getType())) {
                        // An index chains like `.`: x[0][0][0] nests as deeply as x.a.b.c, although each
                        // of its brackets closes before the next one opens.
                        ops[open]++;
                        depth++;
                    }
                    if (++open == ops.length) ops = Arrays.copyOf(ops, ops.length * 2);
                    ops[open] = 0;
                    depth++;
                }
                case ALangLexer.RBRACE, ALangLexer.RPAREN, ALangLexer.RBRACK -> {
                    if (open > 0) {
                        depth -= 1 + ops[open];
                        open--;
                        if (type == ALangLexer.RBRACE) {  // a block ends its statement
                            depth -= ops[open];
                            ops[open] = 0;
                            if (open == 0 && item != null && !item.endsWithSemicolon) item = null;
                        }
                    }
                }
                case ALangLexer.SEMI, ALangLexer.COMMA -> {
                    depth -= ops[open];
                    ops[open] = 0;
                    if (type == ALangLexer.SEMI && open == 0 && item != null && item.endsWithSemicolon) item = null;
                }
                default -> {
                    if (isOperator(type)) {
                        ops[open]++;
                        depth++;
                    } else if (item != null && isIdentifier(type) && t != item.name) {
                        item.names.add(t.getText());
                    }
                }
            }
            if (depth > MAX_DEPTH) {
                diags.error(Code.E0404, AstBuilder.span(t),
                    "this nests more than " + MAX_DEPTH + " levels deep (each bracket and each chained operator counts)",
                    "split the expression with intermediate `let` bindings, or move inner blocks into helper functions");
                return false;
            }
            if (item != null) item.weight = Math.max(item.weight, depth);
        }
        return true;
    }

    private static boolean startsItem(int type) {
        return type == ALangLexer.FN || type == ALangLexer.TOOL || type == ALangLexer.CONST
            || type == ALangLexer.STRUCT || type == ALangLexer.ENUM || type == ALangLexer.TEST;
    }

    private Item startItem(List<Token> tokens, int i) {
        int type = tokens.get(i).getType();
        int nameAt = type == ALangLexer.TOOL ? i + 2 : i + 1;  // tool fn NAME
        Token name = type != ALangLexer.TEST && nameAt < tokens.size() ? tokens.get(nameAt) : null;
        Item item = new Item(tokens.get(i), name, type == ALangLexer.CONST);
        items.add(item);
        return item;
    }

    private static boolean isOperator(int type) {
        return switch (type) {
            case ALangLexer.PLUS, ALangLexer.MINUS, ALangLexer.STAR, ALangLexer.SLASH, ALangLexer.PERCENT,
                 ALangLexer.AMP, ALangLexer.PIPE, ALangLexer.CARET, ALangLexer.SHL, ALangLexer.SHR,
                 ALangLexer.TILDE, ALangLexer.NOT, ALangLexer.AND, ALangLexer.OR, ALangLexer.EQ,
                 ALangLexer.NEQ, ALangLexer.LT, ALangLexer.LE, ALangLexer.GT, ALangLexer.GE,
                 ALangLexer.DOT -> true;
            default -> false;
        };
    }

    /** Can a token of this type end a value, so that a `[` after it indexes the value? */
    private static boolean endsValue(int type) {
        return isIdentifier(type) || switch (type) {
            case ALangLexer.RPAREN, ALangLexer.RBRACK, ALangLexer.INT_LIT, ALangLexer.FLOAT_LIT, ALangLexer.STRING_LIT,
                 ALangLexer.CHAR_LIT, ALangLexer.TRUE, ALangLexer.FALSE -> true;
            default -> false;
        };
    }

    private static boolean isIdentifier(int type) {
        return type == ALangLexer.IDENT || type == ALangLexer.LIMIT || type == ALangLexer.TEST
            || type == ALangLexer.TOOL;
    }

    /**
     * Reports E0404 and returns false if a chain of declarations that name each other is too deep.
     * Run after parsing succeeded, before checking. Iterative, so it cannot overflow itself.
     */
    boolean checkChains(Diagnostics diags) {
        Map<String, List<Integer>> byName = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.name != null) byName.computeIfAbsent(it.name.getText(), k -> new ArrayList<>()).add(i);
        }
        int[][] edges = new int[items.size()][];
        for (int i = 0; i < items.size(); i++) {
            List<Integer> to = new ArrayList<>();
            for (String name : items.get(i).names) to.addAll(byName.getOrDefault(name, List.of()));
            to.sort(null);
            edges[i] = to.stream().mapToInt(Integer::intValue).toArray();
        }
        long[] total = chainWeights(edges);
        for (int i = 0; i < items.size(); i++) {
            if (total[i] > MAX_DEPTH) {
                Item it = items.get(i);
                diags.error(Code.E0404, AstBuilder.span(it.name != null ? it.name : it.start),
                    it.label() + " nests " + total[i] + " levels deep through the functions, constants and types it "
                        + "uses; the limit is " + MAX_DEPTH,
                    "shorten the chain of declarations that use each other, or split deep expressions with `let` bindings");
                return false;
            }
        }
        return true;
    }

    /**
     * For every item: its weight plus the heaviest chain it reaches. Items that reach each other
     * (a cycle, reported elsewhere as recursion) count with the sum of their weights. Tarjan's
     * algorithm, iterative; it emits each strongly connected component after all it reaches.
     */
    private long[] chainWeights(int[][] edges) {
        int n = items.size();
        int[] index = new int[n];
        int[] low = new int[n];
        int[] component = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(index, -1);
        ArrayDeque<Integer> stack = new ArrayDeque<>();
        List<Long> componentTotal = new ArrayList<>();
        int counter = 0;
        int[] next = new int[n];  // next edge to visit, per node on the DFS path
        ArrayDeque<Integer> path = new ArrayDeque<>();
        for (int root = 0; root < n; root++) {
            if (index[root] >= 0) continue;
            path.push(root);
            while (!path.isEmpty()) {
                int v = path.peek();
                if (index[v] < 0) {
                    index[v] = low[v] = counter++;
                    stack.push(v);
                    onStack[v] = true;
                    next[v] = 0;
                }
                if (next[v] < edges[v].length) {
                    int w = edges[v][next[v]++];
                    if (index[w] < 0) {
                        path.push(w);
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                    continue;
                }
                path.pop();
                if (!path.isEmpty()) low[path.peek()] = Math.min(low[path.peek()], low[v]);
                if (low[v] == index[v]) {
                    int c = componentTotal.size();
                    List<Integer> members = new ArrayList<>();
                    int w;
                    do {
                        w = stack.pop();
                        onStack[w] = false;
                        component[w] = c;
                        members.add(w);
                    } while (w != v);
                    long weight = 0;
                    long below = 0;
                    for (int m : members) {
                        weight += items.get(m).weight;
                        for (int x : edges[m]) {
                            // Everything a component reaches was emitted before it.
                            if (component[x] != c) below = Math.max(below, componentTotal.get(component[x]));
                        }
                    }
                    componentTotal.add(weight + below);
                }
            }
        }
        long[] total = new long[n];
        for (int i = 0; i < n; i++) total[i] = componentTotal.get(component[i]);
        return total;
    }
}
