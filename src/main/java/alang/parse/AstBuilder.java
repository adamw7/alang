package alang.parse;

import alang.ast.Ast.*;
import alang.ast.Ast;
import alang.diag.*;
import alang.parser.*;
import alang.parser.ALangParser.*;
import alang.types.PrimKind;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.antlr.v4.runtime.*;

/** Converts the ANTLR parse tree into the {@link Ast}. */
final class AstBuilder {
    private final Diagnostics diags;
    private final Source file;
    private final BufferedTokenStream tokens;
    private final boolean imported;

    AstBuilder(Diagnostics diags, Source file, BufferedTokenStream tokens, boolean imported) {
        this.diags = diags;
        this.file = file;
        this.tokens = tokens;
        this.imported = imported;
    }

    // ------------------------------------------------------------------ spans

    static Span span(Token t, Source file) {
        return new Span(t.getLine(), t.getCharPositionInLine() + 1, Math.max(1, Source.columns(t.getText())), file);
    }

    private Span span(Token t) {
        return span(t, file);
    }

    private Span span(ParserRuleContext ctx) {
        return span(ctx.getStart(), ctx.getStop());
    }

    private Span span(Token start, Token stop) {
        int len = 1;
        if (stop != null && stop.getLine() == start.getLine() && stop.getStopIndex() >= start.getStartIndex()) {
            len = stop.getStopIndex() - start.getStartIndex() + 1;
        } else if (start.getText() != null) {
            len = Math.max(1, Source.columns(start.getText()));
        }
        return new Span(start.getLine(), start.getCharPositionInLine() + 1, len, file);
    }

    // ------------------------------------------------------------------ items

    /**
     * The file's declarations and imports. An imported file's `fn main` and tests belong to it alone, so
     * they are left out, and its `tool fn`s are plain functions: the program's tools are its main file's.
     */
    Program program(ProgramContext ctx) {
        List<Import> imports = new ArrayList<>();
        for (ImportDeclContext ic : ctx.importDecl()) {
            Token path = ic.STRING_LIT().getSymbol();
            IdentifierContext alias = ic.identifier();
            imports.add(new Import(span(path), new String(unescape(path), StandardCharsets.UTF_8),
                alias == null ? null : alias.getText(), alias == null ? null : span(alias)));
        }
        List<Item> items = new ArrayList<>();
        for (ItemContext ic : ctx.item()) {
            if (imported && (ic.testDecl() != null
                || ic.functionDecl() != null && ic.functionDecl().identifier().getText().equals("main"))) {
                continue;
            }
            Item item = item(ic);
            item.doc = docComment(ic.getStart());
            items.add(item);
        }
        return new Program(file, items, imports);
    }

    private String docComment(Token start) {
        List<Token> hidden = tokens.getHiddenTokensToLeft(start.getTokenIndex(), Token.HIDDEN_CHANNEL);
        if (hidden == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Token t : hidden) {
            if (t.getType() != ALangLexer.DOC_COMMENT) continue;
            String line = t.getText().substring(3);
            if (line.startsWith(" ")) line = line.substring(1);
            if (sb.length() > 0) sb.append('\n');
            sb.append(line.stripTrailing());
        }
        return sb.toString().strip();
    }

    private Item item(ItemContext ctx) {
        if (ctx.constDecl() != null) {
            ConstDeclContext c = ctx.constDecl();
            return new ConstDecl(span(c.identifier()), c.identifier().getText(), type(c.type()), expr(c.expression()));
        }
        if (ctx.structDecl() != null) {
            StructDeclContext s = ctx.structDecl();
            List<Field> fields = new ArrayList<>();
            for (FieldDeclContext f : s.fieldDecl()) {
                Field field = new Field(span(f.identifier()), f.identifier().getText(), type(f.type()));
                field.doc = docComment(f.getStart());
                if (f.expression() != null) field.def = expr(f.expression());
                fields.add(field);
            }
            return new StructDecl(span(s.identifier()), s.identifier().getText(), fields, generics(s.typeParams()));
        }
        if (ctx.enumDecl() != null) {
            EnumDeclContext e = ctx.enumDecl();
            List<Variant> variants = new ArrayList<>();
            for (VariantDeclContext v : e.variantDecl()) {
                List<TypeRef> payload = new ArrayList<>();
                for (TypeContext t : v.type()) payload.add(type(t));
                Variant variant = new Variant(span(v.identifier()), v.identifier().getText(), variants.size(), payload);
                variant.doc = docComment(v.getStart());
                variants.add(variant);
            }
            return new EnumDecl(span(e.identifier()), e.identifier().getText(), variants, generics(e.typeParams()));
        }
        if (ctx.functionDecl() != null) return function(ctx.functionDecl());
        if (ctx.typeAliasDecl() != null) {
            TypeAliasDeclContext a = ctx.typeAliasDecl();
            return new TypeAlias(span(a.identifier()), a.identifier().getText(), generics(a.typeParams()), type(a.type()));
        }
        TestDeclContext t = ctx.testDecl();
        String name = new String(unescape(t.STRING_LIT().getSymbol()), StandardCharsets.UTF_8);
        Code expected = null;
        Span trapSpan = null;
        if (t.TRAPS() != null) {
            trapSpan = span(t.identifier());
            expected = trapCode(t.identifier().getText(), trapSpan);
        }
        return new TestDecl(span(t.STRING_LIT().getSymbol()), name, block(t.block()), expected, trapSpan);
    }

    /** The trap code of `test "..." traps R0006`, or null after reporting that the text names none. */
    private Code trapCode(String text, Span at) {
        for (Code c : Code.values()) {
            if (c.isRuntime() && c.name().equals(text)) return c;
        }
        String upper = text.toUpperCase(java.util.Locale.ROOT);
        Code last = Code.R0001;
        for (Code c : Code.values()) {
            if (c.isRuntime()) last = c;
        }
        String hint = "the trap codes are R0001 to " + last.name() + "; `alang explain --all` lists them";
        for (Code c : Code.values()) {
            if (c.isRuntime() && c.name().equals(upper)) hint = "write " + c.name() + " (" + c.title + ")";
        }
        diags.error(Code.E0001, at, "`" + text + "` is not a trap code", hint);
        return null;
    }

