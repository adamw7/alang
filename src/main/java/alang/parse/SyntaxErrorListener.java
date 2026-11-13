package alang.parse;

import alang.diag.Code;
import alang.diag.Diagnostics;
import alang.diag.Edit;
import alang.diag.Hints;
import alang.diag.Source;
import alang.diag.Span;
import alang.parser.ALangLexer;
import alang.parser.ALangParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.InputMismatchException;
import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.IntervalSet;

/**
 * Turns ANTLR syntax errors into a single, actionable diagnostic. Only the first syntax
 * error is reported: later ones are usually consequences of the first.
 */
final class SyntaxErrorListener extends BaseErrorListener {
    private final Diagnostics diags;
    private final Source file;
    private boolean reported;

    SyntaxErrorListener(Diagnostics diags, Source file) {
        this.diags = diags;
        this.file = file;
    }

    boolean hasError() {
        return reported;
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine,
                            String msg, RecognitionException e) {
        if (reported) return;
        reported = true;
        if (!(offendingSymbol instanceof Token tok) || !(recognizer instanceof Parser parser)) {
            diags.error(Code.E0001, new Span(line, charPositionInLine + 1, 1, file), msg);
            return;
        }
        IntervalSet expected = e != null ? e.getExpectedTokens() : parser.getExpectedTokens();
        TokenStream ts = parser.getInputStream();
        Token prev = previous(ts, tok);
        Token prev2 = prev == null ? null : previous(ts, prev);

        String text = tok.getType() == Token.EOF ? "end of file"
            : tok.getType() == ALangLexer.ERROR_CHAR ? "character " + strayCharacter(tok.getText())
            : "'" + tok.getText() + "'";
        int len = tok.getType() == Token.EOF ? 1 : Math.max(1, Source.columns(tok.getText()));

        // A missing semicolon is reported at the end of the previous line, where it belongs.
        if (expected != null && expected.contains(ALangLexer.SEMI) && prev != null
            && (tok.getLine() > prev.getLine() || tok.getType() == Token.EOF) && tok.getType() != ALangLexer.SEMI) {
            Span at = new Span(prev.getLine(), prev.getCharPositionInLine() + 1, Math.max(1, Source.columns(prev.getText())), file);
            diags.fixable(Code.E0001, at, "missing ';' after '" + prev.getText() + "'",
                "every statement and const declaration ends with `;`", List.of(new Edit(after(prev), ";")));
            return;
        }

        String message;
        if (e instanceof NoViableAltException || e instanceof InputMismatchException || e == null) {
            message = "unexpected " + text;
            String exp = describe(expected);
            if (exp != null) message += ", expected " + exp;
        } else {
            message = msg;
        }
        String hint = hint(ts, tok, prev, prev2, expected);
        diags.fixable(Code.E0001, new Span(tok.getLine(), tok.getCharPositionInLine() + 1, len, file), message, hint,
            fixes(ts, tok, prev, expected));
    }

    // ------------------------------------------------------------------ fixes

