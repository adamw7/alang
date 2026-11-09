package alang.interp;

import java.io.ByteArrayOutputStream;

/**
 * Where {@link Format} writes the text of a value. print() streams it to the output through a fixed
 * buffer and append() writes it straight into the target str, so the text of a large value is never
 * built in memory first: it can be dozens of times larger than the value (an f64 prints up to 317
 * characters).
 */
public interface ByteSink {
    void write(int b);

    void write(byte[] b, int off, int len);

    default void writeBytes(byte[] b) {
        write(b, 0, b.length);
    }

    /** A sink that collects everything in {@code buf}. */
    static ByteSink of(ByteArrayOutputStream buf) {
        return new ByteSink() {
            @Override
            public void write(int b) {
                buf.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                buf.write(b, off, len);
            }
        };
    }
}
