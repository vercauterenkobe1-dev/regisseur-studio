"""Judge: meet een Shorts-export tegen vaste criteria. Geen meningen, alleen ffmpeg/ffprobe-metingen.

Gebruik:  python judge.py final.mp4 [--master master.mkv] [--captions captions.ass]
Exitcode 0 = alles groen.

Criteria (vastgelegd VOOR het bouwen van de pipeline, niet aanpassen na het zien van resultaten):
  C1 formaat   : 1080x1920, 60 fps CFR, yuv420p, BT.709-tags, mp4 met faststart (moov voor mdat)
  C2 kwaliteit : VMAF t.o.v. lossless master: gemiddelde >= 95 en 1%-low >= 90
  C3 audio     : AAC 48 kHz stereo, integrated -14 +/-1 LUFS, true peak <= -1.0 dBTP
  C4 captions  : alle caption-pixels binnen safe zone x[90,990] y[260,1460]
  C5 vloeiend  : < 1% (bijna-)dubbele frames
  C6 afspeelbaar: H.264 (avc1) High/Main, level <= 5.2, AAC-LC  (toegevoegd 11 nov 2026: HEVC speelde niet af
                 in Windows Mediaspeler bij de gebruiker)
"""
import argparse, json, os, re, subprocess, sys, tempfile

SAFE = dict(x1=90, y1=260, x2=990, y2=1460)


def run(args, cwd=None):
    p = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=cwd)
    return p.returncode, p.stdout, p.stderr


def probe(path):
    _, out, _ = run(["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", path])
    return json.loads(out)


def c1_format(path, info):
    v = next((s for s in info["streams"] if s["codec_type"] == "video"), None)
    if not v:
        return False, "geen videostream"
    probs = []
    if (v["width"], v["height"]) != (1080, 1920):
        probs.append(f"resolutie {v['width']}x{v['height']}")
    num, den = map(int, v["avg_frame_rate"].split("/"))
    rnum, rden = map(int, v["r_frame_rate"].split("/"))
    fps = num / den if den else 0
    if abs(fps - 60) > 0.01 or abs(rnum / rden - 60) > 0.01:
        probs.append(f"fps {fps:.3f} (r {rnum}/{rden})")
    if v.get("pix_fmt") != "yuv420p":
        probs.append(f"pix_fmt {v.get('pix_fmt')}")
    for k in ("color_primaries", "color_transfer", "color_space"):
        if v.get(k) != "bt709":
            probs.append(f"{k}={v.get(k)}")
    # echte CFR: elke pts-stap moet identiek zijn
    _, out, _ = run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts",
                     "-of", "csv=p=0", path])
    pts = sorted(int(x) for x in out.split() if x.strip().lstrip("-").isdigit())
    steps = {b - a for a, b in zip(pts, pts[1:])}
    if len(pts) < 2 or len(steps) != 1:
        probs.append(f"geen CFR: {len(steps)} verschillende pts-stappen")
    # faststart: top-level atomen echt parsen
    order = []
    with open(path, "rb") as f:
        size_total = os.path.getsize(path)
        pos = 0
        while pos + 8 <= size_total:
            f.seek(pos)
            hdr = f.read(16)
            size, typ = int.from_bytes(hdr[:4], "big"), hdr[4:8].decode("latin-1")
            if size == 1:
                size = int.from_bytes(hdr[8:16], "big")
            elif size == 0:
                size = size_total - pos
            if size < 8:
                break
            order.append(typ)
            pos += size
    if "moov" not in order or "mdat" not in order or order.index("moov") > order.index("mdat"):
        probs.append(f"geen faststart (atomen: {order})")
    return not probs, "ok" if not probs else "; ".join(probs)


def count_frames(path):
    _, out, _ = run(["ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
                     "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", path])
    try:
        return int(out.strip())
    except ValueError:
        return -1


