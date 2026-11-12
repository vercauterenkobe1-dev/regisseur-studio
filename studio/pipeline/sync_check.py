"""Ritme- en regie-judge: leest show.json (geschreven door de mod) en controleert of de show op de muziek staat.

Gebruik:  python sync_check.py show.json      (exitcode 0 = alles groen)

Criteria (vastgelegd VOOR het bouwen van de muzieksynchronisatie, niet aanpassen na het zien van resultaten):
  R1 cuts      : elke shotwissel valt binnen 1 frame van een maatstart (alleen met muziek)
  R2 finale    : het slotvuurwerk valt binnen 1 frame van een maatstart (alleen met muziek en bouwwerk)
  (R3 "landingen op de achtste noot" is op 11 nov 2026 vervallen: de gebruiker wil geen stoten meer, maar blok per blok.)
  B1 laag/laag : geen enkel blok landt vóór een blok uit een lagere laag (100%)
  B2 geen stoot: in geen enkel frame landen meer dan 2x het gemiddelde aantal blokken per frame (minimum 2)
  B3 ease      : tempo in de eerste en laatste 15% van de bouw is hoogstens 60% van het tempo in het middelste 30%
  R4 in beeld  : gemeten verschil tussen gepland en echt-in-beeld: mediaan |verschil| <= 2 frames,
                 90% <= 4 frames, en geen enkel blok dat nooit verscheen
  R5 camera    : 0 frames waarin het oog in een blok of in water zat
  R6 shots     : minstens 4 verschillende shots als er een bouwwerk is (behalve in de stijl "één camera")
"""
import json, os, re, subprocess, sys


def near(t, grid, tol):
    return any(abs(t - g) <= tol for g in grid)


def dynamics(s, fps):
    """WP1-regels.
    D1 terraform ademt : per deelfase (sleuf, uithollen, omhoog) bevatten het eerste en laatste bakje van 0,25 s
                         hoogstens 40% van het drukste bakje van die deelfase.
    D2 camera ademt    : in de ring-shot is de variatiecoefficient van de hoeksnelheid >= 0,25.
    D3 puls op de tel  : in de bouw is het tempo in de 100 ms na een tel >= 1,3x het tempo in de 100 ms ervoor."""
    out = []
    tt = s.get("terraTimes")
    if tt:
        worst, msgs = 0.0, []
        for name in ("ring", "hollow", "rise"):
            ts = sorted(tt.get(name, []))
            if len(ts) < 20:
                continue
            t0 = ts[0]
            nb = int((ts[-1] - t0) / 0.25) + 1
            bins = [0] * nb
            for t in ts:
                bins[min(nb - 1, int((t - t0) / 0.25))] += 1
            peak = max(bins)
            edge = max(bins[0], bins[-1]) / peak if nb > 2 else 1.0
            worst = max(worst, edge)
            msgs.append(f"{name} {bins[0] / peak:.0%}/{bins[-1] / peak:.0%}")
        out.append(("D1 terraform ademt", bool(msgs) and worst <= 0.40,
                    "begin/einde t.o.v. piek: " + ", ".join(msgs) + " (max 40%)"))
    else:
        out.append(("D1 terraform ademt", False, "geen terraTimes in show.json"))
    ca, f0 = s.get("camAngle"), s.get("camAngleStartFrame", 0)
    ring = next((x for x in s.get("shots", []) if x["type"] in ("ring", "orbit")), None)
    if ca and ring:
        a0, a1 = round((ring["start"] + 0.5) * fps) - f0, round((ring["end"] - 0.5) * fps) - f0
        seg = ca[max(0, a0): max(0, a1)]
        vel = []
        for a, b in zip(seg, seg[1:]):
            d = (b - a + 180) % 360 - 180
            vel.append(abs(d))
        if len(vel) > 10 and sum(vel) > 0:
            m = sum(vel) / len(vel)
            sd = (sum((v - m) ** 2 for v in vel) / len(vel)) ** 0.5
            cv = sd / m
            out.append(("D2 camera ademt", cv >= 0.25, f"{ring['type']}-shot: variatie hoeksnelheid {cv:.2f} (min 0.25)"))
        else:
            out.append(("D2 camera ademt", False, "te weinig camera-frames"))
    else:
        out.append(("D2 camera ademt", False, "geen camAngle of ring-shot in show.json"))
    land, music, sec = s.get("landings", []), s.get("music"), s.get("sections", {})
    if land and music:
        b0, b1 = min(land), max(land)
        beats = [b for b in music.get("beats", []) if b0 + 0.1 <= b <= b1 - 0.1]
        after = sum(1 for b in beats for t in land if b <= t < b + 0.1)
        before = sum(1 for b in beats for t in land if b - 0.1 <= t < b)
        r = after / max(1, before)
        out.append(("D3 puls op de tel", r >= 1.3, f"tempo na/voor de tel: {r:.2f} over {len(beats)} tellen (min 1.30)"))
    else:
        out.append(("D3 puls op de tel", None, "geen bouw of geen muziek"))
    return out


