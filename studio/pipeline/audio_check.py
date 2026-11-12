"""Geluids-judge (WP3 + WP4). Gebruik:  python audio_check.py <rendermap>

  S1 geluid volgt beeld : correlatie omhullende sfx.wav (RMS per 50 ms) met de gebeurtenis-dichtheid >= 0.7
  S2 balans             : sfx 4..9 LU zachter dan de muziekstem (zoals ze in de mix zitten)
  S3 op het frame       : alleenstaande geluiden starten binnen 17 ms (1 frame) van hun geplande tijd
  M1 drop op de bouw    : grootste sprong in lage tonen (< 150 Hz) rond de bouwstart ligt binnen 17 ms ervan
  M3 boog               : lage tonen tijdens de bouw >= 6 dB sterker dan tijdens de terraform
  M4 geen klikken       : begin, einde en de stilte-tel voor de drop springen nergens > 0.1 per sample
Ontbreekt een bestand (bv. geen sfx), dan wordt die regel overgeslagen en niet geteld.
"""
import json, os, re, subprocess, sys
import numpy as np

SR = 48000


def load(path, mono=True):
    p = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", path, "-f", "f32le",
                        "-ac", "1" if mono else "2", "-ar", str(SR), "-"], capture_output=True)
    return np.frombuffer(p.stdout, dtype=np.float32)


def lufs(path):
    p = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af", "ebur128", "-f", "null", "-"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    m = re.search(r"I:\s+(-?[\d.]+) LUFS", p.stderr[p.stderr.rfind("Summary:"):])
    return float(m.group(1)) if m else None


def lowband(x, cutoff=150.0):
    a = 1 - np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x); s = 0.0
    for _ in range(2):
        for i in range(len(x)):
            s += a * (x[i] - s); y[i] = s
        x = y.copy(); s = 0.0
    return y


def rms_frames(x, hop):
    n = len(x) // hop
    return np.sqrt(np.mean(x[: n * hop].reshape(n, hop) ** 2, axis=1) + 1e-12)


def s1(d):
    ev = json.load(open(os.path.join(d, "sfx_events.json"), encoding="utf-8"))
    x = load(os.path.join(d, "sfx.wav"))
    hop = int(0.05 * SR)
    env = rms_frames(x, hop)
    dens = np.zeros(len(env))
    for t, _, g in ev["events"]:
        i = int(t / 0.05)
        if i < len(dens):
            dens[i] += g
    k = np.exp(-np.arange(6) / 2.0)  # geluiden duren ~0,2-0,3 s: dichtheid uitsmeren
    dens = np.convolve(dens, k)[: len(dens)]
    if np.std(env) == 0 or np.std(dens) == 0:
        return False, "geen variatie"
    r = float(np.corrcoef(env, dens)[0, 1])
    return r >= 0.7, f"correlatie {r:.2f} (min 0.70)"


def s2(d, gain):
    """Meet sfx zoals hij in de mix zit (met de mixversterking uit render.log)."""
    lm, ls = lufs(os.path.join(d, "music_stem.wav")), lufs(os.path.join(d, "sfx.wav"))
    if lm is None or ls is None:
        return False, "meting mislukt"
    diff = lm - (ls + 20 * np.log10(gain))
    return 4 <= diff <= 9, f"sfx {diff:.1f} LU onder de muziek (doel 4..9)"


def s3(d):
    ev = sorted(json.load(open(os.path.join(d, "sfx_events.json"), encoding="utf-8"))["events"])
    x = load(os.path.join(d, "sfx.wav"))
    iso = [e for i, e in enumerate(ev)
           if (i == 0 or e[0] - ev[i - 1][0] > 0.35) and (i == len(ev) - 1 or ev[i + 1][0] - e[0] > 0.35)]
    if not iso:
        return None, "geen alleenstaande geluiden"
    errs = []
    for t, _, _ in iso[:5]:
        i0, i1 = int((t - 0.05) * SR), int((t + 0.1) * SR)
        seg = np.abs(x[max(0, i0): i1])
        if len(seg) == 0 or seg.max() == 0:
            continue
        on = np.argmax(seg > 0.1 * seg.max()) + max(0, i0)
        errs.append(abs(on / SR - t) * 1000)
    if not errs:
        return False, "geen geluid gevonden op de geplande tijden"
    return max(errs) <= 17, f"afwijking max {max(errs):.1f} ms over {len(errs)} geluiden (max 17)"


