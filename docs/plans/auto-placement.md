# Automatic placement

The notes of the lecture assistant, its review, and any text written elsewhere (pasted, imported,
asked to a model) put by the application in a sensible place of the notebook: next to what they
are about, in the free space of the page, in the order the page is read, without covering what
is already there; proposed, never imposed, and undone in one step. This document starts from the
code as it is (main at 89a72c69, 2026-10-29): how the content of the assistant is inserted today,
then the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **A note is placed by hand.** `NoteInserter.startPlacement` (`drawview-lecture-ui`) jumps to
   the page of the suggestion (`NoteSuggestion.resolvedPageIndex`: by `pageId`, else by index),
   builds a `DrawText` 90 mm wide (at most 45 % of the page), 11 pt, in the blue of the assistant
   (`0xFF1F5AA6`), its title in bold, and starts a `PlacementRequest`: the next tap on a page puts
   it there (`PlacementManager`, `drawview-canvas`), clamped inside the page, selected. Nothing
   looks at what is on the page: the note can fall on the writing or on the slide.
2. **The review goes on new pages.** `NoteInserter.insertPages` splits the Markdown of the review
   between paragraphs and fills new pages greedily (15 mm margins), after a page chosen in
   `InsertPagesCard` or at the end of the notebook, in one step of the history.
3. **Pasted text goes in the middle.** `TextEditorManager.pasteText` puts a pasted text centered on
   the point (or on the page shown), its width following the text, clamped to the margins: over
   whatever is there.
4. **What the assistant knows of a note.** A `NoteSuggestion` has a `kind` (`DEFINITION`,
   `FORMULA`, `THEOREM`, `EXAMPLE`, `EXPLANATION`, `EXAM_HINT`, `ANNOUNCEMENT`, `REFERENCE`,
   `OTHER`), a page (`pageIndex`, `pageId`), an `importance` (1 to 3) and the moment of the
   lecture it was said (`atMs` from `LectureRecord.startedAt`). The model answers the page only
   (`AnalysisNote.page`), not the part of the page.
5. **What the application knows of a page.**
   * Its elements, each with its box: `DrawStroke.bounds`, `DrawText` (box, rotation), `DrawImage`,
     `DrawCustomElement`, the PDF page under them (`DrawPdf`); `RotatableItem.bounds` covers a
     rotated box.
   * The text of a PDF page, character by character with its box (`PdfTextLayout`), from the PDF
     engine when there is one (a scanned page has none).
   * Its background (`PageBackground.Ruled`, `Grid`, `Dotted`: the spacing of the lines).
   * The handwriting in blocks (`groupStrokes`, `InkBlock.bounds`, 6 mm apart), transcribed by
     `HandwritingReader` (by key of block) and sent to the model as text, from the top of the
     page (`PageContent.handwriting`): the model sees the blocks, but without a name to point at.
   * **When a stroke was written**: `DrawElement.added` is a `Stamp` whose `time` is the wall clock
     of the device. With `LectureRecord.startedAt` and `NoteSuggestion.atMs`, the writing of the
     student around the moment a note was said can be found today, with no change to the model or
     to what is stored.
6. **Measuring text.** `DrawViewController.measureText` and `TextTileAdapter.measure` lay a
   `DrawText` out as the canvas draws it (Compose); `NoteInserter.measureHeightMm` uses them.
7. **Search.** `drawview-search` indexes the pages of the notes with a local embedding model
   (optional: without it, the search is by words); it can tell the pages closest to a text.
8. **Formats.** Lecture records are JSON (`LectureRecords`, `ignoreUnknownKeys`), merged between
   the devices of a notebook, told apart by a hash of their canonical form
   (`LectureRecords.version`). Elements carry no field saying who wrote them.

## Requirements

1. **One tap to insert a note in a sensible place.** "Insert" shows the note already in the best
   place found; one tap confirms it. The student can look at the other places found, or drag it
   anywhere, before confirming; placing it by hand stays possible.
2. **Sensible** means, in this order:
   1. on the page the note belongs to;
   2. next to what it is about: what the student wrote when the lecturer said it, or the part of
      the slide it explains;
   3. where it is read after it: to its right or below it, not above;
   4. on free space only: never over writing, typed text, images or the text of the slide, with
      a margin around them;
   5. aligned with what is there (the left edge of the column of writing, the lines of a ruled
      page), at a width that keeps it readable (it may be narrower, never under 30 mm).
3. **When the page is full**, a new page right after it (in the format of the page it follows),
   never at the end of the notebook; said in the preview, before confirming.
4. **Several notes at once** ("Insert all"): placed together, none over another, in one step of
   the history.
