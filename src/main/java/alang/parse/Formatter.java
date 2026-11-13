package alang.parse;

import alang.diag.Diagnostics;
import alang.diag.Source;
import alang.parser.ALangLexer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

/**
 * `alang fmt`: lays a program out the one way. It works on the tokens, comments included, and keeps the lines
 * the author broke the code into, as gofmt does: it sets each line's indentation (four spaces a block, four more
 * for a line that continues a statement or a bracket opened above), the spaces between tokens, and at most one
 * blank line in a row, none at the start or end of a block. The result always lexes to the same tokens as the
 * source; a program with syntax errors is not formatted.
 */
public final class Formatter {
    private Formatter() {}

    private static final String INDENT = "    ";

    /** The formatted text of {@code file}, or null if it has syntax errors, which go to {@code diags}. */
    public static String format(Source file, Diagnostics diags) {
        if (Parser.parse(file, diags, false) == null) return null;
        List<Token> tokens = lex(file.text);
        String out = layout(tokens);
        if (!sameTokens(tokens, lex(out))) {
            throw new IllegalStateException("formatting would change the tokens of " + file.path);
        }
        return out;
    }

    private static List<Token> lex(String text) {
        ALangLexer lexer = new ALangLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        List<Token> out = new ArrayList<>();
        for (Token t = lexer.nextToken(); t.getType() != Token.EOF; t = lexer.nextToken()) out.add(t);
        return out;
    }

