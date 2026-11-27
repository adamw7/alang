package alang.interp;

import alang.interp.Values.*;
import alang.util.Json;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * The encodings, digests and number text of the standard library in the reference interpreter: group_digits,
 * base64, hex and URL encoding, sha256, crc32, and the date and time text of format_date, parse_datetime and
 * format_datetime. The C prelude implements each one the same way, byte for byte.
 */
final class Codecs {
    private Codecs() {}

    private static final byte[] BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEX_LOWER = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEX_UPPER = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    /** The bytes of a str, or of an array or list of u8. */
    static byte[] bytesOf(Object v) {
        if (v instanceof StrV s) return Arrays.copyOf(s.b, s.len);
        Object[] items = v instanceof ListV l ? l.items : ((ArrV) v).items;
        int n = v instanceof ListV l ? l.len : items.length;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) (long) (Long) items[i];
        return out;
    }

    /** The decimal digits of {@code digits} (a magnitude, with a leading '-' if negative) with sep between groups of three. */
    static byte[] groupDigits(String digits, byte[] sep) {
        boolean neg = digits.startsWith("-");
        String d = neg ? digits.substring(1) : digits;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (neg) out.write('-');
        for (int i = 0; i < d.length(); i++) {
            if (i > 0 && (d.length() - i) % 3 == 0) out.writeBytes(sep);
            out.write(d.charAt(i));
        }
        return out.toByteArray();
    }

    static byte[] base64Encode(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < in.length; i += 3) {
            int n = Math.min(3, in.length - i);
            int v = (in[i] & 0xff) << 16 | (n > 1 ? (in[i + 1] & 0xff) << 8 : 0) | (n > 2 ? in[i + 2] & 0xff : 0);
            out.write(BASE64[v >> 18 & 63]);
            out.write(BASE64[v >> 12 & 63]);
            out.write(n > 1 ? BASE64[v >> 6 & 63] : '=');
            out.write(n > 2 ? BASE64[v & 63] : '=');
        }
        return out.toByteArray();
    }

    private static int sextet(int c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    }

    /** The bytes Base64 text encodes, or null: a multiple of 4 characters, with at most two = and only at the end. */
    static byte[] base64Decode(StrV s) {
        if (s.len % 4 != 0) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.len; i += 4) {
            boolean last = i + 4 == s.len;
            int pad = 0;
            int v = 0;
            for (int j = 0; j < 4; j++) {
                int c = s.b[i + j] & 0xff;
                if (c == '=' && last && j >= 2) {
                    pad++;
                    v <<= 6;
                    continue;
                }
                int x = sextet(c);
                if (x < 0 || pad > 0) return null;
                v = v << 6 | x;
            }
            out.write(v >> 16 & 0xff);
            if (pad < 2) out.write(v >> 8 & 0xff);
            if (pad < 1) out.write(v & 0xff);
        }
        return out.toByteArray();
    }

    static byte[] hexEncode(byte[] in) {
        byte[] out = new byte[in.length * 2];
        for (int i = 0; i < in.length; i++) {
            out[2 * i] = HEX_LOWER[(in[i] & 0xff) >> 4];
            out[2 * i + 1] = HEX_LOWER[in[i] & 15];
        }
        return out;
    }

    /** The bytes that pairs of hexadecimal digits stand for, or null. */
    static byte[] hexDecode(StrV s) {
        if (s.len % 2 != 0) return null;
        byte[] out = new byte[s.len / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Json.hexDigit(s.b[2 * i] & 0xff);
            int lo = Json.hexDigit(s.b[2 * i + 1] & 0xff);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    private static boolean unreserved(int c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~';
    }

    static byte[] urlEncode(StrV s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.len; i++) {
            int c = s.b[i] & 0xff;
            if (unreserved(c)) {
                out.write(c);
            } else {
                out.write('%');
                out.write(HEX_UPPER[c >> 4]);
                out.write(HEX_UPPER[c & 15]);
            }
        }
        return out.toByteArray();
    }

    /** s with each %XX replaced by its byte, or null if a % is not followed by two hexadecimal digits. */
    static byte[] urlDecode(StrV s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.len; i++) {
            int c = s.b[i] & 0xff;
            if (c != '%') {
                out.write(c);
                continue;
            }
            if (i + 2 >= s.len) return null;
            int hi = Json.hexDigit(s.b[i + 1] & 0xff);
            int lo = Json.hexDigit(s.b[i + 2] & 0xff);
            if (hi < 0 || lo < 0) return null;
            out.write(hi << 4 | lo);
            i += 2;
        }
        return out.toByteArray();
    }

    static byte[] sha256(byte[] in) {
        try {
            return hexEncode(MessageDigest.getInstance("SHA-256").digest(in));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static long crc32(byte[] in) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(in);
        return crc.getValue();
    }

    // =================================================================== dates and times

    static boolean isLeapYear(long y) {
        return y % 4 == 0 && (y % 100 != 0 || y % 400 == 0);
    }

    /** The days of month m (1 to 12) of year y. */
    static long daysInMonth(long y, long m) {
        return m == 2 ? (isLeapYear(y) ? 29 : 28) : m == 4 || m == 6 || m == 9 || m == 11 ? 30 : 31;
    }

    /** add_months for a day within MIN_DAYS..MAX_DAYS; Long.MIN_VALUE if the result leaves the years 1 to 9999. */
    static long addMonths(long days, long n) {
        long[] ymd = Stdlib.dateFromDays(days);
        // 9999 years are fewer than 120000 months: any n beyond that leaves them.
        if (n > 120000 || n < -120000) return Long.MIN_VALUE;
        long t = ymd[0] * 12 + ymd[1] - 1 + n;
        long y = Math.floorDiv(t, 12);
        long m = Math.floorMod(t, 12) + 1;
        if (y < 1 || y > 9999) return Long.MIN_VALUE;
        return Stdlib.daysFromDate(y, m, Math.min(ymd[2], daysInMonth(y, m)));
    }

    private static void digits(byte[] out, int at, long v, int width) {
        for (int i = width - 1; i >= 0; i--) {
            out[at + i] = (byte) ('0' + v % 10);
            v /= 10;
        }
    }

    /** YYYY-MM-DD of a day within MIN_DAYS..MAX_DAYS. */
    static byte[] formatDate(long days) {
        long[] ymd = Stdlib.dateFromDays(days);
        byte[] out = "0000-00-00".getBytes(StandardCharsets.US_ASCII);
        digits(out, 0, ymd[0], 4);
        digits(out, 5, ymd[1], 2);
        digits(out, 8, ymd[2], 2);
        return out;
    }

    /** The first second of year 1, and the last of year 9999, since 1970-01-01T00:00:00Z. */
    static final long MIN_SECONDS = Stdlib.MIN_DAYS * 86400;
    static final long MAX_SECONDS = Stdlib.MAX_DAYS * 86400 + 86399;

    /** YYYY-MM-DDTHH:MM:SSZ of a second within MIN_SECONDS..MAX_SECONDS. */
    static byte[] formatDateTime(long seconds) {
        long days = Math.floorDiv(seconds, 86400);
        long s = Math.floorMod(seconds, 86400);
        byte[] out = "0000-00-00T00:00:00Z".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(formatDate(days), 0, out, 0, 10);
        digits(out, 11, s / 3600, 2);
        digits(out, 14, s / 60 % 60, 2);
        digits(out, 17, s % 60, 2);
        return out;
    }

    /** The number of the {@code n} digits at {@code at}, or -1 if one is not a digit or they do not fit. */
    private static long number(StrV s, int at, int n) {
        if (at + n > s.len) return -1;
        long v = 0;
        for (int i = at; i < at + n; i++) {
            int c = s.b[i] & 0xff;
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return v;
    }

    /** parse_datetime: the seconds since 1970-01-01T00:00:00Z, or Long.MIN_VALUE for text that is not a date and time. */
    static long parseDateTime(StrV s) {
        if (s.len < 16) return Long.MIN_VALUE;
        long days = Stdlib.parseDate(new StrV(Arrays.copyOf(s.b, 10), 10));
        if (days == Long.MIN_VALUE) return Long.MIN_VALUE;
        int sep = s.b[10] & 0xff;
        if (sep != 'T' && sep != 't' && sep != ' ') return Long.MIN_VALUE;
        long h = number(s, 11, 2);
        if (h < 0 || h > 23 || (s.b[13] & 0xff) != ':') return Long.MIN_VALUE;
        long mi = number(s, 14, 2);
        if (mi < 0 || mi > 59) return Long.MIN_VALUE;
        int at = 16;
        long sec = 0;
        if (at < s.len && (s.b[at] & 0xff) == ':') {
            sec = number(s, at + 1, 2);
            if (sec < 0 || sec > 59) return Long.MIN_VALUE;
            at += 3;
            if (at < s.len && (s.b[at] & 0xff) == '.') {
                int from = ++at;
                while (at < s.len && (s.b[at] & 0xff) >= '0' && (s.b[at] & 0xff) <= '9') at++;
                if (at == from) return Long.MIN_VALUE;
            }
        }
        long offset = 0;
        if (at < s.len) {
            int z = s.b[at] & 0xff;
            if (z == 'Z' || z == 'z') {
                at++;
            } else if (z == '+' || z == '-') {
                long oh = number(s, at + 1, 2);
                if (oh < 0 || oh > 23 || at + 3 >= s.len || (s.b[at + 3] & 0xff) != ':') return Long.MIN_VALUE;
                long om = number(s, at + 4, 2);
                if (om < 0 || om > 59) return Long.MIN_VALUE;
                offset = (z == '+' ? 1 : -1) * (oh * 3600 + om * 60);
                at += 6;
            } else {
                return Long.MIN_VALUE;
            }
        }
        if (at != s.len) return Long.MIN_VALUE;
        return days * 86400 + h * 3600 + mi * 60 + sec - offset;
    }
}
