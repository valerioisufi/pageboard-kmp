# Shared access: an item shared on its own and through a folder

Two things that are exclusive today become possible, with access being the union in both cases:

* **A document shared on its own and through a folder.** A document can have its own sharing and
  be part of a shared folder (or of the shared library) at the same time. Who has it is the union
  of the two, like a file in a shared Drive folder that is also shared with one more person.
* **A folder shared inside a shared folder.** A folder can be shared inside a shared folder or
  inside the shared library: a space in a space, to any depth. What it holds is reachable through
  each of the spaces around it.

Removing someone from one sharing leaves them what the others give them, and ending one sharing
leaves the others.

Who may do what beyond the roles is not fixed by the library. The owner of each sharing decides,
with **grants** signed in its membership, and every device enforces them. The sharing modules are
published for other applications too, so the library gives the mechanism and the choices.
PencilNotes is one application that makes them.

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

The maintainer then asked, the same day:

* for folders shared inside shared folders in the same plan. The shared library is a space too: a
  user who shares the library between their own devices cannot today share one folder of it with
  a classmate;
* that who may share a folder inside a shared folder be the owner's choice;
* that the plan not limit the sharing modules to what PencilNotes needs. `pageboard-sync`,
  `pageboard-sync-protocol` and `pageboard-sync-ui` are published on Maven Central as
  `io.github.valerioisufi:pageboard-*`, for other applications in other contexts.

What is there today (`main` at `a366e7ec`, protocol 3, Room schema 25):

* **One sharing per document.** `SharingState` holds one owner, one signed membership, one set of
  keys (an `EpochKey` for each epoch) and the versions the members have. It is kept by
  `SharedDocuments`: `shared_documents.secret` in Room, `sharingSecrets` in the key-value store.
  A document of a space keeps there a sharing derived from the space (`SharingState.space` set):
  its keys are `DocumentKey.forDocument(id)` of the keys of the space, and its owner and
  membership are the space's (`SpaceSharing.documentState`, `follow`). The space's own sharing is
  in `spaces.sharing`.
* **A document's own sharing and a space are exclusive** ([shared-folders.md](shared-folders.md),
  "A document belongs to one sharing at most"):
  * `SpaceLibrary.reconcileLocked` leaves out of the catalog every document shared apart. The
    predicate `sharedApart` comes from `Collaboration` as `SharingState.decode(secret)?.space ==
    null`. A document already in the catalog that gets its own sharing is removed from the space
    (`RemoveFromSpace`).
  * `DocumentSharing.follow` never gives the sharing of the space to a document with a sharing of
    its own (`previous.space == null && previous.members != null`).
* **A space in a space is refused.**
  * `SpaceLibrary` refuses a root under the root of another space, and a space whose root holds
    one (`isUnder` both ways).
  * The sharing dialog says so when the user tries ([shared-folders.md](shared-folders.md), phase
    7).
  * It is in the "Left for later" of [shared-folders.md](shared-folders.md).
  * So an item (a folder or a document) is in one space at most, and a document has at most two
    accesses: its own sharing and one space.
* **What the owner decides is fixed in the code.**
  * There are three roles: owner, editor, reader (`Role`).
  * Only the owner invites, admits, changes roles and removes (`DocumentSharing.invite`,
    `ownerChange`).
  * Which items go into a shared library is a rule of the library: the documents owned by others
    stay out ([shared-folders.md](shared-folders.md), requirement 7, decided for PencilNotes).
  * `Membership` has no field for a choice of the owner beyond the roles.
* **A catalog is last-writer-wins per piece.** Each item of a `SpaceCatalog` has four pieces:
  presence, name, parent and trash. Each piece carries its `Stamp` (`presence`, `named`,
  `placed`, `trashedVersion`), from the `HybridClock` of its space.
  * `SpaceLibrary` compares the library with the tree of each space. What differs under a root is
    a local change, and the changes received are written into the library.
  * An item gone from a space is moved to the folder holding that space's root.
* **Who gives a document of a space** ([brought-by.md](brought-by.md), on `main` since
  2026-11-24). Found while writing this plan, and fixed on its own:
  * `broughtBy` is the first addition after the latest removal (`CatalogItem.arrivals`). A
    member adding an item again while it is in the space no longer becomes the one it is taken
    from.
  * The owner of a space confirms the giver of each document once it has it (`ConfirmGiver`, the
    piece `giver`, protocol 3). After that, taking a document out and adding it back gives
    nothing either.
  * A device without a document waits for the owner's snapshot while the owner is connected and
    has it.
  * This plan still anchors nothing on `broughtBy` (see "The other memberships, anchored"). It
    says who brought an item, not who owns its own sharing, and it changes when the item is
    taken out and added back.
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
* `Membership.deviceOf(replica)`, `mayWrite(replica, sequence)` (with `writesUpTo` for a member
  made reader or removed) and `knows(replica)` answer for one membership. A membership is decoded
  with `ignoreUnknownKeys`: a field a build does not know is dropped silently.
* Store-and-forward relay: not built. `SealedOperation` was designed so that a relay can hold the
  operations of a tag without reading them ([internet-sync.md](internet-sync.md)).
* The rules of [untrusted-members.md](untrusted-members.md) hold for one membership: an operation
  is taken only signed by the device its membership names for the replica, a key only as the
  owner named it, a document from its owner (or, in a space, from the owner of the space or the
  giver it confirmed, else the device that brought it: `SpaceSharing.mayGive`).

## Requirements

1. **One document, several accesses.** A document can have its own sharing and be in a shared
   folder or the shared library, itself in other shared folders. Every access keeps the same
   document: one identifier, one log, one content on every device that has it through any access.
2. **Folders shared inside shared folders.**
   * A folder inside a shared folder, or inside the shared library, can be shared on its own.
   * A shared folder can be moved into another one.
   * The library can be shared while it holds shared folders.
   * What the inner folder holds is in every space around it. Its members see it as their root,
     and the members of the outer space see it where it is.
   * A change made through either space reaches the devices of both.
3. **Access is the union.**
   * A device has an item while any access names it.
   * Its role is the highest of them: an editor through one and a reader through another can
     edit.
   * It is read only only when no access lets it write.
4. **Each access is managed on its own.**
   * Removing a member from one access (a new key for it) leaves what the others give them. This
     holds for a document and its folder, and for an inner folder and the outer folder or the
     library.
   * Ending one sharing, or moving an item out of one space, leaves the others.
   * A member removed from one access never gets its new key, even when another access still lets
     them in.
