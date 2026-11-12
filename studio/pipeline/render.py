"""Eindrender: storyboard.json (van de mod) -> afgewerkte Short final.mp4 (1080x1920, 60 fps, H.264).

Gebruik:  python render.py storyboard.json [--encoder qsv|x264|x265|hevc] [--out naam.mp4]

Stappen (zie main):
  1. tijdlijn     eerste 0,4 s wegknippen (onrustig) en de teaser vooraan zetten (hook: eindresultaat eerst)
  2. captions     woord-voor-woord captions -> captions.ass
  3. teller       materiaalteller (counter.py) -> counter_panel.png + counter.ass
  4. beeld        segmenten + overgangen + teller + captions -> lossless master.mkv (bron voor de kwaliteitsmeting)
  5. geluid       muziek (eigen track of Regisseur Beat uit synth.py) + Minecraft-geluiden (sfx.py),
                  gemengd en gemasterd op -14 LUFS / -1.5 dBTP -> music_master.wav
  6. eindencode   master + geluid -> final.mp4 (BT.709, faststart, GOP 0,5 s); timeline.json voor audio_check.py

Hulpbestanden: counter.py (teller), sfx.py (geluiden), synth.py (Regisseur Beat), beats.py (tempo).
Controles daarna (door de mod gestart): judge.py, sync_check.py, audio_check.py.
"""
import argparse, json, os, re, subprocess, sys

W, H, FPS = 1080, 1920, 60
COLOR = ["-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709", "-color_range", "tv"]
LOSSLESS = ["-c:v", "libx264", "-qp", "0", "-preset", "ultrafast", "-pix_fmt", "yuv420p"] + COLOR

ENCODERS = {
    # beste kwaliteit per bit; CPU-zwaar maar een Short is kort
    "x265": ["-c:v", "libx265", "-preset", "slow", "-crf", "16", "-tag:v", "hvc1",
             "-x265-params", "colorprim=bt709:transfer=bt709:colormatrix=bt709:log-level=error"],
    "x264": ["-c:v", "libx264", "-preset", "slow", "-crf", "14", "-profile:v", "high"],
    # Intel QuickSync in H.264 (standaard): speelt overal af, ook in Windows Mediaspeler zonder extensies.
    # HEVC ("hevc") is iets zuiniger, maar Windows kan het alleen afspelen met een betaalde Store-extensie.
    # QSV schrijft zelf geen kleurtags in de bitstream; de bsf zet BT.709 erin.
    "qsv": ["-c:v", "h264_qsv", "-preset", "veryslow", "-global_quality", "16", "-profile:v", "high",
            "-tag:v", "avc1",
            "-bsf:v", "h264_metadata=colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1"],
    "hevc": ["-c:v", "hevc_qsv", "-preset", "veryslow", "-global_quality", "18", "-tag:v", "hvc1",
             "-bsf:v", "hevc_metadata=colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1"],
}


