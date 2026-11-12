package alang.parse;

import alang.ast.Ast;
import alang.ast.Ast.*;
import alang.diag.Code;
import alang.diag.Diagnostics;
import alang.diag.Source;
import alang.diag.Span;
import alang.parser.ALangLexer;
import alang.parser.ALangParser;
import alang.parser.ALangParser.*;
import alang.types.PrimKind;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

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
            imports.add(new Import(span(path), new String(unescape(path), StandardCharsets.UTF_8)));
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
                fields.add(field);
            }
            return new StructDecl(span(s.identifier()), s.identifier().getText(), fields);
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
            return new EnumDecl(span(e.identifier()), e.identifier().getText(), variants);
        }
        if (ctx.functionDecl() != null) {
            return function(ctx.functionDecl());
        }
        TestDeclContext t = ctx.testDecl();
        String name = new String(unescape(t.STRING_LIT().getSymbol()), StandardCharsets.UTF_8);
        return new TestDecl(span(t.STRING_LIT().getSymbol()), name, block(t.block()));
    }

    private FnDecl function(FunctionDeclContext f) {
        List<Param> params = new ArrayList<>();
        if (f.paramList() != null) {
            for (ParamContext p : f.paramList().param()) {
                params.add(new Param(span(p.identifier()), p.identifier().getText(), p.MUT() != null, type(p.type()),
                    docComment(p.getStart())));
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
        FnDecl fn = new FnDecl(span(f), span(f.identifier()), f.identifier().getText(), f.TOOL() != null && !imported, params,
            ret, requires, ensures, requiresText, ensuresText, block(f.block()));
        if (params.stream().anyMatch(p -> anyCapacity(p.typeRef()))) {
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
        if (ctx instanceof StrTypeRefContext s) {
            return new StrRef(sp, capacity(s.capacity()));
        }
        if (ctx instanceof ListTypeRefContext l) {
            return new ListRef(sp, type(l.type()), capacity(l.capacity()));
        }
        if (ctx instanceof ArrayTypeRefContext a) {
            return new ArrayRef(sp, type(a.type()), capacity(a.capacity()));
        }
        if (ctx instanceof MapTypeRefContext m) {
            return new MapRef(sp, type(m.type(0)), type(m.type(1)), capacity(m.capacity()));
        }
        if (ctx instanceof SetTypeRefContext s) {
            return new SetRef(sp, type(s.type()), capacity(s.capacity()));
        }
        if (ctx instanceof TupleTypeRefContext t) {
            List<TypeRef> elems = new ArrayList<>();
            for (TypeContext e : t.type()) elems.add(type(e));
            return new TupleRef(sp, elems);
        }
        return new NamedRef(sp, ((NamedTypeRefContext) ctx).identifier().getText());
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
            if (l.identifier() == null) {
                List<String> names = new ArrayList<>();
                List<Span> spans = new ArrayList<>();
                for (BindingContext b : l.binding()) {
                    names.add(b.UNDERSCORE() != null ? null : b.identifier().getText());
                    spans.add(span(b));
                }
                return new Destructure(sp, mutable, names, spans, type(l.type()), expr(l.expression()));
            }
            return new Let(sp, mutable, l.identifier().getText(), span(l.identifier()), type(l.type()), expr(l.expression()));
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
            return new Assign(sp, place(a.place()), op, expr(a.expression()));
        }
        if (ctx.ifStmt() != null) {
            IfStmtContext i = ctx.ifStmt();
            List<Expr> conds = new ArrayList<>();
            List<Block> blocks = new ArrayList<>();
            for (ExpressionContext e : i.expression()) conds.add(expr(e));
            List<BlockContext> bs = i.block();
            for (int k = 0; k < conds.size(); k++) blocks.add(block(bs.get(k)));
            Block elseBlock = bs.size() > conds.size() ? block(bs.get(bs.size() - 1)) : null;
            return new If(sp, conds, blocks, elseBlock);
        }
        if (ctx.whileStmt() != null) return whileLoop(sp, ctx.whileStmt());
        if (ctx.forStmt() != null) return forLoop(sp, ctx.forStmt());
        if (ctx.labeledStmt() != null) {
            LabeledStmtContext l = ctx.labeledStmt();
            Loop loop = l.whileStmt() != null ? whileLoop(sp, l.whileStmt()) : forLoop(sp, l.forStmt());
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
        if (ctx.breakStmt() != null) {
            IdentifierContext l = ctx.breakStmt().identifier();
            return new Break(sp, l == null ? null : l.getText());
        }
        if (ctx.continueStmt() != null) {
            IdentifierContext l = ctx.continueStmt().identifier();
            return new Continue(sp, l == null ? null : l.getText());
        }
        if (ctx.returnStmt() != null) {
            ExpressionContext e = ctx.returnStmt().expression();
            return new Return(sp, e == null ? null : expr(e));
        }
        if (ctx.assertStmt() != null) {
            AssertStmtContext a = ctx.assertStmt();
            byte[] msg = a.STRING_LIT() == null ? null : unescape(a.STRING_LIT().getSymbol());
            return new Assert(sp, expr(a.expression()), msg);
        }
        return new ExprStmt(sp, expr(ctx.exprStmt().expression()));
    }

    private While whileLoop(Span sp, WhileStmtContext w) {
        return new While(sp, expr(w.expression(0)), expr(w.expression(1)), block(w.block()));
    }

    /** `for [i,] x in [rev] from[..to | ..=to [step k]] { }`. */
    private For forLoop(Span sp, ForStmtContext f) {
        List<IdentifierContext> names = f.identifier();
        IdentifierContext name = names.get(names.size() - 1);
        IdentifierContext index = names.size() > 1 ? names.get(0) : null;
        List<ExpressionContext> es = f.expression();
        Expr to = es.size() > 1 ? expr(es.get(1)) : null;
        Expr step = es.size() > 2 ? expr(es.get(2)) : null;
        boolean inclusive = f.range != null && f.range.getType() == ALangLexer.DOTDOTEQ;
        return new For(sp, name.getText(), span(name), index == null ? null : index.getText(), index == null ? null : span(index),
            expr(es.get(0)), to, inclusive, step, f.REV() != null, block(f.block()));
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
        Span sp = span(ctx);
        if (ctx instanceof VariantPatternContext v) {
            List<String> names = null;
            List<Span> spans = new ArrayList<>();
            if (v.LPAREN() != null) {
                names = new ArrayList<>();
                for (BindingContext b : v.binding()) {
                    names.add(b.UNDERSCORE() != null ? null : b.identifier().getText());
                    spans.add(span(b));
                }
            }
            return new VariantPat(sp, v.identifier(0).getText(), v.identifier(1).getText(), names, spans);
        }
        if (ctx instanceof IntPatternContext i) {
            BigInteger val = parseInt(i.INT_LIT().getText());
            return new IntPat(sp, i.MINUS() != null ? val.negate() : val);
        }
        if (ctx instanceof CharPatternContext c) {
            return new IntPat(sp, BigInteger.valueOf(charValue(c.CHAR_LIT().getSymbol())));
        }
        if (ctx instanceof StringPatternContext s) {
            return new StrPat(sp, unescape(s.STRING_LIT().getSymbol()));
        }
        if (ctx instanceof BoolPatternContext b) {
            return new BoolPat(sp, b.TRUE() != null);
        }
        return new WildPat(sp);
    }

    // ------------------------------------------------------------------ expressions

    Expr expr(ExpressionContext ctx) {
        Span sp = span(ctx);
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
        for (int i = 1; i < operands.size(); i++) {
            e = new Binary(sp, op, e, unary(operands.get(i)));
        }
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
        if (ctx instanceof StructExprContext s) {
            List<FieldInit> inits = new ArrayList<>();
            for (FieldInitContext f : s.fieldInit()) {
                inits.add(new FieldInit(span(f.identifier()), f.identifier().getText(), expr(f.expression())));
            }
            return new StructLit(sp, s.identifier().getText(), inits);
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
            case ALangLexer.STRING_LIT -> new StrLit(sp, unescape(t));
            case ALangLexer.CHAR_LIT -> new IntLit(sp, BigInteger.valueOf(charValue(t)), true);
            case ALangLexer.TRUE -> new BoolLit(sp, true);
            default -> new BoolLit(sp, false);
        };
    }

    // ------------------------------------------------------------------ literal decoding

    static BigInteger parseInt(String text) {
        String s = text.replace("_", "");
        if (s.startsWith("0x")) return new BigInteger(s.substring(2), 16);
        if (s.startsWith("0b")) return new BigInteger(s.substring(2), 2);
        return new BigInteger(s);
    }

    /** Decodes a string or char literal token (including quotes) into UTF-8 bytes. */
    static byte[] unescape(Token t) {
        String s = t.getText();
        String body = s.substring(1, s.length() - 1);
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
                default -> throw new IllegalStateException("lexer accepted bad escape");
            }
        }
        out.writeBytes(plain.toString().getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private int charValue(Token t) {
        byte[] b = unescape(t);
        if (b.length != 1) {
            diags.error(Code.E0202, span(t), "character literal " + t.getText() + " is not a single byte",
                "character literals hold one ASCII byte; use a string \"...\" for other text");
            return 0;
        }
        return b[0] & 0xFF;
    }

    static Ast.Program build(Diagnostics diags, Source file, BufferedTokenStream tokens, ProgramContext ctx, boolean imported) {
        return new AstBuilder(diags, file, tokens, imported).program(ctx);
    }
}
