# Text editing

The texts of the pages: writing a text where the user taps, formatting it (Markdown, formulas,
size, color, alignment), moving and sizing its box, selecting it with the lasso, drawing and
exporting it. This document starts from an analysis of the code as it is (2026-10-08): how it
fits the GRASP principles and the experience it gives, then the design and the plan that fix
it.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Creating.** With the text tool (`Tool.TEXT`, key T) a tap ends in
   `CanvasGestureHandler.onTextToolTap`: a text under the tap is opened (`editTextAt`), otherwise
   `TextEditorManager.startEditing(null, page, x, y, width)` is called with the tap as the
   **top-left corner** of the box and a width of 25–70 mm. A double tap with any tool opens the
   text under it; a drag with the text tool pans the canvas.
2. **Session.** `TextEditorManager` (`drawview-core`) keeps the box in page millimeters (x, y,
   width), two flags (`isMathMode`, `isMarkdownLivePreviewEnabled`) and hides the edited text
   from the tiles (`isDragging`).
3. **Editor.** `TextEditorOverlay` (`drawview-ui`, 676 lines) draws a Compose `BasicTextField`
   over the canvas at the box, scaled with the zoom. It owns the text, its size, color and
   alignment (local state of the composable). It has its own frame (1 dp border, 4 dp padding, two
   12 × 28 dp pills for the width, a round move handle above), the Markdown live preview
   (`MarkdownTextUtil.toLiveAnnotatedString`: delimiters concealed unless the caret touches them,
   formulas shown as blue italic source), and a bar above the keyboard: size stepper and slider
   10–60 pt, color, M↓, bold, italic, strikethrough, code, heading, alignment (three glyphs cycled),
   Σ (inserts `$ $` and shows a bar of 14 LaTeX symbols), save; above it a "Formula" row rendering
   the **whole text** with MicroTeX.
4. **Saving.** `saveAndFinish` decides that the text is a LaTeX block when "math mode" is on or the
   text starts with `$$`, measures the height (the layout of the field, or `latexBlockHeightMm`)
   and calls `finishTextEditing(text, isLatex, color, fontSize, height, alignment)`, which submits
   `AddElements`, `UpdateTexts` or `RemoveElements` (blank text) to the `DocumentSession`. Because
   the content lives in the composable, the manager asks the UI to save through a callback
   (`finishEditingRequest`). The draft goes to the collaborators at every change (`reportDraft`).
5. **Drawing.** `ComposeTextTileAdapter` (the `TextTileAdapter` port of the core): a LaTeX block is
   one MicroTeX image scaled to the width of the box (its height rewritten while drawing); any
   other text goes through `MarkdownTextUtil.toCanvasText` (delimiters removed, formulas as
   placeholders) and `TextMeasurer` with `Constraints(maxWidth = width)`.
6. **Selection.** The lasso selects texts too: `SelectionOverlay` draws its frame (6 px padding,
   2.5 px stroke, corner circles, rotation knob, 20 × 48 px pills for the width of a single text,
   all in pixels) and `SelectionActionBar` shows cut, copy, delete, edit, color and a size stepper
   (8–72 pt by 2). The pills change the width only; the corners multiply the font size.
7. **Elsewhere.** The PDF export lays the texts out with `TextTileAdapter.layout`; the lecture
   assistant (`NoteInserter.measureHeightMm`) measures its notes with its own copy of the layout;
   the settings of the tool (`TextToolSettings`) hold the default size (slider 10–48), the default
   color and two switches, "Markdown" and "Math mode".

## Analysis

### GRASP

The design respects GRASP only in part: the ports (`TextTileAdapter`, `LatexTileAdapter`) and the
changes (`TextData`, `UpdateTexts`) are sound, the knowledge of the text is not.

