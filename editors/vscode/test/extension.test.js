'use strict';
// Activates the extension against a small stand-in for the `vscode` module and drives it with the real
// toolchain (../../bin/alang): diagnostics, quick fixes, formatting, hovers, code lenses and symbols.
// Skipped when the toolchain cannot run here (no Java).
const test = require('node:test');
const assert = require('node:assert');
const cp = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const Module = require('module');

const repo = path.resolve(__dirname, '../../..');
const alangBin = path.join(repo, 'bin', process.platform === 'win32' ? 'alang.cmd' : 'alang');
const canRun = (() => {
  try {
    cp.execFileSync(alangBin, ['version'], { stdio: 'pipe', shell: process.platform === 'win32' });
    return true;
  } catch {
    return false;
  }
})();

// ---- a stand-in for the parts of the vscode API the extension uses

class Position {
  constructor(line, character) { this.line = line; this.character = character; }
  isBefore(o) { return this.line < o.line || (this.line === o.line && this.character < o.character); }
  isEqual(o) { return this.line === o.line && this.character === o.character; }
}
class Range {
  constructor(a, b, c, d) {
    if (a instanceof Position) { this.start = a; this.end = b; } else { this.start = new Position(a, b); this.end = new Position(c, d); }
  }
  get isEmpty() { return this.start.isEqual(this.end); }
  isEqual(o) { return this.start.isEqual(o.start) && this.end.isEqual(o.end); }
  intersection(o) {
    const start = this.start.isBefore(o.start) ? o.start : this.start;
    const end = this.end.isBefore(o.end) ? this.end : o.end;
    return start.isBefore(end) || start.isEqual(end) ? new Range(start, end) : undefined;
  }
}
class Uri {
  constructor(scheme, fsPath) { this.scheme = scheme; this.fsPath = fsPath; }
  static file(p) { return new Uri('file', path.resolve(p)); }
  static parse(s) { return new Uri(s.split(':')[0], s); }
  toString() { return `${this.scheme}:${this.fsPath}`; }
}
class Kind {
  constructor(value) { this.value = value; }
  append(part) { return new Kind(`${this.value}.${part}`); }
  contains(other) { return other.value === this.value || other.value.startsWith(this.value + '.'); }
  intersects(other) { return this.contains(other) || other.contains(this); }
}
class WorkspaceEdit {
  constructor() { this.edits = new Map(); }
  set(uri, edits) { this.edits.set(uri.fsPath, edits); }
}
class MarkdownString {
  constructor(value = '') { this.value = value; }
  appendText(t) { this.value += t; return this; }
  appendMarkdown(t) { this.value += t; return this; }
  appendCodeblock(c, lang) { this.value += `\n\`\`\`${lang}\n${c}\n\`\`\`\n`; return this; }
}
class CancellationTokenSource {
  constructor() {
    this.listeners = [];
    this.token = { isCancellationRequested: false, onCancellationRequested: (f) => this.listeners.push(f) };
  }
  cancel() { this.token.isCancellationRequested = true; this.listeners.forEach((f) => f()); }
}
const noToken = { isCancellationRequested: false, onCancellationRequested: () => {} };

function makeDocument(file, text) {
  const lines = () => text.split('\n');
  const offsetAt = (p) => lines().slice(0, p.line).reduce((n, l) => n + l.length + 1, 0) + p.character;
  return {
    uri: Uri.file(file),
    languageId: 'alang',
    isUntitled: false,
    isDirty: false,
    get lineCount() { return lines().length; },
    lineAt: (i) => ({ text: lines()[i] }),
    getText: (r) => (r ? text.slice(offsetAt(r.start), offsetAt(r.end)) : text),
    positionAt: (offset) => {
      const before = text.slice(0, offset).split('\n');
      return new Position(before.length - 1, before[before.length - 1].length);
    },
    getWordRangeAtPosition(p, re) {
      const line = lines()[p.line];
      for (const m of line.matchAll(new RegExp(re.source, 'g'))) {
        if (m.index <= p.character && p.character <= m.index + m[0].length) return new Range(p.line, m.index, p.line, m.index + m[0].length);
      }
      return undefined;
    },
  };
}

