# Members not trusted

The devices of a shared document stop trusting each other blindly: a member with a modified
application cannot write as a reader, make its changes pass for another member's, take the key
of the document away from the others, make them forget operations, crash them with what it
sends, or make its changes win over the ones made after them (phases 4 and 5).

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

### The stamps (phases 4 and 5)

Asked on 2026-10-24, found while studying the stacking order of the elements: the stamps an
operation carries are taken as they are. It starts from `main`, on the branch
`feat/bounded-stamps`. What an editor can do with an application of its own, its operations
signed as they should be:

* **Win for good.** `DocumentSession.receive` observes the stamp of an operation without a bound
  (`HybridClock.observe`). A renaming stamped a century ahead is taken by the owner, given on to
  the third device, and the next changes of both are stamped a century ahead too. With the
  greatest stamp (`Long.MAX_VALUE`, `Int.MAX_VALUE`) the counter of the next stamp overflows:
  the stamps made after it are smaller, and what the member changed stays as it made it.
* **Freeze a text of another device.** A change carries stamps of the document it was made from:
  the versions of its texts (`Versions`, by replica), the orders of its elements
  (`ElementOrder`), what a removal of a page saw (`RemovePage.saw`). Nothing bounds them. A
  change of a text saying that it included the changes of Ada of a century from now makes the
  next changes of Ada to that text count as included already: they are dropped, on Ada's device
  too. An order from the future keeps an element above every other one.
* **Stamp as another device.** Nothing compares the replica of a stamp with the one of its
  operation: in the versions of a text, the change of a member may count as one of another
  device.
* **Push the clocks when the document is loaded again.** `DrawDocument.latestStamp()` observes
  the orders and the versions too, the ones a member put in its changes among them.

Nothing in `drawview-sync` or `drawview-sync-protocol` bounds a stamp: `SyncEngine.onOperations`
checks the signature and the role only. `anOperationStampedFarAheadIsRefused` (`SyncEngineTest`)
and the four tests of `FutureStampTest` (`drawview-model`) fail today.

What limits it: the clock is the one of the document (a `DocumentSession` each), so a member
pushes the clocks of the documents it is a member of only. And a device that observed the stamp
stamps its next changes after it: until the counter overflows, the member wins over the changes
made without seeing its own (offline, or before it arrived), not over the later ones.

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
9. An operation of another device is taken only stamped by its replica, at most an hour ahead
   of the wall clock of this device, and with no stamp in its change after its own: a member
   cannot make its changes win over the ones made after them, nor push the clocks of the others
   further than that hour.
10. The changes made after an operation win over it: the clock never makes a stamp smaller than
    one it saw.
11. The devices of the user sync as before: the operations of the applications, the ones already
    in the logs too, are taken.

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
| How far ahead of the wall clock a stamp made elsewhere may be; the next stamp, never smaller than one seen (phase 4) | `HybridClock.admits`, `HybridClock.next` | Information Expert |
| The stamps a change carries: the orders of its elements, the versions of its texts, what a removal saw (phase 4) | `DocumentChange.stamps()`, overridden by the changes carrying some | Polymorphism, Information Expert |
| An operation of another device that may be received: stamped by its replica, not ahead, carrying nothing after its stamp (phase 4) | `DocumentSession.admits`; `receive` refusing the others | Information Expert, Protected Variations |
| The connection carrying an operation not admitted closed, before anything of it is kept or applied (phase 5) | `SyncEngine.onOperations` | Controller |

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

### Stamps not ahead (phases 4 and 5)

* `HybridClock.admits(stamp)`: a stamp made elsewhere may be observed when it is at most
  `MAX_AHEAD_MS` (an hour) ahead of the wall clock here, with no negative part. The bound is from
  the wall clock, not from the hybrid clock: from the hybrid clock, every stamp admitted would
  move the bound, and a member would push the clocks an hour at a time, as far as it likes.
