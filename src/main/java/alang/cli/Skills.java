package alang.cli;

import alang.util.Json;
import alang.util.Utf8;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * The skills `alang serve --skills DIR` serves alongside the tools, as the MCP Skills extension
 * ({@link #EXTENSION}) defines: Agent Skills (agentskills.io), each a directory with a SKILL.md, whose files
 * are resources at {@code skill://<skill-path>/<file-path>}, listed with skills/list, described one at a time
 * with skills/get, read with resources/read, and browsed with resources/directory/read.
 *
 * <p>A catalogue is a snapshot: every file is read when it is loaded, and what is listed, with its digest, is
 * what a read returns until another catalogue replaces it (with --watch). Loading checks each skill as the
 * extension asks, so a skill that a host would refuse stops the server from starting instead: frontmatter
 * with a `name` that is its directory's name and a `description`, at most {@link #MAX_FILES} files and
 * {@link #MAX_BYTES} bytes.
 */
final class Skills {
    /** The extension's identifier, under which initialize declares it. */
    static final String EXTENSION = "io.modelcontextprotocol/skills";

    /** The most files, SKILL.md included, and bytes a skill may have: what every host must load. */
    static final int MAX_FILES = 512;
    static final long MAX_BYTES = 16L * 1024 * 1024;

    static final String SKILL_MD = "SKILL.md";
    static final String SCHEME = "skill://";
    static final String DIRECTORY = "inode/directory";

    /** No skills: what a server without --skills serves. */
    static final Skills NONE = new Skills(new TreeMap<>(), new TreeMap<>());

    /** A skill name, as the Agent Skills specification has it: lowercase letters, digits and single hyphens. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** Skills that cannot be served, and why. */
    static final class InvalidException extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidException(String message) {
            super(message);
        }
    }

    /** A file of a skill: its URI, its name (the last segment), MIME type and bytes, and their SHA-256. */
    record Resource(String uri, String name, String mimeType, byte[] bytes, String digest) {}

    /** A skill: the URI of its SKILL.md, its frontmatter, and every file under its directory, in URI order. */
    record Skill(String uri, Map<String, Object> frontmatter, List<Resource> files) {
        String name() {
            return (String) frontmatter.get("name");
        }

        String description() {
            return (String) frontmatter.get("description");
        }
    }

    /** Every file served, by URI, and every skill, by the URI of its SKILL.md. */
    private final SortedMap<String, Resource> files;
    private final SortedMap<String, Skill> skills;
    /** Each directory, by URI, with its direct children: their URIs, and whether each is a directory. */
    private final SortedMap<String, SortedMap<String, Boolean>> directories = new TreeMap<>();

    private Skills(SortedMap<String, Resource> files, SortedMap<String, Skill> skills) {
        this.files = Collections.unmodifiableSortedMap(files);
        this.skills = Collections.unmodifiableSortedMap(skills);
        for (String uri : files.keySet()) {
            String child = uri;
            boolean dir = false;
            for (int slash = uri.lastIndexOf('/'); slash > SCHEME.length(); slash = uri.lastIndexOf('/', slash - 1)) {
                String parent = uri.substring(0, slash);
                directories.computeIfAbsent(parent, k -> new TreeMap<>()).put(child, dir);
                child = parent;
                dir = true;
            }
        }
    }

    /**
     * The skills in {@code dirs}. Each is a skill itself, served as {@code skill://<its name>/...}, when it holds
     * a SKILL.md, and otherwise a tree of skills, each directory in it that holds a SKILL.md being one, at its
     * path in the tree: {@code skill://billing/refunds/SKILL.md} for {@code DIR/billing/refunds/SKILL.md}. Files
     * and directories whose names begin with a dot are left out, as are files in no skill. A symbolic link is
     * refused, so that only what is under each directory is ever served.
     */
    static Skills load(List<String> dirs) throws InvalidException {
        SortedMap<String, Resource> files = new TreeMap<>();
        Map<String, String> from = new HashMap<>();
        // Each skill directory's URI, with the SKILL.md file it was found by.
        SortedMap<String, String> skillDirs = new TreeMap<>();
        for (String dir : dirs) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new InvalidException(dir + " is not a directory");
            }
            Path absolute = root.toAbsolutePath().normalize();
            List<String> prefix = new ArrayList<>();
            if (Files.isRegularFile(root.resolve(SKILL_MD), LinkOption.NOFOLLOW_LINKS)) {
                if (absolute.getFileName() == null) throw new InvalidException(dir + " holds a SKILL.md but has no name to serve it under");
                prefix.add(absolute.getFileName().toString());
            }
            for (Map.Entry<List<String>, Path> e : walk(root).entrySet()) {
                List<String> segments = new ArrayList<>(prefix);
                segments.addAll(e.getKey());
                String uri = uri(segments);
                String path = e.getValue().toString();
                String earlier = from.put(uri, path);
                if (earlier != null) throw new InvalidException(uri + " is served twice: from " + earlier + " and from " + path);
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(e.getValue());
                } catch (IOException | RuntimeException x) {
                    throw new InvalidException("cannot read " + path + ": " + x.getMessage());
                }
                String name = segments.get(segments.size() - 1);
                files.put(uri, new Resource(uri, name, mimeType(name, bytes), bytes, digest(bytes)));
                if (name.equals(SKILL_MD) && segments.size() > 1) skillDirs.put(uri.substring(0, uri.length() - SKILL_MD.length() - 1), path);
            }
        }
        SortedMap<String, Skill> skills = new TreeMap<>();
        SortedMap<String, Resource> served = new TreeMap<>();
        for (Map.Entry<String, String> e : skillDirs.entrySet()) {
            String root = e.getKey();
            Resource md = files.get(root + "/" + SKILL_MD);
            String where = e.getValue();
            String dirName = decode(root.substring(root.lastIndexOf('/') + 1));
            Map<String, Object> frontmatter = frontmatter(md.bytes(), where, dirName);
            List<Resource> own = new ArrayList<>(files.subMap(root + "/", root + "0").values());  // '0' follows '/'
            long bytes = 0;
            for (Resource r : own) bytes += r.bytes().length;
            if (own.size() > MAX_FILES) {
                throw new InvalidException("the skill " + md.uri() + " has " + own.size() + " files; a skill may have at most "
                    + MAX_FILES + ", SKILL.md included, for every host to load it");
            }
            if (bytes > MAX_BYTES) {
                throw new InvalidException("the skill " + md.uri() + " holds " + bytes + " bytes; a skill may hold at most "
                    + MAX_BYTES + " (16 MiB), for every host to load it");
            }
            skills.put(md.uri(), new Skill(md.uri(), frontmatter, List.copyOf(own)));
            for (Resource r : own) served.put(r.uri(), r);
        }
        return new Skills(served, skills);
    }

    /**
     * The files under {@code root}, by their path segments under it, in no particular order. Dot files and
     * directories are skipped; a link or any other kind of file is refused.
     */
    private static Map<List<String>, Path> walk(Path root) throws InvalidException {
        Map<List<String>, Path> found = new HashMap<>();
        List<String> refused = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    return !d.equals(root) && hidden(d) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                    if (hidden(f)) return FileVisitResult.CONTINUE;
                    if (attrs.isSymbolicLink()) {
                        refused.add(f + " is a symbolic link: a skill serves only the files in its directory; copy the file in instead");
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile()) {
                        refused.add(f + " is not a regular file");
                        return FileVisitResult.TERMINATE;
                    }
                    List<String> segments = new ArrayList<>();
                    for (Path p : root.relativize(f)) segments.add(p.toString());
                    found.put(segments, f);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            throw new InvalidException("cannot read " + root + ": " + e.getMessage());
        }
        if (!refused.isEmpty()) throw new InvalidException(refused.get(0));
        return found;
    }

    private static boolean hidden(Path p) {
        Path name = p.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    /**
     * The frontmatter of a SKILL.md, read from {@code where}, checked as the Agent Skills specification asks:
     * a {@code name} that is {@code dirName}, of lowercase letters, digits and single hyphens, at most 64
     * characters; a {@code description} of 1 to 1024 characters.
     */
    private static Map<String, Object> frontmatter(byte[] bytes, String where, String dirName) throws InvalidException {
        String text = Utf8.decode(bytes);
        if (text == null) throw new InvalidException(where + ": a SKILL.md must be UTF-8 text");
        if (text.startsWith("﻿")) throw new InvalidException(where + ":1: a SKILL.md must not begin with a byte order mark");
        Map<String, Object> fm;
        try {
            fm = Frontmatter.of(text);
        } catch (Frontmatter.FormatException e) {
            throw new InvalidException(where + ":" + e.line + ": " + e.getMessage());
        }
        if (!(fm.get("name") instanceof String name)) {
            throw new InvalidException(where + ": the frontmatter needs `name: " + dirName + "`, the skill's directory's name");
        }
        if (name.length() > 64 || !NAME.matcher(name).matches()) {
            throw new InvalidException(where + ": the name `" + name + "` must be 1 to 64 lowercase letters, digits and "
                + "hyphens, with no hyphen first, last or next to another");
        }
        if (!name.equals(dirName)) {
            throw new InvalidException(where + ": the name `" + name + "` must be the skill's directory's name, `" + dirName + "`");
        }
        if (!(fm.get("description") instanceof String d) || d.isBlank()) {
            throw new InvalidException(where + ": the frontmatter needs a `description`: what the skill does and when to use it");
        }
        if (d.length() > 1024) {
            throw new InvalidException(where + ": the description has " + d.length() + " characters; it may have at most 1024");
        }
        return fm;
    }

    /** The URI of a file or directory at {@code segments}, each percent-encoded where it is not unreserved. */
    static String uri(List<String> segments) {
        StringBuilder sb = new StringBuilder(SCHEME);
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) sb.append('/');
            for (byte b : segments.get(i).getBytes(StandardCharsets.UTF_8)) {
                char c = (char) (b & 0xFF);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-._~".indexOf(c) >= 0) {
                    sb.append(c);
                } else {
                    sb.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xFF));
                }
            }
        }
        return sb.toString();
    }

    /** A segment of a URI built by {@link #uri}, decoded. */
    static String decode(String segment) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '%' && i + 2 < segment.length()) {
                out.write(Integer.parseInt(segment.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The MIME types of common file names; others are text/plain if UTF-8, else application/octet-stream. */
    private static final Map<String, String> MIME_TYPES = Map.ofEntries(
        Map.entry("md", "text/markdown"), Map.entry("markdown", "text/markdown"), Map.entry("txt", "text/plain"),
        Map.entry("al", "text/plain"), Map.entry("json", "application/json"), Map.entry("yaml", "application/yaml"),
        Map.entry("yml", "application/yaml"), Map.entry("toml", "application/toml"), Map.entry("csv", "text/csv"),
        Map.entry("tsv", "text/tab-separated-values"), Map.entry("html", "text/html"), Map.entry("htm", "text/html"),
        Map.entry("css", "text/css"), Map.entry("js", "text/javascript"), Map.entry("mjs", "text/javascript"),
        Map.entry("ts", "text/x-typescript"), Map.entry("py", "text/x-python"), Map.entry("sh", "text/x-shellscript"),
        Map.entry("xml", "application/xml"), Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
        Map.entry("webp", "image/webp"), Map.entry("pdf", "application/pdf"), Map.entry("zip", "application/zip"));

    static String mimeType(String name, byte[] bytes) {
        int dot = name.lastIndexOf('.');
        String type = dot > 0 ? MIME_TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT)) : null;
        if (type != null) return type;
        return Utf8.decode(bytes) != null ? "text/plain" : "application/octet-stream";
    }

    // ---------------------------------------------------------------- what the server answers

    /** How many skills there are. */
    int count() {
        return skills.size();
    }

    /** The URIs of the skills' SKILL.md files, in order. */
    Set<String> uris() {
        return skills.keySet();
    }

    /** Whether {@code other} serves the same files with the same bytes. */
    boolean sameAs(Skills other) {
        if (!files.keySet().equals(other.files.keySet())) return false;
        for (Resource r : files.values()) {
            if (!r.digest().equals(other.files.get(r.uri()).digest())) return false;
        }
        return true;
    }

    /** The entry of every skill, for skills/list. */
    List<Object> list() {
        List<Object> list = new ArrayList<>();
        for (Skill s : skills.values()) list.add(entry(s));
        return list;
    }

    /** The entry of the skill whose SKILL.md is {@code uri}, for skills/get; null if there is none. */
    Map<String, Object> get(String uri) {
        Skill s = skills.get(uri);
        return s == null ? null : entry(s);
    }

    private static Map<String, Object> entry(Skill s) {
        List<Object> resources = new ArrayList<>();
        for (Resource r : s.files()) resources.add(Json.obj("uri", r.uri(), "digest", r.digest(), "size", (long) r.bytes().length));
        return Json.obj("uri", s.uri(), "frontmatter", s.frontmatter(), "resources", resources);
    }

    /** The metadata of every file, for resources/list: a SKILL.md is named and described as its skill. */
    List<Object> resources() {
        List<Object> list = new ArrayList<>();
        for (Resource r : files.values()) list.add(metadata(r));
        return list;
    }

    private Map<String, Object> metadata(Resource r) {
        Skill s = skills.get(r.uri());
        Map<String, Object> m = Json.obj("uri", r.uri(), "name", s != null ? s.name() : decode(r.name()));
        if (s != null) m.put("description", s.description());
        m.put("mimeType", r.mimeType());
        m.put("size", (long) r.bytes().length);
        return m;
    }

    /** The contents of the file at {@code uri}, for resources/read: its text, or Base64 if it is not UTF-8; null if there is none. */
    Map<String, Object> read(String uri) {
        Resource r = files.get(uri);
        if (r == null) return null;
        String text = Utf8.decode(r.bytes());
        Map<String, Object> c = Json.obj("uri", r.uri(), "mimeType", r.mimeType());
        if (text != null) {
            c.put("text", text);
        } else {
            c.put("blob", Base64.getEncoder().encodeToString(r.bytes()));
        }
        return c;
    }

    /** The direct children of the directory at {@code uri}, for resources/directory/read; null if it is none. */
    List<Object> directory(String uri) {
        SortedMap<String, Boolean> children = directories.get(uri);
        if (children == null) return null;
        List<Object> list = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : children.entrySet()) {
            if (e.getValue()) {
                String name = decode(e.getKey().substring(e.getKey().lastIndexOf('/') + 1));
                list.add(Json.obj("uri", e.getKey(), "name", name, "mimeType", DIRECTORY));
            } else {
                list.add(metadata(files.get(e.getKey())));
            }
        }
        return list;
    }

    /**
     * `serve --watch` for skills: loads them again at each poll and serves what it finds once two polls in a
     * row agree, as for the program's files, so a file still being written is not served half written. Skills
     * that cannot be served are reported once, and the ones before stay.
     */
    static final class Watcher {
        private final List<String> dirs;
        private Skills changing;
        private String lastError;

        Watcher(List<String> dirs) {
            this.dirs = List.copyOf(dirs);
        }

        /** The skills to serve instead of {@code current}, if they changed and settled; else null. */
        Skills poll(Skills current, PrintStream log) {
            Skills now;
            try {
                now = load(dirs);
            } catch (InvalidException e) {
                changing = null;
                if (!e.getMessage().equals(lastError)) {
                    log.print("alang: --skills: " + e.getMessage() + "; still serving the skills loaded before\n");
                }
                lastError = e.getMessage();
                return null;
            }
            lastError = null;
            if (now.sameAs(current)) {
                changing = null;
                return null;
            }
            if (changing == null || !now.sameAs(changing)) {
                changing = now;
                return null;
            }
            changing = null;
            return now;
        }
    }
}
