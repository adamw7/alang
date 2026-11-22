'use strict';
// The end-to-end suite: runs inside a real VS Code (started by run.js), with the extension under
// development loaded and the real toolchain behind it. It goes through VS Code's own API, as the editor
// does, so activation, contributions, providers, commands and the terminal are all the real thing.
const assert = require('node:assert');
const fs = require('fs');
const path = require('path');
const vscode = require('vscode');

const workspace = process.env.ALANG_E2E_WORKSPACE;
const calls = process.env.ALANG_E2E_CALLS;
const file = (name) => path.join(workspace, name);
const uri = (name) => vscode.Uri.file(file(name));

const LIB = 'fn helper() -> i64 { return true; }\n';
const MAIN = [
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
const APP = [
  '/// Doubles a number.',
  'tool fn double(n: i64 = 21) -> i64',
  '    requires -1000 <= n and n <= 1000',
  '{',
  '    return n * 2;',
  '}',
  '',
  'fn main() {',
  '    print("hello ", double(21));',
  '}',
  '',
  'test "doubles one" {',
  '    assert double(1) == 2;',
  '}',
  '',
  'test "doubles two" {',
  '    assert double(2) == 4;',
  '}',
  '',
].join('\n');

// ---------------------------------------------------------------- helpers

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Waits for f() to give a truthy value, and returns it. */
async function until(f, what, ms = 60000) {
  const end = Date.now() + ms;
  for (;;) {
    const v = await f();
    if (v) return v;
    if (Date.now() > end) throw new Error(`timed out waiting for ${typeof what === 'function' ? what() : what}`);
    await sleep(100);
  }
}

function alangDiagnostics(name) {
  return vscode.languages.getDiagnostics(uri(name)).filter((d) => d.source === 'alang');
}

function codeOf(d) {
  return d.code && typeof d.code === 'object' ? String(d.code.value) : String(d.code);
}

async function open(name) {
  const doc = await vscode.workspace.openTextDocument(uri(name));
  await vscode.window.showTextDocument(doc);
  return doc;
}

/** The toolchain calls so far, oldest first: { dir, args, out } (out once the call has finished). */
function toolchainCalls() {
  return fs.readdirSync(calls).filter((f) => f.endsWith('.args'))
    .map((f) => Number(f.slice(0, -5))).sort((a, b) => a - b)
    .map((n) => {
      const [dir, args] = fs.readFileSync(path.join(calls, `${n}.args`), 'utf8').split('\n');
      const outFile = path.join(calls, `${n}.out`);
      return { n, dir, args, out: fs.existsSync(outFile) ? fs.readFileSync(outFile, 'utf8') : undefined };
    });
}

/** The number the next toolchain call will get. */
function nextCall() {
  return toolchainCalls().reduce((max, c) => Math.max(max, c.n + 1), 0);
}

/** Waits for a finished toolchain call made after `since` whose arguments are `args`. */
function finishedCall(since, args, what) {
  return until(() => toolchainCalls().find((c) => c.n >= since && c.args === args && c.out !== undefined),
    () => `${what || `alang ${args}`}; the calls since: ${JSON.stringify(toolchainCalls().filter((c) => c.n >= since)
      .map((c) => `${c.args}${c.out === undefined ? ' (running)' : ''}`))}; the terminals: ${terminalState()}`);
}

/** What became of the terminals, for a wait that timed out on a command sent to one. */
function terminalState() {
  return JSON.stringify(vscode.window.terminals.map((t) => ({
    name: t.name,
    exit: t.exitStatus ? t.exitStatus.code : null,
    active: vscode.window.activeTerminal === t,
  })));
}

/** Runs a command that asks for text in an input box, and accepts what the box offers. */
async function acceptingInput(run) {
  let done = false;
  const pending = Promise.resolve(run()).finally(() => { done = true; });
  for (let i = 0; i < 100 && !done; i++) {
    await sleep(300);
    if (!done) await vscode.commands.executeCommand('workbench.action.acceptSelectedQuickOpenItem');
  }
  return pending;
}

async function setSetting(key, value) {
  await vscode.workspace.getConfiguration('alang').update(key, value, vscode.ConfigurationTarget.Workspace);
}

// ---------------------------------------------------------------- the tests

const tests = [];
const test = (name, f) => tests.push({ name, f });

test('a .al file is ALang, and opening one activates the extension', async () => {
  const doc = await open('main.al');
  assert.strictEqual(doc.languageId, 'alang');
  const ext = vscode.extensions.getExtension('adamw7.alang');
  assert.ok(ext, 'the extension is installed');
  await until(() => ext.isActive, 'activation');
  const commands = await vscode.commands.getCommands(true);
  const contributed = ext.packageJSON.contributes.commands.map((c) => c.command);
  for (const c of [...contributed, 'alang.debugTest']) assert.ok(commands.includes(c), `${c} is registered`);
});

test('opening a file reports the first error, at the right place, linked to its docs', async () => {
  const doc = await open('main.al');
  const [andAnd] = await until(() => {
    const found = alangDiagnostics('main.al').filter((d) => codeOf(d) === 'E0001');
    return found.length > 0 && found;
  }, 'E0001 on main.al');
  assert.strictEqual(doc.getText(andAnd.range), '&&');
  assert.strictEqual(andAnd.severity, vscode.DiagnosticSeverity.Error);
  assert.match(andAnd.message, /hint: use the keyword `and`/);
  assert.match(andAnd.code.target.toString(), /ERRORS\.md#e0001$/);
});

test('the quick fix replaces && with and, and saving re-checks', async () => {
  const doc = await open('main.al');
  const andAnd = alangDiagnostics('main.al').find((d) => codeOf(d) === 'E0001');
  const actions = await vscode.commands.executeCommand('vscode.executeCodeActionProvider', doc.uri, andAnd.range);
  assert.ok(actions.some((a) => a.title === 'Explain E0001'), actions.map((a) => a.title).join(', '));
  const fix = actions.find((a) => a.kind && a.kind.value === 'quickfix' && a.edit);
  assert.ok(fix, 'a quick fix');
  assert.ok(await vscode.workspace.applyEdit(fix.edit));
  assert.match(doc.getText(), /if x > 0 and true/);
  await doc.save();
  // After the fix, the missing type shows, on the line with the emoji: columns are UTF-16 in VS Code.
  const missing = await until(() => alangDiagnostics('main.al').find((d) => codeOf(d) === 'E0219'), 'E0219');
  assert.strictEqual(doc.getText(missing.range), 'x');
});

test('Apply every ALang fix, then errors in an imported file show on that file', async () => {
  const doc = await open('main.al');
  // Asked for exactly as "editor.codeActionsOnSave": {"source.fixAll.alang": "explicit"} asks.
  const actions = await vscode.commands.executeCommand('vscode.executeCodeActionProvider', doc.uri,
    new vscode.Range(0, 0, 0, 0), vscode.CodeActionKind.SourceFixAll.append('alang').value);
  const all = actions.find((a) => a.title === 'Apply every ALang fix');
  assert.ok(all, actions.map((a) => a.title).join(', '));
  assert.ok(await vscode.workspace.applyEdit(all.edit));
  assert.match(doc.getText(), /let x: i64 = 1;/);
  await doc.save();
  // lib.al is not open, but checking main.al reports it there.
  const inLib = await until(() => alangDiagnostics('lib.al')[0], 'a diagnostic on lib.al');
  assert.strictEqual(codeOf(inLib), 'E0201');
  assert.strictEqual(LIB.slice(inLib.range.start.character, inLib.range.end.character), 'true');
  await until(() => alangDiagnostics('main.al').length === 0, 'main.al to be clean');
});

test('fixing the imported file clears its errors, whichever check finishes last', async () => {
  // Saving re-checks the open ALang files, so main.al must stay open: shown in a column of its own, not in the
  // preview tab that lib.al takes over, after which VS Code may close it before the save.
  const lib = await open('lib.al');
  const main = await vscode.workspace.openTextDocument(uri('main.al'));
  await vscode.window.showTextDocument(main, { viewColumn: vscode.ViewColumn.Beside, preview: false, preserveFocus: true });
  assert.ok(vscode.workspace.textDocuments.includes(main), 'main.al is open');
  const edit = new vscode.WorkspaceEdit();
  edit.replace(lib.uri, new vscode.Range(0, 0, lib.lineCount, 0), 'fn helper() -> i64 { return 1; }\n');
  assert.ok(await vscode.workspace.applyEdit(edit));
  const since = nextCall();
  await lib.save();
  // Saving re-checks main.al and lib.al at once. Checked on its own, lib.al has a warning (helper is
  // never called there) that checking main.al does not report; it must stay, whichever finishes last.
  const show = () => JSON.stringify([alangDiagnostics('lib.al').map((d) => codeOf(d)), alangDiagnostics('main.al').map((d) => codeOf(d))]);
  await finishedCall(since, 'check main.al --json');
  await finishedCall(since, 'check lib.al --json');
  await until(() => show() === JSON.stringify([['W0306'], []]), () => `lib.al: [W0306], main.al: []; got ${show()}`, 5000);
  await sleep(500);
  assert.strictEqual(show(), JSON.stringify([['W0306'], []]));
  assert.strictEqual(alangDiagnostics('lib.al')[0].severity, vscode.DiagnosticSeverity.Warning);
  // Back to one column for the tests that follow.
  await vscode.commands.executeCommand('workbench.action.closeEditorsInOtherGroups');
});

test('Format Document lays the file out; a file that does not parse is left alone', async () => {
  const doc = await open('messy.al');
  const edits = await vscode.commands.executeCommand('vscode.executeFormatDocumentProvider', doc.uri,
    { tabSize: 4, insertSpaces: true });
  const edit = new vscode.WorkspaceEdit();
  edit.set(doc.uri, edits);
  assert.ok(await vscode.workspace.applyEdit(edit));
  assert.strictEqual(JSON.stringify(doc.getText()), JSON.stringify('fn main() { print(1); }\n'));
  await doc.save();

  const broken = await open('broken.al');
  const none = await vscode.commands.executeCommand('vscode.executeFormatDocumentProvider', broken.uri,
    { tabSize: 4, insertSpaces: true });
  assert.ok(!none || none.length === 0);
});

test('hovers, completion and signature help know the builtins', async () => {
  const doc = await open('main.al');
  const line = 3;
  const at = new vscode.Position(line, doc.lineAt(line).text.indexOf('len(') + 1);
  const hovers = await vscode.commands.executeCommand('vscode.executeHoverProvider', doc.uri, at);
  const text = hovers.flatMap((h) => h.contents.map((c) => (typeof c === 'string' ? c : c.value))).join('\n');
  assert.match(text, /len\(x\) -> i64/);

  const sig = await open('sig.al');
  const pos = new vscode.Position(1, sig.lineAt(1).text.length);
  const completions = await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', sig.uri, pos);
  const labels = completions.items.map((i) => (typeof i.label === 'string' ? i.label : i.label.label));
  for (const want of ['clamp', 'push', 'while', 'Option']) assert.ok(labels.includes(want), `completion offers ${want}`);
  const help = await vscode.commands.executeCommand('vscode.executeSignatureHelpProvider', sig.uri, pos, ',');
  assert.strictEqual(help.signatures[help.activeSignature].label, 'clamp(x, lo, hi) -> T');
  assert.strictEqual(help.activeParameter, 1);
});

test('the outline lists functions and tests, each spanning its block', async () => {
  const doc = await open('app.al');
  const symbols = await vscode.commands.executeCommand('vscode.executeDocumentSymbolProvider', doc.uri);
  assert.deepStrictEqual(symbols.map((s) => [s.name, s.range.start.line, s.range.end.line]), [
    ['double', 1, 5], ['main', 7, 9], ['test "doubles one"', 11, 13], ['test "doubles two"', 15, 17],
  ]);
});

test('code lenses: Call and Fuzz on a tool fn, Run on main, Run test and Debug on each test', async () => {
  const doc = await open('app.al');
  const lenses = await vscode.commands.executeCommand('vscode.executeCodeLensProvider', doc.uri);
  assert.deepStrictEqual(lenses.map((l) => [l.range.start.line, l.command.title]), [
    [1, '▶ Call…'], [1, 'Fuzz'], [7, '▶ Run'],
    [11, '▶ Run test'], [11, 'Debug'], [15, '▶ Run test'], [15, 'Debug'],
  ]);
  await setSetting('codeLens', false);
  try {
    const none = await vscode.commands.executeCommand('vscode.executeCodeLensProvider', doc.uri);
    assert.strictEqual(none.length, 0);
  } finally {
    await setSetting('codeLens', undefined);
  }
});

test('Run runs fn main in the ALang terminal', async () => {
  const since = nextCall();
  await vscode.commands.executeCommand('alang.run', uri('app.al'));
  const call = await finishedCall(since, 'run app.al');
  assert.strictEqual(call.dir, workspace);
  assert.match(call.out, /hello 42\n/);
  assert.match(call.out, /exit 0\n$/);
  assert.ok(vscode.window.terminals.some((t) => t.name === 'ALang'), 'an ALang terminal');
});

test('Run test runs one test by name; Run Tests runs them all', async () => {
  const since = nextCall();
  await vscode.commands.executeCommand('alang.test', uri('app.al'), 'doubles two');
  const one = await finishedCall(since, 'test app.al --filter doubles two');
  assert.match(one.out, /1 test\(s\): 1 passed, 0 failed/);
  await vscode.commands.executeCommand('alang.test', uri('app.al'));
  const all = await finishedCall(since, 'test app.al');
  assert.match(all.out, /2 test\(s\): 2 passed, 0 failed/);
});

test('Call asks for the arguments and calls the tool', async () => {
  const since = nextCall();
  await acceptingInput(() => vscode.commands.executeCommand('alang.call', uri('app.al'), 'double'));
  const call = await finishedCall(since, 'call app.al double {}');
  assert.match(call.out, /"ok": true, "result": 42/);
});

test('Fuzz and the stack table run in the terminal', async () => {
  const since = nextCall();
  await vscode.commands.executeCommand('alang.fuzz', uri('app.al'));
  const fuzz = await finishedCall(since, 'fuzz app.al');
  assert.match(fuzz.out, /tool double: \d+ call\(s\), no traps/);
  await vscode.commands.executeCommand('alang.memory', uri('app.al'));
  const memory = await finishedCall(since, 'check app.al --memory');
  assert.match(memory.out, /worst-case stack/);
});

test('Run saves the file first', async () => {
  const doc = await open('app.al');
  await until(() => vscode.window.activeTextEditor && vscode.window.activeTextEditor.document === doc,
    'app.al to be the active editor');
  const edit = new vscode.WorkspaceEdit();
  edit.replace(doc.uri, new vscode.Range(8, 0, 8, doc.lineAt(8).text.length), '    print("saved ", double(5));');
  assert.ok(await vscode.workspace.applyEdit(edit));
  assert.ok(doc.isDirty);
  const since = nextCall();
  await vscode.commands.executeCommand('alang.run');
  assert.ok(!doc.isDirty);
  const call = await finishedCall(since, 'run app.al');
  assert.match(call.out, /saved 10\n/);
});

test('Show Tool Definitions opens the JSON beside the file', async () => {
  await vscode.commands.executeCommand('alang.tools', uri('app.al'));
  const shown = await until(() => {
    const d = vscode.window.activeTextEditor && vscode.window.activeTextEditor.document;
    return d && d.isUntitled && d.languageId === 'json' && d;
  }, 'the tool definitions');
  const tools = JSON.parse(shown.getText());
  assert.strictEqual(tools[0].name, 'double');
  assert.match(tools[0].description, /Doubles a number/);
});

test('Explain opens the explanation of a code', async () => {
  await vscode.commands.executeCommand('alang.explain', 'e0001');
  const shown = await until(() => {
    const d = vscode.window.activeTextEditor && vscode.window.activeTextEditor.document;
    return d && d.isUntitled && /^E0001: syntax error/.test(d.getText()) && d;
  }, 'the explanation');
  assert.strictEqual(shown.languageId, 'plaintext');
});

test('Debug asks where to stop and starts the debugger on the test', async () => {
  const since = nextCall();
  await acceptingInput(() => vscode.commands.executeCommand('alang.debugTest', uri('app.al'), 'doubles one'));
  await until(() => toolchainCalls().find((c) => c.n >= since && c.args === 'debug app.al --test doubles one'),
    'alang debug');
});

// ---------------------------------------------------------------- the runner

async function run() {
  fs.writeFileSync(file('lib.al'), LIB);
  fs.writeFileSync(file('main.al'), MAIN);
  fs.writeFileSync(file('app.al'), APP);
  fs.writeFileSync(file('messy.al'), 'fn   main(){print(1);}\n');
  fs.writeFileSync(file('broken.al'), 'fn main() {\n');
  fs.writeFileSync(file('sig.al'), 'fn main() {\n    let p: i64 = clamp(5, ');

  const failures = [];
  for (const t of tests) {
    const start = Date.now();
    try {
      await t.f();
      console.log(`  ok    ${t.name} (${Date.now() - start} ms)`);
    } catch (err) {
      failures.push(t.name);
      console.log(`  FAIL  ${t.name}\n${String(err && err.stack ? err.stack : err).replace(/^/gm, '        ')}`);
    }
  }
  for (const t of vscode.window.terminals) t.dispose();
  console.log(`\n${tests.length} test(s): ${tests.length - failures.length} passed, ${failures.length} failed`);
  if (failures.length) throw new Error(`failed: ${failures.join('; ')}`);
}

module.exports = { run };
