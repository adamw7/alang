package alang.parse;

import alang.ast.Ast.Program;
import alang.diag.Diagnostics;
import alang.parser.ALangLexer;
import alang.parser.ALangParser;
import java.util.List;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.atn.PredictionMode;

/** Parses ALang source using the parser generated from grammar/ALang.g4. */
public final class Parser {
    private Parser() {}

    /** Returns the AST, or null if there were syntax errors (reported to {@code diags}). */
    public static Program parse(Diagnostics diags) {
        ALangLexer lexer = new ALangLexer(CharStreams.fromString(diags.source().text, diags.source().path));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        List<Token> code = tokens.getTokens().stream()
            .filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL && t.getType() != Token.EOF).toList();
        // The parser recurses on nesting, so the limit is checked before it runs (E0404).
        Nesting nesting = Nesting.scan(code, diags);
        if (nesting == null) return null;
        ALangParser parser = new ALangParser(tokens);
        parser.removeErrorListeners();
        SyntaxErrorListener listener = new SyntaxErrorListener(diags);
        parser.addErrorListener(listener);
        parser.getInterpreter().setPredictionMode(PredictionMode.LL);
        ALangParser.ProgramContext tree = parser.program();
        if (listener.hasError()) return null;
        // The checker, interpreter and C backend follow calls and uses of constants and types.
        if (!nesting.checkChains(diags)) return null;
        return AstBuilder.build(diags, tokens, tree);
    }
}
