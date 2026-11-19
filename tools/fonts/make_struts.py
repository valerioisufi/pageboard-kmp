"""
Builds the strut fonts of the texts (pageboard-ui/src/commonMain/composeResources/font):
one empty glyph for U+FFFC (the object replacement character), 1 em wide, all the room of its
line above the baseline (strut_above.ttf) or below it (strut_below.ttf). Nothing else: Skia
aborts on the default-ignorable characters (U+2060, U+200B) mapped to a glyph of these fonts.

A character in one of them gives its line exactly the room asked above or below the baseline,
which a character of a text font cannot (its ascent and descent go together): the editor and
the canvas reserve the room of the formulas and of the blocks of code with them.

    python3 -I tools/fonts/make_struts.py pageboard-ui/src/commonMain/composeResources/font
"""
import sys

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen

UNITS_PER_EM = 1000


def build(path, family, ascent, descent):
    builder = FontBuilder(UNITS_PER_EM, isTTF=True)
    builder.setupGlyphOrder([".notdef", "strut"])
    builder.setupCharacterMap({0xFFFC: "strut"})
    builder.setupGlyf({name: TTGlyphPen(None).glyph() for name in (".notdef", "strut")})
    builder.setupHorizontalMetrics({".notdef": (UNITS_PER_EM, 0), "strut": (UNITS_PER_EM, 0)})
    builder.setupHorizontalHeader(ascent=ascent, descent=-descent, lineGap=0)
    builder.setupNameTable({"familyName": family, "styleName": "Regular"})
    # USE_TYPO_METRICS (bit 7) and REGULAR (bit 6): every engine reads the same ascent and descent
    builder.setupOS2(
        version=4,
        sTypoAscender=ascent,
        sTypoDescender=-descent,
        sTypoLineGap=0,
        usWinAscent=ascent,
        usWinDescent=descent,
        fsSelection=0x40 | 0x80,
        achVendID="DRWV",
    )
    builder.setupPost()
    builder.save(path)


if __name__ == "__main__":
    directory = sys.argv[1]
    build(directory + "/strut_above.ttf", "DrawView Strut Above", UNITS_PER_EM, 0)
    build(directory + "/strut_below.ttf", "DrawView Strut Below", 0, UNITS_PER_EM)
