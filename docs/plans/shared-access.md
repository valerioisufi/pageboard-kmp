# Shared access: a document shared on its own and through a folder

A document can be shared on its own and be part of a shared folder (or of the shared library) at
the same time. Who has it is the union of the two: like a file in a shared Drive folder that is
also shared with one more person. Removing someone from the folder leaves them what the document's
own sharing gives them, and ending one sharing leaves the other.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

Found on 2026-11-24 while debugging the library sync between the MacBook and the iPhone of the
maintainer (`feat/iphone-library-sync`, [iphone-library-sync.md](iphone-library-sync.md)). Three
documents of the folder Machine Learning never reached the iPhone, and no error showed anywhere.
Each one has a sharing of its own on the MacBook: some with other devices, some with only the
MacBook itself (from an invitation nobody used). The shared library leaves such documents out
without saying so. The maintainer finds this rule limiting and hard to guess, and chose not to
change it on that branch: the three documents wait for this plan.

What is there today (`main` at `aef52037`, protocol 2, Room schema 25):

* **One sharing per document.** `SharingState` holds one owner, one signed membership, one set of
  keys (an `EpochKey` for each epoch) and the versions the members have. It is kept by
  `SharedDocuments`: `shared_documents.secret` in Room, `sharingSecrets` in the key-value store.
  A document of a space keeps there a sharing derived from the space (`SharingState.space` set):
  its keys are `DocumentKey.forDocument(id)` of the keys of the space, and its owner and
  membership are the space's (`SpaceSharing.documentState`, `follow`). The space's own sharing is
  in `spaces.sharing`.
