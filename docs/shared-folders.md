# Shared folders and library

A folder of the library, or the whole library, is shared with other devices as one thing: what it
holds (documents, folders at any depth) is kept the same on every device of its members, the
documents created or moved into it later too. The same model serves the devices of the user
(the library on the tablet, the computer and the iPad) and other people (a folder of a course,
shared with a classmate).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

Asked by the user on 2026-11-14.

* A document is shared on its own ([collaboration.md](collaboration.md)): its key
  (`SharingState.keys`, a new one at each removal), its owner and its membership signed by the
  owner (`Membership`, `DocumentSharing`), its invitations (`InvitationGrant`, a `drawview://join`
  link), its tag (an HMAC of the key, in the `OpenFrame`) and its connections, one per device and
  document (`SyncEngine.Link`).
* The members are not trusted ([untrusted-members.md](untrusted-members.md)): an operation is taken
  signed by the device of its replica only, a key only as the owner named it, a document from its
  owner only.
* The replica is one per device and store (`DocumentStore.replicaId`), the same in every document.
* The documents received go to the folder "Shared" ("Condivisi"), created the first time. Where a
  document is in the library (`DocumentSummary.folderId`), the folders (`FolderSummary`: name,
  parent), the trash (`docs/trash.md`) are local: nothing of them travels. A document renamed is
  renamed everywhere (`RenameDocument`, a change of the document).
* On the local network a device announces the tags of the documents it shares, 19 per TXT entry
  (`LanSync`); over the internet `IrohSync` connects to the members of each shared document.
  Sharing a library of 100 documents one by one would make 100 invitations, 100 memberships to
  keep, and 100 connections per device.
* Room schema 24.

## Requirements

1. A folder, or the library, is shared with an invitation (link, QR code), as a document is, with
   the same roles (owner, editor, reader) and the same approval of the devices joining.
2. What the shared folder holds is the same on every member: its folders (name, parent), its
   documents (where they are, their content), the trash of its items. A document or a folder
   created, moved in, renamed, moved, put in the trash or restored on a device is so on the others.
3. One membership for the whole folder: a member admitted, made reader or removed is so for every
   document in it; a member removed gets no document of it anymore (a new key for all of them).
4. The members are not trusted, as for the documents: the changes of the folder are signed by the
   device of their replica, refused from a reader; a document comes from the device that created
   it (or put it in the folder) or from the owner; the keys as the owner named them.
5. It scales to the library: the cost of the connections grows with what changed, not with the
   number of documents (a library of hundreds of documents, a phone on mobile data).
6. **Nothing is lost.** A device never deletes a copy because of another one: what is put in the
   trash elsewhere goes to the trash here, deleted for good only by this device. A document moved
   out of the folder, a member removed, a sharing ended: every device keeps its copies. The stored
   formats change with tested migrations (Room with `MigrationTestHelper`, the key-value store).
7. The documents shared with this device by others (owned by others) stay in their own sharing and
   out of the shared library: they reach the other devices of the user only if those join them
   (decided by the user, 2026-11-14).
8. The documents shared on their own keep working as today, and so do the devices that share
   documents only.
9. The rules of the libraries: headless modules without Compose, explicit API recorded, no global
   state; the UI in `drawview-sync-ui` and `drawview-explorer`.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| A shared folder: its identifier, its root, its key, its owner, its membership | `SharedSpace`, `SpaceSharing` (`drawview-sync`), the `SharingState` of a space | Information Expert |
