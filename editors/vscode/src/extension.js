// ALang for VS Code: highlighting comes from the TextMate grammar; everything that needs the language
// itself (diagnostics, quick fixes, formatting, running) asks the `alang` toolchain, so the editor never
// disagrees with `alang check`.
'use strict';
const vscode = require('vscode');
const cp = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const lib = require('./alang');
const BUILTINS = require('./builtins.json');

const LANG = 'alang';
const ERRORS_DOC = 'https://github.com/adamw7/alang/blob/main/docs/ERRORS.md';
const KEYWORDS = ['fn', 'tool', 'struct', 'enum', 'const', 'let', 'var', 'mut', 'if', 'then', 'else', 'for', 'in',
  'while', 'limit', 'step', 'rev', 'match', 'return', 'break', 'continue', 'requires', 'ensures', 'assert', 'test',
  'traps', 'import', 'as', 'and', 'or', 'not', 'true', 'false', 'result'];
const TYPES = ['bool', 'i8', 'i16', 'i32', 'i64', 'u8', 'u16', 'u32', 'u64', 'f32', 'f64', 'str', 'list', 'map', 'set',
  'Option', 'Result'];

/** @type {vscode.DiagnosticCollection} */
let diagnostics;
/** @type {vscode.OutputChannel} */
let output;
/** The fix edits of each diagnostic, as the toolchain gave them. */
const fixes = new WeakMap();
/**
 * What checking each file reported, by the file it is in: checked file -> (file -> diagnostics). Checking
 * main.al reports errors in the lib.al it imports, and checking lib.al reports on lib.al too, so a file
 * shows the union of every check's latest report on it, whichever check finished last.
 */
const reported = new Map();
/** The running check of each file, so a newer one can replace it. */
const running = new Map();
let warnedMissing = false;

// ---------------------------------------------------------------- the toolchain

function config() {
  return vscode.workspace.getConfiguration('alang');
}

/**
 * The `alang` command: the setting, else bin/alang in a workspace folder (the ALang repository itself),
 * else `alang` on the PATH.
 */
function alangCommand(forFile) {
  const configured = config().get('path');
  if (configured) return configured.replace(/^~(?=$|[\\/])/, os.homedir());
  const folders = [];
  const owner = forFile && vscode.workspace.getWorkspaceFolder(vscode.Uri.file(forFile));
  if (owner) folders.push(owner);
  folders.push(...(vscode.workspace.workspaceFolders || []));
  const launcher = process.platform === 'win32' ? 'alang.cmd' : 'alang';
  for (const f of folders) {
    const candidate = path.join(f.uri.fsPath, 'bin', launcher);
    if (fs.existsSync(candidate) && fs.existsSync(path.join(f.uri.fsPath, 'grammar', 'ALang.g4'))) return candidate;
  }
  // On Windows the shell finds alang.exe (the native build) or alang.cmd (the Java archive) by PATHEXT.
  return 'alang';
}

/**
 * Runs `alang args...` in cwd and resolves with { code, stdout, stderr }; rejects only when the command
 * cannot be started. A non-zero exit (errors, failed tests) is a normal result.
 */
function runAlang(args, cwd, file, token) {
  const command = alangCommand(file);
  const windows = process.platform === 'win32';
  return new Promise((resolve, reject) => {
    // A .cmd launcher runs only through the shell on Windows, so quote the arguments for it there.
    const child = windows
      ? cp.spawn([command, ...args].map((a) => lib.shellQuote(a, true)).join(' '), { cwd, shell: true, windowsHide: true })
      : cp.spawn(command, args, { cwd });
    let stdout = '';
    let stderr = '';
    child.stdout.setEncoding('utf8').on('data', (d) => { stdout += d; });
    child.stderr.setEncoding('utf8').on('data', (d) => { stderr += d; });
    child.on('error', (err) => reject(err));
    child.on('close', (code) => resolve({ code, stdout, stderr }));
    if (token) token.onCancellationRequested(() => child.kill());
  });
}