* **The two are exclusive** ([shared-folders.md](shared-folders.md), "A document belongs to one
  sharing at most"):
  * `SpaceLibrary.reconcileLocked` leaves out of the catalog every document shared apart. The
    predicate `sharedApart` comes from `Collaboration` as `SharingState.decode(secret)?.space ==
    null`. A document already in the catalog that gets its own sharing is removed from the space
    (`RemoveFromSpace`).
  * `DocumentSharing.follow` never gives the sharing of the space to a document with a sharing of
    its own (`previous.space == null && previous.members != null`).
* **A connection is about one tag, decided by one `DocumentSharing`.**
  * `SyncEngine.matchOf(tag)` finds the document and its `DocumentSharing`: the documents', or the
    spaces' (`spaceSharing.spaces`).
  * `Link.access` decides everything on that connection: `authorize` (the other device is a
    member), the `KeyUpdate` of an older epoch, the membership exchanged, admitting joiners.
  * `onOperations` checks the operations with `sharing.membershipOf(documentId)`. That is the one
    membership of the document: an operation of a replica it does not know waits, and a
    `MembershipQuery` is sent.
* **The proofs are independent of the key.** An `OperationProof` signs
  `pageboard-sync/operation|<document>|<replica>|<sequence>|` and the content, with no key in it.
  `OperationSeal` encrypts with the key of the link and authenticates its tag. So an operation
  received under one key can be sealed again under another and stays checkable: the engine already
  seals each operation per link (`seal(link, …)`).
* **A document is in one space at most.** It is in one folder, and a space in a space is refused.
  So a document can have at most two accesses: its own sharing and one space.
* `Membership.deviceOf(replica)`, `mayWrite(replica, sequence)` (with `writesUpTo` for a member
  made reader or removed) and `knows(replica)` answer for one membership.
* Store-and-forward relay: not built. `SealedOperation` was designed so that a relay can hold the
  operations of a tag without reading them ([internet-sync.md](internet-sync.md)).
* The rules of [untrusted-members.md](untrusted-members.md) hold for one membership: an operation
  is taken only signed by the device its membership names for the replica, a key only as the
  owner named it, a document from its owner (or, in a space, from the device that brought it,
  `SpaceSharing.mayGive`).

## Requirements

1. **One document, two accesses.** A document can have its own sharing and be in a shared folder
   or the shared library. Both accesses keep the same document: one identifier, one log, one
   content on every device that has it through either access.
2. **Access is the union.**
   * A device has the document while either access names it.
   * Its role is the higher of the two: an editor through one and a reader through the other can
     edit.
   * It is read only only when no access lets it write.
3. **Each access is managed on its own.**
   * Removing a member from the folder (a new key for the space) leaves their own access to the
     document, and the reverse.
   * Ending the document's own sharing leaves it in the folder. Stopping the folder's sharing, or
     moving the document out of it, leaves the own sharing.
   * A member removed from one access never gets the new key of that access, even when the other
     access still lets them in.
4. **What the user sees.** The sharing dialog of a document says where each person's access
   comes from: the document itself, the folder (with its name), or both. When removing would not
   end someone's access, the dialog says so: "Still has access through the folder Physics", "Keeps
   2 notes shared with them directly".
5. **The members are not trusted** (the rules of [untrusted-members.md](untrusted-members.md),
   across both accesses):
   * An operation is taken only when the device its replica belongs to has signed it.
   * Which device a replica belongs to is answered by both memberships together: when they
     disagree, neither answer is taken.
   * A membership of the other access is taken only when it is anchored in what this device
     already trusts. Never because another member sent it.
   * A member removed from both accesses keeps only the operations each one allowed it before.
6. **The library includes such documents.** A document shared on its own and owned by this device
   joins the shared folder or library it is in. The three documents of Machine Learning reach the
   iPhone. The documents owned by others stay out of the shared library
   ([shared-folders.md](shared-folders.md), requirement 7).
7. **Nothing is lost.**
   * The stored sharing states (`shared_documents.secret`, the key-value `sharingSecrets`,
     `spaces.sharing`) are read as they are.
   * Any new format has a tested migration (Room with `MigrationTestHelper` if the schema
     changes, the key-value store, a value of before read again).
   * No copy of a document is deleted when an access ends.
8. **Protocol.** Any change to what travels is listed, and the version is agreed with the
   maintainer (see Decisions). `ProtocolFormatTest` is re-recorded.
9. **The rules of the libraries.**
   * Headless `-sync`, `-sync-protocol`, `-store`; the interface in `-sync-ui`.
   * The ABI changes are meant and recorded.
   * The strings are in the 7 languages.
   * The screenshots are recorded in the Docker image of `tools/screenshots`.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| One way a device reaches a document: its own sharing, or a space; its keys, owner, membership, invitations, versions | `SharingState` as today, one per access (`SharingState.space` names the space, `null` for the own sharing) | Information Expert |
| All the accesses of one document, and what they give together: the role, read only, the devices to compact with, who may give the document | `DocumentAccess` (`pageboard-sync`, new): the own state and the space state, at most one each | Information Expert, Pure Fabrication |
| Which device a replica belongs to, and whether an operation may be written, across both memberships | `UnionMembership` (`pageboard-sync`, new): `deviceOf`, `mayWrite`, `knows` over the memberships of a `DocumentAccess` | Information Expert, Protected Variations |
| Keeping the accesses of a document in one stored value, readable by the builds of before | `SharedDocuments` / `StoreSharedDocuments`, the encoding of `DocumentAccess` (`SharingState.through`) | Protected Variations |
| Which spaces a document's own sharing allows, said by the owner of the document | `Membership.spaces` (`pageboard-sync-protocol`): a `SpaceRef` for each space, signed with the membership | Information Expert, Protected Variations |
| The membership of the other access, given to the members of one access so that they can check its authors | `AccessMembership` message (new) and `SyncEngine.onAccessMembership` | Controller, Indirection |
| Deciding a connection: which access the tag is of, whether the other device is a member of that access | `SyncEngine.matchOf`, `authorize`, `Link.access` (unchanged: one access per connection) | Controller, Low Coupling |
| Deciding an operation: signed by the device of its replica, allowed to write, by the union | `SyncEngine.onOperations` with `UnionMembership` | Controller |
| The documents of a space with a sharing of their own taken into the catalog | `SpaceLibrary` (the predicate `sharedApart` becomes `keptApart`), `Collaboration` | Indirection |
| Where each person's access comes from, and what removing them does | `Collaboration.accessOf(documentId)`, the sharing dialog (`pageboard-sync-ui`) | Controller, Information Expert |

### The access of a document

* A document has a `DocumentAccess`: `own: SharingState?` (the document's own sharing) and
  `space: SharingState?` (derived from its space, as `SpaceSharing.documentState` gives it today).
  Either one may be missing; at most one of each, because a document is in one space at most.
* Each access keeps everything of its own: keys and epochs, owner, membership, invitations,
  `versions`, `closed`. Nothing is merged in what is stored. The union is computed when it is
  read, by `DocumentAccess`:
  * `roleOf(device)`: the higher role of the two;
  * `readOnly`: no access lets this device write;
  * `members`: the devices of both memberships, each with where its access comes from;
  * `mayGive(device)`: the owner of the own sharing, the owner of the space, or the device that
    brought the document into the space;
  * `compactedWith`: the versions of the members of both accesses. The log is compacted only
    once every member of both has the operations.
* `DocumentSharing` keeps `DocumentAccess` values instead of `SharingState` values:
  * `share`, `invite`, `admit`, `setRole`, `remove`, `end`, `adoptKey` work on the own access;
  * `follow` (called by `SpaceSharing`) writes the space access and no longer skips a document
    with a sharing of its own;
  * `forget(documentId, access)` drops one access, and the document is shared no more only
    once neither is left.
* `match(tag)` and `reachable` look at the keys and invitations of both accesses. A `TagMatch`
  says which access it is of. A connection stays about one tag and one access.

### Connections: one access each

* **Authorization is per access, never by the union.**
  * A connection about the tag of an access is authorized only when the other device is a member
    of *that* access.
  * `KeyUpdate`, `MembershipMessage`, `Admission` and invitations are about that access only.
* Why: a member removed from the folder still has the old keys of the space, and `match` takes
  keys of every epoch. If its direct access authorized it on a connection of the space, the device
  would send it the new key of the space, and with it every document of the folder.
* Two connections with the same device about the same document (through both accesses) are
  twins. `workWith` keeps one, as it does today (by document and replica). When one of them
  closes (a member removed from that access), the other one, or the next connection, carries on.
* The connections are made as today:
  * the own sharing always (its tag announced on the local network, its members reached by
    `IrohSync`);
  * the space only while the document's heads differ or it is open (`DocumentHeads`,
    [shared-folders.md](shared-folders.md) phase 4b).

  A device that is in both accesses with another device can reach the document either way. The
  own sharing keeps its connection open as any document shared on its own does today.

### Sealing

* **On a connection:** each connection seals with the key of its access, at the epoch of the
  connection (`Link.seal`, unchanged). The proofs do not depend on the key. An operation
  received through the folder is sealed again with the document's own key for a direct member,
  and the reverse. Its author's signature goes with it unchanged.
* **For the store-and-forward relay (not built yet):**
  * A device leaves a document's operations under the current tag of each access it is a member
    of: two sealed copies when it is in both.
  * A device in one access only leaves them under that tag. The members of the other access get
    those operations from a device in both, as on the direct connections.
  * Recorded here as a constraint for the plan of the relay: it holds operations by tag, and a
    document may have two tags.
* **For iroh:** nothing changes. `IrohSync` connects to the member devices of each access with
  that access's tag. Over the internet, a device in both accesses has one QUIC connection with
  each device, as today.

### Operations across the two memberships

* A device checks an operation against the membership of the document, not of the connection: an
  operation of a direct member can come through the folder, relayed by a device in both. So
  `onOperations` uses `UnionMembership(access)` where it now uses
  `sharing.membershipOf(documentId)`.
* `UnionMembership.deviceOf(replica)`:
  * the device that the memberships naming the replica (as a member or a removed one) agree on;
  * none when they name two different devices. An honest application never does this: the
    replica is one per device and store.
  * So the owner of one access cannot make a replica of the other access its own and sign in its
    name. The operations of that replica wait (as an unknown replica does), and the conflict is
    kept as a reason for the diagnostics.
* `UnionMembership.mayWrite(replica, sequence)`: true when either membership allows it.
  * A member removed from the folder that is still an editor of the document's own sharing keeps
    writing.
  * A member removed from both is held to the larger of its two `writesUpTo`. Each one was set by
    an owner who allowed those operations.
  * The operations it made before stay on every device, as today.
* `knows(replica)`: known in either membership. An operation of a replica known in neither waits,
  and the device asks for the memberships (a `MembershipQuery` on the connection's access, an
  `AccessMembership` query for the other one).

### The other membership, anchored

Three devices make the point: Ada has the document in both accesses, Bob is in the folder only,
Carol has it directly only. Bob must check Carol's operations that Ada relays, and Carol must check
Bob's. Neither holds the other access's membership. So it has to travel, and each device must know
which one to believe: a member could make a membership of its own, sign it with its own identity,
and call itself the owner of the document's direct sharing.

* **The owner of the document names its spaces.** The document's own membership (signed by its
  owner) gets `spaces: List<SpaceRef>`. Each `SpaceRef` holds:
  * the identifier of the space;
  * the device of the space's owner;
  * `writesUpTo`, once the document left the space: the last sequence of each replica of the
    space's members, as for a removed member.

  The field is not written when it is empty (`@EncodeDefault(NEVER)`), so the memberships of
  before stay byte for byte.
