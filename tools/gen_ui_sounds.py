"""Generate the two EdgeStream interface sounds as small mono WAVs.

Design:
  focus  — a 14 ms soft, filtered noise tick. No musical pitch, no beep.
  confirm— a 75 ms round wooden "tok": low body + short filtered transient.

Run: python tools/gen_ui_sounds.py
"""
import math
import random
import struct
import wave
import os

RATE = 48_000
random.seed(20261107)


def lowpass(samples, cutoff):
    rc = 1.0 / (2.0 * math.pi * cutoff)
    dt = 1.0 / RATE
    alpha = dt / (rc + dt)
    y = 0.0
    out = []
    for x in samples:
        y += alpha * (x - y)
        out.append(y)
    return out


def highpass(samples, cutoff):
    rc = 1.0 / (2.0 * math.pi * cutoff)
    dt = 1.0 / RATE
    alpha = rc / (rc + dt)
    y = 0.0
    prev = 0.0
    out = []
    for x in samples:
        y = alpha * (y + x - prev)
        prev = x
        out.append(y)
    return out


def normalise(samples, peak):
    top = max(abs(x) for x in samples) or 1.0
    gain = peak / top
    return [max(-1.0, min(1.0, x * gain)) for x in samples]


def focus():
    n = int(0.014 * RATE)
    noise = [random.uniform(-1.0, 1.0) for _ in range(n)]
    noise = highpass(lowpass(noise, 3800.0), 450.0)
    out = []
    for i in range(n):
        t = i / RATE
        attack = min(1.0, t / 0.0007)
        decay = math.exp(-t / 0.0032)
        tail = min(1.0, (n - i) / (0.005 * RATE))
        tone = math.sin(2.0 * math.pi * 1750.0 * t) * 0.18
        out.append((noise[i] * 0.92 + tone) * attack * decay * tail)
    return normalise(out, 0.30)


def confirm():
    n = int(0.075 * RATE)
    noise = [random.uniform(-1.0, 1.0) for _ in range(n)]
    noise = highpass(lowpass(noise, 5200.0), 700.0)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / RATE
        progress = min(1.0, t / 0.030)
        freq = 168.0 - 58.0 * progress
        phase += 2.0 * math.pi * freq / RATE
        attack = min(1.0, t / 0.0018)
        body_decay = math.exp(-t / 0.020)
        tail = min(1.0, (n - i) / (0.018 * RATE))
        body = math.sin(phase) * 0.80 + math.sin(phase * 2.0) * 0.14 + math.sin(phase * 3.0) * 0.04
        click = noise[i] * math.exp(-t / 0.0018) * 0.20
        value = math.tanh((body + click) * 1.25) * attack * body_decay * tail
        out.append(value)
    return normalise(out, 0.62)


def write(path, samples):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        frames = b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in samples)
        w.writeframes(frames)
    print(f"{path}: {len(samples) / RATE * 1000:.1f} ms, {len(frames) + 44} bytes")


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    write(os.path.join(root, "app", "src", "main", "res", "raw", "ui_focus.wav"), focus())
    write(os.path.join(root, "app", "src", "main", "res", "raw", "ui_confirm.wav"), confirm())
