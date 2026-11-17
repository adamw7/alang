package alang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import alang.util.Json;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * `alang debug`: breakpoints on lines and functions, stepping into, over and out of calls, the variables in
 * scope, a stop where a trap happens, the run going on by itself once the commands end, and the JSON events.
 */
class DebugTest {
    @TempDir
    Path dir;

    private record Result(int code, String out, String err) {}

    private static Result debug(String commands, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] all = new String[args.length + 1];
        all[0] = "debug";
        System.arraycopy(args, 0, all, 1, args.length);
        int code = new Main(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
            new ByteArrayInputStream(commands.getBytes(StandardCharsets.UTF_8))).run(all);
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path write(String name, String text) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return p;
    }

    private static final String PROGRAM = """
        struct Entry {
            word: str[8],
            count: i64,
        }

        fn bump(entries: mut list[Entry; 4], i: i64) {
            let before: i64 = entries[i].count;
            entries[i].count = before + 1;
        }

        fn main() {
            var entries: list[Entry; 4] = [Entry(word: "a", count: 1)];
            for i in 0..2 {
                bump(mut entries, 0);
            }
            print(entries[0].count);
        }
        """;

    @Test
    void stopsAtALineAndShowsTheVariablesInScope() throws IOException {
        Path p = write("p.al", PROGRAM);
        Result r = debug("break 8\nrun\nlocals\nprint entries[0].count\nprint entries[0].word\nwhere\ncontinue\nlocals\ncontinue\n", p.toString());
        assertEquals(0, r.code(), r.out() + r.err());
        String o = r.out();
        assertTrue(o.contains("breakpoint 1 at " + p + ":8"), o);
        assertTrue(o.contains("stopped at breakpoint 1 in fn bump, " + p + ":8:5"), o);
        assertTrue(o.contains("  entries: list[Entry; 4] = [Entry(word: \"a\", count: 1)]\n  i: i64 = 0\n  before: i64 = 1\n"), o);
        assertTrue(o.contains("entries[0].count: i64 = 1\n"), o);
        assertTrue(o.contains("entries[0].word: str[8] = \"a\"\n"), o);
        assertTrue(o.contains("* #0 fn bump, " + p + ":8:5\n  #1 fn main, " + p + ":14:9\n"), o);
        // The second time round, the change the first call made is there.
        assertTrue(o.contains("before: i64 = 2"), o);
        assertTrue(o.endsWith("3\nprogram exited normally after 12 step(s)\n"), o);
    }

    @Test
    void theDebuggerChangesNeitherTheOutputNorTheSteps() throws IOException {
        Path p = write("p.al", PROGRAM);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Main(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()))
            .run(new String[] {"run", p.toString(), "--json"});
        Map<?, ?> run = (Map<?, ?>) Json.parse(out.toString(StandardCharsets.UTF_8));
        Result r = debug("step\nstep\nnext\nstep\nstep\nfinish\n", p.toString(), "--json");
        List<Map<?, ?>> events = events(r.out());
        Map<?, ?> last = events.get(events.size() - 1);
        assertEquals("exited", last.get("event"));
        assertEquals(run.get("steps"), last.get("steps"));
        StringBuilder printed = new StringBuilder();
        for (Map<?, ?> e : events) {
            if ("output".equals(e.get("event"))) printed.append(e.get("text"));
        }
        assertEquals(run.get("stdout"), printed.toString());
    }

    @Test
    void stepNextAndFinishGoIntoOverAndOutOfCalls() throws IOException {
        Path p = write("p.al", PROGRAM);
        Result r = debug("step\nnext\nnext\nstep\nstep\nfinish\nquit\n", p.toString());
        List<String> stops = new ArrayList<>();
        for (String line : r.out().split("\n")) {
            if (line.startsWith("stepped")) stops.add(line.substring(line.lastIndexOf(':', line.lastIndexOf(':') - 1) + 1));
        }
        // step: main's first line; next: the for; next: the call; step: into bump; step: its next line;
        // finish: back in main, at the call of the next iteration.
        assertEquals(List.of("12:5", "13:5", "14:9", "7:5", "8:5", "14:9"), stops, r.out());
        assertEquals(1, r.code(), "a session that quits a running program fails");
    }

    @Test
    void aFunctionBreakpointStopsAtEachCall() throws IOException {
        Path p = write("p.al", PROGRAM);
        Result r = debug("break bump\nrun\nc\nbreakpoints\nc\n", p.toString());
        assertEquals(2, r.out().split("stopped at breakpoint 1 in fn bump, " + p + ":7:5", -1).length - 1, r.out());
        assertTrue(r.out().contains("1: fn bump, hit 2 time(s)"), r.out());
    }

    @Test
    void aTrapStopsBeforeTheStackUnwinds() throws IOException {
        Path p = write("t.al", """
            fn fill(xs: mut list[i64; 2], n: i64) {
                for i in 0..n {
                    push(mut xs, i * 10);
                }
            }

            fn main() {
                var xs: list[i64; 2] = [];
                fill(mut xs, 3);
            }
            """);
        Result r = debug("run\nprint i\nframe 1\nprint xs\ncontinue\n", p.toString());
        assertEquals(1, r.code());
        String o = r.out();
        assertTrue(o.contains("trapped in fn fill, " + p + ":3:9: trap[R0004]: push to a full list (capacity 2)"), o);
        assertTrue(o.contains("  xs: list[i64; 2] = [0, 10]\n  n: i64 = 3\n  i: i64 = 2\n"), o);
        assertTrue(o.contains("i: i64 = 2\n"), o);
        assertTrue(o.contains("xs: list[i64; 2] = [0, 10]\n"), o);
        assertTrue(o.contains("trap[R0004]: push to a full list (capacity 2)\n --> " + p + ":3:9"), o);
        assertTrue(o.endsWith("program trapped after 9 step(s)\n"), o);
    }

    @Test
    void withoutCommandsEachStopIsShownAndPassed() throws IOException {
        Path p = write("p.al", PROGRAM);
        Result r = debug("", p.toString(), "--break", "8", "--json");
        List<Map<?, ?>> events = events(r.out());
        List<Object> befores = new ArrayList<>();
        for (Map<?, ?> e : events) {
            if (!"stopped".equals(e.get("event"))) continue;
            for (Object v : (List<?>) e.get("variables")) {
                Map<?, ?> var = (Map<?, ?>) v;
                if (var.get("name").equals("before")) befores.add(var.get("value"));
            }
        }
        assertEquals(List.of("1", "2"), befores, r.out());
        assertEquals("{\"event\": \"exited\", \"ok\": true, \"steps\": 12}", Json.write(events.get(events.size() - 1)));
    }

    @Test
    void maxStopsBoundsAnUnattendedSession() throws IOException {
        Path p = write("p.al", PROGRAM);
        Result r = debug("", p.toString(), "--break", "8", "--max-stops", "1");
        assertEquals(1, r.out().split("stopped at breakpoint", -1).length - 1, r.out());
        assertTrue(r.out().contains("reached 1 stop(s) (--max-stops); the program runs on without stopping"), r.out());
        assertTrue(r.out().endsWith("program exited normally after 12 step(s)\n"), r.out());
    }

    @Test
    void variablesGoOutOfScopeWithTheirBlock() throws IOException {
        Path p = write("s.al", """
            fn main() {
                for i in 0..2 {
                    let sq: i64 = i * i;
                    print(sq);
                }
                let done: bool = true;
                print(done);
            }
            """);
        Result r = debug("break 3\nbreak 7\nrun\nlocals\nc\nlocals\nc\nlocals\nc\n", p.toString());
        String[] stops = r.out().split("stopped at breakpoint");
        assertEquals(4, stops.length, r.out());
        // Before line 3 runs, sq is not declared yet, also in the second iteration, which has its own.
        assertTrue(stops[1].contains("  i: i64 = 0\n") && !stops[1].contains("  sq:"), stops[1]);
        assertTrue(stops[2].contains("  i: i64 = 1\n") && !stops[2].contains("  sq:"), stops[2]);
        // After the loop, neither i nor sq is in scope.
        assertTrue(stops[3].contains("  done: bool = true\n") && !stops[3].contains("  i:") && !stops[3].contains("  sq:"), stops[3]);
    }

    @Test
    void testsAndToolsCanBeDebugged() throws IOException {
        Path p = write("t.al", """
            /// Doubles a number.
            tool fn twice(n: i64) -> i64 {
                let d: i64 = n * 2;
                return d;
            }

            test "twice of three" {
                let got: i64 = twice(3);
                assert got == 7;
            }
            """);
        Result t = debug("run\nprint got\nc\n", p.toString(), "--test", "three");
        assertEquals(1, t.code());
        assertTrue(t.out().contains("trapped in test \"twice of three\""), t.out());
        assertTrue(t.out().contains("got: i64 = 6\n"), t.out());
        Result c = debug("", p.toString(), "--call", "twice", "{\"n\": 4}", "--break", "4", "--json");
        List<Map<?, ?>> events = events(c.out());
        assertEquals(0, c.code(), c.out());
        assertEquals("{\"event\": \"exited\", \"ok\": true, \"result\": 8, \"steps\": 2}", Json.write(events.get(events.size() - 1)));
        Result none = debug("", p.toString(), "--test", "nothing");
        assertEquals(1, none.code());
        assertTrue(none.err().contains("no test name contains \"nothing\"; the tests are \"twice of three\""), none.err());
    }

    @Test
    void badCommandsAndLocationsSayWhatToDo() throws IOException {
        Path p = write("p.al", PROGRAM);
        Result r = debug("break 3\nbreak nope\nbreak q.al:1\nprint x\nfly\nbreak 7\nrun\nprint entries[5]\nprint zz\nframe 9\nquit\n",
            p.toString());
        String o = r.out();
        assertTrue(o.contains("error: no statement starts on line 3 of " + p + "\n  = hint: the nearest line with one is 7"), o);
        assertTrue(o.contains("error: no function `nope`\n  = hint: the functions are: bump, main"), o);
        assertTrue(o.contains("error: no file `q.al` in the program"), o);
        assertTrue(o.contains("error: the program has not started, so `print` has nothing to show"), o);
        assertTrue(o.contains("error: unknown command `fly`\n  = hint: `help` lists the commands"), o);
        assertTrue(o.contains("error: index 5 is out of range: `entries` has 1 element(s)"), o);
        assertTrue(o.contains("error: `zz` is not a variable in scope here; these are: entries, i"), o);
        assertTrue(o.contains("error: no frame 9: the stack has 2 frame(s), 0 to 1"), o);
    }

    @Test
    void aBreakpointCanGoInAnImportedFile() throws IOException {
        write("lib/util.al", """
            fn twice(n: i64) -> i64 {
                let d: i64 = n * 2;
                return d;
            }
            """);
        Path p = write("main.al", """
            import "lib/util.al";

            fn main() {
                print(twice(4));
            }
            """);
        Result r = debug("break util.al:3\nrun\nlocals\nc\n", p.toString());
        assertTrue(r.out().contains("stopped at breakpoint 1 in fn twice, " + dir.resolve("lib/util.al") + ":3:5"), r.out());
        assertTrue(r.out().contains("  n: i64 = 4\n  d: i64 = 8\n"), r.out());
        assertFalse(r.out().contains("error"), r.out());
    }

    @Test
    void mainGetsItsInputFromAFile() throws IOException {
        Path p = write("in.al", """
            fn main(input: str[16]) {
                let n: i64 = len(input);
                print(n);
            }
            """);
        Path in = write("in.txt", "hello");
        Result r = debug("break 3\nrun\nprint input\nprint n\nc\n", p.toString(), "--input", in.toString());
        assertTrue(r.out().contains("input: str[16] = \"hello\"\n"), r.out());
        assertTrue(r.out().contains("n: i64 = 5\n"), r.out());
    }

    private static List<Map<?, ?>> events(String out) {
        List<Map<?, ?>> events = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (!line.isEmpty()) events.add((Map<?, ?>) Json.parse(line));
        }
        return events;
    }
}
