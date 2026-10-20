"""Judging sheets from VectorCraft's own SVG exports: each icon at 192 px under the circle, squircle and rounded masks (the whole
108 canvas is the visible icon), at 48 and 24 px, its single-colour layer, and an overview on light and dark home screens."""
import math
import os
import re
import subprocess

INKSCAPE = os.environ.get("INKSCAPE", "C:/Program Files/Inkscape/bin/inkscape.exe")


def inner(path):
    s = open(path, encoding="utf-8").read()
    s = re.sub(r"<\?xml.*?\?>|<!DOCTYPE[^>]*>|<title>.*?</title>", "", s, flags=re.S)
    return re.sub(r"^\s*<svg[^>]*>|</svg>\s*$", "", s.strip(), flags=re.S)


def squircle(r=54, n=4, steps=64):
    pts = []
    for k in range(steps):
        t = 2 * math.pi * k / steps
        c, s = math.cos(t), math.sin(t)
        pts.append(f"{54 + r * math.copysign(abs(c) ** (2 / n), c):.2f} {54 + r * math.copysign(abs(s) ** (2 / n), s):.2f}")
    return "M" + " L".join(pts) + " Z"


MASKS = {"circle": '<circle cx="54" cy="54" r="54"/>', "squircle": f'<path d="{squircle()}"/>', "rounded": '<rect width="108" height="108" rx="19.5"/>'}
DEFS = "<defs>" + "".join(f'<clipPath id="m-{m}">{s}</clipPath>' for m, s in MASKS.items()) + "</defs>"


def namespace(body, label):
    """Prefix every id (gradients, filters) so icons composed on one sheet don't borrow each other's definitions."""
    p = "i" + re.sub(r"\W", "", label) + "-"
    body = re.sub(r'\bid="([^"]+)"', lambda m: f'id="{p}{m.group(1)}"', body)
    return re.sub(r'(url\(#|href="#)([^)"]+)', lambda m: f"{m.group(1)}{p}{m.group(2)}", body)


def mono(body, tile):
    """The single-colour layer as it would be drawn by hand: the tile removed, everything else in one colour, no effects."""
    body = re.sub(r'<path d="M0 0 L108 0 L108 108 L0 108 Z"[^>]*/>', "", body, count=1)
    body = re.sub(r'<(path|rect)[^>]*fill="' + re.escape(tile) + r'"[^>]*/>', "", body, count=1, flags=re.I)
    body = re.sub(r'\sfilter="[^"]*"', "", body)
    body = re.sub(r'fill="url\([^)]*\)"', 'fill="#1B1B1B"', body)
    body = re.sub(r'stroke="#[0-9A-Fa-f]{6}"', 'stroke="#1B1B1B"', body)
    return re.sub(r'fill="#[0-9A-Fa-f]{6}"', 'fill="#1B1B1B"', body)


def cell(body, x, y, size, clip=None, bg=None):
    g = f'<g clip-path="url(#m-{clip})">{body}</g>' if clip else body
    back = f'<rect width="108" height="108" fill="{bg}"/>' if bg else ""
    return f'<g transform="translate({x},{y}) scale({size / 108})">{back}{g}</g>'


def detail(label, body, tile):
    parts = [f'<text x="20" y="24" font-family="sans-serif" font-size="14" fill="#23263A">Icon {label}: 192 px, circle, squircle, rounded</text>', cell(body, 20, 36, 192)]
    parts += [cell(body, 232 + i * 212, 36, 192, clip=m) for i, m in enumerate(MASKS)]
    y = 256
    parts.append(f'<text x="20" y="{y - 8}" font-family="sans-serif" font-size="14" fill="#23263A">48 and 24 px: canvas, circle, squircle, rounded; single colour 96, 48, 24 px</text>')
    for i, m in enumerate([None] + list(MASKS)):
        parts += [cell(body, 20 + i * 70, y, 48, clip=m), cell(body, 32 + i * 70, y + 60, 24, clip=m)]
    mb = mono(body, tile)
    parts += [cell(mb, off, y + (96 - size) // 2, size, bg="#D9E2DC") for size, off in ((96, 340), (48, 450), (24, 510))]
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="1100" height="{y + 110}" viewBox="0 0 1100 {y + 110}"><rect width="1100" height="{y + 110}" fill="#FFFFFF"/>{DEFS}{"".join(parts)}</svg>'


def overview(items):
    w, h = 40 + len(items) * 230, 470
    parts = []
    for i, (label, body) in enumerate(items):
        x = 20 + i * 230
        parts += [f'<text x="{x}" y="24" font-family="sans-serif" font-size="15" font-weight="600" fill="#23263A">{label}</text>',
                  f'<rect x="{x - 6}" y="34" width="216" height="200" fill="#EAEEED"/>', cell(body, x, 38, 192, clip="squircle"),
                  f'<rect x="{x - 6}" y="244" width="216" height="210" fill="#0D1110"/>', cell(body, x, 250, 120, clip="squircle"),
                  cell(body, x + 130, 250, 48, clip="squircle"), cell(body, x + 140, 310, 24, clip="squircle")]
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}"><rect width="{w}" height="{h}" fill="#FFFFFF"/>{DEFS}{"".join(parts)}</svg>'


def render(out, icons, extra=()):
    """icons: [(label, tile hex)] whose SVGs are out/<label>.svg; extra: [(label, svg path)] shown in the overview only."""
    actions, items = [], []
    for label, tile in icons:
        body = namespace(inner(f"{out}{label}.svg"), label)
        open(f"{out}sheet-{label}.svg", "w", encoding="utf-8").write(detail(label, body, tile))
        actions.append(f"file-open:{out}sheet-{label}.svg; export-filename:{out}sheet-{label}.png; export-dpi:96; export-do; file-close")
        items.append((label, body))
    items += [(label, namespace(inner(p), label)) for label, p in extra]
    open(out + "overview.svg", "w", encoding="utf-8").write(overview(items))
    actions.append(f"file-open:{out}overview.svg; export-filename:{out}overview.png; export-dpi:96; export-do; file-close")
    subprocess.run([INKSCAPE, "--shell"], input="\n".join(actions) + "\nquit\n", text=True, capture_output=True, check=True)
