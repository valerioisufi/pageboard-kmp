# Text fidelity: written as the page draws it

A text being written should look exactly as the page will draw it, and nothing should move while
the caret walks through it. The user reported on 2026-11-05:

1. the box being written and the box saved differ, also with no notation shown, and a box of many
   lines visibly shifts when the editor opens or closes;
2. the notation the page hides moves the text when the caret reveals it: a long formula is hard
   to click, because it moves away (by a lot) as its LaTeX appears;
3. the arrows get stuck, or behave badly, around blocks of code;
4. zooming while a text with formulas is written leaves the formulas out of place until the editor
   closes;
5. a backtick typed over a selection does not surround it, as `*` does (on macOS, with ⌥9);
6. on Windows and on macOS, a selection made code (or another format) with the formatting bar gets
   its backticks at its end only (reported with the approval of the plan).

This document follows [text-parity.md](text-parity.md) (one presentation for the page and the
editor, done) and [selection-formatting.md](selection-formatting.md) (a delimiter typed over a
selection, done). It starts from main at 9535abe7. The responsibilities are assigned following
the GRASP principles (Information Expert, Creator, Controller, Low Coupling, High Cohesion,
Polymorphism, Pure Fabrication, Indirection, Protected Variations).

## Starting point

"Measured" was run on the JVM on this Windows computer, in the editor of the application
(`EditorScreen`, with MicroTeX where formulas are drawn), with keys and values given to the field
as the tests of the explorer give them; the probes were not committed. "Read" was read in the code.

### 1. The lines drift with the zoom (measured)

The page lays a text out once, at 20 pixels per millimeter (`ComposeTextTileAdapter`,
`LAYOUT_PX_PER_MM`), and draws that layout scaled at every zoom ([text-heights.md](text-heights.md),
phase 1). The field of the editor lays the same presented text out at the pixels of the screen:
its font size and width are in screen pixels (`TextBoxEditor`: `fontSize * PT_TO_MM * scale`,
`(boxWidth * scale).toInt()`). The text engines round the height of every line to whole pixels, so
the error grows with every line. `TextParityTest` compares the two at 20 px/mm, the one zoom where
they agree. [text-heights.md](text-heights.md) left it for later.

The same 48 lines (16 paragraphs of a wrapped sentence, 100 mm wide), laid out by the page and by the
field; how far the top of the last line is from the page, in pixels of the screen (100% is about
4.2 px/mm):

| Font | 48% | 64% | 90% | 100% | 119% | 150% | 180% | 217% | 270% |
|---|---|---|---|---|---|---|---|---|---|
| 11 pt, 48 lines | +18.8 | −15.6 | −1.6 | −13.2 | −24.5 | −19.3 | −3.2 | −11.8 | −4.8 |
| 12 pt, 48 lines | +23.6 | +22.5 | +12.6 | −7.8 | +12.0 | −11.3 | +25.3 | −15.8 | −10.1 |
| 14 pt, 64 lines | −32.5 | −15.2 | −33.5 | −23.1 | +15.8 | −34.1 | −1.9 | −27.8 | −35.3 |

At 48% the 11 pt box is 264.0 mm high while written and 254.4 mm on the page. The lines wrapped at
the same words in this sample; with widths of other sizes a line near the edge can wrap elsewhere
(read).

Smaller differences, read:

* The field is placed on whole pixels (`Modifier.offset` with `roundToInt`), the page at the exact
  place: up to half a pixel.
* The tiles are rasterized at a level of detail and filtered when drawn
  ([tile-sharpness.md](tile-sharpness.md)); the field is drawn at the pixels of the screen. The same
  glyphs at the same places have slightly different edges written and saved.
* The frame tints the box being written (by design).

### 2. Zoom and size while writing (measured)

Zoomed by 1.6 while a text with formulas and a heading is written: the formulas are drawn over the
words around them and the heading keeps its size of before, until the text changes or the editor
closes. The field receives the presented text through a `VisualTransformation` lambda that reads
the latest presentation; the lambda is the same object at every composition, and the field applies
it again only when its value changes. A zoom changes the presented text (the sizes of the struts
and of the headings are in pixels), not the value. With the transformation keyed on the presented
text the same zoom draws everything in place (measured). A new font size or color while writing,
and the formula engine becoming ready, change the presented text without the value too (read).

### 3. What the caret reveals moves the text (measured)

The presentation of the editor depends on the caret (`RichTextPresenter.present(…, reveal)`):

* **A formula.** The caret in a long formula in the line (120 mm box, 16 pt): its source takes two
  lines where the formula took one, the field grows from 287 to 319 px, every line below moves by
  32 px and the rest of its line moves sideways. The rendered preview is drawn above the line, and
  under the formatting bar when the formula is on the first lines (seen). A formula alone on its
  line keeps its height, but its source wraps when wider than the line.
