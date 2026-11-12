package alang.parse;

import alang.diag.Code;
import alang.diag.Diagnostics;
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
        String hint = hint(ts, tok, prev, prev2, expected);
        diags.error(Code.E0001, new Span(tok.getLine(), tok.getCharPositionInLine() + 1, len, file), message, hint);
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
                || t == ALangParser.SET) {
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
        if (p == ALangLexer.FOR && t == ALangLexer.LPAREN) {
            return "for loops iterate a range or a collection: for i in 0..n { ... } or for x in xs { ... }";
        }
        if (expected != null && expected.contains(ALangLexer.COLON) && t == ALangLexer.ASSIGN) {
            return "every variable needs an explicit type: let x: i32 = 0;";
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
                return "there are no slices or range values; copy the part you need in a loop: "
                    + "for i in a..b { push(mut part, xs[i]); }";
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
            || t == ALangLexer.IMPORT || t == ALangLexer.STEP || t == ALangLexer.REV || t == ALangLexer.THEN || t == ALangLexer.MAP || t == ALangLexer.SET || t >= ALangLexer.BOOL && t <= ALangLexer.LIST;
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
