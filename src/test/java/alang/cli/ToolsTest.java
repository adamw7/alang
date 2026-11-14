package alang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.ast.Ast.Call;
import alang.ast.Ast.IntLit;
import alang.ast.Ast.TestDecl;
import alang.check.Checker;
import alang.diag.Source;
import alang.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The tool definitions `alang tools` prints: descriptions, input schemas and examples. */
class ToolsTest {
    @TempDir
    Path dir;

    private record Result(int code, String out, String err) {}

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new Main(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
            .run(args);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** The definitions `alang tools` prints for {@code program}, checked to be one line of JSON. */
    private String tools(String program) throws IOException {
        Path p = dir.resolve("tools.al");
        Files.writeString(p, program, StandardCharsets.UTF_8);
        Result r = run("tools", p.toString());
        assertEquals(0, r.code(), r.err());
        assertEquals("", r.err());
        return r.out();
    }

    /** JSON written readably, as the CLI prints it: one line, the keys in the same order. */
    private static String oneLine(String json) {
        return Json.write(Json.parse(json)) + "\n";
    }

    /** The schema of parameter {@code param} of the only tool in {@code program}, as one line of JSON. */
    private String param(String program, String param) throws IOException {
        Map<?, ?> tool = (Map<?, ?>) ((List<?>) Json.parse(tools(program))).get(0);
        return Json.write(((Map<?, ?>) ((Map<?, ?>) tool.get("input_schema")).get("properties")).get(param));
    }

    @Test
    void docCommentsDescribeParametersFieldsAndVariants() throws IOException {
        String out = tools("""
            /// An item in the catalog.
            struct Item {
                /// Stock keeping unit.
                sku: str[8],
                count: u8,
            }

            /// How to ship.
            enum Shipping {
                /// Next day, for a fee.
                Express,
                Standard,
                /// Picked up at the given store.
                Pickup(u8),
            }

            enum Wrap {
                Paper,
                Box,
            }

            /// Places an order.
            tool fn order(
                /// What to send.
                item: Item,
                shipping: Shipping,
                /// Gift wrap.
                wrap: Wrap,
            ) -> bool {
                return true;
            }
            """);
        assertEquals(oneLine("""
            [{"name": "order", "description": "Places an order.\\nReturns bool.", "input_schema": {
              "type": "object",
              "properties": {
                "item": {"type": "object", "properties": {
                    "sku": {"type": "string", "maxLength": 8,
                            "description": "Stock keeping unit.\\nUTF-8 text of at most 8 bytes"},
                    "count": {"type": "integer", "minimum": 0, "maximum": 255}},
                  "required": ["sku", "count"], "additionalProperties": false,
                  "description": "What to send.\\nAn item in the catalog."},
                "shipping": {"oneOf": [
                    {"type": "string", "const": "Express", "description": "Next day, for a fee."},
                    {"type": "string", "const": "Standard"},
                    {"type": "object", "properties": {"Pickup": {"type": "array",
                        "prefixItems": [{"type": "integer", "minimum": 0, "maximum": 255}], "minItems": 1, "maxItems": 1}},
                      "required": ["Pickup"], "additionalProperties": false,
                      "description": "Picked up at the given store."}],
                  "description": "How to ship."},
                "wrap": {"type": "string", "enum": ["Paper", "Box"], "description": "Gift wrap."}},
              "required": ["item", "shipping", "wrap"],
              "additionalProperties": false}}]
            """), out);
    }

    /** The README's tool: the description lists the contract, and the schema repeats the bounds. */
    @Test
    void contractsAreListedAndBoundTheSchema() throws IOException {
        String out = tools("""
            /// Splits a bill evenly; the remainder goes to the first payers.
            tool fn split_bill(total_cents: i64, people: i64) -> list[i64; 16]
                requires total_cents >= 0
                requires people > 0 and people <= 16
                ensures len(result) == people
            {
                var shares: list[i64; 16] = [];
                for i in 0..people {
                    push(mut shares, total_cents / people);
                }
                return shares;
            }
            """);
        assertEquals(oneLine("""
            [{"name": "split_bill",
              "description": "Splits a bill evenly; the remainder goes to the first payers.\\nRequires: total_cents >= 0\\nRequires: people > 0 and people <= 16\\nReturns list[i64; 16].\\nEnsures: len(result) == people",
              "input_schema": {"type": "object", "properties": {
                  "total_cents": {"type": "integer", "minimum": 0, "maximum": 9223372036854775807},
                  "people": {"type": "integer", "minimum": 1, "maximum": 16}},
                "required": ["total_cents", "people"], "additionalProperties": false}}]
            """), out);
    }

    @Test
    void comparisonsWithConstantsBecomeBounds() throws IOException {
        // Either side may hold the constant, and a constant may be an expression of named constants.
        assertEquals("{\"type\": \"integer\", \"minimum\": -3, \"maximum\": 99}", param("""
            const LIMIT: i32 = 10;
            tool fn f(x: i32) -> i32 requires -3 <= x and LIMIT * LIMIT > x { return x; }
            """, "x"));
        assertEquals("{\"type\": \"integer\", \"minimum\": 7, \"maximum\": 7}", param("""
            tool fn f(x: u16) -> u16 requires x == 7 { return x; }
            """, "x"));
        // A bound is only ever tightened: a weaker clause, or one wider than the type, changes nothing.
        assertEquals("{\"type\": \"integer\", \"minimum\": 5, \"maximum\": 10}", param("""
            tool fn f(x: i8) -> i8 requires x >= 5 requires x >= 2 requires x <= 10 requires x < 127 { return x; }
            """, "x"));
        assertEquals("{\"type\": \"integer\", \"minimum\": 0, \"maximum\": 18446744073709551614}", param("""
            tool fn f(x: u64) -> u64 requires x >= 0 and x < 18446744073709551615 { return x; }
            """, "x"));
    }

    @Test
    void floatBoundsAreInclusiveOrExclusiveAsWritten() throws IOException {
        assertEquals("{\"type\": \"number\", \"exclusiveMinimum\": 0.0, \"maximum\": 1.5}", param("""
            tool fn f(r: f64) -> f64 requires r > 0.0 and r <= 1.5 { return r; }
            """, "r"));
        // An f32 bound is written as the f32 prints, not as the widened f64 0.10000000149011612.
        assertEquals("{\"type\": \"number\", \"minimum\": 0.1, \"exclusiveMaximum\": 2.0}", param("""
            tool fn f(r: f32) -> f32 requires r >= 0.1 and r < 2.0 { return r; }
            """, "r"));
        assertEquals("{\"type\": \"number\", \"minimum\": 0.5, \"maximum\": 0.5}", param("""
            tool fn f(r: f64) -> f64 requires r == 0.5 { return r; }
            """, "r"));
        // No JSON number is NaN or infinite: such a bound is left to the contract.
        assertEquals("{\"type\": \"number\"}", param("""
            const INF: f64 = 1.0 / 0.0;
            tool fn f(r: f64) -> f64 requires r < INF { return r; }
            """, "r"));
    }

    @Test
    void fieldsAndLengthsAreBoundedToo() throws IOException {
        String program = """
            struct Line {
                sku: str[8],
                qty: i64,
            }

            tool fn f(lines: list[Line; 16], line: Line, code: str[12]) -> i64
                requires len(lines) >= 2 and len(lines) < 10
                requires line.qty > 0
                requires len(line.sku) == 8
                requires len(code) > 3 and len(code) <= 6
            {
                return 0;
            }
            """;
        assertEquals(Json.write(Json.parse("""
            {"type": "array", "items": {"type": "object", "properties": {
                "sku": {"type": "string", "maxLength": 8, "description": "UTF-8 text of at most 8 bytes"},
                "qty": {"type": "integer", "minimum": -9223372036854775808, "maximum": 9223372036854775807}},
              "required": ["sku", "qty"], "additionalProperties": false},
             "maxItems": 9, "minItems": 2}
            """)), param(program, "lines"));
        assertEquals(Json.write(Json.parse("""
            {"type": "object", "properties": {
                "sku": {"type": "string", "maxLength": 8, "description": "UTF-8 text of at most 8 bytes", "minLength": 1},
                "qty": {"type": "integer", "minimum": 1, "maximum": 9223372036854775807}},
              "required": ["sku", "qty"], "additionalProperties": false}
            """)), param(program, "line"));
        // len counts bytes and minLength characters: four bytes may be one character.
        assertEquals("{\"type\": \"string\", \"maxLength\": 6, \"description\": \"UTF-8 text of at most 12 bytes\", "
            + "\"minLength\": 1}", param(program, "code"));
        // A length of at least 0 says nothing; one below 1 allows only the empty list.
        String bounds = """
            tool fn f(s: str[4], xs: list[i64; 4]) -> i64 requires len(s) >= 0 and len(xs) < 1 { return 0; }
            """;
        assertEquals("{\"type\": \"string\", \"maxLength\": 4, \"description\": \"UTF-8 text of at most 4 bytes\"}",
            param(bounds, "s"));
        assertEquals("{\"type\": \"array\", \"items\": {\"type\": \"integer\", \"minimum\": -9223372036854775808, "
            + "\"maximum\": 9223372036854775807}, \"maxItems\": 0}", param(bounds, "xs"));
    }

    /** Calls in tests with constant arguments become input_examples: distinct, in order, at most three. */
    @Test
    void testsProvideInputExamples() throws IOException {
        String out = tools("""
            const START: u8 = 7;

            struct Range {
                from: u8,
                to: u8,
            }

            enum Unit {
                Day,
                Hours(u8),
            }

            /// Counts the steps of a range.
            tool fn steps(r: Range, unit: Unit, label: str[8]) -> list[u8; 4]
                requires r.from <= r.to
            {
                var out: list[u8; 4] = [];
                push(mut out, r.to - r.from);
                return out;
            }

            tool fn ratio(a: f64, b: f64) -> f64 {
                return a / b;
            }

            test "steps" {
                let r: Range = Range(from: 1, to: 3);
                assert len(steps(r, Unit.Day, "local")) == 1;                   // a local: not an example
                assert steps(Range(from: 2, to: 1), Unit.Day, "x") == [255];    // traps: not an example
                assert len(steps(Range(from: START, to: 9), Unit.Hours(2), "ok")) == 1;
                assert len(steps(Range(from: START, to: 9), Unit.Hours(2), "ok")) == 1;
            }

            test "more steps" {
                for i in 0..2 {
                    assert len(steps(Range(from: 0, to: 0), Unit.Day, "")) == 1;
                }
                assert len(steps(Range(from: 1, to: 1), Unit.Day, "a")) == 1;
                assert len(steps(Range(from: 2, to: 2), Unit.Day, "b")) == 1;
            }

            test "ratio" {
                assert ratio(1.0, 4.0) == 0.25;
                assert ratio(0.0, 0.0) != ratio(0.0, 0.0);                      // NaN has no JSON: not an example
            }
            """);
        List<?> tools = (List<?>) Json.parse(out);
        assertEquals(oneLine("""
            [{"r": {"from": 7, "to": 9}, "unit": {"Hours": [2]}, "label": "ok"},
             {"r": {"from": 0, "to": 0}, "unit": "Day", "label": ""},
             {"r": {"from": 1, "to": 1}, "unit": "Day", "label": "a"}]
            """), Json.write(((Map<?, ?>) tools.get(0)).get("input_examples")) + "\n");
        assertEquals("[{\"a\": 1.0, \"b\": 4.0}]", Json.write(((Map<?, ?>) tools.get(1)).get("input_examples")));
    }

    /** A tool call is found wherever a test can make one, in the order the calls are evaluated. */
    @Test
    void examplesAreFoundInEveryStatementAndExpression() {
        String program = """
            struct S {
                a: i64,
            }

            enum E {
                V(i64),
            }

            tool fn t(n: i64) -> i64 {
                return n;
            }

            test "everywhere" {
                let a: i64 = t(1);
                var x: i64 = 0;
                var xs: [i64; 2] = [0, 0];
                xs[t(3)] = t(2);
                if t(4) > 0 {
                    x = t(5);
                } else if t(6) > 0 {
                    x = t(7);
                } else {
                    x = t(8);
                }
                while t(10) > x limit t(9) {
                    x = t(11);
                }
                for i in t(12)..t(13) {
                    x = t(14);
                    if i > 0 {
                        break;
                    }
                    continue;
                }
                for v in [t(15)] {
                }
                match t(16) {
                    0 => { x = t(17); }
                    _ => { }
                }
                assert t(18) > 0;
                t(19);
                match E.V(t(20)) {
                    E.V(n) => { }
                }
                x = S(a: t(21)).a + [t(22)][0] + [t(23); 2][0] - t(24) + i64(i32(t(25))) + [1, 2][t(26)];
                x = abs(t(27));
                if false {
                    return;
                }
            }
            """;
        Frontend.Result r = Frontend.compile(new Source("calls.al", program), Checker.DEFAULT_MAX_STACK);
        assertTrue(r.ok(), () -> r.diags().renderText());
        List<BigInteger> order = new ArrayList<>();
        for (Call c : ToolExamples.callsIn(r.program().itemsOf(TestDecl.class).get(0).body)) {
            if (c.fn != null && c.fn.tool) order.add(((IntLit) c.args.get(0).expr()).value);
        }
        List<BigInteger> expected = new ArrayList<>();
        for (int i = 1; i <= 27; i++) expected.add(BigInteger.valueOf(i));
        assertEquals(expected, order);
    }

    /** Only constants make examples: a local anywhere inside an argument rules the call out. */
    @Test
    void examplesNeedConstantArguments() throws IOException {
        String out = tools("""
            struct S {
                a: i64,
            }

            enum E {
                V(i64),
            }

            tool fn f(s: S, e: E, xs: list[i64; 4]) -> i64 {
                return s.a;
            }

            test "locals" {
                let n: i64 = 1;
                assert f(S(a: n), E.V(1), [1]) == 1;
                assert f(S(a: 1), E.V(n), [1]) == 1;
                assert f(S(a: 1), E.V(1), [n]) == 1;
                assert f(S(a: 1), E.V(1), [n; 2]) == 1;
                assert f(S(a: abs(n)), E.V(1), [1]) == 1;
                assert f(S(a: abs(-3)), E.V(min(1, 2)), [7; 2]) == 3;
            }
            """);
        assertEquals("[{\"s\": {\"a\": 3}, \"e\": {\"V\": [1]}, \"xs\": [7, 7]}]",
            Json.write(((Map<?, ?>) ((List<?>) Json.parse(out)).get(0)).get("input_examples")));
    }

    /** An example must return within the step budget, and a tool without one has no input_examples key. */
    @Test
    void examplesRespectTheStepBudget() throws IOException {
        Path p = dir.resolve("budget.al");
        Files.writeString(p, """
            tool fn spin(n: i64) -> i64 {
                var total: i64 = 0;
                for i in 0..n {
                    total += i;
                }
                return total;
            }

            test "spin" {
                assert spin(1000) == 499500;
            }
            """, StandardCharsets.UTF_8);
        Result r = run("tools", p.toString());
        assertEquals("[{\"n\": 1000}]", Json.write(((Map<?, ?>) ((List<?>) Json.parse(r.out())).get(0)).get("input_examples")));
        r = run("tools", p.toString(), "--max-steps", "100");
        assertEquals(0, r.code(), r.err());
        assertEquals(false, ((Map<?, ?>) ((List<?>) Json.parse(r.out())).get(0)).containsKey("input_examples"), r.out());
    }

    @Test
    void parametersWithDefaultsAreOptional() throws IOException {
        String program = """
            enum Unit {
                Metric,
                Imperial,
            }

            /// Formats a distance.
            tool fn distance(meters: f64, unit: Unit = Unit.Metric, decimals: i64 = 1, nan: f64 = 0.0 / 0.0) -> str[32]
                requires decimals >= 0 and decimals <= 3
            {
                var s: str[32] = "";
                match unit {
                    Unit.Metric => { append(mut s, fixed(meters, decimals), " m"); }
                    Unit.Imperial => { append(mut s, fixed(meters * 3.28084, decimals), " ft"); }
                }
                if is_nan(nan) {
                    return s;
                }
                return "";
            }

            test "defaults" {
                assert distance(2.0) == "2.0 m";
                assert distance(2.0, Unit.Imperial, 0) == "7 ft";
            }
            """;
        Map<?, ?> tool = (Map<?, ?>) ((List<?>) Json.parse(tools(program))).get(0);
        Map<?, ?> schema = (Map<?, ?>) tool.get("input_schema");
        assertEquals(List.of("meters"), schema.get("required"));
        assertEquals("{\"type\": \"integer\", \"minimum\": 0, \"maximum\": 3, \"default\": 1}", param(program, "decimals"));
        assertEquals("Metric", ((Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("unit")).get("default"));
        // JSON has no NaN, so that default is not shown; the parameter is still optional.
        assertEquals(false, ((Map<?, ?>) ((Map<?, ?>) schema.get("properties")).get("nan")).containsKey("default"));
        // A call in a test that leaves out arguments gives an example that leaves them out too.
        assertEquals("[{\"meters\": 2.0}, {\"meters\": 2.0, \"unit\": \"Imperial\", \"decimals\": 0}]",
            Json.write(tool.get("input_examples")));
        Path p = dir.resolve("tools.al");
        // Four statements, and the float fixed() writes, which costs about as long as 22 more.
        Result r = run("call", p.toString(), "distance", "{\"meters\": 1.5}");
        assertEquals("{\"ok\": true, \"result\": \"1.5 m\", \"stdout\": \"\", \"steps\": 27}\n", r.out());
        r = run("call", p.toString(), "distance", "{\"meters\": 1.5, \"decimals\": 2}");
        assertEquals(0, r.code(), r.out());
        assertTrue(r.out().contains("\"result\": \"1.50 m\""), r.out());
        r = run("call", p.toString(), "distance", "{}");
        assertTrue(r.out().contains("meters: missing argument"), r.out());
    }

    @Test
    void otherClausesAreOnlyDescribed() throws IOException {
        String program = """
            const BIG: i64 = 9223372036854775807;

            tool fn f(lo: i64, hi: i64, xs: [i64; 3], s: str[4]) -> i64
                requires lo <= hi
                requires lo > 0 or hi > 0
                requires lo != 3
                requires not (hi > 5)
                requires xs[0] > 0
                requires hi < BIG + 1
                requires len(s) < 0
                requires len(xs) == 3
                requires
                    hi
                        >= 1
            {
                return lo;
            }
            """;
        Map<?, ?> tool = (Map<?, ?>) ((List<?>) Json.parse(tools(program))).get(0);
        assertEquals("Calls the ALang function f.\nRequires: lo <= hi\nRequires: lo > 0 or hi > 0\nRequires: lo != 3\n"
            + "Requires: not (hi > 5)\nRequires: xs[0] > 0\nRequires: hi < BIG + 1\nRequires: len(s) < 0\n"
            + "Requires: len(xs) == 3\nRequires: hi >= 1\nReturns i64.", tool.get("description"));
        assertEquals("{\"type\": \"integer\", \"minimum\": -9223372036854775808, \"maximum\": 9223372036854775807}",
            param(program, "lo"));
        assertEquals("{\"type\": \"integer\", \"minimum\": 1, \"maximum\": 9223372036854775807}", param(program, "hi"));
        assertEquals("{\"type\": \"array\", \"items\": {\"type\": \"integer\", \"minimum\": -9223372036854775808, "
            + "\"maximum\": 9223372036854775807}, \"minItems\": 3, \"maxItems\": 3}", param(program, "xs"));
        assertEquals("{\"type\": \"string\", \"maxLength\": 4, \"description\": \"UTF-8 text of at most 4 bytes\"}",
            param(program, "s"));
    }
}
