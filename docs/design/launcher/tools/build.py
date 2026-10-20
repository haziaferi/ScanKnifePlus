"""Build Untitled-3 variants as .vectorcraft files, then let VectorCraft open, check, save, export (SVG) and render (PNG) each one.

Fixed by the designer: two identical triangles, mirrored top to bottom, touching along y = 54; four crop marks round an A4 box.
Only colours, sizes and positions vary. Usage: python build.py <round dir> (variants defined in VARIANTS below)."""
import json
import os
import math
import sys
import time

# designkit (colour maths) comes with the design-optimization Claude Code plugin; point DESIGNKIT at its engine folder.
sys.path.insert(0, os.environ.get("DESIGNKIT", os.path.expanduser("~/.claude/plugins/cache/c-code-plugins/design-optimization/2.3.2/engine")))
from designkit import colorspace as cs
from vc import VC

DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "work") + "/"
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "launcher-icon.vectorcraft")  # only its document wrapper is used
CVD = ("deuteranopia", "protanopia", "tritanopia")


def dmin(a, b):
    return round(min([cs.delta_e_hex(a, b)] + [cs.delta_e_hex(cs.simulate_cvd(a, k), cs.simulate_cvd(b, k)) for k in CVD]), 1)


def rgb(h):
    h = h.lstrip("#")
    return {"model": "rgb", "r": int(h[0:2], 16) / 255, "g": int(h[2:4], 16) / 255, "b": int(h[4:6], 16) / 255}


def path(i, pts, closed, fill=None, stroke=None, width=1.0):
    items = [{"kind": "fill", "paint": {"type": "solid", "color": rgb(fill)} if fill else {"type": "none"}}]
    if stroke:
        items.append({"kind": "stroke", "paint": {"type": "solid", "color": rgb(stroke)}, "width": width, "cap": "Butt", "join": "Miter"})
    return {"id": i, "appearance": {"items": items},
            "kind": {"type": "path", "path": {"subpaths": [{"anchors": [{"p": list(p)} for p in pts], "closed": closed}]}}}


def document(v):
    s = v.get("scale", 1.0)
    top = [(54 + (x - 54) * s, 54 + (y - 54) * s) for x, y in ((47, 54), (32, 29), (76, 54))]
    cx = sum(x for x, _ in top) / 3  # centre the dart by its area
    top = [(x + 54 - cx, y) for x, y in top]
    bot = [(x, 108 - y) for x, y in top]
    w = v["box_w"]
    h = w * 2 ** 0.5
    x0, x1, y0, y1, arm = 54 - w / 2, 54 + w / 2, 54 - h / 2, 54 + h / 2, v["arm"]
    corners = ((x0, y0, -1, -1), (x1, y0, 1, -1), (x1, y1, 1, 1), (x0, y1, -1, 1))
    nodes = [path(200, [(0, 0), (108, 0), (108, 108), (0, 108)], True, fill=v["tile"]),
             path(201, top, True, v["top"]), path(202, [bot[0], bot[2], bot[1]], True, v["bottom"])]
    nodes += [path(203 + i, [(cx_ + sx * arm, cy_), (cx_, cy_), (cx_, cy_ + sy * arm)], False, stroke=v["marks"], width=v["mark_w"])
              for i, (cx_, cy_, sx, sy) in enumerate(corners)]
    doc = json.load(open(SRC, encoding="utf-8"))
    doc["document"]["title"] = v["file"]
    doc["document"]["layers"][0]["kind"]["children"] = nodes
    reach = max(math.hypot(px - 54, py - 54) for cx_, cy_, sx, sy in corners for px, py in ((cx_ + sx * arm, cy_), (cx_, cy_ + sy * arm)))
    clear = min(math.dist(p, (c[0], c[1])) for p in top + bot for c in corners)
    return doc, dict(clearance=round(clear, 1), reach=round(reach + v["mark_w"] / 2, 1))


def checks(v):
    t = v["tile"]
    return {"top/tile": dmin(v["top"], t), "bottom/tile": dmin(v["bottom"], t), "top/bottom": dmin(v["top"], v["bottom"]),
            "L top/bottom": (round(cs.hex_to_lab(v["top"])[0]), round(cs.hex_to_lab(v["bottom"])[0])),
            "marks:tile": round(cs.contrast_ratio(v["marks"], t), 2), "marks vs red": round(cs.contrast_ratio(v["marks"], t) / cs.contrast_ratio(v["top"], t), 2),
            "tile vs walls": (dmin(t, "#F4F4F4"), dmin(t, "#121212")), "mark px at 48": round(v["mark_w"] * 48 / 108, 2)}


def build(out, variants):
    vc = VC()
    for label, v in variants.items():
        doc, geo = document(v)
        name = v["file"] + ".vectorcraft"
        json.dump(doc, open(out + name, "w", encoding="utf-8"), indent=2)
        open_titles = [d["title"] for d in vc.documents()]
        if name not in open_titles:
            vc.raw("app.open", path=out + name)
            time.sleep(0.8)
        tree = vc.activate(name)
        n = sum(len(layer.get("children") or []) for layer in tree["layers"])
        assert n == 7, f"{name}: VectorCraft sees {n} objects, expected 7"
        vc.raw("app.export", path=f"{out}{label}.svg", format="svg")
        vc.raw("ui.render", path=f"{out}{label}-vc.png", scale=4)
        print(label, name, geo, checks(v))
