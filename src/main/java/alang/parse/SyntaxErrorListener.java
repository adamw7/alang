package alang.parse;

import alang.diag.Code;
import alang.diag.Diagnostics;
import alang.diag.Source;
import alang.diag.Span;
import alang.parser.ALangLexer;
import alang.parser.ALangParser;
import java.util.ArrayList;
import java.util.List;
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
    private boolean reported;

    SyntaxErrorListener(Diagnostics diags) {
        this.diags = diags;
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
            diags.error(Code.E0001, new Span(line, charPositionInLine + 1, 1), msg);
            return;
        }
        IntervalSet expected = e != null ? e.getExpectedTokens() : parser.getExpectedTokens();
        TokenStream ts = parser.getInputStream();
        Token prev = previous(ts, tok);
        Token prev2 = prev == null ? null : previous(ts, prev);

        String text = tok.getType() == Token.EOF ? "end of file"
            : tok.getType() == ALangLexer.ERROR_CHAR ? "character `" + tok.getText() + "`"
            : "'" + tok.getText() + "'";
        int len = tok.getType() == Token.EOF ? 1 : Math.max(1, Source.columns(tok.getText()));

        // A missing semicolon is reported at the end of the previous line, where it belongs.
        if (expected != null && expected.contains(ALangLexer.SEMI) && prev != null
            && (tok.getLine() > prev.getLine() || tok.getType() == Token.EOF) && tok.getType() != ALangLexer.SEMI) {
            Span at = new Span(prev.getLine(), prev.getCharPositionInLine() + 1, Math.max(1, Source.columns(prev.getText())));
            diags.error(Code.E0001, at, "missing ';' after '" + prev.getText() + "'",
                "every statement and const declaration ends with `;`");
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
        String hint = hint(tok, prev, prev2, expected);
        diags.error(Code.E0001, new Span(tok.getLine(), tok.getCharPositionInLine() + 1, len), message, hint);
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
            if (t == ALangParser.LIMIT || t == ALangParser.TEST || t == ALangParser.TOOL) {
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

    private static String hint(Token tok, Token prev, Token prev2, IntervalSet expected) {
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
        if (t == ALangLexer.ERROR_CHAR) {
            String s = tok.getText();
            if (s.equals("#")) return "comments start with //";
            if (s.equals("\"")) return "unterminated string literal; strings must close on the same line (use \\n for newlines)";
            if (s.equals("'")) return "strings use double quotes \"...\"; single quotes are for one-byte character literals like 'a'";
            return "the character '" + s + "' is not part of ALang syntax";
        }
        if (t == ALangLexer.STAR && p == ALangLexer.SLASH) {
            return "block comments are not supported; start each comment line with //";
        }
        if (t == ALangLexer.LPAREN && p == ALangLexer.IDENT && prev2 != null && prev2.getType() == ALangLexer.DOT) {
            return "there are no methods; call functions with the value as an argument, e.g. len(xs) instead of xs.len()";
        }
        if (t == ALangLexer.LBRACE && p == ALangLexer.IDENT && Character.isUpperCase(prev.getText().charAt(0))) {
            return "struct values are built with named arguments: " + prev.getText() + "(field: value, ...)";
        }
        if (expected != null && expected.contains(ALangLexer.LIMIT) && t == ALangLexer.LBRACE) {
            return "while loops must state an iteration limit: while cond limit 1000 { ... }";
        }
        if (p == ALangLexer.FOR && t == ALangLexer.LPAREN) {
            return "for loops iterate a range or a collection: for i in 0..n { ... } or for x in xs { ... }";
        }
        if (p == ALangLexer.DOTDOT && t == ALangLexer.ASSIGN) {
            return "ranges are half-open (end excluded); write a..(b + 1) instead of a..=b";
        }
        if (expected != null && expected.contains(ALangLexer.COLON) && t == ALangLexer.ASSIGN) {
            return "every variable needs an explicit type: let x: i32 = 0;";
        }
        if (expected != null && expected.contains(ALangLexer.COLON)
            && (t == ALangLexer.COMMA || t == ALangLexer.RPAREN) && p == ALangLexer.IDENT) {
            return "parameters need explicit types: fn f(x: i32, ys: mut list[i32; 8])";
        }
        if (isBinaryOperator(t) && prev != null) {
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
