'use strict';
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const lib = require('../src/alang');

test('code-point columns become UTF-16 offsets', () => {
  const line = '  let s: str[8] = "é😀"; let x = 1;';
  // The toolchain puts `x` at column 29 (1-based, in code points); 😀 is two UTF-16 units.
  assert.strictEqual(lib.utf16Column(line, 28), line.indexOf('x ='));
  assert.strictEqual(lib.utf16Column('abc', 0), 0);
  assert.strictEqual(lib.utf16Column('abc', 3), 3);
  assert.strictEqual(lib.utf16Column('abc', 5), 5);
});

test('check output is read even after leading noise', () => {
  assert.deepStrictEqual(lib.parseJsonOutput('notice\n{"ok": true, "diagnostics": []}\n'), { ok: true, diagnostics: [] });
  assert.strictEqual(lib.parseJsonOutput('no json here'), null);
  assert.strictEqual(lib.parseJsonOutput('{broken'), null);
});

test('diagnostics in imported files are grouped by absolute path', () => {
  const cwd = path.resolve('/work/prog');
  const main = path.join(cwd, 'main.al');
  const report = { diagnostics: [
    { code: 'E0001', line: 3, message: 'a' },
    { code: 'E0201', file: 'lib/text.al', line: 1, message: 'b' },
  ] };
  const grouped = lib.groupDiagnostics(report, main, cwd);
  assert.deepStrictEqual([...grouped.keys()], [main, path.join(cwd, 'lib', 'text.al')]);
  assert.strictEqual(grouped.get(main)[0].code, 'E0001');
  // A clean check still reports the checked file, so its old diagnostics are cleared.
  assert.deepStrictEqual([...lib.groupDiagnostics({ diagnostics: [] }, main, cwd).entries()], [[main, []]]);
});

test('messages carry the hint', () => {
  assert.strictEqual(lib.diagnosticMessage({ message: 'm', hint: 'h' }), 'm\nhint: h');
  assert.strictEqual(lib.diagnosticMessage({ message: 'm' }), 'm');
  assert.strictEqual(lib.errorDocAnchor('E0215'), '#e0215');
});

test('tests, fn main and tool fns are found', () => {
  const text = [
    '/// doc',
    'tool fn word_counts(text: str[256]) -> i64 {',
    '}',
    'fn main(input: str[64]) {',
    '}',
    'test "counts \\"quoted\\" words" {',
    '}',
    '  test "zero" traps R0006 {',
    '}',
    'fn mainly() {}',
    '// test "commented out" {',
  ].join('\n');
  assert.deepStrictEqual(lib.findRunnables(text), [
    { kind: 'tool', line: 1, name: 'word_counts' },
    { kind: 'main', line: 3, takesInput: true },
    { kind: 'test', line: 5, name: 'counts "quoted" words' },
    { kind: 'test', line: 7, name: 'zero' },
  ]);
  assert.deepStrictEqual(lib.findRunnables('fn main() {\r\n}'), [{ kind: 'main', line: 0, takesInput: false }]);
});

test('shell arguments are quoted', () => {
  assert.strictEqual(lib.shellQuote('prog.al', false), 'prog.al');
  assert.strictEqual(lib.shellQuote("it's here", false), `'it'\\''s here'`);
  assert.strictEqual(lib.shellQuote('{"text": "a"}', true), '"{""text"": ""a""}"');
});
