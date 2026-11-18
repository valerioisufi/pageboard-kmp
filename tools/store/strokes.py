"""Turns strokes (lists of screen points, 2560x1600) into touchscreen events of an emulator.

Usage: python -I strokes.py <out dir> <shape> [args...]; the shapes are listed in SHAPES. The
directory holds a header, the points and a footer per stroke, and run.sh writing them with dd (each
point on its own, so that the points come over time); push it to /data/local/tmp/st and run run.sh
after adb root.
"""
import math
import sys

DEV = "/dev/input/event1"
W, H = 2560, 1600


def jitter(points, amount=1.2, seed=1):
    """A hand is never exact."""
    out = []
    for i, (x, y) in enumerate(points):
        out.append((x + amount * math.sin(i * 0.21 + seed), y + amount * math.cos(i * 0.17 + seed)))
    return out


def ellipse(cx, cy, rx, ry, turns=1.08, start=-2.4, n=70):
    return jitter([(cx + rx * math.cos(start + 2 * math.pi * turns * i / n),
                    cy + ry * math.sin(start + 2 * math.pi * turns * i / n) * (1 + 0.04 * i / n)) for i in range(n + 1)])


def line(x1, y1, x2, y2, n=24, bow=0.0):
    pts = []
    for i in range(n + 1):
        t = i / n
        nx, ny = -(y2 - y1), x2 - x1
        d = math.hypot(nx, ny) or 1
        b = bow * math.sin(math.pi * t)
        pts.append((x1 + (x2 - x1) * t + nx / d * b, y1 + (y2 - y1) * t + ny / d * b))
    return jitter(pts, 0.8)


def arrow(x1, y1, x2, y2, bow=40):
    shaft = line(x1, y1, x2, y2, 30, bow)
    (ax, ay), (bx, by) = shaft[-4], shaft[-1]
    ang = math.atan2(by - ay, bx - ax)
    head = 34
    left = (bx - head * math.cos(ang - 0.5), by - head * math.sin(ang - 0.5))
    right = (bx - head * math.cos(ang + 0.5), by - head * math.sin(ang + 0.5))
    return [shaft, line(left[0], left[1], bx, by, 8), line(bx, by, right[0], right[1], 8)]


def check(x, y, s=1.0):
    return [line(x, y, x + 22 * s, y + 26 * s, 10) + line(x + 22 * s, y + 26 * s, x + 64 * s, y - 34 * s, 16)[1:]]


def underline(x1, x2, y):
    return [line(x1, y, x2, y + 4, 30, -3)]


def graph(x0, y0, w, h):
    """Axes, a parabola and its tangent at a point."""
    out = [line(x0, y0 + h, x0 + w, y0 + h, 30), line(x0 + w - 24, y0 + h - 12, x0 + w, y0 + h, 6), line(x0 + w - 24, y0 + h + 12, x0 + w, y0 + h, 6),
           line(x0 + 40, y0 + h + 20, x0 + 40, y0, 30), line(x0 + 28, y0 + 24, x0 + 40, y0, 6), line(x0 + 52, y0 + 24, x0 + 40, y0, 6)]
    par = [(x0 + 80 + (w - 120) * i / 40, y0 + h - 20 - (h - 60) * ((i - 20) / 20) ** 2 * 0.9 - 30) for i in range(41)]
    par = [(x, y0 + h - 40 - (h - 80) * (1 - ((i - 20) / 20) ** 2) * 0.0 - (h - 80) * ((i / 40) ** 2)) for i, (x, _) in enumerate(par)]
    out.append(jitter(par, 0.8))
    px, py = par[28]
    slope = (par[29][1] - par[27][1]) / (par[29][0] - par[27][0])
    out.append(line(px - 160, py - 160 * slope, px + 160, py + 160 * slope, 24))
    out.append(ellipse(px, py, 7, 7, 1.0, 0, 16))
    return out


def axes(cx, cy, w, h):
    return [line(cx - w, cy, cx + w, cy, 30), line(cx + w - 22, cy - 11, cx + w, cy, 6), line(cx + w - 22, cy + 11, cx + w, cy, 6),
            line(cx, cy + h, cx, cy - h, 30), line(cx - 11, cy - h + 22, cx, cy - h, 6), line(cx + 11, cy - h + 22, cx, cy - h, 6)]


def bounds(cx, cy, w):
    """y = |x| and y = -|x|."""
    k = 0.75
    return [line(cx - w, cy - w * k, cx, cy, 20) + line(cx, cy, cx + w, cy - w * k, 20)[1:],
            line(cx - w, cy + w * k, cx, cy, 20) + line(cx, cy, cx + w, cy + w * k, 20)[1:]]


def wiggle(cx, cy, w):
    """y = x sin(1/x), squeezed between the bounds."""
    k = 0.75
    pts = []
    for side in (-1, 1):
        seg = []
        n = 300
        for i in range(n + 1):
            t = 0.04 * 25 ** (i / n)
            x = side * t
            y = x * math.sin(1 / x)
            seg.append((cx + x * w, cy - y * w * k))
        pts.append(seg if side > 0 else list(reversed(seg)))
    return [jitter(pts[0] + pts[1], 0.5)]


SHAPES = {
    "axes": lambda a: axes(*a),
    "bounds": lambda a: bounds(*a),
    "wiggle": lambda a: wiggle(*a),
    "ellipse": lambda a: [ellipse(*a)],
    "arrow": lambda a: arrow(*a),
    "check": lambda a: check(*a),
    "underline": lambda a: underline(*a),
    "graph": lambda a: graph(*a),
    "line": lambda a: [line(*a)],
}

import os
import struct


# out is a directory: one file per stroke (s0.bin, s1.bin...), and run.sh writing them in turn
out, shape, args = sys.argv[1], sys.argv[2], [float(v) for v in sys.argv[3:]]
os.makedirs(out, exist_ok=True)
for f in os.listdir(out):
    os.remove(os.path.join(out, f))
def e(t, c, v):
    return struct.pack("<qqHHi", 0, 0, t, c, v)


# Each stroke: a header (the contact), one 144-byte record per point written by its own dd (so
# that the points come over time, as from a hand), and a footer (the lift)
POINT = 144
script = []
for i, s in enumerate(SHAPES[shape](args)):
    head = e(3, 47, 0) + e(3, 57, 100 + i) + e(1, 330, 1)
    body = b""
    for (x, y) in s:
        sx, sy = round(x * 32767 / W), round(y * 32767 / H)
        body += e(3, 53, sx) + e(3, 54, sy) + e(3, 58, 600) + e(3, 0, sx) + e(3, 1, sy) + e(0, 0, 0)
    foot = e(3, 58, 0) + e(3, 57, -1) + e(1, 330, 0) + e(0, 0, 0)
    open(os.path.join(out, f"h{i}.bin"), "wb").write(head)
    open(os.path.join(out, f"b{i}.bin"), "wb").write(body)
    open(os.path.join(out, f"f{i}.bin"), "wb").write(foot)
    d = "/data/local/tmp/st"
    script.append(f"dd if={d}/h{i}.bin of={DEV} bs=72 2>/dev/null")
    script.append(f"for n in $(seq 0 {len(s) - 1}); do dd if={d}/b{i}.bin of={DEV} bs={POINT} skip=$n count=1 2>/dev/null; done")
    script.append(f"dd if={d}/f{i}.bin of={DEV} bs=96 2>/dev/null; sleep 0.15")
open(os.path.join(out, "run.sh"), "w", newline="\n").write("\n".join(script) + "\n")