5. **The owner decides; every device enforces it.**
   * The owner of a sharing chooses who besides itself may:
     * share an item of its space on its own, a folder or a document (the subfolder rule asked by
       the maintainer);
     * bring into its space an item that has a sharing of its own;
     * put its item into a space of theirs.
   * The choice is signed with the membership, so a modified application cannot take more than it
     was given.
   * It can be changed later, as a role can. What was allowed before stays, the same on every
     device.
6. **A library for other applications.**
   * The capabilities of `pageboard-sync` and `pageboard-sync-protocol` are not limited to what
     PencilNotes uses. A rule written for PencilNotes is a default or a choice of its own, made in
     `pageboard-sync-ui` or in the application, never a limit of the engine.
   * An application can attach grants of its own to a membership. The engine signs them, carries
     them and gives them back, and never reads them.
   * Everything the interface of PencilNotes shows (where access comes from, the grants, the
     consequences of a removal) comes from the public API. Another application can build its own
     interface on it.
   * The public API grows without breaking binary compatibility once published: recorded in
     `api/`, and checked by `consumer-checks`.
   * Bounds that an application may tune (the depth of nesting) are parameters with defaults.
     What every device must compute alike (convergence, the checks) is fixed by the protocol.
7. **What the user sees.**
   * The sharing dialog of a document or folder says where each person's access comes from: the
     item itself, the folder or library around it (named), or several.
   * The owner chooses, in the dialog of a shared folder, who may share what is inside it.
   * When removing would not end someone's access, the dialog says so: "Still has access through
     the folder Physics", "Keeps 2 notes shared with them directly", "Keeps the folder Lab shared
     with them".
8. **The members are not trusted** (the rules of [untrusted-members.md](untrusted-members.md),
   across all accesses):
   * An operation is taken only when the device its replica belongs to has signed it.
   * Which device a replica belongs to is answered by all the memberships together: when they
     disagree, no answer is taken.
   * A membership of another access is taken only when it is anchored in what this device
     already trusts, and with the consent of both sides as their grants allow. Never because
     another member sent it.
   * A grant this device does not understand allows nothing.
   * A member removed from every access keeps only the operations each one allowed it before.
   * A catalog change is signed and checked in the space it is submitted to, as today. A device
     relaying a change from one space to another signs it as its own, and only as its role there
     allows.
9. **The library includes such items.**
   * A document shared on its own joins the shared folder or library it is in when both sides
     allow it: the owner of the space (or a member it granted) and the owner of the document (or
     a member it granted). So the three documents of Machine Learning, all owned by the MacBook,
     reach the iPhone.
   * A shared folder joins the shared folder or library it is in in the same way.
   * PencilNotes keeps its own rule for its library: items owned by others stay out (requirement
     7 of [shared-folders.md](shared-folders.md); see Decisions).
10. **Nothing is lost.**
    * The stored sharing states (`shared_documents.secret`, the key-value `sharingSecrets`,
      `spaces.sharing`) and the catalog items (`catalog_items`) are read as they are.
    * Any new format has a tested migration (Room with `MigrationTestHelper` if the schema
      changes, the key-value store, a value of before read again).
    * No copy of an item is deleted when an access ends.
    * Two catalogs never make an item move back and forth.
11. **Protocol.** Any change to what travels is listed, and the version is agreed with the
    maintainer (see Decisions). `ProtocolFormatTest` is re-recorded.
12. **The rules of the libraries.**
    * Headless `-sync`, `-sync-protocol`, `-store`; the interface in `-sync-ui`.
    * The ABI changes are meant and recorded, and the `Module.md` of each module explains the
      model to other applications.
    * The strings are in the 7 languages.
    * The screenshots are recorded in the Docker image of `tools/screenshots`.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| One way a device reaches a document: its own sharing, or a space; its keys, owner, membership, invitations, versions | `SharingState` as today, one per access (`SharingState.space` names the space, `null` for the own sharing) | Information Expert |
| All the accesses of one document, and what they give together: the role, read only, the devices to compact with, who may give the document | `DocumentAccess` (`pageboard-sync`, new): the own state and the states of its spaces, innermost first | Information Expert, Pure Fabrication |
| Which device a replica belongs to, and whether an operation may be written, across all memberships | `UnionMembership` (`pageboard-sync`, new): `deviceOf`, `mayWrite`, `knows` over the memberships of a `DocumentAccess` | Information Expert, Protected Variations |
| What the owner allows beyond the roles: the capabilities of the library and the application's own | `Grant` in `Membership.grants` (`pageboard-sync-protocol`, new), read by `Membership.holds(capability, replica, sequence)` | Information Expert, Protected Variations |
| The capabilities the engine understands | `Capability` (`pageboard-sync-protocol`, new): `SHARE_INSIDE`, `BRING_SHARED`, `PLACE_IN_SPACES` | Information Expert |
| Keeping the accesses of a document in one stored value, readable by the builds of before | `SharedDocuments` / `StoreSharedDocuments`, the encoding of `DocumentAccess` (`SharingState.through`) | Protected Variations |
| Which spaces around an item its sharing allows, said by its owner or by a member it granted | `Membership.spaces` (`SpaceRef`, signed by the owner) and `Placement` (signed by a member holding `PLACE_IN_SPACES`), `pageboard-sync-protocol` | Information Expert, Protected Variations |
| That an item of a space has a sharing of its own, and who owns it | `ShareItem` / `UnshareItem` (`CatalogChange`, new), a piece `sharedAs` of the catalog item | Information Expert, Polymorphism |
| The membership of another access, given to the members of one access so that they can check its authors | `AccessMembership` message (new) and `SyncEngine.onAccessMembership` | Controller, Indirection |
| Deciding a connection: which access the tag is of, whether the other device is a member of that access | `SyncEngine.matchOf`, `authorize`, `Link.access` (unchanged: one access per connection) | Controller, Low Coupling |
| Deciding an operation: signed by the device of its replica, allowed to write, by the union | `SyncEngine.onOperations` with `UnionMembership` | Controller |
| Deciding a catalog change: its role, and the grant it needs (`ShareItem`) | `SpaceSharing.admitsChange` | Information Expert |
| The spaces around each item; the changes of the library given to every one of them; the value of a piece taken from the catalogs that hold it | `SpaceLibrary` (the predicate `sharedApart` becomes `keptApart`; `spacesOf(item)`; `NestedPieces`, new) | Indirection, Controller of "organize the library", Pure Fabrication |
| Joining a space whose items are already in the library | `SpaceLibrary.join` (`SpaceRoot.InPlace`, new) | Creator |
| What an application reads and does about access: the sources, the roles, the grants, sharing, placing, ending one access | `SyncEngine.accessOf(itemId)`, `share`, `setGrants`, `place`, `unshare(itemId, access)`, `leave(itemId, access)` (`pageboard-sync`, public) | Controller, Low Coupling |
| What PencilNotes shows of it, and the choices of its owner | `Collaboration.accessOf`, the sharing dialogs (`pageboard-sync-ui`) | Controller |