* **A direct member (Carol) takes the membership of a space when:**
  * the document's own membership names that space and its owner in a `SpaceRef`;
  * it is signed by that owner, of kind `SPACE`, for that identifier;
  * it is more recent than the one Carol has.

  Carol learns the members of the space (devices, replicas, names, roles), not its key nor its
  catalog.
* **A space member (Bob) takes the document's own membership when:**
  * its owner is the device that brought the document into the space (`CatalogItem.broughtBy`,
    by the space's membership);
  * it names this space in its `spaces`;
  * it is more recent than the one Bob has.
* **Only the owner of the document's own sharing can put it into a space.** The device that
  brings the document into the catalog (`AddDocument`, signed) is the owner of its own sharing.
  This covers the case of the MacBook: it owns the library and the three documents. A document
  shared with this device by someone else stays out of the spaces (requirement 6).
* **The message.** `AccessMembership(documentId, signed: SignedMembership)` goes on the
  connections of a document:
  * sent after authorization when the other device's view of the other access is older (`Hello`
    gains `otherMembershipVersion`), and again whenever it changes;
  * relayed by any member, because it is checked by its signature and its anchor, not by who
    sends it;
  * bounded like a `MembershipMessage` (`receive(maxSize)`, `cborNestsWithin`). Not understood or
    not anchored: the connection closes (`SyncException`).
