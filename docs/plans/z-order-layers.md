# Z-Order: Stack Cycling, Element Locking & Per-Page Layers

This plan implements the items left for later by `docs/plans/z-order.md`: selecting elements underneath others via repeated long presses, locking elements as background/protected items, and per-page layers with visibility, locking, ordering, and element assignment.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator, Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected Variations).

## Starting point

1. **Unified page stack** (`Page.stack`, `ElementOrder`): strokes, texts, images, and custom elements share one stack per page; PDF pages and text marks stay beneath.
2. **Long press** (`CanvasGestureHandler.onLongPress`): picks the topmost selectable element at the pressed point; repeated long presses at the same point keep selecting the same topmost element.
3. **No locking or layers**: every unlocked element can be selected by the lasso or erased, and pages have no layer grouping.
4. **Room schema**: currently at version 25 (`PageboardDatabase`).

## Requirements

1. **Stack cycling on repeated long press**: pressing and holding again at the same location cycles downward through overlapping elements in stack order (`top -> middle -> bottom -> wrap`), allowing selection of items buried beneath strokes, images, or shapes.
2. **Element locking**: any element (`PageElement`) has a `locked` flag (synchronized via `LockElements`, stored in Room v26 and `KeyValueDocumentStore`). Locked elements cannot be selected by the lasso, moved, resized, or erased, and offer an "Unlock" action on long press/secondary click.
3. **Per-page layers**: `PageLayer` (`id`, `name`, `orderKey`, `visible`, `locked`, CRDT stamps) per page. Elements carry an optional `layerId`. Hidden layers omit their elements from tiles, hit-testing, and export; locked layers protect all their elements. Existing pages with zero layers render identically to today.
4. **Untrusted input & convergence**: validate layer names, `FractionalIndex` order keys, and layer counts in `DocumentSession.admits`; verify convergence in `ConvergenceTest`.
5. **Data safety**: Room migration `25 -> 26` tested with `MigrationTestHelper` against `25.json`; no destructive fallback; `PROTOCOL_VERSION` kept at 4 with `ProtocolFormatTest` re-recorded.

## Design

| Responsibility | Class / Module | GRASP |
|---|---|---|
| `PageLayer` entity, element `locked`/`layerId` registers, visibility & stack sorting | `PageLayer`, `PageElement`, `Page` (`pageboard-model`) | Information Expert |
| LWW CRDT changes (`LockElements`, `AssignElementsLayer`, `UpsertPageLayer`, `RemovePageLayer`) | `DocumentChange` (`pageboard-model`) | Polymorphism, Creator |
| Validation of untrusted layer & lock operations | `DocumentSession.admits` (`pageboard-model`) | Protected Variations |
| Room `page_layers` table, element columns, and `Migration25To26` | `Entities.kt`, `Migrations.kt`, `DocumentRepository.kt` (`pageboard-store-room`) | Information Expert, Protected Variations |
| Layer CRUD, active layer tracking, and assignment | `LayerManager` (`pageboard-canvas`) | Controller |
| Stack cycling hit-test and lock enforcement | `CanvasGestureHandler`, `SelectionManager`, `EraserManager` (`pageboard-canvas`) | Controller, Information Expert |
| Lock/Unlock actions and adaptive Layers panel | `LayersPanel`, `EditorCommands`, `SelectionActionBar`, `CanvasContextMenu` (`pageboard-ui`) | Low Coupling, High Cohesion |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | Model (`PageLayer`, `PageElement.locked`/`layerId`, `Page.stack` visibility & layer sorting, `DocumentChange` operations, `DocumentSnapshot` / `ElementVersions`), `KeyValueDocumentStore`, Room v26 + `Migration25To26` + `PageboardDatabaseMigrationTest`, `ProtocolFormatTest` & `ConvergenceTest`; iOS simulator sync tests; ABI recorded | Done |
| 2 | Canvas (`LayerManager`, `SelectionManager` lock/unlock & guards, `EraserManager` lock/visibility guards, `CanvasGestureHandler` repeated long-press stack cycling); `ArrangeTest` & `LayerCanvasTest`; ABI recorded | Done |
| 3 | UI (`EditorCommands` lock/unlock/layers, `SelectionActionBar`, `CanvasContextMenu` unlock action, adaptive `LayersPanel`, strings in 7 locales); `CommandTest` & `compareRoborazziJvm`; ABI recorded | Done |

## Decisions (revertible)

1. **Per-page layers (`PageLayer`)**: layers belong to a page (`page_layers` table with foreign key to `pages`), matching page-scoped `ElementOrder` and `Page.stack`.
2. **Empty `layers` list means legacy flat stack**: pages without created layers behave identically to v25 with zero migration of existing element rows beyond default columns (`locked = 0`, `layerId = NULL`).
3. **Protocol version stays 4**: agreed with maintainer; `ProtocolFormatTest` re-recorded.

## Left for later

* Layer opacity and blend modes.
* Drag-and-drop reordering of elements across layers inside the Layers panel.
