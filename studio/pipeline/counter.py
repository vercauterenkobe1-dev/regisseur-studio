"""Materiaalteller: blokiconen + tellers die meelopen met de bouw (zoals 'hoeveel blokken kost dit?'-Shorts).

Invoer: counter.json van de mod  {materials: [{id, times[], top/side/flat, tintTop, tintSide}], total}
Uitvoer: counter_panel.png (1080x1920, transparant, de iconen) en counter.ass (de getallen).
Iconen: isometrische kubus uit de echte bloktextures van de game, zoals in de inventaris.
"""
import json, os

W, H = 1080, 1920
# Layout (WP2): compact bovenaan, onder de bovenste Shorts-knoppen (schatting: ~190 px) en links van de
# rechterknoppenkolom (x <= 916). Gelezen per kolom: grootste linksboven, dan naar beneden, dan rechts.
TOP = 190             # bovenrand van het paneel (y)
RIGHT = 916           # rechtergrens voor getallen (x)
ICON = 100            # breedte van een icoon in pixels
ROWH = 120            # rijafstand
ROW0 = TOP + 56       # middelpunt eerste rij
COLS = [(150, 216), (590, 656)]  # (icoon-midden x, getal-begin x) per kolom
NUM_W = 250           # beschikbare breedte voor het langste getal
MAX_FS = 84
SS = 4                # supersampling voor gladde randen


