#!/usr/bin/env node
// Renders docs/demo/demo.mp4: Claude Code building a small ALang app (password.al) from a prompt,
// with a spoken English narration and an English subtitle track.
//
// The session is scripted, but nothing alang prints is: this script copies draft.al (Claude's first
// draft) and then password.al (the fixed file) into a scratch directory as password.al, runs every
// command the video shows there, and fails if an outcome changes (a draft that stops failing, a test
// that stops passing). The narration quotes numbers from those outputs. scene.html draws one frame
// as a pure function of time and holds each moment until its narration has been said; video.js
// (shared with make_why.js) has Playwright screenshot each frame and ffmpeg encode them with the mixed,
// loudness-normalized narration.
//
//   node docs/demo/make_demo.js --tts-models DIR --tts-python PYTHON   # writes docs/demo/demo.mp4
//   node docs/demo/make_demo.js --no-voice                             # silent, no TTS needed
//   node docs/demo/make_demo.js --still 20 frame.png                   # one frame, for the layout
//
// Options: --out FILE, --fps N, --voice NAME (a Kokoro voice, default af_heart), --speed X,
// --tts-cache DIR (default: $TMPDIR/alang-demo-voice). ALANG_DEMO_TTS_MODELS and
// ALANG_DEMO_TTS_PYTHON stand in for --tts-models and --tts-python.
//
// Needs JDK 21+ and Maven (for bin/alang), Node with Playwright and Chromium, ffmpeg with libx264
// and soxr, and the JetBrains Mono, Inter and DejaVu fonts. The narration needs a Python with
// kokoro-onnx and soundfile (`pip install kokoro-onnx soundfile`) and the Kokoro model files
// kokoro-v1.0.onnx and voices-v1.0.bin from the kokoro-onnx "model-files-v1.0" release.
'use strict';

const path = require('path');
const fs = require('fs');
const os = require('os');
const video = require('./video');
const { alang, expect, find } = video;

const HERE = __dirname;
const ROOT = video.ROOT;
const PROMPT = 'Build a password strength checker in ALang: a tool agents can call, with tests.';
const CALL_ARGS = '{"password": "hunter2"}';

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

// ------------------------------------------------------------------------------------- narration

// What the narrator says at each cue of scene.html, as the subtitles show it.
function narration(data) {
  const codes = data.diagnostics.map((d) => d.code).sort();
  if (codes.join() !== 'E0203,E0205') throw new Error(`narration: the draft's errors are now ${codes}`);
  const stack = find(data.outputs.check2, /worst-case stack (\d+) B/, 'stack bound');
  const score = find(data.outputs.run, /^score: (\d+)$/m, 'score');
  const called = JSON.parse(data.outputs.call).result;
  return {
    intro: "This is ALang, a small, strict programming language built for AI agents. Let's watch Claude Code "
      + 'build an app with it.',
    ask: 'We ask Claude for a password strength checker: a tool other agents can call, with tests.',
    read: "Claude first reads AGENTS.md, the repository's guide for agents: explicit types, fixed-size "
      + 'strings, no heap, no recursion.',
    write: 'Then it writes the program: an enum for the verdict, a struct for the report, and a function marked '
      + 'as a tool, with a doc comment so agents can discover it. Two tests go at the end.',
    errors: 'Next, Claude runs alang check. The draft has two mistakes, each reported with a code, the exact '
      + 'line and a hint. E0205: score was declared with let, so it cannot change. E0203: a plus sign cannot '
      + 'join a string and a number.',
    fix: 'Claude follows the hints. score becomes a var, and print gets its values separately, so nothing '
      + 'needs joining.',
    clean: `The second check is clean, and it bounds memory too: no heap at all, and at most ${stack} bytes of stack.`,
    test: 'Both tests pass. ALang is deterministic, so they pass the same way on every run and every machine.',
    run: `Running main prints a score of ${score} for a long, mixed password.`,
    call: 'And because rate_password is a tool, any agent can call it with JSON. hunter2 scores '
      + `${called.score} out of 5, and is rated ${String(called.strength).toLowerCase()}.`,
    done: "That's the whole loop: ask, write, check, fix from the hint, test and call. The diagnostics do most "
      + 'of the steering.',
    end: 'To try it yourself, clone the ALang repository and point Claude at AGENTS.md.',
  };
}

// The same line spelled for the speech model, which would otherwise say "uh-lang" or read file names
// and error codes as words.
function spoken(caption) {
  return caption
    .replace(/\balang check\b/g, 'A-Lang check')
    .replace(/\bALang\b/g, 'A-Lang')
    .replace(/\bAGENTS\.md\b/g, 'AGENTS dot M D')
    .replace(/\bE(\d)(\d)(\d)(\d)\b/g, 'E $1 $2 $3 $4')
    .replace(/\brate_password\b/g, 'rate password')
    .replace(/\bhunter2\b/g, 'Hunter two')
    .replace(/^score\b/, 'Score')
    .replace(/(\. )score\b/g, '$1Score');
}

// ------------------------------------------------------------------------------------------ main

async function main() {
  const opts = video.parseArgs(process.argv.slice(2), path.join(HERE, 'demo.mp4'));
  const data = collect();
  await video.render(opts, path.join(HERE, 'scene.html'), data, narration(data), spoken);
}

if (require.main === module) video.run(main);
module.exports = { collect, narration, spoken, subtitles: video.subtitles };