def c2_vmaf(path, master):
    if not master:
        return None, "overgeslagen (geen --master)"
    nd, nm = count_frames(path), count_frames(master)
    if nd != nm or nd <= 0:
        return False, f"aantal frames verschilt: final {nd} vs master {nm} (gedropt/ingekort)"
    with tempfile.TemporaryDirectory() as td:
        log = "vmaf.json"
        # frame-index-uitlijning: mkv (1 ms tijdbasis) en mp4 ronden 60 fps-tijdstempels anders af
        fc = ("[0:v]settb=AVTB,setpts=N/60/TB,format=yuv420p[d];[1:v]settb=AVTB,setpts=N/60/TB,format=yuv420p[r];"
              f"[d][r]libvmaf=log_fmt=json:log_path={log}:n_threads={os.cpu_count()}")
        code, _, err = run(["ffmpeg", "-hide_banner", "-i", os.path.abspath(path), "-i", os.path.abspath(master),
                            "-lavfi", fc, "-f", "null", "-"], cwd=td)
        if code != 0:
            return False, "vmaf mislukt: " + err[-300:]
        with open(os.path.join(td, log), encoding="utf-8") as f:
            data = json.load(f)
    scores = sorted(fr["metrics"]["vmaf"] for fr in data["frames"])
    mean = sum(scores) / len(scores)
    low1 = sum(scores[: max(1, len(scores) // 100)]) / max(1, len(scores) // 100)
    ok = mean >= 95 and low1 >= 90
    return ok, f"VMAF gem {mean:.2f}, 1%-low {low1:.2f} ({len(scores)} frames)"


def c3_audio(path, info):
    a = next((s for s in info["streams"] if s["codec_type"] == "audio"), None)
    if not a:
        return False, "geen audiostream"
    probs = []
    if a["codec_name"] != "aac":
        probs.append(f"codec {a['codec_name']}")
    if int(a["sample_rate"]) != 48000:
        probs.append(f"samplerate {a['sample_rate']}")
    if a.get("channels") != 2:
        probs.append(f"kanalen {a.get('channels')}")
    _, _, err = run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-map", "0:a",
                     "-af", "ebur128=peak=true", "-f", "null", "-"])
    summary = err[err.rfind("Summary:"):]
    i = re.search(r"I:\s+(-?[\d.]+) LUFS", summary)
    tp = re.search(r"True peak:\s+Peak:\s+(-?[\d.]+|-inf) dBFS", summary)
    lufs = float(i.group(1)) if i else None
    peak = float(tp.group(1)) if tp and tp.group(1) != "-inf" else None
    if lufs is None or abs(lufs + 14) > 1:
        probs.append(f"loudness {lufs} LUFS")
    if peak is None or peak > -1.0:
        probs.append(f"true peak {peak} dBTP")
    return not probs, f"{lufs} LUFS, TP {peak} dBTP" + ("" if not probs else " | " + "; ".join(probs))


def c4_captions(ass, duration):
    if not ass:
        return None, "overgeslagen (geen --captions)"
    d, name = os.path.split(os.path.abspath(ass))
    # Captions op middengrijs renderen en het verschil met leeg grijs meten:
    # zo tellen witte tekst, zwarte outline en schaduw allemaal mee. 60 fps = elk frame.
    bg = f"color=0x808080:s=1080x1920:r=60:d={duration:.3f}"
    fc = f"[0:v]split[a][b];[a]ass={name}[t];[t][b]blend=all_mode=difference,format=gray,bbox=min_val=8"
    _, _, err = run(["ffmpeg", "-hide_banner", "-f", "lavfi", "-i", bg,
                     "-filter_complex", fc, "-f", "null", "-"], cwd=d)
    boxes = re.findall(r"x1:(\d+) x2:(\d+) y1:(\d+) y2:(\d+)", err)
    if not boxes:
        return False, "geen caption-pixels gevonden"
    x1 = min(int(b[0]) for b in boxes); x2 = max(int(b[1]) for b in boxes)
    y1 = min(int(b[2]) for b in boxes); y2 = max(int(b[3]) for b in boxes)
    ok = x1 >= SAFE["x1"] and x2 <= SAFE["x2"] and y1 >= SAFE["y1"] and y2 <= SAFE["y2"]
    return ok, f"caption-bbox x[{x1},{x2}] y[{y1},{y2}] in {len(boxes)} frames (safe x[90,990] y[260,1460])"


COUNTER_ZONE = {"x1": 90, "x2": 916, "y1": 190, "y2": 560}


def u1_counter_zone(ass, panel, duration):
    """Teller (getallen + iconen) binnen de zone onder de bovenknoppen en links van de knoppenkolom."""
    d, name = os.path.split(os.path.abspath(ass))
    bg = f"color=0x808080:s=1080x1920:r=60:d={duration:.3f}"
    fc = f"[0:v]split[a][b];[a]ass={name}[t];[t][b]blend=all_mode=difference,format=gray,bbox=min_val=8"
    _, _, err = run(["ffmpeg", "-hide_banner", "-f", "lavfi", "-i", bg, "-filter_complex", fc, "-f", "null", "-"], cwd=d)
    boxes = [tuple(int(v) for v in b) for b in re.findall(r"x1:(\d+) x2:(\d+) y1:(\d+) y2:(\d+)", err)]
    if not boxes:
        return False, "geen teller-pixels gevonden"
    x1 = min(b[0] for b in boxes); x2 = max(b[1] for b in boxes)
    y1 = min(b[2] for b in boxes); y2 = max(b[3] for b in boxes)
    if panel:  # iconen: dekkende pixels (alpha >= 200; het donkere verloop (max 76) en de zachte schaduw tellen niet)
        _, _, e2 = run(["ffmpeg", "-hide_banner", "-i", panel, "-vf", "alphaextract,format=gray,bbox=min_val=200",
                        "-f", "null", "-"])
        pb = re.findall(r"x1:(\d+) x2:(\d+) y1:(\d+) y2:(\d+)", e2)
        if pb:
            p = [int(v) for v in pb[-1]]
            x1, x2, y1, y2 = min(x1, p[0]), max(x2, p[1]), min(y1, p[2]), max(y2, p[3])
    z = COUNTER_ZONE
    ok = x1 >= z["x1"] and x2 <= z["x2"] and y1 >= z["y1"] and y2 <= z["y2"]
    return ok, f"teller-bbox x[{x1},{x2}] y[{y1},{y2}] (zone x[{z['x1']},{z['x2']}] y[{z['y1']},{z['y2']}])"


def u2_one_size(ass):
    with open(ass, encoding="utf-8-sig") as f:
        txt = f.read()
    sizes = set(re.findall(r"\\fs(\d+)", txt))
    style = re.search(r"Style: Cnt,[^,]+,(\d+)", txt)
    if style:
        sizes.add(style.group(1))
    return len(sizes) == 1, f"lettergroottes: {sorted(sizes)}"


def m2_loudness(path):
    _, _, err = run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-map", "0:a",
                     "-af", "ebur128=peak=true", "-f", "null", "-"])
    summary = err[err.rfind("Summary:"):]
    i = re.search(r"I:\s+(-?[\d.]+) LUFS", summary)
    tp = re.search(r"True peak:\s+Peak:\s+(-?[\d.]+|-inf) dBFS", summary)
    if not i or not tp or tp.group(1) == "-inf":
        return False, "meting mislukt"
    lufs, peak = float(i.group(1)), float(tp.group(1))
    return abs(lufs + 14) <= 0.5 and peak <= -1.0, f"{lufs} LUFS (doel -14 +-0.5), TP {peak} dBTP (max -1.0)"


