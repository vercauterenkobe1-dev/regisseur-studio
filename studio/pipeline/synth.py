"""Regisseur Beat (WP4): een volle track (stijl "phonk" standaard, of "edm"), per video opnieuw gemaakt op het ritme en de secties van de show.

Waarom zelf maken: zo valt de drop exact op de start van de bouw, de klap exact op de finale, en eindigt het
nummer naadloos in de teaser (loop). Geen auteursrecht. Alles in numpy, 48 kHz stereo.

Boog (in video-tijd):
  teaser          : drop (volle energie, zodat de hook meteen knalt)
  terraform       : opbouw: kick + hats, filter gaat open, bas erbij, snare-roll + riser in de laatste 2 maten
  laatste tel     : stilte (alleen de riser-staart), dan
  bouw            : DROP: four-on-the-floor, clap, open hats, bas, pompende akkoorden
  finale          : impact + crash, daarna outro (akkoorden en galm, kick half) tot het einde -> loopt in de teaser

Gebruik:  python synth.py --plain out.wav [--bpm 128 --seconds 90]   (vaste loop voor de mod om op te timen)
"""
import argparse, json, math, subprocess
import numpy as np

SR = 48000
rng = np.random.default_rng(3)

# A-mineur: Am - F - C - G (i - VI - III - VII), klassiek voor EDM
PROG = [(57, [57, 60, 64]), (53, [53, 57, 60]), (48, [52, 55, 60]), (55, [55, 59, 62])]


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def env_exp(n, tau):
    return np.exp(-np.arange(n) / (tau * SR)).astype(np.float32)


def kick():
    n = int(0.42 * SR)
    t = np.arange(n) / SR
    f = 45 + 95 * np.exp(-t * 28)                      # toon zakt snel van 140 naar 45 Hz
    ph = 2 * np.pi * np.cumsum(f) / SR
    body = np.sin(ph) * np.exp(-t * 7.5)
    click = rng.standard_normal(n) * np.exp(-t * 400) * 0.25
    return np.tanh(1.6 * (body + click)).astype(np.float32) * 0.9


def noise_hit(dur, tau, hp_coef):
    n = int(dur * SR)
    x = rng.standard_normal(n).astype(np.float32)
    y = np.empty_like(x); prev = 0.0; px = 0.0
    for i in range(n):  # eenvoudige hoogdoorlaat
        prev = hp_coef * (prev + x[i] - px); px = x[i]; y[i] = prev
    return y * env_exp(n, tau)


def hat_closed():
    return noise_hit(0.06, 0.012, 0.95) * 0.32


def hat_open():
    return noise_hit(0.28, 0.07, 0.95) * 0.22


def clap():
    n = int(0.3 * SR)
    x = np.zeros(n, np.float32)
    for k, d in enumerate([0, 0.011, 0.022]):  # drie korte bursts = handklap
        b = noise_hit(0.25, 0.012 if k < 2 else 0.09, 0.85)
        i = int(d * SR)
        x[i:i + len(b)] += b[: n - i]
    return x * 0.55


def saw(freq, n, detune_cents=(0,)):
    t = np.arange(n) / SR
    out = np.zeros(n, np.float32)
    for c in detune_cents:
        f = freq * 2 ** (c / 1200)
        ph = (t * f + rng.random()) % 1.0
        out += (2 * ph - 1).astype(np.float32)
    return out / len(detune_cents)


def lowpass(x, cutoff):
    """Eén-pool laagdoorlaat; cutoff mag een array zijn (filter-sweep)."""
    c = np.broadcast_to(np.asarray(cutoff, dtype=np.float32), x.shape)
    a = 1 - np.exp(-2 * np.pi * c / SR)
    y = np.empty_like(x); s = 0.0
    for i in range(len(x)):
        s += a[i] * (x[i] - s); y[i] = s
    return y


def lowpass_fast(x, cutoff):
    """Vaste cutoff, twee keer toegepast (12 dB/oct), via scipy-loze recursie in blokken."""
    a = 1 - math.exp(-2 * math.pi * cutoff / SR)
    y = x.astype(np.float32)
    for _ in range(2):
        out = np.empty_like(y); s = 0.0
        for i in range(len(y)):
            s += a * (y[i] - s); out[i] = s
        y = out
    return y


