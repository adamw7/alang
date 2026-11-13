package alang.cli;

import alang.ast.Ast.ArrayLit;
import alang.ast.Ast.BinOp;
import alang.ast.Ast.Binary;
import alang.ast.Ast.BoolLit;
import alang.ast.Ast.Call;
import alang.ast.Ast.ConstDecl;
import alang.ast.Ast.Convert;
import alang.ast.Ast.Expr;
import alang.ast.Ast.FieldAccess;
import alang.ast.Ast.FloatLit;
import alang.ast.Ast.FnDecl;
import alang.ast.Ast.IfExpr;
import alang.ast.Ast.Index;
import alang.ast.Ast.IntLit;
import alang.ast.Ast.LocalVar;
import alang.ast.Ast.MatchExpr;
import alang.ast.Ast.Name;
import alang.ast.Ast.Repeat;
import alang.ast.Ast.Resize;
import alang.ast.Ast.SliceExpr;
import alang.ast.Ast.StrLit;
import alang.ast.Ast.StructLit;
import alang.ast.Ast.TupleLit;
import alang.ast.Ast.Unary;
import alang.ast.Ast.VariantCall;
import alang.ast.Builtin;
import alang.interp.Interpreter;
import alang.interp.JsonValues;
import alang.interp.Trap;
import alang.types.Type;
import alang.util.Json;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a model is told about a `tool fn`: its description, which lists the contract, and its input
 * schema, which also carries the parts of the contract JSON Schema can state. A model that reads
 * `"minimum": 1` does not have to fail a call to learn that `people` must be positive.
 */
final class ToolDefinitions {
    private ToolDefinitions() {}

    private static final Set<BinOp> COMPARISONS = EnumSet.of(BinOp.LT, BinOp.LE, BinOp.GT, BinOp.GE, BinOp.EQ);

    /** The doc comment, a line per `requires` clause, the return type, and a line per `ensures` clause. */
    static String description(FnDecl f) {
        StringBuilder sb = new StringBuilder(f.doc.isEmpty() ? "Calls the ALang function " + f.name + "." : f.doc);
        for (String clause : f.requiresText) sb.append("\nRequires: ").append(clause);
        if (!(f.ret instanceof Type.Void)) sb.append("\nReturns ").append(f.ret).append('.');
        for (String clause : f.ensuresText) sb.append("\nEnsures: ").append(clause);
        return sb.toString();
    }

