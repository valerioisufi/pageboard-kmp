# Where the text of a model goes

A model proposing text for a document says what the text is about and where it belongs; the
application finds the place, and tells the model and the user what it found and what is wrong with
it, before anything changes. This document continues `docs/plans/agent-tools.md` and starts from the code
as it is (`main` at 6e9fabfa, 2026-11-04): what exists today, then the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **A model chooses a page and nothing else.** `insert_text(markdown, page?, new_page?)` has no
   position, width or size. `ProposalApplier.insertText` places the text when it is accepted.
2. **The place is the first free one.** `TextFlow.flow` asks `PlacementPlanner.candidates` with the
   policy `ReadingOrder`, so a text about a formula in the middle of a page goes to the first room
   from the top. The planner can do better: `BesideAnchor` and `InMargin` are the policies of the
   notes of the lecture assistant.
3. **The room is known.** `PageOccupancy.of` gives the boxes of the elements and the lines of a PDF
   page (a scanned page counts as taken whole); `FreeSpace` lays them on a grid of 2 mm, with 3 mm of
   clearance and a margin of 10 mm; `freeRuns()` gives the free runs of each row.
4. **The size is chosen by the page.** `TextSizePolicy.forNote(PageTextSizes.of(page, layout), default)`:
   the typed texts, then the body of the PDF page, then the handwriting, then the default, rounded to
   a preset. The width is the one of the page less its margins, narrowed by a fifth at a time down to
   60 mm (`TextFlow.DEFAULT_MIN_WIDTH_MM`).
5. **The height is measured only by an editor.** `AgentEditors.textHeight` lays the text out in the
   editor of the document; without one, `ProposalApplier.heightOf` guesses from the characters.
6. **The height of a text already on the page is not stored** (`docs/plans/text-heights.md`):
   `DrawText.height` is what the editor laid out on this device (`TextBoxes`). A document in no editor
   has only `TextSizes.estimatedHeightMm`, its lines times the height of a line, which does not count
   the lines that wrap, so `PageOccupancy.of` on it reads long texts as too short. Today this does not
   reach the user, because a proposal is decided in an editor; a preview on a closed document would
   be wrong without care.
7. **`replace_text` keeps the box.** `UpdateTexts` changes the Markdown, with the same `x`, `y` and
   `width`. A longer text covers what is under it or goes past the end of the page, and nothing says
   so. A box of a chain (`DrawText.next`) keeps its own part, and `ChainFlow` is not run.
8. **The plan of a proposal ignores its own items.** The boxes placed by earlier `insert_text` items
   count, but the room left by a `remove_text` or taken by a longer `replace_text` does not.
9. **A model sees little.** `read_page` gives `x`, `y` and `width` of the texts, the number of the
   strokes, nothing of images and PDF pages. `render_page` shows the page, with an editor only.
   `propose` and `proposal_status` give only the outcome of each item, so a model never learns where
   its text went.
10. **The user sees text, not places.** The review panel (`AgentReviewUi.EntryContent`) shows the old
    and the new text. `PlacementManager` already shows texts on the page before they are placed: the
    other places proposed, dragging, a bar to confirm.

## Requirements

1. A model can say **what a new text is about**: a text box, or an area of the page for what has no
   box (handwriting, a figure). The text goes next to it, below it or in the margin.
2. A model **never gives coordinates to obey**. A proposal may be accepted later on a changed page,
   and only an editor knows the height of a text. The place is computed by the application.
3. A model can **ask before proposing**: what would happen to these items, without creating a
   proposal, opening a window or changing anything.
4. The answer says, for each item, **where it goes, at what size, whether it fits and how sure this
   is**: the page and box, the font size, the pages added, and warnings a program can read.
5. **`replace_text` is checked too**: a text that no longer fits its place is reported.
6. What a preview says and what `apply` does come **from the same code**, so they cannot disagree
   when the page has not changed. When it has, the place is computed again and the model reads the
   final one in `proposal_status`.
7. A model can **read the geometry of a page**: the height and size of the texts, images, handwriting
   and the largest free areas.
8. The **user sees the same warnings** in the review, and can look at the text on the page and move
   it before accepting.
9. **Nothing stored or sent changes**: no Room schema, no stored format, no protocol change. Placed
   text is the `TextData` of today.
10. What a client sends stays untrusted: a reference to a missing text is stale, an area outside the
    page is refused, every number is checked to be finite, and the limits of `ProposalLimits` hold.

## Design

### Responsibilities

