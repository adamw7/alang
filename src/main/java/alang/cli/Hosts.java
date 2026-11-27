package alang.cli;

import alang.interp.Host;
import alang.interp.Interpreter;
import alang.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The hosts the CLI gives a run: `--hosts FILE`, commands that answer host fn calls, and `--replay FILE`, the calls a
 * run recorded. A hosts file maps each host fn's name, or "*" for every other one, to the command that answers it:
 *
 * <pre>{"price": {"command": ["python3", "price.py"], "timeout": 5}, "*": {"command": ["./host"]}}</pre>
 *
 * Each call runs its command once, in the directory of the hosts file, with {"fn": name, "args": {...}} and a newline on
 * its standard input. The command prints the JSON of the result on its standard output and exits with status 0; any
 * other status fails the call, with what it wrote on its standard error. "timeout" is in seconds (default 10).
 */
final class Hosts {
    private Hosts() {}

    /** How long a host command may take by default, in seconds. */
    static final double DEFAULT_TIMEOUT_SECONDS = 10;

    /** The most bytes of a reply, as of a fn main's JSON input; more fails the call. */
    static final int MAX_REPLY = Interpreter.MAX_JSON_INPUT;

    /** How much of a failing command's standard error a failure quotes. */
    private static final int MAX_STDERR = 2000;

    private record Command(List<String> argv, long timeoutMillis) {}

    /**
     * The host for {@code hostsFile} or {@code replayFile} (at most one may be given), as a supplier of a fresh host for
     * each run, as a replay keeps its place; {@link Host#NONE} with neither.
     */
    static Supplier<Host> load(String hostsFile, String replayFile) {
        if (hostsFile != null && replayFile != null) {
            throw new Main.UsageException("--hosts and --replay cannot be combined: a replay answers every call itself");
        }
        if (hostsFile != null) {
            Host h = commands(Path.of(hostsFile));
            return () -> h;
        }
        if (replayFile != null) {
            List<?> calls = recording(Path.of(replayFile));
            return () -> Host.replay(calls);
        }
        return () -> Host.NONE;
    }

    /** The calls in a replay file: a list of calls, or a report with "host_calls", such as `alang call` prints. */
    private static List<?> recording(Path file) {
        Object j = parse(file, "--replay");
        if (j instanceof Map<?, ?> m && m.get("host_calls") instanceof List<?> calls) return calls;
        if (j instanceof Map<?, ?>) return List.of();
        if (j instanceof List<?> calls) return calls;
        throw new Main.UsageException("--replay " + file + ": expected a list of host calls, or a report with \"host_calls\"");
    }

    private static Object parse(Path file, String option) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException e) {
            throw new Main.UsageException(option + ": cannot read " + file + ": " + e.getMessage());
        }
        try {
            return Json.parse(text);
        } catch (Json.ParseException e) {
            throw new Main.UsageException(option + " " + file + ": not JSON: " + e.getMessage());
        }
    }

    /** The host whose commands a hosts file names; a file that is not one is a usage error. */
    static Host commands(Path file) {
        Object j = parse(file, "--hosts");
        String where = "--hosts " + file;
        if (!(j instanceof Map<?, ?> m) || m.isEmpty()) {
            throw new Main.UsageException(where + ": expected an object such as {\"price\": {\"command\": [\"python3\", \"price.py\"]}}");
        }
        Path dir = file.toAbsolutePath().getParent();
        Map<String, Command> commands = new HashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String name = (String) e.getKey();
            String at = where + ": \"" + name + "\"";
            if (!(e.getValue() instanceof Map<?, ?> spec) || !(spec.get("command") instanceof List<?> argv) || argv.isEmpty()
                || !argv.stream().allMatch(a -> a instanceof String)) {
                throw new Main.UsageException(at + " needs {\"command\": [program, arguments...]}");
            }
            for (Object key : spec.keySet()) {
                if (!key.equals("command") && !key.equals("timeout")) throw new Main.UsageException(at + ": unknown key \"" + key + "\"");
            }
            double seconds = DEFAULT_TIMEOUT_SECONDS;
            if (spec.containsKey("timeout")) {
                if (!(spec.get("timeout") instanceof Number n) || !(n.doubleValue() > 0) || n.doubleValue() > 86400) {
                    throw new Main.UsageException(at + ": \"timeout\" is seconds, above 0 and at most 86400");
                }
                seconds = n.doubleValue();
            }
            List<String> args = new ArrayList<>();
            for (Object a : argv) args.add((String) a);
            // A program named by a relative path, ./host or bin/host, is in the directory of the hosts file.
            String program = args.get(0);
            if (program.contains("/") && !Path.of(program).isAbsolute()) args.set(0, dir.resolve(program).normalize().toString());
            commands.put(name, new Command(List.copyOf(args), (long) Math.ceil(seconds * 1000)));
        }
        return (name, callArgs) -> {
            Command c = commands.getOrDefault(name, commands.get("*"));
            if (c == null) throw new Host.Failure(where + " names no command for `" + name + "` (nor \"*\")");
            return run(c, dir, name, callArgs);
        };
    }

    /** Runs one call's command and reads its reply. */
    private static Object run(Command c, Path dir, String name, Map<String, Object> args) {
        String shown = String.join(" ", c.argv());
        Process p;
        try {
            p = new ProcessBuilder(c.argv()).directory(dir.toFile()).start();
        } catch (IOException e) {
            throw new Host.Failure("cannot start `" + shown + "`: " + e.getMessage());
        }
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread errReader = Thread.ofVirtual().start(() -> drain(p.getErrorStream(), stderr, MAX_STDERR));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Thread outReader = Thread.ofVirtual().start(() -> drain(p.getInputStream(), stdout, MAX_REPLY + 1));
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write((Json.write(Json.obj("fn", name, "args", args)) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // A command that exits without reading its input still answers.
        }
        try {
            if (!p.waitFor(c.timeoutMillis(), TimeUnit.MILLISECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                throw new Host.Failure("`" + shown + "` did not answer within " + seconds(c.timeoutMillis()) + " s");
            }
            // A process it started may still hold its output open: what came before the exit is the answer.
            outReader.join(5000);
            errReader.join(5000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new Host.Failure("the call of `" + shown + "` was interrupted");
        }
        if (p.exitValue() != 0) {
            String why = stderr.toString(StandardCharsets.UTF_8).strip();
            throw new Host.Failure("`" + shown + "` exited with status " + p.exitValue() + (why.isEmpty() ? "" : ": " + why));
        }
        if (stdout.size() > MAX_REPLY) throw new Host.Failure("`" + shown + "` replied with more than " + MAX_REPLY + " bytes");
        String reply = stdout.toString(StandardCharsets.UTF_8).strip();
        try {
            return Json.parse(reply);
        } catch (Json.ParseException e) {
            throw new Host.Failure("`" + shown + "` replied with text that is not JSON (" + e.getMessage() + "): "
                + (reply.length() > 200 ? reply.substring(0, 200) + "..." : reply));
        }
    }

    private static String seconds(long millis) {
        return millis % 1000 == 0 ? Long.toString(millis / 1000) : Double.toString(millis / 1000.0);
    }

    /** Reads {@code in} to its end, keeping its first {@code cap} bytes. */
    private static void drain(InputStream in, ByteArrayOutputStream into, int cap) {
        byte[] buf = new byte[8192];
        try (in) {
            for (int n; (n = in.read(buf)) > 0; ) {
                int keep = Math.min(n, cap - into.size());
                if (keep > 0) into.write(buf, 0, keep);
            }
        } catch (IOException e) {
            // The process ended; what was read is all there is.
        }
    }
}
