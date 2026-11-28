package alang.cli;

import static org.junit.jupiter.api.Assertions.*;

import alang.check.Checker;
import alang.diag.Source;
import alang.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** `alang serve --skills`: Agent Skills served by the MCP Skills extension, beside the tools. */
class SkillsTest {
    private static Path write(Path file, String text) throws IOException {
        return write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static Path write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, bytes);
    }

    private static String skillMd(String name, String description) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n# " + name + "\n";
    }

    private static String digest(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Skills load(Path... dirs) throws Skills.InvalidException {
        List<String> list = new ArrayList<>();
        for (Path d : dirs) list.add(d.toString());
        return Skills.load(list);
    }

    /** The message loading {@code dirs} fails with. */
    private static String refused(Path... dirs) {
        return assertThrows(Skills.InvalidException.class, () -> load(dirs)).getMessage();
    }

    /** A tree of skills: a skill at the top, one under a prefix, and files no skill holds or that are hidden. */
    private static Path tree(Path dir) throws IOException {
        write(dir.resolve("bill-splitting/SKILL.md"), """
            ---
            name: bill-splitting
            description: >
              Split a bill between friends
              with split_bill.
            license: Apache-2.0
            metadata:
              version: "1.0"
            ---
            # Splitting bills

            Fill in [the receipt](templates/receipt.md).
            """);
        write(dir.resolve("bill-splitting/templates/receipt.md"), "# Receipt\n");
        write(dir.resolve("bill-splitting/templates/logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF});
        write(dir.resolve("bill-splitting/.notes"), "hidden\n");
        write(dir.resolve("bill-splitting/.git/HEAD"), "ref\n");
        write(dir.resolve("acme/refunds/SKILL.md"), skillMd("refunds", "\"Refunds: the policy\""));
        write(dir.resolve("README.md"), "in no skill\n");
        return dir;
    }

    @Test
    void aTreeOfSkills(@TempDir Path dir) throws Exception {
        Skills s = load(tree(dir));
        assertEquals(2, s.count());
        byte[] md = Files.readAllBytes(dir.resolve("bill-splitting/SKILL.md"));
        byte[] png = Files.readAllBytes(dir.resolve("bill-splitting/templates/logo.png"));
        byte[] receipt = Files.readAllBytes(dir.resolve("bill-splitting/templates/receipt.md"));
        byte[] refunds = Files.readAllBytes(dir.resolve("acme/refunds/SKILL.md"));
        assertEquals(Json.parse("""
            [{"uri": "skill://acme/refunds/SKILL.md", "frontmatter": {"name": "refunds", "description": "Refunds: the policy"},
              "resources": [{"uri": "skill://acme/refunds/SKILL.md", "digest": "%s", "size": %d}]},
             {"uri": "skill://bill-splitting/SKILL.md",
              "frontmatter": {"name": "bill-splitting", "description": "Split a bill between friends with split_bill.\\n",
                "license": "Apache-2.0", "metadata": {"version": "1.0"}},
              "resources": [{"uri": "skill://bill-splitting/SKILL.md", "digest": "%s", "size": %d},
                {"uri": "skill://bill-splitting/templates/logo.png", "digest": "%s", "size": 6},
                {"uri": "skill://bill-splitting/templates/receipt.md", "digest": "%s", "size": %d}]}]
            """.formatted(digest(refunds), refunds.length, digest(md), md.length, digest(png), digest(receipt), receipt.length)),
            Json.parse(Json.write(s.list())));
        assertEquals(Json.write(s.list().get(1)), Json.write(s.get("skill://bill-splitting/SKILL.md")));
        assertNull(s.get("skill://bill-splitting/templates/receipt.md"));
        assertNull(s.get("skill://acme/SKILL.md"));

        assertEquals(Json.parse("""
            [{"uri": "skill://acme/refunds/SKILL.md", "name": "refunds", "description": "Refunds: the policy", "mimeType": "text/markdown", "size": %d},
             {"uri": "skill://bill-splitting/SKILL.md", "name": "bill-splitting", "description": "Split a bill between friends with split_bill.\\n",
              "mimeType": "text/markdown", "size": %d},
             {"uri": "skill://bill-splitting/templates/logo.png", "name": "logo.png", "mimeType": "image/png", "size": 6},
             {"uri": "skill://bill-splitting/templates/receipt.md", "name": "receipt.md", "mimeType": "text/markdown", "size": 10}]
            """.formatted(refunds.length, md.length)), Json.parse(Json.write(s.resources())));

        assertEquals(Json.obj("uri", "skill://bill-splitting/templates/receipt.md", "mimeType", "text/markdown", "text", "# Receipt\n"),
            s.read("skill://bill-splitting/templates/receipt.md"));
        assertEquals(Json.obj("uri", "skill://bill-splitting/templates/logo.png", "mimeType", "image/png", "blob", "iVBORwD/"),
            s.read("skill://bill-splitting/templates/logo.png"));
        assertNull(s.read("skill://bill-splitting/.notes"));
        assertNull(s.read("skill://README.md"));

        assertEquals(Json.parse("""
            [{"uri": "skill://bill-splitting/SKILL.md", "name": "bill-splitting", "description": "Split a bill between friends with split_bill.\\n",
              "mimeType": "text/markdown", "size": %d},
             {"uri": "skill://bill-splitting/templates", "name": "templates", "mimeType": "inode/directory"}]
            """.formatted(md.length)), Json.parse(Json.write(s.directory("skill://bill-splitting"))));
        assertEquals(Json.parse("[{\"uri\": \"skill://acme/refunds\", \"name\": \"refunds\", \"mimeType\": \"inode/directory\"}]"),
            Json.parse(Json.write(s.directory("skill://acme"))));
        assertNull(s.directory("skill://bill-splitting/SKILL.md"));
        assertNull(s.directory("skill://bill-splitting/templates/"));
        assertNull(s.directory("skill://"));
    }

    /** A directory that holds a SKILL.md is one skill, served under its own name. */
    @Test
    void aSkillOnItsOwn(@TempDir Path dir) throws Exception {
        Path skill = dir.resolve("refunds");
        write(skill.resolve("SKILL.md"), skillMd("refunds", "Refunds."));
        write(skill.resolve("examples/my notes.md"), "é\n");
        Skills s = load(skill);
        assertEquals(Set.of("skill://refunds/SKILL.md"), s.uris());
        assertEquals("skill://refunds/examples/my%20notes.md", ((Map<?, ?>) s.directory("skill://refunds/examples").get(0)).get("uri"));
        assertEquals("my notes.md", ((Map<?, ?>) s.directory("skill://refunds/examples").get(0)).get("name"));
        assertNotNull(s.read("skill://refunds/examples/my%20notes.md"));
    }

    /** A skill inside another is a skill of its own, and its files are the outer one's too. */
    @Test
    void nestedSkills(@TempDir Path dir) throws Exception {
        write(dir.resolve("outer/SKILL.md"), skillMd("outer", "Outer."));
        write(dir.resolve("outer/inner/SKILL.md"), skillMd("inner", "Inner."));
        write(dir.resolve("outer/inner/notes.md"), "notes\n");
        Skills s = load(dir);
        List<?> outer = (List<?>) s.get("skill://outer/SKILL.md").get("resources");
        List<?> inner = (List<?>) s.get("skill://outer/inner/SKILL.md").get("resources");
        assertEquals(3, outer.size());
        assertEquals(2, inner.size());
        assertEquals(Json.write(inner), Json.write(outer.subList(1, 3)));
    }

    @Test
    void skillsAHostWouldRefuseAreRefused(@TempDir Path dir) throws Exception {
        write(dir.resolve("a/refunds/SKILL.md"), skillMd("refund", "Refunds."));
        assertTrue(refused(dir.resolve("a")).endsWith("SKILL.md: the name `refund` must be the skill's directory's name, `refunds`"));
        write(dir.resolve("b/Refunds/SKILL.md"), skillMd("Refunds", "Refunds."));
        assertTrue(refused(dir.resolve("b")).contains("must be 1 to 64 lowercase letters, digits and hyphens"));
        write(dir.resolve("c/refunds/SKILL.md"), "---\nname: refunds\n---\n");
        assertTrue(refused(dir.resolve("c")).contains("needs a `description`"));
        write(dir.resolve("d/refunds/SKILL.md"), skillMd("refunds", "x".repeat(1025)));
        assertTrue(refused(dir.resolve("d")).contains("the description has 1025 characters; it may have at most 1024"));
        write(dir.resolve("e/refunds/SKILL.md"), "# Refunds\n");
        assertTrue(refused(dir.resolve("e")).contains("SKILL.md:1: a SKILL.md must begin with YAML frontmatter"));
        write(dir.resolve("f/refunds/SKILL.md"), "---\nname: refunds\ndescription: Use when: asked\n---\n");
        assertTrue(refused(dir.resolve("f")).contains("SKILL.md:3: a plain value cannot hold `: `"));
        write(dir.resolve("g/refunds/SKILL.md"), new byte[] {'-', '-', '-', '\n', (byte) 0xC3, '\n'});
        assertTrue(refused(dir.resolve("g")).endsWith("a SKILL.md must be UTF-8 text"));
        write(dir.resolve("h/refunds/SKILL.md"), skillMd("refunds", "Refunds."));
        for (int i = 0; i < Skills.MAX_FILES; i++) write(dir.resolve("h/refunds/f" + i + ".txt"), "");
        assertTrue(refused(dir.resolve("h")).contains("has 513 files; a skill may have at most 512"));
        assertTrue(refused(dir.resolve("missing")).endsWith("missing is not a directory"));
        // The same skill from two directories.
        write(dir.resolve("i/refunds/SKILL.md"), skillMd("refunds", "Refunds."));
        assertTrue(refused(dir.resolve("i"), dir.resolve("i/refunds")).startsWith("skill://refunds/SKILL.md is served twice"));
    }

    /** Only what is under the directory is served: a link could reach anything. */
    @Test
    void aLinkIsRefused(@TempDir Path dir) throws Exception {
        write(dir.resolve("s/refunds/SKILL.md"), skillMd("refunds", "Refunds."));
        Path secret = write(dir.resolve("secret.txt"), "secret\n");
        try {
            Files.createSymbolicLink(dir.resolve("s/refunds/notes.txt"), secret);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            Assumptions.assumeTrue(false, "no symbolic links here: " + e);
        }
        assertTrue(refused(dir.resolve("s")).contains("notes.txt is a symbolic link"));
    }

    @Test
    void aWatcherServesSkillsOnceTheySettle(@TempDir Path dir) throws Exception {
        write(dir.resolve("refunds/SKILL.md"), skillMd("refunds", "Refunds."));
        Skills first = load(dir);
        Skills.Watcher w = new Skills.Watcher(List.of(dir.toString()));
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(log, true, StandardCharsets.UTF_8);
        assertNull(w.poll(first, ps));
        write(dir.resolve("refunds/notes.md"), "notes\n");
        assertNull(w.poll(first, ps));  // changed: served once the next poll agrees
        Skills second = w.poll(first, ps);
        assertNotNull(second);
        assertNotNull(second.read("skill://refunds/notes.md"));
        write(dir.resolve("refunds/SKILL.md"), "broken\n");
        assertNull(w.poll(second, ps));
        assertNull(w.poll(second, ps));
        String text = log.toString(StandardCharsets.UTF_8);
        assertEquals(1, text.split("still serving the skills loaded before", -1).length - 1, text);
    }

    /** The example skills load, and name the tool they are for. */
    @Test
    void theExampleSkills() throws Exception {
        Skills s = load(alang.Harness.ROOT.resolve("examples/skills"));
        assertEquals(Set.of("skill://summarizing-expenses/SKILL.md"), s.uris());
        Map<?, ?> fm = (Map<?, ?>) s.get("skill://summarizing-expenses/SKILL.md").get("frontmatter");
        assertTrue(((String) fm.get("description")).contains("summarize tool of examples/expenses.al"));
        assertNotNull(s.read("skill://summarizing-expenses/examples/request.md"));
    }

    // ---------------------------------------------------------------- over MCP

    private static final String PROGRAM = """
        /// Greets.
        tool fn hello(name: str[8]) {
            print("hello ", name);
        }
        """;

    /** The answers of a server for PROGRAM, serving {@code skills} if not null, to {@code input}. */
    private static List<Map<?, ?>> serve(Skills skills, String input) throws IOException {
        Frontend.Result r = Frontend.compile(new Source("hello.al", PROGRAM), Checker.DEFAULT_MAX_STACK);
        assertTrue(r.ok());
        McpServer server = new McpServer(r.diags().source(), r.program(), 1000, new Watchdog(0), 1);
        if (skills != null) server.skills(skills);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(out, false, StandardCharsets.UTF_8));
        List<Map<?, ?>> answers = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) answers.add((Map<?, ?>) Json.parse(line));
        return answers;
    }

    private static String request(int id, String method, String params) {
        return "{\"jsonrpc\": \"2.0\", \"id\": " + id + ", \"method\": \"" + method + "\", \"params\": " + params + "}\n";
    }

    @Test
    void theExtensionOverMcp(@TempDir Path dir) throws Exception {
        Skills skills = load(tree(dir));
        List<Map<?, ?>> a = serve(skills,
            request(1, "initialize", "{\"protocolVersion\": \"2025-11-25\"}")
            + request(2, "skills/list", "{\"_meta\": {\"io.modelcontextprotocol/protocolVersion\": \"2026-07-28\"}}")
            + request(3, "skills/get", "{\"uri\": \"skill://acme/refunds/SKILL.md\"}")
            + request(4, "resources/list", "{}")
            + request(5, "resources/read", "{\"uri\": \"skill://bill-splitting/templates/receipt.md\"}")
            + request(6, "resources/directory/read", "{\"uri\": \"skill://bill-splitting/templates\"}")
            + request(7, "resources/templates/list", "{}")
            + request(8, "skills/get", "{\"uri\": \"skill://bill-splitting/templates/receipt.md\"}")
            + request(9, "resources/read", "{\"uri\": \"skill://nothing.md\"}")
            + request(10, "resources/directory/read", "{\"uri\": \"skill://bill-splitting/SKILL.md\"}")
            + request(11, "skills/get", "{}"));
        Map<?, ?> init = (Map<?, ?>) a.get(0).get("result");
        assertEquals(Json.parse("""
            {"tools": {"listChanged": false}, "resources": {"listChanged": false},
             "extensions": {"io.modelcontextprotocol/tasks": {}, "io.modelcontextprotocol/skills": {"directoryRead": true}}}
            """), init.get("capabilities"));
        assertEquals(McpServer.INSTRUCTIONS + McpServer.SKILL_INSTRUCTIONS
            + "skill://acme/refunds/SKILL.md, skill://bill-splitting/SKILL.md.", init.get("instructions"));
        Map<?, ?> list = (Map<?, ?>) a.get(1).get("result");
        assertEquals(List.of("resultType", "skills", "ttlMs", "cacheScope"), new ArrayList<>(list.keySet()));
        assertEquals("complete", list.get("resultType"));
        assertEquals(Json.parse(Json.write(skills.list())), list.get("skills"));
        assertEquals("private", list.get("cacheScope"));
        assertEquals(java.math.BigInteger.valueOf(McpServer.SKILLS_TTL_MILLIS), list.get("ttlMs"));
        assertEquals(Json.parse(Json.write(skills.get("skill://acme/refunds/SKILL.md"))), ((Map<?, ?>) a.get(2).get("result")).get("skill"));
        assertEquals(Json.parse(Json.write(skills.resources())), ((Map<?, ?>) a.get(3).get("result")).get("resources"));
        assertEquals(Json.parse("[{\"uri\": \"skill://bill-splitting/templates/receipt.md\", \"mimeType\": \"text/markdown\", \"text\": \"# Receipt\\n\"}]"),
            ((Map<?, ?>) a.get(4).get("result")).get("contents"));
        assertEquals(2, ((List<?>) ((Map<?, ?>) a.get(5).get("result")).get("resources")).size());
        assertEquals(List.of(), ((Map<?, ?>) a.get(6).get("result")).get("resourceTemplates"));
        for (int i = 7; i <= 10; i++) {
            assertEquals(java.math.BigInteger.valueOf(McpServer.INVALID_PARAMS), ((Map<?, ?>) a.get(i).get("error")).get("code"), Json.write(a.get(i)));
        }
        assertEquals("unknown skill: skill://bill-splitting/templates/receipt.md", ((Map<?, ?>) a.get(7).get("error")).get("message"));
        assertEquals("skills/get needs params with a \"uri\" string", ((Map<?, ?>) a.get(10).get("error")).get("message"));
    }

    /** A server without --skills has no resources, and says nothing of skills. */
    @Test
    void withoutSkills() throws Exception {
        List<Map<?, ?>> a = serve(null, request(1, "initialize", "{}") + request(2, "skills/list", "{}")
            + request(3, "resources/list", "{}"));
        Map<?, ?> init = (Map<?, ?>) a.get(0).get("result");
        assertEquals(Json.parse("{\"tools\": {\"listChanged\": false}, \"extensions\": {\"io.modelcontextprotocol/tasks\": {}}}"),
            init.get("capabilities"));
        assertEquals(McpServer.INSTRUCTIONS, init.get("instructions"));
        for (int i = 1; i <= 2; i++) {
            assertEquals(java.math.BigInteger.valueOf(McpServer.METHOD_NOT_FOUND), ((Map<?, ?>) a.get(i).get("error")).get("code"));
        }
    }
}
