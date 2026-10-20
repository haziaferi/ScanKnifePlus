"""Writes the ScanKnife+ launcher icon's Android drawables from one description of the design (D8a, drawn in VectorCraft:
launcher-icon.vectorcraft / launcher-icon.svg here), plus an SVG twin of the full artboard for checking against VectorCraft's render.

The whole 108-unit artboard is the visible icon. In the adaptive icon it is scaled by 72/108 about the centre, into the safe zone.
Android vector drawables have gradients but no blur, so each VectorCraft glow (a Gaussian blur of the line's shape, in cyan at 85%)
is rebuilt from its own profile: a gradient band across the beam, and stacked soft strokes under each crop mark.

Usage: python make_icon.py [twin.svg]   (run from anywhere; it writes into app/src/main/res)"""
import math
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")

CYAN = "36E6FF"
GLOW_OPACITY = 0.85
TILE = dict(center=(54, 54), radius=76, stops=[(0, "1C2260"), (1, "080A1E")])
NOTCH, TIP, TAIL_X, TOP_Y, BOTTOM_Y = 49.333, 78.333, 34.333, 29, 79
RED = [(0, "FFC2CC"), (0.14, "FF6B81"), (0.55, "E0284C"), (1, "7E0E2C")]
STEEL = [(0, "E6EAF1"), (0.14, "B4BCCD"), (0.55, "737C94"), (1, "3C4358")]
MARK_W, MARKS = 1.75, [  # crop marks round the A4 box 47 x 66.47, arms 7; the corner point is the middle anchor
    [(23.5, 20.766), (30.5, 20.766), (30.5, 13.766)], [(84.5, 20.766), (77.5, 20.766), (77.5, 13.766)],
    [(84.5, 87.234), (77.5, 87.234), (77.5, 94.234)], [(23.5, 87.234), (30.5, 87.234), (30.5, 94.234)]]
MARK_SIGMA = 0.75
MARK_GLOW_CAP = "butt"  # matched VectorCraft best (butt, square and round measured)
# The scan beam on the seam: (from x, to x, core width, glow sigma); the glow is halved inside the dart. The core is 2.25 wide,
# exactly 1 px at 48 px: thinner, Android's gamma-space smoothing left it at 1.8:1 there.
BEAM = [(23.5, NOTCH, 2.25, 1.5), (NOTCH, TIP, 2.25, 0.75), (TIP, 84.5, 2.25, 1.5)]


def phi(x):
    return 0.5 * (1 + math.erf(x / math.sqrt(2)))


def glow_alpha(d, width, sigma):
    """Opacity of a blurred line's glow at distance d from its centre line (the blur of a band `width` wide)."""
    return GLOW_OPACITY * (phi((d + width / 2) / sigma) - phi((d - width / 2) / sigma))


def argb(rgb, a=1.0):
    return f"#{round(a * 255):02X}{rgb}"


def n(v):
    return f"{v:.3f}".rstrip("0").rstrip(".")


def poly(points, closed=True):
    return "M" + " L".join(f"{n(x)},{n(y)}" for x, y in points) + (" Z" if closed else "")


# ---- the design as a list of primitives, shared by the drawable and the SVG writers -------------------------------------------

def beam_band(x0, x1, width, sigma):
    """The glow along a beam segment: a band filled by a gradient across it that follows the blur's profile."""
    reach = width / 2 + 3 * sigma
    stops = [(k / 12, CYAN, glow_alpha(-reach + 2 * reach * k / 12, width, sigma)) for k in range(13)]
    shape = [(x0, 54 - reach), (x1, 54 - reach), (x1, 54 + reach), (x0, 54 + reach)]
    return dict(kind="fill", d=poly(shape), gradient=dict(type="linear", start=(x0, 54 - reach), end=(x0, 54 + reach), stops=stops))