def add(buf, snd, t, gain=1.0):
    i = int(round(t * SR))
    if i >= len(buf) or i + len(snd) <= 0:
        return
    s0 = max(0, -i); i = max(0, i)
    j = min(len(buf), i + len(snd) - s0)
    buf[i:j] += snd[s0:s0 + (j - i)] * gain


def pump_env(n_total, beat_times, depth=0.7):
    """Sidechain-'pomp': na elke kick zakt het volume en komt het terug (klassiek EDM-gevoel)."""
    e = np.ones(n_total, np.float32)
    rel = int(0.22 * SR)
    curve = 1 - depth * (1 - np.linspace(0, 1, rel) ** 0.6)
    for b in beat_times:
        i = int(b * SR)
        if 0 <= i < n_total:
            j = min(n_total, i + rel)
            e[i:j] = np.minimum(e[i:j], curve[: j - i])
    return e


class Arr:
    """Secties in video-tijd. drop_spans: lijst (start, eind) met volle energie."""
    def __init__(self, total, beat, downbeats, build=None, drop_spans=(), finale=None, gap_before=None):
        self.total, self.beat, self.downbeats = total, beat, downbeats
        self.build, self.drop_spans, self.finale, self.gap = build, list(drop_spans), finale, gap_before

    def in_drop(self, t):
        return any(a <= t < b for a, b in self.drop_spans)


