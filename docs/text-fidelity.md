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
Android and iOS, drawn in popups, may not follow the layer (to be seen on the tablet, see
[Risks](#risks)); neither knows the objects of the text.

* `TextCaret` draws the caret: 2 dp on the screen at every zoom, blinking as Compose's (solid while
  typing or moving), wherever the caret is: in the text, in a cell of a table, in a block of code,
  in the strip. The brush of the field is transparent.
* `TextHitTest` tells the offset of the source under a point of the field: in the text, in a cell
  of a table, in a block of code, on a formula. It gathers `codeOffsetAt` and the taps on the cells.
* `TextBoxGestures` takes every pointer on the text (mouse, pen, finger), after
  `canvasGesturesOverField` keeps the pan and the pinch: a tap puts the caret, two select a word,
  three a line, a drag selects (also into and out of a block of code), Shift and a click extends;
  on a touch screen a long press selects a word and opens the text toolbar of the platform
  (`LocalTextToolbar`), and the handles of the caret and of the selection are drawn at their size
  on the screen, outside the layer, and dragged. The field's own pointer input gets nothing (taken
  at the first pass), so its handles never show; the secondary button keeps its context menu.

`TextBoxGestures` is the controller of the pointers on the text, calling
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
  a list item, a quote or a heading. The source of a formula is made of stops (decision 3).
  `TextEditorManager.updateValue` asks them through a port the field gives it
  (`TextEditorManager.caretStops`), which replaces `keepCaretOutOfMarkers`.
* Deletion of what is seen: `InlineFormatting.delete` (`drawview-canvas`) takes the characters
  shown in a range and keeps the delimiters of a span unless its whole content goes, with it then.
  Backspace and Delete next to a hidden delimiter take the shown character beyond it. Applied by
  `updateValue` to Backspace, Delete, a cut, and a selection typed over. The markers keep their
  rules (`handleConcealedDeletion`). Backspace right after a formula, or at the start of the code
  of a block, goes into it: the strip shows its end.
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
| The pointers on the text, the handles, the toolbar of the platform | `TextBoxGestures` (`drawview-ui`) | Controller, Indirection |
| The presentation independent of the caret | `RichTextPresenter` (`drawview-ui`) | Information Expert |
| Where the caret can stand | `CaretStops` (`drawview-ui`), through `TextEditorManager.caretStops` | Information Expert, Indirection |
| Deleting what is seen | `InlineFormatting.delete` (`drawview-canvas`) | Information Expert |
| ↑ and ↓ through the lines and the objects | `TextNavigation` (`drawview-ui`) | Information Expert |
| The hidden source of what the caret is in | `SourceStrip` (`drawview-ui`) | High Cohesion, Pure Fabrication |
| The caret and the selection in a block of code | `CodeBlockLayout` (`drawview-ui`) | Information Expert, Polymorphism |
| A delimiter committed by a dead key | `SelectionInput`, `TextEditorManager.updateValue` (`drawview-canvas`) | Protected Variations, Controller |
| The controls of the bars never taking the focus | `keepsTextFocus` (`drawview-ui`) | Information Expert |

## What does not change

* **Stored data and the protocol**: a text keeps its source; no migration, no format re-recorded.
* **The page**: the tiles, the export, the thumbnails and `MarkdownText` draw as today; only the
  editor changes. `TextParityTest` stays the reference and is extended through the field.
* **The public API**: `drawview-canvas` changes (`TextEditorManager.caretStops`,
  `InlineFormatting.delete`, `keepCaretOutOfMarkers` removed), recorded in the commits;
  `drawview-ui` keeps its public API (the new classes are internal).

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | Dead keys: the selection kept over a composition, surrounded at its commit; the controls of the bars not taking the focus; tests of the editor (a dead key over a selection, `è` composed over one, its undo) and of the bar pressed with a mouse (code, bold, formula) | Done, not tried on macOS |
| 2 | One layout: the field at 20 px/mm in `PageScale`, the transformation keyed on the presented text, `TextCaret`; tests: the lines written and saved within half a pixel at 48%, 73%, 100%, 160% and 270%, the formulas and the headings in place after a zoom and after a new size, the caret 2 dp at three zooms, `TextParityTest` through the field at several zooms; on the tablet: the text sharp at 30% and 400%, and whether Compose's handles follow the layer | |
| 3 | The pointers: `TextHitTest`, `TextBoxGestures`, the handles and the toolbar on touch screens; tests with an injected mouse (click, double and triple click, Shift, drag) and touch (tap, long press, the handles dragged, a turned text); on the tablet with the pen and a finger | |
| 4 | The inline notation never revealed: the presenter without `reveal` for delimiters, headings, escapes and links, `CaretStops`, `InlineFormatting.delete`; tests: every arrow press over every hidden part moves the caret, the deletions keep the formats, `TextParityTest` with the caret at every offset (no line moves) | |
| 5 | The source strip: formulas always drawn, their source in the strip, the last image of an invalid formula, Backspace after a formula, the addresses of the links, the cells of the tables; tests and screenshots | |
| 6 | Blocks of code as objects: the caret and the selection in the block, `TextNavigation`, the fence in the strip; the walks of the starting point as tests (no jump, ↑ through the code); screenshots | |
| 7 | Checks: the screenshots recorded again on the Mac, the desktop application on a scratch directory, the tablet with the pen, a finger and a keyboard | |

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
7. **One owner of every pointer on the text**, mouse included: a drag into a block of code and a
   tap on a hidden part need the layouts of the objects, which the field does not know.
8. **Typing at the edge of a format goes on in the format on its left**, as in the word processors;
   right after a closing delimiter just typed, outside it.
9. **A `>` alone being typed** stays the one case where the caret changes the layout.
10. **An invalid formula being written keeps its last image**, so that its line does not change at
    every key; red source once the caret leaves it, as on the page.
11. **The fence of a block is edited in the strip**, opened from the label of the block or by
    Backspace at the start of its code.
12. **The controls of the bars are not reached with Tab**: in the field Tab indents, and the
    commands have their shortcuts.

## Risks

* **Handles and popups on Android and iOS.** Compose draws the handles of a field in popups placed
  from its layout; if they do not follow the layers above the field they would be off by the zoom
  (and today already by the rotation of a turned text). Phase 2 looks at it on the tablet; phase 3
  replaces them in any case, for the objects.
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