5. **The review** and **long texts** (pasted, imported, written by a model) flow through the free
   space of the pages they are about, then onto new pages right after them, split between
   paragraphs, never in the middle of a formula, a code block or a list item.
6. **The kind of a note decides its form and place** (stage 2): a definition in the margin next to
   the term, a summary at the end of the section, a question to answer at the bottom of the page.
7. **Nothing written by the student is moved, covered or changed.** Everything inserted is
   undone in one step.
8. **It degrades, never fails**: without a PDF engine the slide is a box; without text measuring,
   the height is estimated; without the search engine, a text goes to the page shown.
9. **Every platform and input**: finger, stylus, mouse, keyboard (Tab and the arrows go through
   the places found, Return confirms, Escape cancels).

## Design

### The experience

* **Insert a note.** The button "Insert" of a suggestion (today "Insert on p. 4") shows the page
  and the note as a ghost in the best place, with a small bar over it: ‹ › to go through the
  other places found (at most five, the best first; Tab and ⇧Tab, the arrows), ✓ to confirm, ✕ to
  cancel. A tap on the ghost confirms; a tap elsewhere on a page places it there, as today; a drag
  of the ghost moves it. When the page has no room, the bar says "New page after p. 4" and the
  end of that page is shown (no ghost: the page does not exist yet).
* **Insert all.** In the list of notes, "Insert all" (the new ones, or those of one page) shows
  every note as a ghost at once; ✓ inserts them, ✕ cancels; one at a time can be dragged.
* **The review.** "Insert in the notebook" places each section of the review next to the pages it
  is about (stage 1: after the page chosen, as today, but in the free space first), shown before
  confirming as a list: "Section 1 → p. 4 (free space), Section 2 → new page after p. 7".
* **Long text.** Pasting a text longer than a few lines (or "Insert text…" in the menu of the
  page, from a file or the clipboard) opens the same preview: the text flowing through the free
  space from the page shown, or from the pages it is about when the search engine is there.
* **The ghost** is the note as it will be (its font, color, width), 60 % opaque, with the free
  regions faintly shaded while it is shown, so the choice can be understood.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The boxes a page holds (strokes, texts, images, elements of the applications), with a margin; the lines of the text of its PDF page as boxes, given by the caller | `PageOccupancy.of(page, pdfText)` (`drawview-model`, package `placement`) | Information Expert (the page knows its content), Low Coupling (the PDF text comes in as rectangles) |
| The free space of a page: a grid of 2 mm cells, whether a box is free in constant time | `FreeSpace` (`drawview-model`) | Pure Fabrication |
| The places a block of a given size can take, and their scores | `PlacementPlanner` (`drawview-model`) | Pure Fabrication, Controller of the computation |
| What makes a place good: near the anchor, after it in reading order, aligned, margins; one per kind of content | `PlacementPolicy` (interface) with `BesideAnchor`, `ReadingOrder` (stage 2: `EndOfSection`, `PageBottom`) | Polymorphism, Protected Variations (new kinds without touching the planner) |
| What a block is about on its page: a region (a box), from the writing of a moment, a block of handwriting, the slide | `PlacementAnchor` (`drawview-model`); `LectureAnchors` (`drawview-lecture`: the strokes added around `startedAt + atMs`) | Information Expert (the lecture knows when a note was said) |
| The height of a text at a width | a function `(width) -> height` passed to the planner, built from `measureText` | Indirection, Low Coupling (the headless module has no Compose) |
| A text split into blocks that never break a formula, a code block or a list item, flowed through the free space and onto new pages | `TextFlow` (`drawview-model`) | Pure Fabrication, High Cohesion |
| The use case "place these blocks": the ghosts, going through the places, dragging them, confirming, new pages created on confirm; the occupancy of a page with the text of its PDF page | `PlacementManager` with `PlacementRequest(items)`, `PlacementItem`, `PlacementSpot`, `spotsFor`, `pagesFor` (`drawview-canvas`) | Controller |
| What is placed and where, the places gone through, cancel and confirm, with the keys | `PlacementBar` (`drawview-ui`), its commands registered while a placement is pending | Controller, Polymorphism (the presenters of `Command`) |
| Drawing the ghosts, the shaded free space, the bar ‹ › ✓ ✕ | the placement overlay (`drawview-canvas`) | Information Expert |
| Turning a suggestion, the review or a text into blocks and asking the planner | `NoteInserter` (`drawview-lecture-ui`), `TextEditorManager.pasteText` (`drawview-canvas`) | Creator, Controller |

### The free space

* **The grid.** A page is a grid of 2 mm cells (an A4 page is 105 × 148 cells, 15,540): a cell is
  taken when a box of the occupancy (with its margin, 3 mm) covers it. The page margins (10 mm)
  are taken too, except for notes in the margin (`BesideAnchor` can use the outer margin when a
  slide fills the page).
