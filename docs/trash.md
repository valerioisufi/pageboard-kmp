# The trash

A document, a folder or a page deleted is not lost at once: it goes to the trash, it can be put
back for 30 days, and only then it is deleted for good. Every deletion can also be undone right
away, from the message that tells it. This document starts from the code as it is (main at
7121125a, 2026-10-29): how documents, folders and pages are deleted today, then the design and the
plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **A document deleted is gone at once.** `DocumentCatalog.deleteDocument` removes it with its
   content and the files of its resources:
   * `RoomDocumentStore` (`FileRepository.deleteDocument`): the row of the document, its pages,
     elements, operations and sharing by the cascade of the foreign keys, then the files no other
     document points at;
   * `KeyValueDocumentStore` (`deleteDocumentLocked`): the same, key by key.
2. **A folder deleted takes its whole content**: `deleteFolder` deletes its sub-folders and their
   documents, recursively.
3. **The library asks first, then deletes** (`drawview-explorer`): Delete in the menu of an item,
   in the bar of the selection, or Backspace / Delete (`LibraryCommands`) opens
   `LibraryDialog.Delete`, "Deleted items are not recoverable."; a shared note adds "the others
   keep their copy". `FileExplorerViewModel.deleteFiles` then calls the store. Nothing can be
   undone.
4. **What follows a document deleted**, watching `DocumentCatalog.documents`:
   * the lectures of PencilNotes (`PencilNotesApp`: the ids gone from the list go to
     `LectureAssistant.forgetDocuments`, which deletes their recordings);
   * the search index (`SearchIndexer` removes the documents no longer in the catalog);
   * the sync: the sharing of the document goes with it (the `shared_documents` row by the
     cascade), the device stops syncing it, the others keep their copy.
5. **A page deleted is hidden, not deleted.** `RemovePage` marks it `removed` with its stamp and
   the document keeps it among its `removedPages` (`DrawDocument.isShown`), with its content. Undo
   (`AddPage.of(page)`, the inverse of the removal) shows it again, here and on the other devices.
   * In a shared document, a hidden page is dropped for good when every member has the removal
     (`OpenDocuments.compact` → `DrawDocument.purgeHidden`, `docs/quiescent-compaction.md`).
   * In a document not shared, nothing compacts: a hidden page stays forever, unseen.
6. **Nothing shows what was deleted**: the page grid shows the pages shown only, the library the
   documents of the store.
7. **The library** has three sections in its navigation pane (`FileExplorerViewModel.Section`:
   HOME, RECENT, SHARED) and the folders; the editor has a snackbar host (`DrawScreen`,
   `DesignSnackbar`).

## Requirements

