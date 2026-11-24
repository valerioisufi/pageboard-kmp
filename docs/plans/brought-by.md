# Who brought a document into a shared folder

A device without a document of a shared folder takes it from the owner of the folder, or from the
device that brought the document into the folder (`broughtBy`). Today any editor can make itself
that device for any document, without anyone seeing it. It can then give a device joining the
folder a document of its own making. This plan makes `broughtBy` follow who really brought the
document. It then closes, as far as the maintainer chooses, the visible way round that is left.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

Found on 2026-11-24 while writing [shared-access.md](shared-access.md) (branch
`feat/shared-access`). That plan anchors nothing on `broughtBy` because of this weakness, and names
this fix in its "Left for later". The weakness is on `main` today (`aef52037`, protocol 2, Room
schema 25).

* **What `broughtBy` is for** ([shared-folders.md](shared-folders.md), [untrusted-members.md](untrusted-members.md)).
  * A device receives a document it does not have as a `Snapshot` (the state of the document and
    the version vector it covers), then the operations after it.
  * The snapshot cannot be checked: it is the giver's word. So it is taken only from a device
    trusted for it (`SyncEngine.onSnapshot`, `mayGive`):
    * for a document shared on its own, its owner;
    * for a document of a space, the owner of the space or the device whose replica is
      `broughtBy` (`SpaceSharing.mayGive`).
  * The giver checks the same rule for itself before it sends (`catchUp`). A modified application
    skips that check, so the receiver's check is the one that counts.
* **How `broughtBy` is written** (`add` in `CatalogChange.kt`, `RemoveFromSpace`):
  * The presence of an item is one last-writer-wins piece: `inSpace`, `kind`, `broughtBy`, and
    its stamp `presence`.
  * An addition (`AddDocument`, `AddFolder`) with a stamp later than `presence` writes the whole
    piece, `broughtBy = stamp.replica` included, **whether or not the item is already in the
    space**.
  * A removal writes it out of the space, with `broughtBy` empty.
* **The attack.** Eve, an editor of a space, submits `AddDocument(d, itsFolder)` for Bob's
  document `d`, already in the space.
  * The change is well formed, signed by Eve, allowed to an editor, and stamped now. Every device
    takes it, and `broughtBy` of `d` becomes Eve's replica, the same everywhere.
  * Nothing shows: `d` stays where it is (same folder) and in the space.
  * From then on, a device joining the space (or any device without `d`) takes `d` from the owner
    or from Eve. If Eve gives first, the snapshot is hers. It can hold content attributed to
    others, drop content, or claim a version vector that covers everything, so that the other
    devices never send that device the operations it misses.
  * Bob, who really brought `d`, may no longer give it.
* **The honest applications rewrite it too, silently.**
  * `SpaceLibrary.reconcileLocked` submits `AddDocument` for an item under the root that is not
    in the view (`view[item.id] == null`). That includes an item in the space that is not shown,
    for instance while the folder above it is not in the space yet. Its `broughtBy` becomes this
    device.
  * In a shared library, an item received as gone from the space stays where it is and is added
    again by the device. That one is a real re-entry after a removal, and is right.
* **What helps the fix.**
  * The log of a catalog is never compacted (`catalog_operations` in Room, the log of the
    key-value store). Every device keeps every operation of every space it is in, and a device
    joining receives all of them ("the changes of the catalog the other misses").
  * `SpaceStore.catalogOperationsAfter(spaceId, emptyMap())` gives the whole log.
  * `SpaceLibrary` loads a space from its stored items (`catalogItems`), not by replaying the log.
  * Only `SpaceSharing.mayGive` reads `broughtBy` (and `CatalogEntry.broughtBy`, which carries it
    to the view).
  * The catalog items are stored as JSON: in Room, one column of `catalog_items`; in the
    key-value store, `StoredSpaceValue`.

## Requirements

1. **An addition of an item already in the space does not change who brought it.** Only the
   addition that brought the item into the space counts, and nothing that comes later while it
   stays in.
2. **The same on every device.** Who brought an item is a function of the set of catalog
   operations, whatever the order in which they arrive. The convergence tests
   (`SpaceCatalogTest`) cover it.
