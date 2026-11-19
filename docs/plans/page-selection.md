# Selecting several pages

In the page grid, several pages can be selected and the actions of a page run on all of them at
once: copy, paste after, change the format and the background, delete, move. Today every action
of the grid takes one page, from the menu of its card. This document starts from the code as it is
(main at 81500594, 2026-10-25): the grid and its actions, then the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The grid** (`PageGridOverlay`, `drawview-ui`): one card per page, a tap shows the page (and
   closes the grid), a long press then a drag moves the page (`PageManager.startPageReorderMode`,
   `movePageDuringReorder`, `finishPageReorderMode`, one `MovePages` at the end). The card of the
   page looked at when the grid opened is ringed. The top bar has Close, the title, Add Page.
2. **The menu of a card** (not in a read-only document): Add page after, Copy page, Paste page
   after (once pages were copied), Edit Background, Delete Page (not the last page). Each one sets
   `PageManager.contextMenuTargetPageIndex` to the page.
3. **The actions, one page each** (`PageManager`, `drawview-canvas`): `copyPage(index)` (into the
   `EditorClipboard`, as `CopiedPages`, a list already: `docs/plans/cross-document-paste.md`),
   `pastePages(index)`, `deletePage(index)` (`RemovePage`), `changeSinglePageTemplate(index, …)`
   (`SetPageFormat`), `movePage(from, to)` (`MovePages.toOrder`).
4. **The configurator of a page** (`DrawScreen`, `showSinglePageConfigurator`): opened from the
   grid (which closes) on `contextMenuTargetPageIndex`, it gives a size and a background to that
   page.
5. **Changes made of several pieces**: `CompositeChange` is one step to undo; `MovePages` takes
   several moves.

## Requirements

1. **Selecting pages in the grid**, on every platform:
   * with touch: a Select button in the top bar enters the selection, a tap then selects or
     deselects a page; a long press stays the drag;
   * with a keyboard and a pointer: ⌘/Ctrl+click adds or removes a page, Shift+click selects the
     range from the last page clicked, without entering anything first;
   * Select all, and leaving the selection (Done, Escape, closing the grid).
2. **The selection is shown**: a check on each card selected, its card ringed; the top bar says
   how many pages are selected and holds the actions.
3. **Actions on all the pages selected**, in the order of the document:
   * Copy (one `CopiedPages` of all of them, pasted in any document);
   * Paste after (the pages copied, after the last page selected);
   * Background: the configurator gives one size and background to all of them;
   * Delete, never every page of the document (disabled then);
   * Move: dragging a selected card moves all the pages selected as one block, in their order.
   Each one is one step to undo.
4. **The menu of a card stays** for a page alone; on a page of the selection it acts on the
   selection.
5. **The selection follows the pages**: kept by their identifiers, it survives a move and a change
   from another device; a page removed leaves it.
6. **Nothing stored or sent changes**: the selection is not stored and not sent to the others
   (the presence shows selections of elements only); the actions send `SetPageFormat`,
   `RemovePage`, `MovePages`, `AddPage` as today (`ProtocolFormatTest` as it is, no migration).
