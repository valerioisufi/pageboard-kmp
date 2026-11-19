# Text parity

A text being written looks exactly as the page draws it: the same lines, the same heights, the
same indents. Today the field of the editor and the canvas build the same text in two different
ways, and the text moves when the editor opens, closes, or the caret walks through it. This
document follows [text-editing.md](text-editing.md) (phases 1–9, done) and designs the parity with
the GRASP principles.

## Starting point

The notation is read once (`RichTextParser`, `drawview-model`), then presented twice by
`MarkdownTextUtil` (`drawview-ui`):

1. **The editor** (`toEditorText`, shown by `TextBoxEditor` through a `VisualTransformation`):
   the source as it is written, the delimiters kept in the text at a size of 0.001 sp, shown faded
   at 0.9 times the size when the caret touches their span; every formula and block of code away
   from the caret one transparent character whose font size is chosen to give its line the room
   of the image (a font has its ascent and its descent in a fixed ratio, so the size is the larger
   of the two needs: `max(ascent / fontAscent, descent / fontDescent)`); the list items as written
   (`- `), with no bullet and no indent.
2. **The canvas** (`toCanvasText`, laid out by `ComposeTextTileAdapter`, shown by `MarkdownText`
   out of the pages): the delimiters removed; every formula two Compose placeholders (its body
   above the baseline, and a zero-width one hanging its descent below the top of the font); every
   block of code one placeholder centered in its line; the list items a bullet or a number and a
   hanging indent approximated from the size of the font (`listPrefixWidthPx`), each line of a text
   with lists a paragraph of its own, its line break dropped.

### What the user sees

| # | Problem | Cause |
|---|---|---|
| P1 | Special characters move the text while it is written | The delimiters are in the field: 0.001 sp away from the caret, 0.9 times the size near it; the line of a heading moves as soon as the caret enters it, its `# ` shown |
| P2 | The formulas leave different margins in the editor and on the page, also when they are not edited | Two models of the room of a formula: one character scaled for the larger need (a tall fraction gets a deep descent too, and the other way round), two placeholders with the ascent of the font guessed from `Hg` |
| P3 | A block of code moves the text by a line | The block of the caret shows its fences as two lines more; away from the caret its line is a scaled character in the editor and a centered placeholder on the canvas |
| P4 | The list items are not indented while written | `toEditorText` ignores `LIST_ITEM`; on the page the indent of the wrapped lines is approximated, not measured |

## Requirements

* What is not under the caret is laid out in the editor **exactly** as on the page: the same
  lines, line heights, baselines and indents, by construction rather than by care.