    /**
     * The edits that fix the error at {@code tok} where the fix is mechanical, as its hint says: a habit from
     * another language with exactly one ALang spelling. None otherwise.
     */
    private List<Edit> fixes(TokenStream ts, Token tok, Token prev, IntervalSet expected) {
        int t = tok.getType();
        int p = prev == null ? -1 : prev.getType();
        Token next = next(ts, tok);
        // `} elif x {`: the error can come after `elif`, which parses as a name, at the token that follows it.
        if (elseIf(ts, prev)) return List.of(new Edit(span(prev), "else if"));
        switch (t) {
            case ALangLexer.AMPAMP -> {
                return List.of(new Edit(span(tok), spaced(prev, tok, next, "and")));
            }
            case ALangLexer.PIPEPIPE -> {
                return List.of(new Edit(span(tok), spaced(prev, tok, next, "or")));
            }
            case ALangLexer.BANG -> {
                // `!x` is `not x`, and `!a == b` is `not a == b`: for bools, (not a) == b and not (a == b) agree.
                if (next == null) return List.of();
                String before = prev != null && touches(prev, tok) && wordLike(prev.getType()) ? " " : "";
                return List.of(new Edit(span(tok), before + "not" + (touches(tok, next) ? " " : "")));
            }
            case ALangLexer.COLONCOLON -> {
                return List.of(new Edit(span(tok), "."));
            }
            case ALangLexer.PLUSPLUS -> {
                // `x++;`: the statement form only, where it is `x += 1;`.
                if (prev != null && endsPlace(p) && next != null && next.getType() == ALangLexer.SEMI) {
                    return List.of(new Edit(span(tok), " += 1"));
                }
                return List.of();
            }
            case ALangLexer.IDENT -> {
                return elseIf(ts, tok) ? List.of(new Edit(span(tok), "else if")) : List.of();
            }
            default -> { }
        }
        // 'abc', text in single quotes, lexes as a stray quote: it is the str "abc".
        if (t == ALangLexer.ERROR_CHAR && tok.getText().equals("'")) {
            for (int i = tok.getTokenIndex() + 1; i < ts.size(); i++) {
                Token q = ts.get(i);
                if (q.getType() == Token.EOF || q.getLine() != tok.getLine()) break;
                if (q.getType() != ALangLexer.ERROR_CHAR || !q.getText().equals("'")) continue;
                int col = tok.getCharPositionInLine() + 1;
                Span whole = new Span(tok.getLine(), col, q.getCharPositionInLine() + 1 - col + 1, file);
                String body = file.text(whole);
                body = body.substring(1, body.length() - 1);
                if (body.contains("\"") || body.contains("\\")) break;
                return List.of(new Edit(whole, "\"" + body + "\""));
            }
            return List.of();
        }
        if (expected == null) return List.of();
        // `a and b or c` and `a or b and c`: parentheses that keep the meaning `and` has in C, Python or Rust, where it
        // binds tighter than `or`: (a and b) or c, a or (b and c).
        if ((t == ALangLexer.AND || t == ALangLexer.OR) && prev != null && expectsOperator(expected)) {
            Token first;
            Token last;
            if (t == ALangLexer.OR) {
                first = exprStart(ts, tok, false);
                last = prev;
            } else {
                first = exprStart(ts, tok, true);
                last = exprEnd(ts, tok);
            }
            if (first == null || last == null) return List.of();
            return List.of(new Edit(before(first), "("), new Edit(after(last), ")"));
        }
        // `let x = 5;`: the type of a lone literal is the one it has on its own.
        if (t == ALangLexer.ASSIGN && expected.contains(ALangLexer.COLON) && prev != null && isNameLike(p)) {
            Token kw = previous(ts, prev);
            Token value = next;
            if (value != null && value.getType() == ALangLexer.MINUS) value = next(ts, value);
            Token end = value == null ? null : next(ts, value);
            if (kw != null && (kw.getType() == ALangLexer.LET || kw.getType() == ALangLexer.VAR) && end != null
                && end.getType() == ALangLexer.SEMI) {
                String type = switch (value.getType()) {
                    case ALangLexer.INT_LIT -> "i64";
                    case ALangLexer.FLOAT_LIT -> "f64";
                    case ALangLexer.TRUE, ALangLexer.FALSE -> value == next ? "bool" : null;
                    default -> null;
                };
                if (type != null) return List.of(new Edit(after(prev), ": " + type));
            }
        }
        // `Point { x: 1, y: 2 }` is Point(x: 1, y: 2): only where a field and `:` follow the brace.
        if (t == ALangLexer.LBRACE && p == ALangLexer.IDENT && Character.isUpperCase(prev.getText().charAt(0))
            && next != null && isNameLike(next.getType()) && next(ts, next) != null
            && next(ts, next).getType() == ALangLexer.COLON) {
            Token close = closer(ts, tok);
            if (close == null) return List.of();
            Token last = previous(ts, close);
            return List.of(new Edit(between(prev, tok, next), "("), new Edit(between(last, close, null), ")"));
        }
        return List.of();
    }

    /**
     * The first token of the expression that {@code tok} is in the middle of, scanning back to what cannot be part of
     * it at its own depth; with {@code afterOr}, the first token after the nearest `or` at that depth instead, if any.
     */
    private static Token exprStart(TokenStream ts, Token tok, boolean afterOr) {
        int depth = 0;
        Token first = null;
        for (int i = tok.getTokenIndex() - 1; i >= 0; i--) {
            Token t = ts.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            int k = t.getType();
            if (k == ALangLexer.RPAREN || k == ALangLexer.RBRACK) depth++;
            else if (k == ALangLexer.LPAREN || k == ALangLexer.LBRACK) {
                if (depth == 0) return first;
                depth--;
            } else if (depth == 0 && (endsExpression(k) || afterOr && k == ALangLexer.OR)) {
                return first;
            }
            first = t;
        }
        return first;
    }

