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
| 1. The catalog | `CatalogChange`, `SpaceCatalog`, `CatalogSession` (stamps, admission, cycles, trash, hidden items); convergence tests on random changes and delivery orders, as `ConvergenceTest` | proposed |
| 2. Storage | `SpaceStore`, Room schema 25 with `Migration24To25` (`MigrationTestHelper`), the key-value store; `SpaceLibrary` turning the changes of the library into changes of the space and back. Tests: both stores, a store of before read, the copy of the user's database | proposed |
| 3. Keys and members | `SpaceSharing`, derived keys and tags, the membership of the space read by `DocumentSharing`, epochs; a document from the device that brought it or the owner. Tests of members not trusted: a reader moving a folder, a member bringing a document as another, a key of a member, a removed member | proposed |
| 4. Synchronization | `SpaceSync`: the catalog, `DocumentHeads`, document connections opened and closed; `LanSync` and `IrohSync` announcing and connecting by space; `ProtocolFormatTest`. Tests: three devices, a library of 200 documents (connections opened only for what changed), a device away and back, random messages; on the JVM and the iOS simulator | proposed |
| 5. Invitations and joining | The invitation of a space, approval, joining into a folder or into the library; the documents arriving where the catalog puts them | proposed |
| 6. Interface | Share a folder (explorer), share the library (settings), the badge of a shared folder, members and roles, stop sharing, leave; a folder read only for a reader; strings in the 7 languages; Roborazzi of explorer and sync-ui, the desktop on a scratch directory | proposed |
| 7. Real devices | The user's tablet, computer and iPad: a library shared, changes made apart and met again | left to the user |

Every phase ends with the checks of `CLAUDE.md` and the tests of the sync modules on the iOS
simulator; a commit per phase on `feat/shared-folders`, the status updated with it.

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

## Left for later

* Putting in a space a document shared on its own, or owned by others (the devices of a user known
  to the owners).
* Documents fetched on demand: a phone keeping the catalog and the documents opened only.
* The store-and-forward relay for devices that are never online at the same time (as for the
  documents).
* Sharing a folder inside a shared folder (a space in a space): refused for now.
