package alang.cli;

import alang.ast.Ast.ArrayLit;
import alang.ast.Ast.Arm;
import alang.ast.Ast.Assert;
import alang.ast.Ast.Assign;
import alang.ast.Ast.Binary;
import alang.ast.Ast.Block;
import alang.ast.Ast.BoolLit;
import alang.ast.Ast.Break;
import alang.ast.Ast.Call;
import alang.ast.Ast.Continue;
import alang.ast.Ast.Convert;
import alang.ast.Ast.Expr;
import alang.ast.Ast.ExprStmt;
import alang.ast.Ast.FieldAccess;
import alang.ast.Ast.FieldInit;
import alang.ast.Ast.FloatLit;
import alang.ast.Ast.FnDecl;
import alang.ast.Ast.For;
import alang.ast.Ast.If;
import alang.ast.Ast.IfExpr;
import alang.ast.Ast.Index;
import alang.ast.Ast.IntLit;
import alang.ast.Ast.Let;
import alang.ast.Ast.LocalVar;
import alang.ast.Ast.Match;
import alang.ast.Ast.Name;
import alang.ast.Ast.Program;
import alang.ast.Ast.Repeat;
import alang.ast.Ast.Resize;
import alang.ast.Ast.Return;
import alang.ast.Ast.Stmt;
import alang.ast.Ast.StrLit;
import alang.ast.Ast.StructLit;
import alang.ast.Ast.TestDecl;
import alang.ast.Ast.Unary;
import alang.ast.Ast.VariantCall;
import alang.ast.Ast.While;
import alang.diag.Source;
import alang.interp.Interpreter;
import alang.interp.JsonValues;
import alang.interp.Trap;
import alang.interp.Values;
import alang.types.Type;
import alang.util.Json;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Example arguments for a tool, taken from the program's own tests: each call of the tool in a `test`
 * block whose arguments are all constants, as long as the call returns a result without a trap. A call
 * that passes the contract also fits the input schema, which only repeats parts of the contract, so every
 * example is one the Messages API accepts (it rejects a request whose `input_examples` break the schema).
 */
final class ToolExamples {
    private ToolExamples() {}

    /** At most this many examples per tool: each one costs prompt tokens on every request. */
    static final int MAX = 3;

    /** Up to {@link #MAX} distinct examples, in the order the tests make the calls. */
    static List<Object> of(FnDecl tool, Program program, Source src, long maxSteps) {
        List<Object> examples = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TestDecl t : program.itemsOf(TestDecl.class)) {
            for (Call c : callsIn(t.body)) {
                if (examples.size() == MAX) return examples;
                if (c.fn != tool || !c.args.stream().allMatch(a -> ToolDefinitions.isConstant(a.expr()))) continue;
                Map<String, Object> example = example(tool, c, src, maxSteps);
                if (example != null && seen.add(Json.write(example))) examples.add(example);
            }
        }
        return examples;
    }

    /** The arguments of {@code c} as a JSON object, or null if the call traps or its result is not JSON. */
    private static Map<String, Object> example(FnDecl tool, Call c, Source src, long maxSteps) {
        Object[] args = new Object[tool.paramVars.size()];
        Map<String, Object> json = new LinkedHashMap<>();
        try {
            for (int i = 0; i < args.length; i++) {
                LocalVar p = tool.paramVars.get(i);
                args[i] = Values.coerce(Interpreter.evalConst(c.args.get(i).expr()), p.type);
                json.put(p.name, JsonValues.toJson(args[i], p.type));
            }
            Interpreter in = new Interpreter(src, OutputStream.nullOutputStream(), maxSteps);
            Object result = in.call(tool, args, c.span);
            if (!(tool.ret instanceof Type.Void)) JsonValues.toJson(result, tool.ret);
        } catch (Trap | JsonValues.EncodeException unusable) {
            return null;
        }
        return json;
    }

    // ------------------------------------------------------------------ the calls in a test, in order

    /** Every call in {@code b}, of functions and builtins alike, in the order they are evaluated. */
    static List<Call> callsIn(Block b) {
        List<Call> out = new ArrayList<>();
        calls(b, out);
        return out;
    }

    private static void calls(Block b, List<Call> out) {
        for (Stmt s : b.stmts()) calls(s, out);
    }

    private static void calls(Stmt s, List<Call> out) {
        switch (s) {
            case Let l -> calls(l.init, out);
            case Assign a -> {
                calls(a.value, out);
                calls(a.target, out);
            }
            case If i -> {
                for (int k = 0; k < i.conds.size(); k++) {
                    calls(i.conds.get(k), out);
                    calls(i.blocks.get(k), out);
                }
                if (i.elseBlock != null) calls(i.elseBlock, out);
            }
            case While w -> {
                calls(w.limit, out);
                calls(w.cond, out);
                calls(w.body, out);
            }
            case For f -> {
                calls(f.from, out);
                if (f.to != null) calls(f.to, out);
                if (f.step != null) calls(f.step, out);
                calls(f.body, out);
            }
            case Match m -> {
                calls(m.subject, out);
                for (Arm arm : m.arms) {
                    if (arm.guard() != null) calls(arm.guard(), out);
                    calls(arm.body(), out);
                }
            }
            case Return r -> {
                if (r.value != null) calls(r.value, out);
            }
            case Assert a -> calls(a.cond, out);
            case ExprStmt e -> calls(e.expr, out);
            case Break b -> { }
            case Continue c -> { }
        }
    }

    /** Arguments are evaluated before the call, so the calls inside them come first. */
    private static void calls(Expr e, List<Call> out) {
        switch (e) {
            case Call c -> {
                c.args.forEach(a -> calls(a.expr(), out));
                out.add(c);
            }
            case VariantCall vc -> vc.args.forEach(a -> calls(a.expr(), out));
            case StructLit s -> {
                for (FieldInit fi : s.fields) calls(fi.value(), out);
            }
            case ArrayLit a -> a.elems.forEach(x -> calls(x, out));
            case Repeat r -> {
                calls(r.value, out);
                calls(r.count, out);
            }
            case IfExpr i -> {
                calls(i.cond, out);
                calls(i.then, out);
                calls(i.otherwise, out);
            }
            case Unary u -> calls(u.operand, out);
            case Binary b -> {
                calls(b.left, out);
                calls(b.right, out);
            }
            case Convert c -> calls(c.arg, out);
            case Resize z -> calls(z.arg, out);
            case FieldAccess fa -> calls(fa.target, out);
            case Index ix -> {
                calls(ix.target, out);
                calls(ix.index, out);
            }
            case IntLit x -> { }
            case FloatLit x -> { }
            case StrLit x -> { }
            case BoolLit x -> { }
            case Name n -> { }
        }
    }
}