3. **The catalogs already stored are read correctly.** An item whose `broughtBy` was rewritten by a
   re-addition gets back the device that brought it, recomputed from the log. This is the same on
   every device, because every device has the whole log. An item whose log does not explain it is
   kept as it is and reported. Nothing of the library or of the documents changes.
4. **The visible way round is closed as the maintainer chooses** (see Decisions). An editor can
   still take an item out of the space and add it again: the item leaves and comes back on every
   device, and its adder is then the one who brought it.
5. **Mechanism, not policy.** The sharing modules are published for other applications
   ([shared-access.md](shared-access.md)). Every device must compute this alike, so no
   application or owner can tune it.
6. **Nothing is lost.**
   * The stored formats (`catalog_items`, `StoredSpaceValue`) change with tested migrations.
   * Nothing is deleted.
   * A device that cannot recompute keeps what it has.
7. **Protocol.** Any change to what travels is listed, and the version is agreed with the
   maintainer.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Who brought an item: the earliest addition after its latest removal | `CatalogItem.arrivals` and `broughtBy` (`pageboard-store`), kept by the additions and removals | Information Expert |
| Applying an addition or a removal to the presence and to the arrivals | `add` and `RemoveFromSpace.applyTo` (`CatalogChange.kt`) | Information Expert, Polymorphism |
| Completing the items stored before this change from the log | `SpaceLibrary` when it loads a space (`CatalogMigration`, new, `pageboard-store`) | Pure Fabrication, Protected Variations |
| Who may give a document | `SpaceSharing.mayGive` (`pageboard-sync`), unchanged in shape | Information Expert |
| (Phase 3, if chosen) The giver fixed by the owner of the space | `ConfirmGiver` (`CatalogChange`, new), a piece `giver` of the item | Information Expert, Controller |
| (Phase 2, if chosen) The owner's snapshot preferred when the owner has the document | `SyncEngine.onSnapshot`, from the heads of the space connection (`DocumentHeads`) | Controller |

### Who brought an item, deterministically

The rule: **the device that brought an item is the author of the earliest addition later than the
latest removal.** Later additions while the item stays in the space change its place and its stamp
of presence, as today, and not who brought it.

Why the item has to keep a list, and not a single stamp:

* The operations arrive in any order: from devices that were offline, through other members.
* Take an addition by A at t1, a re-addition by Eve at t3, and a removal by C at t2, made apart.
  * The set says: in the space (t3 is the latest), brought by Eve (the earliest addition after t2).
  * A device that receives t1, then t3, then t2 must end up there too. Had it kept only "A, at
    t1", the removal at t2 would leave it not knowing Eve's t3. It would say nobody brought the
    item, while a device that received t2 first says Eve.
* So an item keeps the additions later than its latest removal: `arrivals: List<Stamp>`. The
  replica is in each stamp. The rule then reads:
  * an addition at `s`: kept in `arrivals` when `s` is later than the latest removal; the
    presence (`inSpace`, `kind`, `presence`) as today;
  * a removal at `t`: when `t` is later than the latest removal (`removed: Stamp`, new), the
    arrivals up to `t` are dropped; the presence as today;
  * `broughtBy` = the replica of the earliest of `arrivals`, while the item is in the space; empty
    otherwise.
* This is a function of the set of operations: the latest removal is a maximum, and the earliest
  later arrival a minimum, over the set.
* An undo of a removal (its inverse is an addition) is a new addition, after the removal: its
  author becomes the one who brought the item. That is right, because it has the document. The
  original one cannot be named in its stead: an addition naming another replica as the bringer
  could be forged.

`broughtBy` stays a stored field, written from `arrivals`, so that a build of before reading a new
item reads who brought it.

**The bound.** An honest application adds an item already in the space only in the cases listed
in the starting point, so `arrivals` holds one stamp or two. An editor submitting additions of the
same item grows it by one stamp per addition, as its operations grow the log, which is not bounded
either. What another device sends is already bounded (`CompositeCatalogChange.MAX_CHANGES`, the
pending operations). When the catalog log is compacted one day, the arrivals older than the point
every member has seen collapse to their earliest one (see Left for later).

