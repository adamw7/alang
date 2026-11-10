#!/usr/bin/env python3
"""Turns JSON into TOON, to show a tool result to a model in fewer tokens.

ALang speaks JSON: `alang call` prints its result as JSON, and code should keep reading `ok`,
`result` and `error.code` from that JSON. TOON (Token-Oriented Object Notation,
https://github.com/toon-format/spec) is for the copy a harness puts in a prompt. It carries the same data
with indentation instead of braces and less quoting, and a list of structs with the same fields becomes a
table that names the fields once:

    $ bin/alang call prog.al word_counts '{"text": "a b a"}' | python3 harness/toon.py
    ok: true
    result[2]{word,count}:
      a,2
      b,1
    stdout: ""

It saves the most on long lists of structs; on a small or deeply nested result compact JSON costs about
the same, so measure before switching a harness over.

As a module: `from toon import encode` and `encode(json.loads(text))`.

The output follows TOON spec v4.4 and is the same, byte for byte, as the reference encoder
(@toon-format/toon 4.4.0) with its default options (`indent: 2`, `keyFolding: off`) and the given
delimiter, with two exceptions: integers are written exactly, also past 2**53 where JavaScript rounds
them, and object keys keep their JSON order (JavaScript moves keys such as "1" to the front). Standard
library only.
"""

import argparse
import json
import re
import sys
from decimal import Decimal

DELIMITERS = {"comma": ",", "tab": "\t", "pipe": "|"}

_INDENT = "  "
# The reference uses JavaScript's \d and \w, which are ASCII only; Python's are not.
_UNQUOTED_KEY = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*\Z")
_NUMERIC_LIKE = re.compile(r"[+-]?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?\Z")


def encode(value, delimiter=","):
    """The TOON document for a JSON value (None, bool, int, float, str, list, dict with str keys)."""
    if delimiter not in DELIMITERS.values():
        raise ValueError(f"delimiter must be one of {sorted(DELIMITERS.values())!r}, found {delimiter!r}")
    lines = []
    _Encoder(delimiter, lines).value(value)
    return "\n".join(lines)


# ----------------------------------------------------------------------------- primitives

def _is_primitive(v):
    return v is None or isinstance(v, (str, bool, int, float))


def _check_text(s, what):
    # A lone surrogate (from a JSON escape such as "\ud800") is not text; the reference refuses it too.
    for c in s:
        if "\ud800" <= c <= "\udfff":
            raise ValueError(f"cannot encode {what} containing an unpaired surrogate U+{ord(c):04X}")


def _escape(s):
    out = []
    for c in s:
        if c == "\\":
            out.append("\\\\")
        elif c == '"':
            out.append('\\"')
        elif c == "\n":
            out.append("\\n")
        elif c == "\r":
            out.append("\\r")
        elif c == "\t":
            out.append("\\t")
        elif c < " ":
            out.append(f"\\u{ord(c):04x}")
        else:
            out.append(c)
    return "".join(out)


def _key(k):
    if not isinstance(k, str):
        raise TypeError(f"object keys must be strings, found {type(k).__name__}")
    _check_text(k, "an object key")
    return k if _UNQUOTED_KEY.match(k) else f'"{_escape(k)}"'


def _safe_unquoted(s, delimiter):
    return not (
        s == ""
        or s[0] in " \t" or s[-1] in " \t"
        or s in ("true", "false", "null")
        or _NUMERIC_LIKE.match(s)
        or any(c in ':"\\[]{}' or c < " " for c in s)
        or delimiter in s
        or s[0] in "-#"
    )


def _number(x):
    """A number as JavaScript's String(x) writes it, except that integers are exact."""
    if isinstance(x, int):
        return str(x)
    if x != x or x in (float("inf"), float("-inf")):
        return "null"
    if x == 0:
        return "0"  # -0 too
    # repr gives the shortest digits that read back as x, the digits JavaScript picks.
    _, digits, exponent = Decimal(repr(abs(x))).as_tuple()
    s = "".join(map(str, digits)).rstrip("0")
    exponent += len(digits) - len(s)
    sign = "-" if x < 0 else ""
    k = len(s)
    n = exponent + k  # x = 0.s * 10**n
    if k <= n <= 21:
        return sign + s + "0" * (n - k)
    if 0 < n <= 21:
        return sign + s[:n] + "." + s[n:]
    if -6 < n <= 0:
        return sign + "0." + "0" * -n + s
    e = n - 1
    mantissa = s if k == 1 else s[0] + "." + s[1:]
    return f"{sign}{mantissa}e{'+' if e > 0 else '-'}{abs(e)}"


