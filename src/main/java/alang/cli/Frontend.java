package alang.cli;

import alang.ast.Ast.Import;
import alang.ast.Ast.Item;
import alang.ast.Ast.Program;
import alang.check.CheckResult;
import alang.check.Checker;
import alang.diag.Code;
import alang.diag.Diagnostics;
import alang.diag.Source;
import alang.parse.Parser;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parse + check in one call. The program is the given file and every file it imports, directly or through
 * other imports: each is read once, in the order its first import is reached (depth first), and joined
 * into one program before it is checked.
 */
public final class Frontend {
    private Frontend() {}

    /**
     * The C backend reports a trap in an imported file with its line in the bits above {@link #MAX_LINES}, so
     * an imported file has fewer lines than this, and a program imports at most {@link #MAX_IMPORTED_FILES}.
     */
    static final int MAX_LINES = 1 << 20;

    static final int MAX_IMPORTED_FILES = 2047;

    public record Result(Diagnostics diags, Program program, CheckResult check) {
        public boolean ok() {
            return program != null && !diags.hasErrors();
        }
    }

    public static Result compile(Source source, long maxStack) {
        Diagnostics diags = new Diagnostics(source);
        Program program = load(source, diags);
        if (program == null) return new Result(diags, null, null);
        program.items.addAll(0, Parser.prelude());
        CheckResult check = Checker.check(program, diags, maxStack);
        return new Result(diags, program, check);
    }

    /** A file being read: its parse and the next of its imports to follow. */
    private static final class Pending {
        final Parser.Parsed parsed;
        final String key;
        final Map<String, Import> imported = new HashMap<>();
        int next;

        Pending(Parser.Parsed parsed, String key) {
            this.parsed = parsed;
            this.key = key;
        }
    }

    /** Parses {@code root} and the files it imports; returns the joined program, or null on errors. */
    private static Program load(Source root, Diagnostics diags) {
        Parser.Parsed first = Parser.parse(root, diags, false);
        if (first == null) return null;
        List<Parser.Parsed> parsed = new ArrayList<>(List.of(first));
        Map<String, Source> read = new HashMap<>();
        String rootKey = key(root.path);
        read.put(rootKey, root);
        Deque<Pending> path = new ArrayDeque<>();
        path.push(new Pending(first, rootKey));
        boolean failed = false;
        // The scope of each file: 0 for the main file and the files that join it without `as`; a file imported with
        // `as` has one of its own, which the files it imports without `as` join.
        Map<String, Integer> scopeOf = new HashMap<>();
        Map<String, Integer> ownScope = new HashMap<>();
        Map<Source, Map<String, Integer>> aliases = new HashMap<>();
        Map<Source, Integer> fileScopes = new HashMap<>();
        scopeOf.put(rootKey, 0);
        while (!path.isEmpty()) {
            Pending at = path.peek();
            List<Import> imports = at.parsed.program.imports;
            if (at.next == imports.size()) {
                path.pop();
                continue;
            }
            Import imp = imports.get(at.next++);
            Source from = at.parsed.program.source;
            String problem = invalidPath(imp.path());
            if (problem != null) {
                diags.error(Code.E0702, imp.span(), "invalid import path \"" + imp.path() + "\": " + problem,
                    "import a file by its path relative to this one, with / between directories: import \"lib/text.al\";");
                failed = true;
                continue;
            }
            String file = resolve(from.path, imp.path());
            String key = key(file);
            int want = imp.alias() == null ? scopeOf.get(at.key) : ownScope.computeIfAbsent(key, k -> ownScope.size() + 1);
            if (imp.alias() != null) {
                Map<String, Integer> mine = aliases.computeIfAbsent(from, k -> new HashMap<>());
                if (mine.containsKey(imp.alias())) {
                    diags.error(Code.E0702, imp.aliasSpan(), "`" + imp.alias() + "` already names another import of this file",
                        "give each import its own name after `as`");
                    failed = true;
                    continue;
                }
                mine.put(imp.alias(), want);
            }
            Integer had = scopeOf.get(key);
            if (had != null && had != want) {
                diags.error(Code.E0702, imp.span(), "`" + file + "` is imported " + (imp.alias() == null ? "without" : "with")
                        + " `as` here, but another import puts its names " + (imp.alias() == null ? "under a name" : "among the importer's"),
                    "import a file that several files share with `as` in each of them: import \"" + imp.path() + "\" as name;");
                failed = true;
                continue;
            }
            scopeOf.put(key, want);
            Import before = at.imported.putIfAbsent(key, imp);
            if (before != null) {
                diags.error(Code.E0702, imp.span(), "`" + file + "` is already imported on line " + before.span().line(),
                    "remove this import");
                failed = true;
                continue;
            }
            Pending cycle = onPath(path, key);
            if (cycle != null) {
                diags.error(Code.E0703, imp.span(), "import cycle: " + cycle(path, cycle, file),
                    "files cannot import each other; move what they share into a file that both import");
                failed = true;
                continue;
            }
            if (read.containsKey(key)) continue;  // already read through another import
            if (read.size() - 1 == MAX_IMPORTED_FILES) {
                diags.error(Code.E0702, imp.span(), "a program can import at most " + MAX_IMPORTED_FILES + " files",
                    "join small files into larger ones");
                return null;
            }
            Source src = readFile(file, from, imp, diags);
            if (src == null) {
                failed = true;
                continue;
            }
            read.put(key, src);
            diags.addFile(src);
            Parser.Parsed p = Parser.parse(src, diags, true);
            if (p == null) {
                failed = true;
                continue;
            }
            parsed.add(p);
            fileScopes.put(src, want);
            path.push(new Pending(p, key));
        }
        if (failed || !Parser.checkChains(parsed, diags)) return null;
        if (parsed.size() == 1) return first.program;
        // The imported declarations come first, so that a name declared twice is reported in the file that
        // imports the other, where the clash was made, not in a shared file.
        List<Item> items = new ArrayList<>();
        List<Source> files = new ArrayList<>();
        for (Parser.Parsed p : parsed) {
            if (p != first) items.addAll(p.program.items);
            files.add(p.program.source);
        }
        items.addAll(first.program.items);
        return new Program(root, items, first.program.imports, files, aliases, fileScopes);
    }

