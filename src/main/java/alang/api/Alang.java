package alang.api;

import alang.cli.Frontend;
import alang.cli.Main;
import alang.diag.Source;
import alang.util.Utf8;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The entry point: compiles ALang programs, and gives the toolchain's version and the guide for agents. */
public final class Alang {
    private Alang() {}

    /** The toolchain's version, e.g. "0.6.0". */
    public static String version() {
        return Main.VERSION;
    }

    /**
     * AGENTS.md, the guide to writing ALang that the jar carries: put it in a model's system prompt so the
     * model can write programs that compile.
     */
    public static String agentsGuide() {
        try (InputStream in = Alang.class.getResourceAsStream("/alang/AGENTS.md")) {
            if (in == null) throw new IllegalStateException("the jar does not carry /alang/AGENTS.md");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Compiles the program in {@code file}, and the files it imports, with the default options. */
    public static Compilation compile(Path file) throws IOException {
        return compile(file, CompileOptions.defaults());
    }

    /**
     * Compiles the program in {@code file}. Its imports are read relative to it. Throws an IOException when
     * the file cannot be read, and an IllegalArgumentException when it is not UTF-8 text; a missing import is a
     * diagnostic instead.
     */
    public static Compilation compile(Path file, CompileOptions options) throws IOException {
        String text = Utf8.decode(Files.readAllBytes(file));
        if (text == null) throw new IllegalArgumentException(file + " is not UTF-8 text");
        return compile(file.toString(), text, options);
    }

    /** Compiles {@code text}, named {@code path} in diagnostics, with the default options. */
    public static Compilation compile(String path, String text) {
        return compile(path, text, CompileOptions.defaults());
    }

    /**
     * Compiles {@code text}, a program held in memory, named {@code path} in diagnostics. An import in it is read
     * from disk, relative to {@code path}; a program without imports touches no file.
     */
    public static Compilation compile(String path, String text, CompileOptions options) {
        if (path == null || text == null || options == null) throw new NullPointerException("path, text and options are required");
        Frontend.Result r = Frontend.compile(new Source(path, text), options.maxStackBytes());
        return new Compilation(r);
    }
}