| Principle | Where it holds | Where it does not |
|---|---|---|
| Information Expert | The model knows its geometry (`containsPoint`, rotated bounds); the adapter knows how a text is laid out for the tiles and the export | The layout of a text (wrapping, height, formulas) is computed in four places: the text field, `latexBlockHeightMm`, the adapter, `NoteInserter` (a copy). The height is stored and goes stale (see F3). "Is it a formula?" has three different rules (saving, preview, draft). The size limits are written in five places with four ranges. The alignment is an `Int` 0/1/2 mapped to `TextAlign` twice. Where a new text goes is decided by the gesture router |
| Creator | `TextData.toElement()`; the managers create the changes | — |
| Controller | `TextEditorManager` is the controller of the use case | It controls the box only: the content and the style live in the composable, so the controller calls the UI back (`finishEditingRequest`) and receives six parameters at the end. The formatting actions are not `Command`s, unlike every other action of the editor since the adaptive interface (no shortcuts, no menu, no tooltips) |
| High Cohesion | Notation (`MarkdownTextUtil`) apart from the screens | `TextEditorOverlay` mixes placing, handles, the bar, color, size, the symbol bar, the formula preview, keyboard avoidance and drafts. `MarkdownTextUtil` is a parser, two presenters and the editing helpers. The editor bar and the lasso bar duplicate the formatting of the texts |
| Low Coupling | The core does not know the Compose text | The editor and the adapter call the `LatexEngine` singleton directly; the export special-cases the LaTeX blocks (`PdfExporter.writeImage` draws them on a 1 × 1 canvas to get their height set) |
| Polymorphism | — | The kinds of text (plain, Markdown, LaTeX block) are flags (`isLatex`, `isMarkdownEnabled`) tested with `if` in the adapter, the editor and the export |
| Pure Fabrication, Indirection | The ports of the renderers | No shared drawing of the frames (each overlay its own style); no snapping service |
| Protected Variations | Fonts behind `NotesFonts`, MicroTeX behind `LatexPlatformEngine` | The editor relies on the field laying out like the canvas: true for plain text (same font and size), false for the formulas (sources in the editor, images on the canvas) and for the alignment (see R7) |

### Experience

It is not the best experience possible: some basics fail (alignment, caret, frame), the formulas
work against the user, and text can be lost.

#### Reported

| # | Problem | Cause |
|---|---|---|
| R1 | The symbol bar of the formulas is useless | `MATH_SYMBOLS` (`TextEditorOverlay.kt:661`), shown while Σ is on: 14 fixed templates, slower than typing them, taking a row of the screen |
| R2 | The whole text is treated as LaTeX | The preview renders the whole field as one formula whenever it contains `$` or Σ is on (`TextEditorOverlay.kt:188`); saving turns the text into a LaTeX block when it starts with `$$` (`:206`), so `$$a$$ and b` becomes an error badge. The editor never renders the inline formulas: only the canvas does, after saving |
| R3 | The lasso and the editor frame differently | Editor: Compose border 1 dp, padding 4 dp, pills 12 × 28 dp, move knob (`TextEditorOverlay.kt:243–359`). Lasso: path 2.5 px, padding 6 px, circles of 12 px, pills 20 × 48 px (`SelectionOverlay.kt:137–185`) in raw pixels, so on a 3× phone its handles are a third of the editor ones and the touch radius of 36 px is 12 dp (below the 44–48 dp of the guidelines) |
| R4 | The caret does not appear where the user taps | The tap is passed as the top-left corner of the box (`CanvasGestureHandler.kt:756–758`): the caret starts there and goes down by a line. Opening an existing text puts the caret at its end (`TextEditorOverlay.kt:142`), not at the tapped character |
| R5 | The range of the sizes is narrow | Four ranges: tool settings 10–48 (`EditorToolbar.kt:804`), editor slider 10–60 (`TextEditorOverlay.kt:555`), stepper 8–72 by 2 (`TextColorComponents.kt:197, 225`), selection 8–72 (`SelectionManager.kt:526`); the corners of the selection scale without limits |
| R6 | No snapping | Nothing exists: the elements are placed by hand only |
| R7 | The alignment does not work | `TextMeasurer` measured with `Constraints(maxWidth = width)` (`ComposeTextTileAdapter.kt:310`) lays the text out at the width of its longest line (`TextMeasurer` takes the intrinsic width when min and max differ): center and end only move the shorter lines inside it, a single line stays on the left. The field of the editor has a fixed width, so the text looks aligned while typed and jumps when saved. The PDF export (`layout`) and `NoteInserter` measure the same way. The button cycles ⇤ ≡ ⇥ without names |

#### Found

