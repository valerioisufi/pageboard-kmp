# Multi-user collaboration

Design and plan of the collaboration of several users on the same document. The modules stay
usable by other applications: an application enables the collaboration by adding the sync
modules and the transports it wants, the others pay nothing.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* Every change of the document is written twice: the "do" in the managers (`EraserManager`,
  `SelectionManager`, `TextEditorManager`, `MediaManager`, `PageManager`, `TextMarkupManager`,
  `DrawViewController.commitInkStroke`), the undo / redo in the `DrawAction`s of `Actions.kt`.
* A `DrawAction` mixes four responsibilities: it changes the model, persists, invalidates the
  tiles and clears the selection.
* `DrawPersistence` works on object references and upserts, mixes the document with the
  preferences and cannot tell a local change from a remote one: it is not the place to hook the
  network.
* The identifiers are local: `generateRandomId()` has ~10⁹ values, `DrawImage` and `DrawPdf`
  have no id, `DrawPage.uid` is a process counter, the resources are identified by a database
  row, and no identifier is persisted (the strokes get their row id back when loaded).
* The changes live inside `DrawViewController` (camera, layout, tiles): a change cannot be
  applied to a document that is not open in the editor.

## Design

### Changes as data

| Responsibility | Class | GRASP |
|---|---|---|
| Describe a change, apply it, produce its inverse | `DocumentChange` (sealed, `@Serializable`: `AddStrokes`, `RemoveElements`, `SetTransform`, `UpdateText`, `AddPage`, `MovePage`…) | Information Expert, Polymorphism |
| Create the change of a gesture | the managers | Creator (they hold the data of the gesture); they stop mutating the model |
| Find and change the elements | `DrawDocument` (index id → element), `DrawPage` (ordered lists) | Information Expert |
| Single entry point of the changes: order, timestamps, origin | `DocumentSession.submit(change, origin)` | Controller of the use case "modify the document", High Cohesion (out of `DrawViewController`) |
| Undo / redo | `HistoryManager` with (change, inverse) pairs of the local changes | High Cohesion; undo per author |
| Persistence | `ChangePersister`, translating the effects of the changes into `DrawPersistence` calls | Indirection, Protected Variations |
| Tiles and selection | `RenderInvalidator`, `SelectionManager.onElementsRemoved`, subscribed to the session | Low Coupling (observer) |
| Clock and conflicts | `HybridClock`, `Stamp`s of the pieces of the pages and elements (last-writer-wins) | Pure Fabrication |
| Operation log and version vector | `DocumentSession` (numbers the operations, applies those of the others once each), `DrawPersistence.appendOperation`, `DocumentStore.versionVector` / `operationsAfter` (Room and key-value implementations in `drawview-data`) | Protected Variations |
| Synchronize a document | `SyncEngine` | Controller, Pure Fabrication |
| Transport | `SyncTransport` / `SyncChannel`: `InMemoryTransport`, `LanTransport`, later `RelayTransport` | Polymorphism, Protected Variations |
| Encryption | `SecureChannel`, decorator of `SyncChannel` | Indirection |
| Attachments | `BlobStore` (by content hash, in chunks) | Pure Fabrication |
| LAN discovery | `LanDiscovery` (expect / actual) | Protected Variations |
| Sharing, participants, cursors | `SyncEditorExtension` | Low Coupling (like `drawview-lecture`) |

The origin of a change decides what the observers do:

| Origin | Persisted | Sent to the peers | Recorded in the history |
|---|---|---|---|
| `LOCAL` | yes | yes | yes |
| `UNDO` / `REDO` | yes | yes | handled by the history |
| `REMOTE` | yes | no | no |

### Convergence

* Every operation has a hybrid logical clock stamp (`Stamp`: time, counter, replica). Each piece
  of the document keeps the stamp of its last change and is changed only by a more recent one
  (last-writer-wins), so that the devices applying the same operations in any causal order end
  up with the same document:
  * an element: its presence (`added`, `removed`), its placement (`placed`: page, stacking
    order, transform or box) and its content (`updated`: text and format, style of a mark);
  * a page: its presence, its position (`orderVersion`) and its format (`formatVersion`);
  * the document: its defaults (`defaultsVersion`).
* Nothing is destroyed: a removed page or element stays, hidden (`DrawDocument.removedPages`,
  `DrawPage.removedElements`), and keeps receiving the changes of the others; put back (undo),
  it is the same on every device. Its rows stay in the database with the stamp of the removal.
* A stroke keeps its original geometry and an absolute affine transform: moving 500 strokes is
  a small message, and concurrent moves converge (deltas would not).
