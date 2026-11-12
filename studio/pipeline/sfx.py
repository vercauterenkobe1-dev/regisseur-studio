"""Geluidseffecten (WP3): de echte Minecraft-geluiden uit sfx.json (van de mod) op de video-tijdlijn mixen.

Invoer : sfx.json  {sounds: {event: [{file, volume, pitch}]}, events: [[t, event, n, kind]], finale, finaleSounds, xp}
Uitvoer: sfx.wav (48 kHz, mono float -> stereo 24-bit) en sfx_events.json (video-tijden, voor sfx_check.py)
Tijden in sfx.json zijn opname-tijd; clips (uit render.py) zeggen welk stuk opname waar in de video staat.
"""
import json, math, os, subprocess
import numpy as np

SR = 48000
GAIN = {"break": 0.55, "rise": 0.30, "place": 0.65, "finale": 0.9, "xp": 0.5}
MAX_RATE = 24.0   # hoogstens zoveel tikken per seconde per geluid; daarboven luider, niet drukker


def decode(path):
    p = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", path, "-f", "f32le", "-ac", "1",
                        "-ar", str(SR), "-"], capture_output=True)
    if p.returncode != 0:
        return None
    return np.frombuffer(p.stdout, dtype=np.float32).copy()


def clip_map(clips, td=0.0):
    """[(in, dur, offset_in_video)] voor elke clip."""
    out, off = [], 0.0
    for i, c in enumerate(clips):
        out.append((c.get("in", 0.0), c["dur"], off))
        off += c["dur"] - td
    return out


def to_video(t, cmap):
    return [off + t - cin for cin, dur, off in cmap if cin <= t < cin + dur]


def thin(evs):
    """evs: [(t, n)] gesorteerd -> samengevoegd tot hoogstens MAX_RATE per seconde."""
    out = []
    for t, n in evs:
        if out and t - out[-1][0] < 1.0 / MAX_RATE:
            out[-1] = (out[-1][0], out[-1][1] + n)
        else:
            out.append((t, n))
    return out


def build(sfx_json, clips, total, out_wav, events_out, counter_ends=(), td=0.0, seed=7):
    base = os.path.dirname(os.path.abspath(sfx_json))
    with open(sfx_json, encoding="utf-8-sig") as f:
        data = json.load(f)
    rng = np.random.default_rng(seed)
    cache = {}

    def variant(key):
        vs = data["sounds"].get(key)
        if not vs:
            return None, 1.0, 1.0
        v = vs[int(rng.integers(len(vs)))]
        fp = os.path.join(base, v["file"])
        if fp not in cache:
            cache[fp] = decode(fp)
        return cache[fp], float(v.get("volume", 1.0)), float(v.get("pitch", 1.0))

    buf = np.zeros(int((total + 2) * SR), dtype=np.float32)
    placed = []

    def put(t, key, gain, pitch_jitter=0.08, pitch_mul=1.0):
        snd, vol, pitch = variant(key)
        if snd is None or t < 0 or t >= total:
            return
        p = pitch * pitch_mul * (1 + rng.uniform(-pitch_jitter, pitch_jitter))
        if abs(p - 1) > 1e-3:  # toonhoogte = herbemonsteren (zoals de game doet)
            n_out = max(1, int(len(snd) / p))
            snd = np.interp(np.arange(n_out) * p, np.arange(len(snd)), snd).astype(np.float32)
        i = int(round(t * SR))
        j = min(len(buf), i + len(snd))
        buf[i:j] += snd[: j - i] * gain * vol
        placed.append([round(t, 4), key, round(gain * vol, 4)])

    cmap = clip_map(clips, td)
    by_key = {}
    for t, key, n, kind in data.get("events", []):
        by_key.setdefault((key, kind), []).append((float(t), int(n)))
    for (key, kind), evs in by_key.items():
        for t, n in thin(sorted(evs)):
            g = GAIN.get(kind, 0.5) * min(1.0, 0.35 * math.sqrt(n))
            for vt in to_video(t, cmap):
                put(vt, key, g)
    fin = data.get("finale", -1)
    if fin and fin > 0:
        for vt in to_video(fin, cmap):
            for k, key in enumerate(data.get("finaleSounds", [])):
                put(vt + 0.06 * k + (0.45 if "twinkle" in key else 0), key, GAIN["finale"], 0.03)
    xp = data.get("xp")
    if xp:  # teller bereikt eindstand: 'pling', elke volgende iets hoger
        for k, t in enumerate(sorted(counter_ends)):
            snd_t = t
            if 0 <= snd_t < total:
                put(snd_t, xp, GAIN["xp"], 0.0, 0.9 * 1.06 ** k)
    peak = float(np.max(np.abs(buf))) if len(buf) else 0
    if peak > 0.95:  # geen clipping in de tussenstem
        buf *= 0.95 / peak
    buf = buf[: int(total * SR)]
    p = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "1",
                        "-i", "-", "-ac", "2", "-c:a", "pcm_s24le", out_wav], input=buf.tobytes())
    if p.returncode != 0:
        raise RuntimeError("sfx.wav schrijven mislukt")
    with open(events_out, "w", encoding="utf-8") as f:
        json.dump({"events": placed, "total": total}, f)
    return len(placed)
