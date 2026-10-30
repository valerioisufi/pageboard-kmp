# The page grid following the document

The page grid does not show the changes of the pages made while it is open: pages deleted, added,
pasted, restored, moved by dragging, undone or changed by another device stay as they were. This
document starts from the code as it is (main at 18f34cfa, 2026-10-30): the cause, then the design
and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The model is not Compose state** (`docs/modularization.md`): `DrawDocument.pages` is a
   `@Volatile` list, replaced when a page is added, removed or moved, and the same list when the
   content of a page changes. What shows the document calls `observeChanges()`, which collects
   `DrawDocument.revision` and recomposes the caller.
2. **The grid** (`PageGridOverlay`, `drawview-ui`) calls `observeChanges()`, then reads
   `document.pages` and `PageManager.deletedPagesAt(now)` inside the content of its
   `LazyVerticalGrid`.
3. **The content of a lazy grid is run again** only when its lambda is another instance, or when
   a state it reads changes. Its lambda captures the same objects at every recomposition
   (`document`, `controller`, `selection`, the states of the drag), so with strong skipping
   (Kotlin 2.4) the compiler remembers it, and the grid keeps the pages it counted first. The
   title of the grid, outside of it, shows the new number of pages.
4. **A card reads its page by its position** in the list counted first: after a page removed, its
   label, the page shown by a tap (`jumpToPage(index)`) and the page added after it
   (`addNewPageAfter(index)`) are those of another page. Each card follows the content of its page
   by itself (`rememberPageThumbnail` calls `observeChanges()`), so the thumbnails are right.
5. **The page looked at when the grid opened** is remembered by its index: after a page removed or
   moved before it, the ring goes to another page.
6. **The bar of the selection** (`PageSelectionBar`) reads `PageSelection.count`, which filters the
   identifiers selected (Compose state) by `document.pages` (not state). `PageSelection` is
   `@Stable`, so the bar is skipped when its other parameters are the same: a page selected
   removed by another device, or by an undo, stays counted.
7. **The tests** of the grid (`PageGridSelectionTest`, `DocumentObservationTest`) check the model
   and the title, never the cards shown.

## Requirements

1. The grid shows the pages of the document as they are, after every change, here or from another
   device: deleted, added, pasted, restored, moved (by dragging in the grid too), undone, redone.
2. The deleted pages after the others (`docs/trash.md`) show as they are: the row appears with the
   first page deleted, the pages restored leave it.
3. A card acts on its page: its label, a tap, its menu.
4. The ring stays on the page looked at when the grid opened, wherever it goes; none when it is
   deleted.
5. The bar of the selection counts the pages selected still in the document.
6. Nothing stored or sent changes: no migration, `ProtocolFormatTest` as it is, no public API.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Knowing the pages shown and the deleted ones | `DrawDocument`, `PageManager.deletedPagesAt` (as today) | Information Expert |
| Taking them once per recomposition, after `observeChanges()`, and building the grid from what it took | `PageGridOverlay` (`drawview-ui`): `pages` and `deleted` read in its composition, captured by the content of the grid | Controller (the view reads the model when it changes), Low Coupling (the model stays without Compose) |
| The page looked at when the grid opened, by its identifier | `PageGridOverlay` | Information Expert (the identifier follows the page, the index does not) |
| Counting the pages selected for the bar | `PageGridOverlay`, passing `count` to `PageSelectionBar` | Information Expert (the overlay is the one that follows the document), Protected Variations (the bar does not depend on how the selection reads the document) |

### The grid

```kotlin
document.observeChanges()
// The pages and the deleted ones as they are now: the content of the grid is built from them,
// a new list after every change of the pages
val pages = document.pages
val deleted = if (writable) pageManager.deletedPagesAt(currentTimeMillis()) else emptyList()
LazyVerticalGrid(…) {
    items(pages.size, key = { pages[it].id }) { index -> … }
    deletedPages(controller, deleted, …)
}
```

The list of the pages is another instance after every change of the pages, and `deletedPagesAt`
returns a new list at every call: the content of the grid is another lambda at every recomposition
of the overlay, so the lazy grid counts its items again. A change of the content of a page leaves
the list as it is; its card follows it as today.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | The grid built from the pages and the deleted pages read in the composition; the ring on the page by its identifier; tests of the cards shown and of the deleted pages after delete, add, restore, undo and a removal by another device (`PageGridUpdatesTest`) | done |
| 2 | The bar of the selection counting the pages selected still shown; its test | to do |

## Decisions

Taken alone, revertible:

1. **The lists read in the composition**, not the revision read inside the content of the grid: the
   content stays built from one list taken once, which an item reads too (the comment of the grid
   says why), and no read of a value only to make the lambda change.
2. **`count` passed to the bar** rather than `PageSelection` made unstable or a state of its own:
   `PageSelection` is public, and marking it unstable would not make the bar recompose (the same
   instance is compared).

## Left for later

1. **Each card collects the revision** (`rememberPageThumbnail`): every card shown recomposes at
   every change of the document, a stroke too. A thumbnail keyed on the content version of its page
   only (a revision per page) would recompose the card of that page alone.
2. **Purging the trash** (`DrawDocument.purgeHidden`) does not change the revision: a grid open
   then shows the pages purged until the next change of the document.
3. **`PageSelection.count` and `pageIds` outside the grid** still read the document without
   following it; who shows them must observe the document.