    /** The last token of the operand chain that starts at {@code tok}'s operator: up to an `or` or the expression's end. */
    private static Token exprEnd(TokenStream ts, Token tok) {
        int depth = 0;
        Token last = null;
        for (int i = tok.getTokenIndex() + 1; i < ts.size(); i++) {
            Token t = ts.get(i);
            if (t.getType() == Token.EOF) return last;
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            int k = t.getType();
            if (k == ALangLexer.LPAREN || k == ALangLexer.LBRACK) depth++;
            else if (k == ALangLexer.RPAREN || k == ALangLexer.RBRACK) {
                if (depth == 0) return last;
                depth--;
            } else if (depth == 0 && (endsExpression(k) || k == ALangLexer.OR || k == ALangLexer.LBRACE)) {
                return last;
            }
            last = t;
        }
        return last;
    }

    /** Tokens that an expression never contains at its own depth: what comes before or after one. */
    private static boolean endsExpression(int k) {
        return switch (k) {
            case ALangLexer.IF, ALangLexer.WHILE, ALangLexer.RETURN, ALangLexer.ASSIGN, ALangLexer.PLUS_ASSIGN,
                 ALangLexer.MINUS_ASSIGN, ALangLexer.STAR_ASSIGN, ALangLexer.SLASH_ASSIGN, ALangLexer.PERCENT_ASSIGN,
                 ALangLexer.COMMA, ALangLexer.SEMI, ALangLexer.LBRACE, ALangLexer.RBRACE, ALangLexer.ASSERT,
                 ALangLexer.FAT_ARROW, ALangLexer.THEN, ALangLexer.ELSE, ALangLexer.REQUIRES, ALangLexer.ENSURES,
                 ALangLexer.LIMIT, ALangLexer.COLON, ALangLexer.IN, ALangLexer.STEP, ALangLexer.DOTDOT,
                 ALangLexer.DOTDOTEQ -> true;
            default -> false;
        };
    }

    /** Whether {@code t} is the `elif` (or `elsif`, `elseif`) of another language, right after the `}` of an if. */
    private static boolean elseIf(TokenStream ts, Token t) {
        if (t == null || t.getType() != ALangLexer.IDENT) return false;
        String w = t.getText();
        Token before = previous(ts, t);
        return (w.equals("elif") || w.equals("elsif") || w.equals("elseif")) && before != null
            && before.getType() == ALangLexer.RBRACE;
    }

    /** An empty span just before a token: where an edit inserts text in front of it. */
    private Span before(Token t) {
        return new Span(t.getLine(), t.getCharPositionInLine() + 1, 0, file);
    }

    /** The span of a token, in columns. */
    private Span span(Token t) {
        return new Span(t.getLine(), t.getCharPositionInLine() + 1, Source.columns(t.getText()), file);
    }

    /** An empty span just after a token: where an edit inserts text after it. */
    private Span after(Token t) {
        return new Span(t.getLine(), t.getCharPositionInLine() + 1 + Source.columns(t.getText()), 0, file);
    }

    /**
     * The span of {@code tok} with the blanks around it on its line: from the end of {@code before} if that is on the
     * same line, to the start of {@code after} if that is (a null side keeps the token's own edge).
     */
    private Span between(Token before, Token tok, Token after) {
        int start = tok.getCharPositionInLine() + 1;
        if (before != null && before.getLine() == tok.getLine()) start = before.getCharPositionInLine() + 1 + Source.columns(before.getText());
        int end = tok.getCharPositionInLine() + 1 + Source.columns(tok.getText());
        if (after != null && after.getLine() == tok.getLine()) end = after.getCharPositionInLine() + 1;
        return new Span(tok.getLine(), start, end - start, file);
    }

    /** {@code word}, with a space on each side where the token it replaces touches its neighbour: a&&b is a and b. */
    private static String spaced(Token prev, Token tok, Token next, String word) {
        return (prev != null && touches(prev, tok) ? " " : "") + word + (next != null && touches(tok, next) ? " " : "");
    }

    private static boolean touches(Token a, Token b) {
        return a.getStopIndex() + 1 == b.getStartIndex();
    }

    private static boolean wordLike(int t) {
        return isNameLike(t) || t == ALangLexer.INT_LIT || t == ALangLexer.FLOAT_LIT || t == ALangLexer.RPAREN
            || t == ALangLexer.RBRACK;
    }

