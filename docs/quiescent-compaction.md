# Compaction while connected

The members of a shared document that stay connected compact their logs and drop for good what
was removed once every member has everything, without waiting to connect again.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

Asked by the user on 2026-10-24, from phase 5 of [untrusted-members.md](untrusted-members.md):
the check added before the operations are applied changed the order of the steps of
`devicesChangingADocumentThroughEachOtherConverge`, and the devices ended with different hidden
content (removed pages and elements). The test now compares the document shown
(`assertSameShown`). It starts from `feat/bounded-stamps`, on the branch
`feat/quiescent-compaction`.

How the log is compacted today (`docs/collaboration.md`, "After phase 6: compaction"):

* Each device says what it has (`KnownVersions`, its version vector) to the device it connects
  to, and a device takes what a member says of itself only (phase 2 of untrusted-members.md).
* `SyncEngine.compact` removes from the log the operations every member has (the smallest of
  their vectors). When every member has all that every other one made (`quiescent`), nothing made
  without seeing a removal is on its way: the pages and elements removed until then are deleted
  for good (`OpenDocument.compact(purge)`, `DrawDocument.purgeHidden`).
* `KnownVersions` is sent once, when a connection starts (`catchUp`), with the vector of that
  moment: before the operations the two devices exchange then. `theOperationsEveryMemberHasLeaveTheLog`
  says so ("it travels with the connections") and connects Bob again to make Ada compact.

So the members that stay connected never compact their logs and keep every removed element and
page, until they connect again (a restart of the application, a network lost and found).
`theMembersStayingConnectedCompactTheirLogs` (`SyncEngineTest`) fails today: three members
connected to each other, every one with every operation, and the logs not compacted at all.

What it is not: the early return of `SyncEngine.compact` (when the base does not move on) does not
keep a purge from happening. A compaction without purge leaves a member with operations another
one misses; once every one has them, the base moves on for that member, and the next compaction
purges.

## Requirements

1. A member that met every other one since they all had everything compacts its log and drops
   what was removed until then, the connections staying as they are.
2. Nothing removed is dropped for good while a member may still send something made without
   seeing the removal: the rule of `SyncEngine.compact` stays as it is, and what a member has is
   still said by that member only.
3. A member not connected to every other one keeps more, never less: what it was told at its last
   connections.
4. The devices of the user sync as before: nothing that travels changes (protocol 1), the builds
   of before take the messages of this one.
5. The notes of the user are kept: what is dropped for good is what nothing can show again, and a
   removal undone after the purge puts the content back on every device.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| What this device has of a document changed: when to say it again | `DocumentSync` (`drawview-sync`), with its observer of the changes | Information Expert |
| Saying it again on each connection caught up, once the changes stop for a moment, and only when it changed since it was said there | `SyncEngine` (a job of each `DocumentSync`), `Link.toldVersions` | Controller |
| Taking what a member says of itself, compacting | `SyncEngine.onKnownVersions`, `compact` (unchanged) | Protected Variations |

### Told again while connected

* The observer of `DocumentSync` (it sees every operation applied: of this device, received,
  merged) marks the vector as changed and starts again a delay of `QUIET_MS` (2 seconds).
* When the delay passes without another change, the engine sends `KnownVersions` (the vector of
  this device only, as at the start of a connection) on every connection of the document that is
  caught up and was told another vector (`Link.toldVersions`, set by `catchUp` too).
* The delay makes it one message per connection after a burst of changes (strokes being drawn,
  a batch received), not one per operation; a document nobody changes sends nothing.
* The device receiving it records it and compacts, as today. When every member has everything,
  each one compacts at the next message it receives from the others: within `QUIET_MS` of the
  last operation, on every device connected to every other one.

### What is dropped, and the notes of the user

* The rule does not change: a removal is dropped for good once every member said it has every
  operation of every other one, each member saying it of itself. Only when it is applied changes:
  while connected, instead of at the next connection.
* What is dropped: the pages and elements hidden by a removal (`DrawDocument.purgeHidden`), never
  shown again by a change of any member. A removal undone afterwards (the history keeps the content
  in the change undoing it) adds the content again, as new, on every device: tested.
* A member that did not connect to every other one since keeps what it has, as today
  (requirement 3).

### Protocol (unchanged)

`KnownVersions` exists already, and the builds of before take it at any time
(`onKnownVersions` records what the sender says of itself and compacts). `ProtocolFormatTest`
stays as it is and `PROTOCOL_VERSION` stays 1.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1. Told again while connected | `DocumentSync` marking the changes, the job sending `KnownVersions` after `QUIET_MS` on the connections caught up told another vector, `Link.toldVersions`; `docs/collaboration.md`. Tests (`SyncEngineTest`): `theMembersStayingConnectedCompactTheirLogs`; nothing sent when nothing changed, one message after a burst; a member connected to the owner only keeps what was removed; a removal undone after the purge, back on every device; `theOperationsEveryMemberHasLeaveTheLog` without connecting Bob again; the relay test ending with every device connected to every other one, compared whole (`assertSame`); the iOS simulator | approved |

The phase ends with the checks of `CLAUDE.md` and the tests of the sync modules on the iOS
simulator.

## Decisions

### Taken by the user (2026-10-24)

* The plan as it is, with its choices.
* `QUIET_MS`: 2 seconds. Less would compact sooner and send more; more the other way round.
* What was removed dropped for good while connected, as soon as every member has everything,
  rather than at the next connection: the same rule, sooner.
* `PROTOCOL_VERSION` stays 1: nothing that travels changes.

### Taken by me (revertible)

* A plan of its own rather than a phase of untrusted-members.md: it is about when the log is
  compacted, not about what a member may do.
* Said again after the changes stop for a moment, rather than with every operation or with the
  pings: one message per burst; the pings carry nothing, and giving them the vector would change
  the format.
* The vector of this device only, as at the start of a connection: what a member says of the
  others is not taken (phase 2).

## Left for later

* A member connected to some of the others only never learns what the rest has: it keeps their
  removed content until it meets them. Taking it through another member needs what each member
  has signed by that member (a change of what travels).
* A member may send `KnownVersions` as often as it likes, each one making the others read their
  logs to compact: bounding it with the limits of what a member sends.
