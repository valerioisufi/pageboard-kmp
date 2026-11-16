# The tools of external models, after a real session

A model annotated documents of slides through the MCP server of PencilNotes and then listed what cost
it time or produced mistakes: seventeen points. This plan checks each one against the code and says
what changes. It continues `docs/plans/agent-tools.md`, `docs/plans/agent-placement.md` and
`docs/plans/agent-full-access.md`, and starts from the code as it is (`main` at 5bdae29c, 2026-11-11).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

Each point of the model, and what the code says about it.

| # | Point | What the code does | Verdict |
|---|---|---|---|
| 1 | No size, color, width or exact place for `insert_text` | `ProposalPlanner.fontSizeFor` takes the size from the page (`TextSizePolicy.forNote`: typed texts, then 0.85 of the slide body, then handwriting), so it differs from slide to slide; the color is `AGENT_TEXT_COLOR`; `near_area` only says what the text is about. All by decisions 1 and 7 of `docs/plans/agent-placement.md` | True, and by design: the design changes |
| 2 | An element in one item only; a new text cannot be touched again in its proposal | `ProposalLimits.checkItems` refuses a second item on an id; a new text has an id only in the plan | True |
| 3 | Proposals lost when the application quits | `ProposalBook` is in memory; `proposal_status` answers not found | True, by decision (in memory) |
| 4 | `list_activity` empty after accepted proposals | `ExternalAgents.decide` records a batch whenever the change has an inverse and a stamp; `AgentActivity` is in memory. In that session the application was restarted (point 3), which empties it. No defect was found by reading: to be reproduced | Likely the same cause as 3; a test decides |
| 5 | `free_areas` and `overlaps` ignore figures of the PDF | `PageOccupancy.pdfBoxes` takes only the lines of the text of a PDF page; a page without text counts as taken whole. A figure, an arrow or a box drawn in the PDF is free space. `insert_text` has no `overlaps` warning at all | True |
| 6 | The size of the text of the slide is not told | `PageTextSizes` knows it (`typed`, `slide`, `handwriting`) and `read_page` does not give it | True, cheap |
| 7 | `placed` has no id and no `base` | `PlannedPiece.textId` is known and left out of `PieceEntry`; the bases after a change are not computed | True, cheap |
| 8 | `restyle_text` and `place_elements` preview as `pieces: []` | `ProposalPlanner.plan` answers an empty `PlannedItem` for them, and does not count their new boxes for the items after | True |
| 9 | No render and only estimates on a document in no editor | `SessionAgentEditors` measures, reads the PDF layout and renders through the controller of an editor. `PageImageExporter` and `DocumentThumbnails` already draw a page without one. On a closed document the PDF layout is `null`, so a slide with text counts as taken whole | True |
| 10 | Several `new_page` after one slide come out reversed | `planInsert` and `AddPage` add each page right after the page named, so the last item ends first | True, a defect |
| 11 | The width of a box cannot be changed | `place_elements` moves and scales; `TextPlacement` carries `width`, `fontSize` and `autoWidth` | True; no protocol change needed |
| 12 | `read_document` is thin | It gives counts per page only | True |
| 13 | One page per call | True | True |
| 14 | `render_page` has no crop and no overlay | It draws the whole page at `max_side` | True |
| 15 | Some proposals applied at once, a document trashed too | `AgentGrants.applyDirectly`, chosen by the user; the answer says `state: decided` and nothing more. The instructions of the server still say "the user reviews every proposal" | Works as chosen; told badly |
| 16 | The Markdown supported is not described | One sentence in the schema of the items. There is no Mermaid | True |
| 17 | "Connection closed" on the first call | `pencilnotes mcp` (`AgentCli.mcp`) opens the socket once and ends when it cannot, or when the application goes away; the client then sees a closed connection | Plausible cause; to be reproduced |

## Requirements

1. **One round where one is enough.** A model sets the style of a text when it adds it, and changes
   the words, the style, the place and the width of a text box with one item.
2. **A model may say where, the application still checks.** A place asked by a model is a bound, not
   an order: the application lays the text out in it, and warns when it does not fit or covers
   something. This revisits decision 1 of `docs/plans/agent-placement.md`.
3. **What the application answers is enough to go on**: the mode a proposal was taken in, the ids and
   bases of what it made or changed, the boxes of every item that moves or resizes something.