* What the caret reveals never moves the lines vertically: no line appears or disappears, no line
  changes height. A horizontal move inside the line of the caret is accepted for now (see
  [Left for later](#left-for-later)).
* The list items show their bullet or number and their hanging indent while written; the caret
  never enters a marker, Backspace at the start of an item takes its marker away.
* A block of code stays a block while its code is written: no fence lines, its background, its
  label and its numbers in place.
* No stored format changes: the source of a text is the same Markdown, so no migration, no change
  of the synchronization.

## Design

### One presentation

The text is presented **once**, by one expert, for every place that shows it: the canvas (tiles
and PDF export), `MarkdownText` and the editor. The editor only says what the caret reveals; with
nothing revealed the presentation is the canvas one, character by character.

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Present a text: what is shown of every character of the source (kept, hidden, replaced by an object, preceded by a prefix), the styles, the paragraphs, the objects and the offsets between the source and what is shown | `RichTextPresenter` → `PresentedText` (`drawview-ui`, `editor`), replacing `toEditorText` and `toCanvasText` | Information Expert, Pure Fabrication |
| The offsets between the source and the text shown, both ways | `PresentedText.mapping` (`SourceMapping`: two arrays filled while presenting) | Information Expert |
| The room of an object (formula, block of code) in its line: exactly so much above and below the baseline, so wide | `Struts` (`drawview-ui`, `editor`), from the strut fonts of `NotesFonts` | Pure Fabrication, Protected Variations |
| Where an object is drawn, from the layout of the text | `PresentedText.formulas` / `codeBlocks` with their offset; the canvas, `MarkdownText` and the editor read the line of that offset | Information Expert |
| What the caret reveals | `Reveal` (the selection of the editor; none on the canvas) | Information Expert |
| Keep the caret out of what is not shown, Backspace on a concealed marker | `RichTextEditing.snapCaret`, `handleConcealedDeletion` (`drawview-canvas`), called by `TextEditorManager.updateValue` | Information Expert, Controller |

### Lines are paragraphs

Compose adds an empty line around the line break of a paragraph (seen in phase 2 of
text-editing.md and measured again: a paragraph `abc\n` lays out three lines with the next one).
So **every line of the source is a paragraph**, its line break shown as a zero-width space
(U+200B) ending it: one character for one, so the caret before the break is at the end of the
line, after it at the start of the next paragraph. The last line break of a text stays a line
break, which gives the empty last line where the caret goes. The canvas, which dropped the breaks
of the texts with lists only, now presents every text the same way.

### Struts: the exact room of an object

A character of a text font cannot give its line some room above the baseline without some room
below (P2). Two fonts made for it can: `strut_above.ttf` and `strut_below.ttf`
(`tools/fonts/make_struts.py`), one empty glyph for U+FFFC, 1 em wide, all of the line above the
baseline or all below (nothing else: Skia aborts on a word joiner or a zero-width space mapped
to a glyph of such a font). An object of width `w`, ascent `a` and descent `d` is shown as three
characters:

```
U+FFFC in strut_above at a px, letter spacing w − a   (a above the baseline, w wide)
U+2060 in the text font (word joiner: the line never breaks inside the object)
U+FFFC in strut_below at d px, letter spacing −d      (d below the baseline, no width)
```

Measured on the desktop: a line of 50 px above and 15 px below, the object 30 px wide, exactly.
Being plain characters, struts work in a text field (which accepts no placeholders), so the editor
and the canvas lay out **the same string**. Without the strut fonts (`NotesFonts.System`) the
struts fall back to the character of the text font scaled as today: both sides still agree.

* An inline formula: its image's ascent and descent, its width; drawn with its baseline on the
  baseline of its line.
* A formula alone on its line (`$$…$$`): the same, as wide as the line, drawn in its middle.
* A block of code: as high as its block (its descent the descent of the font, so the line is
  exactly the block), as wide as the line; drawn from the top of its line.

### What the caret reveals

* **Delimiters** (`**`, `_`, `~~`, `` ` ``, `\`): hidden; shown faded (the color only, at the size
  of their text) when the caret touches their span. The `#` of a heading only when the caret is on
  them or right after them, not anywhere in the heading.
* **A formula**: its source in the line, the formula rendered above it (as today); before the
  source a zero-width strut keeps the room of the image, so the line keeps its height.
* **A block of code**: with the caret in its code, its code is shown in the line as text laid out
  as in the block: each line a paragraph indented to the code of the block (its column of numbers
  included), the tabs as spaces, the spaces of its list item taken off, a strut on its first line
  for the top of the block (padding and label) and one on its last for its bottom. The editor draws
  the background, the label and the numbers of the block (`CodeBlockLayout.drawChrome`). The fences
  are hidden; they are revealed when the caret is on one of their lines: moved there by the arrows,
  or by Backspace at the start of the code (which reveals the opening fence instead of joining it to
  the first line of code).
* **A list marker**: never revealed. The source of the marker (its indent, `-`, `*`, `+` or the
  number, the space) is replaced by the bullet or the number as the page draws it; the paragraph
  of the item has the hanging indent of that prefix, measured with the font. The caret is kept out
  of the marker; Backspace at the start of the text of an item takes its marker away.

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | Parity test: the editor and the canvas lay out a set of texts (special characters, emphasis, headings, inline and display formulas, blocks of code, nested lists, alignments) with nothing revealed, and their lines, baselines and indents are compared; the differences found today recorded as ignored tests, each phase enabling its own | Done |
| 2 | One presentation: the strut fonts, `RichTextPresenter` with the mapping, the lines as paragraphs, the delimiters removed, the formulas and the blocks of code as struts, the list items with their bullet or number and their hanging indent measured; the canvas, the export, `MarkdownText` and the editor on it (`toEditorText`, `toCanvasText` and the placeholders removed) | Done |
| 3 | Lists while written: the caret kept out of the markers, Backspace at the start of an item taking its marker away | Done |
| 4 | Reveal without vertical moves: the delimiters faded by their color only, the `#` of a heading revealed near them only, the room of a formula kept while its source is shown, a block of code written as a block (struts, chrome, fences hidden, Backspace revealing the opening fence) | Done |
| 5 | Checks: the screenshots recorded again where the page changed (the line breaks of every text are now paragraphs, a block of code takes exactly its height), the desktop application on a scratch directory | Done |

### Phase 1 results

* `TextParityTest` (`drawview-ui`, desktop) lays a text out as the canvas does and as the field of
  the editor does with nothing revealed, with the fonts of the notes, at 20 px on 320 px, and
  compares every line (top, baseline, bottom, left and right ends, within half a pixel).
* The same today: plain text, alignments, emphasis and escapes, headings.
* Different today (ignored until their phase): a line with a fraction is 53 px high on the page
  and 111 px in the editor (P2); a display formula and a block of code have their baseline 9 and
  15 px lower in the editor (P2, P3: drawn centered, so not seen, but the parity of the struts needs
  it); a list item wraps under its marker in the editor, 19 px left of the page (P4); a block of
  code in a list item is 27 px high on the page and 40 px in the editor (P3, P4).

### Phase 2 results

* `RichTextPresenter.present` presents a text once for the canvas (`ComposeTextTileAdapter`, so the
  tiles and the PDF export), `MarkdownText` and the editor (`TextBoxEditor`, through its
  `VisualTransformation`): `toEditorText`, `toCanvasText` and the Compose placeholders are gone. The
  list items are presented by it too, so their bullets and hanging indent came with this phase in
  the editor (phase 3 keeps the behaviors of the markers).
* `SourceMapping` maps the offsets both ways from two arrays filled while presenting: a hidden
  character at the next one shown, an inserted one (a bullet) at the offset it stands for, a tap on
  the second half of a formula after it.
* The strut fonts (`strut_above.ttf`, `strut_below.ttf`, 668 bytes each) are in `NotesFonts`
  (`strutAbove`, `strutBelow`); without them (`NotesFonts.System`) the struts are approximated in
  the font of the text, the same way on both sides. Skia aborts the process when a word joiner or a
  zero-width space is drawn in a font that maps them: they stay in the font of the text.
* The editor renders its formulas at steps of size (fewer images while zooming) but gives them the
  room of the image the canvas renders, at the resolution of the canvas scaled to the zoom: the
  rounding of the images no longer moves the lines.
* `TextParityTest` now compares the real canvas (`ComposeTextTileAdapter.layout`, as the export
  reads it) with the field of the editor, at the resolution of the canvas (20 px per mm): lines,
  formulas, blocks of code and the backgrounds of the code in the line agree within half a pixel
  for every case, the formulas, the blocks of code and the lists included.
* The public API of `drawview-ui` changed (recorded with `updateKotlinAbi`): `MarkdownTextUtil`
  keeps `toLiveAnnotatedString`, `InlineCode` and `RenderedFormula`; `NotesFonts` takes the strut
  fonts.

### Phase 3 results

* `RichTextEditing.keepCaretOutOfMarkers` (`drawview-canvas`): a caret moved into the marker of a
  list item goes to the text of the item, or, moved backwards from that text, to the end of the line
  before; `TextEditorManager.updateValue` applies it to every move of the caret. Without it the
  arrows would walk through the marker without the caret moving on the screen, and a character
  typed there would break the item.
* `RichTextEditing.handleConcealedDeletion`: Backspace at the start of the text of an item, which
  deletes the space of its marker (not shown), takes the whole marker away; applied to what the
  user types (not to the formatting commands).
* Tests: the moves of the caret and the deletion, by themselves and through the editor.
* The public API of `drawview-canvas` gains the two functions (recorded with `updateKotlinAbi`).

### Phase 4 results

* The delimiters the caret reveals are faded by their color only, at the size of their text; the
  `#` of a heading only with the caret on them or right after them, so that walking in a heading
  does not move its text.
* The source of a formula under the caret comes after three struts of no width with the room of
  the formula: the line keeps its height (and a formula alone on its line, its line).
* A block of code with the caret in its code is written as a block: its fence lines hidden, each
  line of code a paragraph at the column of the code of the block (its numbers included), wrapped
  under its indent, its tabs as the spaces of the block, the spaces of its list item taken off; a
  strut on its first line for the top of the block (padding and label) and one after its last line
  for its bottom; the editor draws the rest of the block around it (`CodeBlockLayout.drawChrome`).
  The fences are shown with the caret on one of them; Backspace at the start of the code moves the
  caret onto the opening fence instead of joining it to the first line
  (`RichTextEditing.handleConcealedDeletion`).
* Found while measuring: Compose gives the first line of every paragraph the room of the font of
  the paragraph (the font of the text) above it, also when all its characters are in the font of
  the code. The code of a block is now laid out in paragraphs of the font of the text too
  (`CodeBlockLayout.layout(textFont)`), on the page as in the editor: the lines of a block of code
  are a little further apart than before (11 px at 20 px per mm for a 12 pt text), and the code written in
  the editor lies on the lines of the block drawn around it.
* Tests: no line moves vertically when the caret reveals a formula, a display formula, the code of a
  block, the `#` of a heading or the delimiters of an emphasis (within a pixel: Skia rounds its lines
  to whole pixels, and a line rebuilt of other parts may round the other way); the written code on
  the baselines and the columns of the block; Backspace at the start of the code.

### Phase 5 results

* The screenshots that changed, each seen before recording it again: the texts being written with a
  formula (the line of a fraction no longer has the margin of the old model), a block of code (its
  code written in the block, its lines a little further apart), a list item with a block of code, a
  formula that cannot be read, the phone (a formula no longer makes its line wrap), `MarkdownText`
  and the review of the lecture assistant (the lines of its text are paragraphs: 3 or 4 px closer
  with the height of line of the theme).
* Found by the screenshots: `MarkdownText` takes the style of its theme, whose height of line is
  fixed and ignored the struts (its blocks of code fell on the text around them). A text with
  formulas or blocks of code is laid out with the natural height of its lines, as on the page.
* Checks: `jvmTest checkKotlinAbi`, Android and iOS (`compileAndroidMain`,
  `compileKotlinIosSimulatorArm64`, both applications assembled), `verifyRoborazziJvm` of explorer,
  ui, sync-ui, lecture-ui and export-ui, `consumer-checks/check.sh`.
* The desktop application started on a scratch directory, without errors; not driven by hand (the
  editor itself is covered by the parity tests on the desktop, the platforms with a touch keyboard
  are left for later).

## Decisions

Approved by the maintainer on 2026-10-25 ("Va bene per il momento quello che ha deciso tu"), who asked for the whole plan to be implemented in a worktree:

1. The presentation of the page is the reference: the editor follows it, and the page changes only
   where it was approximated (the indent of the list items, the height of a line with a block of
   code, the line breaks as paragraphs).
2. Inline delimiters are revealed when the caret touches their span, at the size of their text.
3. The fences of a block of code are hidden while its code is written; the label of the block
   stays drawn.
4. The list markers are never revealed.

Taken while designing, revertible:

5. **Strut fonts** in the resources of `drawview-ui` (two files of about 1 KB each, generated by a
   script kept in the repository) rather than a placeholder model in the canvas only: the field of
   the editor accepts no placeholders, and two models cannot agree by construction.
6. Every line is a paragraph, also in the texts without lists, so that one presentation serves all.
7. `MarkdownTextUtil.toCanvasText`, `CanvasText`, `PlaceholderSpec`, `InlineFormula`,
   `CanvasCodeBlock` leave the public API of `drawview-ui` (not released yet: `0.1.0-SNAPSHOT`);
   `NotesFonts` takes the strut fonts as two more optional families.
8. Backspace at the start of the text of a list item removes the whole marker (its indent
   included), not one level of indent.

## Left for later

* **Horizontal moves while writing**: revealing the delimiters of the span under the caret still
  widens its line, and a line close to the right edge can wrap differently while written than on
  the page. To study: revealing only the delimiter next to the caret, drawing the delimiters over
  the text without room (as the formulas), or formatting without visible delimiters.
* The right padding of a block of code written as text: its lines wrap on the width of the line,
  not of the block minus its padding (a line of code within a few characters of the edge only).
* On real devices: the struts and the zero-width line breaks with the caret, the selection handles
  and the input methods of Android and iOS (the layout itself is the same engine on every
  platform, but the field there is not tested by the desktop tests).