function reportMissing(err) {
  output.appendLine(`alang: could not start \`${alangCommand()}\`: ${err.message}`);
  if (warnedMissing) return;
  warnedMissing = true;
  vscode.window.showWarningMessage(
    'ALang: the `alang` command was not found. Install ALang, or set "alang.path" to its bin/alang.',
    'Open Settings', 'Install Instructions',
  ).then((choice) => {
    if (choice === 'Open Settings') vscode.commands.executeCommand('workbench.action.openSettings', 'alang.path');
    if (choice === 'Install Instructions') vscode.env.openExternal(vscode.Uri.parse('https://github.com/adamw7/alang#install'));
  });
}

// ---------------------------------------------------------------- diagnostics

function isAlangFile(doc) {
  return doc.languageId === LANG && doc.uri.scheme === 'file';
}

function toRange(doc, line, column, length) {
  const lineIndex = Math.max(0, Math.min(line - 1, doc.lineCount - 1));
  const text = doc ? doc.lineAt(lineIndex).text : '';
  const start = lib.utf16Column(text, Math.max(0, column - 1));
  const end = lib.utf16Column(text, Math.max(0, column - 1 + Math.max(0, length)));
  return new vscode.Range(lineIndex, start, lineIndex, end);
}

/** The text of a file's lines, from its open editor or else from disk, to place columns on. */
function linesOf(file) {
  const open = vscode.workspace.textDocuments.find((d) => d.uri.fsPath === file);
  if (open) return open;
  let text = '';
  try { text = fs.readFileSync(file, 'utf8'); } catch { /* a missing file gets positions as given */ }
  const lines = text.split(/\r?\n/);
  return { lineCount: lines.length, lineAt: (i) => ({ text: lines[i] || '' }) };
}

function toDiagnostic(doc, d) {
  const range = toRange(doc, d.line || 1, d.column || 1, d.length || 1);
  const severity = d.severity === 'warning' ? vscode.DiagnosticSeverity.Warning
    : d.severity === 'info' || d.severity === 'note' ? vscode.DiagnosticSeverity.Information
      : vscode.DiagnosticSeverity.Error;
  const diag = new vscode.Diagnostic(range, lib.diagnosticMessage(d), severity);
  diag.source = 'alang';
  if (d.code) {
    diag.code = { value: d.code, target: vscode.Uri.parse(ERRORS_DOC + lib.errorDocAnchor(d.code)) };
  }
  if (Array.isArray(d.fix) && d.fix.length > 0) fixes.set(diag, d.fix);
  return diag;
}

async function check(doc) {
  if (!isAlangFile(doc)) return;
  const file = doc.uri.fsPath;
  const previous = running.get(file);
  if (previous) previous.cancel();
  const source = new vscode.CancellationTokenSource();
  running.set(file, source);
  const cwd = path.dirname(file);
  let result;
  try {
    result = await runAlang(['check', path.basename(file), '--json'], cwd, file, source.token);
  } catch (err) {
    reportMissing(err);
    return;
  } finally {
    if (running.get(file) === source) running.delete(file);
  }
  if (source.token.isCancellationRequested) return;
  const report = lib.parseJsonOutput(result.stdout);
  if (!report) {
    output.appendLine(`alang check ${file} gave no JSON (exit ${result.code}):\n${result.stderr}${result.stdout}`);
    return;
  }
  const byTarget = new Map();
  for (const [target, list] of lib.groupDiagnostics(report, file, cwd)) {
    const lines = target === file ? doc : linesOf(target);
    byTarget.set(target, list.map((d) => toDiagnostic(lines, d)));
  }
  const before = reported.get(file);
  reported.set(file, byTarget);
  publish([...(before ? before.keys() : []), ...byTarget.keys()]);
}

/** Shows on each of these files what every check last reported on it, each diagnostic once. */
function publish(targets) {
  for (const target of new Set(targets)) {
    const merged = [];
    const seen = new Set();
    for (const byTarget of reported.values()) {
      for (const d of byTarget.get(target) || []) {
        const r = d.range;
        const key = `${d.severity}|${d.code && d.code.value}|${r.start.line}:${r.start.character}-${r.end.line}:${r.end.character}|${d.message}`;
        if (seen.has(key)) continue;
        seen.add(key);
        merged.push(d);
      }
    }
    if (merged.length > 0) diagnostics.set(vscode.Uri.file(target), merged);
    else diagnostics.delete(vscode.Uri.file(target));
  }
}