4. **The same answers with the document open or not**: heights measured, the PDF read, the page
   drawn, without an editor.
5. **What is drawn on a PDF page counts as taken**, text or not.
6. **Fewer calls to read**: several pages at once, a document with what its pages hold, a part of a
   page drawn.
7. **A proposal and the activity survive the application quitting.** This is a new stored format: it
   is versioned, read back by a test, and what cannot be read is left as it is and skipped, never
   deleted.
8. **A model can ask for more review, never for less.**
9. **Nothing new travels**: every change is a `DocumentChange` of today. `PROTOCOL_VERSION` stays 1,
   `ProtocolFormatTest` is not re-recorded, and Room does not change. A phase that would need one
   stops and asks.
10. **What a client sends stays untrusted**: every new number is checked to be finite and in its
    range, every new list is bounded, and a reference to what is not there is stale or refused.

## Design

### Responsibilities

| Responsibility | Who | GRASP |
|---|---|---|
| The style a model asks for a new text, checked: `font_size`, `color`, `width`, `style_from` | `ProposalItem.InsertText`, `ProposalLimits` (`drawview-agent`) | Information Expert |
| One item changing a text box in any way: `update_text` | `ProposalItem.UpdateText`, turned into `UpdateTexts` and `PlaceElements` by `ElementItems` | Polymorphism, High Cohesion |
| The box of every item after it is applied, and what it covers | `ProposalPlanner`, which today plans the texts only | Pure Fabrication |
| A page without an editor: the height of a text, the layout of its PDF page, its picture | `PageEngine` (`drawview-agent`, an interface), given by the application; `AgentEditors` keeps the review and `settle` | Protected Variations, Indirection |
| What is drawn on a PDF page, as boxes | `PageOccupancy.of` with a `pdfInk` function beside `pdfText` (`drawview-model`); the pixels read by the application | Information Expert |
| The proposals and the activity kept | `AgentJournal` (`drawview-agent`, an interface), given by the application; `ProposalBook` and `AgentActivity` write through it | Pure Fabrication, Protected Variations |
| The bridge that outlives the application | `AgentCli.mcp` (`pencilnotes/desktopApp`) | Indirection |

### Adding text with a style (points 1, 10)

`insert_text` gains optional fields:

| Field | Meaning | Checks |
|---|---|---|
| `font_size` | The size in points | Finite, between `TextSizePolicy.MIN` and `ElementItems.MAX_FONT_SIZE` |
| `color` | `#RRGGBB` or `#RRGGBBAA` | As `restyle_text` |
| `width` | The width of its boxes in millimeters, never made narrower: where no place that wide is free on its page, it begins on a new one, as `moved_to_new_page` says | Finite, from 20 mm to the page less its margins, or to the width of `area` |
| `style_from` | The id of a text box whose size and color are taken; `font_size` and `color` win over it | Missing when proposed: not found; removed later: the size of the page is used |
| `where: "inside"` with `area` | The text goes in this area: at its top-left corner, as wide as the area at most | `area` inside the page, with `page`; without `near` and `new_page` |

`where: "inside"` is planned like any other place: when the text is higher than the area the answer
warns `past_area` (`by_mm`), and when the area is not free `overlaps`. Nothing is clamped or moved
elsewhere. `near_area` keeps its meaning.

`near` may go with `new_page`: the text goes on a new page after the page of its box.

New pages after the same page keep the order of their items: a page is added after the pages the
earlier items of the proposal added after that page.

### Changing a text box with one item (points 2, 11)

```json
{"kind": "update_text", "text": "<id>", "base": "<base>",
 "markdown": "…", "font_size": 12, "color": "#1F5AA6", "alignment": "start",
 "width": 80, "x": 20, "y": 112}
```

Every field but `text` and `base` is optional, one at least is needed. It becomes one `UpdateTexts`,
which carries the content of a text and its placement together (`autoWidth` off when a width is
given), in the operation of the proposal. A corner or a width that would put the box outside its
page is refused when proposed. `replace_text`
and `restyle_text` stay, planned by the same code. An element is still changed by one item only:
with `update_text` nothing needs two.

References from an item to an earlier one of the same proposal (`"ref": 2`) are not added: the user
accepts items one by one, and an item whose reference was rejected would have nothing to stand on.
With the style given at the insertion, a new text needs no second item.

