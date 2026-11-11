// Shared by the demo video scripts (make_demo.js, make_why.js): running commands for real, the spoken
// narration (narrate.py), its loudness-normalized mix, the English subtitles, and rendering a scene page
// frame by frame through Playwright into ffmpeg.
//
// A scene page draws one 1920x1080 frame as a pure function of time. The script sets window.DEMO (the
// sources, the real outputs and the length of every narration clip) before the page loads; the page
// sets window.DURATION and window.CUES ({name, t, seconds} for each clip it starts) and defines
// window.renderAt(seconds), which returns a key that changes whenever the picture does.
'use strict';

const { chromium } = require('playwright');
const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const HERE = __dirname;
const ROOT = path.resolve(HERE, '..', '..');
const ALANG = path.join(ROOT, 'bin', 'alang');
const LOUDNESS = 'I=-16:TP=-1.5:LRA=11'; // EBU R128 target for spoken web video

const USAGE_OPTIONS = 'Options: --out FILE, --fps N, --voice NAME (a Kokoro voice, default af_heart), --speed X,\n'
  + '--tts-cache DIR (default: $TMPDIR/alang-demo-voice). ALANG_DEMO_TTS_MODELS and\n'
  + 'ALANG_DEMO_TTS_PYTHON stand in for --tts-models and --tts-python.';

function parseArgs(argv, defaultOut) {
  const opts = {
    out: defaultOut, fps: 30, still: null, voice: 'af_heart', speed: 1.0, narrate: true,
    models: process.env.ALANG_DEMO_TTS_MODELS, python: process.env.ALANG_DEMO_TTS_PYTHON || 'python3',
    cache: path.join(os.tmpdir(), 'alang-demo-voice'),
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--out') opts.out = path.resolve(argv[++i]);
    else if (a === '--fps') opts.fps = Number(argv[++i]);
    else if (a === '--still') opts.still = { t: Number(argv[++i]), file: path.resolve(argv[++i]) };
    else if (a === '--voice') opts.voice = argv[++i];
    else if (a === '--speed') opts.speed = Number(argv[++i]);
    else if (a === '--tts-models') opts.models = path.resolve(argv[++i]);
    else if (a === '--tts-python') opts.python = argv[++i];
    else if (a === '--tts-cache') opts.cache = path.resolve(argv[++i]);
    else if (a === '--no-voice') opts.narrate = false;
    else throw new Error(`unknown argument: ${a}\n${USAGE_OPTIONS}`);
  }
  if (opts.narrate && !opts.models) {
    throw new Error('the narration needs the Kokoro model files: pass --tts-models DIR (or --no-voice)');
  }
  return opts;
}

// Lines a terminal would show that are about this machine, not the program: the JVM's "Picked up
// JAVA_TOOL_OPTIONS" notice and the launcher's build message.
const cleanOutput = (text) => text.split('\n')
  .filter((l) => !l.startsWith('Picked up ') && !l.startsWith('alang: building'))
  .join('\n').replace(/\s+$/, '');

// Runs bin/alang in `dir`. `text` is stdout and stderr interleaved as a terminal shows them.
function alang(dir, args) {
  const env = { ...process.env, LC_ALL: 'C.UTF-8' };
  const run = (argv) => spawnSync(argv[0], argv.slice(1), { cwd: dir, env, encoding: 'utf8' });
  const merged = run(['sh', '-c', '"$0" "$@" 2>&1', ALANG, ...args]);
  if (merged.error) throw merged.error;
  const plain = run([ALANG, ...args]);
  return { text: cleanOutput(merged.stdout), status: merged.status, stdout: plain.stdout };
}

// Runs a shell command line in `dir` exactly as a viewer would type it, stdout and stderr interleaved.
// `bin/alang` in `dir` is a link to this repository's launcher, so the command reads as it would in a
// checkout.
function shell(dir, command) {
  const bin = path.join(dir, 'bin');
  if (!fs.existsSync(path.join(bin, 'alang'))) {
    fs.mkdirSync(bin, { recursive: true });
    const quoted = `'${ALANG.replace(/'/g, "'\\''")}'`;
    fs.writeFileSync(path.join(bin, 'alang'), `#!/bin/sh\nexec ${quoted} "$@"\n`, { mode: 0o755 });
  }
  const env = { ...process.env, LC_ALL: 'C.UTF-8' };
  const r = spawnSync('sh', ['-c', `${command} 2>&1`], { cwd: dir, env, encoding: 'utf8' });
  if (r.error) throw r.error;
  return { text: cleanOutput(r.stdout), status: r.status };
}

