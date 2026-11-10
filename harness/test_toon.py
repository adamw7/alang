#!/usr/bin/env python3
"""Tests for toon.py: python3 harness/test_toon.py

Each expected document is what the reference encoder (@toon-format/toon 4.4.0) writes for the same JSON,
except where a comment says otherwise.
"""

import json
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from toon import encode  # noqa: E402


def doc(text):
    return textwrap.dedent(text).strip("\n")


class AlangResults(unittest.TestCase):
    """The shapes `alang call` prints: the envelope, structs, enums, traps."""

    def test_a_list_of_structs_is_a_table(self):
        self.assertEqual(encode({"ok": True, "result": [{"word": "a", "count": 2}, {"word": "b", "count": 1}],
                                 "stdout": ""}), doc("""
            ok: true
            result[2]{word,count}:
              a,2
              b,1
            stdout: ""
            """))

    def test_a_struct_in_a_struct_folds_into_the_header(self):
        value = {"ok": True,
                 "result": [{"name": "s1", "from": {"x": 0.0, "y": 1.5}, "to": {"x": 2.0, "y": -0.5}},
                            {"name": "s2", "from": {"x": 1.0, "y": 0.25}, "to": {"x": 3.0, "y": 4.0}}],
                 "shape": {"Rect": [1.5, 2.0]}, "stdout": "line 1\nline 2\n"}
        self.assertEqual(encode(value), doc(r"""
            ok: true
            result[2]{name,from{x,y},to{x,y}}:
              s1,0,1.5,2,-0.5
              s2,1,0.25,3,4
            shape:
              Rect[2]: 1.5,2
            stdout: "line 1\nline 2\n"
            """))

    def test_a_trap(self):
        value = {"ok": False, "error": {
            "code": "R0003", "title": "index out of bounds", "message": "index 5 is out of bounds for length 2",
            "line": 4, "column": 12, "source_line": "    return xs[i];", "trace": ["tool fn at (entry point, line 2)"],
            "hint": "An index is negative or not smaller than the length of the array, list or string."},
            "stdout": ""}
        self.assertEqual(encode(value), doc("""
            ok: false
            error:
              code: R0003
              title: index out of bounds
              message: index 5 is out of bounds for length 2
              line: 4
              column: 12
              source_line: "    return xs[i];"
              trace[1]: "tool fn at (entry point, line 2)"
              hint: "An index is negative or not smaller than the length of the array, list or string."
            stdout: ""
            """))

    def test_enum_values_of_different_variants_are_list_items(self):
        self.assertEqual(encode(["Empty", {"Circle": [2.0]}, {"Rect": [1.5, 0.5]}, {}]), doc("""
            [4]:
              - Empty
              - Circle[1]: 2
              - Rect[2]: 1.5,0.5
              -
            """))


class Forms(unittest.TestCase):
    def test_an_object_of_uniform_objects_is_keyed_rows(self):
        value = {"stock": {"A-100": {"price": 250, "count": 3}, "B-200": {"price": 1999, "count": 0}}}
        self.assertEqual(encode(value), doc("""
            stock[2:]{price,count}:
              "A-100": 250,3
              "B-200": 1999,0
            """))

    def test_arrays_of_arrays_and_empty_containers(self):
        value = {"grid": [[1, 2], [3], []], "none": [], "empty": {}, "mixed": [1, [2, 3], {"a": 1}, [{"b": 2}]]}
        self.assertEqual(encode(value), doc("""
            grid[3]:
              - [2]: 1,2
              - [1]: 3
              - [0]:
            none: []
            empty:
            mixed[4]:
              - 1
              - [2]: 2,3
              - a: 1
              - [1]:
                - b: 2
            """))

    def test_a_list_item_whose_first_field_is_a_table(self):
        value = [{"rows": [{"a": 1, "b": "x"}, {"a": 2, "b": "y"}], "total": 3}, {"rows": [], "total": 0}]
        self.assertEqual(encode(value), doc("""
            [2]:
              - rows[2]{a,b}:
                  1,x
                  2,y
                total: 3
              - rows: []
                total: 0
            """))

    def test_rows_with_different_fields_are_list_items(self):
        self.assertEqual(encode([{"a": 1, "b": 2}, {"a": 3}, {"a": 4, "b": [5]}]), doc("""
            [3]:
              - a: 1
                b: 2
              - a: 3
              - a: 4
                b[1]: 5
            """))

    def test_top_level_values(self):
        self.assertEqual(encode("hello"), "hello")
        self.assertEqual(encode("﻿hello"), '"﻿hello"')
        self.assertEqual(encode(""), '""')
        self.assertEqual(encode(42), "42")
        self.assertEqual(encode(None), "null")
        self.assertEqual(encode(False), "false")
        self.assertEqual(encode([]), "[]")
        self.assertEqual(encode({}), "")


