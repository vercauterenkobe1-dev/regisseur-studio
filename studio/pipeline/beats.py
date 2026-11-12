"""Beat-herkenning zonder extra libraries (alleen Python + FFmpeg).

Gebruik:  python beats.py muziek.mp3   ->  JSON op stdout:
  {"bpm": 128.0, "period": 0.4688, "beats": [...s], "downbeats": [...s], "duration": 182.4, "confidence": 0.8}

Werkwijze (klassieke onset-gebaseerde beat tracking):
  1. FFmpeg decodeert naar mono 8 kHz: een volle band en een lage band (kick, < 150 Hz).
  2. Energie per 10 ms, log, positieve verschillen = onset-sterkte; lokaal gemiddelde eraf.
  3. Tempo: autocorrelatie van de onset-sterkte, met voorkeur rond 120 BPM en controle op dubbel/half tempo.
  4. Precisie: rond elke voorspelde tel de echte piek zoeken en een rechte lijn fitten (tijd = start + k * periode).
  5. Downbeat: de tel uit vier met de meeste lage-band-energie (de kick valt meestal op tel 1).
Getest met test_beats.py.
"""
import json, math, struct, subprocess, sys

SR, HOP = 8000, 80          # 100 analyseframes per seconde
FPS = SR // HOP


def decode(path, lowpass):
    af = "lowpass=f=150,lowpass=f=150" if lowpass else "highpass=f=30"
    p = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-i", path, "-vn", "-ac", "1",
                        "-ar", str(SR), "-af", af, "-f", "s16le", "-"], capture_output=True)
    if p.returncode != 0:
        raise SystemExit("ffmpeg kon de muziek niet lezen: " + p.stderr.decode(errors="replace")[-300:])
    n = len(p.stdout) // 2
    return struct.unpack(f"<{n}h", p.stdout[: n * 2])


def envelope(x):
    out = []
    for i in range(0, len(x) - HOP + 1, HOP):
        s = 0
        for v in x[i:i + HOP]:
            s += v * v
        out.append(math.log(1e-3 + math.sqrt(s / HOP) / 32768.0))
    return out


def onset(env):
    o = [0.0] + [max(0.0, env[i] - env[i - 1]) for i in range(1, len(env))]
    w = 15  # lokaal gemiddelde over 0,3 s eraf
    pre = [0.0]
    for v in o:
        pre.append(pre[-1] + v)
    res = []
    for i in range(len(o)):
        a, b = max(0, i - w), min(len(o), i + w + 1)
        res.append(max(0.0, o[i] - (pre[b] - pre[a]) / (b - a)))
    return res


def autocorr(o, lag):
    return sum(o[i] * o[i + lag] for i in range(len(o) - lag)) / max(1, len(o) - lag)


def comb(o, period, phase, count):
    s = 0.0
    for k in range(count):
        i = int(round(phase + k * period))
        if i >= len(o):
            break
        s += o[i]
    return s