function expect(what, result, status) {
  if (result.status !== status) {
    throw new Error(`${what}: expected exit ${status}, got ${result.status}\n${result.text}`);
  }
  return result.text;
}

function find(text, re, what) {
  const m = text.match(re);
  if (!m) throw new Error(`narration: no ${what} in\n${text}`);
  return m[1];
}

// ------------------------------------------------------------------------------------- narration

// `spoken` spells a caption for the speech model, which would otherwise say "uh-lang" or read file
// names and error codes as words.
function synthesize(opts, lines, spoken) {
  const spec = { voice: opts.voice, speed: opts.speed, lines: {} };
  for (const [name, caption] of Object.entries(lines)) spec.lines[name] = spoken(caption);
  const r = spawnSync(opts.python, ['-I', path.join(HERE, 'narrate.py'), opts.models, opts.cache],
    { input: JSON.stringify(spec), encoding: 'utf8', stdio: ['pipe', 'pipe', 'inherit'], maxBuffer: 1 << 24 });
  if (r.error) throw r.error;
  if (r.status !== 0) throw new Error(`narrate.py exited with ${r.status}`);
  return JSON.parse(r.stdout);
}

function ffmpegSync(args) {
  const r = spawnSync('ffmpeg', ['-hide_banner', '-nostats', ...args], { encoding: 'utf8', maxBuffer: 1 << 26 });
  if (r.error) throw r.error;
  if (r.status !== 0) throw new Error(`ffmpeg exited with ${r.status}\n${r.stderr.slice(-4000)}`);
  return r.stderr;
}

// Places every clip at its cue, cleans it up and normalizes the loudness in two passes, so the
// whole narration sits at -16 LUFS with true peaks under -1.5 dBTP. Writes 48 kHz stereo PCM.
function mixNarration(cues, clips, duration, out) {
  const inputs = cues.flatMap((c) => ['-i', clips[c.name].path]);
  const placed = cues.map((c, i) =>
    `[${i}:a]aresample=48000:resampler=soxr:precision=28,adelay=${Math.round(c.t * 1000)}:all=1[c${i}]`);
  const mixed = `${cues.map((_, i) => `[c${i}]`).join('')}amix=inputs=${cues.length}:normalize=0,`
    + 'highpass=f=60,acompressor=threshold=-22dB:ratio=2:attack=10:release=160:makeup=1,'
    + `apad=whole_dur=${duration},atrim=end=${duration}`;
  const graph = (tail) => `${placed.join(';')};${mixed},${tail}`;
  const log = ffmpegSync([...inputs, '-filter_complex', graph(`loudnorm=${LOUDNESS}:print_format=json`), '-f', 'null', '-']);
  const m = JSON.parse(log.slice(log.lastIndexOf('{'), log.lastIndexOf('}') + 1));
  const measured = `measured_I=${m.input_i}:measured_TP=${m.input_tp}:measured_LRA=${m.input_lra}`
    + `:measured_thresh=${m.input_thresh}:offset=${m.target_offset}:linear=true`;
  ffmpegSync(['-y', ...inputs, '-filter_complex',
    graph(`loudnorm=${LOUDNESS}:${measured},aresample=48000:resampler=soxr,aformat=channel_layouts=stereo`),
    '-c:a', 'pcm_s24le', out]);
}

// Where to break `text`: the space nearest `ideal` characters in, preferring one after a comma or
// colon, and never one that leaves either side longer than `limit` when another would not.
function bestBreak(text, ideal, bonus, limit) {
  let best = null;
  for (let i = text.indexOf(' '); i >= 0; i = text.indexOf(' ', i + 1)) {
    let cost = Math.abs(i - ideal) - (/[,:;]$/.test(text.slice(0, i)) ? bonus : 0);
    if (Math.max(i, text.length - i - 1) > limit) cost += 1000;
    if (!best || cost < best.cost) best = { cost, i };
  }
  return best.i;
}

