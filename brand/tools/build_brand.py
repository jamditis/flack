#!/usr/bin/env python3
"""Build every Flack brand asset from one geometry source.

Writes only into brand/, site/ and app/src/main/res/drawable/. See brand/tools/README.md.

    python3 brand/tools/build_brand.py                 # fetch Manrope with npm, build all
    python3 brand/tools/build_brand.py --fonts DIR     # use local Manrope woff2 files
    python3 brand/tools/build_brand.py --no-png        # SVG and XML only (no Node needed)
"""
import argparse, hashlib, io, math, os, subprocess, sys, tarfile, tempfile

from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen
import uharfbuzz as hb

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ALLOWED = [os.path.join(ROOT, d) for d in ("brand", "site", os.path.join("app", "src", "main", "res", "drawable"))]

# Palette (docs/BRAND.md, app/src/main/res/values/colors.xml)
AMBER = "#FFB547"; AMBER_SOFT = "#FFDDB0"; NIGHT = "#0B1524"
INK = "#0E1A2B"; PAPER = "#F5F7FB"; SLATE = "#4A5B73"; FOG = "#A9B6CA"

# Mark geometry, in the 108-unit adaptive-icon canvas. Radii are to the road centre.
GEO = dict(X=49.3, Y=58.7, R1=14, R2=8.5, w=7.6)       # crossing, big loop, small loop, road
SMALL = dict(X=49.6, Y=58.4, R1=13.6, R2=9.6, w=7.0)   # small mark: 47 px and below
DASH = dict(dw=1.4, L=4, G=3.4)                         # lane lines: width, length, gap
EXT = 7.0                                               # highway past the loops on standalone marks

TAGLINE = "Your messages, riding shotgun."
DISCLAIMER = "Independent app. Not affiliated with Slack."
HEADER = "<!-- Flack brand asset. Generated from one geometry source; see docs/BRAND.md. -->\n"

# Manrope from the Fontsource npm package, pinned by version and file hash.
FONT_PACKAGE = "@fontsource/manrope@5.3.0"
FONT_FILES = {
    600: ("manrope-latin-600-normal.woff2", "f7ac6258da20ab7541939b59851155753d1d24f1b30cbcb949077a3faa3d1593"),
    800: ("manrope-latin-800-normal.woff2", "74c161dbcbce7c3590803cc72dc1f63313c360e13367aba5075a7b7b3818ea49"),
}
FONTS = {}


def f(v, p=2):
    s = f"{v:.{p}f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "", "-") else s