| What a shared folder holds, replicated: folders (name, parent), documents (folder, who brought it), trash, each piece with its stamp | `SpaceCatalog` (`drawview-store`) | Information Expert |
| A change of the folder, applied piece by piece, with its inverse | `CatalogChange` (sealed, `@Serializable`: `AddFolder`, `RenameFolder`, `MoveItem`, `AddDocument`, `TrashItem`, `RestoreItem`, `RemoveFromSpace`) | Polymorphism, Information Expert |
| Single entry point of the changes of a folder: numbering, stamps, admission of the others' | `CatalogSession` (`drawview-store`), as `DocumentSession` for a document | Controller, Pure Fabrication |
| The library changed by the user under a shared folder becomes changes of the folder; the changes received become folders and documents of the library | `SpaceLibrary` (`drawview-store`), between `DocumentCatalog` and the `CatalogSession`s | Indirection, Controller of "organize the library" |
| The key and the tag of each document of a folder, from the key of the folder | `SpaceSharing.documentKey(documentId, epoch)` (HKDF) | Information Expert, Protected Variations |
| What a device may do on a document of a folder | `DocumentSharing`, reading the membership of the folder | Information Expert |
| Synchronizing a folder: its catalog, what each document is at, the documents to bring up to date | `SyncEngine` (`SpaceSync`, a connection per device and folder) | Controller |
| The connections of the documents, opened when they differ or are open in the editor, closed when idle | `SyncEngine` (`DocumentSync` as today, started by `SpaceSync`) | Low Coupling |
| Keeping folders, catalogs and their logs | `SpaceStore` port (`drawview-store`), Room tables (schema 25), key-value values | Protected Variations |
| Sharing a folder or the library, joining, members, stopping | `Collaboration` (`drawview-sync-ui`), the explorer (`drawview-explorer`) | Controller |

### The space

* A shared folder is a **space**: an identifier of its own (a UUID), a root (a folder of the
  library on the owner, the root of the library when the library is shared), a key with its epochs,
  an owner, a membership signed by the owner, invitations. `Membership` and `SharingState` are
  used as they are, with the identifier of the space in place of the one of a document.
* The documents of the space have no key, owner or membership of their own: their key is derived
  from the one of the space (`HKDF(key of the space at the epoch, "drawview-sync/document|" + id)`),
  so their tag too; their membership is the one of the space. A member removed: the space has a new
  epoch, and every document a new key with it, in one step.
* A document belongs to one sharing at most: its own, or the space it is in. A document shared on
  its own and moved into a space keeps its own sharing (its members are not the ones of the space):
  it is shown in the folder, with its badge, and is not in the catalog. A document owned by others
  the same (requirement 7). Putting such a document in the space is left for later.

### The catalog

* What the space holds, as pieces with the stamp of their last change (last-writer-wins, as the
  pieces of a document, with the `HybridClock` of the space):
  * a folder: its presence, its name, its parent (another folder of the space, or the root), its
    trash;
  * a document: its presence, its folder, its trash, the device that brought it (`AddDocument`,
    signed by that device: the one the others take its snapshot from, with the owner).
