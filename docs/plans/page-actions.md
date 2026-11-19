# Duplicating pages, adding a page before another

Two actions on the pages left for later by `docs/plans/new-page-format.md` ("Duplicating a page with its
content", "Adding a page before another"). This document starts from the code as it is (main at
ee07934d, 2026-11-14).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **A page is added after another only** (`PageManager.addNewPageAfter`, `addNewPageAtBottom`),
   of the format of the page it follows (`docs/plans/new-page-format.md`). A page before the first one
   is made by adding it after, then moving it.
2. **A page is copied with its content through the clipboard**: `copyPages(ids)` puts a
   `CopiedPages` in the `EditorClipboard` of the application, `pastePages(index)` pastes it with
   `CopiedPages.pasteInto` (new identifiers for the pages and the elements, the files missing on
   this device left out), one change. Duplicating is Copy then Paste after, two actions, and it
   overwrites what the user had copied.
3. **The menus**: the context menu of a page on the canvas (`CanvasOverlays.kt`), the menu of a
   card in the page grid and the bar of the selection of pages (`PageGridOverlay.kt`).

## Requirements

1. **Duplicate**: the pages chosen (one, or the pages selected in the grid, in the order of the
   document) copied with their content, size and background, right after the last of them; the
   first copy shown; one step to undo; the clipboard left as it is.
2. **Add page before**: a new page right before a page, of the format of that page (its size, its
   background with its link to the document, as after).
3. In the context menu of a page, in the menu of a card (on the selection when the card is in
   it), in the bar of the selection (Duplicate); not in a read-only document.
4. **Nothing stored or sent changes**: the changes are `AddPage` (and the `AddElements` of
   `pasteInto`), as a paste; no migration, `ProtocolFormatTest` as it is.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Duplicating pages: their copy, where it goes, the change | `PageManager.duplicatePages(ids)` (`drawview-canvas`), from `copyPages` and `pasteInto` of the model without the clipboard | Information Expert (it knows the pages and their order), Creator (it makes their change), Controller |
| A page before another, of its format | `PageManager.addNewPageBefore(pageIndex, template)`, with `newPage` as after | Creator, Information Expert |
| The menus and the bar | `CanvasOverlays`, `PageGridOverlay` (`drawview-ui`), `EditorCommands.duplicatePages` (writable only) | Controller, Low Coupling |

```kotlin
/** Duplicates the pages [ids] with their content right after the last of them, in one change. */
public fun duplicatePages(ids: Collection<String>)

/** Inserts a new page right before [pageIndex], of the format of that page by default. */
public fun addNewPageBefore(pageIndex: Int, template: DrawPage? = pageBefore(pageIndex)): DrawPage
```

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `PageManager.duplicatePages` and `addNewPageBefore`; the menus (canvas, card), Duplicate in the bar of the selection, the icon `CopyPlus`, the strings in the seven languages; `PageActionsTest`; ABI of `drawview-canvas` recorded on purpose | Done |

### Phase 1 results

* `duplicatePages` copies with `DrawDocument.copyPages` and pastes with `CopiedPages.pasteInto` at
  the index after the last page: the same change as Copy and Paste after, the clipboard untouched.
* `addNewPageBefore(index)` takes the page at the index as its template; an index that is not a
  page adds at the end, like `addNewPageAfter`.
* Tests (`PageActionsTest`): a page duplicated with a copy of its text, its size and background,
  undone in one step; pages selected apart duplicated after the last one; the clipboard kept; a
  read-only document unchanged; a page before another like it, before the first, at the end for an
  index out of the pages.

## Decisions

Taken alone on 2026-11-14 (the maintainer asked to go on without asking), every one revertible:

1. **Duplicate puts the copies after the last page chosen**, together, rather than each copy after
   its page: as Paste after the selection.
2. **The page before is like the page it goes before** (not like the one before it): it is the
   page the user opened the menu on.
3. **No shortcut** for Duplicate (⌘D duplicates the elements selected).

## Left for later

* Cut on pages (from `docs/plans/page-selection.md`).
* Choosing the format while adding.