1. **Deleting moves to the trash**, without a dialog: a document, a folder (with its whole
   content, as one item), several items selected. A message says it ("“Algebra” moved to the
   Trash", "3 items moved to the Trash") with **Undo**, which puts them back.
2. **The Trash, a section of the library** (the last of the navigation pane, with the number of
   items):
   * the items deleted, the most recent first: name, where they were, how many days are left;
     a folder is one item ("Università · 12 notes");
   * **Restore** puts an item back where it was; when that folder is gone or in the trash, at the
     root; a name already taken gets "name (2)";
   * **Delete for good** (asked first, "cannot be undone") and **Empty Trash**;
   * a line says that the items are deleted for good after 30 days.
3. **What is in the trash is out of the library**: not in Home, Recent, Shared, the tags, the
   folders, the search, the export, the names taken.
4. **Nothing of a document in the trash is lost before it is deleted for good**: its pages, its
   resources, its lectures, its sharing, its thumbnail. Deleted for good, it goes as today
   (lectures included).
5. **A shared document in the trash keeps syncing**, unseen: restored, it is as the others left
   it. The trash is of this device: the others keep their copy, as today. Nothing that travels
   changes.
6. **Deleted for good after 30 days**: the store deletes what has been in the trash longer, when
   the application starts and once a day while it runs.
7. **The pages deleted of a document**, in its page grid:
   * a message with **Undo** when pages are deleted (in the grid and in the editor);
   * a section "Deleted pages" at the end of the grid, closed by default, with the pages removed
     in the last 30 days (here or on another device), how many days are left, and **Restore**
     (one or several), which puts them back where they were, as one step to undo;
   * not in a read-only document.
8. **A page deleted is kept 30 days**, in a shared document too: the compaction does not drop a
   page removed less than 30 days ago. A page removed longer ago is dropped as today (shared) or
   on the daily pass (not shared).
9. **Stored data**: a migration tested for the Room schema and for the index of
   `KeyValueDocumentStore`; the documents of the user never touched by it.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Which documents and folders are in the trash, since when, from where; moving to it, restoring, deleting for good, the items older than a date | `DocumentCatalog` (`drawview-store`): `trash`, `trashDocument`, `trashFolder`, `restore`, `purgeTrash`; `RoomDocumentStore`, `KeyValueDocumentStore` | Information Expert (the store knows its documents), Protected Variations (one interface, two stores) |
| An item of the trash | `TrashedItem` (`drawview-store`) | Information Expert |
| The rules every store follows (what is hidden, names, restore, purge) | `TrashContract` (`drawview-store-testing`), run by both stores | Pure Fabrication |
| How long the trash keeps things | `TrashRetention` (`drawview-store`, 30 days) | Pure Fabrication, Protected Variations |
| Purging when the application starts and every day | `TrashKeeper` (`drawview-explorer`), started by `DocumentLibrary` | Controller, Pure Fabrication |
| Deleting from the library, Undo, the Trash section and its actions | `FileExplorerViewModel` (`trashFiles`, `restore`, `deleteForGood`, `emptyTrash`, `Section.TRASH`), `LibraryPanes`, a `TrashContent` (`drawview-explorer`) | Controller, High Cohesion |
| How the trash splits a library (what is out of it, the items, where one goes back, the names free) | `LibraryTrash` (`drawview-store`), built by both stores from what they keep | Pure Fabrication, Information Expert |
| Lectures forgotten only for documents deleted for good | `PencilNotesApp` (the ids gone from documents and trash together) | Information Expert stays the catalog, Low Coupling |
| The sharing of a shared document in the trash kept as it changes | `StoreSharedDocuments.save` knowing a document in the trash (not one still to come) | Information Expert |
| The pages removed of a document, the most recent first | `DrawDocument.removedPages` made public as a list (`drawview-model`) | Information Expert |
| Restoring pages | `PageManager.restorePages(ids)` (`drawview-canvas`): `AddPage.of(page)` for each, one change | Controller, Creator |
| Keeping a page removed 30 days in the compaction, dropping the older ones of a document not shared | `OpenDocuments.compact` (`drawview-store`) and `purgeExpired`, with the time of the removal (`Stamp.time`) | Information Expert |
| The section "Deleted pages" of the grid | `PageGridOverlay` (`drawview-ui`) | Controller |

### The catalog

```kotlin
/**
 * A document or a folder in the trash ([documentIds]: the documents it holds, itself for a
 * document; [folderId]: where it was).
 */
public data class TrashedItem(
    val id: String,
    val kind: Kind,
    val name: String,
    val folderId: String?,
    val trashedAt: Long,
    val documentIds: Set<String>,
    val isShared: Boolean
) {
    public enum class Kind { DOCUMENT, FOLDER }
}

public interface DocumentCatalog {
    /** Every document out of the trash (neither itself nor one of its folders in it). */
    public val documents: Flow<List<DocumentSummary>>

    /** Every folder out of the trash. */
    public val folders: Flow<List<FolderSummary>>

    /** What is in the trash, the most recent first: the items deleted, not what they hold. */
    public val trash: Flow<List<TrashedItem>>

    public suspend fun trashDocument(documentId: String): Boolean
    public suspend fun trashFolder(folderId: String): Boolean

    /** Puts [itemId] back into its folder (at the root when that is gone or in the trash), renamed "name (2)" when its name is taken; its new name. */
    public suspend fun restore(itemId: String): String?

    /** Deletes for good what went to the trash before [before] (the documents as [deleteDocument]). */
    public suspend fun purgeTrash(before: Long)

    // deleteDocument and deleteFolder stay: deleting for good.
}
```

* **The stored form**: `trashedAt` (nullable) on documents and folders. A folder in the trash
  keeps its content as it is; what it holds is out of the library because of it. An item deleted
  on its own before its folder keeps its own date: when the folder is deleted for good, it moves
  to the root of the trash and keeps its countdown.
* **Names**: `isDocumentNameAvailable` and the names of the folders ignore the trash; a name
  taken meanwhile is resolved on restore.
* **The drawview libraries are not released yet**: the new members of `DocumentCatalog` are
  abstract. Its ABI changes on purpose.

### Who watches the catalog

* **Lectures**: deleted only when a document is gone from `documents` and from the documents of
  `trash` together (the documents of a folder in the trash included).
* **Search**: unchanged. `SearchIndexer` drops the documents out of `documents`, so a document
  in the trash leaves the index and is indexed again once restored: the search finds only what
  is in the library, without `SearchEngine` knowing the catalog.
* **Sync**: unchanged but for `StoreSharedDocuments.save`, which kept in memory the sharing of a
  document missing from `documents` (one joined, still to come): a document in the trash is
  there, its sharing is stored. The engine keeps syncing it.
* **Thumbnails**: kept; the Trash shows them.
* **Export**: unchanged, it reads `documents`.

### The library

* Delete (menu, bar, Backspace / Delete) moves to the trash at once; `LibraryDialog.Delete` is
  left to Delete for good and Empty Trash. The snackbar of the library says what moved, with
  Undo (`restore` of each item) for as long as it shows.
* `Section.TRASH`: last in the navigation pane, its count beside it. Where the pane is not
  (a phone, a narrow window), a button of the bar of the library opens it, and the title of the
  section has a button back.
* The content of the section: rows (never the grid), each with its icon, its name, "from
  Algebra · 12 days left", Restore and a menu with Delete for good; the header: Empty Trash.
  No editor on a document in the trash.
* The strings in the seven languages of the explorer.

### The pages

* `DrawDocument.removedPages(): List<DrawPage>`, the pages hidden, the most recent removal first.
* `PageManager.restorePages(ids)`: `AddPage.of(page)` for each, in one `CompositeChange`: the
  same change as an undo of the removal, so nothing new travels. A page goes back to its order
  key, among the pages shown.
* **The compaction** drops a hidden page only when every member has its removal (as today)
  **and** it was removed more than `TrashRetention` ago (`removed.time`, bounded to one hour ahead
  by `docs/untrusted-members.md`). A page kept stays hidden in the stored document after the
  operations leave the log; a later compaction, or the daily pass, drops it.
* **A document not shared** has no compaction: `OpenDocuments.purgeExpired(before)` drops its
  pages removed before the date, called by `TrashKeeper` for the documents not shared.
* The grid: a row "Deleted pages (N)" after the last page, closed by default; open, the cards of
  the pages removed, faded, with the days left; tapping one selects it, Restore in the bar of the
  page selection (`docs/page-selection.md`). The snackbar "N pages deleted · Undo" in the grid and
  in the editor (undo of the history).

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | The trash in the stores: `TrashedItem`, `LibraryTrash` (shared by the stores), the new members of `DocumentCatalog`, `TrashRetention`; Room 22 (`trashedAt` on `documents` and `folders`) with its migration tested by `MigrationTestHelper` on a database of 21 filled with documents, folders, sharing; the index of `KeyValueDocumentStore` with `trashedAt`, an index stored before read as it was; `TrashContract` in `drawview-store-testing` (hidden from documents and folders, names, restore to the folder or the root, "name (2)", purge by date, an item inside a folder in the trash keeping its date, resources kept until the purge); ABI recorded on purpose. The library still deletes for good | Done |
| 2 | The watchers: lectures forgotten only when deleted for good (`PencilNotesApp`); the search dropping a document in the trash and indexing it again once restored; the sharing of a shared document in the trash stored as it changes (`StoreSharedDocuments`); tests in `drawview-sync` (`StoreSharedDocumentsTest`, the sync modules also on the iOS simulator), `drawview-search` (`SearchIndexerAndEngineTest`) | Done |
| 3 | The library: Delete moves to the trash at once, with a message and Undo (`TrashNotice`); the Trash section (the navigation pane, a button of the bar where the pane is not), its rows with Restore and Delete for good, Empty Trash, both asked first; `TrashKeeper` purging at start and every day (`LocalLibraryClock`, a fixed time in the screenshots); `DesignSnackbar` public; the strings in the seven languages; tests (`LibraryCommandsTest`, `TrashKeeperTest`); screenshots `library-trash` (phone, tablet, desktop light and dark) and `library-trash-empty`, the others with the Trash in the pane and the bar; the desktop application on a copy of the real database: Room 21 to 22, its 26 documents, 4 folders and 1144 pages kept | Done |
| 4 | The pages: `removedPages`, `restorePages`, the compaction keeping 30 days, `purgeExpired` for documents not shared; the section of the grid and the snackbars; tests in `drawview-model`, `drawview-canvas`, `drawview-sync` (a page restored after the operations left the log, on two devices); screenshots of the grid | Planned |

## Decisions

Taken alone, revertible:

* **30 days**, fixed. A setting (7, 30, 90 days, never) can come later on `TrashRetention`.
* **The trash is of this device.** It does not travel: the protocol stays as it is. Trashing a
  document on one device of the user does not trash it on the others, as deleting does not today.
* **A shared document in the trash keeps syncing**: stopping it would reach into the sharing of
  the engine (its cache, the memberships received meanwhile) for little.
* **The search index of the trash is dropped**, and built again on restore: the search needs no
  filter of its own.
* **A name taken on restore** is changed in the store only ("name (2)"), not by a change of the
  document: on a shared document, the next rename of any device wins.
* **The rows of the Trash have their actions each**, no selection of several items.
* **No editor on a document in the trash**: restore it to open it.
* **The pages have no Delete for good**: in a shared document only the compaction may drop them.
* **Elements deleted are not in the trash**: undo covers them.

## Left for later

* A preview, read only, of a document in the trash.
* The trash across the devices of the user (a protocol change, with its version).
* The retention as a setting.
* ⌘Z in the library undoing the last move to the trash.
* Selecting several items in the Trash.