* The title of a document stays in the document (`RenameDocument`): the catalog does not repeat it.
* Moves of folders made apart may make a cycle (A in B on a device, B in A on another): the tree
  read from the catalog puts the folder of the cycle moved most recently at the root of the space,
  the same on every device. Two items with the same name in the same folder are both kept (the
  library shows them as they are; renaming one is the user's choice).
* Nothing is destroyed: an item out of the space stays in the catalog, hidden, with the stamp of
  its removal, as the removed pieces of a document; the compaction of the log follows the rules of
  the documents (`KnownVersions`).

### The library and the space

* A change of the library under the root of a space (`createFolder`, `renameFolder`, `moveFolder`,
  `moveDocument`, `createDocument`, `trashDocument`, `trashFolder`, `restore`) is also a change of
  the space, submitted by `SpaceLibrary`; the changes received are applied to the folders and
  documents of the library, with the same identifiers on every device.
* A document received is stored where the catalog puts it (`insertDocument(folderId)`), no longer
  in "Shared".
* Moving an item out of the space (`RemoveFromSpace`): on the other devices it leaves the space
  too, kept as a copy of their own, moved to the folder holding the root of the space (the
  library root for a shared library). Moving it back in brings it back as it was.
* The trash: an item put in the trash goes to the trash of every device, and can be restored from
  any of them. Deleting for good (`deleteDocument`, `purgeTrash`) deletes the copy of this device
  only, and takes it out of the space for this device: the others keep theirs.
* Joining a space:
  * a folder: its root becomes a folder at the root of the library of the device joining, with the
    name of the owner's (renamed by the user as it likes: the name of the root is local);
  * the library: in a folder in the same way, or into the library itself (asked, with what it
    means): then the root of the space is the root of the library, and the documents and folders of
    the device joining are brought into the space (`AddDocument` signed by it), except the ones in
    a sharing of their own.
* A reader of a space cannot change its catalog: the folder is read only in the explorer
  (no creating, moving, renaming in it).

### Synchronization

* A connection per device and space: the `OpenFrame` with the tag of the space, then `Challenge`,
  `Hello` and the membership as for a document. Then:
  * the operations of the catalog the other misses (`CatalogOperations`, sealed and signed as the
    operations of the documents: `OperationProof` over `drawview-sync/catalog|<space>|...`);
  * what each document is at (`DocumentHeads`: identifier and a hash of its version vector,
    attachments included), again after its changes stop for 2 seconds.
* A document whose heads differ, or open in the editor of either device, gets its connection as
  today (the `OpenFrame` with its derived tag), started by the space; once both have the same
  heads and neither has it open, it is closed after 30 s. A document missing on a device is
  received from the device that brought it or from the owner (the "from its owner only" of
  [untrusted-members.md](untrusted-members.md), with the device that brought it in the catalog).
* The local network announces the tags of the spaces (and of the documents shared on their own),
  not of the documents of a space. Over the internet `IrohSync` connects to the members of each
  space; one QUIC connection per device as today.
* What a device reads before the other is authorized stays bounded (`receive(maxSize)`); the
  catalog operations are checked as the others: nesting (`cborNestsWithin`), stamps
  (`HybridClock.admits`), signatures, roles; anything not understood closes the connection.

### Protocol

What travels changes: a protocol change.

| What changes | Before | After |
|---|---|---|
| `OpenFrame.tag` | the tag of a document | of a document, or of a space |
| Messages | | `CatalogOperations`, `DocumentHeads` |
| `Membership` | of a document | of a document or of a space (`kind`, default document) |
| `Invitation` | `d=` a document | `s=` a space, with its kind (folder, library) and name |
| Keys | random per document | derived for the documents of a space |

`ProtocolFormatTest` is recorded again. `PROTOCOL_VERSION` stays 1 (see Decisions). A device of
a build of before meeting a space closes the connection (a message it does not know): told by
`otherVersionMet`.

### Storage

* Room schema 25 (`Migration24To25`, tested with `MigrationTestHelper` on a database of schema 24
  with documents, folders, a shared document and its operations):
  * `spaces`: identifier, root folder (local, `null` for the library), sharing state (JSON, as
    `shared_documents`);
  * `catalog_operations` and `catalog_proofs`: the log of each space, as the operations of the
    documents;
  * `catalog_items`: the pieces of the catalog with their stamps;
  * no change to the tables of documents and folders: an item is in a space when its folder is
    under the root of one (a document shared on its own aside).
* `KeyValueDocumentStore`: a value per space (`drawview.space.<id>`, format 1) and its log; a store
  of before has none and reads as before.
* Checked on a copy of the database of PencilNotes of the user, the original untouched.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1. The catalog | `CatalogChange`, `SpaceCatalog`, `CatalogSession` (stamps, admission, cycles, trash, hidden items); convergence tests on random changes and delivery orders, as `ConvergenceTest` | done |
| 2. Storage | `SpaceStore`, Room schema 25 with `Migration24To25` (`MigrationTestHelper`), the key-value store; `SpaceLibrary` turning the changes of the library into changes of the space and back. Tests: both stores, a store of before read, the copy of the user's database | done (the copy of the user's database: to do) |
| 3. Keys and members | `SpaceSharing`, derived keys and tags, the membership of the space read by `DocumentSharing`, epochs; a document from the device that brought it or the owner. Tests of members not trusted: a reader moving a folder, a member bringing a document as another, a key of a member, a removed member | done (the engine checks them in phase 4) |
| 4. Synchronization | The connections about a space (the handshake of the documents, the sharing of the space), its catalog (`CatalogOperations`, sealed and signed), the documents of a space synchronized as the others with the keys and membership of the space, from the owner or the device that brought them; `ProtocolFormatTest`. Tests: two devices sharing a folder (both ways, live, the trash, a member removed), the library joined into the library, a reader, a rule removed failing them, random catalog changes to the decoder | done |
| 4b. Fewer connections | `DocumentHeads`: a document's connection only while it differs or is open, closed when idle; `LanSync` and `IrohSync` announcing and connecting by space only. Tests: a library of 200 documents (connections opened only for what changed), a device away and back | proposed |
| 5. Invitations and joining | The invitation of a space (`s=` and its kind in the link), the approval as for a document, joining into a new folder, a folder, or the library itself (`SpaceRoot`) | done (in the engine; the choice in the interface is phase 6) |
| 6. Interface | Share a folder (explorer), share the library (settings), the badge of a shared folder, members and roles, stop sharing, leave; a folder read only for a reader; strings in the 7 languages; Roborazzi of explorer and sync-ui, the desktop on a scratch directory | proposed |
| 7. Real devices | The user's tablet, computer and iPad: a library shared, changes made apart and met again | left to the user |

