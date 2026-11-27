package alang.util;

import java.math.*;
import java.util.*;

/**
 * A tiny JSON reader/writer without dependencies.
 * Values are: null, Boolean, BigInteger, BigDecimal, Double (read only for a negative zero such as
 * -0.0, which BigDecimal cannot hold; non-finite only when writing), String, List&lt;Object&gt;,
 * Map&lt;String,Object&gt; (insertion ordered).
 */
public final class Json {
    private Json() {}

    // ------------------------------------------------------------------ writing

    /**
     * The value as JSON on one line, with a space after each comma and colon: every --json report and
     * tool call result is printed this way, so an agent pays no tokens for indentation.
     */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean || v instanceof BigInteger || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof BigDecimal d) {
            sb.append(d.toString());
        } else if (v instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                sb.append("null");
            } else if (d == 0) {
                // The long below would drop the sign of -0.0.
                sb.append(Math.copySign(1.0, d) < 0 ? "-0.0" : "0.0");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) (double) d).append(".0");
            } else {
                sb.append(d);
            }
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                write(sb, list.get(i));
            }
            sb.append(']');
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue());
            }
            sb.append('}');
        } else {
            throw new IllegalArgumentException("not a JSON value: " + v.getClass());
        }
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
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ------------------------------------------------------------------ reading

    /** The value of an ASCII hexadecimal digit, or -1: Character.digit would also accept non-ASCII digits. */
    public static int hexDigit(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

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
        if (r.pos != text.length()) throw new ParseException("unexpected trailing characters at offset " + r.pos);
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

        /** JSON whitespace only: Character.isWhitespace would also skip form feeds and Unicode spaces. */
        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
                pos++;
            }
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
                if (c < 0x20) {
                    pos--;
                    throw error("control character U+00" + HEX[c >> 4] + HEX[c & 15] + " in a string must be escaped");
                }
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
                            char unit = hex4();
                            if (Character.isHighSurrogate(unit)) {
                                // A pair is written as two escapes, a high then a low surrogate.
                                if (!s.startsWith("\\u", pos)) throw error("unpaired surrogate escape");
                                pos += 2;
                                char low = hex4();
                                if (!Character.isLowSurrogate(low)) throw error("unpaired surrogate escape");
                                sb.append(unit).append(low);
                            } else if (Character.isLowSurrogate(unit)) {
                                throw error("unpaired surrogate escape");
                            } else {
                                sb.append(unit);
                            }
                        }
                        default -> throw error("bad escape");
                    }
                } else if (Character.isSurrogate(c)) {
                    // Text that reached Java with a lone surrogate would become "?" in UTF-8.
                    if (!Character.isHighSurrogate(c) || pos >= s.length() || !Character.isLowSurrogate(s.charAt(pos))) {
                        pos--;
                        throw error("unpaired surrogate");
                    }
                    sb.append(c).append(s.charAt(pos++));
                } else {
                    sb.append(c);
                }
            }
        }

        /** The four hex digits of a backslash-u escape. */
        char hex4() {
            int code = 0;
            for (int k = 0; k < 4; k++) {
                int d = pos + k < s.length() ? hexDigit(s.charAt(pos + k)) : -1;
                if (d < 0) throw error("bad unicode escape");
                code = code * 16 + d;
            }
            pos += 4;
            return (char) code;
        }

        /** Only ASCII digits: Character.isDigit and BigInteger would also read an Arabic-Indic three as 3. */
        static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        void digits() {
            if (!isDigit(peek())) throw error("bad number: expected a digit");
            while (isDigit(peek())) pos++;
        }

        /** A number exactly as JSON spells it: -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)? */
        Object number() {
            int start = pos;
            boolean negative = peek() == '-';
            if (negative) pos++;
            if (peek() == '0') {
                pos++;
                if (isDigit(peek())) throw error("bad number: leading zeros are not allowed");
            } else {
                digits();
            }
            boolean isFloat = false;
            if (peek() == '.') {
                isFloat = true;
                pos++;
                digits();
            }
            if (peek() == 'e' || peek() == 'E') {
                isFloat = true;
                pos++;
                if (peek() == '+' || peek() == '-') pos++;
                digits();
            }
            if (pos - start > MAX_DIGITS) return longNumber(start, negative, isFloat);
            String t = s.substring(start, pos);
            try {
                if (!isFloat) return new BigInteger(t);
                BigDecimal d = new BigDecimal(t);
                // BigDecimal has no negative zero, so a float argument of -0.0 would arrive as 0.0. (An integer
                // has none either: -0 is the integer 0.)
                return negative && d.signum() == 0 ? (Object) (-0.0) : d;
            } catch (NumberFormatException ex) {
                // An exponent beyond the range of int; no ALang type holds such a number.
                throw error("bad number '" + t + "'");
            }
        }

        /**
         * The most characters of a number that are read exactly. BigInteger and BigDecimal read digits in time
         * quadratic in their count, half a minute for a megabyte of them, before any step budget or timeout applies.
         * A longer number keeps its first MAX_DIGITS significant digits and, for the rest, which are never all 0
         * (trailing zeros go first, without changing the value), a 1 after them. Nothing that reads the number can
         * tell the difference: its count of integer digits stays the same; with at most 20 of them, the most an
         * integer type holds, it is still not an integer, the 1 being a fraction digit; and the nearest f64 or f32 of
         * a decimal number depends only on its first 768 significant digits and on whether any digit after them is
         * not 0.
         */
        static final int MAX_DIGITS = 800;

        /** The number of more than MAX_DIGITS characters from start to pos, scanned already: see MAX_DIGITS. */
        Object longNumber(int start, boolean negative, boolean isFloat) {
            int intStart = negative ? start + 1 : start;
            int intEnd = intStart;
            while (intEnd < pos && isDigit(s.charAt(intEnd))) intEnd++;
            int fracStart = intEnd;
            int fracEnd = intEnd;
            if (fracStart < pos && s.charAt(fracStart) == '.') {
                fracStart++;
                fracEnd = fracStart;
                while (fracEnd < pos && isDigit(s.charAt(fracEnd))) fracEnd++;
            }
            long exp = 0;
            if (fracEnd < pos) {
                int i = fracEnd + 1;
                boolean expNegative = s.charAt(i) == '-';
                if (s.charAt(i) == '+' || s.charAt(i) == '-') i++;
                // Saturated past the range of int, which is all that matters to the check below.
                for (; i < pos && exp <= Integer.MAX_VALUE + 1L; i++) exp = exp * 10 + (s.charAt(i) - '0');
                if (expNegative) exp = -exp;
            }
            int intLen = intEnd - intStart;
            long scale = (long) (fracEnd - fracStart) - exp;
            // As new BigDecimal(text) refuses them: no ALang type holds such a number.
            if ((int) exp != exp || (int) scale != scale) {
                throw error("bad number '" + s.substring(start, start + 20) + "...' (" + (pos - start) + " characters)");
            }
            int total = intLen + (fracEnd - fracStart);
            int first = 0;
            while (first < total && digitAt(first, intStart, intLen, fracStart) == '0') first++;
            if (first == total) {
                if (!isFloat) return BigInteger.ZERO;
                return negative ? (Object) (-0.0) : BigDecimal.valueOf(0, (int) scale);
            }
            int last = total - 1;
            while (digitAt(last, intStart, intLen, fracStart) == '0') last--;
            int end = Math.min(last, first + MAX_DIGITS - 1);
            StringBuilder digits = new StringBuilder(MAX_DIGITS + 2);
            if (negative) digits.append('-');
            for (int k = first; k <= end; k++) digits.append(digitAt(k, intStart, intLen, fracStart));
            int lastKept = end;
            if (end < last) {
                digits.append('1');
                lastKept++;
            }
            // The scale of the digits kept. It only shrinks, by at most the length of the text: below the range of
            // int, the number is above 10^2147483647 either way, and no type holds it.
            long keptScale = Math.max(Integer.MIN_VALUE, (long) lastKept - intLen + 1 - exp);
            return new BigDecimal(new BigInteger(digits.toString()), (int) keptScale);
        }

        /** The k-th digit of a number: its integer digits, then its fraction digits. */
        char digitAt(int k, int intStart, int intLen, int fracStart) {
            return k < intLen ? s.charAt(intStart + k) : s.charAt(fracStart + k - intLen);
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