    /** Whether a token of this type can end a place: x, p.x, xs[i]. */
    private static boolean endsPlace(int t) {
        return isNameLike(t) || t == ALangLexer.RBRACK;
    }

    private static Token next(TokenStream ts, Token tok) {
        for (int i = tok.getTokenIndex() + 1; i < ts.size(); i++) {
            Token t = ts.get(i);
            if (t.getType() == Token.EOF) return null;
            if (t.getChannel() == Token.DEFAULT_CHANNEL) return t;
        }
        return null;
    }

    /** The `}` that closes the brace {@code open}, or null. */
    private static Token closer(TokenStream ts, Token open) {
        int depth = 0;
        for (int i = open.getTokenIndex(); i < ts.size(); i++) {
            Token t = ts.get(i);
            if (t.getType() == Token.EOF) return null;
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (t.getType() == ALangLexer.LBRACE) depth++;
            if (t.getType() == ALangLexer.RBRACE && --depth == 0) return t;
        }
        return null;
    }

    /**
     * A character the lexer does not know, as a message shows it: in backticks, or by its code point when
     * it would not show there. A byte order mark, a no-break space or a form feed printed as itself made
     * "unexpected character ``", which names nothing the reader can find.
     */
    private static String strayCharacter(String s) {
        return invisible(s) ? String.format(Locale.ROOT, "U+%04X", s.codePointAt(0)) : "`" + s + "`";
    }

    /** Control characters, format characters such as U+FEFF and U+200B, and spaces other than ' '. */
    private static boolean invisible(String s) {
        return switch (Character.getType(s.codePointAt(0))) {
            case Character.CONTROL, Character.FORMAT, Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR,
                 Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        };
    }

    /** Whether the brackets that {@code close} ends hold a `..` of their own: they slice. */
    private static boolean slicedBefore(TokenStream ts, Token close) {
        int depth = 0;
        for (int i = close.getTokenIndex() - 1; i >= 0; i--) {
            int t = ts.get(i).getType();
            if (t == ALangLexer.RBRACK || t == ALangLexer.RPAREN) depth++;
            else if (t == ALangLexer.LBRACK || t == ALangLexer.LPAREN) {
                if (depth == 0) return false;
                depth--;
            } else if (t == ALangLexer.DOTDOT && depth == 0) {
                return true;
            }
        }
        return false;
    }

    private static Token previous(TokenStream ts, Token tok) {
        for (int i = tok.getTokenIndex() - 1; i >= 0; i--) {
            Token t = ts.get(i);
            if (t.getChannel() == Token.DEFAULT_CHANNEL) return t;
        }
        return null;
    }

    private static String describe(IntervalSet expected) {
        if (expected == null || expected.isNil()) return null;
        if (expected.size() == 1 && expected.contains(ALangParser.STRING_LIT)) return "a string in quotes";
        List<String> names = new ArrayList<>();
        boolean expr = false;
        boolean operator = false;
        boolean type = false;
        for (int t : expected.toList()) {
            if (t == ALangParser.INT_LIT || t == ALangParser.FLOAT_LIT || t == ALangParser.STRING_LIT
                || t == ALangParser.CHAR_LIT) {
                expr = true;
                continue;
            }
            if (isBinaryOperator(t) || t == ALangParser.DOT || t == ALangParser.LBRACK && expected.contains(ALangParser.PLUS)) {
                operator = true;
                continue;
            }
            if (t >= ALangParser.BOOL && t <= ALangParser.LIST) {
                type = true;
                continue;
            }
            if (t == ALangParser.IDENT) {
                names.add("a name");
                continue;
            }
            if (t == ALangParser.LIMIT || t == ALangParser.TEST || t == ALangParser.TOOL || t == ALangParser.IMPORT
                || t == ALangParser.STEP || t == ALangParser.REV || t == ALangParser.THEN || t == ALangParser.MAP
                || t == ALangParser.SET || t == ALangParser.AS || t == ALangParser.TRAPS) {
                if (expected.contains(ALangParser.IDENT)) continue;
            }
            String lit = ALangParser.VOCABULARY.getLiteralName(t);
            if (lit != null) names.add(lit);
            else if (t == Token.EOF) names.add("end of file");
        }
        if (type && !expr) names.add(0, "a type");
        if (expr) names.add(0, "an expression");
        if (operator) names.add("an operator");
        if (names.isEmpty()) return null;
        if (names.size() > 8) return "a statement or expression";
        if (names.size() == 1) return names.get(0);
        if (names.size() == 2) return names.get(0) + " or " + names.get(1);
        return "one of " + String.join(", ", names);
    }