* **A block of code**, walked with → from the line above: on the opening fence the fences appear
  (+29 px for every line below), in the code they hide again (−29 px), on the closing fence +29
  px, after it −29 px: four jumps in one walk. ```` ```kotlin ```` and its line break take ten
  presses.
* **↑ from the line below a block** goes to the start of its opening fence, past its code; the next
  ↑ leaves the block. Going up, the code is never reached. The block is one object in one line of
  the field: the offset under the caret in that line is the start of the block, and once the fence
  shows, the line above it is outside the block. ↓ goes through the code, with the jumps above.
* **Delimiters, `#`, link addresses.** Shown, faded, while the caret touches their span: the line
  gets wider, and a line near the edge wraps differently
  ([text-parity.md](text-parity.md), left for later). A link shows its whole address.
* **← and →** move the caret by one character of the source (measured: one press per character of
  the fences). A character that is not shown costs a press with nothing moving: this is why the
  editor reveals the notation the caret is in, and why `keepCaretOutOfMarkers` exists for the
  markers of lists and quotes.

### 4. A delimiter typed with a dead key (measured on the editor, read)

`SelectionInput.surround` surrounds the selection when a delimiter replaces it with no composition
(`TextEditorManager.updateValue`). On a layout where `` ` `` (or `~`, `'`, `"`) is a dead key, as on
US International, the key first composes: the composed character replaces the selection, then
it is committed over a collapsed caret, and nothing is surrounded. Given to the editor as a dead key
gives them, `una parola qui` with `parola` selected becomes ``una ` qui``; typed directly,
``una `parola` qui``. The user types it on macOS with ⌥9 (decision 4).

### 5. The formatting bar on a selection (measured)

`una parola qui` with `parola` selected, the button "Code" pressed with a mouse: ``una parola`` qui``,
and the same with "Bold". The selection is collapsed to its end when the button is pressed, before
the command runs: the button takes the focus (as a focusable control does when pressed with a mouse
on the desktop), the field losing it keeps only the end of its selection, and the command then
inserts the delimiters at the caret. With the buttons not taking the focus, both surround the word.
The commands run by their shortcuts were right (`CommandTest`): the focus stays in the field.

### A spike (measured, not committed)

The field laid out at 20 px/mm and drawn scaled by `zoom / 20` in a graphics layer:

* at 73%, the nine lines of a box are at the same places written and saved (relative to the box,
  within the pixel); zoomed by 1.6 while written, the formulas and the heading are where the page
  draws them;
* `TextBoxLargeTest`, `TextBoxGesturesOverFieldTest` and `TextBoxFlowTest` pass unchanged;
* the caret of the field is scaled with it: 0.3 px wide at 73%.

## Requirements

1. **Written is saved.** The text being written is laid out exactly as the page lays it out (its
   lines, their heights and baselines, where they wrap, its formulas, blocks and tables), by
   construction, at every zoom, size and width, on every platform. Opening or closing the editor
   moves nothing.
2. **The layout of a text depends on its text and style only**, never on the caret or the
   selection: moving the caret, clicking, selecting move no line and no character. The one
   exception: a `>` alone being typed at the start of a line stays as typed (it may become `>=`).
3. **What the page hides stays reachable**: the source of a formula, the address of a link, the
   language of a block of code are edited by what they belong to, the formula drawn in its place
   while it is written.
4. **The arrows never stall**: every press moves the caret on the screen; ↑ and ↓ go through the
   lines of the text, of the blocks of code and of the tables in their order.
5. **Deleting deletes what is seen**: Backspace and Delete never break a format by taking one of
   its hidden delimiters; a format goes with its last character, or by the bar.
6. **Zooming, or a new size or color, while writing** draws the text in place at once.
7. **A delimiter typed over a selection surrounds it**, whatever the keyboard: dead keys and input
   methods included. **A button of the bar formats the selection** as its shortcut does: the bars
   of the texts never take the focus from the text being written.
8. **The caret, the selection and the handles** are where the text is at every zoom and rotation;
   the caret is 2 dp wide on the screen.
9. **Nothing stored or sent changes**: the source of a text stays the same Markdown; no migration,
   no change of the protocol.

## Design

### One layout, the page's

The field lays its text out at the resolution of the page and is drawn scaled.

* `TextBoxEditor` presents and lays out at `LAYOUT_PX_PER_MM`: the font size, the width and every
  number of the presentation are those of `ComposeTextTileAdapter.prepare`, so the field and the
  page lay out the same characters with the same styles on the same width.
* A layout modifier (`PageScale`) measures the field on that width, gives the canvas its size on
  the screen and places it with a layer scaled by `zoom / 20`, translated to the exact place of the
  box (no rounding) and turned around its corner. A zoom or a pan changes the layer only: no
  composition, no layout. The modifiers before it (the gestures of the canvas) see screen pixels,
  those after it the pixels of the layout.
* The transformation of the field is keyed on the presented text: whatever changes it reaches the
  field (requirement 6).
* The formulas keep the room of the page's images; their images are rendered at the size they are
  drawn on the screen, in steps of 8% as now, as the tiles render them again at their size.
* What goes out of the field (the caret followed by `CaretReveal`, the anchor of the strip, the
  frame) is converted by the same scale.

The adapter's resolution stays the one expert of the layout (Information Expert); the zoom only
reaches a layer (Protected Variations); `PageScale` is a Pure Fabrication.

### The caret, the selection, the pointers

Scaled with the field, the field's own caret is as thin as the zoom (measured), and its handles on
Android and iOS are placed off by the zoom and the rotation: Compose 1.12.1 places a handle at
the top-left corner of the field in the window plus its position in the layout of the field,
without the layers above the field (`HandlePositionProvider`, read in its sources). Neither knows
the objects of the text.

* `TextCaret` draws the caret: 2 dp on the screen at every zoom, blinking as Compose's (solid while
  typing or moving), wherever the caret is: in the text, in a cell of a table, in a block of code,
  in the strip. The brush of the field is transparent.
* `TextHitTest` tells the offset of the source under a point of the field: in the text, in a cell
  of a table, in a block of code, on a formula. It gathers `codeOffsetAt` and the taps on the cells.
* `TouchSelection` takes the touches of a finger and of a pen on the text, after
  `canvasGesturesOverField` keeps the pan and the pinch: a tap puts the caret, two select a word,
  a long press selects a word and then follows the finger, a pen pressed and dragged selects; the
  handles of the caret and of the selection are drawn at their size on the screen, outside the
  layer, and dragged; the text toolbar of the platform (`LocalTextToolbar`) copies, cuts, pastes,
  selects all. The field's own pointer input gets no touch (taken at the first pass), so its handles
  never show. A mouse stays the field's, which selects well through the layer (it has no
  handles): on the objects the editor takes it too (a click on a block of code today, a drag in
  one in phase 6).

`TouchSelection` is the controller of the touches on the text, calling
`TextEditorManager.updateValue` (Controller, Indirection); `TextHitTest` knows the layout and the
presented text (Information Expert).

### Nothing moves with the caret

* `RichTextPresenter.present` loses `reveal`: delimiters, `#`, link addresses, escape backslashes
  and fences are hidden always, formulas, blocks of code and tables are objects always (the cells
  too). `WrittenCodeBlock`, `codeSources`, `formulaAtCaret` and the faded delimiters go. The
  editor asks only for the `>` being typed.
