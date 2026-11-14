# The key-value store and the library view model, by responsibility

Phase 8 of `docs/architecture.md` (design C, High Cohesion): `KeyValueDocumentStore`
(`drawview-store`, 881 lines) and `FileExplorerViewModel` (`drawview-explorer`, 792 lines) are
split where a part has state or rules of its own, with the same behaviour and the same public API.
The stored format of the key-value store does not change: the same keys, the same JSON.

The responsibilities are assigned following the GRASP principles.

## Design

* **Information Expert.** `KeyValueOperationLog`: the operations of the documents and their
  proofs, one entry of each per document, read once and kept, written and forgotten. The store
  calls it under its lock; the compaction, which also changes the index, stays in the store.
* **Pure Fabrication.** `KeyValueFormat.kt`: what the store keeps (`StoredIndex`, `StoredFolder`,
  `StoredSummary`, `StoredDocument`, `StoredResource`, the proofs) and how a document becomes one.
* **High Cohesion.** `LibrarySelection`: the items selected in the library and the one whose
  details are shown, by key among the items shown (toggle, extend from the anchor, select all,
  leave). The view model keeps its members, which ask it: the screens do not change.
* `LibraryFiles.kt`: matching a name with a search (without accents), the order of the items, an
  item of a document.

## Phases

| Step | Content | Status |
|------|---------|--------|
| 1 | `KeyValueOperationLog`, `KeyValueFormat.kt`; `LibrarySelection`, `LibraryFiles.kt` | done |

## Decisions

Taken alone, revertible:

* **The catalog and the persistence stay in the store**: they share its index, its open documents
  and its lock; split, each piece would reach into the others.
* **The navigation and the operations stay in the view model**: they are the derived state the
  screens read (the items shown depend on the section, the folder, the search, the filter); only
  the selection had state and rules of its own.