// ---------------------------------------------------------------- quick fixes

function fixEdits(doc, edits) {
  return edits.map((e) => vscode.TextEdit.replace(toRange(doc, e.line, e.column, e.length), e.replacement));
}

class FixProvider {
  provideCodeActions(doc, range, context) {
    const actions = [];
    for (const diag of context.diagnostics) {
      if (diag.source !== 'alang') continue;
      const edits = fixes.get(diag);
      if (edits) {
        const action = new vscode.CodeAction(`Fix: ${firstLine(diag.message)}`, vscode.CodeActionKind.QuickFix);
        action.edit = new vscode.WorkspaceEdit();
        action.edit.set(doc.uri, fixEdits(doc, edits));
        action.diagnostics = [diag];
        action.isPreferred = true;
        actions.push(action);
      }
      const code = diag.code && (diag.code.value || diag.code);
      if (code) {
        const explain = new vscode.CodeAction(`Explain ${code}`, vscode.CodeActionKind.Empty);
        explain.command = { command: 'alang.explain', title: `Explain ${code}`, arguments: [String(code)] };
        explain.diagnostics = [diag];
        actions.push(explain);
      }
    }
    // `only` may be wider (source, from the Source Action menu) or exactly ours (source.fixAll.alang,
    // from "editor.codeActionsOnSave"); either way it asks for this action.
    if (context.only && context.only.intersects(vscode.CodeActionKind.SourceFixAll.append(LANG))) {
      const all = this.fixAll(doc);
      if (all) actions.push(all);
    }
    return actions;
  }

  /** Every fix in the file at once, skipping any that overlaps one already taken. */
  fixAll(doc) {
    const edits = [];
    for (const diag of diagnostics.get(doc.uri) || []) {
      const own = fixes.get(diag);
      if (!own) continue;
      const candidate = fixEdits(doc, own);
      if (candidate.some((c) => edits.some((e) => overlaps(e.range, c.range)))) continue;
      edits.push(...candidate);
    }
    if (edits.length === 0) return null;
    const action = new vscode.CodeAction('Apply every ALang fix', vscode.CodeActionKind.SourceFixAll.append('alang'));
    action.edit = new vscode.WorkspaceEdit();
    action.edit.set(doc.uri, edits);
    return action;
  }
}
FixProvider.metadata = {
  providedCodeActionKinds: [vscode.CodeActionKind.QuickFix, vscode.CodeActionKind.SourceFixAll.append('alang')],
};

/** Two edits clash when they share text, or insert at the same place; touching ones do not. */
function overlaps(a, b) {
  const shared = a.intersection(b);
  return shared !== undefined && (!shared.isEmpty || a.isEqual(b));
}

function firstLine(s) {
  return s.split('\n')[0];
}

// ---------------------------------------------------------------- formatting

class Formatter {
  async provideDocumentFormattingEdits(doc, _options, token) {
    // fmt reads a file, so give it the buffer as one; it does not resolve imports, so any directory will do.
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'alang-fmt-'));
    const tmp = path.join(dir, path.basename(doc.uri.fsPath || 'untitled.al'));
    try {
      fs.writeFileSync(tmp, doc.getText());
      const result = await runAlang(['fmt', path.basename(tmp)], dir, doc.uri.fsPath, token);
      if (token.isCancellationRequested) return [];
      if (result.code !== 0) {
        const why = result.stderr.split(/\r?\n/).filter((l) => l && !/^Picked up /.test(l));
        vscode.window.setStatusBarMessage(`ALang: not formatted: ${why[why.length - 1] || 'fmt failed'}`, 5000);
        return [];
      }
      if (result.stdout === doc.getText()) return [];
      const whole = new vscode.Range(doc.positionAt(0), doc.positionAt(doc.getText().length));
      return [vscode.TextEdit.replace(whole, result.stdout)];
    } catch (err) {
      reportMissing(err);
      return [];
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  }
}