* `CaretStops` (`drawview-ui`, from `SourceMapping`): an offset is a stop when it is shown at
  another place than the one the caret leaves. A move (arrows, Home and End, words, with Shift
  too) landing on an offset that is not a stop goes on in its direction to the next stop. A caret
  put by a tap, among the offsets shown at the same place, takes the one inside the format on its
  left (typing goes on in the format on its left, as in the word processors), after the marker of
  a list item, a quote or a heading. The source of a formula is made of stops (decision 3). The
  field applies them to what it gives the editor (its keys, a mouse, the touches, the handles),
  where the presented text is (a place given by a pointer is settled and never moved on, since
  `docs/plans/pointer-selection.md`); `keepCaretOutOfMarkers` stays for the moves the editor makes itself
  (changed in phase 4: proposed first as a port of `TextEditorManager`, which would have added to
  the public API what only the field needs).
* Deletion of what is seen: `SeenDeletion` (`drawview-canvas`, internal) takes the characters
  shown in a range and keeps the delimiters of a span unless its whole content goes, with it then.
  Backspace and Delete next to a hidden delimiter take the shown character beyond it. Applied by
  `updateValue` to Backspace, Delete, a cut, and a selection typed over. The markers keep their
  rules (`handleConcealedDeletion`, which makes a heading text again with Backspace at its start).
  Backspace right after a formula, or at the start of the code of a block, goes into it: the strip
  shows its end.
* `TextNavigation` (`drawview-ui`) moves the caret ↑ and ↓ by the layouts: the lines of the text,
  of the blocks of code and the rows of the tables in their order, keeping its column as Compose
  does; at the first and the last line of a box of a chain, into the box before or after as now.

The presenter knows what it hides and where it shows each offset, so the stops come from its
mapping (Information Expert); the deletions are a rule of the notation, so they stay with the
spans of the parser (Information Expert, Pure Fabrication); the port keeps the controller apart
from the presentation (Indirection, Low Coupling).