def track(path):
    full, low = decode(path, False), decode(path, True)
    ef, el = envelope(full), envelope(low)
    n = min(len(ef), len(el))
    of, ol = onset(ef[:n]), onset(el[:n])
    o = [of[i] + 1.5 * ol[i] for i in range(n)]
    duration = len(full) / SR
    if n < 4 * FPS:
        raise SystemExit("muziek te kort voor beat-herkenning")

    # Gladgestreken onset voor het tempo: scherpe drums vallen anders tussen twee meetpunten en "verdwijnen".
    k5 = (1, 2, 3, 2, 1)
    os_ = [sum(k5[j] * o[min(n - 1, max(0, i + j - 2))] for j in range(5)) / 9 for i in range(n)]
    seg = os_[: min(n, 60 * FPS)]  # tempo uit de eerste minuut is genoeg
    lo_L, hi_L = FPS * 60 / 190, FPS * 60 / 76  # zoekbereik 76-190 BPM: daar zit bijna alle popmuziek
    r = [0.0] * (int(4 * hi_L) + 3)
    for L in range(int(lo_L) - 1, len(r)):
        if L < len(seg) - 1:
            r[L] = autocorr(seg, L)

    def ri(x):  # autocorrelatie op een gebroken vertraging (lineair)
        i = int(x)
        if i + 1 >= len(r):
            return 0.0
        return r[i] + (r[i + 1] - r[i]) * (x - i)

    def score(L):  # alle veelvouden van de tel tellen mee: een echte tel herhaalt zich
        bpm = 60 * FPS / L
        w = math.exp(-0.5 * (math.log2(bpm / 120.0) / 1.2) ** 2)
        ms = [m for m in (1, 2, 3, 4) if m * L < len(r) - 2]
        return w * sum(ri(m * L) for m in ms) / len(ms)

    cands = [lo_L + 0.25 * i for i in range(int((hi_L - lo_L) / 0.25) + 1)]
    period = max(cands, key=score)

    # dubbel of half tempo? Kijk of de plekken tussen de tellen bijna even sterk zijn (dan is het tempo dubbel).
    # Hiervoor de volle band zonder kick-weging: een snare telt dan even zwaar als een kick.
    ofs = [sum(k5[j] * of[min(n - 1, max(0, i + j - 2))] for j in range(5)) / 9 for i in range(n)]

    def grid_mean(per, ph, off):
        vals, k = [], 0
        while ph + (k + off) * per < n - 1:
            i = int(round(ph + (k + off) * per))
            vals.append(max(ofs[max(0, i - 1): i + 2]))
            k += 1
        return sum(vals) / max(1, len(vals))
    cnt = int((n - 1) / period)
    ph0 = max((p * 0.5 for p in range(int(period * 2))), key=lambda ph: comb(os_, period, ph, cnt))
    on, mid = grid_mean(period, ph0, 0), grid_mean(period, ph0, 0.5)
    if "--debug" in sys.argv: print(f"periode {period:.2f} on {on:.4f} mid {mid:.4f}", file=sys.stderr)
    if mid >= 0.45 * on and 60 * FPS / (period / 2) <= 190:
        period /= 2
    else:  # te snel gekozen: de oneven tellen zijn veel zwakker dan de even
        even, odd = grid_mean(2 * period, ph0, 0), grid_mean(2 * period, ph0, 0.5)
        if min(even, odd) < 0.3 * max(even, odd) and 60 * FPS / period > 150:
            period *= 2

    # fase: kam over het hele nummer
    count = int((n - 1) / period)
    best = max((p * 0.25 for p in range(int(period * 4))), key=lambda ph: comb(o, period, ph, count))

    # precisie: echte pieken rond de voorspelde tellen, rechte lijn door de pieken (2x, met uitschieters eruit)
    phase = best
    for _ in range(2):
        ks, ts = [], []
        k = 0
        while phase + k * period < n - 3:
            c0 = phase + k * period
            lo, hi = max(1, int(c0 - period * 0.15)), min(n - 2, int(c0 + period * 0.15) + 1)
            if hi > lo:
                i = max(range(lo, hi + 1), key=lambda j: o[j])
                if o[i] > 0:
                    y0, y1, y2 = o[i - 1], o[i], o[i + 1]
                    d = y0 - 2 * y1 + y2
                    ks.append(k)
                    ts.append(i + (0.5 * (y0 - y2) / d if d < 0 else 0.0))
            k += 1
        if len(ks) < 8:
            break
        mk, mt = sum(ks) / len(ks), sum(ts) / len(ts)
        sxx = sum((k - mk) ** 2 for k in ks)
        slope = sum((k - mk) * (t - mt) for k, t in zip(ks, ts)) / sxx
        icpt = mt - slope * mk
        res = [abs(t - (icpt + slope * k)) for k, t in zip(ks, ts)]
        lim = sorted(res)[int(len(res) * 0.8)] + 0.5
        keep = [(k, t) for (k, t), e in zip(zip(ks, ts), res) if e <= lim]
        ks, ts = [k for k, _ in keep], [t for _, t in keep]
        mk, mt = sum(ks) / len(ks), sum(ts) / len(ts)
        sxx = sum((k - mk) ** 2 for k in ks)
        period = sum((k - mk) * (t - mt) for k, t in zip(ks, ts)) / sxx
        phase = mt - period * mk
        while phase - period >= 0:
            phase -= period
        while phase < 0:
            phase += period

    beats = []
    k = 0
    while (phase + k * period) / FPS < duration:
        beats.append(round((phase + k * period) / FPS, 4))
        k += 1

    # downbeat: welke van de vier posities heeft de sterkste lage tonen
    def low_at(t):
        i = int(round(t * FPS))
        return max(ol[max(0, i - 2): i + 3] or [0])
    first = max(range(4), key=lambda q: sum(low_at(beats[k]) for k in range(q, len(beats), 4)))
    downbeats = beats[first::4]

    on = sum(o) / len(o)
    hit = sum(o[int(round(t * FPS))] for t in beats if int(round(t * FPS)) < n) / max(1, len(beats))
    conf = max(0.0, min(1.0, (hit / (on + 1e-9) - 1) / 4))
    return {"bpm": round(60 * FPS / period, 3), "period": round(period / FPS, 5), "beats": beats,
            "downbeats": downbeats, "duration": round(duration, 3), "confidence": round(conf, 3)}


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("gebruik: python beats.py muziekbestand")
    print(json.dumps(track(sys.argv[1])))
