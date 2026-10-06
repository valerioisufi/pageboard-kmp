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
| One session per document, for the editor and the synchronization (documents not open too) | `OpenDocuments` in `drawview-data` | Creator, Information Expert (the store knows the replica, the log, the persistence) |
| Synchronize the documents | `SyncEngine` (a connection: `Link`; a document: `DocumentSync`) | Controller, Pure Fabrication |
| Transport | `SyncTransport` / `SyncChannel`: `InMemoryTransport`, `LanTransport`, later `RelayTransport` | Polymorphism, Protected Variations |
| Encryption | `SecureChannel`, decorator of `SyncChannel` | Indirection |
| Keys of the shared documents | `SharedDocuments` (kept by the application) | Protected Variations |
| Files of the resources | asked for and sent in chunks by the `SyncEngine`, written through `ResourceFiles` | Pure Fabrication, Protected Variations |
| LAN discovery | `LanDiscovery`: `JmdnsLanDiscovery`, `NsdLanDiscovery`, `BonjourLanDiscovery` | Protected Variations, Polymorphism |
| Work with the devices of the network | `LanSync` (announces the shared documents, connects to the devices sharing them) | Controller |
| Share and join from an application | `Collaboration` (`drawview-sync-ui`) | Controller |
| Sharing, participants, cursors, strokes being drawn | `Collaboration.editorExtension()`, an `EditorExtension` | Low Coupling (like `drawview-lecture`) |

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

* The first frame of a connection, in clear, is the version of the protocol and the tag of the
  document (an HMAC of its key): the device accepting the connection finds the document. Every
  other frame is encrypted with AES-256-GCM and the key of the document (a random nonce per
  frame, the version of the protocol authenticated with it); the key travels with the
  invitation (a `drawview://join` link, for a QR code or a message).
* `Hello` (replica, name, version vector, whether the device has the document), then each side
  sends what the other misses: the `Snapshot` of the document to a device without it, then the
  operations after its version vector in stamp order (a causal order), in batches; then the
  operations as they are applied, those of the other devices too (a device relays them, never
  back to the device they came from).
* Operations are identified by (replica, sequence number): the duplicates are dropped, an
  operation arriving before the ones it follows waits for them.
* The files of the resources are asked for by identifier and sent in chunks of 256 KiB.
* A connection with nothing to send sends a `Ping` every 4 s; one silent for 12 s is lost (a
  device gone without closing it: out of the network, asleep, suspended) and closed, so that it
  is made again.
* Two connections between the same devices (both connected to the other at once) become one: the
  one opened by the device with the smaller replica is kept (the newer one is closed when the
  same device opened both), unless the other one is silent (maybe lost: it closes itself).
* Presence (pointer, stroke being drawn, text being written, elements selected and how they are
  being moved) is never logged: of the stroke only the inputs not sent yet travel, the text and
  the selection only when they change; the whole presence every 500 ms (for the devices arrived
  meanwhile).
* CBOR frames.

### Modules