### The source strip

* `SourceStrip` (`drawview-ui`) shows the source that the page hides of what the caret is in: the
  LaTeX of a formula, the address of a link (the caret in the text of the link), the line of the
  opening fence of a block (its language and its words). It is anchored to what it belongs to,
  above its line, below when the formatting bar or the edge of the window is above, never under
  the bar. The caret, the selection and the composition of the field are drawn in it; a tap in it
  puts the caret on the character tapped.
* The keys go to the one field: one value, one undo history, one draft, as in the cells of a table.
* A formula stays drawn in its place while it is written, rendered again at each key; while its
  source cannot be rendered it keeps its last image, and is red source once the caret leaves it, as
  the page draws it. The rendered preview above the caret goes: the formula in its place is the
  preview.
* The fence of a block opens in the strip with a tap on the label of the block, or with Backspace
  at the start of its code.

High Cohesion (one place shows a hidden source), Pure Fabrication, Indirection (the strip draws
what the one field holds).

### Blocks of code as objects

* A block is always its `CodeBlockLayout` drawn over its character, as on the page. With the caret
  in its code, the caret and the selection are drawn in the layout of the block
  (`CodeBlockLayout.cursorRect`, `selectionPath`: the offsets of the source mapped to the code laid
  out, its tabs and the indentation of its item, the reverse of `codeOffsetAt`).
* ← and → go through the code by characters and skip the fences (stops); ↑ and ↓ go by the lines
  of the code laid out (`TextNavigation`); a drag selects in it.

The objects of a text (blocks of code, tables, formulas) answer the same questions: the rectangle
of an offset, the offset at a point, the line above and below (Polymorphism); `CodeBlockLayout`
knows its own layout (Information Expert).

### Dead keys