# ----------------------------------------------------------------------------- tables

def _table_fields(rows):
    """The fields of rows that make a table (the same keys, primitive or nested-table values), else None.

    A field is (name, None) for a column of primitives, or (name, children) for a column of objects that
    fold into the header as a nested field group: `temp{min,max}`.
    """
    if not rows or not rows[0]:
        return None
    first = list(rows[0])
    for row in rows:
        if len(row) != len(first) or any(k not in row for k in first):
            return None
    fields = []
    for name in first:
        column = [row[name] for row in rows]
        if all(_is_primitive(v) for v in column):
            fields.append((name, None))
        elif all(isinstance(v, dict) and v for v in column):
            children = _table_fields(column)
            if children is None:
                return None
            fields.append((name, children))
        else:
            return None
    return fields


def _keyed_fields(obj):
    """The fields of an object whose values (at least two) make a table, for the keyed form, else None."""
    values = list(obj.values())
    if len(values) < 2 or not all(isinstance(v, dict) and v for v in values):
        return None
    return _table_fields(values)


def _leaves(row, fields, out=None):
    out = [] if out is None else out
    for name, children in fields:
        if children is None:
            out.append(row[name])
        else:
            _leaves(row[name], children, out)
    return out


def _field_list(fields, delimiter):
    return delimiter.join(_key(name) + ("" if children is None else "{" + _field_list(children, delimiter) + "}")
                          for name, children in fields)


def _header(length, key=None, fields=None, delimiter=",", keyed=False):
    h = "" if key is None else _key(key)
    h += f"[{length}{':' if keyed else ''}{'' if delimiter == ',' else delimiter}]"
    if fields:
        h += "{" + _field_list(fields, delimiter) + "}"
    return h + ":"


# ----------------------------------------------------------------------------- documents