* **What is taken.** Every element not removed, by its bounds (rotated boxes by their
  `RotatableItem.bounds`); highlighter strokes too (they mark text already there). The PDF page:
  the boxes of its lines of text (characters merged by line), and the box of every image of the
  page when there is no text (a scanned page is taken whole, except where it is white: stage 2,
  if needed). Without the PDF text, the whole PDF page is taken.
* **Free boxes.** A table of sums of the cells taken tells whether any box is free in constant
  time, so every position of the grid can be tried (an A4 page, four widths: about 60,000 tries,
  a few milliseconds). Deterministic: the same page gives the same places on every device.

### The places and their scores

The planner tries, for every free rectangle, the block at its preferred width (90 mm for a note,
the width of the column for a flow) and narrower down to 30 mm, the height measured at each
width, and keeps the positions where it fits: aligned on the left edge of the rectangle, on the
left edge of the anchor, on the next line of a ruled page. Each position is scored by its policy:

* `BesideAnchor` (a note): the distance from the anchor, counted more above it than below or to
  its right (reading order); a bonus for the left edge of the anchor and for the outer margin
  next to it; a penalty for a narrow width.
* `EndOfSection` (a summary): below the last content of the page or of the section, full width.
* `PageBottom` (a question): the bottom of the page.
* `ReadingOrder` (a long text): the first free place from the top, in reading order, the blocks
  one after the other.

The best five, at least 20 mm apart, are the candidates. When none fits, the candidate is a new
page after the page of the anchor, the block at its top margin.

### The anchor of a note (stage 1, no change to what is stored)

The region the note is about, by the first that exists:

1. the strokes and texts the student added on the page of the note between 60 s before and 30 s
   after `startedAt + atMs` (their `added.time`), as one box: what they were writing when the
   lecturer said it;
2. the last block of writing added on that page during the lecture;
3. none: the policy prefers the top of the free space of the page.

The clock of a stroke is the one of the device that drew it: on another device the window may be
off by its skew; the anchor is a preference, not a constraint, so a wrong one only makes the place
less good.

### The anchor named by the model (stage 2)

A note answers `anchor`: **a few exact words** (at most 8) of the slide or of the notes of the
student on its page, that the note explains or completes; omitted when there are none. A quote
rather than names given to the blocks in the prompt (`[b3]`): the description of the notebook does
not change (the slides opening every request stay as they are in the explicit cache of
`docs/plans/lecture-cost.md`), a small local model quotes more reliably than it copies an identifier, and
a quote survives the blocks regrouped by new strokes.

The quote is found again when the note is inserted (`LectureAnchors.quoteAnchor`, Information
Expert), comparing its words with:

1. the texts typed on the page (their boxes);
2. the blocks of handwriting of the page, by the text `HandwritingReader` keeps for them
   (`LectureAssistant.handwritingText`);
3. the text of its PDF page (`PdfTextLayout`): the run of words of the length of the quote that
   shares the most words with it, its lines as the box.

A match shares at least 60 % of the words of the quote; the best wins, the order above breaking
ties. Without a match the anchor of stage 1 (the time) is used.

`NoteSuggestion` gets an optional `anchor`, **never written when empty**
(`@EncodeDefault(NEVER)`): the records stored or received before are written again byte for byte,
their `version` unchanged, and an older version reads a new record (`ignoreUnknownKeys`). Tested:
a record of the old form read and written again as it was, merged with its new form keeping the
anchor.

### The form by kind (stage 2)

The assistant keeps writing **in blue** (`0xFF1F5AA6`): what the student wrote and what was added
for them stay apart at a glance, which matters to study from them; the color is the only mark,
nothing stored tells them apart (see "Left for later").

| Kind | Policy | Width |
|---|---|---|
| `DEFINITION`, `FORMULA`, `REFERENCE` | `InMargin`: beside the anchor first (the margin or the free column next to it), else below | narrow, 60 mm |
| `EXPLANATION`, `EXAMPLE`, `THEOREM`, `OTHER` | `BesideAnchor`: below it first | the width of the anchor (60 mm to the page), 90 mm without one |
| `ANNOUNCEMENT`, `EXAM_HINT` | `PageBottom`: the bottom of the free space | the page |
| the review | `ReadingOrder` through the free space (phase 4), by topic (phase 5) | the page |
| a question to answer | — | left for later (an answer hidden) |

## Phases

### Stage 1: placement on what the application has

