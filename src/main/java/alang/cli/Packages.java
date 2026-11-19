package alang.cli;

import alang.util.Json;
import alang.util.Utf8;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Packages: directories of .al files that a program declares in an {@code alang.json} manifest and imports as
 * {@code import "@name/file.al";}. The toolchain never fetches anything: a package is already on disk, vendored
 * next to the program (copied, or a git submodule), and its manifest entry pins it with the SHA-256 of its
 * files, so the program means the same wherever it is built.
 *
 * <pre>
 * {
 *   "name": "report",
 *   "version": "1.0.0",
 *   "dependencies": {
 *     "text": {"path": "vendor/text", "sha256": "3f1c..."}
 *   }
 * }
 * </pre>
 */
final class Packages {
    private Packages() {}

    static final String MANIFEST = "alang.json";

    /** A package name: lowercase letters, digits and _, starting with a letter. */
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    /** A dependency as its manifest declares it; {@code sha256} is null when it is not pinned. */
    record Dependency(String name, String path, String sha256) {}

    /**
     * A manifest: {@code file} as messages show it, {@code text} as read, and its dependencies in the order
     * written, or {@code error} saying why it is not a valid manifest.
     */
    record Manifest(String file, String text, String name, String version, Map<String, Dependency> deps, String error) {
        /** The directory of a dependency, as messages show it. */
        String dirOf(Dependency d) {
            return Frontend.resolve(file, d.path());
        }
    }