**The honest re-additions.** `reconcileLocked` keeps submitting an addition for an item in the
space that is not shown. It now leaves `broughtBy` alone. Changing it to a `MoveItem` is not
needed, and would change what it does to the place.

### The catalogs stored before

* An item stored before this change has no `arrivals` and no `removed`. It is read with `arrivals`
  empty, and is not used as is.
* When `SpaceLibrary` loads a space (where it reads `catalogItems` today), `CatalogMigration`
  replays the additions and removals of the whole log (`catalogOperationsAfter(id, emptyMap())`)
  for the items of before, and writes `removed`, `arrivals` and `broughtBy` from them. The rest of
  each item (name, parent, trash, presence) is not recomputed and not touched.
* It checks itself: the latest presence event of the log must be the item's stored `presence`
  stamp.
  * When it is, the item is completed and saved in the new format.
  * When it is not (an item whose operations this device does not have, which should not happen),
    the item keeps its stored `broughtBy` as its only arrival (`arrivals = [presence]`), and the
    mismatch is reported in the diagnostics (`sync-diagnostics.md`).
* Every device has the whole log of the spaces it is in, so every device recomputes the same.
  Items rewritten by re-additions in the past get back the device that brought them, on every
  device at once.
* An item of before is told by its fields, not by a number of format: a presence with neither
  arrivals nor a removal. Every item written since has one of them. So both stores are completed
  the same way, with no format number to raise.
  * The key-value store: `StoredSpaceValue` keeps format 1, the items in it gaining two fields.
  * Room: no schema change. `catalog_items` keeps each item as JSON, and the new fields are in it
    (not written when empty, as the other defaults).

### What is left: taking an item out and adding it again

After phase 1, an editor can still take an item out (`RemoveFromSpace`) and add it again. Every
device sees it: the item leaves the shared folder (each device keeps its copy, moved to the folder
holding the root) and comes back. The editor then brought it, and can give it. Three ways to go
further, for the maintainer to choose:

* **Nothing more.** The takeover is visible. The editor could also make a new document of its own
  with the same content. What it gains is the identifier of `d`, and with it the operations the
  others will send to that document.
* **Phase 2: the owner's snapshot preferred** (no change to what travels).
  * A device without a document refuses a snapshot from a device that is not the owner while the
    owner is connected to it about the space and its heads (`DocumentHeads`) list the document.
    It waits for the owner's snapshot instead.
  * This narrows the way round to the times the owner is away. It is a local choice, so devices
    on two builds still sync.
* **Phase 3: the giver confirmed by the owner** (a protocol change).
  * A catalog change `ConfirmGiver(id, replica)`, admitted from the owner of the space only, sets
    a piece `giver` of the item, with its stamp.
  * Once it is set, `mayGive` takes the owner and that replica only, whatever is added later. A
    re-addition by an editor no longer makes it a giver.
  * The owner submits it when it receives a document from the device that brought it, and again
    when an item comes back into the space.
  * The window left is between the bringing and the owner's confirmation.
  * It fits the protocol change of [shared-access.md](shared-access.md) (its `ShareItem` /
    `sharedAs` follow the same idea).

### Protocol

* **Phases 1 and 2: what travels does not change.** `AddDocument`, `AddFolder`, `RemoveFromSpace`,
  `Snapshot` and `DocumentHeads` stay as they are, and `ProtocolFormatTest` stays as it is.
  * What changes is what a device computes from them (who brought an item) and so what it takes
    (whose snapshot). This is as in phases 4 and 5 of
    [untrusted-members.md](untrusted-members.md).
  * Devices on the two builds agree on everything they show. They may disagree on who may give a
    document, and the newer build only refuses more. A device then waits for the owner. Nothing
    diverges.
  * So `PROTOCOL_VERSION` can stay 2 (see Decisions).
* **Phase 3: a protocol change.** `ConfirmGiver` is a new catalog change, whose content is in the
  format. `ProtocolFormatTest` is re-recorded, and the version is decided with the maintainer. A
  build of before that meets it closes the connection (a change it does not know).

## Plan