def beam_end(x, width, sigma, side):
    """Past the beam's outer end the glow fades round it: a half disc with a radial gradient of the same profile, starting at the
    glow's opacity on the centre line and scaled by how far the blur reaches past a butt end."""
    reach = width / 2 + 3 * sigma
    stops = [(k / 12, CYAN, glow_alpha(0, width, sigma) * (1 - phi(k / 12 * reach / sigma - 0.0)) * 2) for k in range(13)]
    sweep = 1 if side > 0 else 0
    shape = f"M{n(x)},{n(54 - reach)} A{n(reach)},{n(reach)} 0 0 {sweep} {n(x)},{n(54 + reach)} Z"
    return dict(kind="fill", d=shape, gradient=dict(type="radial", center=(x, 54), radius=reach, stops=stops))


def mark_glow(points):
    """Stacked round-capped strokes, widest first, whose combined opacity follows the blur's profile ring by ring."""
    rings = [MARK_W / 2 + MARK_SIGMA * k for k in (3.0, 2.25, 1.5, 0.9, 0.4)]  # outer half-widths, outermost first
    layers, covered = [], 1.0  # covered: the transparency left by the layers already placed (outer ones)
    for i, half in enumerate(rings):
        inner = rings[i + 1] if i + 1 < len(rings) else MARK_W / 2
        target = glow_alpha((half + inner) / 2, MARK_W, MARK_SIGMA)
        alpha = max(0.0, 1 - (1 - target) / covered)
        covered *= 1 - alpha
        layers.append(dict(kind="stroke", d=poly(points, closed=False), color=CYAN, alpha=alpha, width=2 * half, cap=MARK_GLOW_CAP, join="round"))
    return layers


# On light surfaces (the app's light themes, about #E2E2E2 to #ECEDF0) the icon's pale highlights and glowing cyan would vanish:
# the mark there uses a deeper cyan of the same hue (>= 3:1 on every light background), darker halves and no glow.
LIGHT = dict(cyan="008A9E", red=[(0, "F04A66"), (0.55, "D4213F"), (1, "7E0E2C")], steel=[(0, "7D869B"), (0.55, "4E566C"), (1, "2E3446")], glow=False)
DARK = dict(cyan=CYAN, red=RED, steel=STEEL, glow=True)


def colour_art(theme=DARK):
    cyan = theme["cyan"]
    art = [dict(kind="fill", d=poly([(NOTCH, 54), (TAIL_X, TOP_Y), (TIP, 54)]), gradient=dict(type="linear", start=(56, 54), end=(56, TOP_Y), stops=[(o, c, 1) for o, c in theme["red"]])),
           dict(kind="fill", d=poly([(NOTCH, 54), (TIP, 54), (TAIL_X, BOTTOM_Y)]), gradient=dict(type="linear", start=(56, 54), end=(56, BOTTOM_Y), stops=[(o, c, 1) for o, c in theme["steel"]]))]
    for m in MARKS:
        if theme["glow"]:
            art += mark_glow(m)
        art.append(dict(kind="stroke", d=poly(m, closed=False), color=cyan, alpha=1, width=MARK_W, cap="butt", join="miter"))
    if theme["glow"]:
        for x0, x1, w, s in BEAM:
            art.append(beam_band(x0, x1, w, s))
        art.append(beam_end(BEAM[0][0], BEAM[0][2], BEAM[0][3], -1))
        art.append(beam_end(BEAM[-1][1], BEAM[-1][2], BEAM[-1][3], 1))
    for x0, x1, w, s in BEAM:
        art.append(dict(kind="stroke", d=poly([(x0, 54), (x1, 54)], closed=False), color=cyan, alpha=1, width=w, cap="butt", join="miter"))
    return art


def tile(radius_scale=1.0):
    cx, cy = TILE["center"]
    return dict(kind="fill", d="M0,0 L108,0 L108,108 L0,108 Z",
                gradient=dict(type="radial", center=(cx, cy), radius=TILE["radius"] * radius_scale, stops=[(o, c, 1) for o, c in TILE["stops"]]))


