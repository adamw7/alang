package alang.cli;

import alang.ast.Ast.FnDecl;
import alang.ast.Ast.Program;
import alang.diag.Source;
import alang.interp.JsonValues;
import alang.types.Type;
import alang.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * `alang serve`: a program's tools over the Model Context Protocol's stdio transport. Each line of stdin
 * is one JSON-RPC 2.0 message, and each answer is one line of stdout; nothing else is written there. The
 * program is checked once, at start, so every call in a session runs the same code.
 */
final class McpServer {
    /** The protocol revisions this server speaks, newest first. A client asking for another gets the newest. */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    static final String INSTRUCTIONS = "Each tool is an ALang function: the same arguments always give the same "
        + "result, and a call cannot read or change anything outside itself. A result's text is the JSON "
        + "`alang call` prints: ok, then result or error, then stdout and steps (the statements it ran).";

    /** JSON-RPC error codes. */
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    private final Source src;
    private final Program program;
    private final long maxSteps;

    McpServer(Source src, Program program, long maxSteps) {
        this.src = src;
        this.program = program;
        this.maxSteps = maxSteps;
    }

    /** A request this server cannot answer with a result: a JSON-RPC error. */
    private static final class RpcException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int code;

        RpcException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Answers the messages read from {@code in} until it ends, flushing each answer. */
    void serve(InputStream in, PrintStream out) throws IOException {
        for (byte[] line = readLine(in); line != null; line = readLine(in)) {
            Map<String, Object> answer = handle(line);
            if (answer != null) {
                out.print(Json.write(answer));
                out.print('\n');
                out.flush();
            }
        }
    }

    /** The next line without its "\n" or "\r\n", or null at the end of the input. */
    private static byte[] readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b = in.read();
        if (b < 0) return null;
        while (b >= 0 && b != '\n') {
            line.write(b);
            b = in.read();
        }
        byte[] bytes = line.toByteArray();
        int n = bytes.length;
        if (n > 0 && bytes[n - 1] == '\r') n--;
        return Arrays.copyOf(bytes, n);
    }

    /**
     * The answer to one line: a result or an error for a request, null for a notification, a response
     * from the client (this server sends no requests), or a blank line.
     */
    Map<String, Object> handle(byte[] line) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(line)).toString();
        } catch (CharacterCodingException e) {
            return error(null, PARSE_ERROR, "the message is not valid UTF-8");
        }
        if (text.isBlank()) return null;
        Object message;
        try {
            message = Json.parse(text);
        } catch (Json.ParseException e) {
            return error(null, PARSE_ERROR, "the message is not valid JSON: " + e.getMessage());
        }
        if (!(message instanceof Map<?, ?> m)) return error(null, INVALID_REQUEST, "a message must be a JSON object");
        Object id = m.get("id");
        boolean validId = id instanceof String || id instanceof BigInteger || id instanceof BigDecimal;
        if (m.containsKey("id") && !validId) return error(null, INVALID_REQUEST, "an id must be a string or a number");
        if (!(m.get("method") instanceof String method)) {
            if (m.containsKey("result") || m.containsKey("error")) return null;  // a response; nothing to answer
            return error(id, INVALID_REQUEST, "a request needs a \"method\" string");
        }
        if (!"2.0".equals(m.get("jsonrpc"))) return error(id, INVALID_REQUEST, "\"jsonrpc\" must be \"2.0\"");
        boolean notification = !m.containsKey("id");
        Object result;
        try {
            result = switch (method) {
                case "initialize" -> initialize(m.get("params"));
                case "ping" -> Json.obj();
                case "tools/list" -> Json.obj("tools", tools());
                case "tools/call" -> call(m.get("params"));
                default -> throw new RpcException(METHOD_NOT_FOUND, "method not found: " + method);
            };
        } catch (RpcException e) {
            return notification ? null : error(id, e.code, e.getMessage());
        }
        return notification ? null : Json.obj("jsonrpc", "2.0", "id", id, "result", result);
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        return Json.obj("jsonrpc", "2.0", "id", id, "error", Json.obj("code", code, "message", message));
    }

    private static Map<String, Object> initialize(Object params) {
        Object asked = params instanceof Map<?, ?> p ? p.get("protocolVersion") : null;
        String version = asked instanceof String s && PROTOCOL_VERSIONS.contains(s) ? s : PROTOCOL_VERSIONS.get(0);
        return Json.obj("protocolVersion", version,
            "capabilities", Json.obj("tools", Json.obj("listChanged", false)),
            "serverInfo", Json.obj("name", "alang", "version", Main.VERSION),
            "instructions", INSTRUCTIONS);
    }

    /**
     * MCP tool definitions: the same description and input schema as `alang tools`, the result's schema,
     * and annotations that are true of every ALang tool: it changes nothing, so it is read-only, not
     * destructive and idempotent, and it reaches nothing outside itself.
     */
    private List<Object> tools() {
        List<Object> tools = new ArrayList<>();
        for (FnDecl f : program.itemsOf(FnDecl.class)) {
            if (!f.tool) continue;
            Map<String, Object> tool = Json.obj("name", f.name, "description", ToolDefinitions.description(f),
                "inputSchema", ToolDefinitions.inputSchema(f));
            if (!(f.ret instanceof Type.Void)) {
                tool.put("outputSchema", Json.obj("type", "object", "properties", Json.obj("result", JsonValues.schema(f.ret)),
                    "required", List.of("result"), "additionalProperties", false));
            }
            tool.put("annotations", Json.obj("readOnlyHint", true, "destructiveHint", false,
                "idempotentHint", true, "openWorldHint", false));
            tools.add(tool);
        }
        return tools;
    }

    /**
     * Runs a tool as `alang call` does. Its report is the result's text; a successful call of a tool that
     * returns a value also gives {"result": value} as structured content, matching the output schema. A
     * call that failed, from bad arguments to a trap, is a result with "isError": true, so the model sees
     * the error and can fix its call; only an unknown tool is a protocol error.
     */
    private Map<String, Object> call(Object params) {
        if (!(params instanceof Map<?, ?> p) || !(p.get("name") instanceof String name)) {
            throw new RpcException(INVALID_PARAMS, "tools/call needs params with a \"name\" string");
        }
        FnDecl f = program.function(name);
        if (f == null || !f.tool) throw new RpcException(INVALID_PARAMS, "unknown tool: " + name);
        Object arguments = p.containsKey("arguments") ? p.get("arguments") : Json.obj();
        Map<String, Object> report = Main.callTool(src, f, arguments, maxSteps);
        boolean ok = report.get("ok") == Boolean.TRUE;
        Map<String, Object> result = Json.obj("content", List.of(Json.obj("type", "text", "text", Json.write(report))));
        if (ok && !(f.ret instanceof Type.Void)) result.put("structuredContent", Json.obj("result", report.get("result")));
        result.put("isError", !ok);
        return result;
    }
}