def sh(args, cwd):
    p = subprocess.run(args, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode != 0:
        sys.exit(f"ffmpeg faalde:\n{' '.join(args)}\n{p.stderr[-1500:]}")
    return p.stderr


# ---------------------------------------------------------------- captions

def ass_time(t):
    cs = int(round(t * 100))
    return f"{cs // 360000}:{cs // 6000 % 60:02d}:{cs // 100 % 60:02d}.{cs % 100:02d}"


def bgr(hexrgb):
    h = hexrgb.lstrip("#")
    return f"&H00{h[4:6]}{h[2:4]}{h[0:2]}&"


def build_ass(captions, style, path):
    """Woord-voor-woord captions: max N woorden in beeld, actief woord in accentkleur met 'pop'."""
    font = style.get("font", "Arial Black")
    size = style.get("size", 92)
    base, accent = bgr(style.get("color", "FFFFFF")), bgr(style.get("highlight", "FFE600"))
    y = style.get("y", 1180)
    per = style.get("words_per_chunk", 3)
    lines = [
        "[Script Info]", "ScriptType: v4.00+", f"PlayResX: {W}", f"PlayResY: {H}",
        "WrapStyle: 0", "ScaledBorderAndShadow: yes", "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic,"
        " Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment,"
        " MarginL, MarginR, MarginV, Encoding",
        f"Style: Cap,{font},{size},{base},{base},&H00000000&,&H90000000&,-1,0,0,0,100,100,0,0,1,7,4,5,130,130,0,1",
        "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    for c in captions:
        words = c["text"].upper().split()
        if not words:
            continue
        times = c.get("word_times")  # optioneel: exacte start per woord (bv. uit voice-over)
        if not times:
            step = (c["end"] - c["start"]) / len(words)
            times = [c["start"] + i * step for i in range(len(words))]
        for i, w in enumerate(words):
            chunk0 = (i // per) * per
            chunk = words[chunk0: chunk0 + per]
            t0 = times[i]
            t1 = times[i + 1] if i + 1 < len(words) and (i + 1) // per == i // per else (
                times[i + 1] if i + 1 < len(words) else c["end"])
            parts = []
            for j, cw in enumerate(chunk):
                if chunk0 + j == i:
                    parts.append("{\\c" + accent + "\\fscx118\\fscy118\\t(0,90,\\fscx100\\fscy100)}" + cw + "{\\c" + base + "}")
                else:
                    parts.append(cw)
            text = " ".join(parts)
            intro = "{\\an5\\pos(540," + str(y) + ")}"
            lines.append(f"Dialogue: 0,{ass_time(t0)},{ass_time(t1)},Cap,,0,0,0,,{intro}{text}")
    with open(path, "w", encoding="utf-8-sig") as f:
        f.write("\n".join(lines) + "\n")


# ---------------------------------------------------------------- subscribe-eindbeeld

def rounded_rect(w, h, r):
    """ASS-tekenpad (p1-modus) voor een afgeronde rechthoek, gecentreerd rond (0,0)."""
    k = 0.5523 * r  # bezier-benadering van een kwartcirkel
    x0, y0, x1, y1 = 0, 0, w, h  # libass centreert een tekening met an5 zelf op haar omhullende
    pts = [f"m {x0 + r:.0f} {y0:.0f}", f"l {x1 - r:.0f} {y0:.0f}",
           f"b {x1 - r + k:.0f} {y0:.0f} {x1:.0f} {y0 + r - k:.0f} {x1:.0f} {y0 + r:.0f}", f"l {x1:.0f} {y1 - r:.0f}",
           f"b {x1:.0f} {y1 - r + k:.0f} {x1 - r + k:.0f} {y1:.0f} {x1 - r:.0f} {y1:.0f}", f"l {x0 + r:.0f} {y1:.0f}",
           f"b {x0 + r - k:.0f} {y1:.0f} {x0:.0f} {y1 - r + k:.0f} {x0:.0f} {y1 - r:.0f}", f"l {x0:.0f} {y0 + r:.0f}",
           f"b {x0:.0f} {y0 + r - k:.0f} {x0 + r - k:.0f} {y0:.0f} {x0 + r:.0f} {y0:.0f}"]
    return " ".join(pts)


def build_endcard(total, card, path):
    """Rode YouTube-subscribeknop de laatste N seconden: popt binnen, klopt een keer, blijft staan tot het einde.
    card = {"text": "SUBSCRIBE", "dur": 3, "y": 1300}"""
    dur = float(card.get("dur", 3))
    t0, t1 = max(0.0, total - dur), total
    y = int(card.get("y", 1300))
    text = card.get("text", "SUBSCRIBE").upper()
    w, h = 40 + 62 * len(text), 150
    pop = r"\fscx30\fscy30\t(0,170,\fscx112\fscy112)\t(170,300,\fscx100\fscy100)"
    pulse = r"\t(1400,1550,\fscx106\fscy106)\t(1550,1750,\fscx100\fscy100)"
    pos = rf"\an5\pos(540,{y})"
    lines = [
        "[Script Info]", "ScriptType: v4.00+", f"PlayResX: {W}", f"PlayResY: {H}", "ScaledBorderAndShadow: yes", "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic,"
        " Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment,"
        " MarginL, MarginR, MarginV, Encoding",
        # knop: YouTube-rood (#FF0000, in ASS BGR) met zachte schaduw; tekst: wit, vet
        "Style: Box,Arial,20,&H000000FF&,&H000000FF&,&H00000000&,&H70000000&,0,0,0,0,100,100,0,0,1,0,6,5,0,0,0,1",
        "Style: Txt,Arial Black,84,&H00FFFFFF&,&H00FFFFFF&,&H00000000&,&H00000000&,-1,0,0,0,100,100,2,0,1,0,0,5,0,0,0,1",
        "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
        rf"Dialogue: 3,{ass_time(t0)},{ass_time(t1)},Box,,0,0,0,,{{{pos}\fad(120,0){pop}{pulse}\p1}}{rounded_rect(w, h, 34)}",
        rf"Dialogue: 4,{ass_time(t0)},{ass_time(t1)},Txt,,0,0,0,,{{{pos}\fad(120,0){pop}{pulse}}}{text}",
    ]
    with open(path, "w", encoding="utf-8-sig") as f:
        f.write("\n".join(lines) + "\n")


# ---------------------------------------------------------------- video

def segment(i, clip, wd):
    out = f"seg{i:02d}.mkv"
    src = os.path.abspath(clip["src"])
    dur = clip["dur"]
    z0, z1 = clip.get("zoom", [1.0, 1.0])
    # cover-fit op 2x resolutie, dan vloeiend zoomen met subpixel-precisie en terugschalen (geen trilling)
    zoom = f"({z0}+({z1}-{z0})*min(t/{dur},1))"
    vf = (f"fps={FPS},scale={2*W}:{2*H}:force_original_aspect_ratio=increase:flags=lanczos,"
          f"crop={2*W}:{2*H},"
          f"zoompan=z='{zoom.replace('t', 'it')}':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s={W}x{H}:fps={FPS},"
          f"setsar=1,format=yuv420p") if (z0, z1) != (1.0, 1.0) else (
          f"fps={FPS},scale={W}:{H}:force_original_aspect_ratio=increase:flags=lanczos,crop={W}:{H},setsar=1,format=yuv420p")
    sh(["ffmpeg", "-hide_banner", "-y", "-ss", str(clip.get("in", 0)), "-t", str(dur), "-i", src,
        "-vf", vf, "-an", "-fps_mode", "cfr", *LOSSLESS, out], wd)
    return out, dur


def build_master(sb, wd, ass, counter=None):
    segs = [segment(i, c, wd) for i, c in enumerate(sb["clips"])]
    tr = sb.get("transition", {"type": "fade", "dur": 0.25})
    cut = tr.get("type") == "cut"
    td = tr["dur"] if len(segs) > 1 and not cut else 0
    inputs, fc, prev, offset = [], [], "[0:v]", 0.0
    for i, (f, d) in enumerate(segs):
        inputs += ["-i", f]
    if cut and len(segs) > 1:  # harde cut (op de maat): gewoon achter elkaar
        fc.append("".join(f"[{i}:v]" for i in range(len(segs))) + f"concat=n={len(segs)}:v=1:a=0[cc]")
        prev = "[cc]"
    for i in range(1, len(segs) if not cut else 1):
        offset += segs[i - 1][1] - td
        lbl = f"[x{i}]"
        fc.append(f"{prev}[{i}:v]xfade=transition={tr['type']}:duration={td}:offset={offset:.4f}{lbl}")
        prev = lbl
    total = sum(d for _, d in segs) - td * (len(segs) - 1)
    if counter:  # materiaalteller: iconen-paneel eronder, getallen erboven, captions bovenaan
        panel, cass = counter
        inputs += ["-i", panel]
        fc.append(f"{prev}[{len(segs)}:v]overlay=0:0:format=auto,ass={cass}[cnt]")
        prev = "[cnt]"
    caps = f"ass={ass}" if ass else "null"
    end = ",ass=endcard.ass" if os.path.exists(os.path.join(wd, "endcard.ass")) else ""  # subscribe-eindbeeld bovenop
    fc.append(f"{prev}{caps}{end},format=yuv420p[v]")
    sh(["ffmpeg", "-hide_banner", "-y", *inputs, "-filter_complex", ";".join(fc), "-map", "[v]",
        "-r", str(FPS), "-fps_mode", "cfr", *LOSSLESS, "master.mkv"], wd)
    return total


# ---------------------------------------------------------------- audio

def lufs(path, wd):
    err = sh(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af", "ebur128", "-f", "null", "-"], wd)
    m = re.search(r"I:\s+(-?[\d.]+) LUFS", err[err.rfind("Summary:"):])
    return float(m.group(1)) if m else None


def show_times(sb):
    """Bouwstart, finale en tel-lengte in video-tijd (uit show.json), of None."""
    if not sb.get("show") or not os.path.exists(sb["show"]):
        return None
    from sfx import clip_map
    with open(sb["show"], encoding="utf-8-sig") as f:
        show = json.load(f)
    cin, _, off = clip_map(sb["clips"])[-1]
    v = lambda t: off + t - cin
    fin = show.get("finale", -1)
    beat = (show.get("music") or {}).get("period")
    return {"show": show, "build": v(show["sections"]["terraEnd"]) if fin > 0 else None,
            "finale": v(fin) if fin > 0 else None, "beat": beat}


def music_stem(sb, total, wd, teaser):
    """Muziekstem op de video-tijdlijn. Regisseur Beat: per video gegenereerd. Eigen track: knippen + DJ-boog."""
    music = sb["music"]
    st = show_times(sb)
    if os.path.basename(music["src"]).lower().startswith("regisseur beat") and st:
        import synth
        synth.for_video(st["show"], sb["clips"], total, os.path.join(wd, "music_stem.wav"), teaser=teaser)
        return "music_stem.wav", st
    src = os.path.abspath(music["src"])
    fin, fout = music.get("fade_in", 0.05), music.get("fade_out", 1.0)
    af = (f"atrim=start={music.get('in', 0)}:duration={total},asetpts=PTS-STARTPTS,"
          f"aresample=48000,afade=t=in:d={fin},afade=t=out:st={max(0, total - fout)}:d={fout}")
    if st and st["build"] and st["beat"]:  # DJ-boog: 1 tel stilte voor de drop, bas omhoog tijdens de bouw
        b, beat = st["build"], st["beat"]
        end = st["finale"] or total
        af += (f",volume=enable='between(t,{b - beat:.4f},{b - 0.005:.4f})':volume=0.05"
               f",equalizer=f=80:t=q:w=1:g=3:enable='between(t,{b:.4f},{end:.4f})'")
    sh(["ffmpeg", "-hide_banner", "-y", "-i", src, "-af", af, "-ac", "2", "-c:a", "pcm_s24le", "music_stem.wav"], wd)
    return "music_stem.wav", st


def master_audio(sb, total, wd, teaser=0.0, counter_ends=()):
    """Muziek + geluidseffecten -> één master op -14 LUFS / -1.5 dBTP.
    SFX zitten 6 LU onder de muziek; de muziek duikt even weg onder elk geluid (sidechain)."""
    stem, st = music_stem(sb, total, wd, teaser)
    inputs, fc = ["-i", stem], "[0:a]anull[mix]"
    if sb.get("sfx") and os.path.exists(sb["sfx"]):
        try:
            import sfx
            n = sfx.build(sb["sfx"], sb["clips"], total, os.path.join(wd, "sfx.wav"),
                          os.path.join(wd, "sfx_events.json"), counter_ends=counter_ends)
            lm, ls = lufs(stem, wd), lufs("sfx.wav", wd)
            if n and lm is not None and ls is not None:
                g = 10 ** ((lm - 6 - ls) / 20)
                inputs += ["-i", "sfx.wav"]
                fc = (f"[1:a]volume={g:.4f},asplit[s1][s2];"
                      f"[0:a][s1]sidechaincompress=threshold=0.04:ratio=3:attack=5:release=120[md];"
                      f"[md][s2]amix=inputs=2:normalize=0:duration=first[mix]")
                print(f"geluidseffecten: {n} geluiden, muziek {lm} LUFS, sfx {ls} LUFS -> x{g:.3f}")
                with open(os.path.join(wd, "mix.json"), "w", encoding="utf-8") as f:
                    json.dump({"sfx_gain": g}, f)
        except Exception as e:
            print(f"geluidseffecten overgeslagen: {e}")
    # limiter voor de loudnorm: minder piek = loudnorm kan lineair naar -14 zonder te pompen
    pre = f"{fc};[mix]alimiter=limit=0.5:attack=3:release=60:level=false,aresample=48000[pre]"
    target = "I=-14:TP=-1.5:LRA=11"
    err = sh(["ffmpeg", "-hide_banner", "-y", *inputs, "-filter_complex", f"{pre};[pre]loudnorm={target}:print_format=json[o]",
              "-map", "[o]", "-f", "null", "-"], wd)
    m = json.loads(err[err.rfind("{"): err.rfind("}") + 1])
    ln = (f"loudnorm={target}:measured_I={m['input_i']}:measured_TP={m['input_tp']}:measured_LRA={m['input_lra']}"
          f":measured_thresh={m['input_thresh']}:offset={m['target_offset']}:linear=true")
    sh(["ffmpeg", "-hide_banner", "-y", *inputs, "-filter_complex",
        f"{pre};[pre]{ln},aresample=48000,alimiter=limit=0.84:level=false,afade=t=out:st={max(0, total - 0.04):.3f}:d=0.04[o]",
        "-map", "[o]", "-ac", "2", "-c:a", "pcm_s24le", "music_master.wav"], wd)
    return "music_master.wav"

# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("storyboard")
    ap.add_argument("--encoder", choices=ENCODERS, default=None)
    ap.add_argument("--out", default=None)
    a = ap.parse_args()
    sbpath = os.path.abspath(a.storyboard)
    wd = os.path.dirname(sbpath)
    with open(sbpath, encoding="utf-8") as f:
        sb = json.load(f)
    os.chdir(wd)
    enc = a.encoder or sb.get("encoder", "qsv")
    out = a.out or sb.get("out", "final.mp4")

    teaser, shift = plan_timeline(sb)                                        # 1. tijdlijn
    ass = None
    if sb.get("captions"):                                                   # 2. captions
        ass = "captions.ass"
        build_ass(sb["captions"], sb.get("style", {}), os.path.join(wd, ass))
    counter, counter_ends = make_counter(sb, teaser, shift)                  # 3. teller
    if sb.get("endcard"):                                                    #    + subscribe-eindbeeld
        build_endcard(sum(c["dur"] for c in sb["clips"]), sb["endcard"], os.path.join(wd, "endcard.ass"))
    total = build_master(sb, wd, ass, counter)                               # 4. beeld
    print(f"master.mkv klaar ({total:.2f} s)")
    audio = ["-i", master_audio(sb, total, wd, teaser, counter_ends)] if sb.get("music") else []   # 5. geluid
    write_timeline(sb, total, teaser)
    amap = ["-map", "1:a", "-c:a", "aac", "-b:a", "320k", "-ar", "48000"] if audio else []          # 6. eindencode
    sh(["ffmpeg", "-hide_banner", "-y", "-i", "master.mkv", *audio, "-map", "0:v", *amap,
        *ENCODERS[enc], "-pix_fmt", "yuv420p", *COLOR, "-r", str(FPS), "-fps_mode", "cfr",
        "-g", str(FPS // 2), "-t", f"{total:.3f}", "-movflags", "+faststart", out], wd)
    print(f"klaar: {out}  (encoder {enc})")


def plan_timeline(sb):
    """Past sb aan voor de hook. Geeft (teaser, shift): lengte van de teaser en hoeveel alles uit de opname
    verschuift in de video (teaser - lead_trim).
      - lead_trim: de eerste frames van een opname zijn onrustig (shader-belichting past zich aan, chunks laden).
        Die knippen we weg; de muziek schuift even ver mee, dus alles blijft op de maat.
      - teaser: open met het eindresultaat (laatste stuk van de opname), harde cut naar het begin.
        De teaser duurt precies 1 maat, dus de muziek blijft op de maat met de cuts in de opname."""
    if len(sb["clips"]) != 1 or not (sb.get("teaser") or sb.get("lead_trim")):
        return 0.0, 0.0
    teaser = 0.0
    lead = float(sb.get("lead_trim", 0))
    main_clip = dict(sb["clips"][0])
    main_clip["in"] = main_clip.get("in", 0) + lead
    main_clip["dur"] -= lead
    if sb.get("music"):
        sb["music"]["in"] = sb["music"].get("in", 0) + lead
    clips = [main_clip]
    if sb.get("teaser"):
        teaser = float(sb["teaser"]["dur"])
        clips.insert(0, dict(main_clip, **{"in": main_clip["in"] + main_clip["dur"] - teaser, "dur": teaser}))
        sb["transition"] = {"type": "cut", "dur": 0}
    sb["clips"] = clips
    shift = teaser - lead
    caps = []
    for c in sb.get("captions", []):
        s, e = max(c["start"] + shift, teaser), c["end"] + shift
        if e - s > 0.3:
            caps.append(dict(c, start=s, end=e))
    if teaser and sb["teaser"].get("text"):
        caps.insert(0, {"start": 0.0, "end": teaser - 0.03, "text": sb["teaser"]["text"]})
    sb["captions"] = caps
    return teaser, shift


def make_counter(sb, teaser, shift):
    """Materiaalteller. Geeft ((paneel, ass) of None, eindtijden per teller). Een fout stopt de video nooit."""
    if not sb.get("counter"):
        return None, []
    try:
        import counter as cnt
        dur = sum(c["dur"] for c in sb["clips"])
        ends = cnt.build(sb["counter"], dur, "counter_panel.png", "counter.ass", ass_time, offset=shift, hold=teaser)
        return (("counter_panel.png", "counter.ass") if ends else None), ends
    except Exception as e:
        print(f"materiaalteller overgeslagen: {e}")
        return None, []


def write_timeline(sb, total, teaser):
    """timeline.json: bouwstart, finale en tel in video-tijd, voor audio_check.py."""
    st = show_times(sb)
    with open("timeline.json", "w", encoding="utf-8") as f:
        json.dump({"total": total, "teaser": teaser, "clips": sb["clips"],
                   "build": st and st["build"], "finale": st and st["finale"], "beat": st and st["beat"]}, f)


if __name__ == "__main__":
    main()
