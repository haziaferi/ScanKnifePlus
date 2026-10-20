"""Round 14: bold directions for the user to react to (no judges). Same dart (mirrored, touching) and crop marks; VectorCraft paints
the gradients and glows. Ids in each document: 200 tile, 201 top half, 202 bottom half, 203-206 marks, 207 scan line (optional)."""
import json
import os
import time

from build import DIR, document, path
from sheet import render
from vc import VC

OUT = DIR + "r14/"
SEAM_X = 56  # gradients across the halves run along x = 56, from the seam out to each tail


def lin(stops, start, end):
    return {"kind": "linear", "stops": [{"offset": o, "color": c} for o, c in stops], "start": start, "end": end}


def across(stops, half):
    """A gradient from the seam (offset 0) out to the half's tail (offset 1)."""
    return lin(stops, [SEAM_X, 54], [SEAM_X, 29 if half == "top" else 79])


def along(stops):
    """A gradient from the dart's point (offset 0) back to its tail (offset 1)."""
    return lin(stops, [78.3, 54], [34.3, 54])


TILE_GLOW = {"kind": "radial", "stops": [{"offset": 0, "color": "#1C2260"}, {"offset": 1, "color": "#080A1E"}], "start": [54, 54], "end": [54, 130]}
CYAN = "#36E6FF"
EDGE_RED = across([(0, "#FF6B81"), (0.5, "#F2294F"), (1, "#9C1236")], "top")
EDGE_BLUE = across([(0, "#8FD0FF"), (0.5, "#2D7BFF"), (1, "#13308F")], "bottom")
GLOW = lambda colour, blur: ("stylize.outerGlow", {"color": colour, "blur": blur, "opacity": 85, "mode": "screen"})

VARIANTS = {
    # Combined: emissive tile, blade gradients, glowing cyan scan line along the seam, glowing marks.
    "C1": dict(marks=CYAN, mark_glow=GLOW(CYAN, 1.5), line=dict(color="#C9FAFF", width=1.25, glow=GLOW(CYAN, 2.5)),
               top=EDGE_RED, bottom=EDGE_BLUE, label="Combined: cyan line and cyan marks, both glowing"),
    "C2": dict(marks="#E8F4FF", line=dict(color="#C9FAFF", width=1.0, glow=GLOW(CYAN, 2.0)),
               top=EDGE_RED, bottom=EDGE_BLUE, label="Combined: cyan line, plain white marks"),
    # Blade variations, no line.
    "B1": dict(marks=CYAN, top=EDGE_RED, bottom=EDGE_BLUE, label="Blade: the seam lit as the edge; cyan marks"),
    "B2": dict(marks_top="#FF5C76", marks_bottom="#5A96FF",
               top=across([(0, "#FF8095"), (1, "#E3264A")], "top"), bottom=across([(0, "#2A5FE0"), (1, "#0D1F66")], "bottom"),
               label="Blade: two facets lit from above; marks in the halves' colours"),
    "B3": dict(marks="#E8F4FF", top=across([(0, "#FFFFFF"), (0.12, "#FF8A9C"), (0.55, "#E0284C"), (1, "#7E0E2C")], "top"),
               bottom=across([(0, "#FFFFFF"), (0.12, "#9CC9FF"), (0.55, "#2D6BF0"), (1, "#0F2878")], "bottom"),
               label="Blade: a white steel edge along the seam; white marks"),
    "B4": dict(marks=CYAN, mark_glow=GLOW(CYAN, 1.2), top=along([(0, "#FF4D6A"), (1, "#5E0C25")]), bottom=along([(0, "#4FB2FF"), (1, "#0E1E63")]),
               label="Blade: the point catching the light; glowing cyan marks"),
}
BASE = dict(box_w=47.0, arm=7.0, mark_w=1.75, tile="#0E1030", top="#F2294F", bottom="#2D7BFF")

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    vc = VC()
    for label, spec in VARIANTS.items():
        name = f"u3-{label}"
        doc, _ = document(dict(BASE, marks=spec.get("marks", "#FFFFFF"), file=name))
        if "line" in spec:  # the scan line runs between the marks' outer ends, exactly on the seam
            doc["document"]["layers"][0]["kind"]["children"].append(path(207, [(23.5, 54), (84.5, 54)], False, stroke=spec["line"]["color"], width=spec["line"]["width"]))
        json.dump(doc, open(f"{OUT}{name}.vectorcraft", "w", encoding="utf-8"), indent=1)
        if f"{name}.vectorcraft" not in [d["title"] for d in vc.documents()]:
            vc.raw("app.open", path=f"{OUT}{name}.vectorcraft")
            time.sleep(0.8)
        tree = vc.activate(f"{name}.vectorcraft")
        assert len(tree["layers"][0]["children"]) == (8 if "line" in spec else 7)
        vc("paint.setFill", ids=[200], gradient=TILE_GLOW)
        vc("paint.setFill", ids=[201], gradient=spec["top"])
        vc("paint.setFill", ids=[202], gradient=spec["bottom"])
        if "marks_top" in spec:
            vc("paint.setStroke", ids=[203, 204], color=spec["marks_top"])
            vc("paint.setStroke", ids=[205, 206], color=spec["marks_bottom"])
        if "mark_glow" in spec:
            vc("effect.apply", effect=spec["mark_glow"][0], params=spec["mark_glow"][1], ids=[203, 204, 205, 206])
        if "line" in spec:
            vc("effect.apply", effect=spec["line"]["glow"][0], params=spec["line"]["glow"][1], ids=[207])
        vc("file.save", path=f"{OUT}{name}.vectorcraft")
        vc.raw("app.export", path=f"{OUT}{label}.svg", format="svg")
        vc.raw("ui.render", path=f"{OUT}{label}-vc.png", scale=4)
        print(label, spec["label"])
    render(OUT, [(k, "#0E1030") for k in VARIANTS], extra=[("T (round 13)", DIR + "r13/T.svg")])
    json.dump({k: v["label"] for k, v in VARIANTS.items()}, open(OUT + "labels.json", "w", encoding="utf-8"), indent=1)
