package alang.cli;

import static org.junit.jupiter.api.Assertions.*;

import alang.util.Json;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The YAML of a SKILL.md's frontmatter, read as a host's YAML parser reads it: each expected value below is what
 * PyYAML's safe_load gives for the same text.
 */
class FrontmatterTest {
    private static final Map<String, String[]> SAME_AS_PYYAML = Map.ofEntries(
            Map.entry("folded", new String[] {"name: a\ndescription: >\n  one\n  two\n\n  three\n    more\n  four\n", "{\"name\": \"a\", \"description\": \"one two\\nthree\\n  more\\nfour\\n\"}"}),
            Map.entry("folded_strip", new String[] {"d: >-\n  one\n  two\n\n\n", "{\"d\": \"one two\"}"}),
            Map.entry("folded_keep", new String[] {"d: >+\n  one\n\n\nx: 1\n", "{\"d\": \"one\\n\\n\\n\", \"x\": 1}"}),
            Map.entry("literal", new String[] {"d: |\n  line one\n    indented\n\n  last\n", "{\"d\": \"line one\\n  indented\\n\\nlast\\n\"}"}),
            Map.entry("literal_strip", new String[] {"d: |-\n  a\n  b\n", "{\"d\": \"a\\nb\"}"}),
            Map.entry("literal_indicator", new String[] {"d: |2\n    four\n  two\n", "{\"d\": \"  four\\ntwo\\n\"}"}),
            Map.entry("plain_multi", new String[] {"description: This does a thing\n  and goes on\n\n  new para\n# comment\nname: x\n", "{\"description\": \"This does a thing and goes on\\nnew para\", \"name\": \"x\"}"}),
            Map.entry("dq", new String[] {"d: \"tab\\there \\u00e9 \\\"q\\\" \\\\ end\"\n", "{\"d\": \"tab\\there \u00e9 \\\"q\\\" \\\\ end\"}"}),
            Map.entry("dq_multi", new String[] {"d: \"first\n  second\n\n  third \\\n  joined\"\n", "{\"d\": \"first second\\nthird joined\"}"}),
            Map.entry("sq", new String[] {"d: 'it''s # not a comment'\n", "{\"d\": \"it's # not a comment\"}"}),
            Map.entry("sq_multi", new String[] {"d: 'a\n  b\n\n  c'\n", "{\"d\": \"a b\\nc\"}"}),
            Map.entry("scalars", new String[] {"a: true\nb: False\nc: ~\nd: null\ne: 12\nf: -3\ng: 1.5\nh: 0x1F\nk: \"12\"\nm:\no: .5\np: 1.2.3\nq: +7\n", "{\"a\": true, \"b\": false, \"c\": null, \"d\": null, \"e\": 12, \"f\": -3, \"g\": 1.5, \"h\": 31, \"k\": \"12\", \"m\": null, \"o\": 0.5, \"p\": \"1.2.3\", \"q\": 7}"}),
            Map.entry("comments", new String[] {"# top\nname: x # trailing\nurl: http://a.b/c#frag\n", "{\"name\": \"x\", \"url\": \"http://a.b/c#frag\"}"}),
            Map.entry("seq", new String[] {"tools:\n  - a\n  - b c\n  - \"q\"\n", "{\"tools\": [\"a\", \"b c\", \"q\"]}"}),
            Map.entry("seq_same_indent", new String[] {"tools:\n- a\n- b\nnext: 1\n", "{\"tools\": [\"a\", \"b\"], \"next\": 1}"}),
            Map.entry("seq_of_maps", new String[] {"items:\n  - name: a\n    v: 1\n  - name: b\n    v: [1, 2]\n", "{\"items\": [{\"name\": \"a\", \"v\": 1}, {\"name\": \"b\", \"v\": [1, 2]}]}"}),
            Map.entry("nested_map", new String[] {"metadata:\n  author: me\n  deep:\n    k: v\nlast: z\n", "{\"metadata\": {\"author\": \"me\", \"deep\": {\"k\": \"v\"}}, \"last\": \"z\"}"}),
            Map.entry("flow", new String[] {"a: [x, 'y z', \"w\", 1, true, [z]]\nb: {k: v, n: 2}\nc: []\nd: {}\n", "{\"a\": [\"x\", \"y z\", \"w\", 1, true, [\"z\"]], \"b\": {\"k\": \"v\", \"n\": 2}, \"c\": [], \"d\": {}}"}),
            Map.entry("seq_nested", new String[] {"a:\n  -\n    - 1\n    - 2\n  - - 3\n", "{\"a\": [[1, 2], [3]]}"}),
            Map.entry("quoted_keys", new String[] {"\"a b\": 1\n'c': 2\n", "{\"a b\": 1, \"c\": 2}"}),
            Map.entry("plain_dash", new String[] {"d: foo\n  - bar\n", "{\"d\": \"foo - bar\"}"}),
            Map.entry("url_and_colon", new String[] {"u: a:b\nv: \"x: y\"\n", "{\"u\": \"a:b\", \"v\": \"x: y\"}"}));

