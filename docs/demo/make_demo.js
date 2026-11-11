#!/usr/bin/env node
// Renders docs/demo/demo.mp4: Claude Code building a small ALang app (password.al) from a prompt.
//
// The session is scripted, but nothing alang prints is: this script copies draft.al (Claude's first
// draft) and then password.al (the fixed file) into a scratch directory as password.al, runs every
// command the video shows there, and fails if an outcome changes (a draft that stops failing, a test
// that stops passing). scene.html draws one frame as a pure function of time; Playwright screenshots
// each frame and ffmpeg encodes them.
//
//   node docs/demo/make_demo.js                       # writes docs/demo/demo.mp4
//   node docs/demo/make_demo.js --out x.mp4 --fps 30
//   node docs/demo/make_demo.js --still 20 frame.png  # one frame, for checking the layout
//
// Needs JDK 21+ and Maven (for bin/alang), Node with Playwright and Chromium, ffmpeg with libx264,
// and the JetBrains Mono, Inter and DejaVu fonts.
'use strict';

const { chromium } = require('playwright');
const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const HERE = __dirname;
const ROOT = path.resolve(HERE, '..', '..');
const ALANG = path.join(ROOT, 'bin', 'alang');
const PROMPT = 'Build a password strength checker in ALang: a tool agents can call, with tests.';
const CALL_ARGS = '{"password": "hunter2"}';

function parseArgs(argv) {
  const opts = { out: path.join(HERE, 'demo.mp4'), fps: 30, still: null };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--out') opts.out = path.resolve(argv[++i]);
    else if (argv[i] === '--fps') opts.fps = Number(argv[++i]);
    else if (argv[i] === '--still') opts.still = { t: Number(argv[++i]), file: path.resolve(argv[++i]) };
    else throw new Error(`unknown argument: ${argv[i]}`);
  }
  return opts;
}

// Runs bin/alang in `dir`. `merged` is stdout and stderr interleaved as a terminal shows them,
// without the JVM's "Picked up JAVA_TOOL_OPTIONS" notice or the launcher's build message.
function alang(dir, args) {
  const env = { ...process.env, LC_ALL: 'C.UTF-8' };
  const run = (argv) => spawnSync(argv[0], argv.slice(1), { cwd: dir, env, encoding: 'utf8' });
  const merged = run(['sh', '-c', '"$0" "$@" 2>&1', ALANG, ...args]);
  if (merged.error) throw merged.error;
  const text = merged.stdout.split('\n')
    .filter((l) => !l.startsWith('Picked up ') && !l.startsWith('alang: building'))
    .join('\n').replace(/\s+$/, '');
  const plain = run([ALANG, ...args]);
  return { text, status: merged.status, stdout: plain.stdout };
}

function expect(what, result, status) {
  if (result.status !== status) {
    throw new Error(`${what}: expected exit ${status}, got ${result.status}\n${result.text}`);
  }
  return result.text;
}

function collect() {
  alang(ROOT, ['explain', 'E0001']); // builds target/alang.jar first if needed
  const draft = fs.readFileSync(path.join(HERE, 'draft.al'), 'utf8');
  const fixed = fs.readFileSync(path.join(HERE, 'password.al'), 'utf8');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'alang-demo-'));
  const file = path.join(dir, 'password.al');
  try {
    fs.writeFileSync(file, draft);
    const check1 = expect('check draft.al', alang(dir, ['check', 'password.al']), 1);
    const report = JSON.parse(alang(dir, ['check', 'password.al', '--json']).stdout);
    fs.writeFileSync(file, fixed);
    const outputs = {
      check1,
      check2: expect('check password.al', alang(dir, ['check', 'password.al']), 0),
      test: expect('test', alang(dir, ['test', 'password.al']), 0),
      run: expect('run', alang(dir, ['run', 'password.al']), 0),
      call: expect('call', alang(dir, ['call', 'password.al', 'rate_password', CALL_ARGS]), 0),
    };
    return {
      draft, fixed, outputs,
      prompt: PROMPT,
      callArgs: CALL_ARGS,
      diagnostics: report.diagnostics,
      agentsLines: fs.readFileSync(path.join(ROOT, 'AGENTS.md'), 'utf8').split('\n').length - 1,
    };
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  const data = collect();
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
    await page.addInitScript((d) => { window.DEMO = d; }, data);
    await page.goto('file://' + path.join(HERE, 'scene.html'));
    await page.evaluate(() => document.fonts.ready);
    const duration = await page.evaluate(() => window.DURATION);

    if (opts.still) {
      await page.evaluate((t) => window.renderAt(t), opts.still.t);
      await page.screenshot({ path: opts.still.file });
      console.log(`wrote ${opts.still.file} (t=${opts.still.t}s of ${duration.toFixed(1)}s)`);
      return;
    }

    const ffmpeg = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(opts.fps),
      '-c:v', 'png', '-i', '-', '-c:v', 'libx264', '-preset', 'slow', '-crf', '18', '-tune', 'animation',
      '-pix_fmt', 'yuv420p', '-movflags', '+faststart', opts.out], { stdio: ['pipe', 'inherit', 'inherit'] });
    const done = new Promise((resolve, reject) => {
      ffmpeg.on('error', reject);
      ffmpeg.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg exited with ${code}`))));
    });
    const frames = Math.ceil(duration * opts.fps);
    let lastKey = null, lastPng = null;
    for (let i = 0; i < frames; i++) {
      const key = await page.evaluate((t) => window.renderAt(t), i / opts.fps);
      if (key !== lastKey) {
        lastPng = await page.screenshot({ type: 'png' });
        lastKey = key;
      }
      if (!ffmpeg.stdin.write(lastPng)) await new Promise((r) => ffmpeg.stdin.once('drain', r));
      if (i % (opts.fps * 5) === 0) process.stderr.write(`\rframe ${i}/${frames}`);
    }
    ffmpeg.stdin.end();
    await done;
    process.stderr.write(`\rwrote ${opts.out}: ${frames} frames, ${duration.toFixed(1)}s\n`);
  } finally {
    await browser.close();
  }
}

main().catch((err) => {
  console.error(err.stack || String(err));
  process.exit(1);
});