| Phase | Content | Status |
|---|---|---|
| 0. This plan | Written, the decisions below for the maintainer | done (to approve) |
| 1. Who brought it | `CatalogItem.arrivals` and `removed`; `add` keeping the arrivals and leaving `broughtBy` to them; `RemoveFromSpace` dropping the ones before it; `CatalogMigration` completing the items of before from the log; the ABI of `pageboard-store` recorded again (additions). Tests: `SpaceCatalogTest`: a re-addition keeping who brought it; the three operations of the example in every order; random additions, re-additions and removals by three devices in random orders giving the same `broughtBy` (with the convergence tests of today); an undo of a removal. `SpaceSharingTest`: Eve re-adding Bob's document is not a giver, Bob still is. `SharedFoldersSyncTest`: Carol joining gets the document from Bob or Ada and never takes Eve's snapshot, and taking it out and adding it again makes Eve the giver, on every device. `checkSpaceStore` on both stores: an item of before completed from the log; an item whose log does not explain it kept and reported; a value of format 1 read. The copy of the maintainer's database: the items whose `broughtBy` changes counted and listed, the original untouched | done (the copy of the maintainer's database: to do, by the maintainer) |
| 2. The owner preferred (if chosen) | `onSnapshot` waiting for the owner while it is connected and lists the document. Tests: Eve and Ada both connected, Ada's snapshot taken; Ada away, Bob's taken | to do |
| 3. The giver confirmed (if chosen) | `ConfirmGiver` admitted from the owner only, the piece `giver`, `mayGive` by it, the owner confirming on receipt; `ProtocolFormatTest`; with the protocol change of shared-access if both are taken. Tests: a confirmation from an editor refused; once confirmed, a removal and re-addition by Eve leaving her no giver; random `ConfirmGiver` bytes to the decoder; the sync modules on the iOS simulator | to do |

### Phase 1 results

* `CatalogItem` (`pageboard-store`) gains `removed` (the latest removal) and `arrivals` (the stamps
  of the additions after it, in their order). `broughtBy` is the replica of the first arrival
  while the item is in the space, written with every change of the presence
  (`CatalogItem.withBringer`).
* `add` keeps the stamp of an addition after the latest removal, and changes the presence as
  before. `RemoveFromSpace` keeps the latest removal and drops the arrivals up to it, even when a
  later addition keeps the item in the space. An addition that changes only the arrivals has no
  inverse (nothing the library shows changed).
* Within one operation the first change of the presence of an item counts, for the arrivals as for
  the presence: an addition after a removal of the same stamp is no arrival, and a removal after
  an arrival of the same stamp is no removal.
* `CatalogMigration` completes the items of before when `SpaceLibrary` opens a space. It replays
  the additions and removals of the whole log, and checks that the replayed presence is the stored
  one.
  * Completed items are kept with `SpaceStore.saveCatalogItems` (new: the items without an
    operation, in both stores).
  * An item the log does not explain keeps who brought it as it was kept, with its presence as its
    only arrival. It is told by `SpaceLibrary.unexplainedItems` at the start that completes it.
* Tests:
  * `SpaceCatalogTest.anItemAddedAgainKeepsWhoBroughtIt`: Eve adding Ada's document again, Ada
    still brought it; taken out and added again by Eve, Eve did.
  * `whoBroughtAnItemIsTheSameInEveryOrder`: seven operations in all 5 040 orders give the same
    items. They are the example of the design, an operation adding then removing, and one removing
    then adding.
  * `anItemOfBeforeIsCompletedFromTheLog`: an item as a build of before wrote it (JSON), completed
    from its log, kept as it was without it, the log not read when nothing is of before.
  * `devicesConvergeOnRandomChangesAndOrders` now also adds documents again, and adds and removes
    the same item in one operation, in either order. It passed on 40 seeds in the build, and was
    checked on 5 000 seeds of 400 steps.
  * `checkCatalogOfBefore` (`pageboard-store-testing`), on both stores
    (`KeyValueDocumentStoreTest`, `PageboardDatabaseTest`): a catalog of before opened by a
    `SpaceLibrary`. The document Eve added again goes back to Bob, an item taken out is completed
    as out, and an item whose addition is not in the log is kept and told. All of it is kept,
    read again by a new store, and not completed again at the next start.
  * `SpaceSharingTest`: Bob adding again the notes Ada brought may not give them, and Ada still
    may.
  * Each check has a test failing without it (checked by changing it): `broughtBy` from the
    latest arrival fails three tests. A removal counting after an addition of its own operation
    fails the test of every order.