    /** Why an import path is not one, or null if it is: relative, with '/' between names, naming a .al file. */
    private static String invalidPath(String path) {
        if (path.indexOf('\\') >= 0) return "write / between directories, on every OS";
        if (path.startsWith("/") || path.length() >= 2 && path.charAt(1) == ':') {
            return "the path must be relative to the importing file, so that the program works wherever it is";
        }
        if (!path.endsWith(".al") || path.endsWith("/.al")) return "it must name a .al file";
        if (path.indexOf('\0') >= 0) return "it contains a NUL character";
        return null;
    }

    /**
     * The path of {@code relative} next to the file {@code from}, as messages show it: from's directory,
     * then the import's names, with "." left out and "dir/.." removed, '/' between names.
     */
    static String resolve(String from, String relative) {
        int slash = Math.max(from.lastIndexOf('/'), from.lastIndexOf(File.separatorChar));
        String dir = slash < 0 ? "" : from.substring(0, slash + 1);
        List<String> names = new ArrayList<>();
        boolean absolute = dir.startsWith("/");
        for (String name : (dir.replace(File.separatorChar, '/') + relative).split("/")) {
            if (name.isEmpty() || name.equals(".")) continue;
            if (name.equals("..") && !names.isEmpty() && !names.get(names.size() - 1).equals("..")) {
                names.remove(names.size() - 1);
            } else if (!(name.equals("..") && absolute && names.isEmpty())) {
                names.add(name);
            }
        }
        return (absolute ? "/" : "") + String.join("/", names);
    }

    /** What makes two paths the same file: the real path where the file exists, else the path itself. */
    private static String key(String path) {
        try {
            return Path.of(path).toRealPath().toString();
        } catch (IOException | InvalidPathException | SecurityException e) {
            return Path.of(path).toAbsolutePath().normalize().toString();
        }
    }

    private static Pending onPath(Deque<Pending> path, String key) {
        for (Pending p : path) {
            if (p.key.equals(key)) return p;
        }
        return null;
    }

    /** "a.al imports b.al, which imports a.al": from the file that starts the cycle to the import that closes it. */
    private static String cycle(Deque<Pending> path, Pending start, String file) {
        List<String> names = new ArrayList<>();
        boolean in = false;
        for (var it = path.descendingIterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p == start) in = true;
            if (in) names.add(p.parsed.program.source.path);
        }
        names.add(file);
        StringBuilder sb = new StringBuilder(names.get(0));
        for (int i = 1; i < names.size(); i++) sb.append(i == 1 ? " imports " : ", which imports ").append(names.get(i));
        return sb.toString();
    }

    /** Reads an imported file as UTF-8, or reports why it cannot and returns null. */
    private static Source readFile(String file, Source from, Import imp, Diagnostics diags) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Path.of(file));
        } catch (NoSuchFileException | InvalidPathException e) {
            diags.error(Code.E0701, imp.span(), "cannot find `" + file + "`",
                "the path is relative to the importing file, " + from.path + "; check the name and the directory");
            return null;
        } catch (IOException | SecurityException e) {
            diags.error(Code.E0701, imp.span(), "cannot read `" + file + "`: " + e.getMessage(), null);
            return null;
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            diags.error(Code.E0701, imp.span(), "`" + file + "` is not UTF-8 text", "save it as UTF-8");
            return null;
        }
        Source src = new Source(file, text);
        if (src.lineCount() >= MAX_LINES) {
            diags.error(Code.E0702, imp.span(), "`" + file + "` has " + src.lineCount() + " lines; an imported file has at most "
                + (MAX_LINES - 1), "split it into smaller files");
            return null;
        }
        return src;
    }
}
