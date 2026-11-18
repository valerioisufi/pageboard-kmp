"""Writes the demo slides of the store screenshots: a calculus lecture with a real text layer."""
import math
import sys

W, H = 960, 540
BLUE = "0.11 0.23 0.45"
GREY = "0.35 0.35 0.38"
ACCENT = "0.95 0.55 0.15"


def esc(s):
    return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def text(x, y, size, s, color=GREY, font="F1"):
    return f"BT {color} rg /{font} {size} Tf {x} {y} Td ({esc(s)}) Tj ET\n"


def runs(x, y, size, parts, color=GREY):
    """Text made of runs of Helvetica (F1), Helvetica-Bold (F2) and Symbol (F3)."""
    out = f"BT {color} rg {x} {y} Td "
    for font, s in parts:
        out += f"/{font} {size} Tf ({esc(s)}) Tj "
    return out + "ET\n"


def header(title, number):
    return (f"{BLUE} rg 0 {H - 8} {W} 8 re f\n"
            + text(60, H - 80, 34, title, BLUE, "F2")
            + f"{ACCENT} RG 3 w 60 {H - 96} m 140 {H - 96} l S\n"
            + text(60, 24, 12, "Calculus I  -  Lecture 4: Limits", GREY)
            + text(W - 40, 24, 12, str(number), GREY))


def graph(x0, y0, w, h):
    """sin(x)/x from -3 pi to 3 pi."""
    out = f"0.6 0.6 0.6 RG 1 w {x0} {y0 + h * 0.3} m {x0 + w} {y0 + h * 0.3} l S {x0 + w / 2} {y0} m {x0 + w / 2} {y0 + h} l S\n"
    pts = []
    for i in range(241):
        x = -3 * math.pi + 6 * math.pi * i / 240
        y = 1.0 if abs(x) < 1e-9 else math.sin(x) / x
        pts.append((x0 + w * i / 240, y0 + h * 0.3 + y * h * 0.6))
    out += f"{BLUE} RG 2.5 w {pts[0][0]:.1f} {pts[0][1]:.1f} m " + " ".join(f"{px:.1f} {py:.1f} l" for px, py in pts[1:]) + " S\n"
    out += f"{ACCENT} rg {x0 + w / 2 - 4} {y0 + h * 0.9 - 4} 8 8 re f\n"
    out += text(x0 + w / 2 + 10, y0 + h * 0.9 + 4, 14, "(0, 1)?", GREY)
    out += runs(x0 + w + 10, y0 + h * 0.3 - 5, 14, [("F1", "x")])
    return out


pages = [
    f"{BLUE} rg 0 0 {W} {H} re f\n"
    + text(80, 300, 54, "Calculus I", "1 1 1", "F2")
    + text(80, 245, 28, "Lecture 4: Limits", "0.85 0.9 1")
    + f"{ACCENT} RG 4 w 80 220 m 200 220 l S\n"
    + text(80, 180, 18, "Notable limits and the squeeze theorem", "0.85 0.9 1"),

    header("A notable limit", 2)
    + runs(60, 380, 30, [("F1", "lim   sin x / x  =  1")], BLUE)
    + runs(70, 352, 16, [("F1", "x "), ("F3", "\xae"), ("F1", " 0")], BLUE)
    + text(60, 290, 18, "The function is not defined at x = 0,")
    + text(60, 264, 18, "yet its values get as close to 1 as we want.")
    + text(60, 214, 18, "Geometric proof: compare the areas of two")
    + text(60, 188, 18, "triangles and a circular sector.")
    + graph(520, 120, 380, 300),

    header("The squeeze theorem", 3)
    + runs(60, 380, 20, [("F1", "If  g(x) "), ("F3", "\xa3"), ("F1", " f(x) "), ("F3", "\xa3"), ("F1", " h(x)  near  c,")])
    + runs(60, 345, 20, [("F1", "and  g(x) "), ("F3", "\xae"), ("F1", " L,  h(x) "), ("F3", "\xae"), ("F1", " L,")])
    + runs(60, 310, 20, [("F2", "then  f(x) "), ("F3", "\xae"), ("F2", " L.")], BLUE)
    + text(60, 240, 18, "Applied to sin x / x:")
    + runs(60, 205, 20, [("F1", "cos x  "), ("F3", "\xa3"), ("F1", "  sin x / x  "), ("F3", "\xa3"), ("F1", "  1      for  0 < |x| < "), ("F3", "p"), ("F1", "/2")], BLUE),

    header("Exercises", 4)
    + text(60, 380, 20, "1.   lim  (1 - cos x) / x^2        as x tends to 0")
    + text(60, 330, 20, "2.   lim  tan x / x                     as x tends to 0")
    + text(60, 280, 20, "3.   lim  x sin(1/x)                   as x tends to 0")
    + text(60, 200, 16, "Hint: for 3, use the squeeze theorem with -|x| and |x|."),
]

objects = []


def add(body):
    objects.append(body)
    return len(objects)


f1 = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
f2 = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
f3 = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Symbol >>")
pages_id = len(objects) + 1 + 2 * len(pages)
kids = []
for body in pages:
    stream = body.encode("latin-1")
    content = add(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream")
    kids.append(add(b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 %d %d] /Resources << /Font << /F1 %d 0 R /F2 %d 0 R /F3 %d 0 R >> >> /Contents %d 0 R >>"
                    % (pages_id, W, H, f1, f2, f3, content)))
assert add(b"<< /Type /Pages /Kids [%s] /Count %d >>" % (b" ".join(b"%d 0 R" % k for k in kids), len(kids))) == pages_id
catalog = add(b"<< /Type /Catalog /Pages %d 0 R >>" % pages_id)

out = bytearray(b"%PDF-1.4\n")
offsets = []
for i, body in enumerate(objects, 1):
    offsets.append(len(out))
    out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
xref = len(out)
out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
for o in offsets:
    out += b"%010d 00000 n \n" % o
out += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, catalog, xref)
open(sys.argv[1], "wb").write(out)