    /**
     * The manifest nearest to the file {@code from}, as messages show its path: in its directory or the closest
     * one above that has an alang.json. With {@code stopAt} (the directory of a package, as messages show it)
     * only that directory's manifest counts, so that a package never picks up the manifest of the program
     * that vendors it. Null when there is none.
     */
    static Manifest nearest(String from, String stopAt) {
        if (stopAt != null) {
            String file = Frontend.resolve(stopAt + "/" + MANIFEST, MANIFEST);
            return Files.isRegularFile(Path.of(file)) ? read(file) : null;
        }
        Path dir = Path.of(from).toAbsolutePath().normalize().getParent();
        for (int up = 0; dir != null; up++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(MANIFEST))) return read(Frontend.resolve(from, "../".repeat(up) + MANIFEST));
        }
        return null;
    }

    /** Reads and validates a manifest. */
    static Manifest read(String file) {
        String text;
        try {
            text = Utf8.decode(Files.readAllBytes(Path.of(file)));
        } catch (IOException | InvalidPathException | SecurityException e) {
            return invalid(file, null, "cannot read it: " + e.getMessage());
        }
        if (text == null) return invalid(file, null, "it is not UTF-8 text");
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (Json.ParseException e) {
            return invalid(file, text, "it is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> top)) return invalid(file, text, "it must hold a JSON object");
        for (Object k : top.keySet()) {
            if (!Set.of("name", "version", "dependencies").contains(k)) {
                return invalid(file, text, "unknown key \"" + k + "\"; a manifest has \"name\", \"version\" and \"dependencies\"");
            }
        }
        String name = null;
        if (top.containsKey("name")) {
            if (!(top.get("name") instanceof String s) || !NAME.matcher(s).matches()) {
                return invalid(file, text, "\"name\" must be a package name: lowercase letters, digits and _, starting with a letter");
            }
            name = s;
        }
        String version = null;
        if (top.containsKey("version")) {
            if (!(top.get("version") instanceof String s) || s.isEmpty()) return invalid(file, text, "\"version\" must be a string");
            version = s;
        }
        Map<String, Dependency> deps = new LinkedHashMap<>();
        Object ds = top.get("dependencies");
        if (ds != null && !(ds instanceof Map<?, ?>)) return invalid(file, text, "\"dependencies\" must be an object");
        if (ds instanceof Map<?, ?> dm) {
            for (Map.Entry<?, ?> e : dm.entrySet()) {
                String dep = (String) e.getKey();
                if (!NAME.matcher(dep).matches()) {
                    return invalid(file, text, "\"" + dep + "\" is not a package name: lowercase letters, digits and _, starting with a letter");
                }
                if (!(e.getValue() instanceof Map<?, ?> d)) return invalid(file, text, "dependency \"" + dep + "\" must be an object");
                for (Object k : d.keySet()) {
                    if (!Set.of("path", "sha256").contains(k)) {
                        return invalid(file, text, "dependency \"" + dep + "\" has an unknown key \"" + k + "\"; it has \"path\" and \"sha256\"");
                    }
                }
                if (!(d.get("path") instanceof String path) || path.isEmpty()) {
                    return invalid(file, text, "dependency \"" + dep + "\" needs a \"path\": its directory, relative to " + MANIFEST);
                }
                String problem = invalidDir(path);
                if (problem != null) return invalid(file, text, "the path of dependency \"" + dep + "\": " + problem);
                Object sha = d.get("sha256");
                if (sha != null && !(sha instanceof String s && HEX.matcher(s).matches())) {
                    return invalid(file, text, "the \"sha256\" of dependency \"" + dep + "\" must be 64 lowercase hex digits");
                }
                deps.put(dep, new Dependency(dep, path, (String) sha));
            }
        }
        return new Manifest(file, text, name, version, deps, null);
    }

    private static Manifest invalid(String file, String text, String why) {
        return new Manifest(file, text, null, null, Map.of(), why);
    }

    /** Why a dependency's path is not a relative directory with '/' between names, or null if it is. */
    private static String invalidDir(String path) {
        if (path.indexOf('\\') >= 0) return "write / between directories, on every OS";
        if (path.startsWith("/") || path.length() >= 2 && path.charAt(1) == ':') {
            return "it must be relative to " + MANIFEST + ", so that the program works wherever it is";
        }
        if (path.indexOf('\0') >= 0) return "it contains a NUL character";
        return null;
    }

    /**
     * The SHA-256 of a package directory, as 64 lowercase hex digits: of each .al file and alang.json in it and
     * its subdirectories, in the order of their paths relative to it (with '/'), each as that path, a NUL byte,
     * its length in decimal, a NUL byte and its bytes, with each CRLF read as LF, so that a checkout that turns
     * line ends into CRLF (git on Windows) has the same hash. Other files do not count, so notes and build output can sit
     * beside the code.
     */
    static String hash(String dir) throws IOException {
        Path root = Path.of(dir);
        List<String> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(root)) {
            s.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".al") || name.equals(MANIFEST)) {
                    files.add(root.relativize(p).toString().replace(File.separatorChar, '/'));
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        Collections.sort(files);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String f : files) {
            byte[] bytes = lf(Files.readAllBytes(root.resolve(f)));
            md.update(f.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(Long.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            md.update((byte) 0);
            md.update(bytes);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** The bytes with each CR LF pair turned into LF. */
    private static byte[] lf(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        for (int i = 0; i < b.length; i++) {
            if (b[i] != '\r' || i + 1 == b.length || b[i + 1] != '\n') out.write(b[i]);
        }
        return out.toByteArray();
    }

    /** The manifest's text with {@code pins} (dependency name to hash) written in, laid out two spaces deep. */
    @SuppressWarnings("unchecked")
    static String withPins(Manifest m, Map<String, String> pins) {
        Map<String, Object> top = (Map<String, Object>) Json.parse(m.text());
        Map<String, Object> deps = (Map<String, Object>) top.get("dependencies");
        for (Map.Entry<String, String> e : pins.entrySet()) {
            ((Map<String, Object>) deps.get(e.getKey())).put("sha256", e.getValue());
        }
        StringBuilder sb = new StringBuilder();
        pretty(sb, top, "");
        return sb.append('\n').toString();
    }

    private static void pretty(StringBuilder sb, Object v, String indent) {
        if (!(v instanceof Map<?, ?> map) || map.isEmpty()) {
            sb.append(Json.write(v));
            return;
        }
        // A dependency fits on one line; the manifest and its dependencies get a line each.
        boolean flat = map.values().stream().noneMatch(x -> x instanceof Map<?, ?> || x instanceof List<?>);
        if (flat && indent.length() >= 4) {
            sb.append(Json.write(v));
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            sb.append(indent).append("  ");
            Json.quote(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            pretty(sb, e.getValue(), indent + "  ");
            sb.append(++i < map.size() ? ",\n" : "\n");
        }
        sb.append(indent).append('}');
    }
}