* `HybridClock.next()` moves to the next millisecond when its counter is at its greatest, rather
  than overflowing: a negative counter makes a stamp smaller than the one seen, and out of the
  text order of the stores (`Stamp.toString`, kept as `TEXT`).
* `DocumentChange.stamps()`: the stamps a change carries from the document it was made from (none
  by default): the orders of the elements of `AddElements`, `AddPage`, `UpdateTexts` and the
  placements of `PlaceElements`, the versions of their texts, what `RemovePage` saw, the ones of
  the changes of a `CompositeChange`. Each change knows what it carries, a change added later
  too.
* `DocumentSession.admits(operation)`: its stamp is of its replica (`stamp.replica` is
  `id.replica`), admitted by the clock, and no stamp its change carries is after it. The author
  had seen all of them (its clock observed every stamp of its document), so every operation of
  the applications passes, and a member cannot say that it included changes made after its own
  operation (the ones of Ada of a century from now): the devices receiving it observe its stamp,
  so their next changes are after anything it carries. `receive` refuses an operation not
  admitted (`IllegalArgumentException`): whoever uses the model without the engine has the same
  rule.
* `SyncEngine.onOperations`: once the signatures and the roles are checked, an operation not
  admitted closes the connection that carried it (a `SyncException`), before its proof is kept,
  its strokes modeled or anything of it applied, as a signature that does not check.

The clocks of the devices:

* Clocks set by the network (all the devices of the user) differ by less than a second. An hour
  lets through a clock set by hand a few minutes off, a device that stayed out of the network for
  long, and the time an operation takes to come.
* A device whose clock is more than an hour ahead of another one's cannot give it its changes:
  the other one closes the connection, which is made again every 5 seconds (LAN) or 10 (iroh),
  and closed again, until the clocks are set right. A device more than an hour behind refuses the
  changes of the others the same way. For that document only: the connections are by document.
* What a device admitted it stamps after, and gives on: an operation admitted at the bound and
  given on at once to a device whose clock is behind may be refused there; the connection is made
  again and the operation taken once the time passed covers the difference of the two clocks, a
  few seconds at most between clocks set by the network.

The operations already in the logs:

* Nothing is replayed from the log when a document is loaded: the stores keep the document as it
  is. The log gives the operations on to the other devices, which check them as any other.
* They pass: the bound is from the clock of the device receiving, when it receives, so an
  operation made before by a clock that was right is in the past; and the rules on the replica and
  on the stamps a change carries hold for every operation the applications made, before this
  change too. Checked on the operations the random changes of `ConvergenceTest` exchange, and on
  a copy of the database of the user (every operation of its log admitted at the time of the
  check, no stamp of its documents ahead of it), the original untouched.
* An operation stored that does not pass (made before this change by a clock far ahead, or by a
  member) stays as it is, never rewritten: the devices it is given to close the connection that
  carries it. If the check finds one, the user decides what to do with it.

When a document is loaded:

* Its stamps are observed as they are (`latestStamp()`), without the bound: they were checked as
  they came (or made here), and a device whose clock went back (reset, set by hand) keeps
  stamping its changes after them. Bounding them would make the changes of that device lose
  against what it has already, silently.
* `latestStamp()` reads the stamp of the title too (`titleVersion`, left out until now: a renaming
  received from a clock a little ahead could win over the next renaming made here after the
  document was loaded again).

The snapshot of the owner is not bounded: the owner gives the document as it likes already
(requirement 4), and a stamp far ahead in it reaches the clock of that document on the device
joining only, whose changes the others then refuse.

### Protocol (phases 4 and 5: unchanged)

What travels does not change: the frames, the messages, the sealed operations, the stamps as they
are written. `ProtocolFormatTest` stays as it is, and `PROTOCOL_VERSION` stays 1 (to be agreed with
the user). What changes is what a device takes: an operation stamped ahead, or carrying stamps
after its own, is taken by the builds of before and refused by this one. No operation of the
applications is among them, so the devices on the two builds sync as before. The rule is written
in the protocol of `docs/collaboration.md`, and "a clock far ahead wins" there becomes "a clock
up to an hour ahead".

