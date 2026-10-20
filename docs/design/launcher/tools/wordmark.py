"""Wordmark drafts, built in VectorCraft: the mark (make_icon.py --marks) beside "ScanKnife+" set in the app's own font,
Plus Jakarta Sans, on the default palette's light and OLED backgrounds. The letters take the theme's text colour; only the "+"
is brand red. Usage: python wordmark.py [labels...]   (outputs in ../work/wordmark/)"""
import json
import os
import sys
import time

from vc import VC

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.normpath(os.path.join(HERE, "..", "work"))
OUT = os.path.join(WORK, "wordmark")
FONT = "Plus Jakarta Sans"
THEMES = {  # Still Water, the default palette: background, text, the "+" red, the beam cyan
    "light": dict(bg="#EAEEED", tx="#121414", plus="#C41E3A", cyan="#008A9E", mark="mark-light.svg"),
    "oled": dict(bg="#0D1110", tx="#E7ECEA", plus="#F04A66", cyan="#36E6FF", mark="mark-dark.svg"),
}
W, H = 640, 180
MARK_H = 116                      # the mark's drawn height; its art (crop-mark ends) is 61 x 80.468 units
ART_W, ART_H, ART_TOP = 61.0, 80.468, 13.766
MARK_X, MARK_Y = 28, (H - MARK_H) / 2
K = MARK_H / ART_H
BEAM_Y = MARK_Y + (54 - ART_TOP) * K   # the mark's beam, y 54 on the artboard
SIZE = 76                         # type size in pt; caps are about 0.7 em, so they centre on the beam with the baseline below
TEXT_X = MARK_X + ART_W * K + 24
BASELINE = BEAM_Y + 0.35 * SIZE

DIRECTIONS = {
    # label: (weights for "Scan", "Knife"; "+" style; tracking in 1/1000 em; beam through the letters)
    "W1": dict(scan="ExtraBold", knife="ExtraBold", plus="full", tracking=-20, beam=False, note="one weight, full-size +"),
    "W2": dict(scan="Medium", knife="ExtraBold", plus="full", tracking=-15, beam=False, note="Scan light, Knife heavy"),
    "W3": dict(scan="Medium", knife="ExtraBold", plus="raised", tracking=-15, beam=False, note="as W2, smaller raised +"),
    "W4": dict(scan="Medium", knife="ExtraBold", plus="full", tracking=-15, beam="all", note="as W2, the beam cuts the whole word at mid x-height"),
    "W5": dict(scan="Medium", knife="ExtraBold", plus="full", tracking=-15, beam="knife", note="as W2, the beam cuts only Knife, at mid x-height"),
}


def ids_of(result):
    if isinstance(result, dict):
        return result.get("ids") or ([result["id"]] if "id" in result else [])
    return []


