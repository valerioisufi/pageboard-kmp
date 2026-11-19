# The store and the sessions as instances

Phase 3 of `docs/plans/architecture.md`: the libraries stop holding the store and the editing sessions
in global objects; the applications create them and pass them. This document starts from the code
as it is (main at 233d1083, 2026-11-14). `LatexEngine`, `LectureRecordings` and `ImageCache` are
phase 4, with a plan of their own.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **`DrawViewStore`** (`drawview-store-room`, an `object`): `default`, the `RoomDocumentStore` of
   the database of the platform, and `documents`, its `OpenDocuments`. The database is
   `DrawViewDatabase.instance` (internal, lazy): "Room must not open the same file more than once".
   Its file is chosen by `getDatabaseBuilder()` per platform: `drawview.db` of the context on
   Android, `database.db` in the data directory on the desktop, the documents directory on iOS.
2. **Who reads it**: PencilNotes (`PencilNotesApp` twice, `PencilNotesAgents` twice) and the sample
   (`App.kt` twice). The KDoc of `DocumentLibrary` names it.
3. **`RoomDocumentStore(database, strokeModeller = InkStrokeModeller)`** and its repository: the
   default is why `drawview-store-room` depends on `drawview-ink` (its native libraries included).
4. **`DrawViewSessions`** (`drawview-session`, an `object`): the open `EditorSession`s and those
   being closed, on a scope of its own. `DocumentEditorViewModel` registers its session and closes
   it there. Read by:
   * the desktop apps (`saveWriting`, `flushAll` at exit, in both apps);
   * `SessionAgentEditors` (`drawview-agent-ui`: the editor of a document, to show a proposal);
   * the sample's ink latency.
5. **`DocumentEditorViewModel(documents, engines, …)`** is built by `DocumentLibrary`
   (`drawview-explorer`), the sample, PencilNotes (`PencilNotesDocumentWindow`) and six tests.
6. **The applications have several windows** (desktop, Android and iPadOS multi-window) sharing the
   store and the sessions; an Android activity is created again on a rotation.

## Requirements

1. No global mutable object in `drawview-store-room` and `drawview-session` (the rules of the
   libraries).
2. **The user's notes open as today**: the same database file on every platform, opened once per
   process, with its migrations. Nothing stored changes.
3. One store and one set of sessions per process, shared by the windows and kept across the
   activities created again.
4. `drawview-store-room` builds without `drawview-ink`: the application gives the stroke modeller.
5. The desktop apps save the texts being written and wait for the writes before exiting, as today.
6. Nothing sent between devices changes.

## Design

* **Creator.** The application creates, once per process, its data: the database, the store with
  its stroke modeller, the open documents, the sessions. PencilNotes and the sample each get a
  holder of their own in their shared module (`PencilNotesData`, `SampleData`): an object of the
  application, not of a library, which is where a process-wide instance belongs (Android keeps it
  across the activities, the desktop across the windows).
* **Information Expert.** `drawview-store-room` keeps the knowledge of where the database lives:
  `createDrawDatabase()`, already public, opens the file of the platform (the same
  `getDatabaseBuilder()` as today) with its migrations. It only opens: holding it once is the
  caller's job, said in its KDoc.
* **Low Coupling.** `RoomDocumentStore(database, strokeModeller)` takes its modeller without a
  default, as `KeyValueDocumentStore` does; the applications pass `InkStrokeModeller`.
  `drawview-ink` leaves the main dependencies of `drawview-store-room` (its tests keep it).
* **Pure Fabrication / Controller.** `DrawViewSessions` becomes a class, `EditorSessions`, with the
  same members (`find`, `saveWriting`, `flushAll`, register and close internal) and a scope given
  to it. `DocumentEditorViewModel` takes it as a parameter; `DocumentLibrary`, `SessionAgentEditors`
  and the windows of the apps receive the one of their application.
* **Protected Variations.** The behaviour stays: the same file, the same migrations, the same
  closing of a session (flush, dispose, close the document) on the scope of the sessions.

## Phases

| Phase | Content | Status |
|-------|---------|--------|
| 1 | The store: `createDrawDatabase()` opened by the applications, `RoomDocumentStore` without a default modeller, `DrawViewStore` and `DrawViewDatabase` removed, `PencilNotesData` and `SampleData` holding database, store and documents; `store-room` without `ink` | done |
| 2 | The sessions: `EditorSessions` instead of `DrawViewSessions`, given to `DocumentEditorViewModel`, `DocumentLibrary`, `SessionAgentEditors`, the windows and the exits of the desktop apps | done |

Each phase is one commit on `feat/instances`, after the checks of `CLAUDE.md` (the migrations of
`drawview-store-room` among them), `consumer-checks`, and both desktop apps run on a scratch data
directory; the database file is checked to be the same path as today.

## Decisions

Taken alone, revertible:

* **The holders are objects of the applications**: one per process is what the database needs, and
  the rule of the libraries is about their APIs. A dependency-injection framework is not brought in.
* **`DocumentEditorViewModel` takes its sessions without a default**: a default instance per view
  model would let two windows open the same document twice without knowing.
* **The names**: `EditorSessions` (what it is: the editing sessions of an application),
  `createDrawDatabase()` kept as it is (it already opens the database where the platform keeps it).

## Left for later

* `LatexEngine`, `LectureRecordings`, `ImageCache.Shared` as instances: phase 4 of
  `docs/plans/architecture.md`.
* `DrawViewAndroid`, the Android context of the platform module: kept (see the decisions of
  `docs/plans/architecture.md`).
