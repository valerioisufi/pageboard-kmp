# The sync engine split, the resources received on disk

Phase 6 of `docs/architecture.md` (designs C and D). This plan does its first part, the downloads
of the resources, and sets out the rest. Nothing that travels changes: the messages, their order
and their bounds are the same (`PROTOCOL_VERSION` 1, `ProtocolFormatTest` untouched).

The responsibilities are assigned following the GRASP principles.

## Starting point

* `SyncEngine` (`drawview-sync`, 1554 lines) holds the connections and their lifecycle, the
  handshake, the membership, the operations and the known versions, the resources, the items
  beside the documents, the addresses and the presence.
* A resource received is kept in memory until its last byte (`Download.bytes`, up to 512 MiB, the
  largest file allowed), then hashed and written. An item beside a document (an attachment) is
  written as it comes already.
* Every part of the engine works on `Link`, an `inner class` of the engine that reaches its lock,
  its documents and its other parts: a part cannot move to a file of its own before `Link` does.

## Requirements

1. A resource received never takes more memory than its part being written.
2. It is kept only whole, with the hash its operation names; an interrupted download goes on from
   where it stopped, over another connection too (as today).
3. Nothing that travels changes; the tests of the sync modules pass on the JVM and on the iOS
   simulator.

## Design

* **Information Expert / Pure Fabrication.** `ResourceDownloads` (internal) owns the downloads of
  the files, by document and resource, with their own lock: the part written, the bytes received,
  the hash computed as they come (`Sha256.update`). `SyncEngine` asks it where to ask from and gives
  it each part; it answers when the file is whole, kept or refused.
* **Protected Variations.** `ResourceFiles` gets `receive(extension): ResourceDownload?`: a file
  written as its bytes come (`append`, `finish`, `discard`). On the device it is a `.part` file next
  to the resources, renamed when whole (`kotlinx-io`); by default (the implementations of the tests)
  the bytes are kept in memory, then written, as before.

## Phases

| Step | Content | Status |
|------|---------|--------|
| 1 | `ResourceDownloads` and `ResourceFiles.receive`: the resources received written as they come, hashed, renamed when whole | done |
| 2 | `Link` out of the engine (its state and its sending), with the lock it needs | later |
| 3 | `Handshake`, `DocumentReplication`, `AttachmentTransfer`, `PresenceRelay`, `AddressExchange` over it | later |

## Decisions

Taken alone, revertible:

* **The downloads first**: they change what the device does (the memory), the rest of the split
  changes only where the code is, on the engine that syncs the notes of the user's devices; it
  waits for `Link` to be taken out on its own, with the tests of today.
* **The part files are not kept across a restart of the application**: a download interrupted then
  starts again from the start, as it did in memory. A `.part` file left by an application stopped
  meanwhile stays in the resources until a cleanup of the resources removes it.

## Left for later

* Steps 2 and 3.
* The device sending a resource reads its whole file to send it in parts (`onResourceRequest`): it
  could read the part asked only.
* The `.part` files left by an application stopped during a download.