def render(arr):
    n = int((arr.total + 1) * SR)
    drums = np.zeros(n, np.float32)
    bass = np.zeros(n, np.float32)
    chords = np.zeros(n, np.float32)
    fx = np.zeros(n, np.float32)
    beat = arr.beat
    K, HC, HO, CL = kick(), hat_closed(), hat_open(), clap()
    # tel-raster over de hele video (ook voor de eerste downbeat terug)
    d0 = arr.downbeats[0] if arr.downbeats else 0.0
    first = d0 - math.ceil(d0 / beat) * beat
    beats = [first + k * beat for k in range(int((arr.total - first) / beat) + 2)]
    kick_times = []
    build_s = arr.build if arr.build is not None else arr.total + 1
    fin = arr.finale if arr.finale is not None else arr.total + 1
    for k, b in enumerate(beats):
        if b < -0.01 or b >= arr.total:
            continue
        bar_pos = int(round((b - d0) / beat)) % 4
        drop = arr.in_drop(b)
        silent = arr.gap is not None and arr.gap - 0.01 <= b < build_s - 0.01
        if silent:
            continue
        outro = b >= fin and not drop
        buildup = not drop and not outro
        # vooruitgang van de opbouw 0..1 (voor filter en dichtheid)
        prog = 0.0
        if buildup and build_s < arr.total + 1:
            prog = max(0.0, min(1.0, b / max(0.1, build_s)))
        # kick: drop + opbouw vol; outro alleen op tel 1 en 3
        if drop or buildup or (outro and bar_pos in (0, 2)):
            add(drums, K, b, 1.0 if drop else (0.25 + 0.25 * prog) if buildup else 0.6)
            kick_times.append(b)
        # hats: achtsten; open hat op de off-beat in de drop
        add(drums, HC, b + beat / 2, 0.8 if drop else 0.5 + 0.3 * prog)
        if drop:
            add(drums, HC, b, 0.45)
            add(drums, HO, b + beat / 2, 0.7)
        if drop and bar_pos in (1, 3):
            add(drums, CL, b, 1.0)
        # snare-roll in de laatste 2 maten voor de bouw: 8sten, dan 16den
        if buildup and build_s - 2 * 4 * beat <= b < build_s - beat - 0.01:
            sub = 4 if b >= build_s - 4 * beat else 2
            for s in range(sub):
                tt = b + s * beat / sub
                add(drums, CL, tt, 0.25 + 0.6 * (tt - (build_s - 8 * beat)) / (8 * beat))
    # bas en akkoorden per maat
    bar = 4 * beat
    nbar0 = math.floor((0 - d0) / bar)
    for bi in range(nbar0, int((arr.total - d0) / bar) + 2):
        t0 = d0 + bi * bar
        if t0 >= arr.total or t0 + bar <= 0:
            continue
        root, chord = PROG[bi % 4]
        drop = arr.in_drop(t0 + 0.01)
        outro = t0 >= fin - 0.01 and not drop
        buildup = not drop and not outro
        nb = int(bar * SR)
        # akkoorden: supersaw, gefilterd; in de opbouw gaat het filter open
        st = sum(saw(hz(m), nb, (-14, -5, 5, 14)) for m in chord) / len(chord)
        st += saw(hz(chord[0] + 12), nb, (-8, 8)) * 0.35
        if drop:
            cut = 5200
        elif outro:
            cut = 2600
        else:
            p = max(0.0, min(1.0, (t0 + bar) / max(0.1, build_s)))
            cut = 350 + 2600 * p ** 2
        st = lowpass_fast(st, cut)
        a = np.minimum(1, np.arange(nb) / (0.01 * SR))
        rel = np.minimum(1, (nb - np.arange(nb)) / (0.03 * SR))
        add(chords, st * a * rel, t0, 0.55 if drop else 0.40 if outro else 0.30)
        # bas: off-beat pulsen (achtsten tussen de kicks) in drop, hele noten in de opbouw
        if drop or (buildup and t0 >= build_s - 2 * bar):
            for q in range(4):
                tt = t0 + q * beat + beat / 2
                nn = int(beat / 2 * SR)
                b_ = saw(hz(root - 24), nn, (0, 7)) + 0.6 * np.sin(2 * np.pi * hz(root - 24) * np.arange(nn) / SR)
                b_ = lowpass_fast(b_.astype(np.float32), 420) * np.minimum(1, (nn - np.arange(nn)) / (0.02 * SR))
                add(bass, b_, tt, 0.85 if drop else 0.25)
        elif outro:
            nn = nb
            b_ = np.sin(2 * np.pi * hz(root - 24) * np.arange(nn) / SR).astype(np.float32) * env_exp(nn, bar / 2)
            add(bass, b_, t0, 0.6)
    # riser: ruis met stijgende kleur en volume, de laatste 2 maten voor de bouw, stopt 1 tel ervoor
    if arr.build is not None and arr.build < arr.total:
        rs, re_ = max(0.0, arr.build - 2 * bar), arr.build - beat
        nr = int((re_ - rs) * SR)
        if nr > 0:
            x = rng.standard_normal(nr).astype(np.float32)
            ramp = np.linspace(0, 1, nr, dtype=np.float32)
            x = lowpass(x, 300 + 9000 * ramp ** 2) * (ramp ** 1.5) * 0.5
            nf = min(nr, int(0.015 * SR))  # zachte stop: geen tik als de riser ophoudt
            x[-nf:] *= np.linspace(1, 0, nf, dtype=np.float32)
            add(fx, x, rs)
        # 'downlifter' + impact op de drop
        add(fx, impact(), arr.build, 0.9)
    if arr.finale is not None and arr.finale < arr.total:
        add(fx, impact(), arr.finale, 1.0)
        crash = noise_hit(2.2, 0.6, 0.97) * 0.35
        add(fx, crash, arr.finale)
    pump = pump_env(n, kick_times)
    if arr.gap is not None and arr.build is not None:  # de stille tel voor de drop: alles weg behalve de riser-staart
        g0, g1 = int(arr.gap * SR), int(arr.build * SR)
        fade = int(0.006 * SR)
        m = np.ones(n, np.float32)
        m[g0:g1] = 0.0
        m[max(0, g0 - fade):g0] = np.linspace(1, 0, g0 - max(0, g0 - fade))
        for s in (drums, bass, chords):
            s *= m
    mix_l = drums + bass * pump + chords * pump * 0.98 + fx
    mix_r = drums + bass * pump + np.roll(chords, int(0.012 * SR)) * pump + fx  # Haas-breedte op de akkoorden
    st = np.stack([mix_l, mix_r], axis=1)[: int(arr.total * SR)]
    st = np.tanh(st * 0.9) * 0.9  # zachte saturatie: 'lijm' en voller
    return st.astype(np.float32)


