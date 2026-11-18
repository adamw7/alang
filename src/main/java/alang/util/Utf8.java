package alang.util;

import java.nio.*;
import java.nio.charset.*;

/**
 * Strict UTF-8 decoding. A lenient decoder puts U+FFFD in place of each byte that is not UTF-8, so text that
 * differs would come out the same; these refuse it instead.
 */
public final class Utf8 {
    private Utf8() {}

    /** The text of {@code b}, or null if it is not UTF-8. */
    public static String decode(byte[] b) {
        return decode(b, b.length);
    }

    /** The text of {@code b[0, len)}, or null if it is not UTF-8. */
    public static String decode(byte[] b, int len) {
        CharBuffer out = CharBuffer.allocate(len);
        return decode(b, len, out) < 0 ? out.flip().toString() : null;
    }

    /** The offset of the first byte of {@code b[0, len)} that is not UTF-8, or -1 if there is none. */
    public static int firstBad(byte[] b, int len) {
        return decode(b, len, CharBuffer.allocate(len));
    }

    private static int decode(byte[] b, int len, CharBuffer out) {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(b, 0, len);
        CoderResult r = dec.decode(in, out, true);
        if (!r.isError()) r = dec.flush(out);
        return r.isError() ? in.position() : -1;
    }
}
