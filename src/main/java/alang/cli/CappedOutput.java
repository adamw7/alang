package alang.cli;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Captures a program's output for JSON (`call`, `run --json`) and for failing tests, keeping only the
 * first {@code limit} bytes. The step budget bounds how often a program prints, not how much, so
 * without the cap a tool printing a 4 KB line a million times exhausted the toolchain's memory.
 */
final class CappedOutput extends OutputStream {
    private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
    private final int limit;
    private long total;

    CappedOutput(int limit) {
        this.limit = limit;
    }

    @Override
    public void write(int b) {
        if (kept.size() < limit) kept.write(b);
        total++;
    }

    @Override
    public void write(byte[] b, int off, int len) {
        int k = Math.min(len, limit - kept.size());
        if (k > 0) kept.write(b, off, k);
        total += len;
    }

    boolean truncated() {
        return total > kept.size();
    }

    long total() {
        return total;
    }

    /** The kept bytes as text, without a character that the cap cut in half. */
    String text() {
        byte[] b = kept.toByteArray();
        int end = b.length;
        if (truncated()) {
            // Step back over the continuation bytes (10xxxxxx) of a final UTF-8 sequence, and drop the
            // sequence if its lead byte announces more bytes than were kept.
            int start = end;
            while (start > 0 && end - start < 3 && (b[start - 1] & 0xC0) == 0x80) start--;
            if (start > 0) {
                int lead = b[start - 1] & 0xFF;
                int need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
                if (end - (start - 1) < need) end = start - 1;
            }
        }
        return new String(b, 0, end, StandardCharsets.UTF_8);
    }
}