    private static String hint(TokenStream ts, Token tok, Token prev, Token prev2, IntervalSet expected) {
        int t = tok.getType();
        int p = prev == null ? -1 : prev.getType();
        String foreign = switch (t) {
            case ALangLexer.AMPAMP -> "use the keyword `and` instead of `&&`";
            case ALangLexer.PIPEPIPE -> "use the keyword `or` instead of `||`";
            case ALangLexer.BANG -> "use the keyword `not` for negation (`!=` is still the inequality operator)";
            case ALangLexer.COLONCOLON -> "enum variants are accessed with a dot: Color.Red, Shape.Circle(1.0)";
            case ALangLexer.PLUSPLUS -> "there is no ++ operator; write `x += 1;`";
            case ALangLexer.QUESTION -> "there is no `?:` or `?` operator; use if/else or match";
            case ALangLexer.IDENT -> switch (tok.getText()) {
                case "elif", "elsif", "elseif" -> "use `else if`";
                case "loop" -> "use `while true limit N { ... }` or `for i in 0..n { ... }`";
                case "def", "func", "function" -> "functions are declared with `fn name(x: i32) -> i32 { ... }`";
                default -> null;
            };
            default -> null;
        };
        if (foreign != null) return foreign;
        if (elseIf(ts, prev)) return "use `else if`";
        if (t == ALangLexer.ERROR_CHAR) {
            String s = tok.getText();
            if (s.equals("#")) return "comments start with //";
            if (s.equals("\"")) return "unterminated string literal; strings must close on the same line (use \\n for newlines)";
            if (s.equals("'")) return "strings use double quotes \"...\"; single quotes are for one-byte character literals like 'a'";
            if (s.equals("﻿") && tok.getStartIndex() == 0) {
                return "the file starts with a byte order mark (U+FEFF); save it as UTF-8 without a BOM";
            }
            if (invisible(s)) {
                return strayCharacter(s) + " is an invisible character that is not part of ALang syntax; "
                    + "delete it, or replace it with a plain space";
            }
            return "the character '" + s + "' is not part of ALang syntax";
        }
        if (t == ALangLexer.STAR && p == ALangLexer.SLASH) {
            return "block comments are not supported; start each comment line with //";
        }
        String missing = missingFeature(ts, tok, prev, prev2, expected);
        if (missing != null) return missing;
        if (t == ALangLexer.LPAREN && p == ALangLexer.IDENT && prev2 != null && prev2.getType() == ALangLexer.DOT) {
            return "there are no methods; call functions with the value as an argument, e.g. len(xs) instead of xs.len()";
        }
        if (t == ALangLexer.LBRACE && p == ALangLexer.IDENT && Character.isUpperCase(prev.getText().charAt(0))) {
            return "struct values are built with named arguments: " + prev.getText() + "(field: value, ...)";
        }
        // `limit` is also a name, so it is expected where an expression starts too: not a `while` there.
        if (expected != null && expected.contains(ALangLexer.LIMIT) && t == ALangLexer.LBRACE
            && !expected.contains(ALangLexer.INT_LIT)) {
            return "while loops must state an iteration limit: while cond limit 1000 { ... }";
        }
        if (t == ALangLexer.ASSIGN && p == ALangLexer.RBRACK && slicedBefore(ts, prev)) {
            return "a slice is a value, not a place; set the elements one at a time: for i in a..b { xs[i] = v; }";
        }
        if (p == ALangLexer.FOR && t == ALangLexer.LPAREN) {
            return "for loops iterate a range or a collection: for i in 0..n { ... } or for x in xs { ... }";
        }
        if (expected != null && expected.contains(ALangLexer.COLON) && t == ALangLexer.ASSIGN) {
            return "every variable needs an explicit type: let x: i64 = 0;";
        }
        if (expected != null && expected.contains(ALangLexer.COLON)
            && (t == ALangLexer.COMMA || t == ALangLexer.RPAREN) && p == ALangLexer.IDENT) {
            return "parameters need explicit types: fn f(x: i32, ys: mut list[i32; 8])";
        }
        // Only where an operator could follow: elsewhere (`fn id<T>`, `1 | 2 =>`) it is not about precedence.
        if (isBinaryOperator(t) && prev != null && expectsOperator(expected)) {
            if (t == ALangLexer.AND || t == ALangLexer.OR) {
                return "`and` and `or` cannot be mixed without parentheses: (a and b) or c";
            }
            if (t == ALangLexer.EQ || t == ALangLexer.NEQ || t == ALangLexer.LT || t == ALangLexer.LE
                || t == ALangLexer.GT || t == ALangLexer.GE) {
                return "comparisons do not chain and bitwise expressions must be parenthesized before comparing: "
                    + "write a < b and b < c, or (flags & MASK) == 0";
            }
            return "bitwise operators (& | ^ << >>) cannot be combined with other operators without parentheses: "
                + "(a & b) | c, (x << 2) + 1";
        }
        if (isKeyword(t) && expected != null && expected.contains(ALangLexer.IDENT)) {
            return "`" + tok.getText() + "` is a reserved word; choose another name";
        }
        if (expected != null && expected.contains(ALangLexer.SEMI) && expected.size() <= 3) {
            return "add `;` at the end of the previous statement";
        }
        return null;
    }

