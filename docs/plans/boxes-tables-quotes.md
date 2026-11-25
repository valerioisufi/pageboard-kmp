# Boxes, chains, tables and quotes

Stage C of the roadmap of `docs/plans/text-editor-enhancements.md`: what `docs/plans/text-boxes.md`,
`docs/plans/text-blocks.md` and `docs/plans/code-blocks.md` left for later about the boxes of text,
their chains, the tables and the quotes. It starts from `implement_text_editor_features` after stage
B (`docs/plans/checklists-and-lists.md`): Room 27, `PROTOCOL_VERSION` 5, `TextElement.maxHeight`
already stored and sent.

The responsibilities are assigned following the GRASP principles.

## Starting point

1. **Chains** (`ChainFlow`, `TextEditorManager.flowChain`): the room of a box of a chain is the
   page under it, to its bottom margin; a box cannot stop above a figure. The other boxes of a chain
   are drawn as they were until the editor closes and the text flows again.
2. **Tables** (`TableEditing`, `TableLayout`): rows and columns are added and removed, their cells
   aligned; cells cannot be merged or split, and the columns share the width by their content.
3. **Soft keyboards**: in a list item, a line break inside the item needs Shift+Enter, which a soft
   keyboard does not have.
4. **Quotes** (`BlockQuote`): one level only (`> > x` shows the second `>`); a block of code, a table
   or a heading in a quote stay text; a quote cannot be inside a list item.
5. **Keeping together** (`ChainFlow.cutFor`): a heading is kept with the line after it; a long
   paragraph after a heading, or a line ending with `:` before a list or a block, may be cut from it.
6. **Formulas and symbols**: `\text{}` in a formula is serif on the page and Noto Sans in the PDF; a
   symbol that the mono font lacks (`✔`) in a block of code comes from the system font on the page.
7. **Selecting by dragging**: a drag selecting text stops at the edge of the window; the canvas does
   not scroll while the text is selected.

## Requirements

1. **A height set by hand** for a box of a chain (`TextElement.maxHeight`): the box takes no more
   than its height, the text goes on in the next box; set by dragging the handle at the bottom of the frame
   of the text being written, cleared by a tap on it. A height from another device is checked
   (finite, at least a line, at most the page).
2. **Tables**: cells merged across a row and split back (`colspan`, written as a cell `<` continuing
   the one before it, readable as a cell in any Markdown), widths of the columns set by dragging
   their borders (written in the delimiter row: `|---|------|`, the dashes proportional), the
   assistant allowed to write tables in its notes.
3. **A key of the bar for a line break in an item** on touch screens: a line in the item, as
   Shift+Enter does.
4. **The boxes of a chain follow while one is written**: drawn with the text as it flows, at most
   once per frame.
5. **Quotes**: nested (`> > x`), with blocks of code, tables and headings in them, and inside list
   items; written and drawn as the levels and the blocks are outside quotes.
6. **Keeping together**: a heading kept with at least two lines of the paragraph after it; a line
   ending with `:` kept with the first line of the block after it (a list, a table, a block of code,
   a formula).
7. **Formulas and symbols**: `\text{}` in the font of the text on the page and in the PDF; the
   symbols the mono font lacks drawn in the symbols font of the notes.
8. **Selecting by dragging past the edge** scrolls the canvas, as dragging a selection does.
9. **No note changes**: the notation added reads as before in the notes written before (a cell `<`
   alone is new; a delimiter row with dashes of different lengths reads as today, equal widths when
   all are equal). Nothing stored is rewritten. Every platform, the 7 languages, light and dark.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The room of a box of a chain, its height by hand checked | `ChainFlow.Room`, `TextRoom.maxHeightOf` (`pageboard-model`), `TextEditorManager.flowChain` | Information Expert, Protected Variations |
