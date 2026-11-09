package alang.parse;

import alang.ast.Ast.Program;
import alang.diag.Code;
import alang.diag.Diagnostics;
import alang.parser.ALangLexer;
import alang.parser.ALangParser;
import java.util.ArrayDeque;
import java.util.Deque;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;

/** Parses ALang source using the parser generated from grammar/ALang.g4. */
public final class Parser {
    private Parser() {}

    /**
     * How deeply brackets {@code ( ) [ ] { }} and the prefix operators {@code - ~ not} may nest. Every
     * recursive rule of the grammar goes through one of them, so this bounds the stack the parser
     * uses, including its lookahead, to a fraction of a default 1 MB thread stack; and the same
     * programs are accepted on every machine.
     */
    public static final int MAX_NESTING = 128;

    /** Returns the AST, or null if there were syntax errors (reported to {@code diags}). */
    public static Program parse(Diagnostics diags) {
        ALangLexer lexer = new ALangLexer(CharStreams.fromString(diags.source().text, diags.source().path));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        Token deep = tooDeep(tokens);
        if (deep != null) {
            diags.error(Code.E0404, AstBuilder.span(deep),
                "brackets and prefix operators are nested more than " + MAX_NESTING + " levels deep here",
                "move the inner part into a `let` binding or a helper function");
            return null;
        }
        ALangParser parser = new ALangParser(tokens);
        parser.removeErrorListeners();
        SyntaxErrorListener listener = new SyntaxErrorListener(diags);
        parser.addErrorListener(listener);
        parser.getInterpreter().setPredictionMode(PredictionMode.LL);
        ALangParser.ProgramContext tree = parser.program();
        if (listener.hasError()) return null;
        return AstBuilder.build(diags, tokens, tree);
    }

    /**
     * Returns the first token nested more than {@link #MAX_NESTING} levels deep, or null. Each open
     * bracket is a level, and so is each prefix operator until the operand it applies to has ended
     * (including suffixes such as {@code .field}, {@code [index]} and call arguments).
     */
    private static Token tooDeep(CommonTokenStream tokens) {
        // One entry per open bracket (and one for the top level): the prefix operators still open there.
        Deque<int[]> frames = new ArrayDeque<>();
        frames.push(new int[1]);
        int nesting = 0;
        boolean operandEnded = false;
        Token prev = null;
        for (Token t : tokens.getTokens()) {
            if (t.getChannel() != Token.DEFAULT_CHANNEL || t.getType() == Token.EOF) continue;
            int type = t.getType();
            if (operandEnded && type != ALangLexer.DOT && type != ALangLexer.LBRACK && type != ALangLexer.LPAREN) {
                // The operand is complete, and so are the prefix operators applied to it.
                nesting -= frames.peek()[0];
                frames.peek()[0] = 0;
            }
            operandEnded = false;
            switch (type) {
                case ALangLexer.LPAREN, ALangLexer.LBRACK, ALangLexer.LBRACE -> {
                    if (++nesting > MAX_NESTING) return t;
                    frames.push(new int[1]);
                }
                case ALangLexer.RPAREN, ALangLexer.RBRACK, ALangLexer.RBRACE -> {
                    if (frames.size() > 1) {
                        nesting -= frames.pop()[0] + 1;
                        operandEnded = true;
                    }
                }
                case ALangLexer.MINUS, ALangLexer.TILDE, ALangLexer.NOT -> {
                    if (type != ALangLexer.MINUS || !endsOperand(prev)) {
                        frames.peek()[0]++;
                        if (++nesting > MAX_NESTING) return t;
                    }
                }
                default -> operandEnded = endsOperand(t);
            }
            prev = t;
        }
        return null;
    }

    /** Can {@code t} be the last token of an operand (so that a `-` after it subtracts)? */
    private static boolean endsOperand(Token t) {
        if (t == null) return false;
        return switch (t.getType()) {
            case ALangLexer.IDENT, ALangLexer.LIMIT, ALangLexer.TEST, ALangLexer.TOOL, ALangLexer.INT_LIT,
                 ALangLexer.FLOAT_LIT, ALangLexer.STRING_LIT, ALangLexer.CHAR_LIT, ALangLexer.TRUE, ALangLexer.FALSE,
                 ALangLexer.RPAREN, ALangLexer.RBRACK -> true;
            default -> false;
        };
    }
}