// ---------------------------------------------------------------- hover and completion

const byName = new Map(BUILTINS.map((b) => [b.name, b]));

function builtinMarkdown(b) {
  const md = new vscode.MarkdownString();
  md.appendCodeblock(b.signature, LANG);
  md.appendMarkdown(`*${b.group}* builtin\n\n`);
  md.appendText(b.description);
  return md;
}

class Hovers {
  provideHover(doc, position) {
    const range = doc.getWordRangeAtPosition(position, /[A-Za-z_][A-Za-z0-9_]*/);
    if (!range) return undefined;
    const word = doc.getText(range);
    const b = byName.get(word);
    if (b) {
      // Only where it is called: a field named `count` is not the builtin.
      const after = doc.lineAt(position.line).text.slice(range.end.character);
      const before = doc.lineAt(position.line).text.slice(0, range.start.character);
      if (/^\s*\(/.test(after) && !/\.\s*$/.test(before)) return new vscode.Hover(builtinMarkdown(b), range);
    }
    if (/^[EWR][0-9]{4}$/.test(word)) {
      const md = new vscode.MarkdownString(`[${word} in docs/ERRORS.md](${ERRORS_DOC}${lib.errorDocAnchor(word)}) · `
        + `[explain](command:alang.explain?${encodeURIComponent(JSON.stringify([word]))})`);
      md.isTrusted = { enabledCommands: ['alang.explain'] };
      return new vscode.Hover(md, range);
    }
    return undefined;
  }
}

class Completions {
  provideCompletionItems(doc, position) {
    const before = doc.lineAt(position.line).text.slice(0, position.character);
    if (/\.\s*[A-Za-z_0-9]*$/.test(before)) return undefined;    // after `Color.` or `p.`, not a builtin
    const items = [];
    for (const b of BUILTINS) {
      const item = new vscode.CompletionItem(b.name, vscode.CompletionItemKind.Function);
      item.detail = b.signature;
      item.documentation = new vscode.MarkdownString().appendText(b.description);
      item.insertText = new vscode.SnippetString(`${b.name}($0)`);
      item.command = { command: 'editor.action.triggerParameterHints', title: '' };
      items.push(item);
    }
    for (const k of KEYWORDS) items.push(new vscode.CompletionItem(k, vscode.CompletionItemKind.Keyword));
    for (const t of TYPES) items.push(new vscode.CompletionItem(t, vscode.CompletionItemKind.TypeParameter));
    return items;
  }
}

class Signatures {
  provideSignatureHelp(doc, position) {
    const text = doc.getText(new vscode.Range(new vscode.Position(Math.max(0, position.line - 20), 0), position));
    // Walk back to the innermost open `(`, counting the commas at its level.
    let depth = 0;
    let commas = 0;
    let inString = false;
    for (let i = text.length - 1; i >= 0; i--) {
      const c = text[i];
      if (c === '"' && text[i - 1] !== '\\') inString = !inString;
      if (inString) continue;
      if (c === ')' || c === ']') depth++;
      else if (c === '[' && depth > 0) depth--;
      else if (c === '(') {
        if (depth > 0) { depth--; continue; }
        const name = /([A-Za-z_][A-Za-z0-9_]*)\s*$/.exec(text.slice(0, i));
        const b = name && byName.get(name[1]);
        if (!b) return undefined;
        const sig = new vscode.SignatureInformation(b.signature, new vscode.MarkdownString().appendText(b.description));
        const params = /\(([^)]*)\)/.exec(b.signature);
        if (params && params[1].trim()) {
          sig.parameters = params[1].split(',').map((p) => new vscode.ParameterInformation(p.trim()));
        }
        const help = new vscode.SignatureHelp();
        help.signatures = [sig];
        help.activeSignature = 0;
        help.activeParameter = sig.parameters.length ? Math.min(commas, sig.parameters.length - 1) : 0;
        return help;
      } else if (c === ',' && depth === 0) commas++;
      else if ((c === ';' || c === '{' || c === '}') && depth === 0) return undefined;
    }
    return undefined;
  }
}