## Plan

| Phase | Content | Status |
|---|---|---|
| 1. Stored proofs | `OperationLog.proofs` / `saveProofs`; `KeyValueDocumentStore`; Room schema 19 with `Migration18To19` (`MigrationTestHelper`); compaction and deletion. Tests: kept, read again after a restart, removed with the operations, a store of before read | done |
| 2. Signed operations | `OperationProof`, `SealedOperation.signature`; the engine signing, checking, keeping and giving on the proofs; `Membership.keyHash` and `adoptKey`; the snapshot from the owner; `KnownVersions` of the sender; the resources checked against their hash and received in parts; `PROTOCOL_VERSION` back to 1 and `ProtocolFormatTest`. Tests: a reader writing as an editor, a member writing as another, an operation given on through a member, a wrong key, a snapshot of a member, versions said for another, a wrong file | done |
| 3. Nothing makes a device fail | `SyncEngine.run` closing the connection on any failure of a message; `SyncCodec` never throwing but `SyncException`; tests with random bytes, changed frames, changed operations and snapshots (a fixed seed, on the JVM and the iOS simulator), and what they find fixed | done |
| 4. Stamps bounded in the model | `HybridClock.admits`, `MAX_AHEAD_MS`, `next` without overflow; `DocumentChange.stamps()`; `DocumentSession.admits`, `receive` refusing; `latestStamp()` with the title; the ABI of `drawview-model` recorded again (additions), the consumer checks. Tests (`FutureStampTest`): the end of time, the greatest counter a minute ahead, a century ahead, a text said to include the future, an order, a version and a removal after the stamp of their operation, in a composite change too, the stamp of another replica, at the bound and a millisecond past it, negative parts, a document loaded with stamps ahead of the wall clock; every operation `ConvergenceTest` exchanges admitted | approved |
| 5. Refused by the engine | `SyncEngine.onOperations` closing the connection before keeping or applying anything; the operations of the tests stamped now (`forged`, 35 000 years ahead until now); `docs/collaboration.md`. Tests (`SyncEngineTest`): `anOperationStampedFarAheadIsRefused`; a member ten minutes ahead, taken, the next changes of the others after it; an order or a version after the stamp, refused; an operation of a member stamped ahead given on by another one, refused; `aMemberSendingAnythingStopsNothing` as before. The check on a copy of the database of the user | approved |

Every phase ends with the checks of `CLAUDE.md`, the Rust tests when the bridge changes, and the
tests of the sync modules on the iOS simulator.

### Phase 1 results

* `OperationLog.proofs(documentId, ids)` and `saveProofs(documentId, proofs)`, with default
  implementations keeping nothing; `OpenDocument.proofs` / `saveProofs` for the engine. A proof
  for a document deleted meanwhile is not kept.
* `KeyValueDocumentStore`: the value `drawview.proofs.<document>` (`StoredProofs`, format 1: the
  proofs in Base64 by replica and sequence), read once per document, removed with the document,
  pruned by the compaction. A store written before has none, and reads as before.
* `RoomDocumentStore`: the table `operation_proofs`, schema 19, `Migration18To19` creating it;
  the compaction deletes the proofs with their operations, the deletion of a document by its
  foreign key. The proofs are read by replica, 500 sequences per query at most.
* `checkOperationProofs` in `drawview-store-testing`, run on both stores: kept by document and by
  operation, read again by a new store on the same storage, removed by the compaction and the
  deletion. `DrawDatabaseMigrationTest`: a database of schema 18 with a document and its
  operation migrated to 19, the operation read, a proof kept for it; the migration from 16 now
  validated against 19.
* The ABI of `drawview-store`, `drawview-store-testing` and `drawview-store-room` is recorded
  again: additions only.
* Checked on a copy of the database of PencilNotes of the user (schema 18: 12 documents, 588
  pages of which 88 removed, 2131 operations), the original untouched: migrated to 19, every
  document read with its 500 pages and its operations, a proof kept and read.

