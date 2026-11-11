package alang.parse;

import alang.ast.Ast.Program;
import alang.diag.Diagnostics;
import alang.diag.Source;
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

    /** One parsed file: its declarations and imports, and what the nesting limit needs of it. */
    public static final class Parsed {
        public final Program program;
        private final Nesting nesting;

        private Parsed(Program program, Nesting nesting) {
            this.program = program;
            this.nesting = nesting;
        }
    }

    /**
     * Parses one file of a program; returns null if it has syntax errors (reported to {@code diags}). An
     * imported file leaves out its `fn main` and tests, and its `tool fn`s are not tools. Once every file
     * of the program is parsed, {@link #checkChains} checks the nesting limit across them.
     */
    public static Parsed parse(Source file, Diagnostics diags, boolean imported) {
        ALangLexer lexer = new ALangLexer(CharStreams.fromString(file.text, file.path));
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        List<Token> code = tokens.getTokens().stream()
            .filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL && t.getType() != Token.EOF).toList();
        // The parser recurses on nesting, so the limit is checked before it runs (E0404).
        Nesting nesting = Nesting.scan(code, file, diags);
        if (nesting == null) return null;
        ALangParser parser = new ALangParser(tokens);
        parser.removeErrorListeners();
        SyntaxErrorListener listener = new SyntaxErrorListener(diags, file);
        parser.addErrorListener(listener);
        parser.getInterpreter().setPredictionMode(PredictionMode.LL);
        ALangParser.ProgramContext tree = parser.program();
        if (listener.hasError()) return null;
        return new Parsed(AstBuilder.build(diags, file, tokens, tree, imported), nesting);
    }

    /**
     * Reports E0404 and returns false if a chain of declarations that use each other, in any of the
     * program's files, nests too deeply. The checker, interpreter and C backend follow calls and uses of
     * constants and types.
     */
    public static boolean checkChains(List<Parsed> files, Diagnostics diags) {
        return Nesting.merge(files.stream().map(f -> f.nesting).toList()).checkChains(diags);
    }
}