def c6_playable(path, info):
    """Speelt af in Windows Mediaspeler zonder extensies: H.264 (avc1) High/Main, level <= 5.2, AAC-LC."""
    v = next((s for s in info["streams"] if s["codec_type"] == "video"), {})
    a = next((s for s in info["streams"] if s["codec_type"] == "audio"), None)
    probs = []
    if v.get("codec_name") != "h264":
        probs.append(f"video is {v.get('codec_name')} (Windows heeft daarvoor een extensie nodig)")
    if v.get("codec_tag_string") != "avc1":
        probs.append(f"tag {v.get('codec_tag_string')}")
    if v.get("profile") not in ("High", "Main", "Constrained Baseline"):
        probs.append(f"profiel {v.get('profile')}")
    if int(v.get("level") or 0) > 52:
        probs.append(f"level {v.get('level')}")
    if a is not None and (a.get("codec_name") != "aac" or a.get("profile") not in ("LC", None)):
        probs.append(f"audio {a.get('codec_name')} {a.get('profile')}")
    desc = f"{v.get('codec_name')} {v.get('profile')} level {v.get('level')}"
    return not probs, desc if not probs else desc + " | " + "; ".join(probs)


def c5_smooth(path, info):
    v = next(s for s in info["streams"] if s["codec_type"] == "video")
    total = count_frames(path)
    _, _, err = run(["ffmpeg", "-hide_banner", "-stats", "-i", path, "-map", "0:v",
                     "-vf", "mpdecimate=hi=64*4:lo=64*2:frac=0.33", "-fps_mode", "passthrough", "-f", "null", "-"])
    m = re.findall(r"frame=\s*(\d+)", err)
    if not m or total <= 0:
        return False, "meting mislukt (geen frametelling)"  # faalt dicht, nooit stil PASS
    kept = int(m[-1])
    dup = max(0, total - kept)
    ratio = dup / total if total else 1
    return ratio < 0.01, f"{dup}/{total} dubbele frames ({ratio:.2%})"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("video")
    ap.add_argument("--master")
    ap.add_argument("--captions")
    ap.add_argument("--counter", help="counter.ass (teller-getallen)")
    ap.add_argument("--panel", help="counter_panel.png (teller-iconen)")
    ap.add_argument("--json", help="schrijf resultaat ook als json")
    a = ap.parse_args()
    info = probe(a.video)
    dur = float(info["format"]["duration"])
    results = {
        "C1 formaat": c1_format(a.video, info),
        "C2 kwaliteit": c2_vmaf(a.video, a.master),
        "C3 audio": c3_audio(a.video, info),
        "C4 captions": c4_captions(a.captions, dur),
        "C5 vloeiend": c5_smooth(a.video, info),
        "C6 afspeelbaar": c6_playable(a.video, info),
        "M2 luidheid": m2_loudness(a.video),
    }
    if a.counter and os.path.exists(a.counter):
        results["U1 teller-zone"] = u1_counter_zone(a.counter, a.panel, dur)
        results["U2 een lettergrootte"] = u2_one_size(a.counter)
    allok = True
    for k, (ok, msg) in results.items():
        mark = "PASS" if ok else ("SKIP" if ok is None else "FAIL")
        allok &= ok is not False and ok is not None
        print(f"[{mark}] {k}: {msg}")
    print("RESULTAAT:", "ALLES GROEN" if allok else "NIET GESLAAGD")
    if a.json:
        with open(a.json, "w", encoding="utf-8") as f:
            json.dump({k: {"ok": v[0], "msg": v[1]} for k, v in results.items()}, f, indent=2)
    sys.exit(0 if allok else 1)


if __name__ == "__main__":
    main()