def mono_art():
    """Themed icons are one colour: the beam can't be drawn over the dart, so inside it the beam is cut out of the dart."""
    gap = BEAM[1][2] / 2  # half the beam: the cut is as wide as the beam
    k = gap / (54 - TOP_Y)  # how far along each edge the cut reaches
    top = [(NOTCH - (NOTCH - TAIL_X) * k, 54 - gap), (TAIL_X, TOP_Y), (TIP - (TIP - TAIL_X) * k, 54 - gap)]
    bottom = [(x, 108 - y) for x, y in top]
    art = [dict(kind="fill", d=poly(top), color="FFFFFF", alpha=1), dict(kind="fill", d=poly([bottom[0], bottom[2], bottom[1]]), color="FFFFFF", alpha=1)]
    art += [dict(kind="stroke", d=poly(m, closed=False), color="FFFFFF", alpha=1, width=MARK_W, cap="butt", join="miter") for m in MARKS]
    art += [dict(kind="stroke", d=poly([(a, 54), (b, 54)], closed=False), color="FFFFFF", alpha=1, width=BEAM[0][2], cap="butt", join="miter")
            for a, b in ((23.5, top[0][0]), (top[2][0], 84.5))]
    return art


# ---- writers ------------------------------------------------------------------------------------------------------------------

HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by docs/design/launcher/make_icon.py; edit that, not this. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"{aapt}\n    android:width="{w}dp"\n    android:height="{h}dp"\n'
        '    android:viewportWidth="{w}"\n    android:viewportHeight="{h}">\n')


def vd_gradient(g, attr, pad):
    if g["type"] == "linear":
        geo = f'android:type="linear" android:startX="{n(g["start"][0])}" android:startY="{n(g["start"][1])}" android:endX="{n(g["end"][0])}" android:endY="{n(g["end"][1])}"'
    else:
        geo = f'android:type="radial" android:centerX="{n(g["center"][0])}" android:centerY="{n(g["center"][1])}" android:gradientRadius="{n(g["radius"])}"'
    items = "".join(f'{pad}            <item android:offset="{n(o)}" android:color="{argb(c, a)}" />\n' for o, c, a in g["stops"])
    return (f'{pad}    <aapt:attr name="{attr}">\n{pad}        <gradient {geo}>\n{items}{pad}        </gradient>\n{pad}    </aapt:attr>\n')


def vd_path(p, pad):
    if p["kind"] == "fill":
        if "gradient" in p:
            return f'{pad}<path android:pathData="{p["d"]}">\n{vd_gradient(p["gradient"], "android:fillColor", pad)}{pad}</path>\n'
        return f'{pad}<path android:pathData="{p["d"]}" android:fillColor="{argb(p["color"], p["alpha"])}" />\n'
    return (f'{pad}<path android:pathData="{p["d"]}" android:strokeColor="{argb(p["color"], p["alpha"])}" android:strokeWidth="{n(p["width"])}"'
            f' android:strokeLineCap="{p["cap"]}" android:strokeLineJoin="{p["join"]}" />\n')


def vector(art, scale=1.0, clip=None, view=None):
    """A vector drawable of the artboard, or (view = "x y w h") of that part of it, as the brand mark crops it."""
    aapt = "\n    xmlns:aapt=\"http://schemas.android.com/aapt\"" if any("gradient" in p for p in art) else ""
    x, y, w, h = (float(v) for v in view.split()) if view else (0, 0, 108, 108)
    out = HEAD.format(aapt=aapt, w=n(w), h=n(h))
    pad = "    "
    if scale != 1.0:
        out += f'    <group android:pivotX="54" android:pivotY="54" android:scaleX="{n(scale)}" android:scaleY="{n(scale)}">\n'
        pad = "        "
    elif view:
        out += f'    <group android:translateX="{n(-x)}" android:translateY="{n(-y)}">\n'
        pad = "        "
    if clip:
        out += f'{pad}<clip-path android:pathData="{clip}" />\n'
    out += "".join(vd_path(p, pad) for p in art)
    if scale != 1.0 or view:
        out += "    </group>\n"
    return out + "</vector>\n"