Every phase ends with the checks of `CLAUDE.md` and the tests of the sync modules on the iOS
simulator; a commit per phase on `feat/shared-folders`, the status updated with it.

### Phase 1 results

* `drawview-store`, package `store.space`: `SpaceCatalog` (the items with their pieces and stamps,
  `view()` giving the tree of the space), `CatalogChange` (`AddFolder`, `AddDocument`,
  `RenameFolder`, `MoveItem`, `TrashItem`, `RestoreItem`, `RemoveFromSpace`,
  `CompositeCatalogChange`), `CatalogOperation`, `CatalogSession` (`submit`, `admits`, `receive`,
  the version vector, read only).
* An item is four pieces, each with its stamp: its presence (in the space, kind, replica that
  brought it: written whole, so a removal leaves no kind of an addition), its name, its parent,
  its trash. A piece takes the stamp of a more recent change even when its value stays the same:
  otherwise a change stamped between the two would win on some devices only.
* An item named before its addition arrives is kept with what was changed of it, not shown until
  added. An item is shown when it and the folders above it are in the space up to its root; the
  cycles of moves are broken by the folder of the cycle moved most recently, at the root.
* What another device sends is bounded: identifiers of 64 characters at most, names of 512,
  10 000 changes in a composite, no composite in a composite, no item in itself; stamped by its
  replica and at most an hour ahead (`HybridClock.admits`).
* Tests (`SpaceCatalogTest`, 11): the most recent rename, a change before its addition, a cycle,
  a folder out of the space with what it holds, the trash, the inverses of every change, the
  operations refused, a read only session, JSON; three devices making random changes (undo among
  them) and receiving the others' in random orders end up with the same items and the same tree,
  without cycles (40 seeds of 150 steps in the build, verified on 600 seeds of 400). It found
  the presence written in part by a removal, fixed.
* The ABI of `drawview-store` is recorded again: additions only.

### Phase 2 results