### What is answered (points 7, 8, 15)

* **The plan of every item that moves or resizes**: `restyle_text`, `place_elements` and `update_text`
  answer the boxes after them as `pieces`, with `overlaps`, `past_page_end` and a new `outside_page`;
  the items after them see those boxes.
* **`insert_text` warns `overlaps`** too, when what the plan gave it covers something (a place inside
  an area, or what the PDF draws there).
* **A piece has the `id`** of its element once that exists: always in `placed`, and in a plan for
  every item but an `insert_text`.
* **`proposal_status`** gives, for each accepted item, `changed`: the id and the new `base` of every
  element it made or changed, beside `placed`.
* **`propose` and `propose_library`** answer `mode`: `review` or `applied`. They take
  `require_review: true`, which makes the proposal wait for the user even when the user chose to
  apply at once. There is no flag the other way. The instructions of the server say what the mode
  is instead of "the user reviews every proposal".
* **A proposal not found** says that it may have been decided before the application was restarted,
  until phase 4 keeps them.

### A page without an editor (points 9, 6)

`AgentEngines` gains `pages: PageEngine?`:

```kotlin
public interface PageEngine {
    public fun textHeight(markdown: String, widthMm: Float, fontSize: Float): Float?
    public suspend fun pdfLayout(path: String, pageIndex: Int): PdfTextLayout?
    public suspend fun pdfInk(path: String, pageIndex: Int): PdfInk?
    public suspend fun renderPage(document: DrawDocument, pageId: String, maxSidePx: Int, area: Rect?): ByteArray?
}
```

PencilNotes implements it with what it already has: the text adapter of the application (the one of
`PageImageExporter`), `PdfiumEngine` and `TileRenderer.renderPageContent`. `TextMeasure` asks the
editor first, then the engine, then estimates; `measured` is `false` only on a platform that gives no
engine. The texts of a document in no editor are measured by the engine too, since their height is
not stored (`docs/plans/text-heights.md`). `AgentEditors.textHeight`, `pdfLayout` and the render of
`SessionAgentEditors` stay for a document in an editor, where they are what is on screen.

`read_page` gains `text_sizes`: `typed`, `slide`, `handwriting` (`PageTextSizes`) and `note`, the size
`insert_text` takes by default.

### What a PDF page draws (point 5)

`PdfInk` (`drawview-model`) is a grid of the page, 2 mm a cell like `FreeSpace`, with the cells that
are not paper. `CanvasPageEngine` draws the PDF page alone, 3 pixels a cell, takes as its paper the
color of most of its pixels, and marks the cells with a pixel that differs from it; so a slide on a
dark background is free where it is its background. `PageOccupancy.of` takes `pdfInk` beside
`pdfText` and adds its cells, joined in boxes, in place of the lines of the text. A page that is one
photograph is taken whole, as today. The grid of a PDF page is kept by its file and index: a PDF does
not change. It is asked of `PageEngine` whether the document is in an editor or not.

`free_areas`, the place of `insert_text` and every `overlaps` come from this occupancy, so they agree.
The notes of the lecture assistant and the import of text use `PageOccupancy.of` too: they are given
`pdfInk` in a phase of their own, after it is seen on real slides.

### Reading more at once (points 12, 13, 14, 16)

| Tool | Change |
|---|---|
| `read_document` | Each page with `pdf_page` (the index in its PDF file, from 0); with `texts: true`, each text box of each page by id, box, size, `base` and its first 80 characters, at most 2000 boxes, and the `text_sizes` of each page (they read the text of every PDF page, so they come only when asked) |
| `read_pages` (new) | `read_page` for at most 20 pages by index or id, without strokes |
| `render_page` | `area` ({x, y, width, height} in millimeters): only that part, at `max_side`; `overlay`: `texts` outlined in red and numbered from 1, `free_areas` in green as F1, F2…, both in the order `read_page` lists them. A part or an overlay is drawn by `PageEngine` (`PageMark`), the whole page by its editor when it is in one |
| `read_guide` (new) | A fixed text (`AgentGuide`): the Markdown of the notebook (headings, lists and their styles, tables, quotes, code, formulas inline and displayed), what is not there (diagrams, HTML, images in the text), the units, how a proposal goes. Every example in it is parsed by a test |

### Proposals and activity kept (points 3, 4)