def _load(path, tint):
    from PIL import Image
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    if h > w:  # geanimeerde texture: eerste beeldje
        im = im.crop((0, 0, w, w))
    if tint:
        r, g, b = int(tint[0:2], 16), int(tint[2:4], 16), int(tint[4:6], 16)
        px = im.load()
        for y in range(im.size[1]):
            for x in range(im.size[0]):
                p = px[x, y]
                px[x, y] = (p[0] * r // 255, p[1] * g // 255, p[2] * b // 255, p[3])
    return im


def _face(canvas, tex, origin, u, v, shade):
    from PIL import Image, ImageEnhance
    n = 256
    t = tex.resize((n, n), Image.NEAREST)
    if shade != 1.0:
        a = t.getchannel("A")
        t = ImageEnhance.Brightness(t.convert("RGB")).enhance(shade).convert("RGBA")
        t.putalpha(a)
    # uitvoer = origin + x/n*u + y/n*v  ->  inverse affiene afbeelding voor PIL
    a11, a12, a21, a22 = u[0] / n, v[0] / n, u[1] / n, v[1] / n
    det = a11 * a22 - a12 * a21
    i11, i12, i21, i22 = a22 / det, -a12 / det, -a21 / det, a11 / det
    ox, oy = origin
    data = (i11, i12, -(i11 * ox + i12 * oy), i21, i22, -(i21 * ox + i22 * oy))
    f = t.transform(canvas.size, Image.AFFINE, data, resample=Image.NEAREST, fillcolor=(0, 0, 0, 0))
    canvas.alpha_composite(f)


def icon(mat, d):
    """Eén icoon (RGBA, ICON x ICON) met zachte schaduw."""
    from PIL import Image, ImageFilter
    S = ICON * SS
    c = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    if mat.get("flat"):
        t = _load(os.path.join(d, mat["flat"]), "")
        k = int(S * 0.86)
        c.alpha_composite(t.resize((k, k), Image.NEAREST), ((S - k) // 2, (S - k) // 2))
    else:
        top = os.path.join(d, mat["top"]) if mat.get("top") else None
        side = os.path.join(d, mat["side"]) if mat.get("side") else top
        if not top and not side:
            return None
        top = top or side
        tt, ts = _load(top, mat.get("tintTop", "")), _load(side, mat.get("tintSide", ""))
        m = S * 0.04  # kleine marge
        s = S - 2 * m
        _face(c, ts, (m, m + s / 4), (s / 2, s / 4), (0, s / 2), 0.80)          # linkerzijde
        _face(c, ts, (m + s / 2, m + s / 2), (s / 2, -s / 4), (0, s / 2), 0.62)  # rechterzijde
        _face(c, tt, (m + s / 2, m), (s / 2, s / 4), (-s / 2, s / 4), 1.0)        # bovenkant
    c = c.resize((ICON, ICON), Image.LANCZOS)
    pad = 16
    out = Image.new("RGBA", (ICON + 2 * pad, ICON + 2 * pad), (0, 0, 0, 0))
    sh = Image.new("RGBA", out.size, (0, 0, 0, 0))
    a = c.getchannel("A").point(lambda x: x * 0.55)
    sh.paste((0, 0, 0, 255), (pad + 5, pad + 8), a)
    out.alpha_composite(sh.filter(ImageFilter.GaussianBlur(7)))
    out.alpha_composite(c, (pad, pad))
    return out


def _slot(i, rows):
    """Per kolom gevuld: 0,1,2 links van boven naar beneden, daarna rechts."""
    col, row = divmod(i, rows)
    return COLS[col], ROW0 + row * ROWH


def fmt(n):
    return f"{n:,}"


def font_size(longest):
    """Eén lettergrootte voor alle getallen; het langste getal past in NUM_W (Arial Black, schuin)."""
    s = fmt(longest)
    em = sum(0.36 if ch == "," else 0.68 for ch in s)
    return max(40, min(MAX_FS, int(NUM_W / em)))


def pick(mats):
    """Alleen blokken met minstens 1% van het totaal, minimaal 3, maximaal 6, grootste eerst."""
    mats = sorted(mats, key=lambda m: -len(m["times"]))[:6]
    total = sum(len(m["times"]) for m in mats) or 1
    keep = [m for m in mats if len(m["times"]) >= 0.01 * total]
    return keep if len(keep) >= 3 else mats[:3]


def build(counter_json, total, panel_path, ass_path, ass_time, offset=0.0, hold=0.0):
    """offset: verschuiving van de opname-tijd naar de video-tijd.
    hold > 0: er staat een teaser (het eindresultaat) voor; die toont de eindstanden, daarna telt alles vanaf 0.
    Geeft de lijst eindtijden (video-tijd) per getoond materiaal terug, of [] als er niets te tonen is."""
    d = os.path.dirname(os.path.abspath(counter_json))
    with open(counter_json, encoding="utf-8-sig") as f:
        data = json.load(f)
    from PIL import Image, ImageDraw
    entries = []
    for m in pick(data.get("materials", [])):
        ic = icon(m, os.path.join(d, "icons"))
        if ic is not None:
            entries.append((m, ic))
    if not entries:
        return []
    rows = (len(entries) + 1) // 2
    panel = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    # zacht donker verloop achter het paneel: leesbaar op sneeuw en lucht
    grad_h = ROW0 + (rows - 1) * ROWH + ROWH
    g = Image.new("L", (1, grad_h))
    for y in range(grad_h):
        g.putpixel((0, y), int(255 * 0.30 * (1 - (y / grad_h) ** 2)))
    shade = Image.new("RGBA", (W, grad_h), (0, 0, 0, 255))
    shade.putalpha(g.resize((W, grad_h)))
    panel.alpha_composite(shade, (0, 0))
    size = font_size(max(len(m["times"]) for m, _ in entries))
    lines = [
        "[Script Info]", "ScriptType: v4.00+", f"PlayResX: {W}", f"PlayResY: {H}",
        "WrapStyle: 2", "ScaledBorderAndShadow: yes", "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic,"
        " Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment,"
        " MarginL, MarginR, MarginV, Encoding",
        f"Style: Cnt,Arial Black,{size},&H00FFFFFF&,&H00FFFFFF&,&H00000000&,&H80000000&,-1,-1,0,0,100,100,0,0,1,7,3,4,0,0,0,1",
        "", "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    ends = []
    for i, (m, ic) in enumerate(entries):
        (ix, nx), y = _slot(i, rows)
        panel.alpha_composite(ic, (ix - ic.size[0] // 2, y - ic.size[1] // 2))
        times = sorted(t + offset for t in m["times"])
        n = len(times)
        pos = "{\\an4\\pos(" + f"{nx},{y}" + ")}"
        steps, k = [], 0
        for t in times:
            k += 1
            cs = round(t * 100)
            if steps and steps[-1][0] == cs:
                steps[-1] = (cs, k)
            else:
                steps.append((cs, k))
        prev_t, prev_n = 0.0, 0
        if hold > 0:
            lines.append(f"Dialogue: 1,{ass_time(0)},{ass_time(hold)},Cnt,,0,0,0,,{pos}{fmt(n)}")
            prev_t = hold
        for cs, cnt in steps:
            t = cs / 100
            if t > prev_t:
                lines.append(f"Dialogue: 1,{ass_time(prev_t)},{ass_time(t)},Cnt,,0,0,0,,{pos}{fmt(prev_n)}")
            prev_t, prev_n = t, cnt
        if total > prev_t:  # eindstand met een 'pop'
            pop = "{\\t(0,110,\\fscx122\\fscy122)\\t(110,320,\\fscx100\\fscy100)}"
            lines.append(f"Dialogue: 1,{ass_time(prev_t)},{ass_time(total)},Cnt,,0,0,0,,{pos}{pop}{fmt(prev_n)}")
        ends.append(prev_t)
    panel.save(panel_path)
    with open(ass_path, "w", encoding="utf-8-sig") as f:
        f.write("\n".join(lines) + "\n")
    return ends