def glitches(s, fps, take):
    """G1 wereld klaar : 0 frames opgenomen terwijl chunks nog niet opnieuw getekend waren of de server achterliep.
    G2 geen flitsen  : 0 frames die sterk afwijken van zowel het frame ervoor als erna (behalve op cuts)."""
    out = []
    m = s.get("measured") or {}
    if "unsettledFrames" in m:
        u, l = m["unsettledFrames"], m["serverLateFrames"]
        out.append(("G1 wereld klaar", u == 0 and l == 0,
                    f"{u} frames met onafgewerkte chunks, {l} frames met achterlopende server (max {m.get('maxServerLag')} frames)"))
    else:
        out.append(("G1 wereld klaar", False, "geen glitch-metingen in show.json"))
    if not os.path.exists(take):
        out.append(("G2 geen flitsen", None, "take.mp4 niet gevonden"))
        return out
    # verschil tussen opeenvolgende frames (kleine versie, grijs), gemiddeld per frame
    p = subprocess.run(["ffmpeg", "-hide_banner", "-i", take, "-vf",
                        "scale=270:480,format=gray,tblend=all_mode=difference,signalstats,metadata=print:key=lavfi.signalstats.YAVG",
                        "-f", "null", "-"], capture_output=True, text=True, encoding="utf-8", errors="replace")
    d = [float(x) for x in re.findall(r"lavfi\.signalstats\.YAVG=([\d.]+)", p.stderr)]
    if len(d) < 30:
        out.append(("G2 geen flitsen", False, "meting mislukt"))
        return out
    cuts = [round(x["start"] * fps) for x in s.get("shots", [])[1:]]
    events = []
    for i in range(1, len(d) - 1):
        f = i + 1  # d[i] = verschil tussen frame i en i+1
        if any(abs(f - c) <= 2 for c in cuts):
            continue
        w = sorted(d[max(0, i - 15): i - 1] + d[i + 2: i + 17])
        med = w[len(w) // 2] if w else 0
        # een flits: groot verschil heen EN terug, telkens veel groter dan de omgeving
        if d[i] > max(2.0, 4 * med) and d[i + 1] > max(2.0, 4 * med):
            events.append(round(f / fps, 2))
    out.append(("G2 geen flitsen", not events, f"{len(events)} flitsen" + (f" op {events[:8]} s" if events else "")
                + f" (over {len(d)} frames)"))
    return out


def main():
    s = json.load(open(sys.argv[1], encoding="utf-8"))
    fps = s.get("fps", 60)
    tol = 1.0 / fps + 1e-4
    music = s.get("music")
    res = []
    shots = s.get("shots", [])
    cuts = [x["start"] for x in shots[1:]]
    if music:
        down = music["downbeats"]
        bad = [c for c in cuts if not near(c, down, tol)]
        res.append(("R1 cuts", not bad, f"{len(cuts) - len(bad)}/{len(cuts)} op een maatstart" + (f" (mis: {bad})" if bad else "")))
        if s.get("finale", -1) >= 0:
            f = s["finale"]
            res.append(("R2 finale", near(f, down, tol), f"vuurwerk op {f:.3f} s"))
        else:
            res.append(("R2 finale", None, "geen bouwwerk"))
    else:
        res += [("R1 cuts", None, "geen muziek"), ("R2 finale", None, "geen muziek")]
    land, ys = s.get("landings", []), s.get("landingsY")
    if land:
        order = sorted(range(len(land)), key=lambda i: (land[i], -(ys[i] if ys else 0)))  # gelijke tijden: hoogste eerst, dus een lager blok tegelijk telt als fout
        if ys:
            bad = sum(1 for a, b in zip(order, order[1:]) if ys[b] < ys[a])
            res.append(("B1 laag/laag", bad == 0, f"{bad} keer een lager blok na een hoger blok"))
        else:
            res.append(("B1 laag/laag", False, "geen hoogtes in show.json"))
        frames = {}
        for t in land:
            k = round(t * fps)
            frames[k] = frames.get(k, 0) + 1
        span = max(1, round((max(land) - min(land)) * fps) + 1)
        avg = len(land) / span
        peak = max(frames.values())
        lim = max(2, 2 * avg)
        res.append(("B2 geen stoot", peak <= lim, f"piek {peak} blokken in één frame, gemiddeld {avg:.2f}, grens {lim:.1f}"))
        t0, t1 = min(land), max(land)
        L = max(1e-6, t1 - t0)
        def rate(a, b):
            return sum(1 for t in land if t0 + a * L <= t < t0 + b * L) / ((b - a) * L)
        r_in, r_mid, r_out = rate(0, 0.15), rate(0.35, 0.65), rate(0.85, 1.0001)
        ok = r_mid > 0 and r_in <= 0.6 * r_mid and r_out <= 0.6 * r_mid
        res.append(("B3 ease", ok, f"tempo begin {r_in:.0f}/s, midden {r_mid:.0f}/s, einde {r_out:.0f}/s"))
    else:
        res.append(("B1 laag/laag", None, "geen bouwwerk"))
    m = s.get("measured")
    if not m:
        res.append(("R4 in beeld", False, "geen metingen in show.json (opname niet afgerond?)"))
        res.append(("R5 camera", False, "geen metingen"))
    else:
        lag = m.get("landLagFrames", [])
        if lag:
            missing = sum(1 for v in lag if v >= 999)
            a = sorted(abs(v) for v in lag if v < 999)
            med = a[len(a) // 2] if a else 999
            p90 = a[int(len(a) * 0.9)] if a else 999
            mean = sum(v for v in lag if v < 999) / max(1, len(a))
            res.append(("R4 in beeld", missing == 0 and med <= 2 and p90 <= 4,
                        f"mediaan {med} frames, 90% <= {p90} frames, gemiddeld {mean:+.1f}, nooit verschenen {missing}"))
        else:
            res.append(("R4 in beeld", None, "geen bouwwerk"))
        res.append(("R5 camera", m.get("camInBlock", 1) == 0,
                    f"{m.get('camInBlock')} frames in een blok, {m.get('camLifted')} frames opgetild"))
    if len(shots) == 1:
        res.append(("R6 shots", None, "stijl: één camera"))
    elif s.get("landings"):
        kinds = len({x["type"] for x in shots})
        res.append(("R6 shots", kinds >= 4, f"{kinds} soorten shots: " + ", ".join(x["type"] for x in shots)))
    else:
        res.append(("R6 shots", None, "geen bouwwerk"))
    res += dynamics(s, fps)
    res += glitches(s, fps, os.path.join(os.path.dirname(os.path.abspath(sys.argv[1])), "take.mp4"))
    allok = True
    for k, ok, msg in res:
        allok &= ok is not False
        print(f"[{'PASS' if ok else ('SKIP' if ok is None else 'FAIL')}] {k}: {msg}")
    print("RITME:", "ALLES GROEN" if allok else "NIET GESLAAGD")
    sys.exit(0 if allok else 1)


if __name__ == "__main__":
    main()