// ---------------------------------------------------------------- outline

const SYMBOL_RE = /^[ \t]*(?:(tool)[ \t]+)?(fn|struct|enum|const|test)[ \t]+("(?:[^"\\]|\\.)*"|[A-Za-z_][A-Za-z0-9_]*)/;

class Symbols {
  provideDocumentSymbols(doc) {
    const symbols = [];
    for (let i = 0; i < doc.lineCount; i++) {
      const line = doc.lineAt(i);
      const m = SYMBOL_RE.exec(line.text);
      if (!m) continue;
      const kind = {
        fn: vscode.SymbolKind.Function,
        struct: vscode.SymbolKind.Struct,
        enum: vscode.SymbolKind.Enum,
        const: vscode.SymbolKind.Constant,
        test: vscode.SymbolKind.Event,
      }[m[2]];
      const name = m[2] === 'test' ? `test ${m[3]}` : m[3];
      const end = m[2] === 'const' ? i : blockEnd(doc, i);
      const range = new vscode.Range(i, 0, end, doc.lineAt(end).text.length);
      const nameAt = line.text.indexOf(m[3], m.index + m[0].length - m[3].length);
      const selection = new vscode.Range(i, nameAt, i, nameAt + m[3].length);
      symbols.push(new vscode.DocumentSymbol(name, m[1] ? 'tool' : '', kind, range, selection));
    }
    return symbols;
  }
}