class _Encoder:
    def __init__(self, delimiter, lines):
        self.d = delimiter
        self.lines = lines

    def line(self, depth, content):
        self.lines.append(_INDENT * depth + content)

    def item(self, depth, content):
        self.line(depth, "- " + content)

    def prim(self, v):
        if v is None:
            return "null"
        if isinstance(v, bool):
            return "true" if v else "false"
        if isinstance(v, (int, float)):
            return _number(v)
        _check_text(v, "a string")
        return v if _safe_unquoted(v, self.d) else f'"{_escape(v)}"'

    def join(self, values):
        return self.d.join(self.prim(v) for v in values)

    def inline(self, values, key=None):
        h = _header(len(values), key, delimiter=self.d)
        return f"{h} {self.join(values)}" if values else h

    def value(self, v):
        if _is_primitive(v):
            # A leading byte order mark would be dropped by a decoder, so it is always quoted.
            if isinstance(v, str) and v.startswith("﻿"):
                _check_text(v, "a string")
                self.lines.append(f'"{_escape(v)}"')
            else:
                self.lines.append(self.prim(v))
        elif isinstance(v, list):
            self.array(None, v, 0)
        elif isinstance(v, dict):
            fields = _keyed_fields(v)
            if fields:
                self.keyed(None, v, fields, 0)
            else:
                self.object(v.items(), 0)
        else:
            raise TypeError(f"not a JSON value: {type(v).__name__}")

    def object(self, entries, depth):
        for k, v in entries:
            self.pair(k, v, depth)

    def pair(self, key, v, depth):
        k = _key(key)
        if _is_primitive(v):
            self.line(depth, f"{k}: {self.prim(v)}")
        elif isinstance(v, list):
            self.array(key, v, depth)
        elif isinstance(v, dict):
            fields = _keyed_fields(v)
            if fields:
                self.keyed(key, v, fields, depth)
                return
            self.line(depth, f"{k}:")
            self.object(v.items(), depth + 1)
        else:
            raise TypeError(f"not a JSON value: {type(v).__name__}")

    def keyed(self, key, obj, fields, depth):
        self.line(depth, _header(len(obj), key, fields, self.d, keyed=True))
        self.keyed_rows(obj, fields, depth + 1)

    def keyed_rows(self, obj, fields, depth):
        for k, v in obj.items():
            self.line(depth, f"{_key(k)}: {self.join(_leaves(v, fields))}")

    def rows(self, rows, fields, depth):
        for row in rows:
            self.line(depth, self.join(_leaves(row, fields)))

    def array(self, key, arr, depth):
        if not arr:
            self.line(depth, "[]" if key is None else f"{_key(key)}: []")
            return
        if all(_is_primitive(x) for x in arr):
            self.line(depth, self.inline(arr, key))
            return
        if all(isinstance(x, list) and all(_is_primitive(y) for y in x) for x in arr):
            self.line(depth, _header(len(arr), key, delimiter=self.d))
            for x in arr:
                self.item(depth + 1, self.inline(x))
            return
        if all(isinstance(x, dict) for x in arr):
            fields = _table_fields(arr)
            if fields:
                self.line(depth, _header(len(arr), key, fields, self.d))
                self.rows(arr, fields, depth + 1)
                return
        self.line(depth, _header(len(arr), key, delimiter=self.d))
        for x in arr:
            self.list_item(x, depth + 1)

    def list_item(self, v, depth):
        if _is_primitive(v):
            self.item(depth, self.prim(v))
        elif isinstance(v, list):
            if all(_is_primitive(x) for x in v):
                self.item(depth, self.inline(v))
            else:
                self.item(depth, _header(len(v), delimiter=self.d))
                for x in v:
                    self.list_item(x, depth + 1)
        elif isinstance(v, dict):
            self.list_object(v, depth)
        else:
            raise TypeError(f"not a JSON value: {type(v).__name__}")

    def list_object(self, obj, depth):
        """An object as a list item: its first field on the `- ` line, the others indented below it."""
        if not obj:
            self.line(depth, "-")
            return
        entries = list(obj.items())
        (key, first), rest = entries[0], entries[1:]
        if isinstance(first, list) and all(isinstance(x, dict) for x in first):
            fields = _table_fields(first)
            if fields:
                self.item(depth, _header(len(first), key, fields, self.d))
                self.rows(first, fields, depth + 2)
                self.object(rest, depth + 1)
                return
        if isinstance(first, dict):
            fields = _keyed_fields(first)
            if fields:
                self.item(depth, _header(len(first), key, fields, self.d, keyed=True))
                self.keyed_rows(first, fields, depth + 2)
                self.object(rest, depth + 1)
                return
        k = _key(key)
        if _is_primitive(first):
            self.item(depth, f"{k}: {self.prim(first)}")
        elif isinstance(first, list):
            if not first:
                self.item(depth, f"{k}: []")
            elif all(_is_primitive(x) for x in first):
                self.item(depth, self.inline(first, key))
            else:
                self.item(depth, k + _header(len(first), delimiter=self.d))
                for x in first:
                    self.list_item(x, depth + 2)
        elif isinstance(first, dict):
            self.item(depth, f"{k}:")
            self.object(first.items(), depth + 2)
        else:
            raise TypeError(f"not a JSON value: {type(first).__name__}")
        self.object(rest, depth + 1)


# ----------------------------------------------------------------------------- command line

def _reject_constant(name):
    raise ValueError(f"{name} is not JSON")


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="toon.py",
        description="Read JSON (an `alang call` result, or any JSON) and print it as TOON.")
    parser.add_argument("file", nargs="?", help="the JSON file (default: stdin)")
    parser.add_argument("--delimiter", choices=sorted(DELIMITERS), default="comma",
                        help="between the values of arrays and table rows (default: comma)")
    args = parser.parse_args(argv)
    try:
        if args.file is None:
            text = sys.stdin.buffer.read().decode("utf-8")
        else:
            with open(args.file, encoding="utf-8") as f:
                text = f.read()
        value = json.loads(text, parse_constant=_reject_constant)
        out = encode(value, DELIMITERS[args.delimiter])
    except (OSError, UnicodeDecodeError, ValueError, TypeError) as e:
        print(f"toon.py: {e}", file=sys.stderr)
        return 1
    sys.stdout.buffer.write((out + "\n").encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
