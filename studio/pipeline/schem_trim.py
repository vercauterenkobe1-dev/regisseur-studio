"""Alleen het gebouw: knipt een gedownloade .schem bij tot het bouwwerk zelf.

Veel downloads bevatten het hele stuk wereld: ondergrond tot op bedrock, water, losse bomen of bouwsels op de rand.
Dit script:
  1. zoekt waar de ondergrond eindigt (laag waar aarde/steen/... minder dan 5% van de oppervlakte vullen)
     en gooit alles daaronder weg;
  2. houdt alleen het grootste aaneengesloten bouwwerk over (in x en z), losse stukjes op de rand gaan weg;
  3. gooit onzichtbare blokken weg (light, barrier, structure_void) en lucht;
  4. schrijft een nieuwe .schem (Sponge v2) die de mod gewoon inleest.

Gebruik:  python schem_trim.py invoer.schem uitvoer.schem [--ground Y]
"""
import argparse, collections, gzip, io, struct

TERRAIN = {"dirt", "grass_block", "stone", "bedrock", "water", "gravel", "sand", "granite", "diorite", "andesite",
           "deepslate", "tuff", "clay", "lava", "coarse_dirt", "podzol", "dirt_path", "sandstone", "short_grass",
           "tall_grass", "snow", "moss_block"}
INVISIBLE = {"air", "cave_air", "void_air", "light", "barrier", "structure_void"}


# ------------------------------------------------------------------ NBT lezen en schrijven

def nbt_read(path):
    d = gzip.open(path).read()
    pos = [0]

    def rd(n):
        b = d[pos[0]:pos[0] + n]; pos[0] += n; return b

    def name():
        n = struct.unpack(">H", rd(2))[0]; return rd(n).decode("utf-8", "replace")

    def payload(t):
        if t == 1: return ("b", struct.unpack(">b", rd(1))[0])
        if t == 2: return ("s", struct.unpack(">h", rd(2))[0])
        if t == 3: return ("i", struct.unpack(">i", rd(4))[0])
        if t == 4: return ("l", struct.unpack(">q", rd(8))[0])
        if t == 5: return ("f", struct.unpack(">f", rd(4))[0])
        if t == 6: return ("d", struct.unpack(">d", rd(8))[0])
        if t == 7: n = struct.unpack(">i", rd(4))[0]; return ("B", rd(n))
        if t == 8: return ("S", name())
        if t == 9:
            et = rd(1)[0]; n = struct.unpack(">i", rd(4))[0]; return ("L", et, [payload(et) for _ in range(n)])
        if t == 10:
            o = {}
            while True:
                tt = rd(1)[0]
                if tt == 0: return ("C", o)
                k = name(); o[k] = payload(tt)
        if t == 11: n = struct.unpack(">i", rd(4))[0]; return ("I", list(struct.unpack(f">{n}i", rd(4 * n))))
        if t == 12: n = struct.unpack(">i", rd(4))[0]; return ("J", list(struct.unpack(f">{n}q", rd(8 * n))))
        raise ValueError(f"onbekend NBT-type {t}")

    t = rd(1)[0]; name()
    return payload(t)


TYPE_ID = {"b": 1, "s": 2, "i": 3, "l": 4, "f": 5, "d": 6, "B": 7, "S": 8, "L": 9, "C": 10, "I": 11, "J": 12}


def nbt_write(path, root_name, tag):
    out = io.BytesIO()

    def wname(s):
        b = s.encode("utf-8"); out.write(struct.pack(">H", len(b))); out.write(b)

    def wpay(tag):
        k = tag[0]
        if k == "b": out.write(struct.pack(">b", tag[1]))
        elif k == "s": out.write(struct.pack(">h", tag[1]))
        elif k == "i": out.write(struct.pack(">i", tag[1]))
        elif k == "l": out.write(struct.pack(">q", tag[1]))
        elif k == "f": out.write(struct.pack(">f", tag[1]))
        elif k == "d": out.write(struct.pack(">d", tag[1]))
        elif k == "B": out.write(struct.pack(">i", len(tag[1]))); out.write(tag[1])
        elif k == "S": wname(tag[1])
        elif k == "L":
            out.write(bytes([tag[1]])); out.write(struct.pack(">i", len(tag[2])))
            for x in tag[2]: wpay(x)
        elif k == "C":
            for key, v in tag[1].items():
                out.write(bytes([TYPE_ID[v[0]]])); wname(key); wpay(v)
            out.write(b"\x00")
        elif k == "I": out.write(struct.pack(">i", len(tag[1]))); out.write(struct.pack(f">{len(tag[1])}i", *tag[1]))
        elif k == "J": out.write(struct.pack(">i", len(tag[1]))); out.write(struct.pack(f">{len(tag[1])}q", *tag[1]))

    out.write(bytes([10])); wname(root_name); wpay(tag)
    with gzip.open(path, "wb") as f:
        f.write(out.getvalue())


def varints(data):
    ids, i, n = [], 0, len(data)
    while i < n:
        v, sh = 0, 0
        while True:
            b = data[i]; i += 1
            v |= (b & 0x7F) << sh; sh += 7
            if not b & 0x80: break
        ids.append(v)
    return ids