| # | Problem | Cause |
|---|---|---|
| F1 | **The text being written is lost** | Turning the drawing mode off, opening the page grid or choosing another tool calls `cancelTextEditing` (`DrawViewController.kt:204, 217, 228`); the drawing mode and page grid commands stay enabled while writing |
| F2 | The "Markdown" and "Math mode" switches of the tool do nothing | `startEditing` overwrites both flags at every new text (`TextEditorManager.kt:72–73`) |
| F3 | The height of a text goes stale | The width pills do not measure the text again (`SelectionManager.resizeText`); the size of the selection bar scales the height by proportion although the lines wrap again (`:528`); a LaTeX block rewrites `height` while it is drawn on the tile threads (`ComposeTextTileAdapter.kt:367`), outside any change (not synchronized, not undoable). The frame, the hit test and the lasso then use a wrong box |
| F4 | A formula alone follows the width, not the size | A LaTeX block is scaled to the width of its box; its font size only changes the resolution |
| F5 | `$` in plain prose starts a formula | `costs $5 and $10` renders `5 and ` as a formula: the inline rule (`MarkdownTextUtil.kt:400`) lacks the Pandoc conditions (no space after the opening `$`, none before the closing one, no digit right after it) and there is no `\$` |
| F6 | `_` inside words makes italics | `file_name_here`, `x_1 and y_2` (`MarkdownTextUtil.kt:405`): CommonMark forbids `_` emphasis inside words |
| F7 | `$$…$$` is drawn inline | A display formula should stand on its own line, centered |
| F8 | The heading button works only at the start of a line | It inserts `# ` at the caret (`TextEditorOverlay.kt:467`), and a heading needs it at the start of the line |
| F9 | A rotated text is edited straight | The editor ignores the rotation: the text jumps when the editor opens and closes |
| F10 | No shortcuts, menu or names for the formatting | No ⌘B / ⌘I…, no Format menu on the desktop, no tooltips; the color swatch and the alignment have no accessible name |
| F11 | The bar is far from the text on large screens | It is always at the bottom of the window: right above the keyboard of a phone, far from the box on a tablet with a keyboard or a desktop |
| F12 | Two formatting interfaces | The lasso bar and the editor bar differ (no alignment for the selection, different size controls) |
| F13 | Every new box is 25–70 mm wide | Whatever is typed; near the right edge it leaves the page (25 mm minimum); a box cannot be drawn with a drag |
| F14 | Two taps to move to another text | While writing, a tap on another text only saves; a second tap opens it |
| F15 | No text from other apps | ⌘V pastes only the elements copied in the editor; a copied text box does not reach the clipboard of the system either |
| F16 | Preview cost | The formula preview is rendered natively on the main thread at every key |

## Design

### The experience

* **What you see is what you get**: the editor shows the text as the page will (formatting,
  formulas, alignment); saving changes nothing visible.
* **The caret goes where the user taps**, on an empty spot and inside a text.
* **One frame** for whatever is selected or edited, the same size on every screen.
* **No modes**: no "math mode", no LaTeX block; a formula is written where it belongs in the text.
* **One formatting bar**, for the text being written and for the texts selected, near them.

### The content of a text

* A text is Markdown with formulas: `$…$` inline, `$$…$$` on its own line, centered. A text made
  of a formula only is a text with one `$$…$$`. `isLatex` leaves `DrawText`, `TextData`, the Room
  entity and the synchronization format (no migration and no `PROTOCOL_VERSION` before the first
  release); the formulas always follow the font size.
