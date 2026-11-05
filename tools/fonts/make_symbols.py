"""
Builds the symbols font of the texts (drawview-ui/src/commonMain/composeResources/font): the arrows,
the mathematical operators, the geometric shapes and the check marks of Noto Sans Math and Noto Sans
Symbols 2 (both under the SIL Open Font License), which Noto Sans does not have. Without it these
characters come from a font of each system: other shapes and widths, an emoji for some.

The lines of the font are those of Noto Sans: a symbol does not make its line higher.

It prints the ranges it covers, for TextSymbols (drawview-model); a test keeps the two together.

    python3 -I tools/fonts/make_symbols.py NotoSansMath-Regular.ttf NotoSansSymbols2-Regular.ttf \
        drawview-ui/src/commonMain/composeResources/font
"""
import os
import sys

from fontTools.subset import Options, Subsetter
from fontTools.ttLib import TTFont

RANGES = [(0x2190, 0x21FF), (0x2200, 0x22FF), (0x25A0, 0x25FF), (0x2605, 0x2606), (0x2713, 0x2718)]
DROPPED = ["GSUB", "GPOS", "GDEF", "MATH", "fpgm", "prep", "cvt ", "gasp", "DSIG", "STAT"]


def subset(path, wanted):
    font = TTFont(path)
    options = Options()
    options.layout_features = []
    options.hinting = False
    options.name_IDs = ["*"]
    subsetter = Subsetter(options)
    subsetter.populate(unicodes=wanted)
    subsetter.subset(font)
    for tag in DROPPED:
        if tag in font:
            del font[tag]
    return font


def add_glyphs(font, other):
    """Adds to [font] the glyphs of [other] with their characters, under names of their own."""
    def renamed(name):
        return "other." + name

    for name in other.getGlyphOrder():
        if name == ".notdef":
            continue
        glyph = other["glyf"][name]
        if glyph.isComposite():
            for component in glyph.components:
                component.glyphName = renamed(component.glyphName)
        font["glyf"][renamed(name)] = glyph
        font["hmtx"][renamed(name)] = other["hmtx"][name]
    # Setting a glyph added its name to the order of the table
    font.setGlyphOrder(font["glyf"].glyphOrder)
    for table in font["cmap"].tables:
        if table.isUnicode():
            for point, name in other.getBestCmap().items():
                if table.format != 4 or point <= 0xFFFF:
                    table.cmap[point] = renamed(name)


def main(math, symbols, out_dir):
    wanted = {c for first, last in RANGES for c in range(first, last + 1)}
    first = subset(math, wanted)
    # What the first font lacks, from the second: a glyph each, no choice left to the merger
    covered = set(first.getBestCmap())
    second = subset(symbols, wanted - covered)
    merged = first
    add_glyphs(merged, second)
    reference = TTFont(os.path.join(out_dir, "noto_sans_regular.ttf"))
    for table, fields in {
        "hhea": ["ascent", "descent", "lineGap"],
        "OS/2": ["sTypoAscender", "sTypoDescender", "sTypoLineGap", "usWinAscent", "usWinDescent", "fsSelection"],
    }.items():
        for field in fields:
            setattr(merged[table], field, getattr(reference[table], field))
    for record in merged["name"].names:
        if record.nameID in (1, 4, 16):
            record.string = "Notes Symbols"
        elif record.nameID == 6:
            record.string = "NotesSymbols-Regular"
    merged.save(os.path.join(out_dir, "notes_symbols.ttf"))

    points = sorted(TTFont(os.path.join(out_dir, "notes_symbols.ttf")).getBestCmap())
    points = [p for p in points if p in wanted]
    ranges = []
    for point in points:
        if ranges and ranges[-1][1] == point - 1:
            ranges[-1][1] = point
        else:
            ranges.append([point, point])
    print(", ".join(f"0x{a:04X} to 0x{b:04X}" for a, b in ranges))


if __name__ == "__main__":
    main(*sys.argv[1:])