* **A document leaving the space** (moved out, the space ended, its owner leaving): its owner
  writes the `SpaceRef` with `writesUpTo`.
  * The direct members then take the operations of the space-only members up to there, as for a
    removed member.
  * Until the owner does so (offline), the direct members keep taking them, as the space's
    membership allows.

### Ending and removing

| Action | Own access | Space access | What the person keeps |
|---|---|---|---|
| Remove Bob from the folder | unchanged | new epoch, Bob removed | the document, if he is a direct member: synced through it |
| Remove Bob from the document | new epoch, Bob removed | unchanged | the document, if he is in the folder |
| End the document's own sharing | ended (`Membership.ended`); its members keep their copies | unchanged | the folder's members go on; the direct-only members stop |
| Stop sharing the folder, or leave it | unchanged | ended / forgotten (`SpaceSharing.forget`) | the direct members go on |
| Move the document out of the folder | `SpaceRef.writesUpTo` written | `RemoveFromSpace`, every device keeps its copy | the direct members go on |

A device whose last access ends keeps its copy, as today. A device that loses one access of two
keeps syncing through the other one, and its library keeps the document where it is.

### The library

* `SpaceLibrary(sharedApart)` becomes `SpaceLibrary(keptApart)`: the documents that stay out of
  the spaces. `Collaboration` gives: owned by another device (`own.owner != device`). A document
  shared on its own and owned here is taken into the catalog like any other document under the
  root.
* A document that this device only had directly, and that now arrives through a space: the first
  time, the catalog's place is written into the library. It is not compared as a local change, so
  a document waiting in "Shared" does not emit `RemoveFromSpace` for every other member.
* `isShared` (the badge) stays true while either access is there.
* What the short-term fix discussed for `feat/iphone-library-sync` (a document whose own sharing
  names no device but its owner joins the library) is a case of this rule. The maintainer chose
  not to make it there.

### What the user sees

