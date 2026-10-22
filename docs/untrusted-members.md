# Members not trusted

The devices of a shared document stop trusting each other blindly: a member with a modified
application cannot write as a reader, make its changes pass for another member's, take the key
of the document away from the others, make them forget operations, or crash them with what it
sends.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

Asked by the user on 2026-10-22, the third point of a review of what publishing the applications
to other users needs (the second, the limits before authentication, is phase 8 of
[internet-sync.md](internet-sync.md)). It starts from `feat/internet-sync` (protocol 2), on the
branch `feat/untrusted-members`.

What a member can do today with a modified application:

* **Write as anyone.** An operation travels as a `SealedOperation`: its replica and sequence in
  clear, its content encrypted with the key of the document. Whoever has the key seals an
  operation under any replica, so a reader writes as an editor, and a member writes as another
  one. `docs/collaboration.md` says so: readers are "not against a modified application".
* **Give the others a wrong key.** `DocumentSharing.adoptKey` takes the first key a member
  gives for the epoch of the membership: a wrong one leaves the device unable to reach the
  others.
* **Give a wrong document.** A device without the document takes the snapshot of any member.
* **Make the others forget operations.** `KnownVersions` carries what every member has, and a
  device compacts its log with it: a member saying that another one has everything makes the
  others drop the operations that one misses, for good.
* **Give a wrong file.** The bytes of a resource are not checked against the hash its
  `AddResource` names, and a download allocates the size announced at once (up to 512 MB).
* **Crash them.** `SyncEngine.run` catches `SyncException` only: anything else thrown while a
  message is handled leaves the engine, and in an application it is an uncaught exception.

What stays as it is: a member may change the document as its role allows (an editor may delete,
as itself), sees all of it, and may keep operations to itself.

## Requirements

1. An operation is accepted only signed by the device of its replica (the `DeviceIdentity` the
   membership names), wherever it comes from: from its author, or through other members.
2. Readers and removed members stay unable to write, now against a modified application too.
3. A key update is accepted only when it is the key the owner chose for that epoch.
4. A document is received from its owner only.
5. What a member says it has counts for that member only.
6. A resource received is the file its `AddResource` names (its SHA-256), and is not allocated
   before its bytes come.
7. Nothing another device sends makes this one fail: the connection closes, the application goes
   on. Checked with random and changed messages, operations and snapshots.
8. The notes of the user are kept: the stored formats change with tested migrations.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The signature of an operation by its author: what is signed (the document, the replica, the sequence, the content), signing, checking | `OperationProof` (`drawview-sync-protocol`), with `DeviceIdentity` | Information Expert, Pure Fabrication |
| An operation as it travels: identity in clear, content encrypted, the signature of its author | `SealedOperation` (+ `signature`) | Information Expert |
| Who signs the operations of a replica | `Membership` (the member, or the removed one, of the replica) | Information Expert |
| Keeping the proofs of the operations of the others, to give them on | `OperationLog.proofs` / `saveProofs` (`drawview-store`): opaque bytes by operation, removed with the operations (compaction, deletion); `KeyValueDocumentStore`, `RoomDocumentStore` | Pure Fabrication, Protected Variations |
| Signing the operations of this device as they leave, checking the ones that come, keeping and giving on the proofs | `SyncEngine` | Controller |
| The key of each epoch, as the owner chose it | `Membership.keyHash` (the SHA-256 of the key, signed with the membership); `DocumentSharing.adoptKey` checks it | Information Expert |
| The document from its owner only | `SyncEngine.onSnapshot` / `catchUp` | Controller |
| What each member has, said by that member | `SyncEngine.onKnownVersions` | Information Expert |
| The file of a resource as its operation names it | `SyncEngine.onResourceChunk`, with `DrawResource.hash` | Information Expert |
| A failure handling what another device sent closes its connection | `SyncEngine.run`, `SyncCodec` | Protected Variations |

### Signed operations

* The author signs `drawview-sync/operation|<document>|<replica>|<sequence>|` followed by the
  content (the operation encoded as it travels, before the encryption). The key of the document
  is not in it: a proof stays good when the key changes.
* This device signs its own operations as they leave (its older ones too). It keeps the proof
  (the content and the signature) of every operation of the others it accepts, and gives it on
  unchanged: the content is not encoded again, so nothing in another encoding makes it differ.
  An operation of another device without a proof (received before this change) is not given on:
  its author gives it.