def build(vc, label, theme_name):
    spec, theme = DIRECTIONS[label], THEMES[theme_name]
    name = f"{label}-{theme_name}"
    path = os.path.join(OUT, name + ".vectorcraft")
    vc("file.new", name=name, width=W, height=H, units="Points")
    time.sleep(0.5)
    bg = vc("shape.rectangle", x=0, y=0, width=W, height=H)["id"]
    vc("paint.setFill", ids=[bg], color=theme["bg"])
    vc("paint.setStroke", ids=[bg], none=True)
    before = {n["id"] for layer in vc.raw("document.inspect", depth=2)["layers"] for n in (layer.get("children") or [])}
    vc("file.place", path=os.path.join(WORK, theme["mark"]), link=False)
    tree = vc.raw("document.inspect", depth=2)
    placed = [n for layer in tree["layers"] for n in (layer.get("children") or []) if n["id"] not in before]
    assert placed, "the mark was not placed"
    mark = placed[0]
    b = mark["bounds"]  # geometric bounds: the crop marks' ends, strokes and glow left out
    sx = MARK_H / b["height"]
    vc("object.transform", ids=[mark["id"]], matrix=[sx, 0, 0, sx, MARK_X - b["x"] * sx, MARK_Y - b["y"] * sx], strokes=True)
    text = vc("text.create", x=TEXT_X, y=BASELINE, text="ScanKnife+", size=SIZE, font=FONT, style=spec["knife"], color=theme["tx"])["id"]
    vc("text.setRangeStyle", id=text, start=0, end=4, font=FONT, style=spec["scan"])
    plus = dict(fill=theme["plus"], font=FONT, style="ExtraBold")
    if spec["plus"] == "raised":
        plus.update(size=SIZE * 0.62, baselineShift=SIZE * 0.30)
    vc("text.setRangeStyle", id=text, start=9, end=10, **plus)
    vc("text.setStyle", ids=[text], tracking=spec["tracking"])
    vc("select.set", ids=[text])
    outlines = ids_of(vc("type.createOutlines"))
    if spec["beam"]:
        # A line at cap height reads as struck-through text; at the middle of the lowercase it reads as a cut. Measure the "n"
        # and move the word so its middle sits on the mark's beam.
        def glyphs():
            def find(nodes):
                for n in nodes:
                    if n["id"] in outlines:
                        return n
                    hit = find(n.get("children") or [])
                    if hit:
                        return hit
            word = find(vc.raw("document.inspect", depth=6)["layers"])
            kids = word.get("children") or [word]
            return sorted((k["bounds"] for k in kids), key=lambda b: b["x"])  # listed front to back; sort left to right
        g = glyphs()
        assert len(g) == 10, f"expected one outline group per glyph, got {len(g)}"
        n_box = g[3]  # the "n" of Scan
        vc("object.move", ids=outlines, dx=0, dy=BEAM_Y - (n_box["y"] + n_box["height"] / 2))
        g = glyphs()
        x0 = MARK_X + ART_W * K  # the beam leaves the mark at its right crop marks
        start = x0 if spec["beam"] == "all" else g[4]["x"] - 4
        right = g[8]["x"] + g[8]["width"] + 6
        if spec["beam"] == "knife":
            lead = vc("path.create", anchors=[{"x": x0, "y": BEAM_Y}, {"x": g[0]["x"] - 8, "y": BEAM_Y}])["id"]
            vc("paint.setFill", ids=[lead], none=True)
            vc("paint.setStroke", ids=[lead], color=theme["cyan"])
            vc("stroke.set", ids=[lead], weight=2.25 * K)
        gap = vc("path.create", anchors=[{"x": start, "y": BEAM_Y}, {"x": right, "y": BEAM_Y}])["id"]
        vc("paint.setFill", ids=[gap], none=True)
        vc("paint.setStroke", ids=[gap], color=theme["bg"])
        vc("stroke.set", ids=[gap], weight=2.25 * K * 2.2)
        line = vc("path.create", anchors=[{"x": start, "y": BEAM_Y}, {"x": right, "y": BEAM_Y}])["id"]
        vc("paint.setFill", ids=[line], none=True)
        vc("paint.setStroke", ids=[line], color=theme["cyan"])
        vc("stroke.set", ids=[line], weight=2.25 * K)
        if theme_name == "oled":
            vc("effect.apply", effect="stylize.outerGlow", params={"color": theme["cyan"], "blur": 3.0 * K, "opacity": 85, "mode": "screen"}, ids=[line])
    vc("file.save", path=path)
    vc.raw("app.export", path=os.path.join(OUT, name + ".svg"), format="svg")
    vc.raw("ui.render", path=os.path.join(OUT, name + ".png"), scale=1.5)
    print(name, spec["note"])


def banner(vc, theme_name):
    """The chosen lockup (W3) stacked, mark above the name, on a transparent ground: the README banner for GitHub's light or dark mode.
    Written to docs/design/launcher/wordmark-<light|dark>.svg."""
    spec, theme = DIRECTIONS["W3"], THEMES[theme_name]
    bw, bh, mark_h, size = 520, 250, 120, 64
    name = f"wordmark-{'dark' if theme_name == 'oled' else 'light'}"
    vc("file.new", name=name, width=bw, height=bh, units="Points")
    time.sleep(0.5)
    vc("file.place", path=os.path.join(WORK, theme["mark"]), link=False)
    mark = vc.raw("document.inspect", depth=2)["layers"][0]["children"][0]
    b = mark["bounds"]
    sx = mark_h / b["height"]
    vc("object.transform", ids=[mark["id"]], matrix=[sx, 0, 0, sx, (bw - b["width"] * sx) / 2 - b["x"] * sx, 14 - b["y"] * sx], strokes=True)
    text = vc("text.create", x=bw / 2, y=14 + mark_h + 22 + 0.72 * size, text="ScanKnife+", size=size, font=FONT, style=spec["knife"],
              color=theme["tx"], justify="center")["id"]
    vc("text.setRangeStyle", id=text, start=0, end=4, font=FONT, style=spec["scan"])
    vc("text.setRangeStyle", id=text, start=9, end=10, fill=theme["plus"], font=FONT, style="ExtraBold", size=size * 0.62, baselineShift=size * 0.30)
    vc("text.setStyle", ids=[text], tracking=spec["tracking"])
    vc("select.set", ids=[text])
    vc("type.createOutlines")
    vc("artboard.fitToArt")
    path = os.path.normpath(os.path.join(HERE, "..", name))
    vc("file.save", path=os.path.join(OUT, name + ".vectorcraft"))
    vc.raw("app.export", path=path + ".svg", format="svg")
    vc.raw("ui.render", path=os.path.join(OUT, name + ".png"), scale=1.5)
    print("wrote", path + ".svg")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    vc = VC()
    if sys.argv[1:2] == ["banner"]:
        # The README's light banner takes GitHub's light page; the plus and mark use the light theme's colours, the dark one the OLED's.
        for theme_name in THEMES:
            banner(vc, theme_name)
        sys.exit()
    for label in sys.argv[1:] or DIRECTIONS:
        for theme_name in THEMES:
            build(vc, label, theme_name)
    json.dump({k: v["note"] for k, v in DIRECTIONS.items()}, open(os.path.join(OUT, "notes.json"), "w", encoding="utf-8"), indent=1)
