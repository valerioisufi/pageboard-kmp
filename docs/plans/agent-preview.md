# What a model proposes, shown on the page

The review of what an external model proposes (CLI, MCP) is a list in a panel. The user asked for the
changes to be shown on the page itself before accepting them (2026-11-12). This document gives the
starting point, the requirements, the design and the plan. It continues `docs/plans/agent-tools.md`,
`docs/plans/agent-placement.md`, `docs/plans/agent-full-access.md` and `docs/plans/agent-ergonomics.md`, and starts from
`main` at d04b4613, 2026-11-12.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Protected Variations).

## Starting point

* `AgentReviewUi` (`drawview-agent-ui`) adds a panel to the editor (`ReviewList`): for every item of
  a proposal a checkbox, a label, a line of detail (`itemDetail`: "Page 2 · 1 element · moved by
  12.5, −4 mm · scaled ×1.5") and, for a text, its words before and after.
* *Show on the page* exists for the items with a text: a new text is shown where it would go and can
  be moved (`PlacementManager`), a text replaced is only jumped to.
* Nothing is drawn for the other items, which `docs/plans/agent-full-access.md` added: elements removed,
  moved, resized, brought forward or back, a text or a stroke restyled, a stroke drawn, an image
  added, a page removed, moved, duplicated or given another format. The user reads numbers and has to
  find the elements on the page alone. An element is named by a count ("2 elements"), not shown.
* `ProposalPlanner.plan` already knows the place of the texts and of what moves or resizes
  (`PlannedItem.pieces`, since `docs/plans/agent-ergonomics.md`). It does not say where the elements were,
  nor anything of what is removed, restyled, reordered, drawn or added, nor of the pages.
* `EditorExtension.canvasOverlay` composes over the canvas, and `Viewport.pageMmToScreen` maps the
  millimeters of a page to the screen: an extension can draw on the pages with no new hook.

## Requirements

1. **Every item of a proposal waiting is drawn on the page it changes**, while its panel is open:
   * what is added (a text, a stroke, an image) as it would be, with a green frame;
   * what is removed with a red veil and frame;
   * what moves or is resized: a dashed frame where it is, a frame where it goes, an arrow between;
   * what changes in place (the words or the style of a text, a stroke restyled, the order): an amber
     frame, the new text shown as it would be when its words change;
   * a page removed with a red veil on the whole page, a page moved, duplicated or given another
     format with a label on it; a page added shown in the list only (it is not there yet).
2. **The list and the page go together**: choosing an item brings the page to what it changes and
   marks it stronger; an item unchecked is drawn faint. (A mark clicked on the page selecting its
   item was asked first and left out: decision 5.)
3. **What is drawn is what would be applied**: the marks come from the same plan as the application
   of the proposal, on the document as it is now, and follow it when it changes. A stale item is
   drawn as stale, where the element still is.
4. **Nothing is applied by looking**: the document, its history and what travels are untouched until
   the user decides. `PROTOCOL_VERSION` stays 1, Room does not change.
5. **The canvas stays usable**: the marks take no pointer outside their labels, and the user can
   pan, zoom and write while a proposal waits.
6. **A proposal of hundreds of items stays light**: only the marks of the pages on the screen are
   composed, and a plan is not made again at every stroke (as today, after a pause).
7. What a client sends stays untrusted: a mark is drawn from numbers already checked by
   `ProposalLimits`, and a box outside its page is clipped to it.

## Design

### Responsibilities

| Responsibility | Who | GRASP |
|---|---|---|
| What an item does on the pages, as marks | `PlannedItem.marks`, made by `ProposalPlanner` (`drawview-agent`, headless) | Information Expert |
| A mark: its page, its box, its kind, what to show in it | `PlannedMark` (`drawview-agent`) | Information Expert |
| Drawing the marks over the canvas | `ProposalMarks` (`drawview-agent-ui`), in the `canvasOverlay` of `AgentReviewUi` | Pure Fabrication |
| The item chosen and the items checked, shared by the list and the page | `ReviewSelection` (`drawview-agent-ui`), held by `AgentReviewUi` per proposal | Controller |
| The words of a text as they would be | The presenter of the texts of `drawview-ui` (`MarkdownText`), as the page draws them | Protected Variations |

### The marks

`PlannedMark(kind, pageId, box, toBox, elementId, markdown, fontSize, color, points, strokeWidth,
isOnNewPage)`, with `kind` one of `ADDED`, `REMOVED`, `MOVED`, `CHANGED`, `PAGE_REMOVED`,
`PAGE_CHANGED`, and `STALE` for an item that would not be applied, where what it was about still is:

| Item | Marks |
|---|---|
| `insert_text` | `ADDED` for every piece, with its text |
| `replace_text`, `update_text` | `CHANGED` on its box with the new text; `MOVED` when the box moves or is resized |
| `remove_text`, `remove_elements` | `REMOVED` on the box of every element |
| `place_elements` | `MOVED` for every element, from its box to the one it would have |
| `reorder_elements`, `restyle_text`, `restyle_stroke` | `CHANGED` on the box of every element |
| `add_stroke` | `ADDED` with its points and color |
| `add_image` | `ADDED` on the box it would take |
| `remove_page` | `PAGE_REMOVED` |
| `move_page`, `duplicate_page`, `set_page_format` | `PAGE_CHANGED` |
| `add_page`, `rename_document` | None: in the list |

The planner already walks every item on the pages as the items before it leave them. `ProposalMarks`
makes the marks afterwards, from the document as it is and from what the planner found for the item
(the boxes of its texts and of what it moves): an element is changed by one item of a proposal only
(`ProposalLimits`), so its box now is its box before the item. The box of a text is as high as its
words are laid out (`TextMeasure`), as everywhere in a plan. `pieces` stays as it is for the tools (`preview_proposal`), which do not
change: the marks are for the interface, and are not sent to a client.

### Drawing

`ProposalMarksLayer(controller, marks)` in the overlay: one `Canvas` over the canvas of the editor,
each mark clipped to its page and mapped by `pageMmToScreen`, only for the pages on the screen,
drawing the frames, the veils, the arrows and the strokes (`drawnMarks` chooses them and how strongly); the texts of `ADDED` and `CHANGED` marks composed with the presenter of the page at the
scale of the page, over a paper background so that the old words do not show through. The overlay
reads the camera as the other overlays of the editor do, so it follows a pan and a zoom frame by
frame without composing again.

### The list and the page

`ReviewSelection`: the items checked (today a local state of `ProposalCard`) and the item chosen. A
row chosen jumps to its first mark (`PageManager.jumpToPage(page, box)`), and its marks are drawn
with a thicker frame. *Show on the page* of a new text keeps what it does today (the text can be
moved before accepting); for the other items it is the choice of the row.

## Phases

One commit per phase on `feat/agent-preview`, with the checks of `CLAUDE.md`.

| # | Phase | Status |
|---|---|---|
| 1 | `PlannedMark` and `PlannedItem.marks` for every item (`ProposalMarks`), with the ABI of `drawview-agent` re-recorded. Tests (`ProposalMarksTest`): the marks of each kind of item, a text changed in place and one given another place, an item after one that removes, a stale item where what it was about still is and none when it is gone. A box outside its page is clipped when drawn (phase 2) | Done |
| 2 | `ProposalMarksLayer` in the overlay of the review: frames, veils, arrows, the strokes added and the pages with their label; `ReviewSelection`, an item chosen in the list bringing the page to it and standing out, one left out drawn faint; the plans made with the places the user chose. Tests: `ProposalMarksLayerTest` (which marks are drawn and how strongly), `AgentReviewEditorTest` in a real editor (nothing drawn before a proposal, its marks while it is reviewed, nothing applied by looking, a wheel on a mark still panning the canvas, an item chosen bringing the page back, nothing left once decided), the screenshot `agent_marks` (`verifyRoborazziJvm`) | Done |
| 3 | The words of the texts added and changed drawn as the page would draw them (`MarkdownText`), at the corner of their box, on the paper of their page, laid out once and placed by the camera where they are drawn; only those of the pages on the screen are composed. Tests: `AgentReviewEditorTest` (the words of the text changed and of the text added are on the page, a wheel on them still pans the canvas), the screenshot `agent_marks` re-recorded | Done |
| 4 | The desktop application on a scratch directory with a real client (`pencilnotes propose`): the review is opened and looked at by clicks in its window | Left to the user |

## Decisions

Taken alone, revertible.

1. **The marks are drawn while the panel of the review is open**, not whenever a proposal waits: a
   page covered by frames while the user writes would be in the way.
2. **The marks are not sent to a client**: `preview_proposal` keeps its answer.
3. **A page added is not drawn**: it would move every page after it on the canvas before anything is
   accepted. It stays in the list.
4. **No miniatures before and after in the panel** (the user chose the page).
5. **A mark takes no pointer**: clicking one does not select its item. The layer only draws, so that
   a pen, a finger and a wheel on a mark reach the canvas as always (requirement 5); a mark taking
   clicks would take strokes from the page under it. The list chooses, the page shows.
6. **The colors of the marks are fixed** (green, red, blue, amber, gray), not those of the theme: they
   are drawn on the paper of the page, which does not follow the theme, and the green of the theme is
   the color of what the application itself selects.

## Left for later

* The words drawn on the page are laid out by `MarkdownText`, the box of the plan by the editor: a
  long text may end a little above or below the frame of its mark. They are not clipped to their
  page.
* What was applied without a review (`applyDirectly`) shown on the page from the activity, beside its
  *Undo*.
* The proposals for the library (documents and folders), which have no page.
* The review on a phone, where the panel covers the canvas.