| Dragging the bottom edge of a box of a chain | `SelectionFrame` (a handle), `CanvasGestureHandler` (`Mode.TEXT_RESIZE_BOTTOM`), `TextEditorManager.setMaxHeight` | Controller, Indirection |
| The chain drawn while written | `TextEditorManager.flowChain` on a frame, `TextBoxes` | Controller, Pure Fabrication |
| Merged cells and widths of the columns | `TableEditing`, `TableLayout`, `RichTextParser.tables` (`pageboard-model`, `pageboard-ui`) | Information Expert |
| A key for a line break in an item | `EditorCommands.lineBreakInItem`, `TextFormatBar`, `RichTextEditing.softBreak` | Controller, High Cohesion |
| Nested quotes and blocks in quotes | `BlockQuote`, `RichTextParser`, `RichTextPresenter` | Information Expert, Polymorphism |
| Keeping blocks together | `ChainFlow.cutFor`, `TextFlow` | Information Expert |
| The font of `\text{}`, the symbols of the code | `FormulaRenderer` (page), `FormulaPaths` (PDF), `CodeBlockLayout` | Protected Variations |
| Scrolling while selecting | `TouchSelection`, `TextEditorOverlay`, `CanvasGestureHandler.updateAutoScroll` | Controller |

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | The height of a box of a chain by hand: the room of `ChainFlow`, the handle, the gesture, a tap clearing it, the height checked; tests | Done |
| 2 | The boxes of a chain follow while one is written; the key of a line break in an item; tests | Done |
| 3 | Nested quotes, headings in quotes; tests | Done (blocks of code and tables in quotes, quotes in list items: left for later, decision 7) |
| 4 | Keeping a heading with two lines, a line ending with `:` with its block; tests | Done |
| 5 | Tables: merged cells, widths of the columns, tables in the notes of the assistant; tests | Pending |
| 6 | `\text{}` in the font of the text, the symbols of the code, scrolling while selecting; tests | Done (`\text{}` already one font, decision 9) |

## Decisions

Taken alone, revertible:

1. **The order of the phases** follows what each needs: the chains first (the field `maxHeight` is
   stored and sent already, and the editor dropped it until stage B, phase 4), then the blocks,
   then the tables, whose notation is the largest change.

2. **The height is set on the text being written, any text, not only a box of a chain**: a box
   whose text goes past its height begins a chain, as a text going past its page does. The handle
   is at the middle of the line where the box stops (its bottom edge without a height), dragged to
   set it, tapped to clear it (a tap rather than a double tap: one gesture less to learn, and the
   text is saved only when the editor closes).
3. **No handle on a frame lower than 48 dp on the screen**: there it would be where the box is
   grabbed to be moved; zooming in shows it.
4. **A new box of a chain has no height of its own and continues no list**: it took the ones of the
   box written before.

5. **The boxes of a chain follow at each pause of the writing** (when the text is saved, see
   `WritingSaver`), not at each key: flowing the chain may open the editor again in another box,
   which in the middle of a word would get in the way of the input methods. It flows only when the
   text would move between the boxes; otherwise the pause only saves, as before.
6. **The key of a line in an item is in the docked bar only** (a phone, a soft keyboard), and only
   with the caret in a list item: a hardware keyboard has Shift+Enter.

7. **Quotes: nested ones and headings in them now; blocks of code, tables and quotes in list items
   later.** A level is `> ` once more (`> > text`), `>>` staying text as in the notes written before;
   each level is a span of its own (`Span.level`), its bar where the text of the quote around it
   starts, Enter going on at its level and an empty line one level out. A block of code or a table in
   a quote needs its lines read without their markers by the code and the tables, which read the
   source as it is: a change of the parser and of the layouts of both, kept for a phase of its own.
   A quote in a list item needs the item to take the marker in its continuation lines.

8. **A heading keeps the first lines of the paragraph after it only when two of them fit**, the
   paragraph cut between its words (it goes on in the next box on the same line); with room for one,
   the heading goes on with the whole paragraph, as before. A line ending with `:` goes on with the
   block after it (a list, a table, a block of code, a formula), never with a line of text.

9. **`\text{}` was already in the same font on the page and in the PDF**: Noto Sans on both since
   `docs/plans/export.md` (the engine of the formulas takes the files of the notes, `LatexEngine.prepare`),
   which `docs/plans/text-blocks.md` did not know yet. A formula in a text of another font (stage A)
   keeps Noto Sans for its `\text{}`: the engine has one set of fonts for all the formulas.
10. **The symbols of a block of code from the symbols font of the notes**, on the page, in the editor
    and in the PDF (which reads the font of each character), rather than the system font.
11. **A selection dragged by a mouse near or past an edge of the canvas scrolls it at each frame**,
    faster the farther (32 dp from the edge, 24 px a frame at most), and the selection follows the text
    moving under the mouse. A finger selects with the handles, which the canvas already follows.

## Left for later

- `\text{}` in the font chosen for its text (decision 9).
- Blocks of code and tables in a quote; a quote in a list item (decision 7).
- Real-device checks (soft keyboards, the handle by touch), left to the maintainer.