### Phase 2 results

* `OperationProof` (`drawview-sync-protocol`): signs and checks
  `drawview-sync/operation|<length>:<document>|<length>:<replica>|<sequence>|` and the content (the
  lengths, so that no other parts give the same bytes), kept as CBOR. `SealedOperation` carries
  the signature; `OperationSeal.seal` takes a proof and `open` gives one. `Membership.deviceOf`
  (a member's or a removed one's), `Membership.keyHash`, `DocumentKey.hash`.
* `SyncEngine`:
  * it signs the operations of this device as they leave, once each (the last 1024 proofs of a
    document kept in memory), its older ones too;
  * an operation is checked once its membership is known; one not signed by the device of its
    replica closes the connection; the proofs of the operations of the others it takes are kept
    (`OpenDocument.saveProofs`) and given on with them, from memory or from the store; an
    operation of another device without a proof is not given on;
  * the snapshot from the owner only, and a device that is not the owner gives nothing of the
    document to one without it;
  * `KnownVersions` carries the versions of the device sending it, and only those are taken;
  * a resource is received in a buffer growing as its bytes come, and kept only when its SHA-256
    is the one of its `AddResource`.
* `DocumentSharing`: the owner names the key of each epoch in the membership (a membership of
  before gets it at its first change); `adoptKey` takes, when it is named, that key only.
* `PROTOCOL_VERSION` back to 1 (the ALPN of iroh is `drawview/sync/1` again); `ProtocolFormatTest`
  records format 1 again, now with the membership (it travels in the bytes the owner signs, and
  was not in the format before).
* Tests of members with an application of their own (`SyncEngineTest`: a device speaking with the
  identity of a member, sending what it likes), each failing without its check (checked by
  removing it): a member writing as another one; a reader writing as itself; an operation of a
  member given on by another, taken; the operations of a member reaching the owner through
  another one, after both started again (the proofs read from the store); a key of a member for
  the new epoch, refused, the owner's taken; a document lost on the way to a device joining, not
  given by a member nor taken from one, then received from the owner; a member saying that another
  one has everything, the log of the owner not compacted until that one says so; a wrong file for
  an image, not kept, the right one kept at the next connection. `SealedOperationTest`: the
  signature of another device, document, replica, sequence or content does not check.
* The cable of the tests cut silently now lets a pending read go when its end is closed, as a
  connection closed by its own side does.
* Compacting needs what each member says of itself, at its connections: the log keeps the
  operations of a connection until the next one.

### Phase 3 results

* What the tests found, and is fixed:
  * a frame changed on the way made the CBOR decoder throw an `ArrayIndexOutOfBoundsException`,
    which `SyncCodec.decode` let through (it caught two kinds of exceptions): out of `run`, it was
    an uncaught exception in the application;
  * a change nested 100 000 times (composite changes in composite changes, a few megabytes)
    makes the decoder run out of stack: a `StackOverflowError` on the JVM, the end of the
    application on iOS, where it cannot be caught.
* `cborNestsWithin` (`drawview-sync`): reads the CBOR of a frame or an operation without
  recursion and refuses it beyond 200 levels, before it is decoded. The changes of the
  applications nest far less (30 composite changes in each other pass).
* `SyncCodec.decode` gives `null` and `decodeOperation` a `SyncException` for any failure;
  `OpenFrame.decode` and `OperationProof.decode` give `null`.
* `SyncEngine`: any failure while a connection starts (`accept`, `connect`), handles a message
  (`run`), sends (the sender) or sends an item asked for (the transfers) closes that connection
  only; the cancellations go on as they are.