```
drawview-core      DocumentChange, DocumentSession, HybridClock, Operation, DocumentSnapshot
drawview-data      persistent identifiers, operations log on Room, OpenDocuments
drawview-sync      SyncEngine, protocol, encryption, presence, InMemoryTransport
drawview-sync-lan  LanTransport (TCP), LanDiscovery (JmDNS / NsdManager / Bonjour), LanSync
drawview-sync-ui   Collaboration, editor extension, join dialog (Compose)
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
| 4. Sync engine | Protocol, encryption, attachments, in-memory transport, convergence tests on random delivery orders | done |
| 5. LAN | Discovery, TCP, permissions (iOS local network and Bonjour, Android local network), invitation | done |
| 6. UI and presence | Sharing, participants, cursors, live strokes, "Shared" folder and badge | done |

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

### Phase 4 results

* `drawview-sync`: `SyncEngine` (`share`, `accept`, `join`, `peers`), `SyncChannel` /
  `SyncTransport` with `InMemoryNetwork`, `SecureChannel`, `DocumentKey`, `Invitation`,
  `SharedDocuments`, `ResourceFiles`; `cryptography-kotlin` 0.6.0 (the blocking forms: quick for
  a frame, and the coroutines keep their thread) and `kotlinx-serialization-cbor`.
* `DocumentSnapshot` (core): the whole state with the stamps, removed content included, in a
  canonical form. The operations already applied change nothing in it, so a device joining gets
  the snapshot and then the whole log (it can then serve the others). The key-value store keeps
  its documents as snapshots.
* `OpenDocuments` (data): one `DocumentSession` per document with the persistence of its
  changes, opened by the synchronization when the document is not open in the editor;
  `DocumentStore.insertDocument` stores a document received (in the folder the application
  gives: "Shared").
* The resources travel by identifier rather than by content hash (they are UUIDs since phase
  1; each chunk is authenticated by the encryption): deduplicating equal files is left for
  later.
* A document without pages gets its page from the editor, by a change (the stores added it
  outside of the changes: the other devices would not have had it).
* Fixed on the way: a document closed and opened again at once could be loaded before its last
  changes were written (its log ahead of its content).
* Tests: invitations, encryption with the right and a wrong key, the messages in CBOR, a device
  joining (folder, live changes, changes made apart, restart), three devices joining through
  each other in cycles, going away and coming back at random (5 seeds in the build, verified on
  40), the file of an image; on the JVM and the iOS simulator.

### Phase 5 results

* `drawview-sync-lan`: `LanTransport` (Ktor sockets, frames of a 4-byte length and their bytes),
  `LanDiscovery` with an implementation per platform, `LanSync`.
* A device announces the service `_drawview._tcp` with its name and the tags of the documents it
  shares (12 hex digits each, in TXT entries `t0`, `t1`… of 19 tags); a device finding another one
  that shares a document with it connects to it (the one with the smaller identifier, so that two
  devices make one connection), and connects again every 10 seconds while the other one is
  there. A device joining through an invitation without an address announces the tag and is
  found; with an address it connects at once. A device accepting a connection for a document it
  has the key of but not the content yet (an invitation) receives it.
* Permissions: `INTERNET` and `ACCESS_LOCAL_NETWORK` in the manifest of the module (from Android
  17 the application asks for the second one at run time; it also covers `NsdManager`, so no
  multicast lock); `NSLocalNetworkUsageDescription` and `NSBonjourServices` in the `Info.plist` of
  PencilNotes.
* Tests: frames over TCP (1.5 MB too) and three devices finding each other (an in-memory
  discovery) and synchronizing over TCP on the loopback, on the JVM and the iOS simulator; JmDNS
  between two processes on the network, run by hand (`DRAWVIEW_MDNS_TESTS=1`): in this session
  the JVM received no multicast at all (not even its own announcement), most likely the local
  network permission of macOS; to verify with the desktop application, like `NsdManager` and
  Bonjour on real devices.

### Phase 6 results

* The editor works on the `OpenDocuments` session of its document (`DrawViewStore.documents` for
  the default store, `DocumentLibrary(documents = …)` otherwise): the changes of the other
  devices reach the open editor, a document not open is changed without it. The controller stores
  the changes only of the sessions it creates, and leaves the shared session when it is closed.
* Keys: `DocumentStore.sharingSecrets` / `setSharingSecret` (Room table `shared_documents`,
  schema 8), `DocumentSummary.isShared`; `StoreSharedDocuments` keeps in memory the key of a
  document joined until it arrives.
* Presence: `DrawViewController.activity` (where the pointer is, the inputs of the stroke being
  drawn) is sent at most every 100 ms and at least every 4 s (`Presence`, never logged, relayed,
  ordered by a sequence per device); a device is gone with its connection or after 12 s of silence.
* `drawview-sync-ui`: `rememberCollaboration()` (one per process, started at once when documents
  are shared already), `Collaboration.editorExtension()` (the action Share with the number of the
  others on the document; the dialog with the invitation, its copy, who is on the document and
  Stop sharing; the pointers with the names and the strokes being drawn of the others),
  `JoinSharedDocumentButton`, `CollaborationDialogs` and `InvitationLinks` (the `drawview://join`
  links opened on the device), strings in the 7 languages of the applications. The documents
  received go to the folder "Shared" ("Condivisi"), created the first time; the library shows the
  badge of the people on the shared documents.
* The local network permission of Android 17 is asked when a document is shared or joined (or at
  the start when some are shared already).
* PencilNotes: the collaboration in the editor (with the lecture assistant), the action Join in
  the library, the `drawview://join` links (Android intent filter, iOS URL type and `onOpenURL`).
* Fixed on the way: an older database made the application crash at the start
  (`fallbackToDestructiveMigrationOnDowngrade` required the migrations again).
* Verified on the iPad simulator against this machine (`RemotePeerTest`, `RemoteHostTest`, run by
  hand): a document shared on the iPad is received by the JVM, whose stroke appears live on the
  iPad with its pointer, its name and the number of participants (gone when it leaves); a
  `drawview://join` link opens the join dialog, the document arrives in the Shared folder with the
  badge. Still to verify on real devices: Bonjour and `NsdManager` between devices, the permission
  of Android 17, two simulators (the second one was not available to this session).
* Later: a QR code of the invitation (the link is copied or opened), choosing the name shown to
  the others, the relay (another `SyncTransport`).

### After phase 6: connections lost, presence drawn

* Connections lost silently (a device out of the network, asleep, suspended in the background)
  stayed "connected" for ever, so they were never made again: the `Ping`s close them (see
  Protocol). `LanSync` makes again every 5 s (and when the application comes back to the
  foreground) the connections missing with the devices sharing a document, from either device
  (the one with the smaller identifier first), with a timeout; it connects again to the devices
  of the invitations joined by address, listens again when the system closed the listener and
  announces again.
* The others are drawn by the canvas itself (`DrawViewController.collaborators`), with the
  camera: their strokes are modeled as their inputs come by the same ink engine as the strokes of
  this device (the brush of the strokes modeled again: the stroke of the document takes their
  place without a jump, they fade in 300 ms), their pointers glide to where they are (presence up
  to 30 times a second).
