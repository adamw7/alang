#!/usr/bin/env python3
"""Synthesizes the demo's narration with Kokoro, an open text-to-speech model (kokoro-onnx).

    narrate.py MODEL_DIR CACHE_DIR < spec.json > clips.json

spec.json is {"voice": "af_heart", "speed": 1.0, "lines": {"intro": "Text to say.", ...}}. MODEL_DIR
holds kokoro-v1.0.onnx and voices-v1.0.bin (the kokoro-onnx "model-files-v1.0" release). Each line
becomes CACHE_DIR/<name>-<hash>.wav, 24 kHz mono with the silence around the speech trimmed, and is
reused while its text, voice and speed stay the same. Prints {"intro": {"path": ..., "seconds": ...}}.
"""
import hashlib
import json
import os
import sys

import numpy as np
import soundfile as sf

EDGE = 0.04  # seconds of room tone kept before and after the speech


def trim(samples, rate):
    loud = np.flatnonzero(np.abs(samples) > 10 ** (-50 / 20))
    if loud.size == 0:
        return samples
    pad = int(EDGE * rate)
    start, end = max(0, loud[0] - pad), min(samples.size, loud[-1] + pad)
    out = samples[start:end].astype(np.float32)
    fade = min(int(0.01 * rate), out.size // 2)
    out[:fade] *= np.linspace(0, 1, fade, dtype=np.float32)
    out[out.size - fade:] *= np.linspace(1, 0, fade, dtype=np.float32)
    return out


def main():
    model_dir, cache_dir = sys.argv[1], sys.argv[2]
    spec = json.load(sys.stdin)
    os.makedirs(cache_dir, exist_ok=True)
    kokoro = None
    clips = {}
    for name, text in spec["lines"].items():
        key = json.dumps([text, spec["voice"], spec["speed"], EDGE]).encode()
        path = os.path.join(cache_dir, f"{name}-{hashlib.sha256(key).hexdigest()[:16]}.wav")
        if not os.path.exists(path):
            if kokoro is None:
                from kokoro_onnx import Kokoro

                kokoro = Kokoro(os.path.join(model_dir, "kokoro-v1.0.onnx"), os.path.join(model_dir, "voices-v1.0.bin"))
            samples, rate = kokoro.create(text, voice=spec["voice"], speed=spec["speed"], lang="en-us")
            sf.write(path + ".tmp.wav", trim(np.asarray(samples), rate), rate, subtype="PCM_24")
            os.replace(path + ".tmp.wav", path)
        info = sf.info(path)
        clips[name] = {"path": path, "seconds": info.frames / info.samplerate}
    json.dump(clips, sys.stdout)


if __name__ == "__main__":
    main()
