package alang.api;

import alang.util.Json;
import java.util.Map;

/**
 * A `tool fn` of a program, as a model is told about it.
 *
 * @param name             the function's name
 * @param description      its doc comment, contract and return type
 * @param inputSchemaJson  the JSON Schema of its arguments, an object
 * @param outputSchemaJson the JSON Schema of its result, or null for a tool that returns nothing
 */
public record Tool(String name, String description, String inputSchemaJson, String outputSchemaJson) {
    /**
     * The definition in the shape of the Messages API's {@code tools}: name, description and input_schema, as
     * `alang tools` prints it (without the examples it adds from the tests).
     */
    public String toJson() {
        Map<String, Object> j = Json.obj("name", name, "description", description, "input_schema", Json.parse(inputSchemaJson));
        return Json.write(j);
    }
}