# ================================================================ stijl "phonk" (standaard sinds 0.11)
# Waarom: de EDM-stijl had zijn bas vooral onder 60 Hz, en dat speelt een gsm-luidspreker niet af (gemeten: gsm-bas
# 100-400 Hz op -9,7 dB in de drop). Virale edits gebruiken een VERVORMDE 808: diep (sub) en met boventonen die je
# ook op een gsm hoort. Daarbij de typische phonk-cowbell als melodie, harde kick, snare op 2 en 4, hats met rollen.

def fft_filter(x, lo=None, hi=None):
    """Hoog-/laagdoorlaat zonder lussen (via FFT), 24 dB/oct. Voor korte buffers (een noot, een maat)."""
    X = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR) + 1e-6
    m = np.ones_like(f)
    if hi:
        m /= 1 + (f / hi) ** 4
    if lo:
        m /= 1 + (lo / f) ** 4
    return np.fft.irfft(X * m, len(x)).astype(np.float32)


def kick_hard():
    n = int(0.32 * SR)
    t = np.arange(n) / SR
    f = 52 + 210 * np.exp(-t * 38)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 17)  # kort: de 808 vult de diepe bas
    click = rng.standard_normal(n) * np.exp(-t * 600) * 0.4
    return (np.tanh(2.2 * (body + click)) * 0.85).astype(np.float32)


