"""Judge voor beats.py: synthetische drumtracks met bekend tempo, bekende eerste tel en bekende maatstart.

Criteria (vastgelegd voor het bouwen van beats.py):
  B1 tempo binnen 1% van de echte BPM
  B2 gemiddelde afwijking van de gevonden tellen <= 15 ms
  B3 de eerste tel van de maat (downbeat) is juist
Gebruik:  python test_beats.py   (exitcode 0 = alles groen)
"""
import json, math, os, struct, subprocess, sys, tempfile, wave, random

HERE = os.path.dirname(os.path.abspath(__file__))
SR = 44100


def synth(path, bpm, first, dur, swing=0.0, seed=1):
    """Kick op tel 1 en 3, snare op 2 en 4, hihat op achtsten, akkoordpad eronder. Maat begint op 'first'."""
    rnd = random.Random(seed)
    n = int(SR * dur)
    buf = [0.0] * n
    beat = 60.0 / bpm

    def add(t0, fn, length):
        i0 = int(t0 * SR)
        for i in range(max(0, i0), min(n, i0 + int(length * SR))):
            buf[i] += fn((i - i0) / SR)

    k = 0
    t = first
    while t < dur:
        pos = k % 4
        if pos in (0, 2):  # kick, op tel 1 extra hard
            amp = 1.0 if pos == 0 else 0.75
            add(t, lambda x, a=amp: a * math.sin(2 * math.pi * (55 + 90 * math.exp(-x * 30)) * x) * math.exp(-x * 9), 0.35)
        else:  # snare: ruis
            add(t, lambda x: 0.45 * (rnd.random() * 2 - 1) * math.exp(-x * 18), 0.2)
        for h in (0, 0.5):  # hihat
            add(t + h * beat + (swing * beat if h else 0), lambda x: 0.12 * (rnd.random() * 2 - 1) * math.exp(-x * 60), 0.05)
        k += 1
        t = first + k * beat
    for i in range(n):  # zacht akkoord dat de onsets niet helpt
        x = i / SR
        buf[i] += 0.08 * (math.sin(2 * math.pi * 220 * x) + math.sin(2 * math.pi * 277.2 * x) + math.sin(2 * math.pi * 329.6 * x))
    peak = max(abs(v) for v in buf) or 1
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(v / peak * 30000)) for v in buf))
    return [first + i * beat for i in range(int((dur - first) / beat) + 1)]


CASES = [  # bpm, eerste tel, duur
    (128, 0.37, 24), (95, 0.81, 24), (140, 0.12, 20), (110, 1.30, 24), (174, 0.25, 18),
]


def main():
    ok_all = True
    with tempfile.TemporaryDirectory() as td:
        for bpm, first, dur in CASES:
            wav = os.path.join(td, f"t{bpm}.wav")
            truth = synth(wav, bpm, first, dur)
            out = subprocess.run([sys.executable, os.path.join(HERE, "beats.py"), wav],
                                 capture_output=True, text=True)
            if out.returncode != 0:
                print(f"[FAIL] {bpm} BPM: beats.py faalde: {out.stderr[-300:]}")
                ok_all = False
                continue
            r = json.loads(out.stdout)
            b1 = abs(r["bpm"] - bpm) / bpm <= 0.01
            errs = [min(abs(b - g) for b in r["beats"]) for g in truth if g < dur - 1]
            mean_ms = 1000 * sum(errs) / len(errs)
            b2 = mean_ms <= 15
            down_truth = truth[0]
            down_err = min(abs(d - down_truth - k * 4 * 60 / bpm) for d in r["downbeats"][:3] for k in range(-2, 3))
            b3 = down_err <= 0.03
            ok = b1 and b2 and b3
            ok_all &= ok
            print(f"[{'PASS' if ok else 'FAIL'}] {bpm:>3} BPM (eerste tel {first:.2f}s): gevonden {r['bpm']:.2f} BPM"
                  f" [{'ok' if b1 else 'X'}], tellen gem. {mean_ms:.1f} ms [{'ok' if b2 else 'X'}],"
                  f" downbeat {1000 * down_err:.0f} ms [{'ok' if b3 else 'X'}]")
    print("RESULTAAT:", "ALLES GROEN" if ok_all else "NIET GESLAAGD")
    sys.exit(0 if ok_all else 1)


if __name__ == "__main__":
    main()
