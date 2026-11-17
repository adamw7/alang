package alang.cli;

import alang.ast.Ast.Program;
import alang.diag.Source;
import alang.util.Utf8;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * `alang serve --watch`: the files of a program, with what they held when it was last compiled, so that the
 * server can see them change and compile them again. It reads files and nothing else; the server's timer
 * calls {@link #poll} from one thread.
 */
final class Reloader {
    private final String main;
    private final long maxStack;
    /**
     * Each file the last compile looked for, the main file first, with what it held then: null for one it
     * could not read as text, such as an import that does not exist yet.
     */
    private Map<String, String> compiled;
    /** What the files held at the last poll, if that was a change from {@link #compiled}; else null. */
    private Map<String, String> changing;

    /** Watches the files of {@code program}, as compiled with a memory budget of {@code maxStack}. */
    Reloader(Program program, long maxStack) {
        this.main = program.source.path;
        this.maxStack = maxStack;
        compiled = new LinkedHashMap<>();
        for (Source s : program.files) compiled.put(s.path, s.text);
    }

    /**
     * The program compiled again, when its files have changed since it was last compiled and the change has
     * settled: two polls in a row found the same text, so a file an editor is still writing is not compiled
     * half written; {@code log} gets its warnings. Null otherwise, and also when the new text has errors or
     * the main file cannot be read: {@code log} then gets the diagnostics, or why, and the server keeps the
     * program it has.
     */
    Program poll(PrintStream log) {
        Map<String, String> now = new LinkedHashMap<>();
        for (String path : compiled.keySet()) now.put(path, read(path));
        if (now.equals(compiled)) {
            changing = null;
            return null;
        }
        if (!now.equals(changing)) {
            changing = now;
            return null;
        }
        changing = null;
        String text = now.get(main);
        if (text == null) {
            compiled = now;  // tried again when the file is back
            log.print("alang: cannot read " + main + " as UTF-8 text; still serving the program loaded before\n");
            return null;
        }
        Map<String, String> imported = new LinkedHashMap<>();
        Frontend.Result r = Frontend.compile(new Source(main, text), maxStack, imported);
        compiled = new LinkedHashMap<>();
        compiled.put(main, text);
        compiled.putAll(imported);
        log.print(r.diags().renderText());
        if (!r.ok()) {
            log.print("alang: " + main + " has " + r.diags().errorCount() + " error(s); still serving the program loaded before\n");
            return null;
        }
        return r.program();
    }

    /** The text of a file, as {@link Frontend} reads it: null when it is missing, unreadable or not UTF-8. */
    private static String read(String path) {
        try {
            return Utf8.decode(Files.readAllBytes(Path.of(path)));
        } catch (IOException | InvalidPathException | SecurityException e) {
            return null;
        }
    }
}