// A sentence as captions of one or two lines of at most about 42 characters.
function captionChunks(sentence) {
  if (sentence.length > 84) {
    const i = bestBreak(sentence, sentence.length / 2, 14, 84);
    return [...captionChunks(sentence.slice(0, i)), ...captionChunks(sentence.slice(i + 1))];
  }
  if (sentence.length <= 42) return [sentence];
  const i = bestBreak(sentence, sentence.length / 2, 6, 44);
  return [`${sentence.slice(0, i)}\n${sentence.slice(i + 1)}`];
}

// SubRip captions: each cue's sentences as captions, sharing the clip's time by length.
function subtitles(cues, lines) {
  const stamp = (s) => {
    const ms = Math.round(s * 1000);
    const p = (n, w) => String(n).padStart(w, '0');
    return `${p(Math.floor(ms / 3600000), 2)}:${p(Math.floor(ms / 60000) % 60, 2)}:${p(Math.floor(ms / 1000) % 60, 2)},${p(ms % 1000, 3)}`;
  };
  const entries = [];
  for (const c of cues) {
    const chunks = lines[c.name].split(/(?<=[.!?])\s+/).flatMap(captionChunks);
    const total = chunks.reduce((n, ch) => n + ch.length, 0);
    let t = c.t;
    for (const ch of chunks) {
      const d = (c.seconds * ch.length) / total;
      entries.push(`${entries.length + 1}\n${stamp(t)} --> ${stamp(t + d)}\n${ch}\n`);
      t += d;
    }
  }
  return entries.join('\n');
}

// ---------------------------------------------------------------------------------------- render

// Synthesizes `lines` (cue name -> caption), opens `scene` with `data` as window.DEMO, and writes
// opts.out: H.264 video, the mixed narration, and the captions as an English subtitle track. With
// opts.still, writes one frame as a PNG instead.
async function render(opts, scene, data, lines, spoken) {
  const clips = opts.narrate ? synthesize(opts, lines, spoken) : {};
  data.voice = Object.fromEntries(Object.entries(clips).map(([name, c]) => [name, c.seconds]));

  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'alang-demo-av-'));
  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
    await page.addInitScript((d) => { window.DEMO = d; }, data);
    await page.goto('file://' + scene);
    await page.evaluate(() => document.fonts.ready);
    const duration = await page.evaluate(() => window.DURATION);
    const cues = await page.evaluate(() => window.CUES);

    if (opts.still) {
      await page.evaluate((t) => window.renderAt(t), opts.still.t);
      await page.screenshot({ path: opts.still.file });
      console.log(`wrote ${opts.still.file} (t=${opts.still.t}s of ${duration.toFixed(1)}s)`);
      return;
    }

    const av = [];
    if (opts.narrate) {
      const missing = Object.keys(lines).filter((name) => !cues.some((c) => c.name === name));
      if (missing.length) throw new Error(`${path.basename(scene)} never cues ${missing.join(', ')}`);
      const audio = path.join(work, 'narration.wav');
      const srt = path.join(work, 'narration.srt');
      mixNarration(cues, clips, duration, audio);
      fs.writeFileSync(srt, subtitles(cues, lines));
      av.push('-i', audio, '-i', srt, '-map', '0:v', '-map', '1:a', '-map', '2:s',
        '-c:a', 'aac', '-b:a', '192k', '-ar', '48000', '-c:s', 'mov_text',
        '-metadata:s:a:0', 'language=eng', '-metadata:s:s:0', 'language=eng',
        '-metadata:s:s:0', 'handler_name=English', '-disposition:s:0', 'default');
    }
    const ffmpeg = spawn('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'image2pipe', '-framerate', String(opts.fps),
      '-c:v', 'png', '-i', '-', ...av, '-c:v', 'libx264', '-preset', 'slow', '-crf', '18', '-tune', 'animation',
      '-pix_fmt', 'yuv420p', '-t', duration.toFixed(3), '-movflags', '+faststart', opts.out],
    { stdio: ['pipe', 'inherit', 'inherit'] });
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
    fs.rmSync(work, { recursive: true, force: true });
  }
}

// Runs `main` as a script's entry point.
function run(main) {
  main().catch((err) => {
    console.error(err.stack || String(err));
    process.exit(1);
  });
}

module.exports = {
  HERE, ROOT, alang, shell, expect, find, parseArgs, synthesize, mixNarration, subtitles, render, run,
};