    /** The type parameters of `struct Pair<A, B>` or `enum Maybe<T>`. */
    private Generics generics(TypeParamsContext ctx) {
        List<String> names = new ArrayList<>();
        List<Span> spans = new ArrayList<>();
        if (ctx != null) {
            for (IdentifierContext t : ctx.identifier()) {
                names.add(t.getText());
                spans.add(span(t));
            }
        }
        return new Generics(names, spans);
    }

    private FnDecl function(FunctionDeclContext f) {
        List<Param> params = new ArrayList<>();
        if (f.paramList() != null) {
            for (ParamContext p : f.paramList().param()) {
                params.add(new Param(span(p.identifier()), p.identifier().getText(), p.MUT() != null, type(p.type()),
                    docComment(p.getStart()), p.expression() == null ? null : expr(p.expression())));
            }
        }
        List<Expr> requires = new ArrayList<>();
        List<Expr> ensures = new ArrayList<>();
        List<String> requiresText = new ArrayList<>();
        List<String> ensuresText = new ArrayList<>();
        for (ContractContext c : f.contract()) {
            boolean pre = c.kind.getType() == ALangLexer.REQUIRES;
            (pre ? requires : ensures).add(expr(c.expression()));
            (pre ? requiresText : ensuresText).add(oneLine(c.expression()));
        }
        TypeRef ret = f.type() == null ? null : type(f.type());
        List<String> typeParams = new ArrayList<>();
        List<Span> typeParamSpans = new ArrayList<>();
        if (f.typeParams() != null) {
            for (IdentifierContext t : f.typeParams().identifier()) {
                typeParams.add(t.getText());
                typeParamSpans.add(span(t));
            }
        }
        FnDecl fn = new FnDecl(span(f), span(f.identifier()), f.identifier().getText(), f.TOOL() != null && !imported,
            typeParams, typeParamSpans, params, ret, requires, ensures, requiresText, ensuresText, block(f.block()));
        if (!typeParams.isEmpty() || params.stream().anyMatch(p -> anyCapacity(p.typeRef()) || p.typeRef() instanceof FnRef)) {
            // A function that takes any capacity is checked once as written, then once more for each set of
            // capacities it is called with, on a fresh copy of its tree; the syntax errors were reported already.
            fn.copy = () -> new AstBuilder(new Diagnostics(file), file, tokens, imported).function(f);
        }
        return fn;
    }