* Verified on the iPad simulator against this machine (`RemotePeerTest`, which draws as by hand):
  the stroke grows under the pointer of the other device and becomes the stroke of the document;
  a device frozen (`SIGSTOP`: its connection open but silent) is gone from the participants in
  less than 16 s. Tests: a connection cut silently is lost on both devices and made again with
  what each one missed, a device gone and back (another port) and a listener closed by the system
  over TCP, the strokes being drawn relayed a few inputs at a time.

### After phase 6: texts written, elements moved

* What the others do is shown beyond their strokes: the text they are writing, as it is typed
  (the text editor reports its draft: content, box, style), and the elements they select, framed
  with their names, drawn where they are moving them. The elements edited or moved elsewhere are
  hidden from the content meanwhile (`isShownByCollaborator`), so they are never drawn twice; the
  text added or the move saved then takes their place.
* `EditorActivity` gained the text draft (`reportTextDraft`) and the selection
  (`SelectionActivity`: page, identifiers, transformation of the move); `Presence` carries them.
  The pointer of a device writing is hidden by its text.
* Verified on the iPad simulator against this machine (`RemotePeerTest`, which now also writes a
  text a letter at a time and moves the wave, then prints what the iPad does): the text of the
  other device grows letter by letter in its frame and becomes the text of the document, the
  wave framed glides where it is moved; the texts written and the elements selected and moved on
  the iPad reach the other device.

### After phase 6: connecting again at once

* Closing and opening the application again sometimes connected it again only after a while, or
  not at all, while the other device was there: Bonjour gave up on a device whose address it did
  not resolve at the first try, and the connection went to its name (looked up again, maybe to
  an IPv6 address first); `NsdManager` (Android 14) kept the first address of a device and
  waited for ever for a device it could not resolve; the device opening the application again
  listened on another port, so the others knew its address only from its new announcement,
  which multicast DNS on Wi-Fi may lose.
* A device listens on the same port every time (`LanTransport.DEFAULT_PORT`, another one when it
  is taken; the port is taken again at once, while the connections just closed still hold it).
  `LanSync` remembers the devices found for 10 minutes after they are gone and connects again to
  them: a device starting again is connected again even when its announcement is lost. The device
  meant to connect second connects after 1 s (no more at the next retry).
* Bonjour and `NsdManager` resolve the devices again when they fail, follow their changes (port,
  documents shared) and give their IPv4 address.
* On the desktop the port is left to another application listening on it over IPv4 (e.g. one in
  the iOS simulator): the dual-stack sockets of the JVM would get only the IPv6 connections.
* A device never takes its own presence for another one's (back through two connections with the
  same device).
* Verified on two simulators (iPad, iPhone) finding each other with Bonjour: opened again, the
  application is connected again in 2-3 s (its start included); back from the background, in
  less than half a second. Tests: a device started again on its port whose announcement and
  discovery get lost is connected again; two connections between the same devices become one.

### After phase 6: working apart for long

* Devices working apart on a document for long (out of the network, the application closed)
  exchange what each one missed when they meet again, in both directions, and end up with the
  same document; nothing is lost (at worst kept hidden). On a conflict the most recent change
  wins, piece by piece (see Convergence), so:
  * a page removed on a device hides what the others added to it meanwhile (kept with it: put
    back, it comes back with it);
  * an element erased on a device stays erased, even when another one moved or changed it;
  * a text changed on two devices keeps the most recent content as a whole;
  * the pages moved on two devices are each where its most recent move put it;
  * "most recent" is by the clocks of the devices (a clock far ahead wins).
* What it costs: every stroke received is modeled again from its inputs (most of the time), the
  missed operations travel in batches of 200 and are applied as they come (the editor stays
  usable, the strokes appear little by little). Measured with `OfflineWorkBenchmark` (run by
  hand: two devices, 2000 strokes each, Room in files): about 18 s on this Mac before, from 17 %
  to 25 % less after (the machine was busy):
  * the elements are looked for in each list read once (no more each element read through the
    snapshot: half of the time of applying);
  * the strokes received are modeled before being applied, at the same time on every core;
  * only the missed operations are read from the log (it was read whole at every connection: a
    document worked on for months would not have fit in the memory of a phone; 2 ms now when
    nothing was missed).
* A stroke given up for a gesture (a second finger, a long press) is gone at once on the other
  devices (`EditorActivity.strokeCancelled`, `Presence.inkCancelled`): it stayed until the
  next stroke.

### Risks

* Regressions of the editor in phase 2: one manager at a time, with tests comparing the state
  before and after undo / redo.
* mDNS on iOS, Ktor sockets on iOS, a cryptography library for Kotlin Multiplatform: verified by
  the spike of phase 0.
* Growth of the log and of the hidden content: compaction with snapshots + version vector (the
  hidden pieces older than what every peer has seen can go), later.
* Networks isolating their clients (guest Wi-Fi, many university networks): only a relay works
  there.
