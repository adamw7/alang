#!/usr/bin/env node
// Renders docs/demo/why.mp4: six bugs that classic languages let through (a string plus a number, an
// overflowing midpoint, a missing null check, a loop that never ends, a buffer overflow, a tool called
// with bad input), each next to the same program in ALang, with a spoken English narration and an
// English subtitle track. It is the companion of demo.mp4 and shares its look and its pipeline.
//
// Nothing the video shows is made up: this script copies the programs in why/ into a scratch directory,
// runs every command the video shows there (node, python3 and gcc for the classic side, bin/alang for
// ALang), and fails if an outcome changes (a classic program that stops misbehaving, an ALang program
// that stops being rejected or trapping with the same code). The narration quotes numbers from those
// outputs. Only the presentation is adjusted: Python's tracebacks show the file name without the
// scratch directory, and the one-line JSON of `alang call` is shown indented. why.html draws one frame
// as a pure function of time and holds each moment until its narration has been said; video.js renders
// and encodes it as for demo.mp4.
//
//   node docs/demo/make_why.js --tts-models DIR --tts-python PYTHON   # writes docs/demo/why.mp4
//   node docs/demo/make_why.js --no-voice                             # silent, no TTS needed
//   node docs/demo/make_why.js --still 20 frame.png                   # one frame, for the layout
//
// Options: --out FILE, --fps N, --voice NAME (a Kokoro voice, default af_heart), --speed X,
// --tts-cache DIR (default: $TMPDIR/alang-demo-voice). ALANG_DEMO_TTS_MODELS and
// ALANG_DEMO_TTS_PYTHON stand in for --tts-models and --tts-python.
//
// Needs everything make_demo.js does (JDK 21+ and Maven, Node with Playwright and Chromium, ffmpeg,
// the fonts, and kokoro-onnx with its model files for the narration), plus python3, gcc and coreutils'
// timeout on Linux. The outputs were recorded with Python 3.13, Node.js 22 and GCC 13.3 on x86-64.
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const video = require('./video');
const { alang, shell, expect, find } = video;

const HERE = __dirname;
const SOURCES = path.join(HERE, 'why');

// One chapter per bug. `classic` and `alang` are the two panes: the file shown, the command run, the exit
// status it must end with and a pattern its output must match. `culprit` finds the classic line at
// fault and `symptom` the output line that shows the damage; `json` is the machine-readable twin of the
// ALang command, run (not shown) to place the squiggle. A command without one prints JSON itself.
const CHAPTERS = [
  {
    id: 'convert', step: 'Conversions', title: 'No implicit conversions', tag: 'a string plus a number',
    classic: { lang: 'JavaScript', file: 'price.js', cmd: 'node price.js', status: 0,
      expect: /^total: 102$/, culprit: /price \+ tax/, symptom: /^total:/, verdict: 'Runs, and prints the wrong total' },
    alang: { file: 'price.al', cmd: 'bin/alang check price.al', status: 1, code: 'E0203',
      json: ['check', 'price.al', '--json'], verdict: 'Rejected before it runs: {code}, with a hint' },
  },
  {
    id: 'overflow', step: 'Overflow', title: 'Arithmetic never wraps silently', tag: 'the midpoint of two large ints',
    classic: { lang: 'C', file: 'midpoint.c', cmd: 'gcc midpoint.c -o midpoint && ./midpoint', status: 0,
      expect: /^-\d+$/, culprit: /lo \+ hi/, symptom: /^-\d+$/, verdict: 'Wraps silently: the midpoint is negative' },
    alang: { file: 'midpoint.al', cmd: 'bin/alang run midpoint.al', status: 1, code: 'R0001',
      json: ['run', 'midpoint.al', '--json'], verdict: 'Traps with {code} at the exact operator' },
  },
  {
    id: 'null', step: 'Null', title: 'No null: every case is handled', tag: 'a lookup that finds nothing',
    classic: { lang: 'Python', file: 'lookup.py', cmd: 'python3 lookup.py', status: 1,
      expect: /AttributeError: 'NoneType' object has no attribute 'lower'$/, culprit: /\.lower\(\)/,
      symptom: /^AttributeError/, verdict: 'Crashes at run time' },
    alang: { file: 'lookup.al', cmd: 'bin/alang check lookup.al', status: 1, code: 'E0306',
      json: ['check', 'lookup.al', '--json'], verdict: 'Rejected before it runs: {code}' },
  },
  {
    id: 'loops', step: 'Loops', title: 'Every program finishes', tag: 'a loop that misses its exit',
    classic: { lang: 'Python', file: 'countdown.py', cmd: 'timeout 3 python3 countdown.py; echo "exit $?"', status: 0,
      expect: /^exit 124$/, culprit: /^while/, symptom: /^exit 124$/, wait: 3.0, verdict: 'Never finishes: killed after 3 s' },
    alang: { file: 'countdown.al', cmd: 'bin/alang check countdown.al', status: 1, code: 'E0001',
      json: ['check', 'countdown.al', '--json'], verdict: 'Rejected until the loop states a limit' },
  },
  {
    id: 'memory', step: 'Memory', title: 'No heap, memory known up front', tag: 'a name longer than its buffer',
    classic: { lang: 'C', file: 'signup.c', cmd: 'gcc signup.c -o signup && ./signup mallory99', status: 0,
      expect: /^admin: yes$/, culprit: /strcpy/, symptom: /^admin: yes$/, verdict: 'Overwrites the next field: mallory is an admin' },
    alang: { file: 'signup.al', cmd: 'bin/alang check signup.al && bin/alang run signup.al', status: 1, code: 'R0004',
      json: ['run', 'signup.al', '--json'], verdict: 'Memory bounded before it runs; the overflow traps' },
  },
  {
    id: 'tools', step: 'Tools', title: 'Tools that fail safely', tag: 'a tool called with bad input',
    classic: { lang: 'JavaScript', file: 'bill.js', cmd: 'node bill.js', status: 0,
      expect: /^\[\]$/, culprit: /Array\(people\)/, symptom: /^\[\]$/, verdict: 'Returns [] and no error' },
    alang: { file: 'bill.al', cmd: `bin/alang call bill.al split_bill '{"total_cents": 1000, "people": 0}'`, status: 1,
      code: 'R0006', verdict: 'A coded JSON error; the contract is in the schema' },
  },
];