function makeVscode(state) {
  const handlers = {};
  const on = (name) => (f) => { handlers[name] = f; return { dispose() {} }; };
  const register = (name) => (_selector, provider) => { state.providers[name] = provider; return { dispose() {} }; };
  return {
    handlers,
    Position, Range, Uri, WorkspaceEdit, MarkdownString, CancellationTokenSource,
    Diagnostic: class { constructor(range, message, severity) { Object.assign(this, { range, message, severity }); } },
    DiagnosticSeverity: { Error: 0, Warning: 1, Information: 2 },
    CodeAction: class { constructor(title, kind) { Object.assign(this, { title, kind }); } },
    CodeActionKind: { QuickFix: new Kind('quickfix'), Empty: new Kind(''), SourceFixAll: new Kind('source.fixAll') },
    CodeLens: class { constructor(range, command) { Object.assign(this, { range, command }); } },
    TextEdit: { replace: (range, newText) => ({ range, newText }) },
    Hover: class { constructor(contents, range) { Object.assign(this, { contents, range }); } },
    CompletionItem: class { constructor(label, kind) { Object.assign(this, { label, kind }); } },
    CompletionItemKind: { Function: 1, Keyword: 2, TypeParameter: 3 },
    SnippetString: class { constructor(value) { this.value = value; } },
    SignatureHelp: class {},
    SignatureInformation: class { constructor(label, documentation) { Object.assign(this, { label, documentation, parameters: [] }); } },
    ParameterInformation: class { constructor(label) { this.label = label; } },
    DocumentSymbol: class { constructor(name, detail, kind, range, selectionRange) { Object.assign(this, { name, detail, kind, range, selectionRange }); } },
    SymbolKind: { Function: 11, Struct: 22, Enum: 9, Constant: 13, Event: 23 },
    EventEmitter: class { constructor() { this.event = () => ({ dispose() {} }); } fire() {} },
    ViewColumn: { Beside: -2 },
    languages: {
      createDiagnosticCollection: () => ({
        set: (uri, list) => state.diagnostics.set(uri.fsPath, list),
        delete: (uri) => state.diagnostics.delete(uri.fsPath),
        get: (uri) => state.diagnostics.get(uri.fsPath),
        dispose() {},
      }),
      registerCodeActionsProvider: register('actions'),
      registerDocumentFormattingEditProvider: register('format'),
      registerHoverProvider: register('hover'),
      registerCompletionItemProvider: register('completion'),
      registerSignatureHelpProvider: register('signature'),
      registerDocumentSymbolProvider: register('symbols'),
      registerCodeLensProvider: register('lenses'),
    },
    workspace: {
      textDocuments: state.documents,
      workspaceFolders: [{ uri: Uri.file(repo) }],
      getWorkspaceFolder: () => ({ uri: Uri.file(repo) }),
      getConfiguration: () => ({ get: (_k, d) => d }),
      onDidOpenTextDocument: on('open'),
      onDidSaveTextDocument: on('save'),
      onDidCloseTextDocument: on('close'),
      onDidChangeConfiguration: on('config'),
    },
    window: {
      createOutputChannel: () => ({ appendLine: (l) => state.log.push(l), dispose() {} }),
      showWarningMessage: (m) => { state.log.push(m); return Promise.resolve(undefined); },
      setStatusBarMessage: (m) => state.log.push(m),
    },
    commands: { registerCommand: (name) => { state.commands.push(name); return { dispose() {} }; } },
    env: {},
  };
}

function loadExtension(state) {
  const vscode = makeVscode(state);
  const original = Module._load;
  Module._load = function load(request, ...rest) {
    return request === 'vscode' ? vscode : original.call(this, request, ...rest);
  };
  try {
    delete require.cache[require.resolve('../src/extension')];
    const ext = require('../src/extension');
    ext.activate({ subscriptions: [] });
    return { ext, vscode };
  } finally {
    Module._load = original;
  }
}

/** Applies TextEdits to a document's text, last first. */
function apply(doc, edits) {
  const text = doc.getText();
  const offset = (p) => doc.getText(new Range(new Position(0, 0), p)).length;
  return [...edits]
    .sort((a, b) => offset(b.range.start) - offset(a.range.start))
    .reduce((t, e) => t.slice(0, offset(e.range.start)) + e.newText + t.slice(offset(e.range.end)), text);
}

async function until(f) {
  for (let i = 0; i < 600 && !f(); i++) await new Promise((r) => setTimeout(r, 100));
  assert.ok(f(), 'timed out');
}

