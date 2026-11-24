# The crashes and the lag of the iPhone

PencilNotes crashes on the iPhone in landscape when a menu or the settings of a tool open, crashes
now and then while texts are laid out, and lags on a document of a library shared with other
devices. The causes come from the crash logs of the iPhone (7 between November 14 and 23) and from
the simulator. Nothing stored and nothing that travels between the devices changes.

The responsibilities are assigned following the GRASP principles.

## Starting point

* **Landscape.** The menus of the design (`DesignMenu`, the "more" menu of the editor) and the
  settings of the tools (`ToolPopover`) are `DropdownMenu`s of Material: a `Column` with
  `width(IntrinsicSize.Max).verticalScroll(...)` in a popup. On the iPhone in landscape, a menu
  taller than the window is measured with an infinite height, and `verticalScroll` stops the
  application ("Vertically scrollable component was measured with an infinity maximum height").
  Reproduced on the simulator with the "more" menu of the editor; a short menu (the one of the
  library) opens.
* **Texts.** `ComposeTextTileAdapter` lays the texts out with a `TextMeasurer` under its own lock,
  off the main thread (the text boxes of `TextBoxes.adapterChanged` on `Dispatchers.Default`, the
  tiles on their workers). On iOS the text engine of Compose keeps the styles of Skia in one global
  `WeakKeysCache`, a `HashMap` that is not safe between threads and that every lookup sweeps: the
  main thread laying out the text of the interface at the same time throws a
  `ConcurrentModificationException` (`ParagraphBuilder.makeSkTextStyle`), which ends the application.
* **The heads of a shared library.** With a folder or the library shared (a space), every change
  of a document (`store.documents`, after 2 s of quiet) makes `SyncEngine.tellHeads` hash the
  version vector of **every** document of the space, two queries each, and `recomputeWanted` does it
  again right after. A library of N documents costs about 4N queries after every pause of the pen,
  on the database the editor saves into.
* **The presence.** In a shared document `CollaboratorsLayer` sends the presence up to 30 times a
  second, on the main thread: `toPresence` copies the whole stroke being drawn into a list of boxed
  floats every time (the engine sends only the inputs not sent yet). Each one also makes
  `markWorkedOn` start a coroutine that waits 30 s: about a thousand of them while writing. The
  gestures copy the whole stroke into the activity at every input, shared or not.

* **The download of a shared library (measured).** A simulator joining the real library of the
  user, sampled every few seconds: the application took up to 3 cores of a Mac. Most of the work of
  the synchronization is decoding the chunks of the files (`ResourceChunk`, `AttachmentPart`):
  their `bytes` have no `@ByteString`, so every byte of a PDF, an image or the audio of a lecture
  travels as a CBOR integer of its own, decoded one at a time. The search indexer of PencilNotes
  runs on the main thread (`PencilNotesServices` starts it in its `Dispatchers.Main` scope), and
  runs again at every change of the library: the documents arriving one by one. The download then
  stopped the application: the index of the handwriting had the table of a build made while the
  feature was written (the same format, other columns), `no such column: reader`, and the failure
  of the indexer was not caught.

## Requirements

1. No menu, popover or sheet stops the application in landscape: it is as tall as the window lets
   it, and scrolls.
2. The texts are laid out on iOS without racing the text of the interface: no crash, and the main
   thread never waits for a worker.
3. The heads of a space cost a few queries, whatever the number of its documents, and are computed
   once per change.
4. The presence costs on the main thread what the stroke gained since the last one, and one timer
   per document worked on.
5. Nothing changes in what is stored: no migration. What travels changes in phase 6 only.
6. The search indexes off the main thread, and a document it cannot read never stops the
   application.
7. The bytes of a file and of an operation travel as they are (protocol 2, agreed with the
   maintainer on 2026-11-24): no stored data changes, and what is signed stays the same.

## Design

* **Protected Variations (the menus).** One modifier of `pageboard-ui`, `menuHeightLimit()`, bounds
  the height of a popup menu to the window (`LocalWindowInfo.containerSize`) less its margins;
  `DesignMenu` and `ToolPopover` give it to `DropdownMenu`, so the `verticalScroll` of Material is
  always measured with a bounded height, whatever the popup of the platform gives it. The dialog of
  `AdaptiveSheet` takes it too: in landscape it was as tall as its content and cut by the window
  ("New note" could not reach its button), now its content scrolls.
* **Pure Fabrication (the thread of the texts).** `onTextLayoutThread { }`, `expect` in
  `pageboard-ui`: on iOS it runs its block on the main thread (directly there, else with
  `dispatch_sync` on the main queue), elsewhere in place. `ComposeTextTileAdapter` runs each of its
  public calls in it, **before** taking its lock: a worker waiting for the main thread holds no lock,
  and the main thread never waits for a worker, so it cannot deadlock. Each call is one text: the
  main thread draws frames between them.
