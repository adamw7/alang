'use strict';
// Runs the end-to-end suite (suite.js) inside a real VS Code, downloaded into .vscode-test/, with the
// extension loaded from this folder and the real toolchain (../../bin/alang) behind it.
//
//   npm run test:e2e                          # the latest stable VS Code
//   ALANG_VSCODE_VERSION=1.80.0 npm run test:e2e   # the oldest the extension supports
//
// On Linux without a display, run it under `xvfb-run -a`.
const cp = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { runTests } = require('@vscode/test-electron');

const extension = path.resolve(__dirname, '../..');
const repo = path.resolve(extension, '../..');
const real = path.join(repo, 'bin', 'alang');

async function main() {
  if (process.platform === 'win32') {
    console.log('test:e2e: skipped on Windows (the toolchain wrapper is a shell script)');
    return;
  }
  // Build the toolchain now, so the first check inside VS Code is not a minute-long Maven build.
  cp.execFileSync(real, ['version'], { stdio: 'inherit' });

  // A workspace of its own, whose settings point the extension at a wrapper around bin/alang: the wrapper
  // logs every call, and keeps what the terminal commands print, so the suite can see what ran.
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'alang-e2e-'));
  const workspace = path.join(root, 'workspace');
  const calls = path.join(root, 'calls');
  fs.mkdirSync(path.join(workspace, '.vscode'), { recursive: true });
  fs.mkdirSync(calls);
  const wrapper = path.join(root, 'alang');
  fs.writeFileSync(wrapper, [
    '#!/bin/sh',
    `REAL=${shQuote(real)}`,
    `CALLS=${shQuote(calls)}`,
    // Number each call: mkdir is atomic, so two checks running at once never take the same number.
    'n=0',
    'while ! mkdir "$CALLS/$n.lock" 2>/dev/null; do n=$((n + 1)); done',
    'printf "%s\\n%s\\n" "$PWD" "$*" > "$CALLS/$n.args"',
    '"$REAL" "$@" > "$CALLS/$n.tmp" 2> "$CALLS/$n.err"',
    'status=$?',
    'cat "$CALLS/$n.tmp"',
    'cat "$CALLS/$n.err" >&2',
    'printf "\\nexit %s\\n" "$status" >> "$CALLS/$n.tmp"',
    'mv "$CALLS/$n.tmp" "$CALLS/$n.out"',
    'exit $status',
    '',
  ].join('\n'), { mode: 0o755 });
  fs.writeFileSync(path.join(workspace, '.vscode', 'settings.json'), JSON.stringify({
    'alang.path': wrapper,
    'terminal.integrated.defaultProfile.linux': 'sh',
    'terminal.integrated.profiles.linux': { sh: { path: '/bin/sh' } },
    'terminal.integrated.defaultProfile.osx': 'sh',
    'terminal.integrated.profiles.osx': { sh: { path: '/bin/sh' } },
    'workbench.startupEditor': 'none',
    'extensions.ignoreRecommendations': true,
    'telemetry.telemetryLevel': 'off',
  }, null, 2));

  let failed = false;
  try {
    await runTests({
      version: process.env.ALANG_VSCODE_VERSION || 'stable',
      extensionDevelopmentPath: extension,
      extensionTestsPath: path.join(__dirname, 'suite.js'),
      extensionTestsEnv: { ALANG_E2E_WORKSPACE: workspace, ALANG_E2E_CALLS: calls },
      launchArgs: [
        workspace,
        '--disable-extensions',
        '--disable-workspace-trust',
        '--skip-welcome',
        '--skip-release-notes',
        '--disable-gpu',
        '--user-data-dir', path.join(root, 'user-data'),
      ],
    });
  } catch (err) {
    console.error(err && err.message ? err.message : err);
    failed = true;
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
  if (failed) process.exit(1);
}

function shQuote(s) {
  return `'${s.replace(/'/g, "'\\''")}'`;
}

main();