    private static Object read(String yaml) throws Frontmatter.FormatException {
        return Frontmatter.parse(List.of(yaml.split("\n", -1)), 2);
    }

    @Test
    void readsYamlAsAYamlParserDoes() throws Exception {
        for (Map.Entry<String, String[]> e : SAME_AS_PYYAML.entrySet()) {
            Object expected = Json.parse(e.getValue()[1]);
            assertEquals(expected, Json.parse(Json.write(read(e.getValue()[0]))), e.getKey());
        }
    }

    /** The message of the error reading {@code yaml} gives, which must be on {@code line} of the SKILL.md. */
    private static String error(String yaml, int line) {
        Frontmatter.FormatException e = assertThrows(Frontmatter.FormatException.class, () -> read(yaml), yaml);
        assertEquals(line, e.line, yaml + ": " + e.getMessage());
        return e.getMessage();
    }

    @Test
    void refusesWhatHostsWouldReadDifferently() {
        // YAML 1.1 (PyYAML) and YAML 1.2 (most others) disagree on these, so a host might not match the entry.
        for (String v : List.of("yes", "No", "off", "010", "0o17", "1e3", "1.5e+3", "1_000", "-.5", "0b101", "1:30",
                "2024-01-01", "2024-01-01T10:00:00Z")) {
            assertTrue(error("name: x\na: " + v, 3).contains("YAML 1.1 and 1.2 read `" + v + "` differently"), v);
        }
        assertTrue(error("description: Use when: the user asks", 2).contains("quote the value: \"Use when: the user asks\""));
        assertTrue(error("a: .inf", 2).contains("JSON cannot hold"));
    }

    @Test
    void refusesWhatItDoesNotRead() {
        assertTrue(error("a: &x 1", 2).contains("anchors"));
        assertTrue(error("a: *x", 2).contains("aliases"));
        assertTrue(error("a: !!str 1", 2).contains("tags"));
        assertTrue(error("a: 1\nb: 2\na: 3", 4).contains("`a` appears twice"));
        assertTrue(error("a:\n\tb: 1", 3).contains("tab"));
        assertTrue(error("a: \"open\nb: 1", 2).contains("not closed"));
        assertTrue(error("a: [1, 2", 2).contains("not closed"));
        assertTrue(error("a: \"\\q\"", 2).contains("unknown escape"));
        assertTrue(error("a: 1\n  b: 2", 3).contains("cannot go on with a `key: value` line"));
        assertTrue(error("a:\n  - 1\n  b: 2", 4).contains("unexpected indentation"));
    }

    @Test
    void theFrontmatterIsBetweenTwoDashLines() throws Exception {
        assertEquals(Json.obj("name", "x", "description", "d"),
            Frontmatter.of("---\r\nname: x\r\ndescription: d\r\n---\r\n# Body: not: yaml\n"));
        assertEquals(Map.of(), Frontmatter.of("---\n---\nbody"));
        assertEquals(1, assertThrows(Frontmatter.FormatException.class, () -> Frontmatter.of("# No frontmatter\n")).line);
        assertTrue(assertThrows(Frontmatter.FormatException.class, () -> Frontmatter.of("---\nname: x\n"))
            .getMessage().contains("no closing line"));
        assertTrue(assertThrows(Frontmatter.FormatException.class, () -> Frontmatter.of("---\n- a\n---\n"))
            .getMessage().contains("mapping"));
    }
}