* **Information Expert (the version vectors).** `OperationLog.versionVectors(documentIds)` gives the
  vectors of many documents at once, by default one by one; the Room store reads them in two queries
  (by chunks of the limit of SQLite), without a change of the schema.
* **Information Expert (the heads).** `SyncEngine.headsOf` reads the documents of the store and the
  trash once, then the vectors of the space at once; `tellHeads` gives the heads it computed to
  `recomputeWanted` instead of computing them again.
* **Controller (worked on).** `markWorkedOn` keeps when each document was last worked on; one job per
  document waits until it is left alone for 30 s, instead of one job per presence.
* **Information Expert (the presence).** `CollaboratorsLayer` keeps the values of the stroke being
  drawn and adds only the inputs that came since the last presence. `CollaboratorPresence` knows
  whether someone follows its activity: the gestures copy the inputs of the stroke into it, at every
  input, only then (a document not shared no longer pays it either).

* **Pure Fabrication (the indexer).** `ContentSearch.start` runs the indexer on
  `Dispatchers.Default`, whatever the scope it is given (its queries stay in that scope).
  `SearchIndexer` skips a document a source fails on (read again at its next change).
  `SearchIndex` makes the table of the handwriting again when its columns are not the ones it
  writes: the readings are a cache of the strokes, read again.

* **Protected Variations (protocol 2).** `@ByteString` on the bytes that travel only:
  `ResourceChunk.bytes`, `AttachmentPart.bytes` (the files), `SealedOperation.content` and
  `.signature` (every operation, sealed). Not `alwaysUseByteString`: the same `Cbor` encodes the
  operations the proofs sign (`SyncCodec.encodeOperation`), and the proofs kept would no longer
  match; what is stored (the memberships, the addresses) is JSON, which the annotation leaves alone.
  `PROTOCOL_VERSION` goes to 2, and with it the associated data of the channels and of the sealed
  operations and the ALPN of iroh: a device of version 1 is refused at the first frame and told
  (`otherVersionMet`) on the local network, and does not meet the others over iroh.
  `ProtocolFormatTest` describes the byte strings (`ByteString`), and records format 2.

## Phases

| Step | Content | Status |
|------|---------|--------|
| 1 | The menus, the popovers and the dialogs bounded to the window (no crash in landscape, the dialogs scroll) | done |
| 2 | The texts laid out on the main thread on iOS | done |
| 3 | The heads of a space: the vectors at once, computed once per change; one timer per document worked on | done |
| 4 | The presence built from what the stroke gained, the stroke copied only for a document followed | done |
| 5 | The search indexed off the main thread; a document it cannot read and an older table of the handwriting never stop the application | done |
| 6 | Protocol 2: the files and the operations as CBOR byte strings | done |

## Decisions

Taken alone, revertible:

* **The main thread lays the texts out on iOS**, rather than a copy of the text engine apart: the
  cache of Compose is internal and global. The tiles of texts cost the main thread their layout and
  drawing, one text at a time.
* **The desktop keeps its workers.** Its cache is a `WeakHashMap`, not safe between threads either,
  but no crash of it is known; see "Left for later".
* **`versionVectors` is a new member with a default**, so the stores of others keep building
  (`checkKotlinAbi` records it).
* **A document worked on stays wanted between 30 and 60 s** after its last presence, where it was
  30 s exactly: the one job of the document looks every 30 s whether a presence came meanwhile.
  It only keeps a connection open a little longer.

* **The table of the handwriting is made again** when its columns differ, rather than migrated:
  it only holds what the readers read from the strokes, and the strokes are kept.

* **No compatibility with protocol 1** (the decision of the maintainer, 2026-11-24): the
  applications are on the devices of the maintainer, updated together.
* **The small bytes of the handshake stay arrays** (`Challenge.nonce`, `Hello.proof`): a few
  dozen bytes, not worth a wider change.

## Left for later

* The desktop (JVM) races the same cache of Compose (`WeakHashMap`): `onTextLayoutThread` on the
  thread of Swing, once a crash or a corruption shows it.
* The tiles of texts drawn off the main thread on iOS again, when Compose makes its cache safe
  between threads (to report to JetBrains).
* Why the popup of Compose gives an infinite height in landscape on iOS (to report to JetBrains with
  a reproduction).
* The list of the documents (`RoomDocumentStore.documents`) mapped again on the main thread at
  every document arriving, the advertisement of Bonjour and the duration of a lecture whose audio
  arrives (`Lecture.audioReceived`, reading the new pages of the file) on the main thread: small in
  the samples.
* Instruments could not record on the iPhone (the device "disconnected" at every start, over the
  cable too): the measures are from the simulator.