    private static final String NO_STR_CONVERSION = "there is no str() conversion; build text with append(mut s, value) "
        + "into a str[N] variable, and change the capacity of a str with str[N](s)";

    private static final String TUPLES = "a tuple is built as (a, b), read as t.0 and t.1, and taken apart with a typed "
        + "let: let (a, b): (i64, i64) = pair;";

    /** Constructs common in other languages that ALang leaves out, each with the ALang way to write it. */
    private static String missingFeature(TokenStream ts, Token tok, Token prev, Token prev2, IntervalSet expected) {
        if (expected == null) return null;
        int t = tok.getType();
        int p = prev == null ? -1 : prev.getType();
        String word = tok.getText();
        if ((p == ALangLexer.STR && t == ALangLexer.LPAREN) || (t == ALangLexer.STR && expected.contains(ALangLexer.INT_LIT))) {
            // `str(x)`: a value turned into text, or a str whose capacity is left out.
            return NO_STR_CONVERSION;
        }
        String capacity = missingCapacity(ts, tok, p, expected);
        if (capacity != null) return capacity;
        if (p == ALangLexer.WHILE && t == ALangLexer.LBRACE) {
            return "a while loop needs a condition and a limit: while i < n limit 1000 { ... }";
        }
        if ((t == ALangLexer.LBRACK || t == ALangLexer.LT) && p == ALangLexer.IDENT && !expectsOperator(expected)
            && isCollection(prev.getText())) {
            return Hints.COLLECTIONS; // map[K, V], HashMap<K, V>, set[T]
        }
        if (t == ALangLexer.LBRACE && expected.contains(ALangLexer.INT_LIT) && switch (p) {
            case ALangLexer.ASSIGN, ALangLexer.RETURN, ALangLexer.LPAREN, ALangLexer.COMMA, ALangLexer.COLON -> true;
            default -> false;
        }) {
            // `{` where a value must start: `= {}`, `return {...}`, `f({...})`.
            return "`{ }` is a block, not a value: an empty list is [], a struct value is Name(field: value, ...), "
                + "and there are no map or set literals";
        }
        if (expected.contains(ALangLexer.STRUCT)) {
            // Where a top-level declaration may start.
            if (t == ALangLexer.LET || t == ALangLexer.VAR || t == ALangLexer.IDENT && word.equals("static")) {
                return "there are no global variables; use `const NAME: T = value;` for a fixed value, "
                    + "and pass changing state to functions as a `mut` parameter";
            }
            if (t == ALangLexer.IMPORT) {
                return "imports come first: move this import above every other declaration of the file";
            }
            if (t == ALangLexer.IDENT && switch (word) {
                case "use", "include", "require", "from", "package", "module", "mod" -> true;
                default -> false;
            }) {
                return "to use the declarations of another file, import it at the top of this one: import \"helpers.al\"; "
                    + "(a path relative to this file). There are no modules or namespaces";
            }
        }
        if (p == ALangLexer.IMPORT && t != ALangLexer.STRING_LIT && !expectsOperator(expected)) {
            // `import math;`, `import foo.bar;`, `import { x } from ...`
            return "import names a file, in quotes, relative to this one: import \"helpers.al\";";
        }
        // `Vec<T>`, or `List[int]` / `Optional[int]` where a type starts (elsewhere `xs[` is an index).
        if ((t == ALangLexer.LT || t == ALangLexer.LBRACK && prev2 != null
                && (prev2.getType() == ALangLexer.COLON || prev2.getType() == ALangLexer.ARROW))
            && p == ALangLexer.IDENT && !expectsOperator(expected)) {
            return "a type takes its element types in brackets: list[i64; 16], map[str[8], i64; 16]; only a function has "
                + "type parameters, fn first<T>(xs: list[T; _]) -> T, and an optional value is an enum: "
                + "enum MaybeI64 { None, Some(i64) }";
        }
        if (expected.contains(ALangLexer.FAT_ARROW)) {
            // After a pattern, where `=>` should come.
            if (t == ALangLexer.PIPE) {
                return "a match arm has exactly one pattern; write one arm per value: 1 => { ... } 2 => { ... }";
            }
            if (t == ALangLexer.DOTDOT) {
                return "there are no range patterns; use an if/else if chain instead: if 1 <= x and x < 5 { ... }";
            }
        }
        if (t == ALangLexer.LBRACE && expected.contains(ALangLexer.THEN)) {
            return "an if expression is written with `then` and no braces: let x: i64 = if c then 1 else 2;";
        }
        if (t == ALangLexer.IF && expected.contains(ALangLexer.INT_LIT) && !expected.contains(ALangLexer.LET)) {
            // Where an operand must start: an if expression is a whole expression.
            return "an if expression is never an operand; put it in parentheses: 1 + (if c then 2 else 3)";
        }
        if (t == ALangLexer.MATCH && expected.contains(ALangLexer.INT_LIT) && !expected.contains(ALangLexer.LET)) {
            return "`match` is a statement, not an expression; declare the variable first and assign it in every "
                + "arm: var x: i64 = 0; match c { ... => { x = 1; } }; for a choice between two values, "
                + "if c then a else b is an expression";
        }
        if (t == ALangLexer.FN) {
            if (expected.contains(ALangLexer.LET)) {
                return "functions cannot be nested; declare every fn at the top level";
            }
            if (expected.contains(ALangLexer.I64)) {
                return "a function type is only the type of a parameter: fn apply(f: fn(i64) -> i64, x: i64); there are "
                    + "no closures, so pass a function declared at the top level by its name";
            }
        }
        if (t == ALangLexer.COMMA) {
            // A comma inside parentheses that do not belong to a call, constructor, pattern or declaration.
            Token open = opener(ts, tok);
            if (open != null && open.getType() == ALangLexer.LPAREN) {
                Token before = previous(ts, open);
                if (before == null || !isNameLike(before.getType())) return TUPLES;
            }
        }
        if (t == ALangLexer.DOTDOT) {
            Token open = opener(ts, tok);
            if (open != null && open.getType() == ALangLexer.LBRACK) {
                return "a range is not a value; a slice is xs[a..b], and a list of numbers is built in a loop: "
                    + "for i in a..b { push(mut xs, i); }";
            }
        }
        if (t == ALangLexer.IDENT && (word.equals("by") || word.equals("step_by")) && expected.contains(ALangLexer.LBRACE)
            && inForHeader(ts, tok)) {
            return "a range counts by a step with `step`: for i in 0..10 step 2 { }; to count down, add `rev`: "
                + "for i in rev 0..10 { } goes 9, 8, ..., 0";
        }
        if (t == ALangLexer.ASSIGN && expected.contains(ALangLexer.COMMA) && expected.contains(ALangLexer.RBRACE)) {
            // `=` in a struct's fields or an enum's variants.
            Token open = opener(ts, tok);
            Token name = open == null ? null : previous(ts, open);
            Token keyword = name == null ? null : previous(ts, name);
            if (keyword != null && keyword.getType() == ALangLexer.STRUCT) {
                return "struct fields have no default values; give every field when building the value: "
                    + name.getText() + "(field: value, ...)";
            }
            if (keyword != null && keyword.getType() == ALangLexer.ENUM) {
                return "enum variants have no numeric values; when you need a number, map each variant to it with a match";
            }
        }
        return null;
    }

