"""The icon of PencilNotes, drawn from its geometry: the P of Cherry Bomb One underlined by a stroke.

Writes every file the platforms take: the vector layers of the Android adaptive icon, the iOS
icon, the desktop icons (.png, .icns, .ico), the master SVG and the paths of the mark shown in the
application. The store images take the iOS icon (tools/store/graphics.py).

Usage: python -I icon.py <repo dir>
"""
import math
import os
import re
import sys

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw

REPO = sys.argv[1]
FONT = os.path.join(REPO, "pencilnotes/shared/src/commonMain/composeResources/font/cherry_bomb_one.ttf")

GREEN, LIME, YELLOW = "#3E691A", "#BDF292", "#EBE865"

# The canvas is 1024 x 1024; the P is 0.66 of its font size, centered with its underline
P_SCALE, P_X, P_Y = 0.66, 500, 460
LINE = [(372, 774), (520, 758), (690, 744)]
LINE_WIDTH = (18, 40, 14)


def glyph_path():
    """The outline of the P, in the coordinates of the canvas (absolute M, L, Q and Z)."""
    font = TTFont(FONT)
    glyphs = font.getGlyphSet()
    pen = SVGPathPen(glyphs)
    glyphs[font.getBestCmap()[ord("P")]].draw(pen)
    tokens = re.findall(r"[MLQZ]|-?\d+(?:\.\d+)?", pen.getCommands())
    out, i = [], 0
    while i < len(tokens):
        cmd = tokens[i]
        i += 1
        n = {"M": 1, "L": 1, "Q": 2, "Z": 0}[cmd]
        pts = []
        for _ in range(n):
            x, y = float(tokens[i]), float(tokens[i + 1])
            i += 2
            pts.append((P_X + (x - 329) * P_SCALE, P_Y - (y - 348.5) * P_SCALE))
        out.append((cmd, pts))
    return out


def catmull(points, n=30):
    pts = [points[0]] + points + [points[-1]]
    out = []
    for i in range(1, len(pts) - 2):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
        for k in range(n):
            t = k / n
            out.append(tuple(0.5 * (2 * p1[j] + (-p0[j] + p2[j]) * t + (2 * p0[j] - 5 * p1[j] + 4 * p2[j] - p3[j]) * t * t
                                    + (-p0[j] + 3 * p1[j] - 3 * p2[j] + p3[j]) * t ** 3) for j in range(2)))
    out.append(points[-1])
    return out


def width_at(t):
    a, b, c = LINE_WIDTH
    return a + (b - a) * math.sin(t * math.pi) if t < 0.5 else c + (b - c) * math.sin((1 - t) * math.pi)


def underline():
    """The outline of the underline: a stroke whose width grows and fades, with round ends."""
    center = catmull(LINE)
    n = len(center)
    left, right, normals = [], [], []
    for i, (x, y) in enumerate(center):
        a, b = center[max(i - 1, 0)], center[min(i + 1, n - 1)]
        d = math.hypot(b[0] - a[0], b[1] - a[1])
        nx, ny = -(b[1] - a[1]) / d, (b[0] - a[0]) / d
        w = width_at(i / (n - 1)) / 2
        left.append((x + nx * w, y + ny * w))
        right.append((x - nx * w, y - ny * w))
        normals.append((nx, ny))

    def cap(c, normal, w, sign):
        # Half a circle from one side to the other, around the end
        base = math.atan2(normal[1], normal[0])
        return [(c[0] + w * math.cos(base + sign * math.pi * k / 12), c[1] + w * math.sin(base + sign * math.pi * k / 12)) for k in range(1, 12)]

    end = cap(center[-1], normals[-1], width_at(1) / 2, -1)
    start = cap(center[0], (-normals[0][0], -normals[0][1]), width_at(0) / 2, -1)
    return left + end + right[::-1] + start


def svg_d(glyph, line):
    d = "".join(cmd + " ".join(f"{x:.1f} {y:.1f}" for x, y in pts) for cmd, pts in glyph)
    return d, "M" + " L".join(f"{x:.1f} {y:.1f}" for x, y in line) + "Z"


def polygons(glyph):
    """The contours of the P as polygons, its curves flattened."""
    out, cur, last = [], [], None
    for cmd, pts in glyph:
        if cmd == "M":
            cur, last = [pts[0]], pts[0]
        elif cmd == "L":
            cur.append(pts[0])
            last = pts[0]
        elif cmd == "Q":
            (cx, cy), (x, y) = pts
            for k in range(1, 9):
                t = k / 8
                cur.append(((1 - t) ** 2 * last[0] + 2 * (1 - t) * t * cx + t * t * x, (1 - t) ** 2 * last[1] + 2 * (1 - t) * t * cy + t * t * y))
            last = (x, y)
        elif cmd == "Z":
            out.append(cur)
    return out


