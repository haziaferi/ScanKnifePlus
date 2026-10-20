"""How the scan line reads at real sizes: VectorCraft renders each icon at 144 px (a 48 dp home-screen icon on a 3x phone), 48 and 24 px.
For each size, the brightest pixel of the line where it crosses empty tile (left of the dart, x = 28 of 108) is compared with the tile
4 units above it, as a contrast ratio. An outline-free 1-px line usually needs about 1.5:1 to be seen at all and 3:1 to read clearly."""
import os
import sys
import time

from PIL import Image

from vc import VC

DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "work") + "/"
ICONS = {"D1": "r15/u3-D1.vectorcraft", "D3": "r16/u3-D3.vectorcraft", "D4": "r16/u3-D4.vectorcraft", "D5": "r17/u3-D5.vectorcraft", "D6": "r17/u3-D6.vectorcraft", "B3": "r14/u3-B3.vectorcraft", "D7": "r19/u3-D7.vectorcraft", "D8": "r19/u3-D8.vectorcraft"}


def lum(px):
    def ch(c):
        c /= 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = px[:3]
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def ratio(a, b):
    hi, lo = max(a, b), min(a, b)
    return (hi + 0.05) / (lo + 0.05)


if __name__ == "__main__":
    vc = VC()
    out = DIR + "r16/linecheck/"
    import os
    os.makedirs(out, exist_ok=True)
    titles = [d["title"] for d in vc.documents()]
    for label, rel in (ICONS.items() if len(sys.argv) < 2 else [(a, ICONS[a]) for a in sys.argv[1:]]):
        name = rel.split("/")[-1]
        if name not in titles:
            vc.raw("app.open", path=DIR + rel)
            time.sleep(0.8)
        vc.activate(name)
        row = []
        for size in (144, 48, 24):
            png = f"{out}{label}-{size}.png"
            vc.raw("ui.render", path=png, scale=size / 108)
            im = Image.open(png).convert("RGB")
            k = im.width / 108
            x = int(28 * k)
            line = max(lum(im.getpixel((x, y))) for y in range(int(51 * k), int(57 * k) + 1))
            tile = lum(im.getpixel((x, int(50 * k) - 1)))
            row.append(f"{size}px {ratio(line, tile):.2f}:1")
        print(label, " | ".join(row))