* The notation follows Pandoc for `$` (and `\$` writes a dollar) and CommonMark for `_` (not inside
  words); `\*`, `\_`, `` \` `` escape the other delimiters.
* `isMarkdownEnabled` is removed (see [Decisions](#decisions)): with the escapes and the stricter
  rules a literal `*` or `$` is written directly, and the flag only added a mode.
* The alignment is an `enum TextAlignment { START, CENTER, END }`.
* The width is either automatic (a text created by a tap: it grows with the text up to the
  margin of the page, then wraps) or fixed (set by a drag or by the width handles): `autoWidth`.
* The height stays stored (for the frame, the hit test and the devices without the fonts) but
  only the layout expert produces it, whenever the content, the width or the size changes.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The notation: spans of Markdown, inline and display formulas, escapes, rules of `$` and `_` | `RichTextParser` → `RichText` (`drawview-core`, `core.text`: pure Kotlin), replacing the parsing of `MarkdownTextUtil` | Information Expert, Pure Fabrication |
| Present the notation: the field of the editor (concealed delimiters, concealed formula sources), the canvas (delimiters removed, formula placeholders) | `RichTextPresenter` (`drawview-ui`), from `MarkdownTextUtil` | High Cohesion |
| Lay a text out: lines, words, formulas, height, automatic width; the metrics of a size (height of a line, first baseline) | `TextTileAdapter.measure(text)` and `metrics(fontSize)` added to the port; `ComposeTextTileAdapter` measuring with `Constraints.fixedWidth`; `NoteInserter` using it | Information Expert, Protected Variations |
| The values of the style: range and presets of the sizes, alignment, defaults (size, color, alignment) | `TextSizes`, `TextAlignment`, `TextDefaults` (`drawview-core`; the defaults in `DrawViewController.textDefaults` and `DrawPreferences`, persisted) | Information Expert |
| Use case "write a text": start at a point or in a text at a character, the draft (content, style, box), move, resize, commit (measure, submit the change, report the draft), cancel | `TextEditorManager` owning the draft `TextData` (no callback to the UI, one commit) | Controller, Information Expert |
| Where a new text goes: caret at the tap, automatic width, inside the page, first baseline on the line of the paper when snapping | `TextEditorManager.startNewText(page, pointMm)` with `metrics` and `SnapEngine` | Information Expert |
| The field over the canvas: caret, selection, input method, rotation, formulas drawn over their concealed source | `TextBoxEditor` (`drawview-ui`) | High Cohesion, Pure Fabrication |
| The formula under the caret: rendered above it, with the error when invalid, off the main thread | `FormulaPopover` (`drawview-ui`) | High Cohesion |
| The frame of the selection and of the edited text: geometry, style in dp, handles, hit test | `SelectionFrame`, `FrameStyle`, `SelectionOverlay.drawFrame` (`drawview-core`), drawn by the canvas for both | Information Expert, High Cohesion |
| The gestures on the handles of the edited text, as on the selection | `CanvasGestureHandler` (modes of the editor) → `TextEditorManager.moveBy`, `resizeWidth` | Controller |
| The formatting actions: bold, italic, strikethrough, code, heading, formula, alignment, size, color | the format commands of `EditorCommands` (`drawview-ui`: shortcuts, the Format menu, tooltips) acting on `DrawViewController.textFormatTarget`: a `TextFormatTarget` (`drawview-core`), the text being written (`TextEditorManager`) or the texts selected (`SelectionManager.selectedTexts`) | Controller, Polymorphism (target) |
| The formatting bar, one for both targets, placed by the window: above the keyboard on a phone, floating by the box on a tablet or a desktop | `TextFormatBar` (`drawview-ui`) with `AdaptiveInfo` | High Cohesion, Low Coupling |
| The size control: steps on the presets, a typed value, the whole range | `FontSizeControl` (`drawview-ui`) with `TextSizes` | Information Expert |
| Snapping: the candidates (edges, margins and center of the page, edges and centers of the other elements, lines of the background), the threshold in screen pixels, the result and its guides | `SnapEngine` (`drawview-core`); the lines from `PageBackground.pattern`; guides drawn by `SnapGuidesOverlay` | Pure Fabrication, Indirection, Information Expert |
| Text from the clipboard of the system as a new text; a selected text copied as plain text too | `EditorCommands.paste` / `copy` → `TextEditorManager.insertText`, `SelectionManager` | Controller |

### Interactions

* **New text.** A tap with the text tool on an empty spot puts the caret on the tap: the left edge
  of the box at its x, the first line centered on its y (on lined paper, with snapping, the first
  baseline on the nearest line). The box has an automatic width. A drag with a stylus or a mouse
  draws a box of fixed width; a finger in stylus-only mode still pans.
* **Existing text.** A tap with the text tool (or a double tap with any tool) opens it with the
  caret on the tapped character. While writing, a tap on another text saves and opens it at once;
  a tap on an empty spot saves only.
* **Leaving.** Every way out saves: back, Escape, a tap outside, another tool, the drawing mode,
  the page grid. Only an explicit cancel (none today) discards.
* **Frame.** The same frame for the lasso and the editor, in dp, handles reachable by a finger;
  the edited text keeps its rotation and shows the width handles, and its frame border moves it;
  rotation and scale belong to the selection. The frames of the collaborators stay dashed with
  their name (another meaning).
* **Formulas.** Σ (and a shortcut) wraps the selection in `$…$`; `$$` on its own line starts a
  display formula. In the editor the formulas away from the caret are drawn as on the page, over
  their concealed source; the formula under the caret shows its source, and a popover above it
  renders it with the error, if any. No symbol bar, no preview of the whole text.
* **Sizes.** One control everywhere: − / + step through presets (6 8 9 10 11 12 14 16 18 20 24 28
  32 36 40 48 56 64 72 96 120 144 192 288), the value can be typed; range 4–288 pt, the corners
  of the selection clamped to it.
* **Alignment.** Three icons (left, center, right) with names and shortcuts, in the editor and for
  the selection; the alignment last used becomes the default, like the size and the color.

### Snapping

* While moving a selection, the edited text or an image being placed, and while dragging the width
  handles, the box snaps to: the edges and the center of the page, its margins, the edges and the
  centers of the other elements of the page, and (texts) the lines of a lined background, which
  the first baseline follows.
* Threshold of a few dp on the screen, converted to millimeters with the zoom; the guides are drawn
  while snapped (thin lines across the page).
* On by default; held ⌥ / Alt moves freely; "Snap to Guides" in the View menu and in the "more"
  menu of the editor (remembered with the preferences).

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | Fixes: alignment (`Constraints.fixedWidth` in the adapter and `NoteInserter`), the text saved on every way out (F1), caret at the tap for a new text and at the tapped character for an existing one, heading at the start of the line, height measured again on width and size changes (`measure` on the port); tests (the switches of the tool, F2, go away with their flags in phase 2) | Done |
| 2 | One kind of text: `isLatex` and `isMathMode` removed (model, Room, synchronization format, export, lecture), the LaTeX block paths, the symbol bar and the whole-text preview removed; `RichTextParser` in the core with the Pandoc and CommonMark rules and the escapes, display formulas on their own centered line; `MarkdownTextUtil` split into parser and presenters | Done |
| 3 | The editing session: `TextEditorManager` owns the draft and commits (no callback, height from the port), `TextBoxEditor` and `TextFormatBar` split out of `TextEditorOverlay`; tests of the use case in the core | Done |
| 4 | One frame: `FrameStyle` in dp, one drawing (`SelectionOverlay.drawFrame`) for the selection and the editor, the handles of the editor routed by `CanvasGestureHandler`, rotated texts edited rotated; screenshot tests of both frames | Done |
| 5 | Formulas in the editor: drawn over their concealed source, popover at the caret with the errors (a spike first, see [Risks](#risks)) | Done |
| 6 | Formatting: `TextCommands` (shortcuts, Format menu, tooltips), `TextFormatBar` shared with the lasso and placed by `AdaptiveInfo`, `TextSizes` and `FontSizeControl`, alignment icons, defaults with the alignment | Done |
| 7 | Creating and sizing: automatic width, box drawn by a drag, one tap to move to another text, text pasted from the system, texts copied as plain text | Done |
| 8 | Snapping: `SnapEngine`, guides, moving the selection and the edited text, width handles, new texts on the lines of the paper; ⌥ / Alt and the switch (the elements placed by the lecture assistant are left for later) | Done |
| 9 | Proposed: bulleted, numbered and check lists; a choice of fonts; completion of the LaTeX commands while typing | Proposed |

### Phase 1 results

* `TextTileAdapter` measures: `measure(text)` (the height of the lines on the width of the box,
  the width of the longest line) and `lineMetrics(fontSize)` (height and baseline of a line);
  `DrawViewController.measureText` and `textLineMetrics` reach them. `TextSizes.PT_TO_MM` moved to
  the core (`core.text`).
* `ComposeTextTileAdapter` lays the texts out on the fixed width of their box: centered and right
  aligned texts are drawn, measured and exported as the editor shows them. `NoteInserter` measures
  its notes with the adapter instead of its copy of the layout.
* Every way out of the editor saves the text: another tool, the drawing mode turned off, the page
  grid, the reordering of the pages, another text opened (`requestFinishEditing` instead of
  `cancelTextEditing`; only the deletion of the page of the text still discards it).
* `TextEditorManager.startNewText` puts the box where the caret lands on the tap (its first line
  centered on it, from `lineMetrics`); the gesture router no longer decides where a text goes.
  A text opened by a tap gets the caret on the tapped character: `RotatableItem.toLocal` gives
  the point in the box before its rotation, the editor turns it into an offset of its layout.
* The width handles and the size of the selection measure the text again (`SelectionManager`).
* The heading button makes the line of the caret a heading, or plain text again
  (`MarkdownTextUtil.toggleHeading`).
* Tests: the caret of a new and of an opened text, a point of a rotated text, the text kept by
  every way out, the height after a resize and a new size (core, with a fake adapter); the
  alignment and the measures of the adapter (desktop); the heading.

### Phase 2 results

* A text is Markdown with formulas: `isLatex`, `isMarkdownEnabled` and the "math mode" left
  `DrawText`, `TextData`, `TextEntity` (Room version 13) and the format of the synchronization
  (recorded again under the same protocol version, before the first release). The alignment is
  `TextAlignment` (`START`, `CENTER`, `END`), stored by name.
* `RichTextParser` (`core.text`) reads the notation once for the editor, the canvas and the
  export: escapes, code and formulas first (written as they are), then emphasis and headings
  around them; the spans nest and never overlap partially. `$` follows Pandoc (`$5 and $10` is
  text), `_` does not work inside words, the emphasis touches its text (`2 * 3 * 4` is text), the
  headings have levels (1.4, 1.25, 1.1 times the size).
* `MarkdownTextUtil` presents the spans: the field of the editor (delimiters concealed away from
  the caret) and the canvas (delimiters and escape backslashes removed, every offset of the
  source mapped, so that nested styles apply). A `$$…$$` alone on its lines takes its line, the
  formula centered in it (smaller when wider than the box); in a line it is in display style
  (`\displaystyle`) on the baseline. Compose `ParagraphStyle`s were tried for the centering and
  left out: around the line breaks of the text they add empty lines.
* `ComposeTextTileAdapter` has one path for every text; the renderer no longer rewrites the height
  of a text while drawing it, and the export no longer draws a formula to learn its height.
* The editor lost the symbol bar, the preview of the whole text, the M↓ switch and the height of
  the LaTeX blocks; Σ wraps the selection in `$…$` ("Formula"). The settings of the tool lost the
  "Markdown" and "Math mode" switches.
* Tests: the rules of the parser (formulas, prices, escapes, display and block formulas, code,
  `_`, emphasis, nesting, no partial overlap), the canvas text (nesting, escapes, block formulas
  and their size), the export of a block formula (as text and vectors, drawn as by the editor).

### Phase 3 results

* `TextEditorManager` owns the text being written: its content and selection (`value`), its
  style (`fontSize`, `color`, `alignment`) and its box (`boxX`, `boxY`, `boxWidth`, the
  `boxHeight` the editor lays it out); it formats it (`toggle(Format)`, `toggleHeading`), moves
  and resizes its box (`moveBy`, `resizeBy`, a part always on the page), reports the draft to the
  others at every change and saves it itself (`finishEditing`: the height measured by the text
  adapter; `cancelEditing`). The editor of the interface no longer registers a callback to be
  asked to save, nor passes six values at the end.
* The formatting of the notation is in the core (`RichTextEditing`: the delimiters of a format
  around the selection, the heading of a line), next to its parser.
* The interface is split by responsibility: `TextBoxEditor` (the field over the canvas, its frame
  and handles), `TextFormatBar` (the bar), `TextEditorOverlay` (both, the keyboard hidden when the
  editor closes, the text kept above the keyboard and the bar).
* Tests: the use case without an interface (caret, every way out saving, the style and the
  measured height saved, the drafts, the formatting, the box on its page), the formatting of the
  notation; screenshots of a text being written, of texts drawn by the page and of a text
  selected (`TextScreenshotTest`, with the scenes of the editor shared with
  `AdaptiveScreenshotTest` in `EditorScene`).

### Phase 4 results

* `FrameStyle` (`drawview-core`) sizes the frame in dp: padding, stroke, corners, rotation knob,
  corner and side handles, and the reach of the handles (44 dp targets for a finger, 24 dp for a
  pen or a mouse; also those of the selected text of the PDFs). The frame of a selection was in
  pixels: a third of its size on a 3× phone.
* `SelectionFrame` computes the frame of a selection (`compute`) and of the text being written
  (`ofEditedText`: its width handles, neither rotation knob nor scaling corners) the same way, and
  tells which handle a pointer takes (`handleAt`); `SelectionOverlay.drawFrame` draws both. The
  same text has the same frame selected and written.
* The editor of the interface no longer draws a frame nor handles: the canvas draws the frame of
  the text being written under its field, and its gestures move the box by its frame
  (`EDITOR_MOVE`, also while the canvas scrolls by itself near the edges) and resize it by its
  sides (`EDITOR_RESIZE_LEFT`/`RIGHT`, along the width of a turned text); a mouse over the frame
  shows a hand.
* A turned text is written turned: the field turns around the middle of the box, as the canvas
  draws it.
* Tests: the frames of the same text selected and written, the handles and their reach;
  screenshots of a text being written, selected, and turned while written. Seen there: on a phone
  the formatting bar falls under the floating tools when no keyboard pushes it up (phase 6).

### Phase 5 results

* The field of the editor shows the formulas as the page does (`MarkdownTextUtil.toEditorText`):
  a formula away from the caret stands as one transparent character, as wide as its image
  (letter spacing) and giving its line the room of the image above and below the baseline (font
  size, from the metrics of the font per pixel, `FontRoom`); the editor draws the image over it
  (`drawWithContent`, positions from the layout of the field). One character, not the concealed
  source: a line never breaks inside a formula, as on the page, where a formula is one
  placeholder. The offsets of the field and of the text written are mapped (`ConcealMapping`):
  the caret, the selection and a tap land on the characters of the text written.
* The formula under the caret shows its source in the line, and above it the formula rendered as
  it is being written (MicroTeX draws an unknown command in red; a formula it cannot read at all
  says so, "Invalid formula"). A formula that cannot be rendered is red source, as on the page.
* `FormulaImages` renders and keeps the images of the formulas for the canvas and the editor; the
  editor renders them at sizes in steps of 8 % (fewer images while zooming), scaled to the exact
  size.
* Tests: the character of a formula (its size, its spacing, the mapping of the offsets), the
  source under the caret, an invalid formula, a block formula; screenshots of the caret in a
  formula and of invalid formulas.
* `BasicTextField` accepts no placeholders, as foreseen in [Risks](#risks); the concealed
  character replaced them on the desktop. Its caret, input methods and handles are to be checked
  on the phones and tablets.

### Phase 6 results

* `TextSizes` (4–288 pt, the presets of the word processors, `larger`, `smaller`, `clamp`) is the
  only place of the sizes: the editor, the selection (also scaled by its corners: the scale of a
  selection stops where one of its texts would leave the range) and the defaults use it.
  `FontSizeControl` steps through the presets and opens them all with a field for any size; it
  replaces the stepper of 8–72 pt and the sliders of 10–48 and 10–60 pt.
* `TextFormatTarget` (`drawview-core`) is what the formatting applies to: the text being written
  (`TextEditorManager`: size, color, alignment, and the formats of its characters) or the texts
  selected (`SelectionManager.selectedTexts`: their style); `DrawViewController.textFormatTarget`
  tells which. The style of a new text is `TextDefaults` (size, color, alignment: the alignment is
  remembered too), stored with the preferences (Room version 14).
* The format commands of `EditorCommands` (menu Format): bold ⌘B, italic ⌘I, strikethrough ⌘⇧X,
  code ⌘E, formula ⌘⇧M, heading ⌘⌥1, alignment ⌘⇧L / ⌘⇧C / ⌘⇧R, size ⌘⇧. / ⌘⇧, (Ctrl
  elsewhere); the buttons run them and show their tooltips.
* One set of controls (`TextStyleControls`: size, color, alignment with its three icons) in the
  formatting bar of the editor, in the bar of the selection and, with the defaults as target, in
  the settings of the tool. `TextFormatBar` adds the formats of the characters and the button
  closing the editor.
* The bar is placed by the window: above the keyboard on a phone or when the software keyboard is
  shown, by the frame of the text elsewhere (above it, below it without room above); on a phone
  the floating tools give it their place while a text is written.
* Tests: the format commands on the text written and on the texts selected (by their keys too),
  the range and the steps of the sizes, the scale of a selection stopped by its texts, the
  formatting target and its undo, the defaults stored; screenshots of the bar by the text, at the
  bottom of a phone and in the bar of a selection.

### Phase 7 results

* A text started by a tap follows its text (`DrawText.autoWidth`, part of the placement of a
  text: `BoxPlacement.autoWidth`, Room version 15, the format of the synchronization recorded
  again): its box grows with its longest line up to the margin of the page, then the lines wrap;
  it grows from the side of its alignment (the left edge stays for a text at the start, the middle
  for a centered one, the right edge for one at the end), and a new text starts so that its caret
  is on the tap whatever the alignment. The width handles, of the editor and of the selection,
  fix the width (one change, undone at once). A formula alone on its line counts its own width,
  not the line it takes.
* A pen or a mouse dragged with the text tool draws the box of a new text (dashed while drawn),
  whose width stays; a finger still moves the canvas.
* While a text is written, a tap on another text saves it and opens that one, the caret where it
  was tapped; a tap elsewhere saves it.
* Paste: a text copied in another application becomes a new text, in the middle of the page shown
  or where the context menu was opened, selected to be moved (`TextEditorManager.pasteText`);
  the texts copied or cut in the editor put their text in the clipboard of the system too
  (`SelectionManager.selectedPlainText`, from the top down), and pasted back they paste the
  elements copied. The paste of the context menu goes through the same command.
* Tests: the automatic width (its growth, the margin, the width set by hand, the side of the
  alignment), a drawn box, a resize of the selection undone at once, a pasted text, the text of
  the texts selected; the gestures on a canvas (`TextGesturesTest`: a box drawn with a mouse, a
  tap on another text and elsewhere while writing); copy and paste through the clipboard of the
  system (`CommandTest`).

### Phase 8 results

* `SnapEngine` (`drawview-core`, `core.snap`) aligns a box with lines: its left edge, middle or
  right edge with a vertical line, its top, middle or bottom with a horizontal one, or the first
  baseline of a text with a row of the paper; the nearest line within the tolerance on each axis,
  and the guides to show. `SnapManager` (owned by `DrawViewController`) gives it what is around:
  the edges and the middles of the other elements of the page (not those moved, not those another
  device is writing), the middle of the page, the rows of the paper (`PageBackground.pattern`); a
  tolerance of 6 dp on the screen, whatever the zoom; the guides of the box snapped now.
* The selection is moved from where the gesture started (`SelectionManager.moveBy`: the move so
  far, then snapped), a single text not turned with its first baseline on the lines; the text being
  written likewise (`TextEditorManager.moveTo`); the sides of a text, written or selected, snap
  (`resizeTo`, `resizeText`; their handles take the padding of the frame into account, which the
  selection measured in pixels); a new text tapped on lined paper sits on the line under the tap,
  its edge aligned with the texts around it. Held ⌥ / Alt, a gesture is free.
* The canvas draws the guides (a thin pink line) while the box is held; "Snap to Guides" (View menu
  and "more" menu) turns snapping off, stored with the preferences (Room version 16).
* Tests: the engine (lines, tolerance, guides, rows, the row under a point), a selection snapped
  to a text then free and with snapping off, a text written on the lines of the paper, the side of
  a text snapped (also on the iOS simulator); screenshots of a snapped move and of the "more" menu.

### Left for later

* On real devices: the caret, the input methods and the selection handles of a field showing a
  formula as one character (phase 5) and of a turned field (phase 4), on Android and iOS; Scribble
  of the Apple Pencil in the field of a text.
* Snapping of the notes placed by the lecture assistant (`PlacementManager`).
* The screenshot tests of the explorer fail now and then (seen once in this work, on a test of the
  texts, passing again at once): `DrawViewController.requestRedraw` writes the state of the canvas
  from the scope of the controller, which in the desktop tests is not the thread of the scene,
  and Compose then corrupts its queue of measures. Not from these phases; to fix with the scope
  of the editor in the tests.
* Phase 9 (lists, fonts, completion of the LaTeX commands) waits for a decision.

## Decisions

Taken by the user on 2026-10-08, who authorized doing the phases in order with commits:

1. `isMarkdownEnabled` is removed: the escapes and the stricter rules write a literal `*` or `$`
   (phase 2).
2. A tap creates a text of automatic width (phase 7).
3. The sizes go from 4 to 288 pt (phase 6).
4. Snapping is on by default, held ⌥ / Alt moves freely; the candidates are those of
   [Snapping](#snapping) (phase 8).

## Risks

* Drawing the formulas inside the field: `BasicTextField` accepts no placeholders, so the source
  of a formula would be concealed with a letter spacing that gives it the width of the image and
  a font size that gives its line the height; to be proven on the three platforms (caret, input
  methods, selection handles) before phase 5. Fallback: sources shown, with the popover only.
* The height measured on each device: the fonts are bundled (`NotesFonts`), so the devices agree;
  the height written with the change remains the reference for the others.
* Rotated fields: the caret rectangle given to the input methods of iOS and Android must follow
  the rotation; to check on devices.
* Removing `isLatex` changes the Room schema and the synchronization format: allowed before the
  first release.