`AgentJournal` is an interface with `load()`, `save(text)` and `setAside()`; the text is written and
read by `JournalCodec` in `drawview-agent`, and PencilNotes keeps it as one file in its data directory
(`agent/journal.json`), written to a file beside it and moved over it.

```json
{"version": 1,
 "proposals": [{"id": "…", "document": "…", "client": "…", "title": "…", "entries": […]}],
 "library": […],
 "activity": [{"id": "…", "document": "…", "client": "…", "title": "…", "items": 3, "at": 0,
               "undone": false,
               "undo": {"change": "<the inverse, as an operation is stored>", "stamp": "…"}}]}
```

* A waiting proposal comes back waiting, and is checked as always when accepted: each item against
  its `base`.
* The inverse of a batch is a `DocumentChange`, written as the stores write the operations, with the
  stamp of its operation. A batch whose way back is not read is listed as not undoable
  (`AgentBatch.undoable`), and what was written of it is written back as it was.
* An entry that is not understood (an item, a proposal or a batch of a later version) is skipped and
  written back as it was. Every item read back goes through `ProposalLimits` again.
* A text that is not understood as a whole (not JSON, cut, another version) is put aside: PencilNotes
  moves the file to `journal-unread-<time>.json` beside it and leaves it there. Nothing is written
  over a journal that was not read back first.
* The bounds stay: 32 waiting, 64 decided, 100 batches.
* The undo of a library batch (`LibraryUndo`) is kept by its values: the ids and the names.
* `AgentJournalTest.theJournalOfVersionOneIsReadAsItWasWritten` holds a journal as version 1 writes
  it: a change of that text is a change of a stored format, which needs a new version and what reads
  the old one.

This reverses decision 8 of `docs/plans/agent-full-access.md` (the activity in memory).

### The bridge (point 17)

`pencilnotes mcp` (`McpBridge`) stays alive for as long as its client goes on. Both cases were
reproduced by tests on the bridge as it was: the application not running when the client starts, and
the application restarted between two calls; in both the bridge ended, which a client reports as
"Connection closed".

* It forwards one message at a time and keeps the `initialize` of its client. When the socket breaks
  it waits up to 10 seconds for the application (it may be starting again), opens with the same
  `initialize`, then sends the request.
* A request the application did not take is asked again of the application that came back. A request
  it took and did not answer is not asked twice: the answer says that it may have been done.
* When the application is not there, a request is answered a JSON-RPC error saying that PencilNotes
  is not running on these notes or the access is off, and the next one tries again.
* A client opening while the application is not there is answered `initialize` by the bridge, with
  that sentence as its instructions, and is kept; the bridge looks for the application every 2
  seconds and sends `notifications/tools/list_changed` when it is there.

### Threats

| Threat | Answer |
|---|---|
| A place or a size asked by a model that makes no sense | Refused by `ProposalLimits`; inside the page or refused; never clamped |
| A text placed over the notes of the user | `overlaps` in the answer and in the review; nothing is removed by it, and the batch is undone from the activity |
| `read_pages`, `texts: true`, `overlay`: larger answers | Bounded by pages, boxes and characters |
| The journal read back | Bounded in size, its nesting checked, each item through `ProposalLimits` again; what fails is skipped |
| `require_review` | Can only add a review |
| The pixels of a PDF | Read by the application with the engine it already draws PDFs with; one small picture a page, kept bounded |

## Phases

One commit per phase on `feat/agent-ergonomics`, with the checks of `CLAUDE.md`. The ABI of
`drawview-agent` and `drawview-model` is re-recorded where said, on purpose. Each phase stands
without the ones after it.