def rgb(hex_color):
    return tuple(int(hex_color[i:i + 2], 16) for i in (1, 3, 5))


def render(size, tile=None, radius=0.0, ss=4):
    """The icon at [size] pixels: the full square, or a rounded tile of [tile] pixels in the middle."""
    big = size * ss
    tile_px = (tile or size) * ss
    off = (big - tile_px) / 2
    k = tile_px / 1024

    def tr(poly):
        return [(off + x * k, off + y * k) for x, y in poly]

    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    shape = Image.new("L", (big, big), 0)
    ImageDraw.Draw(shape).rounded_rectangle((off, off, off + tile_px - 1, off + tile_px - 1), radius * tile_px, fill=255)
    canvas.paste(Image.new("RGBA", (big, big), rgb(GREEN) + (255,)), (0, 0), shape)
    p_outer, p_counter = polygons(GLYPH)
    mask = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(mask)
    d.polygon(tr(p_outer), fill=255)
    d.polygon(tr(p_counter), fill=0)
    canvas.paste(Image.new("RGBA", (big, big), rgb(LIME) + (255,)), (0, 0), mask)
    line = Image.new("L", (big, big), 0)
    ImageDraw.Draw(line).polygon(tr(LINE_OUTLINE), fill=255)
    canvas.paste(Image.new("RGBA", (big, big), rgb(YELLOW) + (255,)), (0, 0), line)
    return canvas.resize((size, size), Image.LANCZOS)


def vector(fill_p, fill_line):
    """An Android vector drawable of the foreground: 108 dp, the canvas of the icon."""
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Made by tools/brand/icon.py: the P of PencilNotes and its underline. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="1024"
    android:viewportHeight="1024">
    <path
        android:fillColor="{fill_p}"
        android:pathData="{P_D}" />
    <path
        android:fillColor="{fill_line}"
        android:pathData="{LINE_D}" />
</vector>
"""


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


GLYPH = glyph_path()
LINE_OUTLINE = underline()
P_D, LINE_D = svg_d(GLYPH, LINE_OUTLINE)

# The master drawing
write(os.path.join(REPO, "tools/brand/pencilnotes-icon.svg"),
      f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">\n<rect width="1024" height="1024" fill="{GREEN}"/>\n'
      f'<path fill="{LIME}" d="{P_D}"/>\n<path fill="{YELLOW}" d="{LINE_D}"/>\n</svg>\n')

# Android: the layers of the adaptive icon (its background is a color) and the themed one
res = os.path.join(REPO, "pencilnotes/androidApp/src/main/res")
write(os.path.join(res, "drawable/ic_launcher_foreground.xml"), vector(LIME, YELLOW))
write(os.path.join(res, "drawable/ic_launcher_monochrome.xml"), vector("#FFFFFF", "#FFFFFF"))

# iOS: one square of 1024, opaque; the system rounds it
render(1024).convert("RGB").save(os.path.join(REPO, "pencilnotes/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png"), optimize=True)

# Desktop: the window and Linux icon, the Dock (in the grid of macOS), .icns and .ico
icons = os.path.join(REPO, "pencilnotes/desktopApp/src/main/resources/icons")
render(512, radius=0.225).save(os.path.join(icons, "icon.png"), optimize=True)
mac = render(1024, tile=824, radius=0.225)
mac.save(os.path.join(icons, "icon_mac.png"), optimize=True)
mac.save(os.path.join(icons, "icon.icns"))
render(256, radius=0.225).save(os.path.join(icons, "icon.ico"), sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])

# The mark in the application: the same paths, drawn by Compose
write(os.path.join(REPO, "pencilnotes/shared/src/commonMain/kotlin/io/github/valerioisufi/pencilnotes/PencilNotesLogo.kt"),
      f"""package io.github.valerioisufi.pencilnotes

// Made by tools/brand/icon.py: change the icon there, and run it again.

/** The outline of the P of PencilNotes, on a canvas of 1024 x 1024 (the icon of the application). */
internal const val PencilNotesLogoP: String = "{P_D}"

/** The outline of the stroke that underlines the P, on the same canvas. */
internal const val PencilNotesLogoLine: String = "{LINE_D}"
""")
print("done")
