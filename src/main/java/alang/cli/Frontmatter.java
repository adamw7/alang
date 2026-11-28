package alang.cli;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The YAML frontmatter of a SKILL.md as JSON values, for `serve --skills`: the MCP Skills extension sends it
 * verbatim as a JSON object, and a host compares that, field by field, with what its own YAML parser reads, so
 * each value must have the type YAML gives it. This reads the YAML that frontmatter is written in and refuses
 * the rest by line, rather than guess: block mappings and sequences (a `- key: value` item too), plain scalars
 * over several lines, single- and double-quoted strings, `|` and `>` block scalars with their chomping and
 * indentation indicators, one-line flow sequences and mappings, and comments. Plain scalars resolve as in
 * YAML 1.2's core schema: null, booleans, integers (decimal or 0x) and floats; any other text is a string.
 * One that YAML 1.1 reads otherwise (`yes`, `010`, `1e3`, a date), anchors, aliases, tags and tabs for
 * indentation are refused.
 */
final class Frontmatter {
    private Frontmatter() { }

    /** Frontmatter that this reader does not take, at {@code line} (1-based, within the SKILL.md). */
    static final class FormatException extends Exception {
        private static final long serialVersionUID = 1L;
        final int line;

        FormatException(int line, String message) {
            super(message);
            this.line = line;
        }
    }

    private static final Pattern INT = Pattern.compile("[-+]?[0-9]+");
    private static final Pattern HEX = Pattern.compile("0x[0-9a-fA-F]+");
    private static final Pattern FLOAT = Pattern.compile("[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?");
    /**
     * Plain scalars that YAML 1.1 and YAML 1.2 read differently, so that hosts would too (PyYAML follows 1.1,
     * most others 1.2): 1.1's booleans and sexagesimal, octal and underscored numbers, 1.2's 0o octal and
     * floats without a dot, and dates, which 1.1 reads as timestamps.
     */
    private static final Pattern AMBIGUOUS = Pattern.compile(
        "y|Y|yes|Yes|YES|n|N|no|No|NO|on|On|ON|off|Off|OFF"
        + "|[-+]?0[0-9_]+|0o[0-7]+|[-+]?[0-9][0-9_]*(:[0-5]?[0-9])+(\\.[0-9_]*)?"
        + "|[-+]?(?=[0-9._]*[0-9])[0-9._]*_[0-9._]*|[-+]?[0-9.]*[0-9][0-9.]*[eE][-+]?[0-9]+|[-+]\\.[0-9]+"
        + "|[-+]?0b[01_]+|[-+]0x[0-9a-fA-F_]+|0x[0-9a-fA-F_]*_[0-9a-fA-F_]*"
        + "|[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}([Tt ].*)?");
    private static final Pattern SPECIAL_FLOAT = Pattern.compile("[-+]?\\.(inf|Inf|INF)|\\.(nan|NaN|NAN)");