* Tests:
  * `whatADeviceSendsNeverMakesTheDecodingFail`: 3 000 frames, operations, open frames and proofs
    changed at random (bytes changed, cut, added, others altogether), from messages of every
    kind; what the devices send is read back as it was;
  * `aMemberSendingAnythingStopsNothing`: a member sends 150 changed messages of every kind, then
    300 operations with one or two bytes changed, signed as they should be (123 of them are
    applied: odd values); the owner and another member go on, their documents the same, and the
    store of the owner reads the document again;
  * `aFailureHandlingWhatAMemberSentClosesItsConnectionOnly`: the storage of an item failing on
    the owner closes that connection, and the owner goes on with the others;
  * `DeepNestingJvmTest`: the change nested 100 000 times, as an operation and in a frame, refused
    (encoded on a thread with a large stack, as the device sending it would);
  * every test of `SyncEngineTest` fails when an exception leaves an engine
    (`CoroutineExceptionHandler` of its scope).

  Without the new catches, the first and the third fail; without the check of the nesting, the
  last one.

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
* The nesting of the CBOR checked before decoding, rather than a depth counted by the
  serializers of the changes: it protects every kind of value, the ones added later too.
* 200 levels at most: far more than the changes of the applications, far less than the stack of a
  thread decoding them.

### Taken by the user (2026-10-24)

* Phases 4 and 5 as they are, with their choices.
* The bound: an hour. Less (ten minutes) would give a member less advantage over the changes made
  offline, and refuse sooner a device whose clock was set by hand; more (a day) the other way
  round. Nothing wins for good with any bound.
* `PROTOCOL_VERSION` stays 1: nothing that travels changes, and the devices on the two builds
  sync as before.
* The check on the real notes (phase 5) on a copy of the database of PencilNotes made by the
  user.

### Taken by me, phases 4 and 5 (revertible)

* Phases of this plan rather than a document of their own: the same threat (a member with an
  application of its own), the same rules.
* A branch from `main` (`feat/bounded-stamps`): phases 1 to 3 are in it.
* The bound from the wall clock, not from the hybrid clock: from the hybrid clock it would move
  with every stamp admitted.
* The stamps a change carries checked against the stamp of its operation, rather than against
  the wall clock: they are what its author had seen, so the operations of the applications pass,
  and a member cannot say it included changes made after its own (the text of Ada said to
  include the future: with the wall clock only, the next changes of Ada would be dropped for up
  to an hour).
* An operation not admitted closes the connection, rather than being kept until its time comes:
  a stamp a century ahead would be kept a century; the connection is made again anyway every few
  seconds, and the operation taken once it is no longer ahead.
* `receive` refusing too: the rule is the model's, the engine checks first so that nothing of
  the batch is kept or applied.
* The stamps of a document stored observed as they are when it is loaded, rather than bounded: a
  device whose clock went back keeps its changes winning.
* The snapshot of the owner not bounded: the owner gives the document as it likes already.
* `latestStamp()` keeps reading the orders and the versions: harmless once they are bounded by the
  stamps of their operations.

## Left for later

* The presence (cursors, selections, ink being drawn) is not signed: a member may show one for
  another member.
* The items beside the documents (the lecture records) are not signed: a member may add to them,
  or give a wrong file for one missing.
* A member may still change the document as its role allows: deleting as itself, for instance
  (every change can be undone, and nothing is destroyed until the log is compacted).
* A member may still say that its change of a text included the changes of another device made
  before it, received or not: their content is then not kept as a copy under the text. Knowing
  what a member received needs its operations to name what they follow (a version vector each), a
  change of what travels.
* Within the hour, the change of a member stamped ahead wins over the changes made without seeing
  it (offline), as the one of a clock an hour ahead would.
* A device whose clock is off by more than an hour stops syncing that document silently: telling
  the user ("the date of this device, or of that one, is wrong") needs the reason of the closing
  up to the interface.
* The lecture records have stamps of their own (the `Stamp` of `drawview-lecture`), not bounded
  either: with their signature.
* The stacking order (`docs/z-order.md`, proposed): its new keys will not be stamps. Then
  `DocumentChange.stamps()` names only the orders still written as stamps, and `latestStamp()`
  may stop reading the orders (an order is never after the `placed` of its element).