    /** A str, list or array type without its capacity: {@code str}, {@code list<T>}, {@code list[T]}, {@code [T]}. */
    private static String missingCapacity(TokenStream ts, Token tok, int p, IntervalSet expected) {
        int t = tok.getType();
        if (p == ALangLexer.STR && t != ALangLexer.LBRACK && expected.contains(ALangLexer.LBRACK)) {
            return "a string type needs its capacity in bytes: str[64]";
        }
        if (p == ALangLexer.LIST && t != ALangLexer.LBRACK && expected.contains(ALangLexer.LBRACK)) {
            return "a list type needs an element type and a capacity: list[i64; 16]";
        }
        if (t == ALangLexer.RBRACK && expected.contains(ALangLexer.SEMI)) {
            // A `]` that closes a `[` is unexpected only in a type: in an expression it closes an index
            // or a literal. (A stray `]`, as in `xs[0]];`, closes nothing.)
            Token open = opener(ts, tok);
            if (open != null && open.getType() == ALangLexer.LBRACK) {
                Token kind = previous(ts, open);
                if (kind != null && (kind.getType() == ALangLexer.MAP || kind.getType() == ALangLexer.SET)) {
                    return "maps and sets need a capacity after `;`: map[str[16], i64; 64] holds up to 64 entries, "
                        + "set[i64; 32] up to 32 values";
                }
                return "lists and arrays need a capacity after `;`: list[i64; 16] holds up to 16 items, "
                    + "[i64; 4] exactly 4";
            }
        }
        return null;
    }

