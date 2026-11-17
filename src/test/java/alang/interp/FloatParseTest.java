package alang.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.diag.Code;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * f64(s) promises the nearest f64 to the decimal text, the same on every backend, so it converts only text
 * that one correctly rounded multiplication or division turns into that f64. Java's Double.parseDouble is
 * correctly rounded for all text, so on everything is_float accepts the two must agree bit for bit.
 */
class FloatParseTest {

    private static Values.StrV str(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        return new Values.StrV(b, b.length);
    }

    /** Decimal text built from the bits of {@code x}: a sign, 1 to 18 digits, zeros at either end, and a point. */
    private static String text(long x) {
        StringBuilder sb = new StringBuilder();
        long r = x;
        int sign = (int) (r & 3);
        r >>>= 2;
        if (sign == 1) sb.append('-');
        if (sign == 2) sb.append('+');
        int zerosBefore = (int) (r % 4);
        r /= 4;
        int digits = 1 + (int) (r % 18);
        r /= 18;
        int zerosAfter = (int) (r % 30);
        r /= 30;
        StringBuilder ds = new StringBuilder("0".repeat(zerosBefore));
        long d = r;
        for (int i = 0; i < digits; i++) {
            ds.append((char) ('0' + (int) Long.remainderUnsigned(d, 10)));
            d = Long.divideUnsigned(d, 10);
            if (d == 0) d = x >>> 7 | 1;
        }
        ds.append("0".repeat(zerosAfter));
        int point = (int) Long.remainderUnsigned(x >>> 40, ds.length() + 2) - 1;
        if (point >= 0 && point <= ds.length()) ds.insert(point, '.');
        return sb.append(ds).toString();
    }

    @Test
    void acceptedTextConvertsToTheNearestF64() {
        long x = 2024;
        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 300_000; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            String s = text(x);
            if (s.equals(".") || s.equals("-.") || s.equals("+.")) continue;
            Values.StrV v = str(s);
            if (Stdlib.isFloat(v, new Meter(0))) {
                accepted++;
                long want = Double.doubleToRawLongBits(Double.parseDouble(s));
                assertEquals(want, Double.doubleToRawLongBits(Stdlib.parseFloat(v, null, new Meter(0))), s);
            } else {
                rejected++;
                Trap t = assertThrows(Trap.class, () -> Stdlib.parseFloat(v, null, new Meter(0)), s);
                assertEquals(Code.R0009, t.code, s);
            }
        }
        assertTrue(accepted > 100_000 && rejected > 10_000, accepted + " accepted, " + rejected + " rejected");
    }

    @Test
    void textWithAnExponentConvertsToTheNearestF64() {
        long x = 7;
        int accepted = 0;
        for (int i = 0; i < 200_000; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            String s = text(x);
            if (s.equals(".") || s.equals("-.") || s.equals("+.")) continue;
            int exponent = (int) Long.remainderUnsigned(x >>> 20, 81) - 40;
            String withExponent = s + ((x >>> 50 & 1) == 0 ? "e" : "E") + ((x >>> 51 & 1) == 0 && exponent >= 0 ? "+" : "")
                + exponent;
            Values.StrV v = str(withExponent);
            if (Stdlib.isFloat(v, new Meter(0))) {
                accepted++;
                long want = Double.doubleToRawLongBits(Double.parseDouble(withExponent));
                assertEquals(want, Double.doubleToRawLongBits(Stdlib.parseFloat(v, null, new Meter(0))), withExponent);
            } else {
                Trap t = assertThrows(Trap.class, () -> Stdlib.parseFloat(v, null, new Meter(0)), withExponent);
                assertEquals(Code.R0009, t.code, withExponent);
            }
        }
        assertTrue(accepted > 50_000, accepted + " accepted");
    }

    @Test
    void fifteenSignificantDigitsAlwaysConvert() {
        for (String s : new String[] {"123456789012345", "0.123456789012345", "1234567.89012345", "-999999999999999",
            "1e0".replace("e0", ""), "0.0000000000000000000001", "1" + "0".repeat(22), "123456789012345" + "0".repeat(22),
            "9007199254740992", "3.", ".5", "-0", "0.000", "+19.99", "1e5", "1E5", "2.5e-3", "-6.02e+23", "0e999999999", "1e22",
            "123456789012345e22", "1e-22", ".5E1"}) {
            Values.StrV v = str(s);
            assertTrue(Stdlib.isFloat(v, new Meter(0)), s);
            assertEquals(Double.doubleToRawLongBits(Double.parseDouble(s)), Double.doubleToRawLongBits(Stdlib.parseFloat(v, null, new Meter(0))), s);
        }
    }

    @Test
    void otherTextIsRejected() {
        for (String s : new String[] {"", ".", "-", "+", "1e", "1e+", "1E-", "e5", ".e5", "1e5.0", "1e5e2", "1e 5", "1e-23",
            "1e39", "1e9999999999999999999", "1.2.3", " 1", "1 ", "0x10", "inf", "nan", "1_000",
            "9007199254740993", "12345678901234567", "0.00000000000000000000001", "1" + "0".repeat(39), "--1", "1-"}) {
            assertFalse(Stdlib.isFloat(str(s), new Meter(0)), s);
        }
    }
}
