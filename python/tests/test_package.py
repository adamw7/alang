"""Tests an installed alang-toolchain wheel: run with the Python of the environment it is installed in,

    python -m unittest discover -s python/tests -v

ALANG_EXPECT=native or ALANG_EXPECT=jar says which wheel it should be. Standard library only.
"""

import json
import os
import shutil
import subprocess
import sys
import sysconfig
import tempfile
import unittest
from pathlib import Path

import alang

REPO = Path(__file__).resolve().parent.parent.parent
EXAMPLES = REPO / "examples"


def golden(name):
    return (REPO / "tests" / "examples" / f"{name}.out").read_text(encoding="utf-8").replace("\r\n", "\n")


def alang_script():
    """The alang command that pip installed, in this environment's scripts directory."""
    name = "alang.exe" if os.name == "nt" else "alang"
    path = Path(sysconfig.get_path("scripts")) / name
    return str(path) if path.exists() else shutil.which("alang")


def run(argv, stdin=""):
    proc = subprocess.run(argv, input=stdin.encode("utf-8"), capture_output=True)
    proc.stdout = proc.stdout.decode("utf-8").replace("\r\n", "\n")
    proc.stderr = proc.stderr.decode("utf-8", errors="replace")
    return proc


class InstalledWheel(unittest.TestCase):
    def test_imported_from_the_install_not_the_sources(self):
        self.assertNotEqual(Path(alang.__file__).resolve().parent, REPO / "python" / "alang")

    def test_the_expected_wheel(self):
        expect = os.environ.get("ALANG_EXPECT")
        if not expect:
            self.skipTest("ALANG_EXPECT is not set")
        argv, _ = alang.command()
        if expect == "native":
            self.assertTrue(Path(argv[0]).name.startswith("alang"), argv)
        else:
            self.assertEqual(argv[-2:], ["-jar", str(Path(alang.__file__).resolve().parent / "lib" / "alang.jar")])

    def test_version_matches_the_package(self):
        self.assertEqual(run([alang_script(), "version"]).stdout.strip(), f"alang {alang.__version__}")
        self.assertEqual(run([sys.executable, "-m", "alang", "version"]).stdout.strip(), f"alang {alang.__version__}")

    def test_run_prints_the_golden_output(self):
        proc = run([alang_script(), "run", str(EXAMPLES / "hello.al")])
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(proc.stdout, golden("hello"))

    def test_stdin_reaches_main(self):
        stdin = (EXAMPLES / "expenses.in").read_text(encoding="utf-8")
        self.assertEqual(run([alang_script(), "run", str(EXAMPLES / "expenses.al")], stdin).stdout, golden("expenses"))
        self.assertEqual(alang.invoke("run", EXAMPLES / "expenses.al", input=stdin).stdout.replace("\r\n", "\n"), golden("expenses"))

    def test_exit_codes_come_through(self):
        self.assertEqual(run([alang_script(), "check", str(REPO / "tests" / "errors" / "E0001_and_and.al")]).returncode, 1)
        self.assertEqual(run([alang_script(), "no-such-command"]).returncode, 2)
        self.assertEqual(run([alang_script(), "test", str(EXAMPLES / "shapes.al")]).returncode, 0)

    def test_check(self):
        report = alang.check(REPO / "tests" / "errors" / "E0001_and_and.al")
        self.assertFalse(report["ok"])
        self.assertEqual(report["diagnostics"][0]["code"], "E0001")
        self.assertTrue(alang.check(EXAMPLES / "hello.al")["ok"])

    def test_tools(self):
        names = [t["name"] for t in alang.tools(EXAMPLES / "text_tools.al")]
        self.assertIn("word_count", names)

    def test_call(self):
        reply = alang.call(EXAMPLES / "inventory.al", "place_order", {"sku": "B-200", "quantity": 2})
        self.assertTrue(reply["ok"], reply)
        self.assertEqual(alang.call(EXAMPLES / "inventory.al", "nope", {})["error"]["code"], "bad_tool")

    def test_call_passes_non_ascii_as_utf8(self):
        with tempfile.TemporaryDirectory() as tmp:
            prog = Path(tmp) / "size.al"
            prog.write_text("tool fn size(text: str[16]) -> i64 {\n    return len(text);\n}\n", encoding="utf-8")
            self.assertEqual(alang.call(prog, "size", {"text": "é"})["result"], 2)

    def test_agents_md(self):
        self.assertEqual(alang.agents_md(), (REPO / "AGENTS.md").read_text(encoding="utf-8"))

    def test_bad_output_raises(self):
        with self.assertRaises(alang.AlangError):
            alang._json(alang.invoke("no-such-command"))

    def test_serve_lists_the_tools(self):
        request = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/list"}) + "\n"
        proc = run([alang_script(), "serve", str(EXAMPLES / "text_tools.al")], request)
        self.assertIn('"name": "word_count"', proc.stdout)


if __name__ == "__main__":
    unittest.main()