| Responsibility | Who | GRASP |
|---|---|---|
| The plan of a list of items on a document as it is: for each item its pieces, boxes, size, new pages and warnings; pages planned as the earlier items leave them | `ProposalPlanner` (`drawview-agent`, new) | Pure Fabrication, High Cohesion |
| Turning a plan into one `CompositeChange` | `ProposalApplier`, which keeps `apply` and loses the placement | Creator |
| The height of a text as well as it can be told, and whether it was measured | `TextMeasure` (`drawview-agent`, internal), from `AgentEditors.textHeight` or the estimate | Information Expert, Protected Variations |
| The first piece of a flowed text placed by a policy, what follows staying below it | `TextFlow.flow`, an overload with `FirstPlace` | Polymorphism |
| The largest free areas of a page | `FreeSpace.largestAreas(count)` (`drawview-model`), built on the grid | Information Expert |
| The tools `preview_proposal`, `read_page` with its geometry, `proposal_status` with the places | `DocumentTools` | Controller |
| The command `pencilnotes preview`, and the warnings printed by `propose` | `AgentCli` | Indirection |
| The warnings in the review, the text shown on the page, the place chosen by the user | `AgentReviewUi` over `PlacementManager` | Low Coupling |

The planner keeps what it plans in `PlannedPage`, moved out of `ProposalApplier`. Its occupancy is
`PageOccupancy.of(page, excluded = what the earlier items removed or replaced)` plus the boxes they
took, with each text of the page at the height `TextMeasure` gives: laid out in an editor, estimated
with the lines that wrap otherwise (point 6 above).

### What the model sends

`insert_text` gains two optional, exclusive references and a choice of how to use them:

```json
{"kind": "insert_text", "markdown": "…", "near": "<id of a text box>", "where": "below"}
{"kind": "insert_text", "markdown": "…", "page": "<id>", "near_area": {"x": 20, "y": 80, "width": 120, "height": 30}, "where": "margin"}
```

| Field | Meaning | Checks |
|---|---|---|
| `near` | The text box it is about; the page is the page of that box when the item is planned, so the note follows the box if it moved | Missing when proposed: not found; missing or removed later: the item is stale. With `page`, `near_area` or `new_page`: refused |
| `near_area` | The area of the page it is about, in millimeters, read from `read_page` or from `render_page`; the text does not cover it | Needs `page`. Finite, positive, inside the page: refused otherwise. With `new_page`: refused |
| `where` | `below` (`BesideAnchor`, the default) or `margin` (`InMargin`, at most 60 mm wide) | Needs `near` or `near_area` |

There is no size and no coordinate. The anchor is only a preference: when there is no room near it,
the text goes where the policy finds room and the answer warns about it.

A box referred to by `near` and removed by the same proposal is refused by `ProposalLimits`.

### What the model is answered

`preview_proposal(document, items)` takes the items of `propose`, runs `ProposalLimits` and plans
them on the thread of the document. It changes nothing, adds nothing to `ProposalBook` and opens no
window. `propose` answers the same plan beside the id of the proposal.

```json
{
  "measured": true,
  "items": [
    {
      "kind": "insert_text",
      "stale": false,
      "font_size": 11,
      "pieces": [
        {"page": "<id>", "page_index": 2, "new_page": false, "x": 20, "y": 112, "width": 170, "height": 24}
      ],
      "pages_added": 0,
      "warnings": []
    },
    {
      "kind": "replace_text",
      "text": "<id>",
      "stale": false,
      "font_size": 14,
      "pieces": [{"page": "<id>", "page_index": 3, "new_page": false, "x": 20, "y": 200, "width": 170, "height": 96}],
      "pages_added": 0,
      "warnings": [{"code": "past_page_end", "by_mm": 9}, {"code": "overlaps", "with": ["<id>"]}]
    }
  ]
}
```

| Warning | When |
|---|---|
| `far_from_anchor` (`by_mm`) | The box is more than 30 mm from what it is about |
| `moved_to_new_page` | An item with a page or an anchor did not fit there and began on a new page |
| `split` (`pieces`) | The text is in several boxes |
| `narrowed` (`from_mm`) | Narrower than the width the place wanted, for lack of room |
| `overlaps` (`with`) | `replace_text`: it would cover these elements. Strokes are named as `"ink"`, a PDF page as `"pdf"` |
| `past_page_end` (`by_mm`) | `replace_text`: it would pass the bottom margin of the page |
| `chain_overflow` (`by_mm`) | `replace_text` on a box of a chain: its part no longer fits its box, and the chain is not flowed again |

A piece on a page the proposal adds has `new_page: true` and no `page`, since the page has no id until
it is accepted; `page_index` is the place of the page once the items are applied.

Warnings carry identifiers and numbers, never the content of a document, and they do not stop a
proposal: the user decides. `measured: false` means the document is in no editor, so every height,
those of the texts already there included, is an estimate.

`proposal_status` gains, for each accepted item, the `pieces` as they were placed.

### What the model reads

`read_page` gains the geometry:

```json
{
  "measured": true,
  "texts": [{"id": "…", "x": 20, "y": 30, "width": 170, "height": 18, "font_size": 14, "…": "…"}],
  "images": [{"id": "…", "x": 20, "y": 60, "width": 80, "height": 50}],
  "ink_area": {"x": 15, "y": 120, "width": 180, "height": 60},
  "pdf_pages": 1,
  "free_areas": [{"x": 10, "y": 190, "width": 190, "height": 97}]
}
```

