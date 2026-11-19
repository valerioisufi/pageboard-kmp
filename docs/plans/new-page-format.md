# The format of a new page

A page added after another one takes its format: its size and its background, as that page has
them. Today every new page takes the format of the document, whatever the page it follows. This
document starts from the code as it is (main at 1523d560, 2026-10-25): how a page is added today,
then the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **One way to make a page.** `PageManager.newPage(index)` (`drawview-canvas`) makes every new
   page with the defaults of the document: `defaultWidthMm`, `defaultHeightMm` and
   `defaultBackground`, following the background of the document (`hasCustomBackground` false).
   `addNewPageAfter(pageIndex)` puts it after a page (at the end when the index is not a page),
   `addNewPageAtBottom()` at the end; both submit `AddPage.of(page)`.
2. **Three places add a page**, all through `addNewPageAfter`:
   * the context menu of a page (`CanvasContextMenu` in `CanvasOverlays.kt`, the long press or
     the secondary click): after the page under the pointer (`contextMenuTargetPageIndex`);
   * the page grid (`PageGridOverlay`): the menu of a page, after it; the button of its header,
     after the last page;
   * the command Add Page (`EditorCommands.addPage`, the menu Insert of the menu bar): after the
     page on screen (`mostVisiblePageIndex`).
3. **The review of a lecture.** `NoteInserter.insertPages` (`drawview-lecture-ui`) lays its text
   out on the format of the document (`defaultWidthMm`, `defaultHeightMm`, less the margins), then
   adds its pages with `addNewPageAfter` (after the page on screen) or `addNewPageAtBottom`.
4. **The format of a page.** Its size (`widthMm`, `heightMm`) is always its own: nothing says that
   a size follows the document. Its background is its own when `hasCustomBackground`, else the
   document's, which it follows: `PageData.background` is `null`, `SetPageFormat` with `null`
   makes a page follow it again, `SetDocumentDefaults` changes the pages that follow it.
5. **The PDF pages.** `MediaManager.importPdf` makes each page the size of its PDF page, on the
   background of the document (following it); the PDF is an element of the page (`DrawPdf`),
   drawn over its background.
6. **The format of the document.** Its configurator asks "Apply to all pages?": "Default Only"
   changes the defaults and leaves the pages as they are (the ones following the background change
   with it), "Overwrite All" gives every page the size and makes it follow the background.
7. **What is stored and travels.** `AddPage(PageData)` carries the size and the background of the
   page (`null` when it follows the document).

## Requirements

1. **A page added after a page takes its format**: its size and its background.
2. **The background keeps its link**: after a page following the background of the document, the
   new page follows it too (a later change of the background of the document changes both); after
   a page with a background of its own, the new page has a copy of it, of its own.
3. **After a PDF page**: the size of that page and its background (the paper of the document,
   unless it was changed), never the PDF.
4. **Without a page chosen**: the button of the page grid adds at the end, like the last page; the
   command Add Page adds after the page on screen, like it.
5. **The review of a lecture keeps the format of the document**: its text is laid out on it.
6. **Nothing stored or sent changes**: `PageData` as it is, no migration, no change of the
   protocol (`ProtocolFormatTest` as it is); the other devices receive the page as it was made;
   undo removes it, as now.
7. **Every platform**: the rule lives in `drawview-canvas` (common code).

## Design

### The experience

* **The context menu of a page, the menu of a page in the grid**: "Add page after" gives a page
  like the one the menu was opened on.