class Primitives(unittest.TestCase):
    def test_numbers(self):
        # The reference writes the last two as 18446744073709552000 and -9223372036854776000: JavaScript
        # numbers cannot hold them. A u64 or i64 result is written exactly here.
        values = [0, -0.0, 2.0, 0.1 + 0.2, 1e-7, 0.000001, 1e20, 1e21, 123456.789, -5e-324,
                  18446744073709551615, -9223372036854775808]
        self.assertEqual(encode(values), "[12]: 0,0,2,0.30000000000000004,1e-7,0.000001,100000000000000000000,"
                                         "1e+21,123456.789,-5e-324,18446744073709551615,-9223372036854775808")

    def test_floats_that_json_cannot_hold_are_null(self):
        self.assertEqual(encode([float("nan"), float("inf"), float("-inf")]), "[3]: null,null,null")

    def test_strings_are_quoted_only_where_they_would_read_back_differently(self):
        values = ["plain text", "é", "", "true", "null", "05", "1e5", "-x", "#x", " a", "a ", "a,b", "a|b", "a:b",
                  "[x]", "{x}", 'q"q', "back\\slash", "tab\there", "line\nbreak", "\u0001"]
        self.assertEqual(encode(values), r'[21]: plain text,é,"","true","null","05","1e5","-x","#x"," a","a ",'
                                         r'"a,b",a|b,"a:b","[x]","{x}","q\"q","back\\slash","tab\there",'
                                         r'"line\nbreak","\u0001"')

    def test_keys(self):
        value = {"plain": 1, "snake_case": 2, "dotted.key": 3, "with space": 4, "": 5, "-dash": 6, 'q"uote': 7}
        self.assertEqual(encode(value), doc(r"""
            plain: 1
            snake_case: 2
            dotted.key: 3
            "with space": 4
            "": 5
            "-dash": 6
            "q\"uote": 7
            """))

    def test_keys_keep_their_order(self):
        # JavaScript moves integer-like keys such as "2" to the front; JSON order is kept here.
        self.assertEqual(encode({"b": 1, "2": 2}), 'b: 1\n"2": 2')


class Delimiters(unittest.TestCase):
    def test_tab(self):
        value = {"rows": [{"text": "a, b", "n": 1}, {"text": "c\td", "n": 2}], "tags": ["x y", "z"]}
        self.assertEqual(encode(value, "\t"),
                         'rows[2\t]{text\tn}:\n  a, b\t1\n  "c\\td"\t2\ntags[2\t]: x y\tz')

    def test_pipe(self):
        value = {"rows": [{"text": "a, b", "n": 1}, {"text": "c|d", "n": 2}]}
        self.assertEqual(encode(value, "|"), 'rows[2|]{text|n}:\n  a, b|1\n  "c|d"|2')

    def test_other_delimiters_are_refused(self):
        with self.assertRaises(ValueError):
            encode([1, 2], ";")


class NotJson(unittest.TestCase):
    def test_lone_surrogates_are_refused(self):
        with self.assertRaises(ValueError):
            encode(json.loads('"\\ud800"'))
        with self.assertRaises(ValueError):
            encode(json.loads('{"\\udc00": 1}'))

    def test_other_values_are_refused(self):
        for value in [object(), (1, 2), {1: "a"}, [{1, 2}]]:
            with self.subTest(value=value), self.assertRaises(TypeError):
                encode(value)


class CommandLine(unittest.TestCase):
    def run_toon(self, *args, stdin=b""):
        return subprocess.run([sys.executable, str(HERE / "toon.py"), *args], input=stdin, capture_output=True,
                              timeout=30)

    def test_stdin(self):
        r = self.run_toon(stdin='{"ok": true, "result": "é", "stdout": ""}'.encode())
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.decode(), 'ok: true\nresult: é\nstdout: ""\n')

    def test_file_and_delimiter(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "result.json"
            path.write_text('{"result": [1, 2]}', encoding="utf-8")
            r = self.run_toon("--delimiter", "pipe", str(path))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.decode(), "result[2|]: 1|2\n")

    def test_errors(self):
        for stdin in [b"{", b"[NaN]", b'"\xff"', b'"\\ud800"']:
            with self.subTest(stdin=stdin):
                r = self.run_toon(stdin=stdin)
                self.assertEqual(r.returncode, 1)
                self.assertEqual(r.stdout, b"")
                self.assertTrue(r.stderr.startswith(b"toon.py: "), r.stderr)


if __name__ == "__main__":
    unittest.main()
