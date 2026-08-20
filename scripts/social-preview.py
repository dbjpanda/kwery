#!/usr/bin/env python3
"""Render docs/assets/social-preview.png from the same design as the SVG.

There is no SVG rasteriser on the machines this repo is built on, and the PNG
is what GitHub actually serves as the link-preview card, so it drifted away
from the SVG once. This script is the fix: run it whenever the SVG text
changes, and the two stay in step.

Geometry and colours below mirror docs/assets/social-preview.svg. Text y values
are baselines in both, so they are used directly with PIL's 'ls' anchor.
"""
from PIL import Image, ImageDraw, ImageFont

W, H = 1280, 640
OUT = "docs/assets/social-preview.png"

SF = "/System/Library/Fonts/SFNS.ttf"
MONO = "/System/Library/Fonts/Menlo.ttc"

TAGLINE = "Server state for Android"
BULLETS = [
    ("Two screens. One request.", (139, 92, 246)),
    ("Refresh failed. Data stays.", (193, 63, 166)),
    ("Offline write. Replayed on reconnect.", (240, 128, 60)),
]
COORD = "io.github.dbjpanda:kwery-core"
STACK = "Kotlin · Coroutines · Flow · Compose"


def sf(size, weight="Regular"):
    f = ImageFont.truetype(SF, size)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f


def lerp(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def ramp(stops, t):
    """stops: [(offset, (r,g,b))] sorted by offset."""
    for i in range(len(stops) - 1):
        o0, c0 = stops[i]
        o1, c1 = stops[i + 1]
        if t <= o1:
            span = o1 - o0
            return lerp(c0, c1, 0.0 if span == 0 else (t - o0) / span)
    return stops[-1][1]


def background():
    """Diagonal 3-stop gradient, built small and upscaled."""
    stops = [(0.0, (18, 19, 42)), (0.55, (24, 26, 56)), (1.0, (34, 26, 61))]
    n = 256
    small = Image.new("RGB", (n, n))
    px = small.load()
    for y in range(n):
        for x in range(n):
            px[x, y] = ramp(stops, (x / (n - 1) + y / (n - 1)) / 2)
    return small.resize((W, H), Image.BICUBIC)


def glow(img):
    """Radial purple glow, ellipse cx=1040 cy=150 rx=440 ry=330, 0.28 -> 0."""
    n = 256
    a = Image.new("L", (n, n), 0)
    ap = a.load()
    for y in range(n):
        for x in range(n):
            dx = (x - (n - 1) / 2) / ((n - 1) / 2)
            dy = (y - (n - 1) / 2) / ((n - 1) / 2)
            d = (dx * dx + dy * dy) ** 0.5
            ap[x, y] = 0 if d >= 1 else round(255 * 0.28 * (1 - d))
    a = a.resize((880, 660), Image.BICUBIC)
    layer = Image.new("RGB", (880, 660), (139, 92, 246))
    img.paste(layer, (1040 - 440, 150 - 330), a)


def tracked(draw, xy, text, font, fill, tracking=0.0):
    """PIL has no letter-spacing, so step per glyph."""
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=font, fill=fill, anchor="ls")
        x += draw.textlength(ch, font=font) + tracking


def rule(img):
    """Horizontal 3-stop gradient bar with alpha, rect 92,228 300x6 r=3."""
    w, h = 300, 6
    bar = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    bp = bar.load()
    cstops = [(0.0, (139, 92, 246)), (0.6, (193, 63, 166)), (1.0, (240, 128, 60))]
    astops = [(0.0, (230,)), (0.6, (128,)), (1.0, (0,))]
    for x in range(w):
        t = x / (w - 1)
        r, g, b = ramp(cstops, t)
        a = ramp(astops, t)[0]
        for y in range(h):
            bp[x, y] = (r, g, b, a)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=3, fill=255)
    bar.putalpha(Image.composite(bar.getchannel("A"), Image.new("L", (w, h), 0), mask))
    img.paste(bar, (92, 228), bar)


def main():
    img = background()
    glow(img)
    rule(img)
    d = ImageDraw.Draw(img)

    tracked(d, (88, 196), "Kwery", sf(128, "Bold"), (255, 255, 255), tracking=-4)
    d.text((88, 306), TAGLINE, font=sf(46, "Semibold"), fill=(246, 247, 251), anchor="ls")

    body = sf(32)
    for i, (text, dot) in enumerate(BULLETS):
        cy = 386 + i * 64
        d.ellipse([102 - 7, cy - 7, 102 + 7, cy + 7], fill=dot)
        d.text((132, cy + 11), text, font=body, fill=(185, 190, 218), anchor="ls")

    d.rectangle([88, 566, 88 + 1104, 566], fill=(42, 45, 82))
    d.text((88, 606), COORD, font=ImageFont.truetype(MONO, 25),
           fill=(127, 134, 168), anchor="ls")
    d.text((1192, 606), STACK, font=sf(25), fill=(110, 117, 160), anchor="rs")

    img.save(OUT)
    print(f"wrote {OUT} {img.size[0]}x{img.size[1]}")


if __name__ == "__main__":
    main()