* An operation is checked once its membership is known (the ones of a device admitted meanwhile
  already wait for it): its replica must be one of a member, or of a removed one, and the
  signature the one of that device. A signature that does not check closes the connection that
  carried it.

### Stored proofs

* `OperationLog` gains `proofs(documentId, ids)` and `saveProofs(documentId, proofs)`, opaque
  bytes by operation, with default implementations doing nothing (a store without them gives on
  only the operations of its own device). `compactOperations` and the deletion of a document
  remove them.
* `KeyValueDocumentStore`: a value of its own per document (the map of the proofs), next to the
  operations. A store written before has none.
* `RoomDocumentStore`: a table `operation_proofs` (document, replica, sequence, proof), schema 19,
  `Migration18To19` creating it, tested with `MigrationTestHelper` on a database of schema 18
  with documents and operations.

### Protocol (back to version 1)

| What changes | Before | After |
|---|---|---|
| `SealedOperation` | replica, sequence, content | + `signature` |
| `Membership` | | + `keyHash` (empty in the memberships of before: their keys are taken as before) |
| `Snapshot` | from any member | from the owner only (the same message) |
| `KnownVersions` | relayed, recorded for every device | recorded for the device that sends it only (the same message) |

`PROTOCOL_VERSION` goes back to 1, with these formats and those of `feat/internet-sync`
(addresses, limits): the user decided so, the applications being on two devices of theirs only,
updated together. `ProtocolFormatTest` records format 1 again. A device on a build of before with
the same number does not meet the "other version" message: it is to be updated with the other.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1. Stored proofs | `OperationLog.proofs` / `saveProofs`; `KeyValueDocumentStore`; Room schema 19 with `Migration18To19` (`MigrationTestHelper`); compaction and deletion. Tests: kept, read again after a restart, removed with the operations, a store of before read | to do |
| 2. Signed operations | `OperationProof`, `SealedOperation.signature`; the engine signing, checking, keeping and giving on the proofs; `Membership.keyHash` and `adoptKey`; the snapshot from the owner; `KnownVersions` of the sender; the resources checked against their hash and received in parts; `PROTOCOL_VERSION` back to 1 and `ProtocolFormatTest`. Tests: a reader writing as an editor, a member writing as another, an operation given on through a member, a wrong key, a snapshot of a member, versions said for another, a wrong file | to do |
| 3. Nothing makes a device fail | `SyncEngine.run` closing the connection on any failure of a message; `SyncCodec` never throwing but `SyncException`; tests with random bytes, changed frames, changed operations and snapshots (a fixed seed, on the JVM and the iOS simulator), and what they find fixed | to do |

Every phase ends with the checks of `CLAUDE.md`, the Rust tests when the bridge changes, and the
tests of the sync modules on the iOS simulator.

## Decisions

### Taken by the user (2026-10-22)

* The plan as it is, with its choices.
* `PROTOCOL_VERSION` back to 1: the applications are on two devices of the user only.

### Taken by me (revertible)

* A branch from `feat/internet-sync` rather than from `main`: it needs protocol 2 and the limits
  of phase 8. `main` takes `feat/internet-sync`, then this one.
* The proof kept as the content signed and its signature, rather than the signature alone over
  the operation encoded again from the store: the store keeps the changes as JSON, the network as
  CBOR, and a field read again differently would make every proof given on fail.
* The proofs apart from the operations (a table, a value), rather than a column of the
  operations: the engine knows a proof when the operation comes, the store writes the operation
  when the session applies it.
* The key checked through its hash in the signed membership, rather than a signature of the
  owner on each key update: nothing more to keep, and the key updates stay as they are.
* The snapshot from the owner only: a device that lost its copy waits for the owner.
* What a member has, from that member only: the log is compacted once this device met every
  member, rather than when another one says so.
* A signature that does not check closes the connection, rather than dropping the operation:
  the device that sent it is not to be worked with.

## Left for later

* The presence (cursors, selections, ink being drawn) is not signed: a member may show one for
  another member.
* The items beside the documents (the lecture records) are not signed: a member may add to them,
  or give a wrong file for one missing.
* A member may still change the document as its role allows: deleting as itself, for instance
  (every change can be undone, and nothing is destroyed until the log is compacted).
