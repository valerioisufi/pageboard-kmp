"""
Builds the symbols font of the texts (pageboard-ui/src/commonMain/composeResources/font): the arrows,
the mathematical operators, the geometric shapes and the check marks of Noto Sans Math and Noto Sans
Symbols 2 (both under the SIL Open Font License), which Noto Sans does not have. Without it these
characters come from a font of each system: other shapes and widths, an emoji for some.

The lines of the font are those of Noto Sans: a symbol does not make its line higher.

It prints the ranges it covers, for TextSymbols (pageboard-model); a test keeps the two together.

    python3 -I tools/fonts/make_symbols.py NotoSansMath-Regular.ttf NotoSansSymbols2-Regular.ttf \
        pageboard-ui/src/commonMain/composeResources/font
"""
import os
import sys

from fontTools.subset import Options, Subsetter
from fontTools.ttLib import TTFont
from fontTools.ttLib.tables._g_l_y_f import Glyph, GlyphComponent

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


BOX = 0x25A1
CHECK = 0x2713
BOXES = {0x2610: False, 0x2611: True}


def compose_boxes(font):
    """Adds the boxes of the checklists: the white square, with the check mark inside it when checked."""
    cmap = font.getBestCmap()
    glyf = font["glyf"]
    box, check = cmap[BOX], cmap[CHECK]
    box_glyph, check_glyph = glyf[box], glyf[check]
    box_glyph.recalcBounds(glyf)
    check_glyph.recalcBounds(glyf)
    scale = 0.7
    # The check mark in the middle of the square
    dx = round((box_glyph.xMin + box_glyph.xMax) / 2 - scale * (check_glyph.xMin + check_glyph.xMax) / 2)
    dy = round((box_glyph.yMin + box_glyph.yMax) / 2 - scale * (check_glyph.yMin + check_glyph.yMax) / 2)
    for point, checked in BOXES.items():
        name = "checkbox.checked" if checked else "checkbox"
        glyph = Glyph()
        glyph.numberOfContours = -1
        glyph.components = []
        square = GlyphComponent()
        square.glyphName, square.x, square.y, square.flags = box, 0, 0, 0x4  # ROUND_XY_TO_GRID
        glyph.components.append(square)
        if checked:
            mark = GlyphComponent()
            mark.glyphName, mark.x, mark.y, mark.flags = check, dx, dy, 0x4
            mark.transform = [[scale, 0], [0, scale]]
            glyph.components.append(mark)
        glyf[name] = glyph
        font["hmtx"][name] = font["hmtx"][box]
        for table in font["cmap"].tables:
            if table.isUnicode():
                table.cmap[point] = name
    font.setGlyphOrder(glyf.glyphOrder)
    glyf[box].recalcBounds(glyf)


def print_ranges(path, wanted):
    points = sorted(TTFont(path).getBestCmap())
    points = [p for p in points if p in wanted]
    ranges = []
    for point in points:
        if ranges and ranges[-1][1] == point - 1:
            ranges[-1][1] = point
        else:
            ranges.append([point, point])
    print(", ".join(f"0x{a:04X} to 0x{b:04X}" for a, b in ranges))


def boxes_only(out_dir):
    path = os.path.join(out_dir, "notes_symbols.ttf")
    font = TTFont(path)
    compose_boxes(font)
    font.save(path)
    print_ranges(path, {c for first, last in RANGES for c in range(first, last + 1)} | set(BOXES))


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
    compose_boxes(merged)
    merged.save(os.path.join(out_dir, "notes_symbols.ttf"))
    print_ranges(os.path.join(out_dir, "notes_symbols.ttf"), wanted | set(BOXES))


if __name__ == "__main__":
    if sys.argv[1] == "--boxes":
        boxes_only(sys.argv[2])
    else:
        main(*sys.argv[1:])