    /**
     * The frontmatter of {@code text}, a SKILL.md: the YAML between a first line `---` and the next line `---`,
     * as a map of JSON values (String, Boolean, BigInteger, BigDecimal, List, Map, null), in the order written.
     */
    static Map<String, Object> of(String text) throws FormatException {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        if (lines.isEmpty() || !lines.get(0).stripTrailing().equals("---")) {
            throw new FormatException(1, "a SKILL.md must begin with YAML frontmatter: a line `---`, the fields, and a line `---`");
        }
        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i).stripTrailing();
            if (l.equals("---") || l.equals("...")) {
                end = i;
                break;
            }
        }
        if (end < 0) throw new FormatException(1, "the frontmatter has no closing line `---`");
        Object value = parse(lines.subList(1, end), 2);
        if (value == null) return new LinkedHashMap<>();
        if (!(value instanceof Map<?, ?>)) throw new FormatException(2, "the frontmatter must be a mapping of `key: value` lines");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) value;
        return m;
    }

    /** {@code lines} as one YAML node; the first of them is line {@code first} of the file. */
    static Object parse(List<String> lines, int first) throws FormatException {
        Reader r = new Reader(new ArrayList<>(lines), first);
        r.skipBlank();
        if (r.at >= r.lines.size()) return null;
        Object v = r.block(r.indent(r.at), -1);
        r.skipBlank();
        if (r.at < r.lines.size()) throw r.error(r.at, "unexpected text; is it indented as the lines above?");
        return v;
    }

    private static final class Reader {
        final List<String> lines;
        final int first;
        int at;

        Reader(List<String> lines, int first) throws FormatException {
            this.lines = lines;
            this.first = first;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                int n = 0;
                while (n < l.length() && (l.charAt(n) == ' ' || l.charAt(n) == '\t')) n++;
                if (l.substring(0, n).indexOf('\t') >= 0 && n < l.length()) {
                    throw error(i, "a tab in the indentation: YAML indents with spaces");
                }
            }
        }

        FormatException error(int index, String message) {
            return new FormatException(first + index, message);
        }

        static boolean blank(String l) {
            String t = l.strip();
            return t.isEmpty() || t.startsWith("#");
        }

        void skipBlank() {
            while (at < lines.size() && blank(lines.get(at))) at++;
        }

        int indent(int index) {
            String l = lines.get(index);
            int n = 0;
            while (n < l.length() && l.charAt(n) == ' ') n++;
            return n;
        }

        /** The node whose first line, at {@link #at}, is indented {@code indent}, more than {@code parent}. */
        Object block(int indent, int parent) throws FormatException {
            String content = lines.get(at).substring(indent);
            if (content.equals("-") || content.startsWith("- ")) return sequence(indent);
            if (keyEnd(content) >= 0) return mapping(indent);
            return scalarLines(indent, parent);
        }

        /** Where the key of a `key: value` line ends (the colon), or -1 if the line is not one. */
        static int keyEnd(String content) {
            if (content.startsWith("\"") || content.startsWith("'")) {
                char q = content.charAt(0);
                int i = 1;
                while (i < content.length()) {
                    char c = content.charAt(i);
                    if (q == '"' && c == '\\') {
                        i += 2;
                        continue;
                    }
                    if (c == q) {
                        if (q == '\'' && i + 1 < content.length() && content.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        break;
                    }
                    i++;
                }
                int j = i + 1;
                while (j < content.length() && content.charAt(j) == ' ') j++;
                return j < content.length() && content.charAt(j) == ':' && (j + 1 == content.length() || content.charAt(j + 1) == ' ') ? j : -1;
            }
            if (content.startsWith("[") || content.startsWith("{") || content.startsWith("#")) return -1;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '#' && i > 0 && content.charAt(i - 1) == ' ') return -1;
                if (c == ':' && (i + 1 == content.length() || content.charAt(i + 1) == ' ')) return i;
            }
            return -1;
        }

        Map<String, Object> mapping(int indent) throws FormatException {
            Map<String, Object> m = new LinkedHashMap<>();
            while (true) {
                skipBlank();
                if (at >= lines.size() || indent(at) < indent) return m;
                int line = at;
                if (indent(at) > indent) throw error(at, "unexpected indentation");
                String content = lines.get(at).substring(indent);
                int colon = keyEnd(content);
                if (colon < 0) throw error(at, "expected `key: value`");
                String rawKey = content.substring(0, colon).strip();
                String key = rawKey.startsWith("\"") || rawKey.startsWith("'") ? quoted(rawKey, at) : rawKey;
                if (key.startsWith("&") || key.startsWith("*") || key.startsWith("!") || key.startsWith("?")) {
                    throw error(at, "anchors, aliases, tags and complex keys are not supported in frontmatter");
                }
                if (m.containsKey(key)) throw error(at, "the key `" + key + "` appears twice");
                m.put(key, value(content.substring(colon + 1), indent, true));
                if (at == line) at++;
            }
        }

        List<Object> sequence(int indent) throws FormatException {
            List<Object> list = new ArrayList<>();
            while (true) {
                skipBlank();
                if (at >= lines.size() || indent(at) < indent) return list;
                if (indent(at) > indent) throw error(at, "unexpected indentation");
                String content = lines.get(at).substring(indent);
                if (!(content.equals("-") || content.startsWith("- "))) {
                    // A mapping's value sequence at its key's indentation ends where the next key begins.
                    return list;
                }
                String rest = content.substring(1);
                int line = at;
                String afterDash = rest.stripLeading();
                int column = indent + 1 + (rest.length() - afterDash.length());
                if (afterDash.equals("-") || afterDash.startsWith("- ")) {
                    // `- - item`: a sequence whose items line up with this first one.
                    lines.set(at, " ".repeat(column) + afterDash);
                    list.add(sequence(column));
                    continue;
                }
                if (!afterDash.isEmpty() && !afterDash.startsWith("#") && keyEnd(afterDash) >= 0) {
                    // `- key: value`: a mapping whose keys line up with this first one.
                    lines.set(at, " ".repeat(column) + afterDash);
                    list.add(mapping(column));
                    continue;
                }
                list.add(value(rest, indent, false));
                if (at == line) at++;
            }
        }

        /**
         * The value after a key's colon or a sequence's dash, {@code rest}, on line {@link #at}, whose key or
         * dash is indented {@code indent}. Leaves {@link #at} on the last line it read, or past it.
         */
        Object value(String rest, int indent, boolean ofKey) throws FormatException {
            String t = stripComment(rest).strip();
            int line = at;
            if (t.isEmpty()) {
                at++;
                skipBlank();
                if (at >= lines.size()) return null;
                int next = indent(at);
                String content = lines.get(at).substring(next);
                boolean dash = content.equals("-") || content.startsWith("- ");
                if (next > indent || (ofKey && next == indent && dash)) return block(next, indent);
                return null;
            }
            char c = t.charAt(0);
            if (c == '&' || c == '*' || c == '!') {
                throw error(line, "anchors, aliases and tags are not supported in frontmatter");
            }
            if (c == '|' || c == '>') return blockScalar(t, indent);
            if (c == '"' || c == '\'') return multilineQuoted(rest.stripLeading(), indent);
            if (c == '[' || c == '{') {
                Flow f = new Flow(t, this, line);
                Object v = f.value();
                f.end();
                at++;
                return v;
            }
            return plain(t, indent, line);
        }

        /** A plain scalar beginning with {@code head}, continued by the more indented lines after it. */
        Object plain(String head, int indent, int line) throws FormatException {
            if (head.contains(": ") || head.endsWith(":")) {
                throw error(line, "a plain value cannot hold `: `, which YAML reads as a key; quote the value: \""
                    + head.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            }
            List<String> parts = new ArrayList<>(List.of(head));
            int i = at + 1;
            int last = at;
            while (i < lines.size()) {
                String l = lines.get(i);
                if (l.isBlank()) {
                    parts.add("");
                    i++;
                    continue;
                }
                if (indent(i) <= indent || l.strip().startsWith("#")) break;
                String s = stripComment(l).strip();
                if (keyEnd(s) >= 0) throw error(i, "a plain value cannot go on with a `key: value` line; quote the value, or indent the key as the others");
                parts.add(s);
                last = i;
                i++;
            }
            at = last + 1;
            while (!parts.isEmpty() && parts.get(parts.size() - 1).isEmpty()) parts.remove(parts.size() - 1);
            if (parts.size() == 1) return resolve(head, first + line);
            return fold(parts);
        }

        /** A scalar that makes up a whole node: lines more indented than {@code parent}. */
        Object scalarLines(int indent, int parent) throws FormatException {
            String t = stripComment(lines.get(at).substring(indent)).strip();
            char c = t.charAt(0);
            if (c == '"' || c == '\'') return multilineQuoted(lines.get(at).substring(indent), parent);
            if (c == '[' || c == '{') return value(t, parent, false);
            if (c == '&' || c == '*' || c == '!') throw error(at, "anchors, aliases and tags are not supported in frontmatter");
            return plain(t, parent, at);
        }

        /** A quoted scalar starting at the head of {@code text}, which may go on over the next lines. */
        Object multilineQuoted(String text, int indent) throws FormatException {
            char q = text.charAt(0);
            int line = at;
            StringBuilder raw = new StringBuilder(text);
            while (true) {
                int close = closingQuote(raw.toString(), q);
                if (close >= 0) {
                    String after = stripComment(raw.substring(close + 1)).strip();
                    if (!after.isEmpty()) throw error(at, "unexpected text after the closing quote");
                    at++;
                    return foldQuoted(raw.substring(0, close + 1), q, line);
                }
                at++;
                if (at >= lines.size()) throw error(line, "the quoted string is not closed");
                raw.append('\n').append(lines.get(at));
            }
        }

        static int closingQuote(String s, char q) {
            for (int i = 1; i < s.length(); i++) {
                char c = s.charAt(i);
                if (q == '"' && c == '\\') {
                    i++;
                } else if (c == q) {
                    if (q == '\'' && i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        return i;
                    }
                }
            }
            return -1;
        }

        /** A quoted scalar over one or more lines: line breaks fold to a space, and empty lines to newlines. */
        String foldQuoted(String raw, char q, int line) throws FormatException {
            String[] ls = raw.split("\n", -1);
            if (ls.length == 1) return quoted(raw, line);
            // Fold the line breaks first; escapes are then read from the joined text, where a `\` at the end
            // of a line has already joined it to the next.
            StringBuilder joined = new StringBuilder();
            int empties = 0;
            for (int i = 0; i < ls.length; i++) {
                String part = i == 0 ? ls[i].stripTrailing() : i == ls.length - 1 ? ls[i].stripLeading() : ls[i].strip();
                if (i > 0 && part.isEmpty() && i < ls.length - 1) {
                    empties++;
                    continue;
                }
                if (i > 0) {
                    boolean escaped = q == '"' && joined.length() > 0 && joined.charAt(joined.length() - 1) == '\\'
                        && !escapedBackslash(joined);
                    if (escaped) {
                        joined.setLength(joined.length() - 1);
                    } else if (empties > 0) {
                        // Read as an escape in a double-quoted string, and as it is in a single-quoted one.
                        joined.append((q == '"' ? "\\n" : "\n").repeat(empties));
                    } else {
                        joined.append(' ');
                    }
                }
                empties = 0;
                joined.append(part);
            }
            return quoted(joined.toString(), line);
        }

        static boolean escapedBackslash(StringBuilder s) {
            int n = 0;
            for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) n++;
            return n % 2 == 0;
        }

        /** A one-line quoted scalar, its quotes included, as its text. */
        String quoted(String s, int line) throws FormatException {
            char q = s.charAt(0);
            if (s.length() < 2 || s.charAt(s.length() - 1) != q) throw error(line, "the quoted string is not closed");
            String body = s.substring(1, s.length() - 1);
            if (q == '\'') return body.replace("''", "'");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (++i >= body.length()) throw error(line, "a `\\` ends the string");
                char e = body.charAt(i);
                switch (e) {
                    case '0' -> sb.append('\0');
                    case 'a' -> sb.append('\u0007');
                    case 'b' -> sb.append('\b');
                    case 't', '\t' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'v' -> sb.append('\u000B');
                    case 'f' -> sb.append('\f');
                    case 'r' -> sb.append('\r');
                    case 'e' -> sb.append('\u001B');
                    case ' ' -> sb.append(' ');
                    case '"' -> sb.append('"');
                    case '/' -> sb.append('/');
                    case '\\' -> sb.append('\\');
                    case 'N' -> sb.append('\u0085');
                    case '_' -> sb.append(' ');
                    case 'L' -> sb.append(' ');
                    case 'P' -> sb.append(' ');
                    case 'x', 'u', 'U' -> {
                        int n = e == 'x' ? 2 : e == 'u' ? 4 : 8;
                        String hex = i + 1 + n <= body.length() ? body.substring(i + 1, i + 1 + n) : "";
                        if (hex.isEmpty() || !HEX_DIGITS.matcher(hex).matches()) throw error(line, "a bad `\\" + e + "` escape");
                        int cp = Integer.parseUnsignedInt(hex, 16);
                        if (!Character.isValidCodePoint(cp) || (cp >= 0xD800 && cp <= 0xDFFF)) throw error(line, "a bad `\\" + e + "` escape");
                        sb.appendCodePoint(cp);
                        i += n;
                    }
                    default -> throw error(line, "an unknown escape `\\" + e + "`");
                }
            }
            return sb.toString();
        }

        /** A `|` or `>` block scalar whose header is {@code header}; its lines follow, more indented than {@code indent}. */
        String blockScalar(String header, int indent) throws FormatException {
            int line = at;
            boolean literal = header.charAt(0) == '|';
            char chomp = 'c';
            int explicit = 0;
            for (int i = 1; i < header.length(); i++) {
                char c = header.charAt(i);
                if ((c == '-' || c == '+') && chomp == 'c') {
                    chomp = c;
                } else if (c >= '1' && c <= '9' && explicit == 0) {
                    explicit = c - '0';
                } else {
                    throw error(line, "a bad block scalar header `" + header + "`");
                }
            }
            at++;
            int content = explicit > 0 ? indent + explicit : -1;
            List<String> body = new ArrayList<>();
            while (at < lines.size()) {
                String l = lines.get(at);
                if (l.isBlank()) {
                    body.add("");
                    at++;
                    continue;
                }
                int n = indent(at);
                if (content < 0) {
                    if (n <= indent) break;
                    content = n;
                }
                if (n < content) {
                    if (n > indent) throw error(at, "a line of the block scalar is indented less than its first");
                    break;
                }
                body.add(l.substring(content));
                at++;
            }
            int trailing = 0;
            while (trailing < body.size() && body.get(body.size() - 1 - trailing).isEmpty()) trailing++;
            List<String> kept = body.subList(0, body.size() - trailing);
            StringBuilder sb = new StringBuilder();
            if (literal) {
                sb.append(String.join("\n", kept));
            } else {
                // Folded: a line break between two lines that are not more indented becomes a space, or, with
                // empty lines between them, one newline per empty line; around a more indented line, it stays.
                int i = 0;
                for (; i < kept.size() && kept.get(i).isEmpty(); i++) sb.append('\n');
                String prev = null;
                int empties = 0;
                for (; i < kept.size(); i++) {
                    String l = kept.get(i);
                    if (l.isEmpty()) {
                        empties++;
                        continue;
                    }
                    if (prev != null) {
                        boolean normal = !moreIndented(prev) && !moreIndented(l);
                        sb.append(normal && empties == 0 ? " " : "\n".repeat(normal ? empties : empties + 1));
                    }
                    sb.append(l);
                    prev = l;
                    empties = 0;
                }
            }
            if (kept.isEmpty()) return chomp == '+' ? "\n".repeat(trailing) : "";
            if (chomp == 'c') sb.append('\n');
            if (chomp == '+') sb.append("\n".repeat(trailing + 1));
            return sb.toString();
        }
    }

    static boolean moreIndented(String l) {
        return !l.isEmpty() && (l.charAt(0) == ' ' || l.charAt(0) == '\t');
    }

    private static final Pattern HEX_DIGITS = Pattern.compile("[0-9a-fA-F]+");

    /** A plain scalar's lines, folded: one line break becomes a space, and each empty line a newline. */
    static String fold(List<String> parts) {
        StringBuilder sb = new StringBuilder(parts.get(0));
        int empties = 0;
        for (int i = 1; i < parts.size(); i++) {
            String p = parts.get(i);
            if (p.isEmpty()) {
                empties++;
                continue;
            }
            sb.append(empties == 0 ? " " : "\n".repeat(empties)).append(p);
            empties = 0;
        }
        return sb.toString();
    }

    /** {@code s} without a ` #` comment, minding quotes. */
    static String stripComment(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if ((c == '"' || c == '\'') && (i == 0 || " [{,:".indexOf(s.charAt(i - 1)) >= 0)) {
                quote = c;
            } else if (c == '#' && (i == 0 || s.charAt(i - 1) == ' ')) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    /** A one-line plain scalar's value under YAML 1.2's core schema; {@code line} is its line in the file. */
    static Object resolve(String s, int line) throws FormatException {
        switch (s) {
            case "", "~", "null", "Null", "NULL" -> {
                return null;
            }
            case "true", "True", "TRUE" -> {
                return true;
            }
            case "false", "False", "FALSE" -> {
                return false;
            }
            default -> { }
        }
        if (AMBIGUOUS.matcher(s).matches()) {
            throw new FormatException(line, "YAML 1.1 and 1.2 read `" + s + "` differently, so hosts would too; quote it "
                + "(\"" + s + "\") for text, or write true, false or the number in decimal");
        }
        if (INT.matcher(s).matches()) return new BigInteger(s.startsWith("+") ? s.substring(1) : s);
        if (HEX.matcher(s).matches()) return new BigInteger(s.substring(2), 16);
        if (FLOAT.matcher(s).matches()) return new BigDecimal(s.startsWith("+") ? s.substring(1) : s);
        if (SPECIAL_FLOAT.matcher(s).matches()) {
            throw new FormatException(line, "`" + s + "` is a float JSON cannot hold; quote it to make it text");
        }
        if (s.startsWith("@") || s.startsWith("`") || s.startsWith("%")) {
            throw new FormatException(line, "a plain value cannot begin with `" + s.charAt(0) + "`; quote it");
        }
        return s;
    }

    /** A flow collection, `[a, b]` or `{k: v}`, on one line. */
    private static final class Flow {
        final String s;
        final Reader r;
        final int line;
        int i;

        Flow(String s, Reader r, int line) {
            this.s = s;
            this.r = r;
            this.line = line;
        }

        FormatException error(String message) {
            return r.error(line, message);
        }

        void spaces() {
            while (i < s.length() && s.charAt(i) == ' ') i++;
        }

        void end() throws FormatException {
            spaces();
            if (i < s.length()) throw error("unexpected text after `" + s.substring(0, i).strip() + "`; a flow collection must end its line");
        }

        Object value() throws FormatException {
            return value(false);
        }

        /** The next value; a plain {@code key} is text, as a JSON key is, whatever it looks like. */
        Object value(boolean key) throws FormatException {
            spaces();
            if (i >= s.length()) throw error("the flow collection is not closed; write it on one line");
            char c = s.charAt(i);
            if (c == '[') {
                i++;
                List<Object> list = new ArrayList<>();
                spaces();
                if (i < s.length() && s.charAt(i) == ']') {
                    i++;
                    return list;
                }
                while (true) {
                    list.add(value());
                    spaces();
                    if (i >= s.length()) throw error("the flow sequence is not closed; write it on one line");
                    char d = s.charAt(i++);
                    if (d == ']') return list;
                    if (d != ',') throw error("expected `,` or `]` in a flow sequence");
                    spaces();
                    if (i < s.length() && s.charAt(i) == ']') {
                        i++;
                        return list;
                    }
                }
            }
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                spaces();
                if (i < s.length() && s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    Object k = value(true);
                    spaces();
                    if (i >= s.length() || s.charAt(i) != ':') throw error("expected `:` after a key in a flow mapping");
                    i++;
                    String name = k instanceof String ks ? ks : String.valueOf(k);
                    if (m.containsKey(name)) throw error("the key `" + name + "` appears twice");
                    m.put(name, value());
                    spaces();
                    if (i >= s.length()) throw error("the flow mapping is not closed; write it on one line");
                    char d = s.charAt(i++);
                    if (d == '}') return m;
                    if (d != ',') throw error("expected `,` or `}` in a flow mapping");
                    spaces();
                    if (i < s.length() && s.charAt(i) == '}') {
                        i++;
                        return m;
                    }
                }
            }
            if (c == '"' || c == '\'') {
                int close = Reader.closingQuote(s.substring(i), c);
                if (close < 0) throw error("the quoted string is not closed");
                String q = s.substring(i, i + close + 1);
                i += close + 1;
                return r.quoted(q, line);
            }
            if (c == '&' || c == '*' || c == '!') throw error("anchors, aliases and tags are not supported in frontmatter");
            int start = i;
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d == ',' || d == ']' || d == '}' || d == '[' || d == '{') break;
                if (d == ':' && (i + 1 == s.length() || " ,]}".indexOf(s.charAt(i + 1)) >= 0)) break;
                i++;
            }
            String text = s.substring(start, i).strip();
            return key ? text : resolve(text, r.first + line);
        }
    }
}