    /**
     * The tokens of {@code ctx} on one line: one space wherever the source has whitespace or a comment
     * between two tokens, none where they touch, so `len(xs) >\n    0` reads `len(xs) > 0`.
     */
    private String oneLine(ParserRuleContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx.getStop() == null) return "";
        Token prev = null;
        for (int i = ctx.getStart().getTokenIndex(); i <= ctx.getStop().getTokenIndex(); i++) {
            Token t = tokens.get(i);
            if (t.getChannel() != Token.DEFAULT_CHANNEL) continue;
            if (prev != null && t.getStartIndex() > prev.getStopIndex() + 1) sb.append(' ');
            sb.append(t.getText());
            prev = t;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ types

    private TypeRef type(TypeContext ctx) {
        Span sp = span(ctx);
        if (ctx instanceof PrimitiveTypeRefContext p) {
            return new PrimRef(sp, PrimKind.byName(p.primitiveType().getText()));
        }
        if (ctx instanceof StrTypeRefContext s) return new StrRef(sp, capacity(s.capacity()));
        if (ctx instanceof ListTypeRefContext l) return new ListRef(sp, type(l.type()), capacity(l.capacity()));
        if (ctx instanceof ArrayTypeRefContext a) return new ArrayRef(sp, type(a.type()), capacity(a.capacity()));
        if (ctx instanceof FnTypeRefContext f) {
            List<TypeRef> ps = new ArrayList<>();
            List<TypeContext> ts = f.type();
            // With `-> R` the last type is the result.
            boolean ret = f.ARROW() != null;
            for (int i = 0; i < ts.size() - (ret ? 1 : 0); i++) ps.add(type(ts.get(i)));
            return new FnRef(sp, ps, ret ? type(ts.get(ts.size() - 1)) : null);
        }
        if (ctx instanceof MapTypeRefContext m) {
            return new MapRef(sp, type(m.type(0)), type(m.type(1)), capacity(m.capacity()));
        }
        if (ctx instanceof SetTypeRefContext s) return new SetRef(sp, type(s.type()), capacity(s.capacity()));
        if (ctx instanceof TupleTypeRefContext t) {
            List<TypeRef> elems = new ArrayList<>();
            for (TypeContext e : t.type()) elems.add(type(e));
            return new TupleRef(sp, elems);
        }
        if (ctx instanceof NestedTypeRefContext n) {
            // Name<A, Inner<B>>: the `>>` closes Inner's arguments and Name's.
            List<TypeRef> args = new ArrayList<>();
            for (TypeContext t : n.type()) args.add(type(t));
            InnerTypeContext in = n.innerType();
            List<TypeRef> innerArgs = new ArrayList<>();
            for (TypeContext t : in.type()) innerArgs.add(type(t));
            args.add(new NamedRef(span(in.getStart(), n.SHR().getSymbol()), in.identifier(in.identifier().size() - 1).getText(),
                in.qual == null ? null : in.qual.getText(), innerArgs));
            return new NamedRef(sp, n.identifier(n.identifier().size() - 1).getText(), n.qual == null ? null : n.qual.getText(), args);
        }
        NamedTypeRefContext n = (NamedTypeRefContext) ctx;
        List<TypeRef> args = new ArrayList<>();
        for (TypeContext t : n.type()) args.add(type(t));
        return new NamedRef(sp, n.identifier(n.identifier().size() - 1).getText(), n.qual == null ? null : n.qual.getText(), args);
    }

    /** A capacity or length: its expression, or null for `_` (any capacity, in a parameter's type). */
    private Expr capacity(CapacityContext ctx) {
        return ctx.UNDERSCORE() != null ? null : expr(ctx.expression());
    }

    /** Whether a type has a `_` capacity or length anywhere in it. */
    private static boolean anyCapacity(TypeRef t) {
        return switch (t) {
            case StrRef s -> s.cap() == null;
            case ListRef l -> l.cap() == null || anyCapacity(l.elem());
            case ArrayRef a -> a.len() == null || anyCapacity(a.elem());
            case MapRef m -> m.cap() == null || anyCapacity(m.key()) || anyCapacity(m.value());
            case SetRef s -> s.cap() == null || anyCapacity(s.elem());
            case TupleRef tr -> tr.elems().stream().anyMatch(AstBuilder::anyCapacity);
            default -> false;
        };
    }

    // ------------------------------------------------------------------ statements

    private Block block(BlockContext ctx) {
        List<Stmt> stmts = new ArrayList<>();
        for (StatementContext s : ctx.statement()) stmts.add(stmt(s));
        return new Block(span(ctx.getStart()), stmts);
    }

    private Stmt stmt(StatementContext ctx) {
        Span sp = span(ctx);
        if (ctx.letStmt() != null) {
            LetStmtContext l = ctx.letStmt();
            boolean mutable = l.kind.getType() == ALangLexer.VAR;
            Block elseBlock = l.block() == null ? null : block(l.block());
            if (l.identifier() == null || elseBlock != null) {
                // `let x: T = e else { }` too, whose pattern is a name that always matches: the checker says so.
                Pattern p = l.identifier() != null ? new BindPat(span(l.identifier()), l.identifier().getText()) : pattern(l.pattern());
                return new Destructure(sp, mutable, p, l.type() == null ? null : type(l.type()), value(l.loopValue(), l.expression()),
                    elseBlock, false);
            }
            return new Let(sp, mutable, l.identifier().getText(), span(l.identifier()),
                l.type() == null ? null : type(l.type()), value(l.loopValue(), l.expression()));
        }
        if (ctx.assignStmt() != null) {
            AssignStmtContext a = ctx.assignStmt();
            BinOp op = switch (a.op.getType()) {
                case ALangLexer.PLUS_ASSIGN -> BinOp.ADD;
                case ALangLexer.MINUS_ASSIGN -> BinOp.SUB;
                case ALangLexer.STAR_ASSIGN -> BinOp.MUL;
                case ALangLexer.SLASH_ASSIGN -> BinOp.DIV;
                case ALangLexer.PERCENT_ASSIGN -> BinOp.MOD;
                default -> null;
            };
            return new Assign(sp, place(a.place()), op, value(a.loopValue(), a.expression()));
        }
        if (ctx.ifStmt() != null) {
            IfStmtContext i = ctx.ifStmt();
            List<ConditionContext> cs = i.condition();
            List<BlockContext> bs = i.block();
            Block elseBlock = bs.size() > cs.size() ? block(bs.get(bs.size() - 1)) : null;
            return ifChain(sp, cs, bs, 0, elseBlock);
        }
        if (ctx.whileStmt() != null) return whileLoop(sp, ctx.whileStmt());
        if (ctx.forStmt() != null) return forLoop(sp, ctx.forStmt());
        if (ctx.labeledStmt() != null) {
            LabeledStmtContext l = ctx.labeledStmt();
            labels.push(l.identifier().getText());
            Loop loop = l.whileStmt() != null ? whileLoop(sp, l.whileStmt()) : forLoop(sp, l.forStmt());
            labels.pop();
            loop.label = l.identifier().getText();
            loop.labelSpan = span(l.identifier());
            return loop;
        }
        if (ctx.matchStmt() != null) {
            MatchStmtContext m = ctx.matchStmt();
            List<Arm> arms = new ArrayList<>();
            for (MatchArmContext a : m.matchArm()) {
                arms.add(new Arm(pattern(a.pattern()), a.expression() == null ? null : expr(a.expression()), block(a.block())));
            }
            return new Match(sp, expr(m.expression()), arms);
        }
        if (ctx.breakStmt() != null) return breakStmt(sp, ctx.breakStmt());
        if (ctx.continueStmt() != null) {
            IdentifierContext l = ctx.continueStmt().identifier();
            return new Continue(sp, l == null ? null : l.getText());
        }
        if (ctx.returnStmt() != null) {
            ReturnStmtContext r = ctx.returnStmt();
            return new Return(sp, r.loopValue() == null && r.expression() == null ? null : value(r.loopValue(), r.expression()));
        }
        if (ctx.assertStmt() != null) {
            AssertStmtContext a = ctx.assertStmt();
            byte[] msg = a.STRING_LIT() == null ? null : literalBytes(a.STRING_LIT().getSymbol());
            return new Assert(sp, expr(a.expression()), msg);
        }
        Expr e = expr(ctx.exprStmt().expression());
        // assert_eq(a, b); is assert a == b;, whose failure shows both values.
        if (e instanceof Call c && c.name.equals("assert_eq") && c.args.size() == 2 && !c.hasMutArg()) {
            return new Assert(sp, new Binary(c.span, BinOp.EQ, c.args.get(0).expr(), c.args.get(1).expr()), null);
        }
        return new ExprStmt(sp, e);
    }

    /**
     * The conditions from {@code from} on of an if statement, and its else block: plain conditions make an if
     * statement, and `if let P = e { A } else { B }` is `match e { P => { A } _ => { B } }`, where B holds the rest of
     * the chain.
     */
    private Stmt ifChain(Span sp, List<ConditionContext> cs, List<BlockContext> bs, int from, Block elseBlock) {
        List<Expr> conds = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        int k = from;
        for (; k < cs.size() && cs.get(k).LET() == null; k++) {
            conds.add(expr(cs.get(k).expression()));
            blocks.add(block(bs.get(k)));
        }
        if (k == cs.size()) return new If(sp, conds, blocks, elseBlock);
        ConditionContext c = cs.get(k);
        Span at = span(c);
        Block then = block(bs.get(k));
        Block rest = k + 1 < cs.size()
            ? new Block(span(cs.get(k + 1)), List.of(ifChain(span(cs.get(k + 1)), cs, bs, k + 1, elseBlock)))
            : elseBlock != null ? elseBlock : new Block(at, List.of());
        Expr[] sg = scrutineeAndGuard(c.expression());
        Match m = new Match(conds.isEmpty() ? sp : at, sg[0],
            List.of(new Arm(pattern(c.pattern()), sg[1], then), new Arm(new WildPat(at), null, rest)), true);
        if (conds.isEmpty()) return m;
        return new If(sp, conds, blocks, new Block(at, List.of(m)));
    }

    /** The labels of the loops around the statement being built, innermost first: what a `break name` may leave. */
    private final Deque<String> labels = new ArrayDeque<>();

    /** The value of a let, assignment or return: a loop that gives a value, or an expression. */
    private Expr value(LoopValueContext lv, ExpressionContext e) {
        if (lv == null) return expr(e);
        Span sp = span(lv.whileStmt() != null ? lv.whileStmt() : lv.forStmt());
        Loop loop = lv.whileStmt() != null ? whileLoop(sp, lv.whileStmt()) : forLoop(sp, lv.forStmt());
        return new LoopExpr(span(lv), loop, expr(lv.expression()));
    }

    /**
     * `break;`, `break label;` or `break value;`. `break x;` names a label or a value, which the checker tells apart. A
     * name that labels no loop around it and is followed by more starts the value, which the parser cut after the name:
     * `break x - 1;` reads as the label x and the value -1, so the tokens from the name on are read again as one value.
     */
    private Break breakStmt(Span sp, BreakStmtContext b) {
        IdentifierContext id = b.identifier();
        if (id != null && b.expression() == null) {
            Break plain = new Break(sp, id.getText());
            plain.labelSpan = span(id);
            return plain;
        }
        if (id != null && !labels.contains(id.getText())) {
            List<Token> ts = new ArrayList<>();
            for (int i = id.getStart().getTokenIndex(); i < b.SEMI().getSymbol().getTokenIndex(); i++) {
                Token t = tokens.get(i);
                if (t.getChannel() == Token.DEFAULT_CHANNEL) ts.add(t);
            }
            ALangParser p = new ALangParser(new CommonTokenStream(new ListTokenSource(ts)));
            p.removeErrorListeners();
            p.setErrorHandler(new BailErrorStrategy());
            try {
                ExpressionContext e = p.expression();
                if (p.getCurrentToken().getType() == Token.EOF) return new Break(sp, null, expr(e));
            } catch (org.antlr.v4.runtime.misc.ParseCancellationException ignored) {
                // reported below
            }
            diags.error(Code.E0001, span(id), "`" + id.getText() + "` labels no loop around this break, and what follows it is no value",
                "write break; or break label; to leave a loop, or break value; to leave a loop that gives a value");
            return new Break(sp, null, null);
        }
        if (id != null) {
            diags.error(Code.E0001, span(id), "a break with a value leaves the innermost loop that gives a value, and names no label",
                "write break value;");
            return new Break(sp, null, null);
        }
        return new Break(sp, null, b.expression() == null ? null : expr(b.expression()));
    }

    private While whileLoop(Span sp, WhileStmtContext w) {
        if (w.pattern() == null) return new While(sp, null, expr(w.expression(0)), expr(w.expression(1)), block(w.block()));
        Expr[] sg = scrutineeAndGuard(w.expression(0));
        While loop = new While(sp, pattern(w.pattern()), sg[0], expr(w.expression(1)), block(w.block()));
        loop.guard = sg[1];
        return loop;
    }

    /**
     * The value that `if let P = e` or `while let P = e` matches against, and the condition of the names it binds:
     * in `if let P = a and c and d`, the pattern matches a, and the conditions after the first `and`, which may use
     * those names, must hold too. Parentheses match against a whole `and`: if let false = (a and b). The guard is null
     * without an `and`.
     */
    private Expr[] scrutineeAndGuard(ExpressionContext e) {
        if (!(e instanceof LogicExprContext le) || !(le.logic() instanceof AndChainContext a)) return new Expr[] {expr(e), null};
        List<NotExprContext> parts = a.notExpr();
        Expr guard = not(parts.get(1));
        Span gs = span(parts.get(1).getStart(), parts.get(parts.size() - 1).getStop());
        for (int i = 2; i < parts.size(); i++) guard = new Binary(gs, BinOp.AND, guard, not(parts.get(i)));
        return new Expr[] {not(parts.get(0)), guard};
    }

    /**
     * `for [i,] x in [rev] from[..to | ..=to [step k]] { }`. `for [i,] P in xs { body }`, with a pattern P, is
     * `for [i,] $x in xs { let P = $x; body }`: a hidden loop variable, which a pattern that always matches takes apart.
     */
    private For forLoop(Span sp, ForStmtContext f) {
        IdentifierContext index = f.index;
        IdentifierContext name = f.identifier().stream().filter(n -> n != index).findFirst().orElse(null);
        List<ExpressionContext> es = f.expression();
        Expr to = es.size() > 1 ? expr(es.get(1)) : null;
        Expr step = es.size() > 2 ? expr(es.get(2)) : null;
        boolean inclusive = f.range != null && f.range.getType() == ALangLexer.DOTDOTEQ;
        Block body = block(f.block());
        String loopName;
        Span loopSpan;
        if (name != null) {
            loopName = name.getText();
            loopSpan = span(name);
        } else {
            Pattern p = pattern(f.pattern());
            loopSpan = p.span;
            // `$` cannot start a name in the source, so the hidden variable clashes with none.
            loopName = "$item" + loopSpan.line() + "_" + loopSpan.col();
            List<Stmt> stmts = new ArrayList<>();
            stmts.add(new Destructure(p.span, false, p, null, new Name(p.span, loopName), null, true));
            stmts.addAll(body.stmts());
            body = new Block(body.span(), stmts);
        }
        For loop = new For(sp, loopName, loopSpan, index == null ? null : index.getText(), index == null ? null : span(index),
            expr(es.get(0)), to, inclusive, step, f.REV() != null, body);
        loop.inPlace = f.MUT() != null;
        return loop;
    }

    private Expr place(PlaceContext ctx) {
        Expr e = new Name(span(ctx.identifier()), ctx.identifier().getText());
        for (PlaceSuffixContext s : ctx.placeSuffix()) {
            Span sp = new Span(e.span.line(), e.span.col(),
                s.getStop().getStopIndex() - ctx.getStart().getStartIndex() + 1, file);
            if (s.identifier() != null) {
                e = new FieldAccess(sp, e, s.identifier().getText(), span(s.identifier()));
            } else if (s.expression() != null) {
                e = new Index(sp, e, expr(s.expression()));
            } else {
                e = tupleAccess(sp, e, s.getStop());
            }
        }
        return e;
    }

    private Pattern pattern(PatternContext ctx) {
        List<SinglePatternContext> alts = ctx.singlePattern();
        if (alts.size() == 1) return pattern(alts.get(0));
        List<Pattern> ps = new ArrayList<>();
        for (SinglePatternContext a : alts) ps.add(pattern(a));
        return new OrPat(span(ctx), ps);
    }

    private Pattern pattern(SinglePatternContext ctx) {
        Span sp = span(ctx);
        if (ctx instanceof VariantPatternContext v) {
            List<Pattern> args = null;
            if (v.LPAREN() != null) {
                args = new ArrayList<>();
                for (PatternContext p : v.pattern()) args.add(pattern(p));
            }
            int k = v.qual == null ? 0 : 1;
            VariantPat vp = new VariantPat(sp, v.identifier(k).getText(), v.identifier(k + 1).getText(), args);
            if (v.qual != null) vp.qualifier = v.qual.getText();
            return vp;
        }
        if (ctx instanceof StructPatternContext st) {
            List<String> names = new ArrayList<>();
            List<Span> spans = new ArrayList<>();
            List<Pattern> fields = new ArrayList<>();
            for (FieldPatternContext f : st.fieldPattern()) {
                names.add(f.identifier().getText());
                spans.add(span(f.identifier()));
                fields.add(pattern(f.pattern()));
            }
            int k = st.qual == null ? 0 : 1;
            StructPat p = new StructPat(sp, st.identifier(k).getText(), names, spans, fields);
            if (st.qual != null) p.qualifier = st.qual.getText();
            return p;
        }
        if (ctx instanceof TuplePatternContext t) {
            List<Pattern> elems = new ArrayList<>();
            for (PatternContext p : t.pattern()) elems.add(pattern(p));
            return new TuplePat(sp, elems);
        }
        if (ctx instanceof ParenPatternContext p) return pattern(p.pattern());
        if (ctx instanceof ListPatternContext l) {
            List<Pattern> prefix = new ArrayList<>();
            List<Pattern> suffix = new ArrayList<>();
            boolean rest = false;
            for (ListPatternElemContext e : l.listPatternElem()) {
                if (e.DOTDOT() == null) {
                    (rest ? suffix : prefix).add(pattern(e.pattern()));
                } else if (rest) {
                    diags.error(Code.E0308, span(e), "a list pattern has at most one `..`",
                        "name the elements at the front and back: [first, .., last]");
                } else {
                    rest = true;
                }
            }
            return new ListPat(sp, prefix, rest, suffix);
        }
        if (ctx instanceof RangePatternContext r) {
            RangePat rp = new RangePat(sp, rangeBound(r.rangeBound(0)), rangeBound(r.rangeBound(1)), r.op.getType() == ALangLexer.DOTDOTEQ);
            rp.codePoint = r.rangeBound().stream().anyMatch(b -> b.CHAR_LIT() != null && codePointLiteral(b.CHAR_LIT().getSymbol()));
            return rp;
        }
        if (ctx instanceof BindingPatternContext b) return new BindPat(sp, b.identifier().getText());
        if (ctx instanceof IntPatternContext i) {
            BigInteger val = parseInt(i.INT_LIT().getText());
            return new IntPat(sp, i.MINUS() != null ? val.negate() : val);
        }
        if (ctx instanceof CharPatternContext c) {
            IntPat ip = new IntPat(sp, BigInteger.valueOf(charValue(c.CHAR_LIT().getSymbol())));
            ip.codePoint = codePointLiteral(c.CHAR_LIT().getSymbol());
            return ip;
        }
        if (ctx instanceof StringPatternContext s) return new StrPat(sp, literalBytes(s.STRING_LIT().getSymbol()));
        if (ctx instanceof BoolPatternContext b) return new BoolPat(sp, b.TRUE() != null);
        return new WildPat(sp);
    }

    private BigInteger rangeBound(RangeBoundContext ctx) {
        if (ctx.CHAR_LIT() != null) return BigInteger.valueOf(charValue(ctx.CHAR_LIT().getSymbol()));
        BigInteger val = parseInt(ctx.INT_LIT().getText());
        return ctx.MINUS() != null ? val.negate() : val;
    }

    // ------------------------------------------------------------------ expressions

    Expr expr(ExpressionContext ctx) {
        Span sp = span(ctx);
        if (ctx instanceof MatchExprContext m) {
            List<ExprArm> arms = new ArrayList<>();
            for (MatchExprArmContext a : m.matchExprArm()) {
                List<ExpressionContext> es = a.expression();
                Expr guard = es.size() > 1 ? expr(es.get(0)) : null;
                arms.add(new ExprArm(pattern(a.pattern()), guard, expr(es.get(es.size() - 1))));
            }
            return new MatchExpr(sp, expr(m.expression()), arms);
        }
        if (ctx instanceof BitAndExprContext b) return chain(sp, BinOp.BAND, b.unary());
        if (ctx instanceof BitOrExprContext b) return chain(sp, BinOp.BOR, b.unary());
        if (ctx instanceof BitXorExprContext b) return chain(sp, BinOp.BXOR, b.unary());
        if (ctx instanceof IfExprContext i) {
            return new IfExpr(sp, expr(i.expression(0)), expr(i.expression(1)), expr(i.expression(2)));
        }
        if (ctx instanceof ShiftExprContext s) {
            BinOp op = s.op.getType() == ALangLexer.SHL ? BinOp.SHL : BinOp.SHR;
            return new Binary(sp, op, unary(s.unary(0)), unary(s.unary(1)));
        }
        return logic(((LogicExprContext) ctx).logic());
    }

    private Expr chain(Span sp, BinOp op, List<UnaryContext> operands) {
        Expr e = unary(operands.get(0));
        for (int i = 1; i < operands.size(); i++) e = new Binary(sp, op, e, unary(operands.get(i)));
        return e;
    }

    private Expr logic(LogicContext ctx) {
        Span sp = span(ctx);
        List<NotExprContext> parts;
        BinOp op;
        if (ctx instanceof AndChainContext a) {
            parts = a.notExpr();
            op = BinOp.AND;
        } else if (ctx instanceof OrChainContext o) {
            parts = o.notExpr();
            op = BinOp.OR;
        } else {
            return not(((SingleLogicContext) ctx).notExpr());
        }
        Expr e = not(parts.get(0));
        for (int i = 1; i < parts.size(); i++) e = new Binary(sp, op, e, not(parts.get(i)));
        return e;
    }

    private Expr not(NotExprContext ctx) {
        if (ctx.NOT() != null) return new Unary(span(ctx), UnOp.NOT, not(ctx.notExpr()));
        return comparison(ctx.comparison());
    }

    private Expr comparison(ComparisonContext ctx) {
        Expr left = additive(ctx.additive(0));
        if (ctx.op == null) return left;
        BinOp op = switch (ctx.op.getType()) {
            case ALangLexer.EQ -> BinOp.EQ;
            case ALangLexer.NEQ -> BinOp.NE;
            case ALangLexer.LT -> BinOp.LT;
            case ALangLexer.LE -> BinOp.LE;
            case ALangLexer.GT -> BinOp.GT;
            default -> BinOp.GE;
        };
        return new Binary(span(ctx), op, left, additive(ctx.additive(1)));
    }

    private Expr additive(AdditiveContext ctx) {
        // Fetch the operands once: ANTLR's per-index getter scans all of them, so a long chain
        // would take quadratic time.
        List<MultiplicativeContext> operands = ctx.multiplicative();
        Expr e = multiplicative(operands.get(0));
        for (int i = 1; i < operands.size(); i++) {
            BinOp op = ctx.ops.get(i - 1).getType() == ALangLexer.PLUS ? BinOp.ADD : BinOp.SUB;
            MultiplicativeContext next = operands.get(i);
            e = new Binary(spanTo(e.span, next), op, e, multiplicative(next));
        }
        return e;
    }

    private Expr multiplicative(MultiplicativeContext ctx) {
        List<UnaryContext> operands = ctx.unary();
        Expr e = unary(operands.get(0));
        for (int i = 1; i < operands.size(); i++) {
            BinOp op = switch (ctx.ops.get(i - 1).getType()) {
                case ALangLexer.STAR -> BinOp.MUL;
                case ALangLexer.SLASH -> BinOp.DIV;
                default -> BinOp.MOD;
            };
            UnaryContext next = operands.get(i);
            e = new Binary(spanTo(e.span, next), op, e, unary(next));
        }
        return e;
    }

    private static Span spanTo(Span start, ParserRuleContext end) {
        Token stop = end.getStop();
        if (stop.getLine() != start.line()) return start;
        int len = stop.getCharPositionInLine() + Source.columns(stop.getText()) - (start.col() - 1);
        return new Span(start.line(), start.col(), Math.max(1, len), start.file());
    }

    private Expr unary(UnaryContext ctx) {
        if (ctx.op != null) {
            UnOp op = ctx.op.getType() == ALangLexer.MINUS ? UnOp.NEG : UnOp.BNOT;
            Expr operand = unary(ctx.unary());
            // `-128` is one literal, so that it can be checked against the range of i8.
            if (op == UnOp.NEG && ctx.unary().postfix() != null && ctx.unary().postfix().postfixOp().isEmpty()
                && ctx.unary().postfix().primary() instanceof LiteralExprContext) {
                if (operand instanceof IntLit i && !i.isChar) return new IntLit(span(ctx), i.value.negate(), false);
                if (operand instanceof FloatLit f) return new FloatLit(span(ctx), -f.value, -f.f32Value);
            }
            return new Unary(span(ctx), op, operand);
        }
        PostfixContext p = ctx.postfix();
        Expr e = primary(p.primary());
        for (PostfixOpContext op : p.postfixOp()) {
            Span sp = spanTo(e.span, op);
            if (op instanceof FieldAccessContext f) {
                e = new FieldAccess(sp, e, f.identifier().getText(), span(f.identifier()));
            } else if (op instanceof TupleAccessContext t) {
                e = tupleAccess(sp, e, t.getStop());
            } else if (op instanceof TryAccessContext) {
                e = new Try(sp, e);
            } else if (op instanceof SliceAccessContext sl) {
                e = new SliceExpr(sp, e, sl.from == null ? null : expr(sl.from), sl.to == null ? null : expr(sl.to));
            } else {
                e = new Index(sp, e, expr(((IndexAccessContext) op).expression()));
            }
        }
        return e;
    }

    /**
     * t.0 (an INT_LIT after the dot), or t.0.1, which the lexer reads as t, a dot and the FLOAT_LIT 0.1: two
     * element accesses. Anything else after the dot, such as 1e5, is not an element.
     */
    private Expr tupleAccess(Span sp, Expr target, Token t) {
        String text = t.getText();
        if (!text.matches("[0-9]+(\\.[0-9]+)?")) {
            diags.error(Code.E0001, span(t), "`" + text + "` is not an element of a tuple",
                "elements are numbered from 0: t.0, t.1, and t.0.1 for an element of an element");
            return target;
        }
        Span first = new Span(sp.line(), sp.col(), Math.max(1, sp.length() - (text.contains(".") ? text.length() - text.indexOf('.') : 0)), file);
        String[] parts = text.split("\\.");
        Expr e = new FieldAccess(parts.length == 1 ? sp : first, target, parts[0], span(t));
        if (parts.length == 2) e = new FieldAccess(sp, e, parts[1], span(t));
        return e;
    }

    private Expr primary(PrimaryContext ctx) {
        Span sp = span(ctx);
        if (ctx instanceof LiteralExprContext l) return literal(l.literal());
        if (ctx instanceof ConversionExprContext c) {
            return new Convert(sp, PrimKind.byName(c.primitiveType().getText()), expr(c.expression()));
        }
        if (ctx instanceof StrResizeExprContext s) {
            Span ts = span(s.STR().getSymbol(), s.RBRACK().getSymbol());
            return new Resize(sp, new StrRef(ts, expr(s.expression(0))), expr(s.expression(1)));
        }
        if (ctx instanceof ListResizeExprContext l) {
            Span ts = span(l.LIST().getSymbol(), l.RBRACK().getSymbol());
            return new Resize(sp, new ListRef(ts, type(l.type()), expr(l.expression(0))), expr(l.expression(1)));
        }
        if (ctx instanceof StructExprContext s) return structLit(sp, null, s.identifier().getText(), s.structInit());
        if (ctx instanceof QualifiedStructExprContext s) {
            return structLit(sp, s.identifier(0).getText(), s.identifier(1).getText(), s.structInit());
        }
        if (ctx instanceof QualifiedVariantExprContext v) {
            VariantCall vc = new VariantCall(sp, v.identifier(1).getText(), v.identifier(2).getText(), args(v.argList()));
            vc.qualifier = v.identifier(0).getText();
            return vc;
        }
        if (ctx instanceof VariantExprContext v) {
            return new VariantCall(sp, v.identifier(0).getText(), v.identifier(1).getText(), args(v.argList()));
        }
        if (ctx instanceof CallExprContext c) {
            return new Call(sp, c.identifier().getText(), span(c.identifier()), args(c.argList()));
        }
        if (ctx instanceof NameExprContext n) return new Name(sp, n.identifier().getText());
        if (ctx instanceof ParenExprContext p) return expr(p.expression());
        if (ctx instanceof TupleExprContext t) {
            List<Expr> elems = new ArrayList<>();
            for (ExpressionContext e : t.expression()) elems.add(expr(e));
            return new TupleLit(sp, elems);
        }
        if (ctx instanceof RepeatExprContext r) return new Repeat(sp, expr(r.expression(0)), expr(r.expression(1)));
        ArrayExprContext a = (ArrayExprContext) ctx;
        List<Expr> elems = new ArrayList<>();
        for (ExpressionContext e : a.expression()) elems.add(expr(e));
        return new ArrayLit(sp, elems);
    }

    /**
     * `P(x: 1, ..base)`: the fields it names, and the value the others come from. A second `..` is kept as the base
     * expression of a field named "..", which the checker reports.
     */
    private StructLit structLit(Span sp, String qualifier, String name, List<StructInitContext> inits) {
        List<FieldInit> fields = new ArrayList<>();
        Expr base = null;
        for (StructInitContext i : inits) {
            FieldInitContext f = i.fieldInit();
            if (f != null) {
                fields.add(new FieldInit(span(f.identifier()), f.identifier().getText(), expr(f.expression())));
            } else if (base == null) {
                base = expr(i.expression());
            } else {
                fields.add(new FieldInit(span(i), "..", expr(i.expression())));
            }
        }
        StructLit lit = new StructLit(sp, name, fields);
        lit.qualifier = qualifier;
        lit.base = base;
        return lit;
    }

    private List<Arg> args(ArgListContext ctx) {
        List<Arg> out = new ArrayList<>();
        if (ctx == null) return out;
        for (ArgContext a : ctx.arg()) {
            if (a instanceof MutArgContext m) {
                out.add(new Arg(span(a), true, place(m.place())));
            } else {
                out.add(new Arg(span(a), false, expr(((ValueArgContext) a).expression())));
            }
        }
        return out;
    }

    private Expr literal(LiteralContext ctx) {
        Token t = ctx.getStart();
        Span sp = span(t);
        return switch (t.getType()) {
            case ALangLexer.INT_LIT -> new IntLit(sp, parseInt(t.getText()), false);
            case ALangLexer.FLOAT_LIT -> {
                String text = t.getText().replace("_", "");
                yield new FloatLit(sp, Double.parseDouble(text), Float.parseFloat(text));
            }
            case ALangLexer.STRING_LIT -> new StrLit(sp, literalBytes(t));
            case ALangLexer.CHAR_LIT -> {
                IntLit lit = new IntLit(sp, BigInteger.valueOf(charValue(t)), true);
                lit.codePoint = codePointLiteral(t);
                yield lit;
            }
            case ALangLexer.TRUE -> new BoolLit(sp, true);
            default -> new BoolLit(sp, false);
        };
    }

    // ------------------------------------------------------------------ literal decoding

    static BigInteger parseInt(String text) {
        String s = text.replace("_", "");
        if (s.startsWith("0x")) return new BigInteger(s.substring(2), 16);
        if (s.startsWith("0b")) return new BigInteger(s.substring(2), 2);
        if (s.startsWith("0o")) return new BigInteger(s.substring(2), 8);
        return new BigInteger(s);
    }

    /**
     * Decodes a string or char literal token (including quotes) into UTF-8 bytes: "..." and '...' with escapes,
     * r"..." without, and the multi-line forms """...""" and r"""...""" (see {@link #textBlock}).
     */
    static byte[] unescape(Token t) {
        return decode(t.getText(), null, null);
    }

    /** As {@link #unescape(Token)}, reporting a bad \\u{...} escape or text block to {@code diags} (when not null). */
    private byte[] literalBytes(Token t) {
        return decode(t.getText(), diags, span(t));
    }

    private static byte[] decode(String s, Diagnostics diags, Span at) {
        if (s.startsWith("r\"\"\"")) return textBlock(s.substring(4, s.length() - 3), false, diags, at);
        if (s.startsWith("\"\"\"") && s.length() >= 6) return textBlock(s.substring(3, s.length() - 3), true, diags, at);
        if (s.startsWith("r\"")) return s.substring(2, s.length() - 1).getBytes(StandardCharsets.UTF_8);
        return escapes(s.substring(1, s.length() - 1), diags, at);
    }

    /**
     * The text of a multi-line literal, {@code body} being what comes between its quotes: it must start with a line
     * break, which is not part of the text; the indentation that every line which is not blank shares, and the line of
     * the closing quotes if they stand on a line of their own, is removed from each; and a line break escaped with a
     * backslash joins two lines (only where escapes are read).
     */
    private static byte[] textBlock(String body, boolean escaped, Diagnostics diags, Span at) {
        String b = body.replace("\r\n", "\n").replace('\r', '\n');
        if (!b.startsWith("\n")) {
            if (diags != null) {
                diags.error(Code.E0202, at, "a multi-line string starts with a line break after its opening quotes",
                    "write \"\"\" at the end of a line and the text from the next one, or use \"...\" for one line");
            }
            return new byte[0];
        }
        List<String> lines = new ArrayList<>(Arrays.asList(b.substring(1).split("\n", -1)));
        String last = lines.get(lines.size() - 1);
        boolean closingOwnLine = last.isBlank();
        int indent = Integer.MAX_VALUE;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isBlank() && !(closingOwnLine && i == lines.size() - 1)) continue;
            int n = 0;
            while (n < l.length() && (l.charAt(n) == ' ' || l.charAt(n) == '\t')) n++;
            indent = Math.min(indent, n);
        }
        if (indent == Integer.MAX_VALUE) indent = 0;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (i == lines.size() - 1 && closingOwnLine) break;
            text.append(l.length() >= indent ? l.substring(indent) : "");
            if (i < lines.size() - 1) text.append('\n');
        }
        String t = text.toString();
        if (!escaped) return t.getBytes(StandardCharsets.UTF_8);
        return escapes(t.replace("\\\n", ""), diags, at);
    }

    /** The UTF-8 bytes of text with the escapes of a "..." literal in it. */
    private static byte[] escapes(String body, Diagnostics diags, Span at) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                plain.append(c);
                continue;
            }
            out.writeBytes(plain.toString().getBytes(StandardCharsets.UTF_8));
            plain.setLength(0);
            char e = body.charAt(++i);
            switch (e) {
                case 'n' -> out.write('\n');
                case 't' -> out.write('\t');
                case 'r' -> out.write('\r');
                case '0' -> out.write(0);
                case '\\' -> out.write('\\');
                case '"' -> out.write('"');
                case '\'' -> out.write('\'');
                case 'x' -> {
                    out.write(Integer.parseInt(body.substring(i + 1, i + 3), 16));
                    i += 2;
                }
                case 'u' -> {
                    int close = body.indexOf('}', i);
                    String hex = body.substring(i + 2, close);
                    int cp = codePoint(hex);
                    if (cp < 0) {
                        if (diags != null) {
                            diags.error(Code.E0202, at, "`\\u{" + hex + "}` is not a Unicode character",
                                "a \\u{...} escape holds the hex digits of a code point from 0 to 10FFFF, not a surrogate (D800 to DFFF)");
                        }
                    } else {
                        out.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
                    }
                    i = close;
                }
                default -> throw new IllegalStateException("lexer accepted bad escape");
            }
        }
        out.writeBytes(plain.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** The code point that the hex digits of a \\u{...} escape give, or -1 for none (too large, or a surrogate). */
    private static int codePoint(String hex) {
        if (hex.length() > 6) return -1;
        int cp = Integer.parseInt(hex, 16);
        return cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF) ? -1 : cp;
    }

    /**
     * The value of a character literal: its byte, or for one character beyond ASCII ('é', '\\u{20AC}') its code
     * point, which {@link #codePointLiteral} tells apart.
     */
    private int charValue(Token t) {
        String text = t.getText();
        String body = text.substring(1, text.length() - 1);
        if (body.startsWith("\\u{")) {
            byte[] b = literalBytes(t);
            if (b.length == 0) return 0;
            return new String(b, StandardCharsets.UTF_8).codePointAt(0);
        }
        if (!body.startsWith("\\") && body.codePointCount(0, body.length()) == 1 && body.codePointAt(0) > 0x7F) {
            return body.codePointAt(0);
        }
        byte[] b = literalBytes(t);
        if (b.length != 1) {
            // 'abc' is the text "abc", in the quotes text takes; one with a `"` in it would need escapes.
            List<Edit> fix = body.contains("\"") ? List.of() : List.of(new Edit(span(t), "\"" + body + "\""));
            diags.fixable(Code.E0202, span(t), "character literal " + text + " is not a single character",
                "character literals hold one character; use a string \"...\" for other text", fix);
            return 0;
        }
        return b[0] & 0xFF;
    }

    /** Whether a character literal is one beyond ASCII, whose value is a code point rather than a byte. */
    static boolean codePointLiteral(Token t) {
        String body = t.getText().substring(1, t.getText().length() - 1);
        if (body.startsWith("\\u{")) {
            String hex = body.substring(3, body.length() - 1);
            int cp = codePoint(hex);
            return cp > 0x7F;
        }
        return !body.startsWith("\\") && body.codePointCount(0, body.length()) == 1 && body.codePointAt(0) > 0x7F;
    }

    static Ast.Program build(Diagnostics diags, Source file, BufferedTokenStream tokens, ProgramContext ctx, boolean imported) {
        return new AstBuilder(diags, file, tokens, imported).program(ctx);
    }
}