    private static boolean sameTokens(List<Token> a, List<Token> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).getType() != b.get(i).getType() || !a.get(i).getText().equals(b.get(i).getText())) return false;
        }
        return true;
    }

    private static boolean isComment(Token t) {
        return t.getType() == ALangLexer.LINE_COMMENT || t.getType() == ALangLexer.DOC_COMMENT;
    }

    /** Words that are names in some places and keywords in others. */
    private static boolean contextual(int t) {
        return t == ALangLexer.LIMIT || t == ALangLexer.TEST || t == ALangLexer.TOOL || t == ALangLexer.IMPORT
            || t == ALangLexer.STEP || t == ALangLexer.REV || t == ALangLexer.THEN || t == ALangLexer.MAP
            || t == ALangLexer.SET || t == ALangLexer.AS || t == ALangLexer.TRAPS;
    }

    private static boolean nameLike(int t) {
        return t == ALangLexer.IDENT || contextual(t);
    }

    private static boolean primitiveType(int t) {
        return t >= ALangLexer.BOOL && t <= ALangLexer.F64;
    }

    /** Whether a statement may end with {@code t}, so that the next line starts a new one. */
    private static boolean endsStatement(Token t) {
        if (t == null) return true;
        int k = t.getType();
        return k == ALangLexer.SEMI || k == ALangLexer.LBRACE || k == ALangLexer.RBRACE || k == ALangLexer.COMMA;
    }

    /**
     * Whether the `-` or `~` at {@code i} is a sign, not a subtraction: it follows an operator, an opening bracket, a
     * comma or a keyword. After a word that may be a name or a keyword, the source decides: -x is written together.
     */
    private static boolean unary(List<Token> tokens, int i, Token prev) {
        if (prev == null) return true;
        int k = prev.getType();
        if (contextual(k)) return i + 1 < tokens.size() && !gap(tokens.get(i), tokens.get(i + 1));
        if (k == ALangLexer.IDENT) return false;
        return switch (k) {
            case ALangLexer.INT_LIT, ALangLexer.FLOAT_LIT, ALangLexer.STRING_LIT, ALangLexer.CHAR_LIT, ALangLexer.TRUE,
                 ALangLexer.FALSE, ALangLexer.RPAREN, ALangLexer.RBRACK -> false;
            default -> true;
        };
    }

    /** Whether the source had whitespace between {@code a} and {@code b}. */
    private static boolean gap(Token a, Token b) {
        return b.getStartIndex() > a.getStopIndex() + 1;
    }

    /**
     * Whether a space goes between {@code a} and {@code b} on one line; {@code touching} when one of them is a sign
     * or a bracket of type parameters, which touch their neighbours.
     */
    private static boolean space(Token a, Token b, boolean touching) {
        int x = a.getType();
        int y = b.getType();
        if (touching) return false;
        if (x == ALangLexer.LPAREN || x == ALangLexer.LBRACK || x == ALangLexer.DOT) return false;
        if (y == ALangLexer.RPAREN || y == ALangLexer.RBRACK || y == ALangLexer.COMMA || y == ALangLexer.SEMI
            || y == ALangLexer.DOT || y == ALangLexer.COLON) {
            return false;
        }
        if (x == ALangLexer.DOTDOT || x == ALangLexer.DOTDOTEQ || y == ALangLexer.DOTDOT || y == ALangLexer.DOTDOTEQ) return false;
        if (y == ALangLexer.LPAREN || y == ALangLexer.LBRACK) {
            // A call, conversion, index or type's brackets touch what they follow; after a keyword or operator, a
            // parenthesis or array literal is spaced. A word that is a name or a keyword keeps the source's choice.
            if (contextual(x)) return gap(a, b);
            if (x == ALangLexer.IDENT || primitiveType(x) || x == ALangLexer.RBRACK || x == ALangLexer.RPAREN) return false;
            if (y == ALangLexer.LBRACK && x == ALangLexer.STRING_LIT) return false;
            if (y == ALangLexer.LPAREN && x == ALangLexer.FN) return false;
            if (y == ALangLexer.LBRACK && (x == ALangLexer.STR || x == ALangLexer.LIST)) return false;
            return true;
        }
        return true;
    }

    /** The positions of the `<` and `>` around the type parameters of `fn name<T, U>`. */
    private static java.util.Set<Integer> typeParamBrackets(List<Token> tokens) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (int i = 2; i < tokens.size(); i++) {
            if (tokens.get(i).getType() != ALangLexer.LT || !nameLike(tokens.get(i - 1).getType())
                || tokens.get(i - 2).getType() != ALangLexer.FN) {
                continue;
            }
            for (int j = i + 1; j < tokens.size(); j++) {
                if (tokens.get(j).getType() == ALangLexer.GT) {
                    out.add(i);
                    out.add(j);
                    break;
                }
            }
        }
        return out;
    }

    private static String layout(List<Token> tokens) {
        java.util.Set<Integer> params = typeParamBrackets(tokens);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int depth = 0;
        // The lines of the ( and [ still open: brackets opened on one line indent what follows once.
        Deque<Integer> brackets = new ArrayDeque<>();
        Token prev = null;
        Token prevCode = null;
        int prevIndex = -1;
        boolean sign = false;
        boolean openedBlock = false;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            int k = t.getType();
            boolean newLine = prev == null || t.getLine() > prev.getLine();
            if (newLine) {
                if (prev != null) {
                    lines.add(line.toString());
                    line.setLength(0);
                    // At most one blank line, and none right after a `{`; one between top-level declarations.
                    boolean nextItem = depth == 0 && prev == prevCode && prev.getType() == ALangLexer.RBRACE;
                    if ((t.getLine() - prev.getLine() > 1 || nextItem) && !openedBlock && !lines.get(lines.size() - 1).isEmpty()) {
                        lines.add("");
                    }
                }
                int d = depth;
                if (k == ALangLexer.RBRACE) {
                    d = depth - 1;
                } else if (k == ALangLexer.RPAREN || k == ALangLexer.RBRACK) {
                    // Back to the indentation of the line that opened the bracket.
                    Integer closes = brackets.peek();
                    boolean outer = brackets.stream().skip(1).anyMatch(l -> !l.equals(closes));
                    d = depth + (outer ? 1 : 0);
                } else if (k != ALangLexer.LBRACE && (!brackets.isEmpty() || !endsStatement(prevCode))) {
                    d = depth + 1;
                }
                line.append(INDENT.repeat(Math.max(0, d)));
            } else if (isComment(t)) {
                // A comment after code keeps its distance from it, so that aligned comments stay aligned.
                int end = prev.getCharPositionInLine() + prev.getText().length();
                line.append(" ".repeat(Math.max(1, t.getCharPositionInLine() - end)));
            } else if (space(prev, t, sign || params.contains(i) || params.contains(prevIndex) && tokens.get(prevIndex).getType() == ALangLexer.LT
                || params.contains(prevIndex) && k == ALangLexer.LPAREN)) {
                line.append(' ');
            }
            line.append(t.getText());
            if (isComment(t)) {
                prev = t;
                continue;
            }
            openedBlock = k == ALangLexer.LBRACE;
            sign = (k == ALangLexer.MINUS || k == ALangLexer.TILDE) && unary(tokens, i, prevCode);
            switch (k) {
                case ALangLexer.LBRACE -> depth++;
                case ALangLexer.RBRACE -> depth--;
                case ALangLexer.LPAREN, ALangLexer.LBRACK -> brackets.push(t.getLine());
                case ALangLexer.RPAREN, ALangLexer.RBRACK -> {
                    if (!brackets.isEmpty()) brackets.pop();
                }
                default -> { }
            }
            prev = t;
            prevCode = t;
            prevIndex = i;
        }
        if (prev != null) lines.add(line.toString());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            // No blank line before a closing brace.
            if (l.isEmpty() && i + 1 < lines.size() && lines.get(i + 1).strip().startsWith("}")) continue;
            out.append(l).append('\n');
        }
        return out.toString();
    }
}
