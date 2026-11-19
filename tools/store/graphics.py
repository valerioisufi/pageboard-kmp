"""The store graphics of PencilNotes: captioned screenshots, the feature graphic, the social preview.

Usage: python -I graphics.py <shots dir> <repo dir>
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

SHOTS, REPO = sys.argv[1], sys.argv[2]
FONT = os.path.join(REPO, "pageboard-ui/src/commonMain/composeResources/font")
BOLD = os.path.join(FONT, "plus_jakarta_sans_extrabold.ttf")
MEDIUM = os.path.join(FONT, "plus_jakarta_sans_medium.ttf")
WORDMARK = os.path.join(REPO, "pencilnotes/shared/src/commonMain/composeResources/font/cherry_bomb_one.ttf")
ICON = os.path.join(REPO, "pencilnotes/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png")
META = os.path.join(REPO, "pencilnotes/androidApp/fastlane/metadata/android")

CREAM = (253, 253, 245)
LIGHT = (189, 242, 146)
GREEN = (62, 105, 26)
YELLOW = (235, 232, 101)
INK = (28, 28, 24)

CAPTIONS = {
    "tablet-1-annotate": {"en-US": ("Write on your slides", "Import the PDF of the lecture and annotate it by hand"),
                          "it-IT": ("Scrivi sulle slide", "Importa il PDF della lezione e annotalo a mano")},
    "tablet-2-library": {"en-US": ("Every course in its place", "Folders, PDFs and notes, with a preview of each"),
                         "it-IT": ("Ogni corso al suo posto", "Cartelle, PDF e appunti, con l'anteprima di ognuno")},
    "tablet-3-notes": {"en-US": ("Formulas like a textbook", "Type Markdown and LaTeX, draw graphs by hand"),
                       "it-IT": ("Formule come in un libro", "Scrivi in Markdown e LaTeX, disegna i grafici a mano")},
    "tablet-4-pen": {"en-US": ("Your pens, your colors", "Pens, highlighter, shapes and presets to switch in a tap"),
                     "it-IT": ("Le tue penne, i tuoi colori", "Penne, evidenziatore, forme e preset a portata di tocco")},
    "tablet-5-ask": {"en-US": ("Ask your notes", "Answers with the page they come from, on your device"),
                     "it-IT": ("Chiedi ai tuoi appunti", "Risposte con la pagina da cui vengono, sul dispositivo")},
    "tablet-6-dark": {"en-US": ("Dark when you want it", "Follows the theme of your device"),
                      "it-IT": ("Scuro quando vuoi", "Segue il tema del dispositivo")},
    "phone-1-library": {"en-US": ("Your notes in your pocket", "The same library on your phone"),
                        "it-IT": ("Gli appunti in tasca", "La stessa libreria sul telefono")},
    "phone-2-annotate": {"en-US": ("Write on your slides", "Annotate the lecture PDF by hand"),
                         "it-IT": ("Scrivi sulle slide", "Annota a mano il PDF della lezione")},
    "phone-3-ask": {"en-US": ("Ask your notes", "Answers with their sources, on your device"),
                    "it-IT": ("Chiedi ai tuoi appunti", "Risposte con le fonti, sul dispositivo")},
}


def font(path, size):
    return ImageFont.truetype(path, size)


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, im.width - 1, im.height - 1), radius, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def paste_shadowed(canvas, im, x, y, radius):
    shot = rounded(im, radius)
    shadow = Image.new("RGBA", (shot.width + 80, shot.height + 80), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((40, 40, 40 + shot.width, 40 + shot.height), radius, fill=(40, 60, 20, 70))
    shadow = shadow.filter(ImageFilter.GaussianBlur(24))
    canvas.alpha_composite(shadow, (x - 40, y - 28))
    canvas.alpha_composite(shot, (x, y))


def trim_status(im):
    """The phone shots have a black band where the status bar was: cut it off."""
    px = im.convert("RGB")
    top = 0
    while top < 120 and px.getpixel((im.width // 2, top)) == (0, 0, 0):
        top += 1
    return im.crop((0, top, im.width, im.height))


def captioned(name, locale, title, subtitle):
    shot = Image.open(os.path.join(SHOTS, name + ".png")).convert("RGB")
    phone = name.startswith("phone")
    if phone:
        shot = trim_status(shot)
        w, h = 1080, 2160
    else:
        w, h = 2560, 1600
    canvas = Image.new("RGBA", (w, h), LIGHT + (255,))
    d = ImageDraw.Draw(canvas)
    # A soft band of cream behind the screen
    d.rounded_rectangle((-200, int(h * 0.62), w + 200, h + 400), 300, fill=CREAM + (255,))
    t = font(BOLD, 112 if not phone else 84)
    s = font(MEDIUM, 54 if not phone else 40)
    top = 70 if not phone else 90
    d.text((w // 2, top), title, font=t, fill=GREEN, anchor="ma")
    d.text((w // 2, top + (150 if not phone else 112)), subtitle, font=s, fill=INK, anchor="ma")
    area_top = top + (250 if not phone else 200)
    max_w, max_h = int(w * (0.86 if not phone else 0.80)), h - area_top - 40
    scale = min(max_w / shot.width, max_h / shot.height)
    shot = shot.resize((int(shot.width * scale), int(shot.height * scale)), Image.LANCZOS)
    paste_shadowed(canvas, shot, (w - shot.width) // 2, area_top, 36 if not phone else 44)
    kind = "phoneScreenshots" if phone else "tenInchScreenshots"
    out = os.path.join(META, locale, "images", kind)
    os.makedirs(out, exist_ok=True)
    canvas.convert("RGB").save(os.path.join(out, name.split("-", 1)[1] + ".png"), optimize=True)
    if not phone and name in ("tablet-1-annotate", "tablet-2-library", "tablet-5-ask"):
        # The same for the seven-inch tablets, as Play asks for them apart
        out7 = os.path.join(META, locale, "images", "sevenInchScreenshots")
        os.makedirs(out7, exist_ok=True)
        canvas.convert("RGB").save(os.path.join(out7, name.split("-", 1)[1] + ".png"), optimize=True)


def feature_graphic(locale, tagline):
    w, h = 1024, 500
    canvas = Image.new("RGBA", (w, h), CREAM + (255,))
    d = ImageDraw.Draw(canvas)
    d.ellipse((560, -260, 1400, 640), fill=LIGHT + (255,))
    icon = Image.open(ICON).convert("RGB").resize((112, 112), Image.LANCZOS)
    canvas.alpha_composite(rounded(icon, 26), (52, 112))
    d.text((50, 244), "PencilNotes", font=font(WORDMARK, 58), fill=GREEN)
    y = 336
    for line in tagline:
        d.text((52, y), line, font=font(MEDIUM, 23), fill=INK)
        y += 32
    shot = Image.open(os.path.join(SHOTS, "tablet-1-annotate.png")).convert("RGB")
    shot = shot.resize((512, 320), Image.LANCZOS)
    paste_shadowed(canvas, shot, 482, 90, 20)
    out = os.path.join(META, locale, "images")
    os.makedirs(out, exist_ok=True)
    canvas.convert("RGB").save(os.path.join(out, "featureGraphic.png"), optimize=True)


def icon_512(locale):
    out = os.path.join(META, locale, "images")
    os.makedirs(out, exist_ok=True)
    Image.open(ICON).convert("RGB").resize((512, 512), Image.LANCZOS).save(os.path.join(out, "icon.png"), optimize=True)


def social_preview():
    """The preview GitHub shows for a link to the repository (1280x640)."""
    w, h = 1280, 640
    canvas = Image.new("RGBA", (w, h), CREAM + (255,))
    d = ImageDraw.Draw(canvas)
    d.ellipse((700, -300, 1700, 800), fill=LIGHT + (255,))
    d.text((64, 150), "DrawView", font=font(BOLD, 78), fill=INK)
    d.text((66, 250), "Kotlin Multiplatform libraries for", font=font(MEDIUM, 32), fill=INK)
    d.text((66, 292), "drawing, handwriting and notes", font=font(MEDIUM, 32), fill=INK)
    icon = Image.open(ICON).convert("RGB").resize((64, 64), Image.LANCZOS)
    canvas.alpha_composite(rounded(icon, 14), (66, 380))
    d.text((146, 384), "PencilNotes", font=font(WORDMARK, 44), fill=GREEN)
    d.text((66, 470), "Android  ·  iOS  ·  Desktop", font=font(MEDIUM, 26), fill=GREEN)
    shot = Image.open(os.path.join(SHOTS, "tablet-1-annotate.png")).convert("RGB").resize((640, 400), Image.LANCZOS)
    paste_shadowed(canvas, shot, 590, 120, 24)
    canvas.convert("RGB").save(os.path.join(REPO, "docs/images/social-preview.png"), optimize=True)


for name, locales in CAPTIONS.items():
    for locale, (title, subtitle) in locales.items():
        captioned(name, locale, title, subtitle)
feature_graphic("en-US", ["Handwritten notes on your slides,", "everything on your device"])
feature_graphic("it-IT", ["Appunti a mano sulle tue slide,", "tutto sul tuo dispositivo"])
icon_512("en-US")
icon_512("it-IT")
social_preview()
# The README shows the real application
for name, size in (("tablet-2-library", 1600), ("tablet-1-annotate", 1600)):
    im = Image.open(os.path.join(SHOTS, name + ".png")).convert("RGB")
    im = im.resize((size, int(im.height * size / im.width)), Image.LANCZOS)
    im.save(os.path.join(REPO, "docs/images", name.split("-")[-1] + ".png"), optimize=True)
im = trim_status(Image.open(os.path.join(SHOTS, "phone-3-ask.png")).convert("RGB"))
im.resize((540, int(im.height * 540 / im.width)), Image.LANCZOS).save(os.path.join(REPO, "docs/images/ask.png"), optimize=True)
print("done")
