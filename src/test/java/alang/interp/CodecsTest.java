package alang.interp;

import static org.junit.jupiter.api.Assertions.*;

import alang.interp.Values.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The encodings, digests and dates of the standard library, against the JDK's own implementations of the same
 * standards (RFC 4648 Base64, RFC 3986 percent-encoding, SHA-256, CRC-32 and the proleptic Gregorian calendar
 * of java.time), on fixed cases and on seeded random input.
 */
class CodecsTest {

    /** xorshift64*: the tests' generators must be seeded explicitly (TestConventionsArchitectureTest). */
    private long state = 0x9E3779B97F4A7C15L;

    private int next(int bound) {
        state ^= state >>> 12;
        state ^= state << 25;
        state ^= state >>> 27;
        return (int) Long.remainderUnsigned(state * 0x2545F4914F6CDD1DL, bound);
    }

    private byte[] randomBytes(int max) {
        byte[] b = new byte[next(max + 1)];
        for (int i = 0; i < b.length; i++) b[i] = (byte) next(256);
        return b;
    }

    private static StrV str(byte[] b) {
        return new StrV(b.clone(), b.length);
    }

    private static StrV str(String s) {
        return str(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String ascii(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    @Test
    void bytes_of_reads_a_str_a_list_and_an_array() {
        assertArrayEquals(new byte[] {'a', 'b'}, Codecs.bytesOf(new StrV(new byte[] {'a', 'b', 'c'}, 2)));
        assertArrayEquals(new byte[] {1, (byte) 255}, Codecs.bytesOf(new ListV(new Object[] {1L, 255L, 7L}, 2)));
        assertArrayEquals(new byte[] {0, 9}, Codecs.bytesOf(new ArrV(new Object[] {0L, 9L})));
    }

    @Test
    void group_digits_separates_thousands_and_keeps_the_sign() {
        byte[] comma = {','};
        assertEquals("0", ascii(Codecs.groupDigits("0", comma)));
        assertEquals("999", ascii(Codecs.groupDigits("999", comma)));
        assertEquals("1,000", ascii(Codecs.groupDigits("1000", comma)));
        assertEquals("-12,345,678", ascii(Codecs.groupDigits("-12345678", comma)));
        assertEquals("9_223_372_036_854_775_807", ascii(Codecs.groupDigits(Long.toString(Long.MAX_VALUE), new byte[] {'_'})));
        assertEquals("1 . 000", ascii(Codecs.groupDigits("1000", " . ".getBytes(StandardCharsets.US_ASCII))));
        assertEquals("100000", ascii(Codecs.groupDigits("100000", new byte[0])));
    }

    @Test
    void base64_agrees_with_the_jdk_and_round_trips() {
        for (int i = 0; i < 5_000; i++) {
            byte[] b = randomBytes(40);
            byte[] enc = Codecs.base64Encode(b);
            assertArrayEquals(Base64.getEncoder().encode(b), enc);
            assertArrayEquals(b, Codecs.base64Decode(str(enc)));
        }
        assertEquals("", ascii(Codecs.base64Encode(new byte[0])));
        assertEquals("Zm9vYmFy", ascii(Codecs.base64Encode("foobar".getBytes(StandardCharsets.US_ASCII))));
        assertEquals("Zm8=", ascii(Codecs.base64Encode("fo".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void base64_decode_refuses_what_is_not_padded_base64() {
        for (String bad : new String[] {"Zm9", "Zm9v=", "Z===", "=Zm9", "Zm=v", "Zm9v Zm9v", "Zm9-", "Zm9_", "Zg==Zg==", "Zm9\n"}) {
            assertNull(Codecs.base64Decode(str(bad)), bad);
        }
        assertArrayEquals(new byte[0], Codecs.base64Decode(str("")));
        assertArrayEquals("f".getBytes(StandardCharsets.US_ASCII), Codecs.base64Decode(str("Zg==")));
        // Only the first len bytes of the buffer count.
        assertArrayEquals("fo".getBytes(StandardCharsets.US_ASCII), Codecs.base64Decode(new StrV("Zm8=garbage".getBytes(StandardCharsets.US_ASCII), 4)));
    }

    @Test
    void hex_agrees_with_the_jdk_and_reads_either_case() {
        HexFormat hex = HexFormat.of();
        for (int i = 0; i < 5_000; i++) {
            byte[] b = randomBytes(40);
            byte[] enc = Codecs.hexEncode(b);
            assertEquals(hex.formatHex(b), ascii(enc));
            assertArrayEquals(b, Codecs.hexDecode(str(enc)));
            assertArrayEquals(b, Codecs.hexDecode(str(ascii(enc).toUpperCase(Locale.ROOT))));
        }
        assertNull(Codecs.hexDecode(str("abc")));
        assertNull(Codecs.hexDecode(str("0g")));
        assertNull(Codecs.hexDecode(str(" 0")));
        assertArrayEquals(new byte[0], Codecs.hexDecode(str("")));
    }

    @Test
    void url_encoding_escapes_all_but_the_unreserved_characters() {
        String unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        assertEquals(unreserved, ascii(Codecs.urlEncode(str(unreserved))));
        assertEquals("a%20b%2Fc%3F%26%3D%25%2B", ascii(Codecs.urlEncode(str("a b/c?&=%+"))));
        assertEquals("%C3%A9", ascii(Codecs.urlEncode(str("é".getBytes(StandardCharsets.UTF_8)))));
        for (int i = 0; i < 5_000; i++) {
            byte[] b = randomBytes(30);
            byte[] enc = Codecs.urlEncode(str(b));
            for (byte c : enc) assertTrue(unreserved.indexOf(c) >= 0 || c == '%', ascii(enc));
            assertArrayEquals(b, Codecs.urlDecode(str(enc)));
            // The JDK's decoder reads '+' as a space; the encoder never writes one, so on its output they agree.
            assertEquals(ascii(b), URLDecoder.decode(ascii(enc), StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    void url_decode_refuses_a_percent_without_two_hex_digits() {
        assertEquals("a+b c", ascii(Codecs.urlDecode(str("a+b%20c"))));
        assertEquals("ÿ", ascii(Codecs.urlDecode(str("%fF"))));
        for (String bad : new String[] {"%", "%2", "a%2", "%zz", "%2g", "100%"}) {
            assertNull(Codecs.urlDecode(str(bad)), bad);
        }
    }

    @Test
    void sha256_and_crc32_agree_with_known_values_and_the_jdk() throws Exception {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ascii(Codecs.sha256(new byte[0])));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ascii(Codecs.sha256("abc".getBytes(StandardCharsets.US_ASCII))));
        assertEquals(0xCBF43926L, Codecs.crc32("123456789".getBytes(StandardCharsets.US_ASCII)));
        assertEquals(0L, Codecs.crc32(new byte[0]));
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < 500; i++) {
            byte[] b = randomBytes(200);
            assertEquals(HexFormat.of().formatHex(md.digest(b)), ascii(Codecs.sha256(b)));
            long crc = Codecs.crc32(b);
            assertTrue(crc >= 0 && crc <= 0xFFFFFFFFL);
        }
    }

    // =================================================================== dates

    @Test
    void days_and_dates_agree_with_java_time_over_the_whole_range() {
        // Every day of the first and last years, and of 1900, 1970, 2000 and 2100, plus random ones.
        List<Long> days = new ArrayList<>();
        for (long d = Stdlib.MIN_DAYS; d < Stdlib.MIN_DAYS + 800; d++) days.add(d);
        for (long d = Stdlib.MAX_DAYS - 800; d <= Stdlib.MAX_DAYS; d++) days.add(d);
        for (int y : new int[] {1900, 1970, 2000, 2100}) {
            long first = LocalDate.of(y, 1, 1).toEpochDay();
            for (long d = first - 1; d <= first + 366; d++) days.add(d);
        }
        for (int i = 0; i < 20_000; i++) days.add(Stdlib.MIN_DAYS + next((int) (Stdlib.MAX_DAYS - Stdlib.MIN_DAYS + 1)));
        for (long d : days) {
            LocalDate date = LocalDate.ofEpochDay(d);
            long[] ymd = Stdlib.dateFromDays(d);
            assertArrayEquals(new long[] {date.getYear(), date.getMonthValue(), date.getDayOfMonth()}, ymd, date.toString());
            assertEquals(d, Stdlib.daysFromDate(ymd[0], ymd[1], ymd[2]), date.toString());
            assertEquals(date.toString(), ascii(Codecs.formatDate(d)));
            assertEquals(d, Stdlib.parseDate(str(date.toString())));
            assertEquals(date.isLeapYear(), Codecs.isLeapYear(date.getYear()));
            assertEquals(date.lengthOfMonth(), Codecs.daysInMonth(date.getYear(), date.getMonthValue()));
        }
        assertEquals(LocalDate.of(1, 1, 1).toEpochDay(), Stdlib.MIN_DAYS);
        assertEquals(LocalDate.of(9999, 12, 31).toEpochDay(), Stdlib.MAX_DAYS);
    }

    @Test
    void parse_date_refuses_dates_that_do_not_exist_or_are_not_yyyy_mm_dd() {
        for (String bad : new String[] {"2026-02-29", "2100-02-29", "2026-13-01", "2026-00-10", "2026-04-31", "0000-01-01",
                "2026-1-01", "2026/01/01", "26-01-01", "2026-01-011", "2026-01-0a", "+026-01-01", ""}) {
            assertEquals(Long.MIN_VALUE, Stdlib.parseDate(str(bad)), bad);
        }
        assertEquals(LocalDate.of(2000, 2, 29).toEpochDay(), Stdlib.parseDate(str("2000-02-29")));
    }

    @Test
    void add_months_clamps_to_the_last_day_as_java_time_does() {
        for (int i = 0; i < 20_000; i++) {
            long d = Stdlib.MIN_DAYS + next((int) (Stdlib.MAX_DAYS - Stdlib.MIN_DAYS + 1));
            long n = next(4001) - 2000;
            LocalDate expected = LocalDate.ofEpochDay(d).plusMonths(n);
            long got = Codecs.addMonths(d, n);
            if (expected.getYear() < 1 || expected.getYear() > 9999) {
                assertEquals(Long.MIN_VALUE, got, LocalDate.ofEpochDay(d) + " + " + n);
            } else {
                assertEquals(expected.toEpochDay(), got, LocalDate.ofEpochDay(d) + " + " + n);
            }
        }
        assertEquals(LocalDate.of(2026, 2, 28).toEpochDay(), Codecs.addMonths(LocalDate.of(2026, 1, 31).toEpochDay(), 1));
        assertEquals(LocalDate.of(2024, 2, 29).toEpochDay(), Codecs.addMonths(LocalDate.of(2023, 12, 31).toEpochDay(), 2));
        assertEquals(Long.MIN_VALUE, Codecs.addMonths(Stdlib.MAX_DAYS, 1));
        assertEquals(Long.MIN_VALUE, Codecs.addMonths(Stdlib.MIN_DAYS, -1));
        assertEquals(Long.MIN_VALUE, Codecs.addMonths(0, Long.MAX_VALUE));
        assertEquals(Long.MIN_VALUE, Codecs.addMonths(0, Long.MIN_VALUE));
    }

    private static final java.time.format.DateTimeFormatter ISO_SECONDS =
        java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);

    @Test
    void format_datetime_is_the_iso_instant_of_each_second() {
        long range = Codecs.MAX_SECONDS - Codecs.MIN_SECONDS + 1;
        for (int i = 0; i < 20_000; i++) {
            long s = Codecs.MIN_SECONDS + Math.floorMod(((long) next(Integer.MAX_VALUE) << 20) ^ next(1 << 20), range);
            String expected = LocalDateTime.ofEpochSecond(s, 0, ZoneOffset.UTC).format(ISO_SECONDS);
            assertEquals(expected, ascii(Codecs.formatDateTime(s)));
            assertEquals(s, Codecs.parseDateTime(str(Codecs.formatDateTime(s))));
        }
        assertEquals("0001-01-01T00:00:00Z", ascii(Codecs.formatDateTime(Codecs.MIN_SECONDS)));
        assertEquals("9999-12-31T23:59:59Z", ascii(Codecs.formatDateTime(Codecs.MAX_SECONDS)));
        assertEquals("1969-12-31T23:59:59Z", ascii(Codecs.formatDateTime(-1)));
    }

    private static long utc(int y, int mo, int d, int h, int mi, int s) {
        return LocalDateTime.of(y, mo, d, h, mi, s).toEpochSecond(ZoneOffset.UTC);
    }

    @Test
    void parse_datetime_reads_iso_8601_with_offsets() {
        long t = utc(2026, 11, 27, 10, 30, 0);
        assertEquals(t, Codecs.parseDateTime(str("2026-11-27T10:30:00Z")));
        assertEquals(t, Codecs.parseDateTime(str("2026-11-27T10:30Z")));
        assertEquals(t, Codecs.parseDateTime(str("2026-11-27T10:30")));
        assertEquals(t, Codecs.parseDateTime(str("2026-11-27t10:30:00z")));
        assertEquals(t, Codecs.parseDateTime(str("2026-11-27 10:30:00")));
        assertEquals(t, Codecs.parseDateTime(str("2026-11-27T10:30:00.999999Z")), "fractions of a second are dropped");
        assertEquals(t - 3600, Codecs.parseDateTime(str("2026-11-27T10:30:00+01:00")));
        assertEquals(t + 5 * 3600 + 1800, Codecs.parseDateTime(str("2026-11-27T10:30:00-05:30")));
        assertEquals(t, Codecs.parseDateTime(str("2026-11-27T10:30:00-00:00")));
        // An offset may move the instant into the previous or the next day, or past the years 1 to 9999.
        assertEquals(utc(2026, 11, 27, 0, 1, 0), Codecs.parseDateTime(str("2026-11-28T00:00:00+23:59")));
        assertEquals(utc(2026, 11, 28, 23, 59, 0), Codecs.parseDateTime(str("2026-11-28T00:00:00-23:59")));
        assertEquals(Codecs.MIN_SECONDS - 3600, Codecs.parseDateTime(str("0001-01-01T00:00:00+01:00")));
        assertEquals(Codecs.MAX_SECONDS, Codecs.parseDateTime(str("9999-12-31T23:59:59Z")));
        for (String s : new String[] {"2026-11-27T10:30:00+01:00", "2024-02-29T12:00:00-07:00", "1969-12-31T23:59:59+05:45"}) {
            assertEquals(OffsetDateTime.parse(s).toEpochSecond(), Codecs.parseDateTime(str(s)), s);
        }
    }

    @Test
    void parse_datetime_refuses_text_that_is_not_a_date_and_time() {
        String[] bad = {
            "", "2026-11-27", "2026-11-27T", "2026-11-27T10", "2026-11-27T1:30", "2026-11-27T24:00:00Z",
            "2026-11-27T10:60:00Z", "2026-11-27T10:30:60Z", "2026-11-27X10:30:00Z", "2026-02-30T10:30:00Z",
            "2026-11-27T10:30:00.Z", "2026-11-27T10:30:00+1:00", "2026-11-27T10:30:00+01", "2026-11-27T10:30:00+0100",
            "2026-11-27T10:30:00+24:00", "2026-11-27T10:30:00+01:60", "2026-11-27T10:30:00ZZ", "2026-11-27T10:30:00 ",
            "2026-11-27T10-30-00Z", "2026-11-27T10:30:0Z", "2026-11-27T10:30:00+01:00 ", "2026-11-27T10:30.5Z",
        };
        for (String s : bad) {
            assertEquals(Long.MIN_VALUE, Codecs.parseDateTime(str(s)), s);
        }
    }
}
