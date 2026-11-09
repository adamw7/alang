package alang.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny JSON reader/writer without dependencies.
 * Values are: null, Boolean, BigInteger, BigDecimal, Double (non-finite only when writing),
 * String, List&lt;Object&gt;, Map&lt;String,Object&gt; (insertion ordered).
 */
public final class Json {
    private Json() {}

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, -1, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 2, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, int indent, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof BigInteger || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof BigDecimal d) {
            sb.append(d.toString());
        } else if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) (double) d).append(".0");
            } else {
                sb.append(d);
            }
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(indent >= 0 ? "," : ", ");
                newline(sb, indent, depth + 1);
                write(sb, list.get(i), indent, depth + 1);
            }
            newline(sb, indent, depth);
            sb.append(']');
        } else if (v instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(indent >= 0 ? "," : ", ");
                first = false;
                newline(sb, indent, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), indent, depth + 1);
            }
            newline(sb, indent, depth);
            sb.append('}');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + v.getClass());
        }
    }

    private static void newline(StringBuilder sb, int indent, int depth) {
        if (indent < 0) return;
        sb.append('\n');
        sb.append(" ".repeat(indent * depth));
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u00").append(HEX[c >> 4]).append(HEX[c & 15]);
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Convenience builder for ordered objects: obj("a", 1, "b", 2). */
    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ reading

    public static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ParseException(String message) {
            super(message);
        }
    }

    public static Object parse(String text) {
        Reader r = new Reader(text);
        r.skipWs();
        Object v = r.value();
        r.skipWs();
        if (r.pos != text.length()) {
            throw new ParseException("unexpected trailing characters at offset " + r.pos);
        }
        return v;
    }

    private static final class Reader {
        /** Parsing recurses, so nesting is limited; no ALang value nests nearly this deep. */
        static final int MAX_DEPTH = 256;

        final String s;
        int pos;
        int depth;

        Reader(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        ParseException error(String what) {
            return new ParseException(what + " at offset " + pos);
        }

        Object value() {
            if (pos >= s.length()) throw error("unexpected end of JSON");
            char c = s.charAt(pos);
            if (c == '{' || c == '[') {
                if (++depth > MAX_DEPTH) throw error("values nested more than " + MAX_DEPTH + " levels deep");
                Object v = c == '{' ? object() : array();
                depth--;
                return v;
            }
            return switch (c) {
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield number();
                    throw error("unexpected character '" + c + "'");
                }
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, pos)) throw error("invalid literal");
            pos += word.length();
            return v;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') throw error("expected string key");
                String k = string();
                if (m.containsKey(k)) throw error("duplicate key \"" + k + "\"");
                skipWs();
                expect(':');
                skipWs();
                m.put(k, value());
                skipWs();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw error("expected ',' or '}'");
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++;
            skipWs();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                char c = next();
                if (c == ']') return l;
                if (c != ',') throw error("expected ',' or ']'");
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw error("unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw error("unterminated escape");
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            int code = 0;
                            for (int k = 0; k < 4; k++) {
                                int d = pos + k < s.length() ? Character.digit(s.charAt(pos + k), 16) : -1;
                                // Character.digit also accepts non-ASCII digits; JSON allows only [0-9a-fA-F].
                                if (d < 0 || s.charAt(pos + k) > 'f') throw error("bad unicode escape");
                                code = code * 16 + d;
                            }
                            sb.append((char) code);
                            pos += 4;
                        }
                        default -> throw error("bad escape");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            boolean isFloat = false;
            if (pos < s.length() && s.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) pos++;
            }
            String t = s.substring(start, pos);
            try {
                return isFloat ? new BigDecimal(t) : new BigInteger(t);
            } catch (NumberFormatException ex) {
                throw error("bad number '" + t + "'");
            }
        }

        char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }

        char next() {
            if (pos >= s.length()) throw error("unexpected end of JSON");
            return s.charAt(pos++);
        }

        void expect(char c) {
            if (next() != c) {
                pos--;
                throw error("expected '" + c + "'");
            }
        }
    }
}