* **The button of the page grid** ("Add Page", at the end): a page like the last one.
* **The command Add Page** (menu bar): a page like the page on screen, after it.
* **The review** ("Insert as new pages"): pages of the format of the document, as now.
* Nothing new is shown: the page added is changed, if needed, with its configurator ("Edit
  Background"), or undone.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The format of a new page: the one of a page given (its size, its background with its link), or the document's | `PageManager.newPage(index, template)` (`drawview-canvas`) | Creator (the page manager makes the pages of the editor), Information Expert (the page followed knows its size and whether its background is its own) |
| Which page a new page is like: the page it follows, by default | `PageManager.addNewPageAfter(pageIndex, template)`, `addNewPageAtBottom(template)` | Information Expert (the page manager knows the pages and their order), Low Coupling (the menus, the grid and the commands keep saying only where) |
| Pages of a format chosen by their content | `NoteInserter.insertPages` (`drawview-lecture-ui`), with `template = null` | Information Expert (it measured its text on the format of the document), Protected Variations (the rule of the editor can change without breaking its layout) |
| Storing and sending the page | `AddPage.of(page)`, `DrawPage.toData()` (`drawview-model`), as they are | Protected Variations (the format of `PageData` does not change) |

### The page manager

```kotlin
/**
 * Inserts a new page right after [pageIndex] (at the end when the index is invalid), of the
 * format of [template]: its size and its background, of its own or following the document
 * (`null`: the format of the document).
 */
public fun addNewPageAfter(pageIndex: Int, template: DrawPage? = pageBefore(pageIndex)): DrawPage

/** Inserts a new page at the end, of the format of [template] (by default the last page). */
public fun addNewPageAtBottom(template: DrawPage? = document.pages.lastOrNull()): DrawPage
```

* `pageBefore(pageIndex)` is the page the new one follows: the page at `pageIndex`, the last page
  when the index is not a page.
* `newPage(index, template)` takes `widthMm` and `heightMm` of the template, and its background
  only when it is its own (`hasCustomBackground`, copied): a page following the document gives a
  page following it, `AddPage.of` carrying `background = null` as for a page made today.
* Without a template, the defaults of the document, as today.
* The callers in `drawview-ui` do not change: the default of `template` is the page they add
  after. `NoteInserter` passes `template = null`.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `PageManager.addNewPageAfter` and `addNewPageAtBottom` with their `template`, `newPage` taking the size and the background with its link; `NoteInserter.insertPages` with `template = null`; tests in `drawview-canvas` (after a page of a size and background of its own, after a page following the background of the document and the document changed after, after a PDF page, an invalid index, at the end, without template, undo, the `PageData` sent) and in `drawview-lecture-ui` (the review after a page of another format, on the format of the document); ABI of `drawview-canvas` recorded again on purpose; the consumer checks; the desktop application on a scratch directory | Done |

No screenshot should change: the menus and the grid look the same (`verifyRoborazziJvm` of ui,
explorer and lecture-ui).

### Phase 1 results

* `PageManager.newPage(index, template)` takes the size of the template, and its background only
  when it is its own (`hasCustomBackground` copied); without a template, the defaults of the
  document. `pageBefore(pageIndex)` is the page a new page follows: the page at the index, the
  last page when the index is not a page.
* `addNewPageAfter(pageIndex, template = pageBefore(pageIndex))` and
  `addNewPageAtBottom(template = the last page)`: the context menu of a page, the page grid and the
  command Add Page did not change, the default being the page they add after. `DrawViewController`
  adding the first page of an empty document gets the defaults, there being no page.
* `NoteInserter.insertPages` adds its pages with `template = null`: the format of the document,
  which it lays its text out on.
* Tests: `NewPageTest` (`drawview-canvas`: after a page of its own size and background, sent with
  it and kept when the document changes; after a page following the background of the document,
  sent with `background = null` and following the document changed after; after a PDF page, its
  size, the paper of the document, no PDF; at the end, from the grid, an invalid index and
  `addNewPageAtBottom` like the last page; without a template the format of the document; undo),
  `NoteInserterTest` (`drawview-lecture-ui`: the review after a slide-sized page of its own
  background, on pages of the format of the document). ABI of `drawview-canvas` recorded again on
  purpose: `addNewPageAfter` and `addNewPageAtBottom` take a `template`.
* Nothing stored or sent changed: `PageData`, `AddPage`, the Room schema and `ProtocolFormatTest`
  are as they were.
* Checks: `jvmTest`, `checkKotlinAbi`, the Android and iOS compilations and the two Android
  applications, the screenshots of ui, explorer, lecture-ui, sync-ui and export-ui unchanged
  (`verifyRoborazziJvm`), the consumer checks. The desktop application run on a scratch directory
  (`-Ddrawview.dataDir`): it starts, makes its database at version 20, no error. Its window was not
  driven by hand: the tests above go through the same `PageManager` calls as the menus; trying
  them on the user's devices is left to the user.

## Decisions

Proposed on 2026-10-25 and approved by the user the same day ("implement phase 1"), every one
revertible:

1. **The page it follows.** The new page is like the page it is put after, everywhere: the button
   of the page grid, which adds at the end, takes the last page, not the page ringed in the grid
   (the new page is never unlike its neighbor).
2. **The size is always taken from that page.** The size of the document is used only without a
   template (the review). So after "Default Only" with a new size, a page added after a page of
   the old size keeps the old size; "Overwrite All", or the configurator of the page, changes it.
   The other choice, the size of the document winning when it changed after the format of the
   page followed (comparing `formatVersion` and `defaultsVersion`), would also change what a slide
   gives after a change of the background only: left for later.
3. **The review keeps the format of the document**, its text being laid out on it.
4. **A PDF page gives a page of its size** on its background (the paper of the document unless
   changed): the notes of a slide on a page as large as the slide.
5. **No choice in the menus** ("Add page like the document"): the configurator of the page added
   changes it, undo removes it.

## Risks

* **The public API of `drawview-canvas` changes** (a parameter with a default): the callers
  compile as they are, the binary does not match; nothing is published yet (`0.1.0-SNAPSHOT`), the
  ABI is recorded again on purpose.
* **A page followed of an odd size** (a scan, a PDF of mixed pages): the new page takes it, like
  any other page of the document; the configurator changes it.

## Left for later

* Adding a page before another.
* Choosing the format while adding (a submenu, or the configurator opened on the new page).
* The size of the document winning after "Default Only" (decision 2).
* Duplicating a page with its content.