/** The line of the `}` that closes the first `{` at or after a line (or that line, if none). */
function blockEnd(doc, from) {
  let depth = 0;
  let opened = false;
  for (let i = from; i < doc.lineCount && i < from + 5000; i++) {
    // Strings and comments in one pass, left to right: a `//` inside "http://..." does not start a comment.
    const text = doc.lineAt(i).text
      .replace(/"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|\/\/.*$/g, (m) => (m.startsWith('/') ? '' : '""'));
    for (const c of text) {
      if (c === '{') { depth++; opened = true; }
      if (c === '}') depth--;
      if (opened && depth === 0) return i;
    }
  }
  return from;
}

// ---------------------------------------------------------------- running

class Lenses {
  constructor() {
    this.changed = new vscode.EventEmitter();
    this.onDidChangeCodeLenses = this.changed.event;
  }

  provideCodeLenses(doc) {
    if (!config().get('codeLens', true)) return [];
    const lenses = [];
    for (const r of lib.findRunnables(doc.getText())) {
      const range = new vscode.Range(r.line, 0, r.line, 0);
      if (r.kind === 'main') {
        lenses.push(new vscode.CodeLens(range, { title: '▶ Run', command: 'alang.run', arguments: [doc.uri] }));
        if (r.takesInput) {
          lenses.push(new vscode.CodeLens(range,
            { title: 'Run with input file…', command: 'alang.runWithInput', arguments: [doc.uri] }));
        }
      } else if (r.kind === 'test') {
        lenses.push(new vscode.CodeLens(range,
          { title: '▶ Run test', command: 'alang.test', arguments: [doc.uri, r.name] }));
        lenses.push(new vscode.CodeLens(range,
          { title: 'Debug', command: 'alang.debugTest', arguments: [doc.uri, r.name] }));
      } else if (r.kind === 'tool') {
        lenses.push(new vscode.CodeLens(range,
          { title: '▶ Call…', command: 'alang.call', arguments: [doc.uri, r.name] }));
        lenses.push(new vscode.CodeLens(range, { title: 'Fuzz', command: 'alang.fuzz', arguments: [doc.uri] }));
      }
    }
    return lenses;
  }
}

let terminal;

/** Runs `alang args...` in the ALang terminal, from the file's directory. */
function inTerminal(file, args, stdinFile) {
  const windows = process.platform === 'win32';
  if (!terminal || terminal.exitStatus !== undefined) {
    terminal = vscode.window.createTerminal({ name: 'ALang' });
  }
  terminal.show(true);
  const dir = path.dirname(file);
  // Windows terminals default to PowerShell, which runs a quoted command with `&` and has no `<`.
  let cmd = [alangCommand(file), ...args].map((a) => lib.shellQuote(a, windows)).join(' ');
  if (windows) cmd = `& ${cmd}`;
  if (stdinFile) {
    cmd = windows
      ? `Get-Content -Raw ${lib.shellQuote(stdinFile, true)} | ${cmd}`
      : `${cmd} < ${lib.shellQuote(stdinFile, false)}`;
  }
  // One line, so the cd and the command reach the shell together, however busy it still is.
  terminal.sendText(`cd ${lib.shellQuote(dir, windows)}${windows ? ';' : ' &&'} ${cmd}`);
}

/** The file a command acts on: the one it was given, else the active editor's, saved first. */
async function targetFile(uri) {
  let doc;
  if (uri instanceof vscode.Uri) {
    doc = await vscode.workspace.openTextDocument(uri);
  } else {
    doc = vscode.window.activeTextEditor && vscode.window.activeTextEditor.document;
  }
  if (!doc || doc.languageId !== LANG) {
    vscode.window.showWarningMessage('ALang: open a .al file first.');
    return undefined;
  }
  if (doc.isUntitled) {
    vscode.window.showWarningMessage('ALang: save the file first.');
    return undefined;
  }
  if (doc.isDirty) await doc.save();
  return doc.uri.fsPath;
}

function fileCommand(build) {
  return async (uri, ...rest) => {
    const file = await targetFile(uri);
    if (!file) return;
    const args = await build(file, ...rest);
    if (args) inTerminal(file, args.args || args, args.stdin);
  };
}

async function explain(code) {
  if (!code) {
    code = await vscode.window.showInputBox({ prompt: 'Diagnostic or trap code', placeHolder: 'E0215',
      validateInput: (v) => (/^[A-Za-z][0-9]{4}$/.test(v.trim()) ? null : 'a code such as E0215, W0305 or R0006') });
    if (!code) return;
  }
  code = code.trim().toUpperCase();
  const folder = vscode.workspace.workspaceFolders && vscode.workspace.workspaceFolders[0];
  try {
    const result = await runAlang(['explain', code], folder ? folder.uri.fsPath : os.homedir());
    const text = result.code === 0 ? result.stdout : (result.stderr || result.stdout);
    const doc = await vscode.workspace.openTextDocument({ content: text, language: 'plaintext' });
    await vscode.window.showTextDocument(doc, { viewColumn: vscode.ViewColumn.Beside, preview: true });
  } catch (err) {
    reportMissing(err);
  }
}

async function showTools(uri) {
  const file = await targetFile(uri);
  if (!file) return;
  try {
    const result = await runAlang(['tools', path.basename(file)], path.dirname(file), file);
    const doc = await vscode.workspace.openTextDocument({ content: result.stdout || result.stderr, language: 'json' });
    await vscode.window.showTextDocument(doc, { viewColumn: vscode.ViewColumn.Beside, preview: true });
  } catch (err) {
    reportMissing(err);
  }
}

// ---------------------------------------------------------------- activation

function activate(context) {
  diagnostics = vscode.languages.createDiagnosticCollection(LANG);
  output = vscode.window.createOutputChannel('ALang');
  const selector = { language: LANG };
  const lenses = new Lenses();

  const checkIfWanted = (doc, when) => {
    if (isAlangFile(doc) && config().get(when, true)) check(doc);
  };

  context.subscriptions.push(
    diagnostics,
    output,
    vscode.workspace.onDidOpenTextDocument((doc) => checkIfWanted(doc, 'checkOnOpen')),
    vscode.workspace.onDidSaveTextDocument((doc) => {
      if (!isAlangFile(doc) || !config().get('checkOnSave', true)) return;
      // A saved file can change what its importers report, so re-check every open ALang file.
      for (const open of vscode.workspace.textDocuments) if (isAlangFile(open)) check(open);
    }),
    vscode.workspace.onDidCloseTextDocument((doc) => {
      if (!isAlangFile(doc)) return;
      const byTarget = reported.get(doc.uri.fsPath);
      if (!byTarget) return;
      reported.delete(doc.uri.fsPath);
      publish(byTarget.keys());
    }),
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration('alang.path')) warnedMissing = false;
      if (e.affectsConfiguration('alang.codeLens')) lenses.changed.fire();
    }),
    vscode.languages.registerCodeActionsProvider(selector, new FixProvider(), FixProvider.metadata),
    vscode.languages.registerDocumentFormattingEditProvider(selector, new Formatter()),
    vscode.languages.registerHoverProvider(selector, new Hovers()),
    vscode.languages.registerCompletionItemProvider(selector, new Completions()),
    vscode.languages.registerSignatureHelpProvider(selector, new Signatures(), '(', ','),
    vscode.languages.registerDocumentSymbolProvider(selector, new Symbols()),
    vscode.languages.registerCodeLensProvider(selector, lenses),

    vscode.commands.registerCommand('alang.check', async (uri) => {
      const file = await targetFile(uri);
      if (file) await check(await vscode.workspace.openTextDocument(file));
    }),
    vscode.commands.registerCommand('alang.run', fileCommand((file) => ['run', path.basename(file)])),
    vscode.commands.registerCommand('alang.runWithInput', fileCommand(async (file) => {
      const picked = await vscode.window.showOpenDialog({ canSelectMany: false, openLabel: 'Use as stdin',
        defaultUri: vscode.Uri.file(path.dirname(file)) });
      return picked && picked[0] ? { args: ['run', path.basename(file)], stdin: picked[0].fsPath } : undefined;
    })),
    vscode.commands.registerCommand('alang.test', fileCommand((file, name) =>
      (typeof name === 'string' ? ['test', path.basename(file), '--filter', name] : ['test', path.basename(file)]))),
    vscode.commands.registerCommand('alang.debugTest', fileCommand(async (file, name) => {
      const at = await vscode.window.showInputBox({ prompt: `Stop where, in test "${name}"?`,
        placeHolder: 'a line number, a function name, or "12 if i == 3"' });
      if (at === undefined) return undefined;
      const args = ['debug', path.basename(file), '--test', name];
      if (at.trim()) args.push('--break', at.trim());
      return args;
    })),
    vscode.commands.registerCommand('alang.fuzz', fileCommand((file) => ['fuzz', path.basename(file)])),
    vscode.commands.registerCommand('alang.call', fileCommand(async (file, tool) => {
      if (!tool) {
        tool = await vscode.window.showInputBox({ prompt: 'Tool to call' });
        if (!tool) return undefined;
      }
      const json = await vscode.window.showInputBox({ prompt: `Arguments of ${tool}, as a JSON object`, value: '{}',
        validateInput: (v) => { try { JSON.parse(v); return null; } catch (e) { return e.message; } } });
      if (json === undefined) return undefined;
      if (process.platform !== 'win32') return ['call', path.basename(file), tool, json];
      // Windows shells mangle quotes in arguments, so hand the JSON over on stdin, as `alang call ... -` reads it.
      const args = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'alang-call-')), 'args.json');
      fs.writeFileSync(args, json);
      return { args: ['call', path.basename(file), tool, '-'], stdin: args };
    })),
    vscode.commands.registerCommand('alang.build', fileCommand(async (file) => {
      const out = await vscode.window.showSaveDialog({ filters: { C: ['c'] },
        defaultUri: vscode.Uri.file(file.replace(/\.al$/, '') + '.c') });
      return out ? ['build', path.basename(file), '-o', out.fsPath] : undefined;
    })),
    vscode.commands.registerCommand('alang.memory', fileCommand((file) => ['check', path.basename(file), '--memory'])),
    vscode.commands.registerCommand('alang.tools', showTools),
    vscode.commands.registerCommand('alang.explain', explain),
  );

  for (const doc of vscode.workspace.textDocuments) checkIfWanted(doc, 'checkOnOpen');
}

function deactivate() {
  for (const source of running.values()) source.cancel();
}

module.exports = { activate, deactivate };