### Mechanism, policy, defaults

Three layers, so that the library serves applications other than PencilNotes without giving up the
rules of [untrusted-members.md](untrusted-members.md):

| Layer | Who decides | Where it lives | Examples |
|---|---|---|---|
| Mechanism | the protocol, the same on every device | `pageboard-sync`, `pageboard-sync-protocol` | the union of accesses, the anchors, the checks of signatures, the convergence of nested catalogs |
| Policy | the owner of each sharing, signed | `Membership.grants`, `Membership.spaces`, `Placement` | who may share a folder of my space, who may put my note into their folders |
| Defaults and offers | the application | its own code, `pageboard-sync-ui` for PencilNotes | the grants of a new sharing, which choices the dialog shows, which items a library keeps apart |

* An application can only narrow locally what it does (not offer a choice, keep items apart). It
  never changes what other devices accept: they follow the signed policy.
* What every device must compute the same way is never a policy: which value wins between two
  catalogs, where an item moved out goes, the bounds of what is read before authorization. A
  device that computed it differently would diverge.

### Grants

* **A grant is a capability given to some members.** `Membership.grants: List<Grant>`, signed
  with the membership by the owner, not written when empty (the memberships of before keep their
  bytes). A `Grant` holds:
  * `capability`: a name. The library's are under `pageboard.`; an application's are under a
    name of its own (`com.example.comments`);
  * to whom: the members of at least a role (`EDITOR`), and/or devices named one by one;
  * `upTo`: for a member that held it and no longer does, the last sequence of its replica that
    it covered, as `writesUpTo` does for a role.
* **The owner holds every capability.** A new sharing has no grants unless the application gives
  defaults (`share(…, grants)`). The owner changes them with `setGrants`, a new version of the
  membership, as for `setRole`.
* **Grants only allow, so a device fails closed.**
  * A device takes into account only the capabilities it knows. A grant it does not know allows
    nothing, so a build of before refuses what a newer grant would allow, and never allows what
    the owner did not give.
  * No policy is written as a restriction: a field forbidding something would be dropped by a
    build that does not know it (`ignoreUnknownKeys`), and allowed by it.
* **What was allowed stays allowed, the same everywhere.**
  * A catalog change needing a capability is admitted when its replica held it at that sequence:
    by the grant, or by the grant's `upTo` once taken back.
  * Every device decides the same, whatever the order in which the memberships arrived, as for a
    member made reader.
* **The capabilities of the library:**

  | Capability | Granted in | Allows |
  |---|---|---|
  | `pageboard.share-inside` | the membership of a space | sharing an item of the space on its own while it stays inside: a folder as a new space, a document with a sharing of its own (submitting `ShareItem`) |
  | `pageboard.bring-shared` | the membership of a space | bringing into the space an item that has a sharing of its own: a document shared on its own, a shared folder moved in (submitting `ShareItem`) |
  | `pageboard.place-in-spaces` | the membership of a document or of a space | putting this item into a space of the member's own, without the owner being online (signing a `Placement`) |

  Without `share-inside`, a member can still move an item out of the space and share it, as
  today. The grants decide what the space does. They do not make its content secret from its
  members, who can copy what they see.
* **The grants of an application** are signed and carried like the library's, given back by
  `accessOf(itemId).grants`, and never read by the engine. An application enforces them on what
  it adds beside the documents, and tells its users. This is Protected Variations: a capability
  that an application needs later costs it no change of the library.
* **What the maintainer asked:** "who may share a folder inside" is `pageboard.share-inside`,
  chosen by the owner of the space. PencilNotes offers it in the dialog of a shared folder ("Who
  can share what is inside: Only me / Editors too").

### The access of a document

* A document has a `DocumentAccess`:
  * `own: SharingState?`, the document's own sharing;
  * `spaces: List<SharingState>`, one derived state for each space it is in, innermost first (as
    `SpaceSharing.documentState` gives it today).

  Either may be empty. What is read from others is bounded to the engine's `maxNesting` (a
  parameter, 8 by default).
* Each access keeps everything of its own: keys and epochs, owner, membership, invitations,
  `versions`, `closed`. Nothing is merged in what is stored. The union is computed when it is
  read, by `DocumentAccess`:
  * `roleOf(device)`: the highest role among the accesses;
  * `readOnly`: no access lets this device write;
  * `members`: the devices of every membership, each with where its access comes from;
  * `mayGive(device)`: the owner of the own sharing, or, for each space, its owner or the giver
    it confirmed (until then, the device that brought the document into it);
  * `compactedWith`: the versions of the members of every access. The log is compacted only once
    every one of them has the operations.
* `DocumentSharing` keeps `DocumentAccess` values instead of `SharingState` values:
  * `share`, `invite`, `admit`, `setRole`, `setGrants`, `remove`, `end`, `adoptKey` work on the
    own access;
  * `follow(documentId, state)` (called by `SpaceSharing` for each space) writes that space's
    access and no longer skips a document with a sharing of its own;
  * `forget(documentId, access)` drops one access, and the document is shared no more only
    once none is left.
* `match(tag)` and `reachable` look at the keys and invitations of every access. A `TagMatch`
  says which access it is of. A connection stays about one tag and one access.
* The spaces themselves follow the same model:
  * The sharing of a space stays in `spaces.sharing`, as today.
  * An inner space is reached through its own tag only. Its catalog is its own and is not
    derived from the outer space.
  * The union of the spaces counts for the items they hold, not for the catalogs.

### Folders inside shared folders

* **Allowed.** `SpaceLibrary` no longer refuses a root under another root. It refuses only a
  space whose root is the root of another space, and a chain deeper than `maxNesting`.
  * A folder F inside the space S1 is shared as S2: its root is F, and F stays an item of S1.
  * The same holds when S1 is the library.
  * The device sharing F must hold `share-inside` in S1. To move a shared folder into S1, it must
    hold `bring-shared` there.
* **Every item under F is in both catalogs.**
  * The members of S1 see F and what it holds where it is, with the stamps of S1.
  * The members of S2 see F as their root, with the stamps of S2.
  * `SpaceLibrary.spacesOf(item)` lists the spaces whose root is above the item, innermost
    first.