`free_areas` are the eight largest free rectangles at most, as `FreeSpace` sees them. `ink_area` is
the box of all the strokes: a page of handwriting is looked at with `render_page`, whose pixels turn
into millimeters by the size of the page.

### The review

An item with warnings shows them in words in its card. The card of an `insert_text` has a button
showing it on the page: a `PlacementRequest` with the pieces of the plan, so the user can look at
the box, drag it and confirm the place, which is what is applied. Confirming only keeps the place:
nothing changes until the proposal is applied. `decide` gains the places chosen by the user, by item.
A place chosen by the user is applied as chosen, and is not checked again. The panel stays open
meanwhile, which suits the desktop, the only place the review is today (stage 2 will look at this on
small screens).

A `replace_text` with warnings shows them, and takes the user to its box. Moving a box is not an item
of a proposal yet (`docs/plans/agent-tools.md`, "Left for later").

### Threats

* A preview is a read of a document the user allowed, through `withDocument`: no new access.
* A preview costs time on the thread of the document: plans are made one at a time, whoever asks,
  and only up to 32 KiB of text. Measured in a real editor on the Mac: 3 KiB in 170 ms, 14 KiB in
  390 ms, 55 KiB in 1.1 s, with the code cold. Above the bound `preview_proposal` is refused and
  `propose` answers without its plan; the text is still placed when the user accepts.
* An area or a reference that makes no sense is refused or stale, never clamped into something else.

## Phases

One commit per phase on `feat/agent-placement`, with the checks of `CLAUDE.md`. Phases 1 and 2 are
the ones that matter most, and each phase can be left out of the ones after it.

| Phase | What | Status |
|---|---|---|
| 1 | `ProposalPlanner` and `TextMeasure` out of `ProposalApplier`, the plan seeing what earlier items removed or grew; `near`, `near_area`, `where`; `TextFlow.FirstPlace`, with the ABI of `drawview-model` and `drawview-agent` re-recorded. Tests: the place below and in the margin of a box and of an area, a box moved after the proposal, no room near the anchor, stale and refused references, removing then inserting on one page | done |
| 2 | `preview_proposal`, the plan in the answer of `propose` and the places in `proposal_status`; the warnings, with the check of `replace_text`; `pencilnotes preview`. Tests: a preview changes nothing and opens nothing, what a preview says is what `apply` does on the same page, each warning, a closed document answering `measured: false`, the largest proposal measured; the commands against the application on a scratch directory | done |
| 3 | The geometry in `read_page`, `FreeSpace.largestAreas`. Tests: empty, full, PDF and scanned pages, texts at their measured height | done |
| 4 | The warnings in the review panel. Tests: screenshots (`verifyRoborazziJvm`), a real editor | done |
| 5 | The text shown on the page from the review, its place chosen by the user, `decide` with the places. Tests: a real editor (shown, confirmed, applied where it was, undone at once; cancelled, nothing changes; a place chosen is the one applied), a piece moved off a new page adds no page. The drag itself is the one of `PlacementManager`, tested in the canvas. The clicks in the window of the application are left to the user | done |

## Decisions

Those marked *revertible* were taken without the user.

1. **The application places, the model refers.** No coordinates from a model. This is what the user
   approved in discussing it.
2. **A preview is a tool of its own**, not `propose` with a flag, so a client can allow it without
   allowing proposals. *Revertible.*
3. **Warnings do not refuse.** A model may have a reason, and the user decides. *Revertible.*
4. **A missing anchor makes the item stale** rather than falling back to the first free place: as for
   `replace_text`, nothing happens other than what was reviewed. *Revertible.*
5. **The note follows its box.** `near` is resolved when planned, so a box moved or carried to another
   page brings the note with it. *Revertible.*
6. **30 mm for `far_from_anchor`, 60 mm for `margin`, eight free areas.** First values, to be looked
   at on real pages. *Revertible.*
7. **A model does not choose the size.** The page does, as for the notes of the assistant; Markdown
   headings remain. A size asked by a model is left for later. *Revertible.*
8. **A closed document is planned on estimates**, said by `measured: false`, rather than opening a
   window for a preview. *Revertible.*
9. **A place chosen by the user is not checked again**: it is their choice, and they see it.
   *Revertible.*

10. **A text with no anchor prefers a wide place** to the first one (`BesideAnchor(null)`, not
    `ReadingOrder`): seen in a real editor, a short text went into a narrow gap beside a text, with a
    wide page free below it. The import and the review of the assistant keep filling the gaps.
    *Revertible.*

## Left for later

* `replace_text` on a chain flowed with `ChainFlow`, instead of a warning.
* Moving and resizing a box from a proposal (`move_text`), which would resolve a warning of
  `replace_text` from the model's side.
* A size or a width asked by a model.
* Anchors on single strokes or groups of handwriting: `inkBlocksOf` is in `drawview-lecture-ui`, and
  with `render_page` an area is enough for now.
* Rendering a closed document for a model, which needs a renderer without an editor.
* Stage 2 of `docs/plans/agent-tools.md`, the panel on every device, gets all this through the same tools.