def svg(art, clip=None, view="0 0 108 108"):
    defs, body = [], []
    for i, p in enumerate(art):
        attrs = ""
        if "gradient" in p:
            g = p["gradient"]
            stops = "".join(f'<stop offset="{n(o)}" stop-color="#{c}" stop-opacity="{a:.4f}"/>' for o, c, a in g["stops"])
            if g["type"] == "linear":
                defs.append(f'<linearGradient id="g{i}" gradientUnits="userSpaceOnUse" x1="{n(g["start"][0])}" y1="{n(g["start"][1])}" x2="{n(g["end"][0])}" y2="{n(g["end"][1])}">{stops}</linearGradient>')
            else:
                defs.append(f'<radialGradient id="g{i}" gradientUnits="userSpaceOnUse" cx="{n(g["center"][0])}" cy="{n(g["center"][1])}" r="{n(g["radius"])}">{stops}</radialGradient>')
            attrs = f'fill="url(#g{i})"'
        elif p["kind"] == "fill":
            attrs = f'fill="#{p["color"]}" fill-opacity="{p["alpha"]:.4f}"'
        else:
            attrs = (f'fill="none" stroke="#{p["color"]}" stroke-opacity="{p["alpha"]:.4f}" stroke-width="{n(p["width"])}"'
                     f' stroke-linecap="{p["cap"]}" stroke-linejoin="{p["join"]}"')
        body.append(f'<path d="{p["d"].replace(",", " ")}" {attrs}/>')
    if clip:
        defs.append(f'<clipPath id="clip"><path d="{clip}"/></clipPath>')
    inner = "".join(body)
    if clip:
        inner = f'<g clip-path="url(#clip)">{inner}</g>'
    w, h = view.split()[2:]
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="{view}"><defs>{"".join(defs)}</defs>{inner}</svg>'


ADAPTIVE = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by docs/design/launcher/make_icon.py; edit that, not this. -->\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')
ROUNDED = "M19.5,0 L88.5,0 A19.5,19.5 0 0 1 108,19.5 L108,88.5 A19.5,19.5 0 0 1 88.5,108 L19.5,108 A19.5,19.5 0 0 1 0,88.5 L0,19.5 A19.5,19.5 0 0 1 19.5,0 Z"
CIRCLE = "M54,0 A54,54 0 1 1 54,108 A54,54 0 1 1 54,0 Z"
SAFE = 72 / 108

MARK_VIEW = "21 11 66 86"  # the mark without its tile: the crop marks' outer ends plus their glow


def write_marks(folder):
    """The mark without its tile, for the wordmark and the About screen: mark-dark.svg (the icon's colours and glow, for OLED)
    and mark-light.svg (deeper cyan, darker halves, no glow)."""
    for name, theme in (("dark", DARK), ("light", LIGHT)):
        with open(os.path.join(folder, f"mark-{name}.svg"), "w", encoding="utf-8") as f:
            f.write(svg(colour_art(theme), view=MARK_VIEW))
        print("wrote", f"mark-{name}.svg")


if __name__ == "__main__":
    if sys.argv[1:2] == ["--marks"]:
        write_marks(sys.argv[2])
        sys.exit()
    files = {
        "drawable/ic_launcher_background.xml": vector([tile(SAFE)]),
        "drawable/ic_launcher_foreground.xml": vector(colour_art(), scale=SAFE),
        "drawable/ic_launcher_monochrome.xml": vector(mono_art(), scale=SAFE),
        # Android 7.0 and 7.1 have no adaptive icons: the whole artboard, in a rounded square and (for round-icon launchers) a circle.
        "drawable/ic_launcher.xml": vector([tile()] + colour_art(), clip=ROUNDED),
        "drawable/ic_launcher_round.xml": vector([tile()] + colour_art(), clip=CIRCLE),
        "drawable-v26/ic_launcher.xml": ADAPTIVE,
        "drawable-v26/ic_launcher_round.xml": ADAPTIVE,
        # The mark without its tile, beside the wordmark (Home) and above it (About); the app picks one by its own theme.
        "drawable/brand_mark_light.xml": vector(colour_art(LIGHT), view=MARK_VIEW),
        "drawable/brand_mark_dark.xml": vector(colour_art(DARK), view=MARK_VIEW),
    }
    for rel, text in files.items():
        with open(os.path.join(RES, rel), "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
        print("wrote", rel)
    if len(sys.argv) > 1:  # the full artboard, as the legacy icon draws it but unclipped, for comparing with VectorCraft
        with open(sys.argv[1], "w", encoding="utf-8") as f:
            f.write(svg([tile()] + colour_art()))
        with open(sys.argv[1].replace(".svg", "-mono.svg"), "w", encoding="utf-8") as f:
            f.write(svg(mono_art()))
        print("wrote", sys.argv[1])