* The elements of a page are stacked by `ElementOrder` (the stamp of the change placing them on
  top, its position in the change); the pages are ordered by a fractional key (`FractionalIndex`,
  then by id); a reorder moves the fewest pages.
* Text: last-writer-wins on its content and on its placement; a remote change of the box being
  edited is applied when the edit ends.
* The operations of a replica are applied in their order (`DocumentSession.receive` refuses the
  others); the protocol sends them in causal order (stamp order).

### Protocol

* `Hello` (protocol version, document tag = HMAC of the document key, replica, version vector),
  then each side sends the operations the other one misses, then the live operations.
* Operations are identified by (replica, sequence number): duplicates are ignored.
* Ephemeral messages (cursor, viewport, points of the stroke being drawn) are rate-limited and
  never logged.
* Attachments are requested by content hash, in chunks; a placeholder is drawn until they
  arrive.
* Versioned binary envelope (CBOR), encrypted end-to-end (AEAD) with the key of the document,
  shared through an invitation (link or QR code); the envelope has an optional signature field
  for read-only roles (asymmetric keys) later.

### Modules

```
drawview-core      DocumentChange, DocumentSession, HybridClock, Operation
drawview-data      persistent identifiers, operations log on Room, BlobStore
drawview-sync      SyncEngine, protocol, encryption, InMemoryTransport, SyncEditorExtension
drawview-sync-lan  LanDiscovery (NsdManager / Bonjour / JmDNS) + TCP sockets
drawview-sync-relay (later)
```

The documents received from other users are created in a "Shared" folder of the library.

## Plan

| Phase | Content | Status |
|---|---|---|
| 0. Preparation | Several `EditorExtension`s; in-memory `DocumentStore` of the tests; network spike (mDNS, sockets, cryptography) on the three platforms | done |
| 1. Identity | UUIDs for documents, folders, pages, elements and resources, used as primary keys | done |
| 2. Changes as data | `DocumentChange`, `DocumentSession`, one manager at a time, with undo parity tests; absolute transforms of the strokes (from phase 3) | done |
| 3. Convergence | HLC, last-writer-wins stamps, hidden removed pages and elements, fractional page order, operations log | done |
| 4. Sync engine | Protocol, encryption, attachments, in-memory transport, convergence tests on random delivery orders | |
| 5. LAN | Discovery, TCP, permissions (iOS local network and Bonjour, Android multicast lock), invitation | |
| 6. UI and presence | Sharing, participants, cursors, live strokes, "Shared" folder and badge | |

### Phase 0 results

* `EditorExtension`s are combined with `+` (toolbar actions in order, side panels side by side,
  overlays stacked).
* `KeyValueDocumentStore` stays as the in-memory store of the tests: it is synchronous (Room
  queries run on `Dispatchers.IO`, which would make the library tests timing-dependent) and it
  keeps the documents as JSON, so the new identifiers cost little there.
* Network spike (a throw-away module, not versioned):

  | Check | JVM (macOS) | iOS simulator | Android |
  |---|---|---|---|
  | Loopback TCP with Ktor raw sockets (`io.ktor:ktor-network` 3.6.0) | passed | passed | compiled |
  | AES-256-GCM, HMAC-SHA256, SHA-256 (`dev.whyoleg.cryptography` 0.6.0, `cryptography-provider-optimal`) | passed | passed | compiled |
  | mDNS advertisement + discovery with a TXT record | passed (JmDNS 3.6.3, on the Wi-Fi interface) | passed (`NSNetService`); `nw_browser_create` of Network.framework reachable from Kotlin/Native | compiled (`NsdManager` + multicast lock) |

  Still to verify on real devices: the local network permission of iOS
  (`NSLocalNetworkUsageDescription`, `NSBonjourServices`; the simulator does not ask for it) and
  `NsdManager` at run time.

### Phase 1 results

The application is not released yet, so the stored documents are not migrated:

* `newElementId()` (a random UUID) identifies documents, folders, pages, strokes, texts, images,
  PDF pages, text marks and resources; `DrawPage.uid` is now `renderKey` (the key of the render
  caches of the process).
* The model has no database identifier anymore (`dbId`): the Room rows use the identifier of
  the model as primary key and are written with `@Upsert` (an `INSERT OR REPLACE` of a page
  would cascade to its content). `DocumentStore` identifies documents and folders by `String`.
* `DrawPersistence.insertPage(document, page, index)` also puts back a soft-deleted page
  (`restorePage` is gone).
* Room schema version 4 without migrations: until the first release a database of another
  version is recreated (`fallbackToDestructiveMigration`).