| Phase | What | Points | Status |
|---|---|---|---|
| 1 | Defects and answers: the order of new pages; `mode` and `require_review`; `changed` in `proposal_status`; the instructions; a test accepting a proposal in the review and by `applyDirectly`, then `list_activity` (if it fails, the defect is fixed here). Tests: three `new_page` after one page in their order, `require_review` under `applyDirectly`, ids and bases usable for a second proposal without `read_page` | 10, 15, 7, 4 | done |
| 2 | `insert_text` with `font_size`, `color`, `width`, `style_from`, `where: "inside"`, `near` with `new_page`; `past_area`; `overlaps` for an insertion. Tests: each field, refused values, a style from a box removed since, an area too small, what a preview says is what is applied | 1, 10 | done |
| 3 | `update_text`; the boxes and warnings of `restyle_text`, `place_elements`, `update_text` in the plan, seen by the items after; `outside_page`. Tests: words, size, width and place in one operation and one undo, stale by `base`, converging on another device through the written operation, a text grown by a larger size reported | 2, 11, 8 | done |
| 4 | `AgentJournal`: proposals, library proposals and activity kept; `ExternalAgents` loading them. Tests: a waiting proposal accepted after a restart, a batch undone after a restart, stale after a change in between, a file of version 2, a truncated file and a batch that is not read all left untouched, the bounds | 3, 4 | done |
| 5 | `PageEngine` in PencilNotes: measured heights, PDF layout and render with the document closed; `text_sizes` in `read_page`. Tests: a closed document answering `measured: true` with the heights of the same document in an editor, a slide with text no longer taken whole, the picture of a closed page; the desktop application on a scratch directory | 9, 6 | done |
| 6 | `PdfInk`: `PageOccupancy.of` with `pdfInk`, free areas and `overlaps` on what a PDF draws. Tests: a slide with a figure and no text under it, a scanned page, an empty PDF page, the time of a page measured; the screenshots of the free areas on real slides from `docs/plans/test-data.md` | 5 | done |
| 7 | Reading: `read_pages`, `read_document` with `pdf_page`, `text_sizes` and `texts`, `render_page` with `area` and `overlay`, `read_guide`; the commands `read --pages`, `guide`. Tests: the bounds, a crop at the scale asked, each example of the guide parsed as it says | 12, 13, 14, 16 | done |
| 8 | The bridge: "Connection closed" reproduced, then `mcp` waiting, reconnecting and answering errors. Tests against a socket: the application started after the bridge, restarted between two calls, absent | 17 | done |

Phase 1 found no defect behind point 4: a batch accepted in the review or applied at once is listed
by `list_activity`. The list was empty because the application had been restarted, which phase 4
answers. The command line gained `--review` for `propose` and `propose-library`.

Phase 6 was checked on PDF pages drawn by the tests (a figure, a hairline, a dark background, an
empty page, a photograph): the repository holds no PDF of slides. Reading what an A4 page draws takes
5.6 ms on the Mac, the drawing of the PDF by PDFium apart. Looking at the free areas of real slides in
the application is left to the user.

Phases 1 to 3 remove the second and third rounds of that session; 4 removes the guessing after a
restart; 5 and 6 make the answers true on any document; 7 and 8 are comfort.

## Decisions

Taken with the user (2026-11-11), as recommended:

1. **A model may ask for a place** (`where: "inside"`, `x` and `y` of `update_text`), checked and
   warned about, against "no coordinates from a model" of `docs/plans/agent-placement.md`. `place_elements`
   already moves by millimeters since `docs/plans/agent-full-access.md`.
2. **The proposals and the activity are kept in a file**, a new stored format with its rules, against
   "in memory" of the two plans before.
3. **`require_review` and no more**: applying at once stays the choice of the user for everything,
   trashing included. The other way would be a third switch, "always review what removes".

Taken alone, revertible:

4. **`update_text` instead of references between items** (see above).
5. **`style_from` copies size and color**, not the width: the width depends on the place.
6. **A default size stays the one of the page** when a model gives none.
7. **`PageEngine` beside `AgentEditors`**, not in place of it: what an editor shows is asked to it.
8. **The free space from the pixels of the PDF alone**, not of the whole page: the elements of the
   notebook are known by their boxes, which is exact and costs nothing.
9. **`pdfInk` for the agents first**, the lecture assistant and the import after it is seen working.
10. **`read_guide` is a tool**, since the endpoint offers only tools; the schema of the items stays
    short.
11. **One file for the journal**, not Room: no schema change, and it is small and bounded.
12. **The bridge waits 10 seconds** for the application before answering an error.

## Left for later

* References between the items of a proposal.
* The printed label of a PDF page (`i`, `ii`, `12`) in `read_document`: `pdf_page` is its index.
* A switch to always review what removes (decision 3).
* `pdfInk` for the lecture assistant and the import of text.
* `PageEngine` on Android and iOS, with stage 2 of `docs/plans/agent-tools.md`.
* Moving an element to another page; the highlights of the texts; a headless host with the
  application closed (as in `docs/plans/agent-full-access.md`).