def bass808(f0, dur, f_from=None, glide=0.07):
    """808: sinus met korte 'punch' in toonhoogte, optioneel glijden vanaf de vorige noot, dan vervormd
    (oneven + even boventonen: 2f, 3f, 4f vallen in 100-400 Hz en zijn dus hoorbaar op een gsm)."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    f = f0 * 2 ** (np.exp(-t * 45) * 7 / 12)  # punch: start 7 halve tonen hoger, valt in ~30 ms
    if f_from:
        g = np.clip(t / glide, 0, 1)
        f = f * (f_from / f0) ** (1 - (g * g * (3 - 2 * g)))
    ph = 2 * np.pi * np.cumsum(f) / SR
    # sub + een laag een octaaf hoger (rond 110 Hz): die tweede laag is wat je op een gsm als 'bas' hoort
    s = np.sin(ph) + 0.85 * np.sin(2 * ph)
    drive = 4.5
    y = np.tanh(drive * (s + 0.22)) - np.tanh(drive * 0.22)  # asymmetrisch: ook even boventonen
    env = np.exp(-t / 0.85) * np.minimum(1, t / 0.003) * np.minimum(1, (dur - t) / 0.012)
    return fft_filter((y * env).astype(np.float32), hi=2400) * 0.75


def cowbell(midi, dur=0.26):
    """Phonk-cowbell: twee 'vierkante' tonen (verhouding 1,48) met alleen de eerste boventonen, kort uitsterven."""
    n = int(dur * SR)
    t = np.arange(n) / SR
    out = np.zeros(n, np.float32)
    for ratio, g in ((1.0, 1.0), (1.48, 0.8)):
        fr = hz(midi) * ratio
        for h, gh in ((1, 1.0), (3, 0.33), (5, 0.2)):
            out += (g * gh * np.sin(2 * np.pi * fr * h * t)).astype(np.float32)
    env = np.exp(-t / 0.075) * 0.75 + np.exp(-t / 0.3) * 0.25
    env *= np.minimum(1, t / 0.0015) * np.minimum(1, (dur - t) / 0.01)
    return fft_filter((out * env).astype(np.float32), lo=350, hi=7000) * 0.32


def snare():
    n = int(0.28 * SR)
    t = np.arange(n) / SR
    tone = np.sin(2 * np.pi * 190 * t) * np.exp(-t * 28)
    nz = fft_filter(rng.standard_normal(n).astype(np.float32), lo=1200, hi=11000) * np.exp(-t * 16)
    return (np.tanh(1.5 * (0.6 * tone + 0.9 * nz)) * 0.6).astype(np.float32)


# 808-grondtonen per maat (A-mineur: A1, F1, C2, G1) en het ritme binnen een maat: (tel, halve tonen, lengte, glijden)
ROOTS = [33, 29, 36, 31]
BASS_PAT = [(0.0, 0, 1.4, False), (1.5, 0, 0.5, False), (2.0, 0, 0.9, False), (3.0, 12, 0.5, True), (3.5, 7, 0.5, True)]
# cowbell-melodie: per maat 8 achtsten (None = rust), past bij de akkoorden Am - F - C - G
BELL = [[81, None, 81, 84, None, 79, None, 76],
        [77, None, 77, 81, None, 76, None, 74],
        [76, None, 76, 79, None, 72, None, 74],
        [79, None, 79, 83, None, 74, None, 71]]


def render_phonk(arr):
    n = int((arr.total + 1) * SR)
    drums, bass, bells, fx = (np.zeros(n, np.float32) for _ in range(4))
    beat, bar = arr.beat, 4 * arr.beat
    K, SN, CL, HC = kick_hard(), snare(), clap(), hat_closed()
    d0 = arr.downbeats[0] if arr.downbeats else 0.0
    build_s = arr.build if arr.build is not None else arr.total + 1
    fin = arr.finale if arr.finale is not None else arr.total + 1
    in_gap = lambda t: arr.gap is not None and arr.gap - 0.01 <= t < build_s - 0.01
    kick_times = []
    for bi in range(math.floor((0 - d0) / bar), int((arr.total - d0) / bar) + 2):
        t0 = d0 + bi * bar
        if t0 >= arr.total or t0 + bar <= 0:
            continue
        k4 = bi % 4
        for q in range(4):                                   # ---- drums per tel
            b = t0 + q * beat
            if b < -0.01 or b >= arr.total or in_gap(b):
                continue
            drop = arr.in_drop(b + 0.001)
            outro = b >= fin - 0.01 and not drop
            buildup = not drop and not outro
            prog = max(0.0, min(1.0, b / max(0.1, build_s))) if buildup else 0.0
            if drop or buildup or (outro and q in (0, 2)):
                add(drums, K, b, 1.0 if drop else 0.7 if outro else 0.3 + 0.3 * prog)
                kick_times.append(b)
            if drop and q in (1, 3):
                add(drums, SN, b, 1.0)
                add(drums, CL, b, 0.5)
            if drop:                                          # 16den, accent op de off-beat; rol op de laatste tel
                roll = q == 3 and bi % 2 == 1
                steps = 6 if roll else 4
                for s_ in range(steps):
                    add(drums, HC, b + s_ * beat / steps, (0.55 if s_ % 2 else 0.8) * (0.8 if roll else 1))
            else:
                add(drums, HC, b + beat / 2, 0.45 + 0.35 * prog)
            if buildup and build_s - 2 * bar <= b < build_s - beat - 0.01:  # snare-roll naar de drop
                sub = 4 if b >= build_s - bar else 2
                for s_ in range(sub):
                    tt = b + s_ * beat / sub
                    add(drums, SN, tt, 0.2 + 0.7 * (tt - (build_s - 2 * bar)) / (2 * bar))
        drop = arr.in_drop(t0 + 0.01)
        outro = t0 >= fin - 0.01 and not drop
        root = ROOTS[k4]
        if drop:                                              # ---- 808
            prev = None
            for (q, semi, ln, gl) in BASS_PAT:
                f = hz(root + semi)
                add(bass, bass808(f, ln * beat, prev if gl else None), t0 + q * beat)
                prev = f
        elif outro:
            add(bass, bass808(hz(root), min(bar, 2.0)), t0, 0.8)
        # ---- cowbell-melodie: drop vol, opbouw gefilterd (gaat open), outro zacht; niet in de stille tel
        if not in_gap(t0):
            mel = np.zeros(int(bar * SR) + int(0.3 * SR), np.float32)
            for e, m in enumerate(BELL[k4]):
                if m is not None:
                    add(mel, cowbell(m), e * beat / 2, 1.0 if e % 2 == 0 else 0.8)
            if drop:
                g = 1.0
            elif outro:
                g, mel = 0.6, fft_filter(mel, hi=3500)
            else:
                p = max(0.0, min(1.0, (t0 + bar) / max(0.1, build_s)))
                g, mel = 0.5 + 0.4 * p, fft_filter(mel, hi=600 + 5000 * p ** 2)
            add(bells, mel, t0, g)
    # riser + impacts zoals in de EDM-stijl
    if arr.build is not None and arr.build < arr.total:
        rs, re_ = max(0.0, arr.build - 2 * bar), arr.build - beat
        nr = int((re_ - rs) * SR)
        if nr > 0:
            x = rng.standard_normal(nr).astype(np.float32)
            ramp = np.linspace(0, 1, nr, dtype=np.float32)
            x = lowpass(x, 300 + 9000 * ramp ** 2) * (ramp ** 1.5) * 0.45
            nf = min(nr, int(0.015 * SR))
            x[-nf:] *= np.linspace(1, 0, nf, dtype=np.float32)
            add(fx, x, rs)
        add(fx, impact(), arr.build, 0.9)
    if arr.finale is not None and arr.finale < arr.total:
        add(fx, impact(), arr.finale, 1.0)
        add(fx, noise_hit(2.2, 0.6, 0.97) * 0.3, arr.finale)
    duck = pump_env(n, kick_times, depth=0.55)          # 808 en bel buigen kort weg onder de kick
    if arr.gap is not None and arr.build is not None:   # stille tel voor de drop
        g0, g1 = int(arr.gap * SR), int(arr.build * SR)
        fade = int(0.006 * SR)
        m = np.ones(n, np.float32)
        m[g0:g1] = 0.0
        m[max(0, g0 - fade):g0] = np.linspace(1, 0, g0 - max(0, g0 - fade))
        for s_ in (drums, bass, bells):
            s_ *= m
    mono = drums + bass * duck * 1.4
    left = mono + bells * duck + fx
    right = mono + np.roll(bells, int(0.009 * SR)) * duck + fx   # bel iets breder
    st = np.stack([left, right], axis=1)[: int(arr.total * SR)]
    st = np.tanh(st * 0.85) * 0.92
    return st.astype(np.float32)


def impact():
    n = int(1.4 * SR)
    t = np.arange(n) / SR
    f = 38 + 70 * np.exp(-t * 6)
    boom = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 3.0)
    return (boom * 0.9).astype(np.float32)


def write(st, path):
    p = subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", "2",
                        "-i", "-", "-c:a", "pcm_s24le", path], input=st.tobytes())
    if p.returncode != 0:
        raise RuntimeError("synth: wav schrijven mislukt")


def for_video(show, clips, total, out, teaser=0.0, td=0.0, style="phonk"):
    """Maak de track voor deze video. show = show.json (opname-tijd); clips zoals in render.py.
    style: "phonk" (standaard: vervormde 808, cowbell) of "edm" (de oude Regisseur Beat)."""
    from sfx import clip_map, to_video
    cmap = clip_map(clips, td)
    music = show.get("music") or {}
    beat = float(music.get("period") or 60 / 128)
    sec = show["sections"]
    main = cmap[-1]  # de hoofdclip (na de teaser)

    def v(t):  # opname-tijd -> video-tijd in de hoofdclip
        return main[2] + t - main[0]
    downs = [v(d) for d in music.get("downbeats", [])] or [v(0.0)]
    build = v(sec["terraEnd"]) if show.get("finale", -1) > 0 else None
    fin = v(show["finale"]) if show.get("finale", -1) > 0 else None
    spans = []
    if teaser > 0:
        spans.append((0.0, teaser))
    if build is not None:
        spans.append((build, fin if fin else total))
    arr = Arr(total, beat, downs, build=build, drop_spans=spans, finale=fin,
              gap_before=(build - beat) if build is not None else None)
    write(render_phonk(arr) if style == "phonk" else render(arr), out)
    return {"bpm": 60 / beat, "build": build, "finale": fin}


def plain(out, bpm=128.0, seconds=90.0):
    """Vaste loop (drop-energie, 128 BPM, downbeat op 0) waarop de mod de show kan timen."""
    beat = 60 / bpm
    arr = Arr(seconds, beat, [0.0], build=None, drop_spans=[(0, seconds)], finale=None)
    write(render(arr), out)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--plain")
    ap.add_argument("--bpm", type=float, default=128)
    ap.add_argument("--seconds", type=float, default=90)
    a = ap.parse_args()
    if a.plain:
        plain(a.plain, a.bpm, a.seconds)
        print("klaar:", a.plain)