* **A change made here goes to every space.** `reconcileLocked` compares the library with each
  space's tree, as today:
  * A change under F (a rename, a move inside F, the trash, a document added) becomes a change
    of S2 and a change of S1, each submitted by `SpaceLibrary` and stamped by its space's clock.
  * A move across the edge of F: moving an item from F to elsewhere in S1 is `RemoveFromSpace`
    in S2 and `MoveItem` in S1. Moving one in is `AddDocument` / `AddFolder` in S2 and `MoveItem`
    in S1.
* **The library is where the catalogs meet.**
  * A device in both S1 and S2 receives a change from S2 and writes it into the library. The
    next comparison with S1 finds the library differs and submits the same change to S1, signed
    by this device. The reverse holds too.
  * So a device in both spaces relays the changes between the members of one only, and it signs
    them as its own in the space it submits to.
  * A relayed change is refused when this device's role does not allow it there. A reader of S1
    who is an editor of S2 cannot pass S2's changes into S1: S1 gets them from another device in
    both, or not at all, and stays as its own members made it.
* **Which value the library takes** (`NestedPieces`):
  * For each piece of an item held by several catalogs (presence, name, parent inside F, trash),
    the library takes the value with the latest stamp across those catalogs. The stamps of all
    spaces come from hybrid clocks of the wall time, bounded an hour ahead
    (`HybridClock.admits`), so they compare. Ties go to the higher replica, as within a catalog.
  * The catalogs that hold an older value then get the value from the next comparison, with a
    new stamp of their own. Once a value is everywhere, it differs nowhere and nothing more is
    submitted.
  * So two devices in both spaces cannot keep moving an item back and forth: they compute the
    same winner from the same catalogs.
  * Tested with random changes through two nested spaces and three devices (one in each space,
    one in both), as `SpaceCatalogTest` does for one space.
* **The root F of S2.**
  * Its name and its parent are pieces of S1 only. For S2 the name of the root is local, as
    today.
  * Moving F inside S1 changes nothing for S2.
  * Putting F in the trash in S1 puts it in the trash on the devices of S1. On a device of S2
    only, the root of S2 is untouched.
* **An item moved out of F by a member of S2 only** (into its own library) leaves S2. On the
  devices in both, the rule of today applies: an item gone from a space goes to the folder
  holding its root, which is F's parent in S1. So for S1 it is a `MoveItem` to F's parent. For
  the member of S2 the item is a copy of its own outside, and nothing is deleted on anybody's
  device.
* **Ending.** Stopping the sharing of S2, or a member leaving it, leaves F and what it holds in
  S1. Moving F out of S1 (`RemoveFromSpace` of F in S1) takes everything under F out of S1, and
  S2 goes on. Every device keeps its copies.
* **Joining a space whose items are already here** (`SpaceRoot.InPlace`).
  * A device of S1 joining S2 already has F and what it holds, with the same identifiers. The
    join takes F as the root of S2 where it is, rather than making a new folder.
  * The items already in the library are compared with S2's catalog, not added again.
  * This avoids a folder made twice, which happened in phase 7 of
    [shared-folders.md](shared-folders.md) with two admissions.

### Connections: one access each

* **Authorization is per access, never by the union.**
  * A connection about the tag of an access is authorized only when the other device is a member
    of *that* access.
  * `KeyUpdate`, `MembershipMessage`, `Admission` and invitations are about that access only.
  * Why: a member removed from the folder still has the old keys of the space, and `match` takes
    keys of every epoch. If another access authorized it on a connection of the space, the device
    would send it the new key of the space, and with it every document of the folder. The same
    goes for an inner space and the library around it.
* Two connections with the same device about the same document (through two accesses) are twins.
  `workWith` keeps one, as it does today (by document and replica). When one of them closes (a
  member removed from that access), the other one, or the next connection, carries on.
* The connections are made as today:
  * the own sharing always (its tag announced on the local network, its members reached by
    `IrohSync`);
  * the documents of a space only while their heads differ or they are open (`DocumentHeads`,
    [shared-folders.md](shared-folders.md) phase 4b). Each space sends the heads of every document
    it holds, the ones under an inner space too.
  * a connection per device and space for the catalogs, one for each space the two devices
    share.
* A device that is in several accesses with another device can reach the document either way.
  The own sharing keeps its connection open, as any document shared on its own does today.

### Sealing

* **On a connection:** each connection seals with the key of its access, at the epoch of the
  connection (`Link.seal`, unchanged). The proofs do not depend on the key. An operation received
  through one access is sealed again with the key of another, and its author's signature goes
  with it unchanged.
* **For the store-and-forward relay (not built yet):**
  * A device leaves a document's operations under the current tag of each access it is a member
    of: one sealed copy per access.
  * A device in one access only leaves them under that tag. The members of the other accesses get
    those operations from a device in several, as on the direct connections.
  * Recorded here as a constraint for the plan of the relay: it holds operations by tag, and a
    document may have several tags.
* **For iroh:** nothing changes. `IrohSync` connects to the member devices of each access with
  that access's tag. Over the internet, a device has one QUIC connection with each device, as
  today.

### Operations across the memberships

* A device checks an operation against the memberships of the document, not of the connection.
  An operation of a direct member can come through the folder, relayed by a device in both. So
  `onOperations` uses `UnionMembership(access)` where it now uses
  `sharing.membershipOf(documentId)`.