    /** Names other languages give maps, dictionaries and sets. */
    private static boolean isCollection(String name) {
        return switch (name) {
            case "map", "Map", "HashMap", "hashmap", "BTreeMap", "TreeMap", "unordered_map", "dict", "Dict",
                 "dictionary", "Dictionary", "OrderedDict", "set", "Set", "HashSet", "BTreeSet", "TreeSet",
                 "unordered_set", "frozenset" -> true;
            default -> false;
        };
    }

    /** The innermost bracket, parenthesis or brace still open at {@code tok}, or null. */
    private static Token opener(TokenStream ts, Token tok) {
        int depth = 0;
        for (int i = tok.getTokenIndex() - 1; i >= 0; i--) {
            Token t = ts.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            switch (t.getType()) {
                case ALangLexer.RPAREN, ALangLexer.RBRACK, ALangLexer.RBRACE -> depth++;
                case ALangLexer.LPAREN, ALangLexer.LBRACK, ALangLexer.LBRACE -> {
                    if (depth == 0) return t;
                    depth--;
                }
                default -> { }
            }
        }
        return null;
    }


    /** Whether {@code tok} is between `for` and the loop's `{`. */
    private static boolean inForHeader(TokenStream ts, Token tok) {
        for (int i = tok.getTokenIndex() - 1; i >= 0; i--) {
            switch (ts.get(i).getType()) {
                case ALangLexer.FOR -> {
                    return true;
                }
                case ALangLexer.LBRACE, ALangLexer.RBRACE, ALangLexer.SEMI -> {
                    return false;
                }
                default -> { }
            }
        }
        return false;
    }

    /** Tokens that can come right before the `(` of a call, constructor, pattern or declaration. */
    private static boolean isNameLike(int t) {
        return t == ALangLexer.IDENT || t == ALangLexer.LIMIT || t == ALangLexer.TEST || t == ALangLexer.TOOL
            || t == ALangLexer.IMPORT || t == ALangLexer.STEP || t == ALangLexer.REV || t == ALangLexer.THEN || t == ALangLexer.MAP || t == ALangLexer.SET || t == ALangLexer.AS || t == ALangLexer.TRAPS || t >= ALangLexer.BOOL && t <= ALangLexer.LIST;
    }

    private static boolean expectsOperator(IntervalSet expected) {
        if (expected == null) return false;
        for (int t : expected.toList()) {
            if (isBinaryOperator(t)) return true;
        }
        return false;
    }

    private static boolean isBinaryOperator(int t) {
        return switch (t) {
            case ALangLexer.AND, ALangLexer.OR, ALangLexer.EQ, ALangLexer.NEQ, ALangLexer.LT, ALangLexer.LE,
                 ALangLexer.GT, ALangLexer.GE, ALangLexer.AMP, ALangLexer.PIPE, ALangLexer.CARET,
                 ALangLexer.SHL, ALangLexer.SHR, ALangLexer.PLUS, ALangLexer.MINUS, ALangLexer.STAR,
                 ALangLexer.SLASH, ALangLexer.PERCENT -> true;
            default -> false;
        };
    }

    private static boolean isKeyword(int t) {
        String lit = ALangLexer.VOCABULARY.getLiteralName(t);
        return lit != null && Character.isLetter(lit.charAt(1));
    }
}