* The resources are identified by a UUID; their content hash belongs to the `BlobStore` of
  phase 4.
* The compatibility code went with it: the old controller APIs (`screenToDocument`,
  `activeBrush`, `addCompletedStroke`, `addNewPage`, …), the headless `DrawCanvas`, the first
  formats of the lecture assistant (`ReviewPage`, the migration of its settings), the unused
  preference columns and the copies of the default presets.

### Phase 2 results

* `core.change`: the changes (`AddElements`, `RemoveElements`, `PlaceElements`, `UpdateTexts`,
  `RestyleMarkups`, `AddPage`, `RemovePage`, `SetPageOrder`, `SetPageFormat`,
  `SetDocumentDefaults`, `AddResource`, `CompositeChange`) are `@Serializable` values referring
  to pages and elements by identifier; each applies itself and returns the change undoing it.
  The content travels as `ElementData` / `PageData`; a change created from the editor also keeps
  the model objects it holds (not serialized), so that undo / redo put back the same objects
  (meshes, bitmaps, render caches) and only a remote change models the strokes again.
* `DocumentSession` is the single entry point: it applies a change and notifies its observers
  with the `ChangeEffects` (elements saved or deleted, pages inserted, removed, reordered or
  formatted, files added, areas to draw again). The `HistoryManager` records the inverses of the
  local changes (groups: one entry for an eraser gesture, for the pages inserted by the lecture
  assistant), the `ChangePersister` writes the effects through `DrawPersistence`, the controller
  draws them again and, for a change of another device, drops the stale selections.
* The managers create the changes (GRASP Creator) and no longer touch the model, except the live
  previews of a gesture (pages reordered while dragged, width of a selected text), which are put
  back before the change is submitted. `DrawAction` and its subclasses are gone.
* A stroke keeps the geometry it was drawn with and an absolute placement (`DrawStroke.transform`,
  stored with it): a lasso move is a `PlaceElements` of a few numbers per element, undone exactly.
* `DrawPersistence`: `saveStrokes` (instead of save / update), `deletePdf`, `saveResource` (the
  identifier of a file is created by the model, no longer by the store); Room schema 5.
* Improvements on the way: moving elements to another page is undone in their place, the change
  of the template of the document is undoable, a stroke modeled again keeps its inputs exactly
  (the modeller adjusted their timestamps: two copies of a document would have differed).
* Tests: every change against its undo / redo, 20 random sessions of 60 changes undone and redone
  exactly, serialization round trip, a deserialized change applied to another copy of the
  document, the order of the writes, and the managers through the controller.

### Phase 3 results

* `HybridClock` and `Stamp` (ordered as text too: the stamps are stored as `TEXT`);
  `Operation(id = (replica, sequence), stamp, change)`. `DocumentSession.submit` numbers and
  stamps the changes of this device, `receive` applies the next operation of another replica
  (also when it changes nothing: it is logged all the same) and keeps the version vector.
* Every change applies itself with the stamp of its operation, piece by piece (see
  Convergence). A change of another device applies to the pieces it is the most recent for: the
  elements are found on any page (another device may have moved them), shown or hidden.
* The editor gives the stacking order of what it moves on the same page; what goes on top gets
  the order of the change (`Placement.order`, `ElementData.order`).
* `MovePages` gives the pages their new fractional keys (it replaces `SetPageOrder`): two
  devices moving different pages at the same time keep both moves.
* `DrawPersistence` only saves (rows with their stamps and their removal) and appends the
  operations; Room schema 7 (operations log, replica of the device), the key-value store keeps
  the same information as JSON. `DocumentStore.replicaId` / `versionVector` / `operationsAfter`
  serve the synchronization of phase 4.
* `ConvergenceTest`: three devices making random changes (undo and redo too) and exchanging their
  operations in random causal orders end up with the same document, removed pages and elements
  and stamps included (30 seeds of 150 steps in the build; verified on 600 seeds of 300 steps).
  It found the cases the design above answers: elements moved to a page removed meanwhile, the
  order derived from local state, a removed element put back with stale values, partial writes
  of a register (a move and a text edit share no field now).

### Risks

* Regressions of the editor in phase 2: one manager at a time, with tests comparing the state
  before and after undo / redo.
* mDNS on iOS, Ktor sockets on iOS, a cryptography library for Kotlin Multiplatform: verified by
  the spike of phase 0.
* Growth of the log and of the hidden content: compaction with snapshots + version vector (the
  hidden pieces older than what every peer has seen can go), later.
* Networks isolating their clients (guest Wi-Fi, many university networks): only a relay works
  there.
