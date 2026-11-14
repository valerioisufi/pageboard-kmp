# The Trash: a preview, several items at once, ⌘Z

Three things left for later by `docs/trash.md`: a preview, read only, of a document in the trash;
selecting several items in the Trash; ⌘Z in the library undoing the last move to the trash. This
document starts from the code as it is (main at 9d7e1184, 2026-11-14).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The Trash** (`TrashContent.kt`, `drawview-explorer`) is a row per item: an icon, the name,
   where it was and the days left, Restore, and a menu with Delete for good (asked first,
   `LibraryDialog.DeleteForGood` of one item). A tap on a row does nothing: what a document holds
   can only be seen by restoring it.
2. **Deleting** in the library (`FileExplorerViewModel.deleteFiles`) moves to the trash at once and
   tells it with a snackbar with Undo (`trashNotice`, `undoTrash`), for as long as it shows. The
   menu Edit has no Undo, and ⌘Z does nothing in the library.
3. **The thumbnails** (`DocumentThumbnails`) draw the first page of a document from the store
   (`DocumentStore.loadPage`, which reads a page of any document, in the trash too) with the
   renderer of the tiles.

## Requirements

1. **A preview of a document in the trash**: a tap on its row (Enter or Space on a keyboard) shows
   its pages as the editor draws them, read only, with its name, the days left, Restore and Close.
   Nothing can be changed there; nothing is loaded into an editor or a session.
2. **Several items at once**: a long press on a row (⌘/Ctrl+click, Shift+click for a range with a
   keyboard and a pointer) selects; then a tap selects or deselects. A bar says how many are
   selected, with Select all, Restore, Delete for good (asked first, one question for all of them)
   and Done. The selection follows the trash (an item restored or purged leaves it); leaving the
   Trash clears it.
3. **⌘Z in the library** puts back what was moved to the trash last, as the Undo of the snackbar,
   also after the snackbar is gone, until something else is moved or it is put back; in the menu
   Edit as "Undo Move to Trash".
4. **Nothing stored changes**: the catalog, its stores and their schema as they are; no migration.
5. The seven languages; light and dark; phone, tablet, desktop.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The items selected in the trash, selecting, ranges, all, pruning | `FileExplorerViewModel` (`trashSelection`, `toggleTrash`, `selectTrashRange`, `selectAllTrash`, `clearTrashSelection`) | Information Expert (it holds the trash), Controller |
| Restoring and deleting several items | `FileExplorerViewModel.restore(items)`, `deleteForGood(items)` | Controller |
| What ⌘Z puts back | `FileExplorerViewModel.lastTrashed`, `undoLastTrash()` | Information Expert (it moved them) |
| The command Undo, Delete for good of several items | `LibraryCommands.undoTrash`, `LibraryDialog.DeleteForGood(items)` | Controller |
| Drawing a page of a document for the preview | `DocumentThumbnails.page(documentId, index, widthPx)`, memory only | Information Expert (it draws pages), Pure Fabrication |
| The preview | `TrashPreview` (`drawview-explorer`), opened by `FileExplorerViewModel.previewed` | High Cohesion, Low Coupling (no editor, no session) |
| The rows, the bar of the selection | `TrashContent.kt` (`TrashRow`, `TrashSelectionBar`) | Controller |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | The selection of the trash and its bar, Restore and Delete for good of several items, ⌘Z and Undo in the menu Edit, the preview with `DocumentThumbnails.page`; the strings in the seven languages; tests of the view model; screenshots | Done |

## Decisions

Taken alone on 2026-11-14 (the user asked to go on without asking), every one revertible:

1. **The preview draws the pages as images**, from the store, rather than opening the document in
   the editor read only: a document in the trash is never opened in a session, so nothing (an
   editor, the synchronization) can change it there, and no read-only state has to be kept apart
   from the one of the sharing.
2. **A folder has no preview**: its row shows how many notes it holds; restoring it shows them.
3. **⌘Z undoes the last move to the trash only**, not the moves or the renames of the library: those
   have no history today.
4. **The preview draws the pages one after the other as they come into sight**, at the width of the
   window, kept in memory while it is open.

## Left for later

* Dragging a range of rows to select them.
* A preview of the documents inside a folder in the trash.
* An undo history of the library (moves, renames, new folders).

## Phase 1 results

* `FileExplorerViewModel`: `trashSelection` with `toggleTrash`, `selectTrashRange` (from the item
  toggled last, in the order shown), `selectAllTrash`, `clearTrashSelection` (also on leaving the
  Trash); `restore(items)`, `deleteForGood(items)`; `lastTrashed` and `undoLastTrash` (the Undo of the
  snackbar ends it too); `previewed`, set back to `null` when its document leaves the trash.
* `LibraryCommands.undoTrash` (⌘Z, menu Edit), `selectAll` and `delete` acting on the trash in the
  Trash (Backspace asks once for the items selected); `LibraryDialog.DeleteForGood` takes a list,
  its question "Delete N items for good?" for more than one.
* `TrashRow` and `TrashSelectionBar` (`TrashContent.kt`); `TrashPreview`, hosted by the screen,
  drawing the pages with `DocumentThumbnails.page` until the store has no more.
* Tests (`LibraryCommandsTest`): several items selected, a range, all, restored and deleted for
  good in one question, the selection left with the Trash; ⌘Z after the snackbar, once; the preview
  closed by a restore.
* The screenshot `library-trash` keeps its look (checked on the images rendered on Windows; the
  references are of the Mac). The selection and the preview have no screenshot yet: recording one
  is left to the Mac.
* ABI of `drawview-explorer` recorded on purpose (`DocumentThumbnails.page`, the strings); its klib
  dump written by hand on Windows.
