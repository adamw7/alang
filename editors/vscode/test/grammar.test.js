'use strict';
// Tokenizes ALang with the TextMate engine VS Code itself uses, and checks the scopes of key tokens.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const vsctm = require('vscode-textmate');
const oniguruma = require('vscode-oniguruma');

const root = path.resolve(__dirname, '..');

async function loadGrammar() {
  const wasm = fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm'));
  await oniguruma.loadWASM(wasm.buffer.slice(wasm.byteOffset, wasm.byteOffset + wasm.byteLength));
  const registry = new vsctm.Registry({
    onigLib: Promise.resolve({
      createOnigScanner: (sources) => new oniguruma.OnigScanner(sources),
      createOnigString: (s) => new oniguruma.OnigString(s),
    }),
    loadGrammar: async (scope) => {
      if (scope !== 'source.alang') return null;
      const file = path.join(root, 'syntaxes', 'alang.tmLanguage.json');
      return vsctm.parseRawGrammar(fs.readFileSync(file, 'utf8'), file);
    },
  });
  return registry.loadGrammar('source.alang');
}

/** Every token of the text, as [text, scopes]. */
function tokenize(grammar, text) {
  const out = [];
  let state = vsctm.INITIAL;
  for (const line of text.split('\n')) {
    const r = grammar.tokenizeLine(line, state);
    for (const t of r.tokens) out.push([line.slice(t.startIndex, t.endIndex), t.scopes]);
    state = r.ruleStack;
  }
  return out;
}

function scopeOf(tokens, text, nth = 0) {
  const found = tokens.filter(([t]) => t === text);
  assert.ok(found.length > nth, `no token ${JSON.stringify(text)} (#${nth})`);
  return found[nth][1].slice(1).join(' ');
}

let grammar;
test.before(async () => { grammar = await loadGrammar(); });

test('declarations', () => {
  const t = tokenize(grammar, '/// Doc.\ntool fn word_counts(text: str[256]) -> list[Entry; MAX] {\n}\nstruct Entry { word: str[16] }\nconst MAX: i64 = 16;');
  assert.match(scopeOf(t, '/// Doc.'), /comment\.line\.documentation/);
  assert.match(scopeOf(t, 'tool'), /storage\.modifier\.tool/);
  assert.match(scopeOf(t, 'word_counts'), /entity\.name\.function/);
  assert.match(scopeOf(t, 'str'), /storage\.type\.collection/);
  assert.match(scopeOf(t, 'Entry'), /entity\.name\.type/);
  assert.match(scopeOf(t, 'MAX'), /variable\.other\.constant/);
  assert.match(scopeOf(t, 'i64'), /storage\.type\.primitive/);
});

test('statements, literals and builtins', () => {
  const t = tokenize(grammar, [
    'var s: str[8] = "a\\n\\x41"; let c: u8 = \'\\\'\';',
    'while lo < hi limit 64 { push(mut xs, 0xff_ff + 1_000 + 2.5e-3); }',
    'if (a and not b) or c { x = helper(y); } // done',
    'match o { Option.Some(v) => { }, _ => { } }',
    'test "zero" traps R0006 { assert result >= 0; }',
  ].join('\n'));
  assert.match(scopeOf(t, '"'), /string\.quoted\.double/);
  assert.match(scopeOf(t, '\\n'), /constant\.character\.escape/);
  assert.match(scopeOf(t, '\\x41'), /constant\.character\.escape/);
  assert.match(scopeOf(t, "\\'"), /string\.quoted\.single.*constant\.character\.escape/);
  assert.match(scopeOf(t, 'while'), /keyword\.control/);
  assert.match(scopeOf(t, 'limit'), /keyword\.control/);
  assert.match(scopeOf(t, 'push'), /support\.function\.builtin/);
  assert.match(scopeOf(t, 'mut'), /storage\.modifier\.mut/);
  assert.match(scopeOf(t, '1_000'), /constant\.numeric\.integer/);
  assert.match(scopeOf(t, '0xff_ff'), /constant\.numeric\.hex/);
  assert.match(scopeOf(t, '2.5e-3'), /constant\.numeric\.float/);
  assert.match(scopeOf(t, 'and'), /keyword\.operator\.logical/);
  assert.match(scopeOf(t, 'helper'), /entity\.name\.function\.call/);
  assert.match(scopeOf(t, '// done'), /comment\.line\.double-slash/);
  assert.match(scopeOf(t, 'Option'), /support\.type\.builtin/);
  assert.match(scopeOf(t, '=>'), /keyword\.operator\.arrow/);
  assert.match(scopeOf(t, 'R0006'), /constant\.other\.error-code/);
  assert.match(scopeOf(t, 'result'), /variable\.language/);
});

test('habits from other languages stand out', () => {
  const t = tokenize(grammar, 'if a && b || !c { i++; }');
  assert.match(scopeOf(t, '&&'), /invalid\.illegal/);
  assert.match(scopeOf(t, '||'), /invalid\.illegal/);
  assert.match(scopeOf(t, '!'), /invalid\.illegal/);
  assert.match(scopeOf(t, '++'), /invalid\.illegal/);
  const ok = tokenize(grammar, 'if a != b { }');
  assert.match(scopeOf(ok, '!='), /keyword\.operator\.comparison/);
});

test('block comments, raw and multi-line strings, octal and code points', () => {
  const t = tokenize(grammar, [
    'let p: str[16] = r"\\d+"; /* one',
    'two */ let m: i64 = 0o755;',
    'let s: str[32] = """',
    '    caf\\u{e9}',
    '    """;',
    "let e: u32 = '\\u{20AC}';",
  ].join('\n'));
  assert.match(scopeOf(t, 'r"\\d+"'), /string\.quoted\.double\.raw/);
  assert.match(scopeOf(t, '/*'), /comment\.block/);
  assert.match(scopeOf(t, 'two '), /comment\.block/);
  assert.match(scopeOf(t, '0o755'), /constant\.numeric\.octal/);
  assert.match(scopeOf(t, '\\u{e9}'), /string\.quoted\.triple.*constant\.character\.escape/);
  assert.match(scopeOf(t, '\\u{20AC}'), /string\.quoted\.single.*constant\.character\.escape/);
});

test('a comment inside a string is not a comment', () => {
  const t = tokenize(grammar, 'let url: str[32] = "http://x"; // real');
  assert.ok(!t.some(([text, s]) => text.includes('//x') && s.some((x) => x.startsWith('comment'))));
  assert.match(scopeOf(t, '// real'), /comment/);
});

test('every example in the repository tokenizes without unknown builtins being called builtins', () => {
  const builtins = new Set(require('../src/builtins.json').map((b) => b.name));
  const examples = path.resolve(root, '../../examples');
  for (const f of fs.readdirSync(examples).filter((n) => n.endsWith('.al'))) {
    const t = tokenize(grammar, fs.readFileSync(path.join(examples, f), 'utf8'));
    for (const [text, scopes] of t) {
      if (scopes.some((s) => s.startsWith('support.function.builtin'))) assert.ok(builtins.has(text), `${f}: ${text}`);
    }
  }
});

test('the builtins table matches the toolchain', () => {
  const { execFileSync } = require('child_process');
  execFileSync(process.execPath, [path.join(root, 'scripts', 'gen-builtins.js'), '--check'], { stdio: 'pipe' });
});