* The ABI of `pageboard-store`, `pageboard-store-testing` and `pageboard-store-room` is recorded
  again. Meant: the constructor and `copy` of `CatalogItem` take the two fields, and `SpaceStore`
  gains `saveCatalogItems`. The rest are additions.
* An engine-level test of the silent re-addition needs an application of its own (an honest
  `SpaceLibrary` adds an item already in the space only while it is hidden): it is covered by the
  catalog and sharing tests. The engine is tested in phase 3, with the visible way round.
* `CatalogsOfACopyCheck` (`pageboard-store-room`, never run by `jvmTest`) opens a copy of a
  database and completes its catalogs as a `SpaceLibrary` does, with an empty library beside them
  so that nothing else changes. It prints, for each space, the items of before, the ones whose
  `broughtBy` changes (from whom to whom), and the ones the log does not explain. It refuses a path
  in the data directories of the applications.

  ```bash
  ./gradlew :pageboard-store-room:jvmTest --tests '*CatalogsOfACopyCheck*' -Ppageboard.catalogCopy=<copy of the database file>
  ```

* Still to do, by the maintainer: that check on a copy of the PencilNotes database (`CLAUDE.md`:
  `~/.pencilnotes` is not touched while verifying). It was run here without a copy only.

Every phase ends with the checks of `CLAUDE.md` (with `./consumer-checks/check.sh`, since
`pageboard-store` is published) and the tests of the sync modules on the iOS simulator. One commit
per phase on `feat/brought-by`, the status updated with it.

## Decisions

### Taken by the maintainer (2026-11-24)

* Every phase, implemented at once, the open decisions taken by me.
* The devices on builds of before need not be supported: the applications are not in production,
  and the maintainer updates every device. So phase 3 does not wait for the protocol change of
  [shared-access.md](shared-access.md).

### Taken by me, on the maintainer's word (revertible)

1. **Phases 1, 2 and 3.**
   * Phase 1 stops the silent takeover, the one nobody sees.
   * Phase 2 costs no protocol change, and covers the window before a confirmation reaches a
     device.
   * Phase 3 closes the way round once the owner has confirmed.
2. **`PROTOCOL_VERSION` 3 with phase 3.** `ConfirmGiver` travels, so the format changes. A device
   still on 2 is told so (`otherVersionMet`) rather than failing on a change it does not know.
   The ALPN of iroh follows (`pageboard/sync/3`). Phases 1 and 2 change nothing that travels.
3. **The past rewritten by recomputation.**
   * Recomputing gives every item back the device that really brought it, the same on every
     device, since every device has the whole log.
   * Keeping it would leave a takeover made before this change in place.
   * The maintainer sees, on the copy of their database, how many items change.

### Taken by me (revertible)

* A plan of its own rather than a phase of [shared-folders.md](shared-folders.md): that plan is
  finished but for its tests on real devices, and this is a fix of the trust rules, which concern
  [untrusted-members.md](untrusted-members.md) as much.
* The earliest addition after the latest removal, rather than the earliest addition ever: an
  editor could stamp an addition far in the past (the stamps are bounded ahead, not behind), and
  make it the earliest forever.
* A list of arrivals per item, rather than one stamp: with one stamp the result depends on the
  order in which the operations arrive (see the example).
* The honest re-addition in `reconcileLocked` left as it is: it now changes only the place and the
  stamp of presence.
* The items of before completed from the log when a space is loaded, rather than when an operation
  touches them: who a device takes a document from must follow the rule from the moment it
  starts.
* An item whose log does not explain it kept as it is: a device never invents who brought an item.

## Left for later

* When the catalog log is compacted (it is not today), the arrivals older than the point every
  member has seen collapse to their earliest one, as the log does.
* A snapshot that can be checked (signed by its giver over its content and version vector, or
  rebuilt from signed operations while the log is complete). With it, any member could give a
  document, and `broughtBy` would matter only for the operations before the log starts.
* The same rule for the folders: `broughtBy` of a folder is not used today.