* `SpaceStore` (`drawview-store`): the spaces (`StoredSpace`: root, sharing, read only), the items
  of their catalogs, their logs and proofs, and the writes of the library made through a space
  (`putFolder`, `putDocumentFolder`, `putTrashed`: taken as they are, without the checks of the
  user's changes, so that two folders of the same name may be side by side). Removing a space
  forgets its catalog and its log, never the library.
* `RoomDocumentStore`: schema 25, `Migration24To25` creating `spaces`, `catalog_items` (each item as
  JSON), `catalog_operations` and `catalog_proofs`, removed with their space (foreign keys). No table
  of before changes.
* `KeyValueDocumentStore`: the identifiers of the spaces under `drawview.spaces`, each space under
  `drawview.space.<id>` (`StoredSpaceValue`, format 1). A storage of before has neither, and reads
  as before; an index written by a build of before keeps them (they are not in it).
* `SpaceLibrary` compares the library with the tree of each space, rather than wrapping the writes
  of the library: whatever changes the library (the explorer, an agent, an import, the
  synchronization) is seen, and a write of one side is no change of the other.
  * It follows where the items are, the names of the folders and the trash (not the content of the
    documents), settled for 200 ms, and `reconcile()` takes them in at once.
  * Under the root of a space: a folder or a document not in the catalog is added, a name, a place
    or the trash that differs is a change; an item in the catalog moved out of the root, or shared
    on its own, leaves the space. An item not in the library (deleted for good here, or not
    received yet) changes nothing.
  * The changes received are written into the library: the folders from the top down, the
    documents placed, the trash; an item gone from the space taken out of the root, to the folder
    holding it (where the user had not moved it meanwhile). In a shared library an item stays
    where it is (there is no outside), and is added again.
  * A reader's library is put back as the space holds it.
  * A space in a space is refused, and a document shared on its own stays out of the spaces.
  * `localOperations` gives the changes made here to the synchronization (phase 4), `receive`,
    `admits`, `versionVector`, `view`, `folderOf` and `spaceOf` serve it.
* Tests:
  * `checkSpaceStore` (`drawview-store-testing`), run on both stores: kept and read again by a new
    store, an operation kept once, the version vector, the operations in the order of their stamps,
    the proofs, the writes of other devices, a space saved again, removed with the library kept.
  * `checkSharedFolders`, on both stores: two devices, Ada sharing a course, Bob joining into a
    folder of his; the folders and documents arriving where the catalog puts them, a rename, a
    folder made, the trash and its restore, a folder moved out kept by both, a document deleted
    for good on one device kept by the other, the space left with both libraries as they were. It
    found the place of the documents read among the folders only, fixed.
  * `aStorageOfBeforeHasNoSpaces` (key-value store).
  * `DrawDatabaseMigrationTest`: a database of schema 24 with a folder, a document, its page, its
    operation and its sharing migrated to 25, everything read again, its folder then shared; the
    migrations from 16 now validated against 25.
* The ABI of `drawview-store`, `drawview-store-testing` and `drawview-store-room` is recorded
  again: additions only, the stores implementing `SpaceStore`.
* Still to do: the check on a copy of the database of PencilNotes of the user (made by the user,
  the original untouched).

### Phase 3 results

* `drawview-sync-protocol`:
  * `DocumentKey.forDocument(documentId)`: the key of a document of a space, an HMAC-SHA256 of
    its identifier with the key of the space (a tag of its own with it);
  * `MembershipKind` (`DOCUMENT`, `SPACE`) and `Membership.kind`, not written for a document: its
    memberships stay as they were, byte for byte (the signed bytes of the memberships of before are
    the same);
  * `OperationProof.signCatalog` / `isCatalogBy`: the changes of a catalog signed under a label of
    their own (`drawview-sync/catalog`), so that neither a change of a catalog nor an operation of a
    document passes for the other.
* `drawview-sync`:
  * `SharingState.space`: the space a document is shared through (not written for a document
    shared on its own);
  * `SpaceSharing`: the sharing of the spaces (a `DocumentSharing` of kind `SPACE` over
    `SpaceStates`, the sharing kept with each `StoredSpace`); `follow(space)` gives every document
    of the space its sharing from the space (the keys of every epoch derived, the owner, the
    membership), and takes it from the documents that left; `admitsChange` (a change of the
    catalog signed by the device of its replica, as its role allows); `mayGive` (a document from
    the owner or from the device that brought it); `forget`;
  * `DocumentSharing`: the membership of a document of a space is the space's (`adopt` takes the
    space's only, `admit`, `setRole`, `remove`, `end` refused, `adoptKey` refused: the keys come
    from the space); `follow(document, state)` keeps what is known of the members' operations and
    never replaces the sharing of a document shared on its own.
