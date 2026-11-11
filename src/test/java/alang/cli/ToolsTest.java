package alang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import alang.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