* When a composition starts over a selection, `TextEditorManager` keeps the value before it. When
  the composition is committed as one delimiter or opening of `SelectionInput` and the rest of the
  text is the same, the selection is surrounded as if the character had been typed; anything else
  (`è` from `` ` `` and `e`) stays a replacement. The composed character is shown while composing,
  as now.

The keyboards stay seen as values only (Protected Variations).

### The bars keep the focus in the text

* The controls of the bars of the texts (`FormatButton`, `FormatToggle`, the color and fill
  buttons, the numbering button, `BarAction`, the steps of `FontSizeControl`) are not focusable
  (`keepsTextFocus`): pressed with a mouse they act on the selection the field still has, as their
  shortcuts do. The field of a size typed in the menu of `FontSizeControl` keeps taking the focus.

The control knows whether it should take the focus (Information Expert); the field keeps its own
rule for a focus lost.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The field laid out at the resolution of the page, drawn scaled at the exact place | `TextBoxEditor`, `PageScale` (`drawview-ui`) | Information Expert, Protected Variations, Pure Fabrication |
| The caret drawn at a constant width | `TextCaret` (`drawview-ui`) | Pure Fabrication |
| The offset under a point | `TextHitTest` (`drawview-ui`) | Information Expert |
| The touches on the text, the handles, the toolbar of the platform | `TouchSelection` (`drawview-ui`) | Controller, Indirection |
| The presentation independent of the caret | `RichTextPresenter` (`drawview-ui`) | Information Expert |
| Where the caret can stand | `CaretStops` (`drawview-ui`), applied by the field | Information Expert |
| Deleting what is seen | `SeenDeletion` (`drawview-canvas`) | Information Expert, Pure Fabrication |
| ↑ and ↓ through the lines and the objects | `TextNavigation` (`drawview-ui`) | Information Expert |
| The hidden source of what the caret is in | `SourceStrip` (`drawview-ui`) | High Cohesion, Pure Fabrication |
| The caret and the selection in a block of code | `CodeBlockLayout` (`drawview-ui`) | Information Expert, Polymorphism |
| A delimiter committed by a dead key | `SelectionInput`, `TextEditorManager.updateValue` (`drawview-canvas`) | Protected Variations, Controller |
| The controls of the bars never taking the focus | `keepsTextFocus` (`drawview-ui`) | Information Expert |

## What does not change

* **Stored data and the protocol**: a text keeps its source; no migration, no format re-recorded.
* **The page**: the tiles, the export, the thumbnails and `MarkdownText` draw as today; only the
  editor changes. `TextParityTest` stays the reference and is extended through the field.
* **The public API**: unchanged so far (the new classes are internal); a public change, if a later
  phase needs one, is recorded in its commit.

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | Dead keys: the selection kept over a composition, surrounded at its commit; the controls of the bars not taking the focus; tests of the editor (a dead key over a selection, `è` composed over one, its undo) and of the bar pressed with a mouse (code, bold, formula) | Done, not tried on macOS |
| 2 | One layout: the field at 20 px/mm in `PageScale`, the transformation keyed on the presented text, `TextCaret`; tests: the lines written and saved within half a pixel at 48%, 73%, 100%, 160% and 270%, the formulas and the headings in place after a zoom and after a new size, the caret 2 dp at three zooms, `TextParityTest` through the field at several zooms; on the tablet: the text sharp at 30% and 400%, and whether Compose's handles follow the layer | Done, not tried on the tablet |
| 3 | The touches: `TouchSelection`, the handles and the toolbar on touch screens; tests with injected touches (a tap, a long press, the handles where the text is at two zooms, a handle dragged, a turned text); on the tablet with the pen and a finger | Done, not tried on the tablet |
| 4 | The inline notation never revealed: the presenter without `reveal` for delimiters, headings and escapes (the links in phase 5, with their address in the strip), `CaretStops`, `SeenDeletion`; tests: every arrow press over every hidden part moves the caret, the deletions keep the formats, the presented text the same with the caret at every offset | Done |
| 5 | The source strip: formulas always drawn, their source in the strip, the last image of an invalid formula, Backspace after a formula, the addresses of the links, the cells of the tables; tests and screenshots | Done |
| 6 | Blocks of code as objects: the caret and the selection in the block, `TextNavigation`, the fence in the strip; the walks of the starting point as tests (no jump, ↑ through the code); screenshots | Done |
| 7 | Checks: the screenshots recorded again on the Mac, the desktop application on a scratch directory, the tablet with the pen, a finger and a keyboard | Done on Windows and on the tablet (six fixes); the Mac and a real pen left to the user |

Phases 1 and 2 stand alone; 3 needs 2; 4 needs 3 (a tap on a hidden part); 5 needs 4; 6 needs 4 and
5 (the fence in the strip). Each phase is one commit with its status here, after the checks of
`CLAUDE.md`.

### Phase 1 results

* `TextEditorManager` keeps the value a composition started from when it replaced a selection
  (`composedOverSelection`); at the commit, `SelectionInput.surround` is asked about that value, so
  a delimiter committed by a dead key surrounds the selection, a letter composed by it (`è`)
  replaces it, and a composition from a caret surrounds nothing. The field's undo goes back to the
  selection.
* `keepsTextFocus` on the controls of the bars of the texts: the buttons pressed with a mouse format
  the selection (`TextBoxFormatBarTest`: code, bold, formula, in the editor with the mouse of the
  test).
* Not tried on macOS: the values of a dead key are those the field gives (a composition, then its
  commit, the text the same or replaced); ⌥9 on the Mac of the user is to be tried.

### Phase 2 results

* `TextBoxEditor` presents and lays out at `ComposeTextTileAdapter.LAYOUT_PX_PER_MM`, the font size
  and the width of the page; `pageScale` measures the field on that width and draws it scaled by
  the zoom, the layer of the field translated to the exact place of the box. A zoom or a pan
  changes layers only: the presented text no longer depends on the zoom.
* The transformation of the field is remembered with the presented text, so a new size, color or
  formula engine reaches the field with the same value.
* The room of a formula comes from one place, `FormulaImages.formula`, for the page and the field;
  the field draws its images rendered again at their size on the screen (steps of 8%), as the
  tiles do. `renderAtBucket` is gone.
* `TextCaret` draws the caret, 2 dp wide on the screen, blinking every 500 ms and shown again at
  each move, in the text and in the cell of a table; the brush of the field is transparent. The
  outline of the cell of the caret keeps its width on the screen too.
* Tests (`TextBoxFidelityTest`): the baselines of the field are those of the page within half a
  pixel of the layout at 48%, 73%, 100%, 160% and 270%, and so is its height; after a zoom and after
  a new size while written, with MicroTeX (without the transformation keyed on the presented text,
  the lines stay at the old size: 7.5 mm instead of 8.6 mm for the first one); the caret 1 to 3 px
  wide at 40%, 100% and 300%. The tests of the explorer and of `drawview-ui` pass, but for
  `TextHeightTest`, which fails on this computer on main too.
* Not done on this computer: the screenshots, recorded on the Mac (the field is now at the exact
  place of the box, the glyphs may move by a fraction of a pixel); the tablet (the sharpness of the
  scaled text, the handles of Compose under the layer).

### Phase 3 results

* `TouchSelection` (`drawview-ui`): the touches of a finger and of a pen on the field, taken at the
  first pass after the gestures of the canvas: a tap puts the caret on the character tapped and
  its handle under it (a tap on the caret opens the toolbar or closes it), two taps select a word,
  a long press selects a word then follows the finger, a pen dragged selects; a finger taken by the
  canvas (a pan, a pinch) is left to it. A long press is told by a timeout and by the time of the
  events, as the canvas tells it; a selection follows the pointer only once it moved, not on a move
  of the content under it.
* `TouchHandles`: the drop of the caret and the two of the selection, 22 dp on the screen in the
  color of the selection, hanging from the bottom of their caret where the text is (its place, its
  rotation, the zoom); dragged, the finger is taken where the handle is placed, not on the handle,
  which moves with the caret under it. They go when the value changes by another way (keys, a mouse,
  the bar).
* The toolbar of the platform (`LocalTextToolbar`: copy, cut, paste, with the formatted text of the
  clipboard as the field pastes it, select all) over the selection, after a long press, two taps or
  the end of a drag; none on the desktop, which has no such toolbar.
* The click on a block of code stays for the mouse only; a touch on one goes through
  `TouchSelection`, with the same offset.
* Tests (`TextBoxTouchTest`, injected touches): a tap at 60% and at 200% puts the caret on the
  character and its handle under it within 3 px; a long press selects the word, its handles at its
  ends; the end handle dragged two words further; a tap on a text turned by 20°. The gestures over
  the field (`TextBoxGesturesOverFieldTest`) are unchanged. A pen cannot be injected by the tests of
  the desktop: its drag is to be tried on the tablet.

### Phase 4 results

* `RichTextPresenter` reveals no delimiter of the emphasis, of the code in the line, of an escape,
  nor the `#` of a heading: the text shown is the same with the caret at every offset. The address of
  a link is still shown with the caret in the link, and the formulas and the blocks of code keep
  their source under the caret, until phases 5 and 6.
* `SourceMapping` tells where the caret may not stand (the markers of the list items, the quotes and
  the headings, between an escape and what it escapes) and which offsets are inside an object.
* `CaretStops` (`drawview-ui`), applied by the field to its keys, a mouse, the touches and the
  handles: a move shown at the same place goes on in its direction; a caret takes the offset inside
  the format on its left, a selection the offsets of what it shows (a word selected holds no
  delimiter: the bar takes the format off it). Inside an object the offsets are left as they are.
* `SeenDeletion` (`drawview-canvas`): Backspace after `**bold**` takes the `d`; Delete before it the
  `b`; a selection across the end of a format keeps the delimiters of the part left; the last
  character of a span, of an escape, of nested spans, takes their delimiters with it.
  `handleConcealedDeletion` makes a heading text again with Backspace at the start of its text.
* Tests: `CaretStopsTest` (the arrows across the delimiters, the caret put at the edges, a word and a
  selection by Shift, the markers, an escape, the objects), `SeenDeletionTest`,
  `RichTextPresenterTest` (nothing revealed at any offset, the places of the caret),
  `TextParityTest` (a heading never shows its `#`), and in the editor with its keys
  (`TextBoxNotationTest`): across bold, code, strikethrough, italic and an escape, → and ← take one
  press per character seen, the text shown and the height of the field the same at every press;
  Backspace after a bold takes its last letter.

### Phase 5 results

* `RichTextPresenter` draws a formula as the page does with the caret in it too, and hides the
  address of a link with the caret in the link; it tells the editor what the caret is in
  (`HiddenSource`: the LaTeX of a formula, the address of a link, in the text or in a cell of a
  table). The offsets of a formula and of the address of a link keep the caret's own moves
  (`SourceMapping.insideObject`): the arrows go into the source, through it and out of it.
* `SourceStrip`: the source in the monospace of the notes, above what it belongs to, below it when
  the formatting bar or the edge of the window is there; the caret (blinking), the selection and the
  composition of the field drawn in it, a tap in it putting the caret on the character tapped. The
  caret of the text is not drawn while it is in the strip; the caret followed by `CaretReveal` is the
  formula then. The preview of the rendered formula above the caret is gone: the formula in its
  place is the preview.
* A formula being written keeps its last image while its source cannot be rendered, "Invalid
  formula" under the source in the strip; a formula that cannot be rendered at all is red source,
  as on the page.
* A click or a tap on a formula puts the caret at the end of its source. Backspace right after a
  formula goes into it, at the start of its source out of it (`handleConcealedDeletion`); a formula
  emptied goes with its `$`, a link whose text goes goes with its address, and in the address of a
  link the characters go as they are (`SeenDeletion`).
* Tests (`TextBoxSourceStripTest`, with MicroTeX): the caret in a long formula changes neither the
  text shown nor the height of the field, the source in the strip; the arrows into the source,
  through it, out of it and back; Backspace after a formula; a click on it; the caret in a link,
  its address in the strip and not in the line. `RichTextPresenterTest` and `SeenDeletionTest`
  for the rules.

### Phase 6 results

* `RichTextPresenter` keeps every block of code an object, with the caret in it too: the code
  written as text in the field, its struts, its chrome drawn around it and its fences faded are gone
  (`WrittenCodeBlock`, `codeSources`). The caret on the line of the opening fence gives it to the
  strip (`HiddenSource` of kind `CODE_BLOCK`). `PresentedCodeBlock` tells the ends of the code and
  of the block.
* `CodeBlockLayout` draws the caret and the selection in its code (`cursorRect`, `selectionPath`),
  and tells its lines (`lineOf`, `offsetOnLine`) and its label (`labelRect`).
* `CodeBlockCaret`: ← and → step over the fences (from the line before into the first character of
  the code, from the last one onto the line after, and back); ↑ and ↓ go through the lines of the
  code as the block lays them out, into a block from the line above or below and out of it at the
  place of the caret; Home and End go to the ends of the line of the code. On the opening fence,
  opened by a click on the label or by Backspace at the start of the code, the caret moves as in
  any text, in the strip.
* A click on a block puts the caret on the character, a drag from it selects its code.
* Tests (`TextBoxCodeBlockTest`, the walks of the starting point with the keys of the editor): → from
  the line before goes into the code, never onto a fence, on to the line after, every press moving;
  ← back the same; ↑ from the line below goes through both lines of the code and on to the line
  above; ↓ through them from the line above; the text shown and the height of the field the same at
  every press; Backspace at the start of the code opens the fence in the strip; a drag selects a part
  of the code. `EditorCodeTest` and `TextParityTest`: a block the same with the caret anywhere, the
  caret at the column of its code.

### Phase 7 results

* On Windows, after every phase: `jvmTest`, `checkKotlinAbi`, `compileAndroidMain` and both Android
  applications assembled. The failures are those of main on this computer (as listed for this computer: the
  iOS dumps of the ABI, `TextHeightTest`, the trackpad, the lectures, the export), and the JVM and
  Android dumps of `drawview-ui` miss two strings of main (`common_action_paste_plain`,
  `paste_equation_placeholder`), not from this work; no public API changed.
* The screenshots of the texts of the explorer rendered and looked at (they are recorded on the Mac,
  and differ here in every glyph): the field at the place of the page, the strip of an invalid
  formula below it instead of a line wrapped by its source, a text turned as before (its caret, past
  the top of its frame, is so in the reference too).
* PencilNotes desktop started twice on a scratch directory (after phase 2 and at the end), without
  errors; not driven by hand.
* **On the tablet** (a Huawei MatePad, Android 10, SwiftKey), on a note made for it, driven over
  adb: the touches by `input` and by `monkey` scripts (a double tap, a long press, a handle
  dragged), the keys by a keyboard made with `hid` (Ctrl+= and Ctrl+−, the arrows, the text).
  * The lines written and saved at 100%: 12 pixels of about 11 800 differ; at 125% the lines at the
    same place to the pixel (the antialiasing differs, the glyphs sharp in both).
  * A zoom while writing, a formula in the text: in place, as saved.
  * A tap, two taps, a long press, a handle dragged, the toolbar of the system, the bar's code over
    a selection by touch: as on the desktop, after the fixes below.
  * A formula: the caret going into it with the arrows or a tap, nothing moving, its source written
    in the strip with the keyboard of the system, its image again at every key.
  * A block of code written, its fences closed by the editor, ↑ through its lines.
  * Fixed on the tablet:
    1. The pointers of `input` and of other automations have no type (`PointerType.Unknown`): they
       went to the field, its handles misplaced. Every pointer but a mouse is now the editor's, a
       pen's eraser a pen.
    2. The keyboards of Android compose again the word where the caret is put: the composition
       changing alone hid the handles and the toolbar of a selection just made. Now only the text
       and the selection count.
    3. The caret moved by the editor (in a block of code) kept the composition of the word it left;
       SwiftKey then moved the caret again. A move of the editor ends the composition, as the
       field's own moves.
    4. Out of a block of code ending the text (→ at the end of its code, ↓ on its last line) nothing
       could be written after it, nor before one starting it: the line is added (decision 14).
    5. The handle of the caret stood at the start of a formula while the caret was in the strip:
       no handle then.
    6. A finger and the keyboard of the screen had no arrows to add the line of fix 4: a tap
       just past the box adds it (decision 15).
  * Seen, not from this work: SwiftKey puts the caret at the end of a word tapped when it composes
    another one, in every field of Compose (the search of the library too); Ctrl+Home and Ctrl+End
    do nothing in a field on Android; the text of a box being written is lost when the application
    is killed (an install over it here).
* Left to the user:
  * **On the Mac:** ⌥9 over a selection (phase 1), and `./gradlew verifyRoborazziJvm` of explorer,
    ui, sync-ui, lecture-ui and export-ui: the screenshots of the texts written change (the field
    at the exact place of the box, the caret of the editor, the strip, the blocks of code with the
    caret in them), to look at and record again (`recordRoborazziJvm`); the iOS compilation and
    the tests of the iOS simulator, which this computer cannot run.
  * **On the tablet**, by hand: the pen dragged selecting (adb gives no pen), a pinch while writing
    (adb gives one finger), and a real finger.

## Decisions

Approved by the user on 2026-11-05 ("approvo il piano"), with the plan:

1. **The delimiters are never shown while writing** (proposed): bold is bold, the bar shows which
   formats are on at the caret, Backspace deletes what is seen. The alternative keeps them visible
   near the caret without taking room (drawn over the text, small), which still covers the letters
   around them.
2. **The source of a formula in a strip by the formula** (proposed), or in a row of the formatting
   bar: the bar is always in the same place and never covers the text, but far from a formula at
   the bottom of a large box.
3. **The arrows go into a formula** (proposed): its source in the strip, character by character,
   ↑ and ↓ leaving it; as today, without anything moving. The alternative steps over a formula as
   one character, a tap or Enter editing it: faster across a line full of formulas, one more way
   of editing to learn.
4. **The keyboard of the backtick**: macOS, ⌥9 (said by the user).

Taken alone, revertible:

5. **The resolution of the page, 20 px/mm**, for the field too: the one where the page lays out,
   measures and exports.
6. **The caret drawn by the editor**, 2 dp, blinking as Compose's.
7. **The editor owns the touches on the text, the field keeps the mouse** (changed in phase 3:
   proposed first for every pointer). The handles are what needs the editor; the field selects
   well with a mouse through the layer, and a click or a drag on an object is taken by the editor
   where it lands. A pen is a touch: Compose shows handles for it.
8. **Typing at the edge of a format goes on in the format on its left**, as in the word processors;
   right after a closing delimiter just typed, outside it.
9. **A `>` alone being typed** stays the one case where the caret changes the layout.
10. **An invalid formula being written keeps its last image**, so that its line does not change at
    every key; red source once the caret leaves it, as on the page.
11. **The fence of a block is edited in the strip**, opened from the label of the block or by
    Backspace at the start of its code.
12. **The controls of the bars are not reached with Tab**: in the field Tab indents, and the
    commands have their shortcuts.
13. **A pointer of no known type is a finger** (phase 7): automations and accessibility services
    send such pointers, and the field would misplace its handles for them.
14. **The arrows out of a block of code at an end of the text add the line** that is not there
    (phase 7), as Typora does: → or ↓ out of a block ending the text, ← or ↑ out of one starting it.
15. **A tap just past a box being written by such a block adds the line too** (phase 7, asked by
    the user: "Sì, aggiungilo"), as a tap below the last block in Notion: within 40 dp below a box
    ending with a closed block, or above one starting with it, and within its width; further away
    the tap ends the writing as before. Not Enter twice at the end of the code, which would take the
    empty lines written between the parts of a code. Above a box the formatting bar may be there
    (on the desktop, by the frame): there the arrows add the line.

## Risks

* **Handles and popups on Android and iOS.** Compose places the handles of a field from its layout,
  without the layers above it (read in its sources): scaled, they would be off by the zoom, and
  today they are already off by the rotation of a turned text. Phase 3 draws them in the editor;
  to see on the tablet: no handle of Compose shows, the toolbar of the system opens over the
  selection.
* **Scaled text on Android.** The text drawn in a scaled layer is expected to be rasterized at its
  size on the screen, as on the desktop; to see on the tablet at small and large zooms.
* **Input methods.** The rectangle of the caret they are given should go through the layers; a
  composition in a formula is drawn in the strip. To try on the tablet with its keyboards.
* **The gestures of the desktop**: double and triple clicks, Shift and a click, a drag past the edge
  of the window; each one gets a test.
* **A large layout**: an A4 page at 20 px/mm is 4200 × 5940 px, drawn as a list of commands, not as
  a bitmap; laid out once per change instead of once per zoom.

## Left for later

* The edges of the glyphs saved, rasterized at a level of detail, against the field drawn at the
  pixels of the screen ([tile-sharpness.md](tile-sharpness.md)).
* A format chosen at the caret before typing: ⌘B with no selection writes `****` today, shown until
  a character is typed in it.
* A view of the whole source of a text on demand.
* Scribble of the Apple Pencil in the field ([text-editing.md](text-editing.md)).
* The strip covers the line next to what it belongs to (above or below it); a place in the bar, on
  small screens, if it gets in the way.
* A selection drawn by a mouse from the text into a block of code, and Shift and the arrows across a
  block: the field selects the block whole from outside it.