* The documents of a space keep a sharing of their own in the store (derived, the same on every
    member), rather than having the engine derive it at each connection: the engine works on them
    as on the documents shared on their own (tags, keys, membership, read only, compaction), and
    phase 4 adds the space only where it differs.
* `SpaceLibrary(sharedApart)`: tells the documents shared on their own from the ones of a space
  (both have a secret now); the collaboration gives it from `SharingState.space`.
* `ProtocolFormatTest` recorded again (protocol 1): `Membership.kind`, `MembershipKind`.
* Tests (`SpaceSharingTest`): the derived keys (the same on every device, of each document and
  of each space); a membership of a document written as before; the catalog proofs apart from the
  operations; two devices: a folder shared, its document following the space and the one shared on
  its own not, Bob admitted on every document, joining with the key of the space and getting the
  same derived key, a change of Bob taken and the same signed by another device, as an operation of
  a document or by no member refused, a document brought by Bob given by Bob or Ada only, Bob made
  reader (his later changes refused, the documents read only), the sharing of a document of the
  space refused to change on its own, Bob removed (a new key for every document), a document moved
  out shared no more and kept, the space left.
* The ABI of `drawview-store`, `drawview-sync` and `drawview-sync-protocol` is recorded again:
  the constructors of `Membership`, `SharingState` and `SpaceLibrary` take a new parameter (a
  change meant; the libraries are not released with the spaces yet), the rest additions.
* Not run on this host (Windows): the tests of the sync modules on the iOS simulator, to run on
  the Mac with the next phase.

### Phase 4 and 5 results

* `SyncEngine(spaces = SpaceLibrary)`: with it, folders and the library are shared too.
  * A connection about a space is a `Link` decided by the sharing of the space (`Link.access`): the
    same `OpenFrame`, `Challenge`, `Hello` (the version vector of the catalog), membership, key
    updates, invitations, approval and admission as for a document, so that nothing of the trust
    rules is written twice.
  * Then the changes of the catalog the other misses, in the order of their stamps, then the ones
    made as they are made (`SpaceLibrary.localOperations`), relayed to the other members connected
    (never back): `CatalogOperations`, each sealed with the key of the space and signed by its
    author (`OperationProof.signCatalog`); the proofs of the others' changes kept and given on as
    they came.
  * A change received is applied once those before it of its replica are, and once its replica is
    a member (it waits for the membership otherwise); it is checked first, signed by the device of
    its replica as its role allows and admitted by the catalog (stamps, bounds): one that is not
    closes the connection, before anything of its batch is kept or applied. At most 20 000 wait.
  * The documents of a space are synchronized by the connections of the documents, with the keys
    and the membership the space gives them (`SpaceSharing.follow`, after each change of the
    catalog, of the membership or of the keys; their connections are told the membership, the ones
    of devices no longer members closed). A document missing on a device comes from its owner or
    from the device that brought it into the space (`mayGive`, both sides), and is stored where the
    catalog puts it.
  * A member removed from a space, or the sharing ended: the space and its documents are shared no
    more on that device, which keeps its copies.
  * `shareSpace(rootFolderId)`, `joinSpace(invitation, SpaceRoot)` (`NewFolder`, `Folder`,
    `Library`), `followSpaces()`, `spaceMemberships`; `invite`, `setRole`, `removeMember`,
    `unshare`, `invitationsOf`, `revokeInvitation` work on a space as on a document;
    `sharingChanged` tells the spaces too.
* `Invitation.kind` (`InvitationKind`: `DOCUMENT`, `FOLDER`, `LIBRARY`): a link `drawview://join?s=`
  with `n=folder` or `n=library`; the links of documents as before.
* `ProtocolFormatTest` recorded again (protocol 1): `catalogOperations` and the changes of the
  catalog (their content is in the format, as the operations of the documents).