* **The sharing dialog of a document** (`ShareTarget.Document`) lists:
  * the people of its own sharing, with their roles, managed here as today;
  * "Through the folder Physics" (or "Through the library"): the members of the space, with their
    roles, read only here and with a link to the folder's dialog.

  Someone in both is shown once, with the higher role and both sources ("Editor · also through
  Physics").
* **Removing from the document someone who stays in the folder.** The dialog asks first: "Bob
  still has access through the folder Physics". The same in reverse from the folder's dialog:
  "Bob keeps 2 notes shared with him directly", with their titles.
* **Ending.** "Stop sharing this note" ends its own sharing only, and says what stays ("It stays
  shared through Physics"). "Stop sharing" of a folder says which notes keep a sharing of their
  own.
* **On a device that received the document.** It is placed where the folder puts it. Its sharing
  dialog shows the two sources in the same way. "Leave" leaves one access, the one chosen.
* `Collaboration.accessOf(documentId)` gives the dialog what it shows: the people, their sources
  and their roles. The strings are in the 7 languages. The screenshots: the dialog with both
  sources, the confirmation of a removal that leaves access.

### Protocol

What travels changes: **a protocol change.**

| What changes | Before | After |
|---|---|---|
| `Membership` | `documentId`, owner, members, removed, epoch, `keyHash`, `kind` | + `spaces: List<SpaceRef>` (not written when empty: the memberships of before keep their bytes and signatures) |
| Messages | | `AccessMembership`, its query |
| `Hello` | `membershipVersion`, `epoch` | + `otherMembershipVersion` |
| Operations relayed across accesses | never: one membership per document | an operation of a member of one access sealed again for the other (the same `SealedOperation`, another key) |
| What a device takes | the operations of the membership of the document | of the union of the two memberships, the other one anchored |

Not changed:

* `OpenFrame`, the tags and the derived keys (`DocumentKey.forDocument`);
* `SealedOperation` and `OperationProof`;
* the invitations (`Invitation`, `drawview://join` / `s=` links);
* the catalog changes;
* `DocumentHeads`.

A build of before that meets `AccessMembership` closes the connection (a message it does not
know). One that receives an operation of a replica of the other access waits for it forever.
So the two builds must not meet on the same number: see the decision on `PROTOCOL_VERSION`.

### Storage

* **`shared_documents.secret` (Room) and `sharingSecrets` (key-value):**
  * still one value per document;
  * the encoding of a `DocumentAccess` keeps a value of before readable both ways. The first
    access is written as the `SharingState` it is today. A second access goes in a new field
    `through: SharingState?` of the own state: the space's derived state, not written when
    missing.
  * So: a value of before reads as one access (its `space` field says which). A build of before
    reading a new value sees the document's own sharing and ignores `through`
    (`ignoreUnknownKeys`).
  * No Room schema change (the same column), so no `Migration25To26`. If the maintainer prefers a
    table of accesses (one row per document and access), that is schema 26 with a migration
    tested by `MigrationTestHelper` on a database of schema 25 with a document shared on its own,
    one of a space and their operations (see Decisions).
* **`readOnly`** (the column and the key-value set): the union, written with every change of
  either access.
* **`spaces.sharing`:** unchanged. The `SpaceRef`s are in the signed membership bytes, which the
  stored states already keep as they came.
* **Tests of the stored format:**
  * the values written by the build of now (recorded as fixtures from `main`: a document shared
    on its own, a document of a space, a closed one), read again as one access each;
  * a value with both accesses, read back;
  * that same value read by the `SharingState` decoder of before (a copy kept in the test), which
    gives the own access;
  * both stores (`checkSharedDocuments` in `pageboard-store-testing`);
  * a copy of the maintainer's PencilNotes database (MacBook), the original untouched: every
    secret read, the three documents of Machine Learning with their own access and, after
    joining, the library's.
* Nothing is rewritten at start: a value takes the new shape only when an access is added to it.

## Plan

| Phase | Content | Status |
|---|---|---|
| 0. This plan | Written, the decisions below for the maintainer | done (to approve) |
| 1. Stored accesses | `DocumentAccess` and its encoding (`through`), `SharedDocuments` keeping it, `readOnly` as the union; no change in behaviour (a document still has one access). Tests: the values of before read, the new values both ways, the decoder of before on a new value, both stores, the copy of the maintainer's database | to do |
| 2. The union in the engine | `DocumentSharing` over `DocumentAccess` (`follow` taking the space access next to the own one, `forget` per access), `TagMatch` with its access, `authorize` per access, `UnionMembership` in `onOperations`, the role, read only, `mayGive` and compaction by the union. Tests (`SharedAccessTest`, two devices in both accesses): a document in a folder and shared on its own, synced by either connection; removed from the folder, still synced directly and never given the new key of the space; removed directly, still synced through the folder; the own sharing ended; the document moved out | to do |
| 3. The other membership | `Membership.spaces` / `SpaceRef`, `AccessMembership` and its query, `Hello.otherMembershipVersion`, the anchors on both sides, `PROTOCOL_VERSION` as decided, `ProtocolFormatTest` re-recorded. Tests with three devices (Ada in both, Bob in the folder, Carol direct): Carol's operations reaching Bob through Ada and the reverse; a space reader making a membership of its own for the document, refused; a `SpaceRef` naming another owner, refused; a replica named for two devices, its operations not taken; Bob removed from the folder, his later operations refused by Carol; random `AccessMembership` bytes to the decoder; the sync modules on the iOS simulator | to do |
| 4. The library | `keptApart` in `SpaceLibrary` and `Collaboration`; the catalog's place written the first time a document arrives through a space; the documents owned by others still out. Tests: `checkSharedFolders` with a document shared on its own in the folder (both stores), one owned by another device left out, a document first had directly then through the library | to do |
| 5. Interface | The sharing dialog with the sources of access, the confirmations of removal and ending, "Leave" per access; strings in the 7 languages; Roborazzi recorded in the Docker image; the desktop on a scratch directory | to do |
| 6. Real devices | The MacBook and the iPhone: the three documents of Machine Learning on the iPhone, a direct member removed from the library and still synced, the own sharing of one ended | to do |

Every phase ends with the checks of `CLAUDE.md` (with `./consumer-checks/check.sh` for phases 1
to 3, which change published APIs) and the tests of the sync modules on the iOS simulator. One
commit per phase on `feat/shared-access`, the status updated with it.

## Decisions

### For the maintainer

1. **`PROTOCOL_VERSION`: 3 or stays 2.** Phase 3 changes what travels (`Membership.spaces`,
   `AccessMembership`, `Hello`), and what a device takes changes from phase 2.
   * **3 (proposed):** a device on protocol 2 meets a device on 3, is told so by
     `otherVersionMet`, and the two do not sync until both are updated. The ALPN of iroh becomes
     `pageboard/sync/3` (it follows the number).
   * **Stays 2:** possible only if every device (the MacBook, the iPhone, the others) is updated
     together. A device of before then fails the connections where the new message comes.
2. **Who may put a document with its own sharing into a space: its owner only (proposed).**
   Members of the space can check the document's membership only by its owner being the one who
   brought it. Taking in a document owned by someone else (as Drive lets an editor do) needs that
   owner's consent, carried in its membership. Left for later.
3. **The role: the higher of the two (proposed).** A document cannot be limited below what its
   folder gives (Drive's "limited access" folders). Left for later.
4. **Who sees whom.** To check the authors, the direct members learn the members of the space
   (names, devices, roles) and the reverse, never the keys nor the catalog. This is what makes the
   union work. It is also what Drive shows ("People with access"). Accept, or the dialog of the
   other side shows only "and 3 through a folder"? The devices still need the full membership in
   either case.
5. **Storage: one value with `through` (proposed) or a table of accesses.**
   * One value: no Room migration, and a build of before still reads the own sharing.
   * A table (schema 26): one row per access, queried directly, with a `MigrationTestHelper`
     migration.
6. **A replica named for two devices: its operations wait (proposed),** and the conflict shows in
   the diagnostics. Closing the connection would let the owner of one access cut a device off
   from the other access.

### Taken by me (revertible)

* At most two accesses (the own one and one space), modelled as two fields rather than a list. A
  document is in one folder, and spaces do not nest. A list costs nothing more if that changes.
* Each access stored apart, the union computed when read: removing or ending one never touches
  the other.
* A connection authorized by its own access only, the union used for the operations: otherwise a
  member removed from the folder would get its new key through its direct access.
* The anchor of the other membership is in what the device trusts already: the owner of the
  document names its spaces; the space members check that owner against `broughtBy`. A message
  relayed by anyone is believed for its signature and anchor, not for its sender.
* `writesUpTo` for a document leaving a space, in its `SpaceRef`: the same rule as a removed
  member.
* The operations sealed for the relay under each tag a device has, rather than a per-document key
  for the relay: no new key to distribute. To be confirmed in the relay's plan.
* Phases 1 and 2 before the protocol change (phase 3), so that the union is tested locally first.
  Until phase 3, a device in one access only cannot check the operations of a member of the other
  access (Bob, in the folder only, and Carol's operations): they wait, as an unknown replica's do
  today, and nothing unchecked is taken. Phase 2 alone does not ship to the devices.
* The catalog's place written the first time a document arrives through a space, rather than
  compared: a document waiting in "Shared" must not take itself out of the space for everyone.

## Left for later

* Putting into a space a document owned by someone else, with that owner's consent.
* A document limited below its folder's role.
* The store-and-forward relay holding two tags for one document (its own plan).
* A replica bound to its device by construction (derived from the device's key), which would make
  `deviceOf` conflicts impossible: a change of every replica and of what travels.
* Telling a member why an operation waits (a replica in conflict, a membership not anchored) in
  the interface.