    /**
     * An object with one property per parameter, each described by its doc comment and narrowed by the
     * `requires` clauses that compare it, a field of it, or its length with a constant.
     */
    static Map<String, Object> inputSchema(FnDecl f) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (int i = 0; i < f.paramVars.size(); i++) {
            LocalVar p = f.paramVars.get(i);
            Map<String, Object> schema = new LinkedHashMap<>(JsonValues.describe(JsonValues.schema(p.type), f.params.get(i).doc()));
            ConstDecl d = f.defaults.get(i);
            if (d == null) {
                required.add(p.name);
            } else {
                // Optional: a call that leaves it out gets the default. One JSON cannot write (a NaN) is not shown.
                try {
                    schema.put("default", JsonValues.toJson(d.value, p.type));
                } catch (JsonValues.EncodeException notJson) {
                    // left out
                }
            }
            props.put(p.name, schema);
        }
        for (Expr clause : f.requires) constrain(props, f, clause);
        return Json.obj("type", "object", "properties", props, "required", required, "additionalProperties", false);
    }

    /**
     * True if {@code e} needs no frame: literals, constants and the pure builtins applied to them, so
     * {@link Interpreter#evalConst} can compute it. Anything naming a local or calling a function is not.
     */
    static boolean isConstant(Expr e) {
        return switch (e) {
            case IntLit x -> true;
            case FloatLit x -> true;
            case BoolLit x -> true;
            case StrLit x -> true;
            case Name n -> n.local == null && n.constant != null;
            case Unary u -> isConstant(u.operand);
            case Binary b -> isConstant(b.left) && isConstant(b.right);
            case Convert c -> isConstant(c.arg);
            case Resize z -> isConstant(z.arg);
            case FieldAccess fa -> fa.constant != null || fa.variant != null || isConstant(fa.target);
            case Index ix -> isConstant(ix.target) && isConstant(ix.index);
            case Call c -> c.fn == null && c.builtin != null && c.builtin.pure
                && c.args.stream().allMatch(a -> !a.mut() && isConstant(a.expr()));
            case VariantCall vc -> vc.asCall == null && vc.args.stream().allMatch(a -> isConstant(a.expr()));
            case StructLit s -> s.fields.stream().allMatch(fi -> isConstant(fi.value()));
            case ArrayLit a -> a.elems.stream().allMatch(ToolDefinitions::isConstant);
            case Repeat r -> isConstant(r.value) && isConstant(r.count);
            case IfExpr i -> isConstant(i.cond) && isConstant(i.then) && isConstant(i.otherwise);
            case MatchExpr m -> false;
            case TupleLit t -> t.elems.stream().allMatch(ToolDefinitions::isConstant);
            case SliceExpr s -> isConstant(s.target) && (s.from == null || isConstant(s.from)) && (s.to == null || isConstant(s.to));
        };
    }

    // ------------------------------------------------------------------ contracts

    /**
     * Narrows the schemas in {@code props} by one `requires` clause, or by each side of an `and`. Only a
     * comparison of a parameter, a field of one, or the length of either with a constant says something
     * JSON Schema can repeat; the description lists every clause anyway. A keyword is only ever made
     * stricter, and never stricter than the clause: the schema may let through a call that the contract
     * then rejects (R0006), but never refuses one the contract accepts.
     */
    private static void constrain(Map<String, Object> props, FnDecl f, Expr clause) {
        if (!(clause instanceof Binary b)) return;
        if (b.op == BinOp.AND) {
            constrain(props, f, b.left);
            constrain(props, f, b.right);
            return;
        }
        if (!COMPARISONS.contains(b.op)) return;
        boolean leftConstant = isConstant(b.left);
        boolean rightConstant = isConstant(b.right);
        if (leftConstant == rightConstant) return;
        Expr subject = rightConstant ? b.left : b.right;
        Expr bound = rightConstant ? b.right : b.left;
        BinOp op = rightConstant ? b.op : flip(b.op);
        boolean length = subject instanceof Call c && c.builtin == Builtin.LEN;
        Expr path = length ? ((Call) subject).args.get(0).expr() : subject;
        Map<String, Object> node = node(props, f, path);
        if (node == null) return;
        Object value;
        try {
            value = Interpreter.evalConst(bound);
        } catch (Trap overflowOrBadConversion) {
            return;  // the clause traps on every call; the schema need not say so
        }
        if (length) {
            lengthBound(node, path.type, op, BigInteger.valueOf((Long) value));
        } else if (subject.type instanceof Type.Prim p && p.kind().isInt()) {
            integerBound(node, op, (BigInteger) JsonValues.toJson(value, subject.type));
        } else if (subject.type instanceof Type.Prim p && p.kind().isFloat && Double.isFinite((Double) value)) {
            floatBound(node, op, JsonValues.toJson(value, subject.type));
        }
    }

    /** The comparison with its operands swapped: `0 < x` is `x > 0`. */
    private static BinOp flip(BinOp op) {
        return switch (op) {
            case LT -> BinOp.GT;
            case LE -> BinOp.GE;
            case GT -> BinOp.LT;
            case GE -> BinOp.LE;
            default -> op;
        };
    }

    /** The schema of a parameter, or of a field reached from one through `.field`s; null for anything else. */
    private static Map<String, Object> node(Map<String, Object> props, FnDecl f, Expr e) {
        if (e instanceof Name n) {
            return f.paramVars.contains(n.local) ? map(props.get(n.name)) : null;
        }
        if (e instanceof FieldAccess fa && fa.variant == null) {
            Map<String, Object> outer = node(props, f, fa.target);
            if (outer == null) return null;
            if (fa.target.type instanceof Type.TupleT) {
                // A tuple's element: the schema of its position in prefixItems.
                return outer.get("prefixItems") instanceof List<?> items ? map(items.get(fa.fieldIndex)) : null;
            }
            return outer.get("properties") == null ? null : map(map(outer.get("properties")).get(fa.name));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object schema) {
        return (Map<String, Object>) schema;
    }

    private static void integerBound(Map<String, Object> node, BinOp op, BigInteger c) {
        switch (op) {
            case GT -> raise(node, "minimum", c.add(BigInteger.ONE));
            case GE -> raise(node, "minimum", c);
            case LT -> lower(node, "maximum", c.subtract(BigInteger.ONE));
            case LE -> lower(node, "maximum", c);
            default -> {
                raise(node, "minimum", c);
                lower(node, "maximum", c);
            }
        }
    }

    /** {@code c} is the bound as JSON: the shortest decimal that reads back as the constant. */
    private static void floatBound(Map<String, Object> node, BinOp op, Object c) {
        switch (op) {
            case GT -> raise(node, "exclusiveMinimum", c);
            case GE -> raise(node, "minimum", c);
            case LT -> lower(node, "exclusiveMaximum", c);
            case LE -> lower(node, "maximum", c);
            default -> {
                raise(node, "minimum", c);
                lower(node, "maximum", c);
            }
        }
    }

    /**
     * A bound on `len(...)`. For a list it is the item count. For a str it counts bytes, while JSON
     * Schema counts characters, which are 1 to 4 bytes: at most n bytes means at most n characters, but
     * at least n bytes only means at least one character.
     */
    private static void lengthBound(Map<String, Object> node, Type t, BinOp op, BigInteger c) {
        BigInteger atLeast = switch (op) {
            case GT -> c.add(BigInteger.ONE);
            case GE, EQ -> c;
            default -> null;
        };
        BigInteger atMost = switch (op) {
            case LT -> c.subtract(BigInteger.ONE);
            case LE, EQ -> c;
            default -> null;
        };
        String min = t instanceof Type.Str ? "minLength" : t instanceof Type.ListT ? "minItems" : null;
        String max = t instanceof Type.Str ? "maxLength" : t instanceof Type.ListT ? "maxItems" : null;
        if (min == null) return;  // an array's length is fixed by its type
        if (atLeast != null && atLeast.signum() > 0) {
            raise(node, min, t instanceof Type.Str ? BigInteger.ONE : atLeast);
        }
        if (atMost != null && atMost.signum() >= 0) lower(node, max, atMost);
    }

    /** Sets a lower bound, unless the schema already has one at least as high. */
    private static void raise(Map<String, Object> node, String key, Object bound) {
        Object old = node.get(key);
        if (old == null || compare(bound, old) > 0) node.put(key, bound);
    }

    /** Sets an upper bound, unless the schema already has one at least as low. */
    private static void lower(Map<String, Object> node, String key, Object bound) {
        Object old = node.get(key);
        if (old == null || compare(bound, old) < 0) node.put(key, bound);
    }

    /** Compares two JSON numbers: integers are BigInteger or Integer, floats BigDecimal or a zero Double. */
    private static int compare(Object a, Object b) {
        return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
    }
}