def to_varints(ids):
    out = bytearray()
    for v in ids:
        while True:
            b = v & 0x7F; v >>= 7
            if v: out.append(b | 0x80)
            else: out.append(b); break
    return bytes(out)


# ------------------------------------------------------------------ bijknippen

def biggest_run(hist, thresh):
    """Grootste aaneengesloten reeks bakjes boven de drempel -> (eerste, laatste) bakje."""
    best, cur, start, total = None, 0, None, 0
    keys = range(max(hist) + 2) if hist else range(0)
    for k in keys:
        if hist.get(k, 0) > thresh:
            if start is None: start, total = k, 0
            total += hist[k]
        elif start is not None:
            if best is None or total > best[2]: best = (start, k - 1, total)
            start = None
    return (best[0], best[1]) if best else (0, max(hist))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("invoer"); ap.add_argument("uitvoer")
    ap.add_argument("--ground", type=int, default=None, help="laagste y die bij het gebouw hoort (anders automatisch)")
    a = ap.parse_args()
    root = nbt_read(a.invoer)[1]
    s = root["Schematic"][1] if "Schematic" in root else root
    version = s["Version"][1]
    W, H, L = s["Width"][1] & 0xFFFF, s["Height"][1] & 0xFFFF, s["Length"][1] & 0xFFFF
    blocks = s["Blocks"][1] if version >= 3 else s
    pal = (blocks["Palette"][1])
    data = blocks["Data"][1] if version >= 3 else s["BlockData"][1]
    inv = {v[1]: k for k, v in pal.items()}
    ids = varints(data)
    base = {i: inv[i].split("[")[0].split(":")[-1] for i in inv}
    area = W * L

    # 1. waar eindigt de ondergrond?
    terr_y = collections.Counter()
    for idx, v in enumerate(ids):
        if base[v] in TERRAIN or base[v].endswith("_ore"): terr_y[idx // area] += 1
    y0 = a.ground if a.ground is not None else next(
        (y for y in range(1, H) if terr_y[y] < 0.05 * area and terr_y[y - 1] >= 0.05 * area), 0)
    if a.ground is None and all(terr_y[y] < 0.05 * area for y in range(H)): y0 = 0

    # 2. grootste aaneengesloten bouwwerk in x en z (bakjes van 8)
    hx, hz = collections.Counter(), collections.Counter()
    for idx, v in enumerate(ids):
        if idx // area < y0 or base[v] in INVISIBLE: continue
        r = idx % area
        hx[(r % W) // 8] += 1; hz[(r // W) // 8] += 1
    tx = 0.01 * max(hx.values()); tz = 0.01 * max(hz.values())
    bx0, bx1 = biggest_run(hx, tx); bz0, bz1 = biggest_run(hz, tz)
    x0, x1, z0, z1 = bx0 * 8, min(W - 1, bx1 * 8 + 7), bz0 * 8, min(L - 1, bz1 * 8 + 7)

    # 3. overhouden wat zichtbaar is, en strak bijsnijden
    keep = []
    for idx, v in enumerate(ids):
        y = idx // area
        if y < y0 or base[v] in INVISIBLE: continue
        r = idx % area; z = r // W; x = r % W
        if x0 <= x <= x1 and z0 <= z <= z1: keep.append((x, y, z, v))
    mx, my, mz = min(k[0] for k in keep), min(k[1] for k in keep), min(k[2] for k in keep)
    Mx, My, Mz = max(k[0] for k in keep), max(k[1] for k in keep), max(k[2] for k in keep)
    nW, nH, nL = Mx - mx + 1, My - my + 1, Mz - mz + 1

    # 4. nieuw palet (lucht = 0) en schrijven
    new_pal = {"minecraft:air": 0}
    grid = [0] * (nW * nH * nL)
    for x, y, z, v in keep:
        name = inv[v]
        if name not in new_pal: new_pal[name] = len(new_pal)
        grid[(y - my) * nW * nL + (z - mz) * nW + (x - mx)] = new_pal[name]
    out = {
        "Version": ("i", 2),
        "DataVersion": s.get("DataVersion", ("i", 3953)),
        "Width": ("s", nW if nW < 32768 else nW - 65536),
        "Height": ("s", nH if nH < 32768 else nH - 65536),
        "Length": ("s", nL if nL < 32768 else nL - 65536),
        "PaletteMax": ("i", len(new_pal)),
        "Palette": ("C", {k: ("i", v) for k, v in new_pal.items()}),
        "BlockData": ("B", to_varints(grid)),
        "Offset": ("I", [0, 0, 0]),
    }
    nbt_write(a.uitvoer, "Schematic", ("C", out))
    print(f"ondergrond tot y={y0} weg; gebouw x[{mx}..{Mx}] y[{my}..{My}] z[{mz}..{Mz}]")
    print(f"resultaat: {nW} x {nH} x {nL}, {len(keep):,} blokken (was {sum(1 for v in ids if base[v] not in INVISIBLE):,})")


if __name__ == "__main__":
    main()
