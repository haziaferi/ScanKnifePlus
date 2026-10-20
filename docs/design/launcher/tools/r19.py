"""Round 19: D5 tuned per the round-18 judges, red kept (the forked app's recall), every colour with a job:
red = the forked app / PDF, the lower half = the digital page, cyan = the scanner's light (beam and marks) and nothing else.
- Beam in three segments on the seam: outside the dart full glow; inside the dart a slightly thicker core with half the glow,
  so it slices through without bunching into a blob at 48 px.
- The blade's edge tints toward its own half (pink-white, ice-blue or grey-white) instead of cyan or pure white,
  so the beam is the only cyan inside the dart and no white band reads as a flag.
D7: red / electric blue. D8: red / graphite, a nod to the forked app's dark lower half without copying it."""
import json
import os
import time

import r14
from build import DIR, document, path
from r14 import BASE, CYAN, GLOW, TILE_GLOW, across
from sheet import render
from vc import VC

r14.OUT = OUT = DIR + "r19/"
NOTCH, TIP = 49.333, 78.333
RED = across([(0, "#FFC2CC"), (0.14, "#FF6B81"), (0.55, "#E0284C"), (1, "#7E0E2C")], "top")
VARIANTS = {
    "D7": dict(bottom=across([(0, "#C2DCFF"), (0.14, "#5C9BFF"), (0.55, "#2D6BF0"), (1, "#0F2878")], "bottom"), label="red / electric blue"),
    "D8": dict(bottom=across([(0, "#D5DAE4"), (0.14, "#8A92A8"), (0.55, "#454C62"), (1, "#1C2030")], "bottom"), label="red / graphite"),
}
# (id, from x, to x, core width, glow blur)
BEAM = [(207, 23.5, NOTCH, 1.5, 3.0), (208, NOTCH, TIP, 1.75, 1.5), (209, TIP, 84.5, 1.5, 3.0)]

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    vc = VC()
    for label, spec in VARIANTS.items():
        name = f"u3-{label}"
        doc, _ = document(dict(BASE, marks=CYAN, file=name))
        kids = doc["document"]["layers"][0]["kind"]["children"]
        kids += [path(i, [(a, 54), (b, 54)], False, stroke=CYAN, width=w) for i, a, b, w, _ in BEAM]
        json.dump(doc, open(f"{OUT}{name}.vectorcraft", "w", encoding="utf-8"), indent=1)
        if f"{name}.vectorcraft" not in [d["title"] for d in vc.documents()]:
            vc.raw("app.open", path=f"{OUT}{name}.vectorcraft")
            time.sleep(0.8)
        tree = vc.activate(f"{name}.vectorcraft")
        assert len(tree["layers"][0]["children"]) == 10
        vc("paint.setFill", ids=[200], gradient=TILE_GLOW)
        vc("paint.setFill", ids=[201], gradient=RED)
        vc("paint.setFill", ids=[202], gradient=spec["bottom"])
        vc("effect.apply", effect="stylize.outerGlow", params=GLOW(CYAN, 1.5)[1], ids=[203, 204, 205, 206])
        for i, _, _, _, blur in BEAM:
            vc("effect.apply", effect="stylize.outerGlow", params=GLOW(CYAN, blur)[1], ids=[i])
        vc("file.save", path=f"{OUT}{name}.vectorcraft")
        vc.raw("app.export", path=f"{OUT}{label}.svg", format="svg")
        vc.raw("ui.render", path=f"{OUT}{label}-vc.png", scale=4)
        print(label, spec["label"])
    render(OUT, [(k, "#0E1030") for k in VARIANTS], extra=[("D5", DIR + "r17/D5.svg")])