| Phase | What | Status |
|---|---|---|
| 1 | `PageOccupancy`, `FreeSpace`, `PlacementPlanner`, `PlacementPolicy` (`BesideAnchor`, `ReadingOrder`) in `drawview-model`: tests on pages built in the tests (empty, full, a slide, ruled, rotated elements), deterministic results; `api/` recorded | done |
| 2 | `PlacementRequest` of items with their places (`PlacementItem`, `PlacementSpot`) and the ghosts in `PlacementManager`: going through the places (‹ ›, Tab, arrows), confirm (Return), cancel, drag; the new page created on confirm (`pagesFor`); `PlacementBar` in `drawview-ui`. Screenshot | done |
| 3 | `NoteInserter.startPlacement` on the planner: the anchor of the moment (`LectureAnchors`), the PDF text from the engine; "Insert all" (`startPlacementAll`); the banner of the lecture replaced by `PlacementBar` | done |
| 4 | `TextFlow`: the review (`NoteInserter.startReviewPlacement`) and long pasted texts (`TextEditorManager.pasteText`, 600 characters and more of several paragraphs) through the free space and onto new pages after them, with the preview; `PlacementSpot.newPage` names the n-th new page | done |
| 5 | The pages a section of the review is about (`TopicFinder` of `drawview-lecture`, given by the application from `SearchEngine.pagesAbout`; `TextFlow.sectionsOf`), each section flowed from its page, degrading to the page shown | done |

### Stage 2: what the model and the stored formats add (each decided with the maintainer first)

| Phase | What | Status |
|---|---|---|
| 6 | The anchor named by the model: a quote in the answer (`AnalysisNote.anchor`, the schemas and the instructions of the live notes and of the review), `NoteSuggestion.anchor` never written when empty, tested on the records of the old form; `LectureAnchors.quoteAnchor`, `LectureAssistant.handwritingTexts`, `PlacementManager.pdfTextOf` | done |
| 7 | The policy and the width by kind (`InMargin`, `PageBottom` in `drawview-model`; `NotePlacement` in `drawview-lecture`) | done |

## Decisions

Taken alone, revertible:

1. **The engine is in `drawview-model`** (package `placement`): it needs the pages and the
   geometry only, and is headless; text measuring and the PDF text come in from outside. A module
   of its own would be one more artifact for a few classes.
2. **A grid of 2 mm**, not exact geometry: simple, linear, deterministic, and more precise than
   the placement needs. Every position is tried with a table of sums, rather than listing the
   largest empty rectangles: as fast, and the anchor and the lines of the page give positions of
   their own.
3. **Proposed, not imposed**: the best place is shown, never inserted without a tap.
4. **A new page goes right after the page of the anchor**, not at the end of the notebook (as the
   review does today): the notes stay next to what they are about.
5. **The anchor of stage 1 comes from the time** the strokes were written, a preference only.
6. **Stage 1 changes nothing stored nor what travels**: the elements inserted are ordinary texts,
   as today.
7. **No ghost for a new page**: the page is not drawn before it exists (the layout of the pages
   would have to make room for it); the bar names it and the end of the page it follows is shown.
8. **The keys of the placement are commands** (Tab, ⇧Tab, the arrows, Return), registered while a
   placement is pending and listed in the menu Edit then; Escape cancels as the system back.
9. **No "Insert text…" command yet**: a long text is pasted (the clipboard covers a text written
   by a model elsewhere); a command reading a file is left for later.
10. **The review keeps its choices** "on the page shown / at the end", after "by topic" when the
    search is there: on the page shown it fills
    its free space first, then new pages after it, of the format of the document.
11. **A pasted text is not placed by topic**: where it is pasted says where it goes; only the
    review, which has no place of its own, is placed by topic.
12. **`TopicFinder` is an interface of `drawview-lecture`**: the lecture modules do not depend on
    the search; the application connects them (Indirection, Low Coupling).

13. **The anchor is a quote**, not a name given to the blocks in the prompt (see "The anchor named
    by the model").
14. **The form by kind** is a policy and a width, and the assistant keeps its blue (the maintainer left
    it to me, 2026-10-29). No `EndOfSection`: the review already flows by topic.
15. **The review goes by topic by default** when the search is there.

## Left for later

* **Questions with a hidden answer** (active recall): a text that shows its answer when touched
  is a new kind of content (a `DrawCustomElement` of an application, or a property of the text):
  a stored format and a protocol change; a plan of its own.
* **A line from a note to its anchor**: it needs the anchor kept on the element (stored, and
  travelling): a protocol change.
* **The layer of the assistant** (hiding or exporting without what the assistant wrote): it needs
  the origin of an element stored and travelling: a protocol change. Until then the color tells
  them apart.
* **Moving the student's content to make room**: never without asking; not planned.
* **Scanned slides** taken only where they are not white (the free space inside a scanned page).
* **Conflicts with `feat/lecture-cost`**, which changes the prompts of `drawview-lecture`: phase 6
  starts after it is merged.
