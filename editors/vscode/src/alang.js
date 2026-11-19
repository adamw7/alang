// The parts of the extension that do not need VS Code: reading the toolchain's JSON and finding the
// places in a file that get a code lens. Kept apart so that `node --test` can test them.
'use strict';
const path = require('path');

/**
 * The UTF-16 offset (what VS Code counts) of a 0-based code-point column (what the toolchain counts)
 * in a line of text. A column past the end of the line maps past it by the same amount.
 */
function utf16Column(lineText, codePoints) {
  let offset = 0;
  let seen = 0;
  for (const ch of lineText) {
    if (seen === codePoints) return offset;
    offset += ch.length;
    seen++;
  }
  return offset + (codePoints - seen);
}

/**
 * The first JSON object in a command's stdout, or null. The launcher may print a build notice before
 * it on stderr, never on stdout, but be lenient about leading text all the same.
 */
function parseJsonOutput(stdout) {
  const start = stdout.indexOf('{');
  if (start < 0) return null;
  try {
    return JSON.parse(stdout.slice(start));
  } catch {
    return null;
  }
}

/**
 * The diagnostics of `alang check --json` (or test/fix), grouped by absolute file path. A diagnostic
 * without a `file` is in the checked file; a `file` is relative to the directory the command ran in.
 * Positions stay as the toolchain gives them: 1-based line and code-point column.
 */
function groupDiagnostics(report, checkedFile, cwd) {
  const byFile = new Map();
  byFile.set(checkedFile, []);
  for (const d of (report && report.diagnostics) || []) {
    const file = d.file ? path.resolve(cwd, d.file) : checkedFile;
    if (!byFile.has(file)) byFile.set(file, []);
    byFile.get(file).push(d);
  }
  return byFile;
}

/** The message shown for a diagnostic: the message, then the hint. */
function diagnosticMessage(d) {
  return d.hint ? `${d.message}\nhint: ${d.hint}` : d.message;
}

/** The anchor of a code's section in docs/ERRORS.md, e.g. E0215 -> #e0215. */
function errorDocAnchor(code) {
  return '#' + String(code).toLowerCase();
}

const TEST_RE = /^[ \t]*test[ \t]+"((?:[^"\\\r\n]|\\.)*)"/;
const MAIN_RE = /^[ \t]*fn[ \t]+main[ \t]*\(/;
const TOOL_RE = /^[ \t]*tool[ \t]+fn[ \t]+([A-Za-z_][A-Za-z0-9_]*)/;

/** The tests, fn main and tool fns declared in a file's text, each with its 0-based line. */
function findRunnables(text) {
  const found = [];
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    let m;
    if ((m = TEST_RE.exec(line))) {
      found.push({ kind: 'test', line: i, name: unescape(m[1]) });
    } else if (MAIN_RE.test(line)) {
      found.push({ kind: 'main', line: i, takesInput: /^[ \t]*fn[ \t]+main[ \t]*\([ \t]*[A-Za-z_]/.test(line) });
    } else if ((m = TOOL_RE.exec(line))) {
      found.push({ kind: 'tool', line: i, name: m[1] });
    }
  }
  return found;
}

function unescape(s) {
  return s.replace(/\\(x[0-9a-fA-F]{2}|.)/g, (_, e) => {
    switch (e[0]) {
      case 'n': return '\n';
      case 't': return '\t';
      case 'r': return '\r';
      case '0': return '\0';
      case 'x': return String.fromCharCode(parseInt(e.slice(1), 16));
      default: return e;
    }
  });
}

/** Quotes one argument for a POSIX shell, or for PowerShell / cmd on Windows. */
function shellQuote(arg, windows) {
  if (/^[A-Za-z0-9_\-./:=@%+,]+$/.test(arg)) return arg;
  if (windows) return `"${arg.replace(/"/g, '""')}"`;
  return `'${arg.replace(/'/g, `'\\''`)}'`;
}

module.exports = {
  utf16Column,
  parseJsonOutput,
  groupDiagnostics,
  diagnosticMessage,
  errorDocAnchor,
  findRunnables,
  shellQuote,
};