* Tests (`SharedFoldersSyncTest`, through the engines on the in-memory network):
  * a folder shared: Bob joining into a new folder, the folders then the document from Ada where
    the folder has it, a folder and a document of Bob reaching Ada (the document from Bob), a
    document renamed live, a folder moved and a document put in the trash, Bob removed (a new key
    for every document, his copies kept);
  * the library shared and joined into the library: both libraries in both places;
  * a reader renaming a folder: put back, nothing reaching the owner.
  * Without the rule "a document from the device that brought it", two of the three fail.
  * `whatADeviceSendsNeverMakesTheDecodingFail`: the changes of catalogs too, changed at random.
* The ABI of `drawview-store`, `drawview-sync` and `drawview-sync-protocol` recorded again: the
  constructors of `Invitation` and `SyncEngine` and `invitationOf` take a new parameter (meant), the
  rest additions.
* Not run on this host (Windows): the tests of the sync modules on the iOS simulator.
* Left for phase 4b: every document of a space has its connections, as a document shared on its
  own (fine for a library of tens of documents, not of hundreds).

## Decisions

### Taken by the user (2026-11-14)

* One model for the devices of the user and for other people: a space with members and roles.
* The documents owned by others stay out of the shared library (requirement 7).
* The plan as it is, with its choices.
* `PROTOCOL_VERSION` stays 1: the applications are on the devices of the user only, updated
  together, as for `feat/untrusted-members`. `ProtocolFormatTest` records format 1 again.
* A shared library is joined into a folder or into the library itself (both libraries become
  one), as the user chooses when joining.

### Taken by me (revertible)

* A space with the key of its documents derived from its own, rather than a key and a membership
  per document kept the same by the owner: one membership to sign, one rotation at a removal, a
  document created by a member needs nothing of the owner.
* A connection per space, and one per document only while it differs or is open, rather than a
  connection per document always: a library of hundreds of documents costs what changed.
* A catalog of its own beside the documents, with the same rules (stamps, signed operations,
  compaction), rather than the folders in a document: the folders are not pages.
* The title of a document stays in the document, not in the catalog: it is already replicated.
* A cycle of moves broken by putting the folder moved most recently at the root of the space,
  rather than refusing a move: decided by the state alone, the same on every device.
* A document shared on its own and moved into a space keeps its own sharing.
* An item moved out of the space goes, on the others, to the folder holding the root of the space,
  rather than staying where it was: it leaves the shared folder on every device, as on the one
  that moved it.
* Deleting for good deletes the copy of this device only.
* The name of the root of a joined space is local.
* `SpaceLibrary` compares the library with the catalog rather than wrapping the writes of the
  library: every writer of the library is covered, the explorer and the agents unchanged.
* At the start, what differs under the root of a space is taken as a change of this device (made
  while the application was closed, or before it was sent); the changes received are written into
  the library at once, under the same lock.
* An item received in a shared library leaving the space stays where it is and is added again:
  there is nothing outside the library.
* The documents of a space keep a sharing of their own, derived from the space's, rather than the
  engine deriving it: the engine stays as it is where a space changes nothing.
* A connection about a space reuses the handshake of the documents, decided by the sharing of the
  space, rather than a protocol of its own: the trust rules are written once.
* The invitation and the joining of a space in phase 4, before the interface: the engine needs them
  to be tested between devices.
* The interface (phase 6) before fewer connections (phase 4b): the library of the user holds tens
  of documents, which the connections per document serve.
* The catalog items kept as JSON (one column), rather than a column per piece: the format of an
  item can grow without a migration of the table.

## Left for later

* Putting in a space a document shared on its own, or owned by others (the devices of a user known
  to the owners).
* Documents fetched on demand: a phone keeping the catalog and the documents opened only.
* The store-and-forward relay for devices that are never online at the same time (as for the
  documents).
* Sharing a folder inside a shared folder (a space in a space): refused for now.