test('the extension against the real toolchain', { skip: !canRun && 'bin/alang cannot run here' }, async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'alang-vscode-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const libText = 'fn helper() -> i64 { return true; }\n';
  fs.writeFileSync(path.join(dir, 'lib.al'), libText);
  const mainText = [
    'import "lib.al";',
    'fn main() {',
    '    let s: str[8] = "é😀"; let x = 1;',
    '    if x > 0 && true { print(len(s), helper()); }',
    '}',
    'test "one" {',
    '    assert 1 == 1;',
    '}',
    '',
  ].join('\n');
  const mainFile = path.join(dir, 'main.al');
  fs.writeFileSync(mainFile, mainText);
  const state = { documents: [], diagnostics: new Map(), providers: {}, commands: [], log: [] };
  const doc = makeDocument(mainFile, mainText);
  state.documents.push(doc);
  const { vscode } = loadExtension(state);

  assert.ok(state.commands.includes('alang.run') && state.commands.includes('alang.test'));

  // Opening the file checked it: the parse error comes first, at the right UTF-16 column.
  await until(() => state.diagnostics.has(mainFile));
  const first = state.diagnostics.get(mainFile);
  assert.ok(first.length >= 1, state.log.join('\n'));
  const andAnd = first.find((d) => d.code.value === 'E0001');
  assert.ok(andAnd, JSON.stringify(first.map((d) => d.message)));
  assert.strictEqual(doc.getText(andAnd.range), '&&');
  assert.match(andAnd.message, /hint: use the keyword `and`/);
  assert.match(andAnd.code.target.fsPath, /ERRORS\.md#e0001$/);

  // Its quick fix replaces && with and.
  const actions = state.providers.actions.provideCodeActions(doc, andAnd.range, { diagnostics: [andAnd] });
  const fix = actions.find((a) => a.kind.value === 'quickfix');
  assert.ok(actions.some((a) => a.title === 'Explain E0001'));
  const fixed = apply(doc, fix.edit.edits.get(mainFile));
  assert.match(fixed, /if x > 0 and true/);

  // After the fix, the type errors show: the missing type on the line with the emoji, and one in lib.al.
  fs.writeFileSync(mainFile, fixed);
  const doc2 = makeDocument(mainFile, fixed);
  state.documents.splice(0, 1, doc2);
  state.diagnostics.clear();
  vscode.handlers.save(doc2);
  await until(() => (state.diagnostics.get(mainFile) || []).length > 0);
  const missing = state.diagnostics.get(mainFile).find((d) => d.code.value === 'E0219');
  assert.ok(missing, JSON.stringify(state.diagnostics.get(mainFile).map((d) => d.message)));
  assert.strictEqual(doc2.getText(missing.range), 'x');
  const fixAll = state.providers.actions.provideCodeActions(doc2, missing.range,
    { diagnostics: [], only: vscode.CodeActionKind.SourceFixAll });
  assert.match(apply(doc2, fixAll[0].edit.edits.get(mainFile)), /let x: i64 = 1;/);
  // The Source Action menu asks for any source action, and codeActionsOnSave for exactly ours; a quick
  // fix request gets no fix-all.
  for (const only of [new Kind('source'), vscode.CodeActionKind.SourceFixAll.append('alang')]) {
    const found = state.providers.actions.provideCodeActions(doc2, missing.range, { diagnostics: [], only });
    assert.ok(found.some((a) => a.title === 'Apply every ALang fix'), only.value);
  }
  assert.ok(!state.providers.actions.provideCodeActions(doc2, missing.range,
    { diagnostics: [], only: vscode.CodeActionKind.QuickFix }).some((a) => a.title === 'Apply every ALang fix'));
  if (!state.diagnostics.has(path.join(dir, 'lib.al'))) {
    // The checker stops at the first file with errors; lib.al shows once main.al is clean.
    const clean = apply(doc2, fixAll[0].edit.edits.get(mainFile));
    fs.writeFileSync(mainFile, clean);
    const doc3 = makeDocument(mainFile, clean);
    state.documents.splice(0, 1, doc3);
    vscode.handlers.save(doc3);
  }
  await until(() => (state.diagnostics.get(path.join(dir, 'lib.al')) || []).length > 0);
  const inLib = state.diagnostics.get(path.join(dir, 'lib.al'))[0];
  assert.strictEqual(inLib.code.value, 'E0201');
  assert.strictEqual(libText.split('\n')[0].slice(inLib.range.start.character, inLib.range.end.character), 'true');

  // Formatting lays the buffer out; a buffer that does not parse is left alone.
  const messy = makeDocument(path.join(dir, 'messy.al'), 'fn   main(){print(1);}\n');
  const edits = await state.providers.format.provideDocumentFormattingEdits(messy, {}, noToken);
  assert.strictEqual(apply(messy, edits), 'fn main() { print(1); }\n');
  const broken = makeDocument(path.join(dir, 'broken.al'), 'fn main() {\n');
  assert.deepStrictEqual(await state.providers.format.provideDocumentFormattingEdits(broken, {}, noToken), []);

  // Hovers describe builtins where they are called, and nowhere else.
  const line = 3;
  const lenAt = doc2.lineAt(line).text.indexOf('len(');
  const hover = state.providers.hover.provideHover(doc2, new Position(line, lenAt + 1));
  assert.match(hover.contents.value, /len\(x\) -> i64/);
  const field = makeDocument(path.join(dir, 'f.al'), 'let n: i64 = e.count;');
  assert.strictEqual(state.providers.hover.provideHover(field, new Position(0, 16)), undefined);

  // Signature help names the parameter being typed.
  const sigDoc = makeDocument(path.join(dir, 's.al'), 'let p: i64 = clamp(x, ');
  const help = state.providers.signature.provideSignatureHelp(sigDoc, new Position(0, 22));
  assert.strictEqual(help.signatures[0].label, 'clamp(x, lo, hi) -> T');
  assert.strictEqual(help.activeParameter, 1);

  // Code lenses: Run on fn main, Run test and Debug on the test.
  const lenses = state.providers.lenses.provideCodeLenses(doc2);
  assert.deepStrictEqual(lenses.map((l) => [l.range.start.line, l.command.title]),
    [[1, '▶ Run'], [5, '▶ Run test'], [5, 'Debug']]);
  assert.deepStrictEqual(lenses[1].command.arguments.slice(1), ['one']);

  // The outline lists main and the test, each spanning its block.
  const symbols = state.providers.symbols.provideDocumentSymbols(doc2);
  assert.deepStrictEqual(symbols.map((s) => [s.name, s.range.start.line, s.range.end.line]),
    [['main', 1, 4], ['test "one"', 5, 7]]);
});
