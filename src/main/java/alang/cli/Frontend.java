package alang.cli;

import alang.ast.Ast.*;
import alang.check.*;
import alang.diag.*;
import alang.parse.Parser;
import alang.util.Utf8;
import java.io.*;
import java.nio.file.*;
import java.util.*;

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
        return compile(source, maxStack, null);
    }

    /**
     * As {@link #compile(Source, long)}, and {@code imported}, unless null, gets the path of each file the
     * program imports that was looked for, with its text, or null for one that could not be read as text:
     * what `serve --watch` watches.
     */
    static Result compile(Source source, long maxStack, Map<String, String> imported) {
        Diagnostics diags = new Diagnostics(source);
        Program program = load(source, diags, imported);
        if (program == null) return new Result(diags, null, null);
        program.items.addAll(0, Parser.prelude());
        CheckResult check = Checker.check(program, diags, maxStack);
        return new Result(diags, program, check);
    }

    /** A file being read: its parse and the next of its imports to follow. */
    private static final class Pending {
        final Parser.Parsed parsed;
        final String key;
        /** The package the file belongs to, or null for a file of the program itself. */
        final Pkg pkg;
        final Map<String, Import> imported = new HashMap<>();
        int next;

        Pending(Parser.Parsed parsed, String key, Pkg pkg) {
            this.parsed = parsed;
            this.key = key;
            this.pkg = pkg;
        }
    }

    /** A package in use: its name, its directory as messages show it and as a real path, and its hash. */
    private record Pkg(String name, String dir, String dirKey, String hash) {
        boolean holds(String fileKey) {
            return fileKey.startsWith(dirKey + File.separator);
        }
    }

    /** The packages of one program being loaded: their manifests and hashes, each looked at once. */
    private static final class PackageSet {
        final Map<String, Optional<Packages.Manifest>> manifests = new HashMap<>();
        final Map<String, String> hashes = new HashMap<>();
        /** Each package name in use, with the copy of it that the program uses. */
        final Map<String, Pkg> byName = new HashMap<>();
    }

    /** Parses {@code root} and the files it imports; returns the joined program, or null on errors. */
    private static Program load(Source root, Diagnostics diags, Map<String, String> imported) {
        Parser.Parsed first = Parser.parse(root, diags, false);
        if (first == null) return null;
        List<Parser.Parsed> parsed = new ArrayList<>(List.of(first));
        Map<String, Source> read = new HashMap<>();
        String rootKey = key(root.path);
        read.put(rootKey, root);
        Deque<Pending> path = new ArrayDeque<>();
        path.push(new Pending(first, rootKey, null));
        PackageSet packages = new PackageSet();
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
            Pkg pkg = at.pkg;
            String file;
            if (imp.path().startsWith("@")) {
                pkg = findPackage(imp, at, root, packages, diags, imported);
                if (pkg == null) {
                    failed = true;
                    continue;
                }
                file = resolve(pkg.dir() + "/" + Packages.MANIFEST, imp.path().substring(imp.path().indexOf('/') + 1));
            } else {
                String problem = invalidPath(imp.path());
                if (problem != null) {
                    diags.error(Code.E0702, imp.span(), "invalid import path \"" + imp.path() + "\": " + problem,
                        "import a file by its path relative to this one, with / between directories: import \"lib/text.al\";");
                    failed = true;
                    continue;
                }
                file = resolve(from.path, imp.path());
            }
            String key = key(file);
            if (pkg != null && !pkg.holds(key)) {
                diags.error(Code.E0702, imp.span(), "`" + file + "` is outside package `" + pkg.name() + "`, in " + pkg.dir(),
                    "a package imports its own files by their relative paths, and other packages with import \"@name/file.al\";");
                failed = true;
                continue;
            }
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
            // Before the scopes: a file that imports one of its importers is a cycle, however each imports.
            Pending cycle = onPath(path, key);
            if (cycle != null) {
                diags.error(Code.E0703, imp.span(), "import cycle: " + cycle(path, cycle, file),
                    "files cannot import each other; move what they share into a file that both import");
                failed = true;
                continue;
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
            if (read.containsKey(key)) continue;  // already read through another import
            if (read.size() - 1 == MAX_IMPORTED_FILES) {
                diags.error(Code.E0702, imp.span(), "a program can import at most " + MAX_IMPORTED_FILES + " files",
                    "join small files into larger ones");
                return null;
            }
            Source src = readFile(file, from, imp, diags, imported);
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
            path.push(new Pending(p, key, pkg));
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

    /**
     * The package an import "@name/file.al" names, found in the manifest nearest to the importing file (for a
     * file of a package, that package's own), checked against its pinned hash; or null, reported, when it
     * cannot be used.
     */
    private static Pkg findPackage(Import imp, Pending at, Source root, PackageSet set, Diagnostics diags,
                                   Map<String, String> imported) {
        String spec = imp.path();
        int slash = spec.indexOf('/');
        String name = slash < 0 ? spec.substring(1) : spec.substring(1, slash);
        String rest = slash < 0 ? "" : spec.substring(slash + 1);
        String problem = !Packages.NAME.matcher(name).matches()
            ? "`" + name + "` is not a package name: lowercase letters, digits and _, starting with a letter"
            : rest.isEmpty() ? "name a file of the package after its name" : invalidPath(rest);
        if (problem == null && (rest.equals("..") || resolve("", rest).startsWith("../"))) problem = "the path leaves the package";
        if (problem != null) {
            diags.error(Code.E0702, imp.span(), "invalid import path \"" + spec + "\": " + problem,
                "import a file of a package by the package's name and the file's path in it: import \"@text/fmt.al\";");
            return null;
        }
        String from = at.parsed.program.source.path;
        String stopAt = at.pkg == null ? null : at.pkg.dir();
        Packages.Manifest m = set.manifests.computeIfAbsent(from.substring(0, Math.max(0, from.lastIndexOf('/') + 1)) + "|" + stopAt,
            k -> Optional.ofNullable(Packages.nearest(from, stopAt))).orElse(null);
        if (m == null) {
            diags.error(Code.E0704, imp.span(), "no " + Packages.MANIFEST + " declares package `" + name + "`"
                    + (at.pkg == null ? "" : ": package `" + at.pkg.name() + "` has no " + Packages.MANIFEST),
                "declare it in " + Packages.MANIFEST + " beside the program, or a directory above it: "
                    + "{\"dependencies\": {\"" + name + "\": {\"path\": \"vendor/" + name + "\"}}}");
            return null;
        }
        if (imported != null) imported.put(m.file(), m.text());
        if (m.error() != null) {
            diags.error(Code.E0705, imp.span(), "invalid " + m.file() + ": " + m.error(), "see `alang explain E0705` for the format");
            return null;
        }
        Packages.Dependency d = m.deps().get(name);
        if (d == null) {
            diags.error(Code.E0704, imp.span(), m.file() + " declares no package `" + name + "`",
                m.deps().isEmpty() ? "add it to \"dependencies\": \"" + name + "\": {\"path\": \"vendor/" + name + "\"}"
                    : "it declares " + String.join(", ", m.deps().keySet().stream().map(n -> "`" + n + "`").toList())
                        + "; add `" + name + "` to its \"dependencies\" or fix the name");
            return null;
        }
        String dir = m.dirOf(d);
        if (dir.isEmpty()) dir = ".";
        if (!Files.isDirectory(Path.of(dir))) {
            diags.error(Code.E0705, imp.span(), "package `" + name + "`: " + m.file() + " gives the directory `" + dir
                    + "`, which does not exist",
                "vendor the package there (copy it, or add it as a git submodule), or fix its \"path\"; the toolchain never downloads");
            return null;
        }
        String dirKey = key(dir);
        String hash = set.hashes.get(dirKey);
        if (hash == null) {
            try {
                hash = Packages.hash(dir);
            } catch (IOException | UncheckedIOException | SecurityException e) {
                diags.error(Code.E0705, imp.span(), "package `" + name + "`: cannot read " + dir + ": " + e.getMessage(), null);
                return null;
            }
            set.hashes.put(dirKey, hash);
        }
        String lock = "run: alang deps " + root.path + " --lock";
        if (d.sha256() == null) {
            diags.error(Code.E0706, imp.span(), "package `" + name + "` is not pinned in " + m.file(),
                lock + " (or add \"sha256\": \"" + hash + "\" to it), so that the program builds from these files only");
            return null;
        }
        if (!d.sha256().equals(hash)) {
            diags.error(Code.E0706, imp.span(), "package `" + name + "` in " + dir + " is not the one pinned in " + m.file()
                    + ": its files have changed",
                "restore its files, or, if the change is wanted, " + lock + " (its sha256 is now " + hash + ")");
            return null;
        }
        Pkg seen = set.byName.get(name);
        if (seen != null && !seen.hash().equals(hash)) {
            diags.error(Code.E0706, imp.span(), "two different copies of package `" + name + "`: " + seen.dir() + " and " + dir,
                "a program uses one copy of each package; vendor the same version of `" + name + "` in both places");
            return null;
        }
        // Identical copies, vendored by two packages, are one package: its files are read once.
        if (seen != null) return seen;
        Pkg pkg = new Pkg(name, dir, dirKey, hash);
        set.byName.put(name, pkg);
        return pkg;
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

    /**
     * Reads an imported file as UTF-8, or reports why it cannot and returns null. {@code imported}, unless null,
     * gets its text, or null when it cannot be read as text.
     */
    private static Source readFile(String file, Source from, Import imp, Diagnostics diags, Map<String, String> imported) {
        if (imported != null) imported.put(file, null);
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
        String text = Utf8.decode(bytes);
        if (text == null) {
            diags.error(Code.E0701, imp.span(), "`" + file + "` is not UTF-8 text", "save it as UTF-8");
            return null;
        }
        if (imported != null) imported.put(file, text);
        Source src = new Source(file, text);
        if (src.lineCount() >= MAX_LINES) {
            diags.error(Code.E0702, imp.span(), "`" + file + "` has " + src.lineCount() + " lines; an imported file has at most "
                + (MAX_LINES - 1), "split it into smaller files");
            return null;
        }
        return src;
    }
}