const readLines = (file) => fs.readFileSync(file, 'utf8').replace(/\n$/, '').split('\n');
const lineOf = (lines, re, what) => {
  const i = lines.findIndex((l) => re.test(l));
  if (i < 0) throw new Error(`${what}: no line matches ${re}`);
  return i;
};

// Where the ALang pane underlines the error: from the diagnostic, or from the trap and its carets.
function markOf(where, text, code) {
  if (where.diagnostics && where.diagnostics.length) {
    const d = where.diagnostics.find((x) => x.code === code);
    if (!d) throw new Error(`no ${code} among ${where.diagnostics.map((x) => x.code)}`);
    return { line: d.line, column: d.column, length: d.length, code, label: d.message };
  }
  const trap = where.trap || where.error;
  if (!trap || trap.code !== code) throw new Error(`expected trap ${code}, got ${JSON.stringify(trap)}`);
  const carets = text.match(/^\s*\d*\s*\|\s*(\^+)\s*$/m);
  const word = trap.source_line.slice(trap.column - 1).match(/^\w+/);
  return { line: trap.line, column: trap.column, length: carets ? carets[1].length : word[0].length, code, label: trap.title };
}

function collect() {
  alang(video.ROOT, ['explain', 'E0001']); // builds target/alang.jar first if needed
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'alang-why-'));
  try {
    for (const f of fs.readdirSync(SOURCES)) fs.copyFileSync(path.join(SOURCES, f), path.join(dir, f));
    const chapters = CHAPTERS.map((ch) => {
      const c = ch.classic, a = ch.alang;
      const classicLines = readLines(path.join(SOURCES, c.file));
      const classicOut = expect(c.cmd, shell(dir, c.cmd), c.status).split(`${dir}/`).join('');
      if (!c.expect.test(classicOut)) throw new Error(`${c.cmd}: the output no longer matches ${c.expect}\n${classicOut}`);
      const alangOut = expect(a.cmd, shell(dir, a.cmd), a.status);
      if (!alangOut.includes(a.code)) throw new Error(`${a.cmd}: no ${a.code} in\n${alangOut}`);
      const where = JSON.parse(a.json ? alang(dir, a.json).stdout : alangOut);
      const outLines = classicOut.split('\n');
      return {
        id: ch.id, step: ch.step, title: ch.title, tag: ch.tag,
        classic: {
          lang: c.lang, file: c.file, code: classicLines, cmd: c.cmd, output: classicOut, wait: c.wait || 0.7,
          culprit: lineOf(classicLines, c.culprit, c.file), symptom: lineOf(outLines, c.symptom, c.cmd),
          verdict: c.verdict,
        },
        alang: {
          lang: 'ALang', file: a.file, code: readLines(path.join(SOURCES, a.file)), cmd: a.cmd, output: alangOut,
          outputJson: a.json ? null : where, mark: markOf(where, alangOut, a.code),
          verdict: a.verdict.replace('{code}', a.code),
        },
      };
    });
    const tools = JSON.parse(alang(dir, ['tools', 'bill.al']).stdout);
    return { chapters, toolSchema: tools[0].input_schema };
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

// ------------------------------------------------------------------------------------- narration

// What the narrator says at each cue of why.html, as the subtitles show it.
function narration(data) {
  const ch = Object.fromEntries(data.chapters.map((c) => [c.id, c]));
  const total = find(ch.convert.classic.output, /^total: (\d+)$/m, 'total');
  const stack = find(ch.memory.alang.output, /worst-case stack (\d+) B/, 'stack bound');
  const heap = find(ch.memory.alang.output, /memory: heap (\d+) B/, 'heap size');
  const [, capacity, bytes] = ch.memory.alang.output.match(/capacity (\d+) \(length 0, appending (\d+) bytes\)/) || [];
  if (!capacity) throw new Error(`narration: no capacity in\n${ch.memory.alang.output}`);
  if (heap !== '0') throw new Error(`narration: signup.al now uses ${heap} B of heap`);
  const people = data.toolSchema.properties.people;
  if (people.minimum !== 1) throw new Error(`narration: the schema's minimum for people is ${people.minimum}`);
  const hint = ch.tools.alang.outputJson.error.hint;
  if (!/caller is at fault/.test(hint)) throw new Error(`narration: the R0006 hint changed: ${hint}`);
  return {
    intro: 'ALang is a small, strict language built for AI agents. Here are six bugs that classic languages let '
      + 'through, side by side with what ALang does instead.',
    convert_classic: `In JavaScript, a price read from a form is still a string, so price plus tax prints ${total}.`,
    convert_alang: 'ALang never converts implicitly. alang check rejects the line with E0203, and a hint.',
    overflow_classic: 'In C, the midpoint of two large integers overflows, and silently comes out negative.',
    overflow_alang: 'ALang never wraps. The same code traps with R0001, at the exact operator, on every run.',
    null_classic: 'In Python, a function that finds nothing quietly returns None, and the program crashes at run time.',
    null_alang: 'ALang has no null. Absence is an enum variant, and match must handle every one: forgetting '
      + 'Email.Missing is E0306.',
    loops_classic: 'This Python loop steps right over zero and never ends. All you get is a timeout.',
    loops_alang: 'In ALang, every while loop states a limit, and recursion is not allowed, so every program finishes.',
    memory_classic: `In C, a ${bytes}-byte name overflows an ${capacity}-byte buffer and quietly sets the next field. `
      + 'Mallory is now an admin.',
    memory_alang: `ALang has no heap, and alang check bounds memory before running: here, ${stack} bytes of stack. `
      + 'The overflow traps with R0004.',
    tools_classic: 'And a JavaScript tool called with zero people returns an empty list, with no error.',
    tools_alang: `An ALang tool states its contract, so its schema asks for at least ${people.minimum} person, and a bad `
      + 'call comes back as JSON with a code and a hint, never a crash.',
    outro: 'No silent mistakes, no hangs, and the same output on every run and every machine. To try it, clone '
      + 'the ALang repository and point your agent at AGENTS.md.',
  };
}

// The same line spelled for the speech model, which would otherwise say "uh-lang" or read file names
// and codes as words.
function spoken(caption) {
  return caption
    .replace(/\balang check\b/g, 'A-Lang check')
    .replace(/\bALang\b/g, 'A-Lang')
    .replace(/\bAGENTS\.md\b/g, 'AGENTS dot M D')
    .replace(/\b([ER])(\d)(\d)(\d)(\d)\b/g, '$1 $2 $3 $4 $5')
    .replace(/\bEmail\.Missing\b/g, 'Email dot Missing');
}

// ------------------------------------------------------------------------------------------ main

async function main() {
  const opts = video.parseArgs(process.argv.slice(2), path.join(HERE, 'why.mp4'));
  const data = collect();
  await video.render(opts, path.join(HERE, 'why.html'), data, narration(data), spoken);
}

if (require.main === module) video.run(main);
module.exports = { collect, narration, spoken };