7. **Read-only documents**: selecting and copying only.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Which pages are selected (their identifiers, in the order of the document), the last one clicked, selecting, deselecting, ranges, all | `PageSelection` (`drawview-canvas`, Compose state), held by `PageManager.selection` | Information Expert (it knows the selected pages), Pure Fabrication, High Cohesion (apart from the reorder state) |
| The actions on several pages: copy, paste after, format, delete, move as a block | `PageManager.copyPages(ids)`, `pastePagesAfter(ids)`, `changePagesTemplate(ids, …)`, `deletePages(ids)`, `movePages(ids, toIndex)` | Controller, Information Expert (it knows the pages and their order), Creator (it makes their changes) |
| Pruning the pages removed from the selection | `PageSelection` reading `document.pages` (pages shown only) | Information Expert |
| The grid in selection: checks, rings, the clicks with their modifiers, the bar of the actions, the drag of a block | `PageGridOverlay`, a `PageSelectionBar` (`drawview-ui`) | Controller (the UI events to the page manager), Low Coupling (the actions are the manager's) |
| The configurator on several pages | `DrawScreen` (the target: the pages selected, or the page of the menu) | Indirection |
| Choosing the modifier of the platform (⌘ on Apple, Ctrl elsewhere) | `PlatformConventions` (`drawview-ui/adaptive`), as the shortcuts | Protected Variations |

### The page manager

```kotlin
/** The pages selected in the grid (see `docs/plans/page-selection.md`). */
public val selection: PageSelection

/** Copies the pages [ids] (in the order of the document) into the clipboard of the application. */
public fun copyPages(ids: Collection<String>)

/** Gives the pages [ids] the size of [dimension] and [background], in one change. */
public fun changePagesTemplate(ids: Collection<String>, dimension: Dimension, background: PageBackground)

/** Deletes the pages [ids] in one change, unless they are every page of the document. */
public fun deletePages(ids: Collection<String>)

/** Moves the pages [ids] as a block, in their order, to [toIndex] among the other pages. */
public fun movePages(ids: Collection<String>, toIndex: Int)
```

The functions of one page stay and call these (`deletePage(index)` → `deletePages(listOf(id))`).

### The grid

* Not selecting: as today; ⌘/Ctrl+click or Shift+click starts a selection with a keyboard.
* Selecting: the top bar becomes the bar of the selection: Done, "N selected", Select all, and the
  actions (Copy, Paste after, Background, Delete; in a compact window, the less frequent in a
  menu). A tap selects or deselects; a long press and a drag on a selected card moves the block
  (the cards of the block follow the finger, the others make room), on a card not selected moves
  that page alone, as today.
* The menu of a card on a page selected acts on the selection; on another page, on that page.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `PageSelection` and the actions on several pages in `PageManager` (copy, paste after, format, delete, move as a block, one change each), the single-page functions calling them; tests in `drawview-canvas` (selection in the order of the document, ranges, all, pruned when a page is removed here or by another device, kept across a move; each action on several pages, one undo; delete never every page; read-only); ABI recorded on purpose | Done |
| 2 | The grid: Select / Done, the taps, ⌘/Ctrl+click and Shift+click, Escape, checks and rings, the bar of the selection with its actions, the menu of a card on the selection, the configurator on several pages; the strings in the seven languages; screenshots of the grid in selection (compact and wide, light and dark) | Done |
| 3 | Dragging the selection as one block in the grid; tests of the order given by the drop; the desktop application on a scratch directory | Done |

### Phase 1 results

* `PageSelection` (`drawview-canvas`, `PageManager.selection`): the identifiers of the pages
  selected (Compose state), `pageIds` in the order of the document and among the pages shown
  only (a page removed here or by another device leaves it, a page moved keeps its place in it),
  `count`, `isActive` (the grid selecting), `start`, `toggle`, `selectRange` (from the last page
  toggled, the page itself without one), `selectAll`, `clear` (leaving the selection).
* `PageManager`: `copyPages(ids)`, `pastePagesAfter(ids)`, `changePagesTemplate(ids, dimension,
  background)`, `deletePages(ids)` with `canDeletePages(ids)`, `movePages(ids, toIndex)`; each one
  takes the pages in the order of the document and makes one change (the change itself for one
  page, a `CompositeChange` for several; `MovePages.toOrder` for a move). `copyPage`,
  `deletePage` and `changeSinglePageTemplate` call them: what one page sends is as before.
* Tests: `PageSelectionTest` (`drawview-canvas`): selecting one by one, a range from the last page
  toggled, all, clearing; the selection kept across a move and left by a page removed by another
  device; deleting two pages in one step and undone, never every page; two pages copied in the
  order of the document and pasted after the last page of a selection in another document, undone;
  one format for two pages in one step, undone; a block gathered from pages apart, moved after the
  first of the others in one change, to the end and to the start, undone; a read-only document
  copied only.
* ABI of `drawview-canvas` recorded again on purpose: `PageSelection` and the new functions only.

### Phase 2 results

* `PageGridOverlay` (`drawview-ui`): Select (a checked square) in the top bar; while selecting,
  `PageSelectionBar`: Done, "N pages selected", Select all, Copy, Paste page after (once pages were
  copied), Edit Background, Delete (disabled for every page); in a compact window Select all,
  Paste after and Background go in a menu (⋯), so that the count is not cut. A read-only document
  selects and copies only.
* The clicks of a card (`pageClicks`, the pattern of the files of the library): the keys held at
  the press are read by the click: Shift → `selectRange`, ⌘ (Apple) or Ctrl (the others) →
  `toggle`, else a tap toggles while selecting, shows the page otherwise. The long press stays the
  drag.
* A card selected: ringed in 3 dp, a check at its corner, `selected` in its semantics; the card
  carries the description "Page N" (the thumbnail none: it was the only one, and only once drawn).
* The menu of a card on a page selected acts on all of them: Copy page, Paste page after, Edit
  Background, Delete Page (shown when they can be deleted); Add page after stays on the page.
  `EditorCommands.copyPages(ids)` and `pastePagesAfter(ids)` (the text of the system kept as for
  one page).
* The configurator (`DrawScreen`): opened from the grid on several pages (`onEditPagesBackground`,
  their identifiers taken before the grid closes), "N pages of “document”", from the format of
  the first one, `changePagesTemplate` on Apply.
* Leaving the selection: Done, the back of the system (Escape on the desktop, before closing the
  grid: `EditorBackHandler`), closing the grid (`togglePageGrid`) or showing a page (`jumpToPage`).
* Found by the test of the grid: its items read `document.pages` when composed, after the count
  of an earlier composition; pages removed meanwhile (two at once) gave an index out of the list.
  The grid reads the list once and its items read that list.
* The strings in the seven languages: `page_grid_action_select`, `page_grid_action_done`,
  `page_grid_action_select_all`, the plurals `page_grid_selected_count` and
  `page_config_subtitle_pages`.
* Tests: `PageGridSelectionTest` (`drawview-ui`: Select, two taps selecting without leaving the
  grid, the count, Delete of the two pages, Delete disabled after Select all, Done). Screenshots:
  `editor-page-grid-selection` (tablet light and dark, phone light) new; `editor-page-grid`
  (tablet, phone) recorded again with Select in the top bar.

### Phase 3 results

* `PageManager.moveBlockDuringReorder(ids, toIndex)` (`drawview-canvas`): during a reorder session
  it puts the pages together, in their order, at `toIndex` among the others, live
  (`DrawDocument.restorePageOrder`); `finishPageReorderMode` records the whole drag as one
  `MovePages`, as for one page. Without a session, `movePages`.
* The grid (`PageGridOverlay`): a long press on a card of a selection of several pages drags the
  block: at the start the block is gathered around that card (the pages of the block before it
  just before it), then over another page it goes before it going up, after it going down; the
  card dragged keeps following the finger where it lands. A card not selected, or alone in the
  selection, is dragged as before.
* Tests: `PageSelectionTest.aSelectionDraggedInTheGridIsOneChangeAtTheDrop` (`drawview-canvas`:
  gathered, moved, nothing recorded while dragging, one change at the drop, undone),
  `PageGridSelectionTest.aSelectedPageDragsTheSelectionAsOneBlock` (`drawview-ui`: a long press
  and a drag of the third page of a selection of the first and the third over the fourth gives
  2, 4, 1, 3, the selection kept, undone in one step).
* ABI of `drawview-canvas` recorded again on purpose: `moveBlockDuringReorder`.
* Checks: `jvmTest`, `checkKotlinAbi`, `verifyRoborazziJvm` of the five UI modules, the Android
  and iOS compilations, the two Android applications, the consumer checks. PencilNotes desktop run
  on a scratch directory (`-Ddrawview.dataDir`): it starts, its database at version 21, no error.
  Its window was not driven by hand: the tests of the grid drive its taps and its drag; trying it
  with ⌘/Ctrl+click and Shift+click on the maintainer's devices is left to the maintainer (the clicks with
  modifiers are not driven by the tests).

## Decisions

Proposed on 2026-10-25 and approved by the maintainer the same day ("implementa tutte le fasi"); every
one revertible:

1. **The selection lives in the grid**: closing the grid clears it; the canvas does not select
   pages (its context menu stays on one page).
2. **The configurator of several pages starts from the first page selected** (its size and
   background shown), and gives the same to all of them.
3. **Delete is disabled when every page is selected**, rather than deleting all but one.
4. **Paste after puts the pages after the last page selected.**
5. **A long press stays the drag**, also in selection: selecting with touch goes through Select.
6. **Moving a block puts its pages together**, in their order, where it is dropped (pages selected
   apart are gathered).

## Left for later

* Exporting the pages selected (the export dialog takes the whole document today).
* Cut (copy then delete) on pages.
* Selecting pages on the canvas, or by dragging a rectangle in the grid.
