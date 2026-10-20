"""Round 20: D8 (red / graphite) with its graphite half separated from the tile at small sizes.
D8a lighter steel graphite; D8b near-black tile behind a slightly lighter graphite; D8c neutral (unblued) graphite.
After building, VectorCraft renders each at 144 / 48 / 24 px and the graphite half's middle is measured against the tile."""
import json
import os
import sys
import time

from PIL import Image

import r14
from build import DIR, dmin, document, path
from linecheck import lum, ratio
from r14 import BASE, CYAN, GLOW, TILE_GLOW, across
from r19 import BEAM, RED
from sheet import render
from vc import VC

r14.OUT = OUT = DIR
DARK_TILE = {"kind": "radial", "stops": [{"offset": 0, "color": "#141838"}, {"offset": 1, "color": "#04050C"}], "start": [54, 54], "end": [54, 130]}
VARIANTS = {
    "D8a": dict(tile=TILE_GLOW, bottom=across([(0, "#E6EAF1"), (0.14, "#B4BCCD"), (0.55, "#737C94"), (1, "#3C4358")], "bottom"), label="lighter steel graphite"),
    "D8b": dict(tile=DARK_TILE, bottom=across([(0, "#DCE0E8"), (0.14, "#9CA3B6"), (0.55, "#596075"), (1, "#2A2F40")], "bottom"), label="near-black tile, lighter graphite"),
    "D8c": dict(tile=TILE_GLOW, bottom=across([(0, "#E6E6E8"), (0.14, "#AEAEB4"), (0.55, "#6C6C74"), (1, "#36363C")], "bottom"), label="neutral graphite"),
}


def hexpx(p):
    return "#%02X%02X%02X" % p[:3]


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    vc = VC()
    for label, spec in VARIANTS.items():
        name = f"u3-{label}"
        doc, _ = document(dict(BASE, marks=CYAN, file=name))
        doc["document"]["layers"][0]["kind"]["children"] += [path(i, [(a, 54), (b, 54)], False, stroke=CYAN, width=w) for i, a, b, w, _ in BEAM]
        json.dump(doc, open(f"{OUT}{name}.vectorcraft", "w", encoding="utf-8"), indent=1)
        if f"{name}.vectorcraft" not in [d["title"] for d in vc.documents()]:
            vc.raw("app.open", path=f"{OUT}{name}.vectorcraft")
            time.sleep(0.8)
        assert len(vc.activate(f"{name}.vectorcraft")["layers"][0]["children"]) == 10
        vc("paint.setFill", ids=[200], gradient=spec["tile"])
        vc("paint.setFill", ids=[201], gradient=RED)
        vc("paint.setFill", ids=[202], gradient=spec["bottom"])
        vc("effect.apply", effect="stylize.outerGlow", params=GLOW(CYAN, 1.5)[1], ids=[203, 204, 205, 206])
        for i, _, _, _, blur in BEAM:
            vc("effect.apply", effect="stylize.outerGlow", params=GLOW(CYAN, blur)[1], ids=[i])
        vc("file.save", path=f"{OUT}{name}.vectorcraft")
        vc.raw("app.export", path=f"{OUT}{label}.svg", format="svg")
        vc.raw("ui.render", path=f"{OUT}{label}-vc.png", scale=4)
        row = []
        for size in (144, 48, 24):
            png = f"{OUT}{label}-{size}.png"
            vc.raw("ui.render", path=png, scale=size / 108)
            im = Image.open(png).convert("RGB")
            k = im.width / 108
            half = im.getpixel((int(50 * k), int(64 * k)))   # middle of the graphite half
            tile = im.getpixel((int(50 * k), int(84 * k)))   # tile just below it
            row.append(f"{size}px {ratio(lum(half), lum(tile)):.2f}:1 dE {dmin(hexpx(half), hexpx(tile))}")
        print(label, spec["label"], "| graphite vs tile:", " | ".join(row))
    render(OUT, [(k, "#0E1030") for k in VARIANTS], extra=[])
    strip = Image.new("RGB", (470, 20 + len(VARIANTS) * 170), "#0D1110")
    from PIL import ImageDraw
    dr = ImageDraw.Draw(strip)
    for n, label in enumerate(VARIANTS):
        y, x = 20 + n * 170, 60
        dr.text((10, y + 60), label, fill="#E8EBEE")
        for size in (144, 48, 24):
            strip.paste(Image.open(f"{OUT}{label}-{size}.png").convert("RGB"), (x, y))
            x += size + 30
    strip.save(OUT + "actual-size.png")