# ------------------------------------------------------------------------------------- mark
class Mark:
    """A highway with a cloverleaf loop pair, as one evenOdd path: an outer contour and two
    holes. The big loop's hole is a round speech bubble with its corner tail at the crossing."""

    def __init__(self, X, Y, R1, R2, w, xa, xb, p=2):
        self.X, self.Y, self.R1, self.R2, self.w = X, Y, R1, R2, w
        self.h = w / 2
        self.p = p
        self.xa, self.xb = xa, xb

    def F(self, v):
        return f(v, self.p)

    def outline(self):
        X, Y, R1, R2, h, F = self.X, self.Y, self.R1, self.R2, self.h, self.F
        xa, xb = self.xa, self.xb
        Ro1, Ri1, Ro2, Ri2 = R1 + h, R1 - h, R2 + h, R2 - h
        w1x = X + R1 + 2 * math.sqrt(R1 * h)   # big loop meets the highway's top edge
        w2x = X - R2 - 2 * math.sqrt(R2 * h)   # small loop meets the highway's bottom edge
        outer = (f"M{F(xa)},{F(Y - h)}H{F(X - h)}V{F(Y - R1)}"
                 f"A{F(Ro1)},{F(Ro1)} 0 1 1 {F(w1x)},{F(Y - h)}"
                 f"H{F(xb)}V{F(Y + h)}H{F(X + h)}V{F(Y + R2)}"
                 f"A{F(Ro2)},{F(Ro2)} 0 1 1 {F(w2x)},{F(Y + h)}"
                 f"H{F(xa)}Z")
        big_hole = (f"M{F(X + R1)},{F(Y - h)}A{F(Ri1)},{F(Ri1)} 0 1 0 {F(X + h)},{F(Y - R1)}"
                    f"V{F(Y - h)}Z")
        small_hole = (f"M{F(X - R2)},{F(Y + h)}A{F(Ri2)},{F(Ri2)} 0 1 0 {F(X - h)},{F(Y + R2)}"
                      f"V{F(Y + h)}Z")
        return outer + big_hole + small_hole

    def dashes(self, dw, L, G, hx=None):
        """Lane lines as filled shapes (VectorDrawable has no dasharray). Loop lines stop short of
        the merges; highway lines skip the crossing. hx limits the highway lines to [x0, x1].
        Also sets self.dash_groups (highway, big, small) so each can be its own short path."""
        X, Y, R1, R2, h, F = self.X, self.Y, self.R1, self.R2, self.h, self.F
        out, groups = [], {}

        def line_pieces(a, b, from_end):
            n = int((b - a + G) // (L + G))
            for i in range(max(n, 0)):
                if from_end:
                    x1 = b - i * (L + G); x0 = x1 - L
                else:
                    x0 = a + i * (L + G); x1 = x0 + L
                out.append(f"M{F(x0)},{F(Y - dw / 2)}H{F(x1)}V{F(Y + dw / 2)}H{F(x0)}Z")

        lo, hi = hx if hx else (self.xa, self.xb)
        gap = h + 1.6
        line_pieces(max(lo, self.xa) + 0.8, X - gap, True)
        line_pieces(X + gap, min(hi, self.xb) - 0.8, False)
        groups["highway"] = "".join(out); out.clear()
        for key, (cx, cy, R, a0, a1) in (("big", (X + R1, Y - R1, R1, -150, 52)),
                                         ("small", (X - R2, Y + R2, R2, 28, 228))):
            arc_len = math.radians(a1 - a0) * R
            n = max(1, int((arc_len + G) // (L + G)))
            total = n * L + (n - 1) * G
            start = math.radians(a0) + (arc_len - total) / 2 / R
            ro, ri = R + dw / 2, R - dw / 2

            def P(r, t):
                return f"{F(cx + r * math.cos(t))},{F(cy + r * math.sin(t))}"
            for i in range(n):
                ta = start + i * (L + G) / R
                tb = ta + L / R
                out.append(f"M{P(ro, ta)}A{F(ro)},{F(ro)} 0 0 1 {P(ro, tb)}L{P(ri, tb)}"
                           f"A{F(ri)},{F(ri)} 0 0 0 {P(ri, ta)}Z")
            groups[key] = "".join(out); out.clear()
        self.dash_groups = groups
        return "".join(groups.values())


def extents(g):
    h = g["w"] / 2
    return (g["X"] - 2 * g["R2"] - h, g["Y"] - 2 * g["R1"] - h, g["X"] + 2 * g["R1"] + h, g["Y"] + 2 * g["R2"] + h)


def mark(g=GEO, ext=EXT, xa=None, xb=None, p=2):
    L, T, R, B = extents(g)
    return Mark(xa=L - ext if xa is None else xa, xb=R + ext if xb is None else xb, p=p, **g)


# ------------------------------------------------------------------------------------- type
def load_fonts(font_dir):
    """Load Manrope 600 and 800. From font_dir if given, else from the pinned npm package."""
    blobs = {}
    if font_dir:
        for w, (name, _) in FONT_FILES.items():
            with open(os.path.join(font_dir, name), "rb") as fh:
                blobs[w] = fh.read()
    else:
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(["npm", "pack", FONT_PACKAGE, "--pack-destination", tmp, "--silent"],
                           check=True, stdout=subprocess.DEVNULL)
            tgz = [n for n in os.listdir(tmp) if n.endswith(".tgz")][0]
            with tarfile.open(os.path.join(tmp, tgz)) as tar:
                for w, (name, _) in FONT_FILES.items():
                    blobs[w] = tar.extractfile(f"package/files/{name}").read()
    for w, (name, digest) in FONT_FILES.items():
        if hashlib.sha256(blobs[w]).hexdigest() != digest:
            sys.exit(f"{name} does not match the pinned hash; expected {FONT_PACKAGE}.")
        font = TTFont(io.BytesIO(blobs[w]))
        font.flavor = None
        buf = io.BytesIO()
        font.save(buf)
        FONTS[w] = buf.getvalue()


class _RoundPen(SVGPathPen):
    def __init__(self, glyphset, p):
        super().__init__(glyphset, ntos=lambda v: (f"{v:.{p}f}".rstrip("0").rstrip(".") or "0"))


def outline(text, weight, size, tracking=0.0, p=2):
    """Outline text with HarfBuzz shaping (kerning). The cap line is y=0 and y grows down.
    Returns (path, bounds, cap height, advance). tracking is in em."""
    data = FONTS[weight]
    hbfont = hb.Font(hb.Face(hb.Blob(data)))
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(hbfont, buf, {})
    tt = TTFont(io.BytesIO(data))
    upm = tt["head"].unitsPerEm
    cap = tt["OS/2"].sCapHeight
    gs = tt.getGlyphSet()
    order = tt.getGlyphOrder()
    s = size / upm
    pen, bp = _RoundPen(gs, p), BoundsPen(gs)
    x = 0.0
    n = len(buf.glyph_infos)
    for i, (info, pos) in enumerate(zip(buf.glyph_infos, buf.glyph_positions)):
        name = order[info.codepoint]
        t = (s, 0, 0, -s, (x + pos.x_offset) * s, cap * s - pos.y_offset * s)
        gs[name].draw(TransformPen(pen, t))
        gs[name].draw(TransformPen(bp, t))
        x += pos.x_advance + (tracking * upm if i < n - 1 else 0)
    return pen.getCommands(), bp.bounds, cap * s, x * s


def word(size):
    """The wordmark: Manrope ExtraBold at -0.015 em."""
    return outline("Flack", 800, size, -0.015)


def text_path(text, size, color, x, y, weight, tracking=0.0):
    d, _, cap, adv = outline(text, weight, size, tracking)
    return f'<path fill="{color}" transform="translate({f(x)} {f(y)})" d="{d}"/>', adv, cap


def svg(viewbox, w, h, body, title=None, header=True):
    t = f'<title id="t">{title}</title>' if title else ""
    role = ' role="img" aria-labelledby="t"' if title else ""
    return ((HEADER if header else "") + f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{viewbox}" '
            f'width="{f(w)}" height="{f(h)}"{role}>{t}{body}</svg>\n')


# ------------------------------------------------------------------------------------ brand
def svg_mark(road, title, g=GEO, dashes=True, ext=EXT):
    """Lane lines are cut out of the road (evenOdd), so they show whatever is behind."""
    m = mark(g, ext)
    L, T, R, B = extents(g)
    d = m.outline() + (m.dashes(**DASH) if dashes else "")
    w, h = m.xb - m.xa, B - T
    return svg(f"{f(m.xa)} {f(T)} {f(w)} {f(h)}", w * 4, h * 4,
               f'<path fill="{road}" fill-rule="evenodd" d="{d}"/>', title)


def tile_body(uid, crop=18.0, rx=16.0):
    m = mark(xa=0, xb=108)
    size = 108 - 2 * crop
    d = m.outline() + m.dashes(hx=(crop, 108 - crop), **DASH)
    return (f'<clipPath id="tile{uid}"><rect x="{f(crop)}" y="{f(crop)}" width="{f(size)}" height="{f(size)}" rx="{f(rx)}"/></clipPath>'
            f'<g clip-path="url(#tile{uid})"><rect x="{f(crop)}" y="{f(crop)}" width="{f(size)}" height="{f(size)}" fill="{NIGHT}"/>'
            f'<path fill="{AMBER}" fill-rule="evenodd" d="{d}"/></g>')


def svg_icon():
    return svg("18 18 72 72", 512, 512, tile_body("i"), "Flack app icon")


def svg_wordmark(color, title):
    d, (x0, y0, x1, y1), cap, adv = word(100)
    return svg(f"{f(x0)} {f(y0)} {f(x1 - x0)} {f(y1 - y0)}", (x1 - x0) * 2, (y1 - y0) * 2,
               f'<path fill="{color}" d="{d}"/>', title)


def lockup(variant, mark_h):
    """Mark (or, on light backgrounds, the app tile) and wordmark. Returns (body, width, height)."""
    L, T, R, B = extents(GEO)
    cap_h = mark_h * 0.46
    d, (wx0, wy0, wx1, wy1), cap, adv = word(cap_h / 0.72)
    if variant == "dark":
        m = mark()
        s = mark_h / (B - T)
        mark_w = (m.xb - m.xa) * s
        body = (f'<g transform="translate({f(-m.xa * s)} {f(-T * s)}) scale({f(s, 5)})">'
                f'<path fill="{AMBER}" fill-rule="evenodd" d="{m.outline()}{m.dashes(**DASH)}"/></g>')
        wcol, box_h = PAPER, mark_h
    else:
        box_h = mark_h * 1.18
        mark_w = box_h
        body = f'<g transform="scale({f(box_h / 72, 5)}) translate(-18 -18)">' + tile_body("l") + '</g>'
        wcol = INK
    tx = mark_w + mark_h * 0.24 - wx0
    ty = box_h / 2 - cap / 2
    body += f'<path fill="{wcol}" transform="translate({f(tx)} {f(ty)})" d="{d}"/>'
    return body, tx + wx1, box_h


def svg_lockup(variant):
    body, w, h = lockup(variant, 100)
    return svg(f"0 0 {f(w)} {f(h)}", w * 2, h * 2, body, "Flack")


# ------------------------------------------------------------------------------ route lines
def route(points, r):
    """Orthogonal polyline with rounded corners of radius r."""
    out = [f"M{f(points[0][0])},{f(points[0][1])}"]
    for i in range(1, len(points) - 1):
        (x0, y0), (x1, y1), (x2, y2) = points[i - 1], points[i], points[i + 1]
        d0 = ((x1 - x0) / (abs(x1 - x0) + abs(y1 - y0)), (y1 - y0) / (abs(x1 - x0) + abs(y1 - y0)))
        d1 = ((x2 - x1) / (abs(x2 - x1) + abs(y2 - y1)), (y2 - y1) / (abs(x2 - x1) + abs(y2 - y1)))
        a = (x1 - d0[0] * r, y1 - d0[1] * r)
        b = (x1 + d1[0] * r, y1 + d1[1] * r)
        sweep = 1 if d0[0] * d1[1] - d0[1] * d1[0] > 0 else 0
        out.append(f"L{f(a[0])},{f(a[1])}A{f(r)},{f(r)} 0 0 {sweep} {f(b[0])},{f(b[1])}")
    out.append(f"L{f(points[-1][0])},{f(points[-1][1])}")
    return "".join(out)


def corridor(W, H, k, bg, x_left=None):
    """Two routes on concentric bends and a third carried over them. Each route gets a casing
    in the background colour so the crossing reads as a flyover. Units: 630-high card x k."""
    rw, cas, gap = 44 * k, 11 * k, 74 * k
    xl = x_left if x_left is not None else W - 440 * k
    y_turn = H - 200 * k
    Ro = 250 * k
    cx = xl + Ro
    Ri = Ro - gap
    a = f"M{f(xl)},{f(H + 80 * k)}V{f(y_turn)}A{f(Ro)},{f(Ro)} 0 0 1 {f(cx)},{f(y_turn - Ro)}H{f(W + 80 * k)}"
    b = f"M{f(xl + gap)},{f(H + 80 * k)}V{f(y_turn)}A{f(Ri)},{f(Ri)} 0 0 1 {f(cx)},{f(y_turn - Ri)}H{f(W + 80 * k)}"
    xc = cx + 110 * k
    c = route([(xc, -80 * k), (xc, H - 90 * k), (W + 80 * k, H - 90 * k)], 120 * k)
    parts = []
    for d, col, lane in ((a, AMBER, True), (b, AMBER_SOFT, False), (c, AMBER, False)):
        parts.append(f'<path d="{d}" fill="none" stroke="{bg}" stroke-width="{f(rw + 2 * cas)}"/>')
        parts.append(f'<path d="{d}" fill="none" stroke="{col}" stroke-width="{f(rw)}"/>')
        if lane:
            parts.append(f'<path d="{d}" fill="none" stroke="{bg}" stroke-width="{f(5 * k)}" '
                         f'stroke-dasharray="{f(22 * k)} {f(18 * k)}"/>')
    return "".join(parts)


# ----------------------------------------------------------------------------- cards, banner
def card(W, H):
    """Social card: lockup, tagline and disclaimer on the left; route lines on the right."""
    k = H / 630
    margin = 88 * k
    parts = [f'<rect width="{W}" height="{H}" fill="{NIGHT}"/>', corridor(W, H, k, NIGHT)]
    lk, lw, lh = lockup("dark", 92 * k)
    top = 96 * k
    parts.append(f'<g transform="translate({f(margin)} {f(top)})">{lk}</g>')
    size = 68 * k
    y = top + lh + 70 * k
    for line in ("Your messages,", "riding shotgun."):
        parts.append(text_path(line, size, PAPER, margin - 3 * k, y, 800, -0.01)[0])
        y += size * 1.12
    parts.append(text_path(DISCLAIMER, 30 * k, FOG, margin - 1 * k, H - 96 * k - 30 * k * 0.72, 600)[0])
    return svg(f"0 0 {W} {H}", W, H, "".join(parts), header=False)


def banner(light, W=800, H=200):
    bg, fg, sub = (PAPER, INK, SLATE) if light else (NIGHT, PAPER, FOG)
    parts = [f'<rect width="{W}" height="{H}" fill="{bg}"/>', corridor(W, H, 200 / 630 * 0.98, bg, x_left=548)]
    lk, lw, lh = lockup("light" if light else "dark", 46)
    x, top = 48, 34
    parts.append(f'<g transform="translate({x} {top})">{lk}</g>')
    t, adv, cap = text_path(TAGLINE, 27, fg, x - 1, top + lh + 26, 800, -0.01)
    parts.append(t)
    parts.append(text_path(DISCLAIMER, 15, sub, x - 0.5, top + lh + 26 + cap + 16, 600)[0])
    title = "Flack. Your messages, riding shotgun. Independent app, not affiliated with Slack."
    return svg(f"0 0 {W} {H}", W, H, "".join(parts), title)


def svg_favicon():
    crop = 21.0
    m = Mark(xa=0, xb=108, **SMALL)
    size = 108 - 2 * crop
    r = size * 0.22
    return svg(f"{f(crop)} {f(crop)} {f(size)} {f(size)}", 32, 32,
               f'<clipPath id="c"><rect x="{f(crop)}" y="{f(crop)}" width="{f(size)}" height="{f(size)}" rx="{f(r)}"/></clipPath>'
               f'<rect x="{f(crop)}" y="{f(crop)}" width="{f(size)}" height="{f(size)}" rx="{f(r)}" fill="{NIGHT}"/>'
               f'<path clip-path="url(#c)" fill="{AMBER}" fill-rule="evenodd" d="{m.outline()}"/>', "Flack")


def svg_apple_touch():
    m = mark(xa=0, xb=108)
    return svg("18 18 72 72", 180, 180, f'<rect x="18" y="18" width="72" height="72" fill="{NIGHT}"/>'
               f'<path fill="{AMBER}" fill-rule="evenodd" d="{m.outline()}{m.dashes(hx=(18, 90), **DASH)}"/>', header=False)


# ---------------------------------------------------------------------------------- android
def vd(width, body, comment):
    return ('<?xml version="1.0" encoding="utf-8"?>\n' + f"<!-- {comment} -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{width}dp"\n    android:height="{width}dp"\n'
            f'    android:viewportWidth="{width}"\n    android:viewportHeight="{width}">\n' + body + "</vector>\n")


def vd_path(d, color, evenodd=False, indent="    "):
    ft = f'\n{indent}    android:fillType="evenOdd"' if evenodd else ""
    return f'{indent}<path\n{indent}    android:fillColor="{color}"{ft}\n{indent}    android:pathData="{d}" />\n'


def vd_group(s, tx, ty, inner):
    return (f'    <group\n        android:scaleX="{f(s, 4)}"\n        android:scaleY="{f(s, 4)}"\n'
            f'        android:translateX="{f(tx, 3)}"\n        android:translateY="{f(ty, 3)}">\n' + inner + "    </group>\n")


def mark_with_lanes(m, m1, indent="    "):
    """Road plus lane lines as three short paths (lint flags very long pathData)."""
    g = m1.dash_groups
    return (vd_path(m.outline(), "@color/flack_amber", evenodd=True, indent=indent)
            + "".join(vd_path(g[k], "@color/flack_night", indent=indent) for k in ("highway", "big", "small")))


def android_foreground():
    m, m1 = mark(xa=0, xb=108), mark(xa=0, xb=108, p=1)
    m1.dashes(hx=(14, 94), **DASH)
    return vd(108, mark_with_lanes(m, m1),
              "Flack mark: a highway with a cloverleaf loop pair. The loops sit inside the 66dp safe zone; "
              "the highway runs off the edge under any mask. Source: brand/flack-icon.svg")


def android_background():
    return vd(108, vd_path("M0,0h108v108h-108z", "@color/flack_night"), "Flat night navy behind the launcher mark.")


def android_monochrome():
    return vd(108, vd_path(mark(xa=0, xb=108).outline(), "#FFFFFFFF", evenodd=True),
              "Themed icon (Android 13+): one colour, loop interiors cut out with evenOdd, no lane lines.")


def android_notification():
    m = mark(SMALL, 3.0)
    L, T, R, B = extents(SMALL)
    s = 22.0 / (m.xb - m.xa)
    tx, ty = 1.0 - m.xa * s, (24 - (B - T) * s) / 2 - T * s
    return vd(24, vd_group(s, tx, ty, vd_path(m.outline(), "#FFFFFFFF", evenodd=True, indent="        ")),
              "Status bar and Android Auto icon: the small mark as a white silhouette (brand/flack-mark-small.svg).")


def android_splash():
    """The system scales this into the splash icon box. The mark stays inside the central
    two-thirds circle (160dp of the 240dp box): radius 31.5 of the 108 canvas's 36."""
    m, m1 = mark(ext=5), mark(ext=5, p=1)
    m1.dashes(**DASH)
    L, T, R, B = extents(GEO)
    cx, cy = (L + R) / 2, (T + B) / 2
    h = GEO["w"] / 2
    rad = max([math.hypot(x - cx, y - cy) for x in (m.xa, m.xb) for y in (GEO["Y"] - h, GEO["Y"] + h)]
              + [math.hypot(GEO["X"] + GEO["R1"] - cx, GEO["Y"] - GEO["R1"] - cy) + GEO["R1"] + h,
                 math.hypot(GEO["X"] - GEO["R2"] - cx, GEO["Y"] + GEO["R2"] - cy) + GEO["R2"] + h])
    s = 31.5 / rad
    return vd(108, vd_group(s, 54 - cx * s, 54 - cy * s, mark_with_lanes(m, m1, indent="        ")),
              "Splash screen icon (Android 12+). The system scales it into the splash icon box; the mark "
              "stays inside the central circle. Use with a flack_night splash background.")


# ------------------------------------------------------------------------------------ output
def write(rel, data):
    path = os.path.abspath(os.path.join(ROOT, rel))
    if not any(path.startswith(d + os.sep) for d in ALLOWED):
        sys.exit(f"refusing to write outside brand/, site/ and drawable/: {rel}")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb" if isinstance(data, bytes) else "w") as fh:
        fh.write(data)


def render_png(svg_text, rel, w, h, scale, tmp):
    """Render with Chromium (render_png.js), then re-save losslessly with Pillow's optimizer."""
    from PIL import Image
    src = os.path.join(tmp, os.path.basename(rel) + ".svg")
    raw = os.path.join(tmp, os.path.basename(rel))
    with open(src, "w") as fh:
        fh.write(svg_text)
    js = os.path.join(os.path.dirname(os.path.abspath(__file__)), "render_png.js")
    subprocess.run(["node", js, src, raw, str(w), str(h), str(scale)], check=True)
    buf = io.BytesIO()
    Image.open(raw).convert("RGB").save(buf, format="PNG", optimize=True)
    write(rel, buf.getvalue())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fonts", help="folder with manrope-latin-600-normal.woff2 and -800-normal.woff2 "
                                    f"from {FONT_PACKAGE}; fetched with npm when omitted")
    ap.add_argument("--no-png", action="store_true", help="skip the PNG files (no Node or Chromium needed)")
    args = ap.parse_args()
    load_fonts(args.fonts)

    write("brand/flack-icon.svg", svg_icon())
    write("brand/flack-mark.svg", svg_mark(AMBER, "Flack mark, for dark backgrounds"))
    write("brand/flack-mark-light.svg", svg_mark(INK, "Flack mark, for light backgrounds"))
    write("brand/flack-mark-small.svg", svg_mark("currentColor", "Flack small mark, for 16 to 47 px",
                                                  g=SMALL, dashes=False, ext=3.0))
    write("brand/flack-wordmark.svg", svg_wordmark(PAPER, "Flack wordmark, for dark backgrounds"))
    write("brand/flack-wordmark-light.svg", svg_wordmark(INK, "Flack wordmark, for light backgrounds"))
    write("brand/flack-lockup.svg", svg_lockup("dark"))
    write("brand/flack-lockup-light.svg", svg_lockup("light"))
    banners = {"brand/readme-banner": banner(False), "brand/readme-banner-light": banner(True)}
    for rel, text in banners.items():
        write(rel + ".svg", text)
    write("site/favicon.svg", svg_favicon())
    res = "app/src/main/res/drawable/"
    write(res + "ic_launcher_background.xml", android_background())
    write(res + "ic_launcher_foreground.xml", android_foreground())
    write(res + "ic_launcher_monochrome.xml", android_monochrome())
    write(res + "ic_notification.xml", android_notification())
    write(res + "ic_splash.xml", android_splash())

    if not args.no_png:
        with tempfile.TemporaryDirectory() as tmp:
            for rel, text in banners.items():
                render_png(text, rel + ".png", 800, 200, 2, tmp)
            render_png(card(1280, 640), "brand/social-preview.png", 1280, 640, 1, tmp)
            render_png(card(1200, 630), "site/og-image.png", 1200, 630, 1, tmp)
            render_png(svg_apple_touch(), "site/apple-touch-icon.png", 180, 180, 1, tmp)
    print("Built brand/, site/ and drawable assets.")


if __name__ == "__main__":
    main()