* `UnionMembership.deviceOf(replica)`:
  * the device that every membership naming the replica (as a member or a removed one) agrees on;
  * none when two of them name different devices. An honest application never does this: the
    replica is one per device and store.
  * So the owner of one access cannot make a replica of another access its own and sign in its
    name. The operations of that replica wait (as an unknown replica's do), and the conflict is
    kept as a reason for the diagnostics.
* `UnionMembership.mayWrite(replica, sequence)`: true when any membership allows it.
  * A member removed from the folder that is still an editor of the document's own sharing, or
    of a space around the folder, keeps writing.
  * A member removed from every access is held to the largest of its `writesUpTo`. Each one was
    set by an owner who allowed those operations.
  * The operations it made before stay on every device, as today.
* `knows(replica)`: known in any membership. An operation of a replica known in none waits, and
  the device asks for the memberships: a `MembershipQuery` on the connection's access, an
  `AccessMembership` query for the others.
* The catalogs are not checked by the union. A change of S2's catalog is checked by S2's
  membership only (`SpaceSharing.admitsChange`, with its grants). The devices relaying it to S1
  submit it there under their own signatures.

### The other memberships, anchored

Three devices make the point: Ada has the document in both accesses, Bob is in the folder only,
Carol has it directly only. Bob must check Carol's operations that Ada relays, and Carol must check
Bob's. Neither holds the other access's membership. So it has to travel, and each device must know
which one to believe: a member could make a membership of its own, sign it with its own identity,
and call itself the owner of the document's direct sharing, or of an inner folder.

The rule is the same at every level, and it needs the consent of both sides:

* **the outer space** names the owner of the inner sharing in its catalog (`ShareItem`, allowed by
  its grants);
* **the inner sharing** names the outer space (`SpaceRef` by its owner, or `Placement` by a
  member it granted).

Each side checks the other against what it has.

* **The outer space: `ShareItem(itemId, sharing, owner)`.**
  * It is a catalog change, signed, that sets a piece `sharedAs` of the item: the identifier of
    the inner sharing (the document's own, or the inner space), its owner device, and its stamp.
  * It is admitted (`SpaceSharing.admitsChange`) from the owner of the space, or from a member
    holding `share-inside` (an item already in the space getting a sharing) or `bring-shared` (an
    item that had one, brought in).
  * `UnshareItem(itemId)` clears it, from the same devices or from the owner of the inner
    sharing.
  * The anchor is `sharedAs`, not `broughtBy`: who brought an item is not who owns its own
    sharing, and it changes when the item is taken out and added back (see Starting point).
  * `sharedAs` also gives the badge of a shared folder to the members of S1 who are not in S2.
* **The inner sharing: `SpaceRef` or `Placement`.**
  * The membership of a document's own sharing, and the membership of a space, get
    `spaces: List<SpaceRef>`, signed by its owner. They are not written when empty, so the
    memberships of before stay byte for byte. Each `SpaceRef` holds:
    * the identifier of an enclosing space;
    * the device of that space's owner;
    * `writesUpTo`, once the item left that space: the last sequence of each replica of the
      space's members, as for a removed member.
  * A `Placement(itemId, SpaceRef, device, signature)` says the same, signed by a member of the
    inner sharing that holds `place-in-spaces` there. It lets a member put the item into its
    folder while the owner is offline. The owner can end it later with a `SpaceRef` holding
    `writesUpTo`.
* **A member of the inner sharing (Carol) takes the membership of a space around it when:**
  * the inner sharing names that space and its owner (a `SpaceRef` of the owner, or a
    `Placement` by a member holding the grant at that membership version);
  * it is signed by that owner, of kind `SPACE`, for that identifier;
  * it is more recent than the one Carol has.

  Carol learns the members of the space (devices, replicas, names, roles, grants), not its key
  nor its catalog.
* **A member of the outer space (Bob) takes the inner membership when:**
  * its owner is the one the `sharedAs` piece of the item names in Bob's catalog;
  * it names Bob's space (in its `spaces`, or by a `Placement` that travels with it);
  * it is more recent than the one Bob has.
* **Who may put an item with its own sharing into a space**, by the grants of both sides:
  * The owner of both (the MacBook with the library and the three documents): always.
  * A member of the space who owns the item: the space must grant it `bring-shared`.
  * An item owned by someone else: its owner must name the space (`SpaceRef`), or grant the
    member `place-in-spaces`. The space must grant `bring-shared` too.
  * PencilNotes does not offer the last case for its library (Decisions).
* **The message.** `AccessMembership(itemId, signed: SignedMembership, placement: Placement?)`
  goes on the connections of a document and of a space:
  * it is sent after authorization when the other device's view of another access is older
    (`Hello` gains `otherMemberships`: the version of each one it has, at most `maxNesting`), and
    again whenever it changes;
  * any member may relay it, because it is checked by its signatures and its anchors, not by who
    sends it;
  * it is bounded like a `MembershipMessage` (`receive(maxSize)`, `cborNestsWithin`). One not
    understood or not anchored closes the connection (`SyncException`).
* **An item leaving a space** (moved out, that space ended, its owner leaving): the owner of the
  inner sharing writes the `SpaceRef` with `writesUpTo`.
  * The inner members then take the operations of that space's other members up to there, as for
    a removed member.
  * Until the owner does so (offline), they keep taking them, as the space's membership allows.

### Ending and removing

| Action | The item's own access | The space around | What the person keeps |
|---|---|---|---|
| Remove Bob from the folder | unchanged | new epoch, Bob removed | the document, or the inner folder, if he has it another way: synced through it |
| Remove Bob from the document, or from the inner folder | new epoch, Bob removed | unchanged | the item, if he is in a space around it |
| End the document's own sharing, or the inner folder's | ended (`Membership.ended`); its members keep their copies | `UnshareItem` | the members of the spaces around go on; the others stop |
| Stop sharing the outer folder or the library, or leave it | unchanged | ended / forgotten (`SpaceSharing.forget`) | the members of the inner sharings go on |
| Move the item out of the folder | `SpaceRef.writesUpTo` written | `RemoveFromSpace`, every device keeps its copy | the members of the inner sharing go on |
| Take back a grant (`setGrants`) | the grant's `upTo` written | the changes it allowed before stay | what was shared or placed with it stays; nothing new |

A device whose last access ends keeps its copy, as today. A device that loses one access of
several keeps syncing through the others, and its library keeps the item where it is.

### The library

* `SpaceLibrary(sharedApart)` becomes `SpaceLibrary(keptApart)`: the items an application keeps
  out of the spaces, a narrowing of its own. The engine keeps out by itself what the grants of
  either side do not allow.
* `Collaboration` (PencilNotes) keeps apart the items owned by another device in its library,
  following requirement 7 of [shared-folders.md](shared-folders.md). A document shared on its own
  and owned here is taken into the catalog like any other document under the root, and so is a
  folder shared from here.
* An item that this device only had through one access, and that now arrives through another
  space: the first time, that space's place for it is written into the library. It is not
  compared as a local change, so a document waiting in "Shared" does not emit `RemoveFromSpace`
  for every other member.
* `isShared` (the badge) stays true while any access is there. For a folder it is also true when
  its `sharedAs` piece says it is the root of a space this device is not in.
* What the short-term fix discussed for `feat/iphone-library-sync` (a document whose own sharing
  names no device but its owner joins the library) is a case of this rule. The maintainer chose
  not to make it there.

### The public API, for other applications

* **What an application gets from `SyncEngine`** (`pageboard-sync`):
  * `accessOf(itemId)`: the accesses of a document or folder (its own, each space around it), the
    members of each with their roles, the role and read only of this device, the grants of each
    access (the library's and the application's);
  * `share(itemId, role, grants)`, `setGrants(sharingId, grants)`, `place(itemId, spaceId)`,
    `unshare(itemId, access)`, `leave(itemId, access)`, the removals as today;
  * what a removal would leave (`accessOf` without that access), so an interface can warn before
    it.
* **Nothing of PencilNotes in the engine.** The folder "Shared", the wording of the dialogs, which
  choices are offered, and the rule of the library on items owned by others are in
  `pageboard-sync-ui` or in PencilNotes.
* **Binary compatibility.** The types expected to grow (`Grant`, `ItemAccess`, `AccessSource`,
  the options of `share`) are not public data classes whose constructor is the way to add a
  field. Each addition to such a constructor breaks the callers compiled before. They get
  factories with defaults, or sealed interfaces (see the warning on `EditorExtension` in
  `CLAUDE.md`). They are recorded in `api/` and used by a consumer in `consumer-checks`.
* **Documented.** The `Module.md` of `pageboard-sync` and `pageboard-sync-protocol` explain the
  accesses, the union, the grants and the anchors, for applications that do not read the plans.
* **Tested without PencilNotes.** The tests of the engine use no interface. One of them is an
  application of its own: it signs a grant of its own, reads it back on another device, and
  checks that a device of before (no grant known) allows nothing by it.

### What the user sees

* **The sharing dialog of a document** (`ShareTarget.Document`) lists:
  * the people of its own sharing, with their roles, managed here as today;
  * for each space around it, innermost first: "Through the folder Physics", "Through the
    library". These are the members of that space, with their roles, read only here, with a link
    to that folder's dialog.

  Someone with several accesses is shown once, with the highest role and every source ("Editor ·
  also through Physics").
* **The sharing dialog of a shared folder** (`ShareTarget.Folder`), for its owner, has "Who can
  share what is inside": "Only me" or "Editors too" (`share-inside`). The same choice covers
  bringing in items shared on their own (`bring-shared`); PencilNotes offers the two as one choice
  (see Decisions).
* **Sharing a folder inside a shared folder or the library:**
  * It is no longer refused when this device may (the owner, or an editor granted). The dialog
    says what it means: "Physics is in your shared library: its people keep it, and the people you
    invite get only Lab".
  * When it may not, the dialog says who decides: "Only the owner of Physics can share folders
    inside it".
  * It then lists its own people and, below, the ones through the spaces around it, as for a
    document.
* **Removing someone who keeps access another way.** The dialog asks first: "Bob still has access
  through the folder Physics". From an outer folder's dialog: "Bob keeps 2 notes shared with him
  directly and the folder Lab", with their names.
* **Ending.** "Stop sharing" of a note or a folder ends its own sharing only, and says what stays
  ("It stays shared through Physics"). "Stop sharing" of an outer folder or of the library says
  which notes and folders keep a sharing of their own.
* **On a device that received the item.** It is placed where its innermost space puts it. Its
  sharing dialog shows every source in the same way. "Leave" leaves one access, the one chosen.
* **Joining an inner folder already in the library** (a device of the library joining Lab): it
  stays where it is, and the join dialog says so rather than asking where to put it.
* `Collaboration.accessOf(itemId)` gives the dialogs what they show, from `SyncEngine.accessOf`.
  The strings are in the 7 languages. The screenshots: a document with two sources, sharing a
  folder inside the library (allowed, and not allowed), the choice of the owner, the confirmation
  of a removal that leaves access.

### Protocol

What travels changes: **a protocol change.**

| What changes | Before | After |
|---|---|---|
| `Membership` | `documentId`, owner, members, removed, epoch, `keyHash`, `kind` | + `grants: List<Grant>`, + `spaces: List<SpaceRef>`, for a document and for a space (neither written when empty: the memberships of before keep their bytes and signatures) |
| Catalog changes | `AddFolder`, `AddDocument`, `RenameFolder`, `MoveItem`, `TrashItem`, `RestoreItem`, `RemoveFromSpace` | + `ShareItem`, `UnshareItem` (their content is in the format, as the others) |
| Messages | | `AccessMembership` (with its `Placement`), its query |
| `Hello` | `membershipVersion`, `epoch` | + `otherMemberships` |
| Operations relayed across accesses | never: one membership per document | an operation of a member of one access sealed again for another (the same `SealedOperation`, another key) |
| What a device takes | the operations of the one membership of the document | of the union of its memberships, the others anchored |

Not changed:

* `OpenFrame`, the tags and the derived keys (`DocumentKey.forDocument`, from each space);
* `SealedOperation` and `OperationProof`;
* the invitations (`Invitation`, `drawview://join` / `s=` links);
* the existing catalog changes;
* `DocumentHeads`.

A build of before that meets `AccessMembership` or `ShareItem` closes the connection (a message or
a change it does not know). One that receives an operation of a replica of another access waits
for it forever. So the two builds must not meet on the same number: see the decision on
`PROTOCOL_VERSION`. Every change of this plan goes into that one version.

From then on the grants let the protocol grow:

* a new capability is a new name, which the devices of before ignore and so allow nothing by;
* a new kind of anchor or message still needs a new version.

### Storage

* **`shared_documents.secret` (Room) and `sharingSecrets` (key-value):**
  * still one value per document;
  * the encoding of a `DocumentAccess` keeps a value of before readable both ways. The first
    access is written as the `SharingState` it is today: the own one when there is one, else the
    innermost space. The others go in a new field `through: List<SharingState>` of that state,
    not written when empty;
  * a value of before reads as one access (its `space` field says which). A build of before
    reading a new value sees the first access and ignores `through` (`ignoreUnknownKeys`);
  * no Room schema change (the same column), so no `Migration25To26`. If the maintainer prefers a
    table of accesses (one row per document and access), that is schema 26 with a migration
    tested by `MigrationTestHelper` on a database of schema 25 with a document shared on its own,
    one of a space and their operations (see Decisions).
* **`readOnly`** (the column and the key-value set): the union, written with every change of any
  access.
* **`spaces` (Room) and `pageboard.space.<id>` (key-value):** unchanged in shape.
  * A root may now be under another root.
  * `spaces.sharing` keeps the signed membership with its grants and `SpaceRef`s, in the bytes
    it already keeps as they came.
  * The `Placement`s received are kept with the access they anchor, in its `SharingState`.
* **`catalog_items`:** each item is JSON (a decision of [shared-folders.md](shared-folders.md),
  so that its format can grow). It gains the piece `sharedAs` (the inner sharing and its owner)
  with its stamp, not written when missing. An item of before reads without it.
* **Tests of the stored formats:**
  * the values written by the build of now (recorded as fixtures from `main`: a document shared
    on its own, a document of a space, a closed one, a catalog item), read again as they were;
  * a value with several accesses, a placement and an item with `sharedAs`, read back;
  * the same values read by the decoders of before (copies kept in the test), which give the
    first access and the item without `sharedAs`;
  * both stores (`checkSharedDocuments` and `checkSpaceStore` in `pageboard-store-testing`), with
    a space whose root is under the root of another;
  * a copy of the maintainer's PencilNotes database (MacBook), the original untouched: every
    secret and catalog item read; the three documents of Machine Learning with their own access
    and, after joining, the library's; a folder of the library shared on its own.
* Nothing is rewritten at start: a value takes the new shape only when an access, a placement or
  a piece is added to it.

## Plan

| Phase | Content | Status |
|---|---|---|
| 0. This plan | Written, the decisions below for the maintainer; folders inside shared folders included, grants chosen by the owner, the modules as a library for other applications; brought up to `main` after the fix of who gives a document (protocol 3) (2026-11-24) | done (to approve) |
| 1. Stored accesses | `DocumentAccess` and its encoding (`through`, a list), `SharedDocuments` keeping it, `readOnly` as the union; `sharedAs` in the catalog items; no change in behaviour (an item still has one access). Tests: the values of before read, the new values both ways, the decoders of before on new values, both stores, the copy of the maintainer's database | to do |
| 2. The union in the engine | `DocumentSharing` over `DocumentAccess` (`follow` per space next to the own access, `forget` per access), `TagMatch` with its access, `authorize` per access, `UnionMembership` in `onOperations`, the role, read only, `mayGive` and compaction by the union. Tests (`SharedAccessTest`, two devices in both accesses): a document in a folder and shared on its own, synced by either connection; removed from the folder, still synced directly and never given the new key of the space; removed directly, still synced through the folder; the own sharing ended; the document moved out | to do |
| 3. Grants and the other memberships | `Grant`, `Capability`, `Membership.grants` and `holds` (with `upTo`), `setGrants`; `Membership.spaces` / `SpaceRef`, `Placement`, `ShareItem` / `UnshareItem` admitted by the grants; `AccessMembership` and its query, `Hello.otherMemberships`, the anchors on both sides; `PROTOCOL_VERSION` as decided, `ProtocolFormatTest` re-recorded. Tests with three devices (Ada in both, Bob in the folder, Carol direct): Carol's operations reaching Bob through Ada and the reverse; a space reader making a membership of its own for the document, refused; a `ShareItem` from an editor without the grant, refused, and with it, taken; the grant taken back, what it allowed before kept on every device whatever the order; a `Placement` by a member without the grant, refused; an item taken out and added back by another member (its `broughtBy` changed) anchoring nothing; a replica named for two devices, its operations not taken; Bob removed from the folder, his later operations refused by Carol; a grant of an application carried and read back, and a grant unknown allowing nothing; random `AccessMembership` bytes and catalog changes to the decoder; the sync modules on the iOS simulator | to do |
| 4. Documents in the library | `keptApart` in `SpaceLibrary`, PencilNotes's rule in `Collaboration`; the catalog's place written the first time a document arrives through a space. Tests: `checkSharedFolders` with a document shared on its own in the folder (both stores), one owned by another device kept apart by PencilNotes's rule and taken with consent when the rule is not given, a document first had directly then through the library | to do |
| 5. Folders inside shared folders | `SpaceLibrary` without the refusal (up to `maxNesting`), `spacesOf`, changes submitted to every space around, `NestedPieces` (the latest stamp across catalogs), `ShareItem` submitted when a folder is shared inside, the move out of F by an inner member, `SpaceRoot.InPlace`; `SyncEngine` with the heads of the inner documents on every space. Tests: convergence with random changes through two nested spaces and three devices (one in each, one in both), no item moving back and forth; a folder of the shared library shared with a third device and synced both ways through the device in both; an editor sharing inside with and without `share-inside`; a reader of the outer space not relaying; the inner sharing ended, the outer one left, the inner folder moved out; a device of the library joining the inner folder in place; both stores | to do |
| 6. The public API | `SyncEngine.accessOf`, `share` with grants, `setGrants`, `place`, `unshare` / `leave` per access, in shapes that grow without breaking (factories, sealed interfaces); the `Module.md` of `-sync` and `-sync-protocol`; a consumer in `consumer-checks`; the ABI recorded | to do |
| 7. Interface | The sharing dialogs with the sources of access, the choice of the owner ("Who can share what is inside"), sharing a folder inside a shared folder or the library (allowed or who decides), the confirmations of removal and ending, "Leave" per access, the join in place; strings in the 7 languages; Roborazzi recorded in the Docker image; the desktop on a scratch directory | to do |
| 8. Real devices | The MacBook and the iPhone: the three documents of Machine Learning on the iPhone, a direct member removed from the library and still synced, the own sharing of one ended; a folder of the library shared with a third device (or a scratch desktop), changes made on each side and met again | to do |

Every phase ends with the checks of `CLAUDE.md` (with `./consumer-checks/check.sh` for phases 1,
2, 3, 5 and 6, which change published APIs) and the tests of the sync modules on the iOS
simulator. One commit per phase on `feat/shared-access`, the status updated with it.

## Decisions

### Taken by the maintainer (2026-11-24)

* Folders shared inside shared folders and the library are part of this plan.
* Who may share a folder inside a shared folder is the owner's choice (`pageboard.share-inside`).
* The sharing modules are published for other applications: their capabilities are not limited to
  what PencilNotes offers, and the rules of PencilNotes are its own defaults and choices.

### For the maintainer

1. **`PROTOCOL_VERSION`: 4 or stays 3.** Phases 3 and 5 change what travels (`Membership.grants`
   and `spaces`, `AccessMembership`, `Hello`, `ShareItem`), and what a device takes changes from
   phase 2. Version 3 is taken by [brought-by.md](brought-by.md) (`ConfirmGiver`).
   * **4 (proposed),** once for the whole plan: a device on protocol 3 meets a device on 4, is
     told so by `otherVersionMet`, and the two do not sync until both are updated. The ALPN of
     iroh becomes `pageboard/sync/4` (it follows the number). `ProtocolFormats` records format 4
     beside 1 to 3.
   * **Stays 3:** possible only if every device (the MacBook, the iPhone, the others) is updated
     together. A device of before then fails the connections where the new messages come.
   * The maintainer said on 2026-11-24 that devices on builds of before need not be supported (the
     applications are not in production). So nothing is lost to either choice now; 4 says it to a
     device not updated.
   * Once the modules are on Maven Central, other applications' devices are on the network too.
     Changing the format without changing the number is no longer something the maintainer can
     settle by updating their own devices.
2. **The defaults.**
   * The library: a new sharing has no grants (only the owner), unless the application gives
     some (proposed).
   * PencilNotes: "Only me" in the dialog of a new shared folder (proposed), "Editors too" one tap
     away.
3. **PencilNotes's one choice or two.** It offers `share-inside` and `bring-shared` together as
   "Who can share what is inside" (proposed), or as two choices. The library keeps them apart in
   any case: one gives the space's content to new people, the other brings other people's
   writing in.
4. **Items owned by others in PencilNotes's shared library: still kept apart (proposed),** as
   decided on 2026-11-14, now by PencilNotes's `keptApart` rather than by the library. The library
   allows them with the consent of both owners (`SpaceRef` or `Placement`, and `bring-shared`).
   Revisit for PencilNotes?
5. **Sharing a document of a space on its own without `share-inside`: refused (proposed).** The
   application may offer to move it out first, as today. The alternative, sharing it and taking
   it out of the space silently, is what happened to the three documents.
6. **The role: the highest of the accesses (proposed).** An item cannot be limited below what the
   spaces around it give (Drive's "limited access" folders). Left for later, and it is a
   restriction, so it needs a new protocol version when it comes (grants only allow).
7. **Who sees whom.** To check the authors, the members of each access learn the members of the
   others (names, devices, roles, grants), never their keys nor the catalogs. This is what makes
   the union work. It is also what Drive shows ("People with access"). Accept, or the dialog of
   the other side shows only "and 3 through a folder"? The devices still need the full
   memberships in either case.
8. **The value of a piece held by nested catalogs: the latest stamp across them (proposed).**
   The alternative is the innermost space always winning, which is simpler. But then a rename
   made by a member of the outer space only would be undone by the next device in both.
9. **An item moved out of an inner folder by one of its members only: it goes to the folder's
   parent in the outer space (proposed),** as an item leaving a space does today. The alternative
   is to leave it where it was for the outer space, but then the two spaces disagree on where it
   is.
10. **Storage: one value with `through` (proposed) or a table of accesses.**
    * One value: no Room migration, and a build of before still reads the first access.
    * A table (schema 26): one row per access, queried directly, with a `MigrationTestHelper`
      migration.
11. **A replica named for two devices: its operations wait (proposed),** and the conflict shows
    in the diagnostics. Closing the connection would let the owner of one access cut a device off
    from the others.
12. **`maxNesting`: a parameter of the engine, 8 by default (proposed).** Deeper chains are
    refused when made here and when read from others. A shared library holding shared folders
    holding shared folders is 3.
13. **The grants of applications: signed and carried by the engine, enforced by the application
    (proposed),** or only the library's capabilities for now. Carrying them costs one field and
    saves every application a fork of the protocol.

### Taken by me (revertible)

* The accesses of a document as a list of spaces next to its own sharing, rather than two fields:
  with nested folders a document may be in several spaces.
* Each access stored apart, the union computed when read: removing or ending one never touches
  the others.
* A connection authorized by its own access only, the union used for the operations: otherwise a
  member removed from the folder would get its new key through another access.
* Policies as grants that only allow, never as restrictions: a device that does not know a grant
  fails closed, and `ignoreUnknownKeys` cannot turn a prohibition into a permission.
* A grant taken back keeps what it allowed by the sequences of each replica (`upTo`), as a role
  does: every device admits the same catalog changes, whatever the order of the memberships.
* Grants given to roles and to devices named one by one: what the owner of a library of its own
  devices and the teacher of a course both need.
* Three capabilities, not one: sharing out, bringing in and placing are different trust given to
  different people. An application may offer them together.
* The anchor of an inner sharing in the outer catalog is a piece of its own (`sharedAs`, set by
  `ShareItem` under the grants), not `broughtBy`: who brought an item is not who owns its own
  sharing, and it changes when the item is taken out and added back.
* The catalogs of nested spaces kept apart, each complete for its own members, rather than an
  inner space delegating its items to the outer one: a member of one space needs no catalog of the
  other.
* The library as the meeting point of nested catalogs: the comparison of today, run for every
  space, relays the changes. No new path between the catalogs is needed.
* A relayed catalog change signed by the device relaying it, in the space it submits to, rather
  than carrying the original author's proof: the catalogs keep their own membership checks, and a
  member of one space cannot write into another.
* `writesUpTo` for an item leaving a space, in its `SpaceRef`: the same rule as a removed member.
* The operations sealed for the relay under each tag a device has, rather than a per-document key
  for the relay: no new key to distribute. To be confirmed in the relay's plan.
* Phases 1 and 2 before the protocol change (phase 3), so that the union is tested locally first.
  Until phase 3, a device in one access only cannot check the operations of a member of another
  access (Bob, in the folder only, and Carol's operations): they wait, as an unknown replica's do
  today, and nothing unchecked is taken. Phase 2 alone does not ship to the devices.
* The documents (phase 4) before the nested folders (phase 5): they unblock the three documents
  of the iPhone, and the nested folders build on the same union.
* The public API (phase 6) after the engine and before the interface: the interface of PencilNotes
  is its first consumer, so what it needs is in the API, and nothing reaches it another way.
* The first place of an item arriving through a new space written, rather than compared: a
  document waiting in "Shared" must not take itself out of the space for everyone.
* Joining an inner space in place when its root is already here, rather than into a new folder:
  the same identifiers would otherwise be in two places.

## Left for later

* Owner decisions delegated by signature: an editor inviting, admitting or removing (Drive's
  "editors can change permissions"). The membership is signed by the owner only, so this needs a
  chain of signatures. The grants are shaped to carry it (`pageboard.invite`, …) once it exists.
* Roles beyond owner, editor and reader (a commenter): until then, an application's own grant.
* An item limited below the role its spaces give (a restriction: a new protocol version).
* The store-and-forward relay holding several tags for one document (its own plan).
* A replica bound to its device by construction (derived from the device's key), which would make
  `deviceOf` conflicts impossible: a change of every replica and of what travels.
* Telling a member why an operation or a catalog change waits (a replica in conflict, a membership
  not anchored, a grant missing, a reader that cannot relay) in the interface.
* A member of an inner space told that parts of the outer space disagree with it, when no device
  in both relays their changes (a reader of the outer space only).
