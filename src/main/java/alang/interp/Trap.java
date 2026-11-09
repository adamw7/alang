package alang.interp;

import alang.diag.Code;
import alang.diag.Span;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A runtime error. ALang has no undefined behavior: every failure is a trap with a code. */
public final class Trap extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public final Code code;
    public final Span span;
    /** Call stack, innermost first: "fn name (line:col)". */
    public final List<String> trace = new ArrayList<>();

    /** The exact message: the text of a str value in it need not be valid UTF-8. */
    private final byte[] bytes;

    public Trap(Code code, Span span, String message) {
        super(message, null, false, false);
        this.code = code;
        this.span = span;
        this.bytes = message.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A trap whose message holds bytes of str values, which the C backend writes as they are. {@link
     * #getMessage()} then has U+FFFD for bytes that are not UTF-8; {@link #messageBytes()} has them all.
     */
    public Trap(Code code, Span span, byte[] message) {
        super(new String(message, StandardCharsets.UTF_8), null, false, false);
        this.code = code;
        this.span = span;
        this.bytes = message.clone();
    }

    /** The message exactly as the C backend writes it. */
    public byte[] messageBytes() {
        return bytes.clone();
    }
}