def m1(d, tl):
    b = tl.get("build")
    if b is None:
        return None, "geen bouw"
    x = load(os.path.join(d, "music_stem.wav"))
    i0, i1 = int((b - 0.6) * SR), int((b + 0.6) * SR)
    lo = lowband(x[max(0, i0): i1].copy())
    hop = int(0.005 * SR)
    e = 20 * np.log10(rms_frames(lo, hop))
    jump = e[4:] - np.array([e[max(0, k - 4): k].mean() for k in range(4, len(e))])
    k = int(np.argmax(jump)) + 4
    t = max(0, i0) / SR + k * hop / SR
    err = abs(t - b) * 1000
    return err <= 17 + 5, f"drop op {t:.3f} s, bouw start {b:.3f} s, verschil {err:.0f} ms (max 17 + 5 meetstap)"


def m3(d, tl):
    b, fin, T = tl.get("build"), tl.get("finale"), tl.get("teaser", 0)
    if b is None:
        return None, "geen bouw"
    x = load(os.path.join(d, "music_stem.wav"))
    lo = lowband(x.copy(), 120)
    beat = tl.get("beat") or 0.5
    terra = lo[int(T * SR): int((b - beat) * SR)]
    build = lo[int(b * SR): int((fin or tl["total"]) * SR)]
    if len(terra) == 0 or len(build) == 0:
        return None, "secties te kort"
    db = 20 * np.log10(np.sqrt(np.mean(build ** 2)) / np.sqrt(np.mean(terra ** 2)))
    return db >= 6, f"lage tonen bouw t.o.v. terraform: {db:+.1f} dB (min +6)"


def m4(d, tl):
    """Ongewenste naden: de loop (einde -> begin), het einde, en de ingang van de stille tel.
    (Gewone drum-aanslagen springen bewust; die tellen niet.)"""
    x = load(os.path.join(d, "music_master.wav"))
    checks = [("loop einde->begin", abs(float(x[-1]) - float(x[0])))]
    if tl.get("build") and tl.get("beat"):
        stem = load(os.path.join(d, "music_stem.wav"))  # muziek alleen: de SFX mogen in de stille tel doorlopen
        i = int((tl["build"] - tl["beat"]) * SR)
        seg = stem[i: i + 480]  # de eerste 10 ms van de stille tel moeten echt stil zijn
        checks.append(("ingang stille tel", float(np.max(np.abs(np.diff(seg)))) if len(seg) > 1 else 0.0))
    seg = x[-480:]
    checks.append(("einde", float(np.max(np.abs(np.diff(seg)))) if len(seg) > 1 else 0.0))
    worst = max(v for _, v in checks)
    return worst < 0.1, ", ".join(f"{k} {v:.3f}" for k, v in checks) + " (max 0.1)"

def main():
    d = os.path.abspath(sys.argv[1])
    tl = json.load(open(os.path.join(d, "timeline.json"), encoding="utf-8"))
    res = {}
    if os.path.exists(os.path.join(d, "sfx.wav")) and os.path.exists(os.path.join(d, "sfx_events.json")):
        mix = os.path.join(d, "mix.json")
        g = json.load(open(mix, encoding="utf-8"))["sfx_gain"] if os.path.exists(mix) else 1.0
        res["S1 geluid volgt beeld"] = s1(d)
        res["S2 balans"] = s2(d, g)
        res["S3 op het frame"] = s3(d)
    if os.path.exists(os.path.join(d, "music_stem.wav")):
        res["M1 drop op de bouw"] = m1(d, tl)
        res["M3 boog"] = m3(d, tl)
    if os.path.exists(os.path.join(d, "music_master.wav")):
        res["M4 geen klikken"] = m4(d, tl)
    allok = True
    for k, (ok, msg) in res.items():
        mark = "PASS" if ok else ("SKIP" if ok is None else "FAIL")
        allok &= ok is not False
        print(f"[{mark}] {k}: {msg}")
    print("GELUID:", "ALLES GROEN" if allok else "NIET GESLAAGD")
    sys.exit(0 if allok else 1)


if __name__ == "__main__":
    main()
