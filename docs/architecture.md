# The architecture after the cleanup

The cleanup (`docs/cleanup.md`, in main at 62f75094, 2026-11-14) removed the dead and duplicated
code and left aside the work that needs a design of its own. This document orders that work: for
each piece, where it starts, what it must give, the design by the GRASP principles and its phases.
Each piece gets its own detailed plan (`docs/<piece>.md`) when it starts, approved before its code;
this one fixes the order, the scope and the decisions shared by all of them.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The editor is extended by a class of lambdas.** `EditorExtension` (`drawview-ui`) holds six
   lambdas (toolbar actions, canvas overlay, commands, panels, read-only note, page grid actions),
   joined by `plus`. Each new hook is a new constructor parameter: a binary break once published.
   The suggestions while writing need one more (the text box, `TextBoxEditor`), and the first
   release of the libraries has not happened yet.
2. **An extension receives the whole controller.** `EditorContext(controller: DrawViewController)`:
   every manager of the editor is reachable. What the six extensions use of it, counted in the
   code:
   * the document: its id, title, pages (export, shapes, agent, collaboration);
   * the pages: the selection, `jumpToPage`, the page most visible (export, shapes, agent);
   * changes: `customElementManager.add` (shapes), `placementManager.start` (agent);
   * observing the session: `session.addObserver`/`removeObserver` (agent);
   * the presence of the others (`presence.editsRefused`, collaboration);
   * the ink latency (the sample).
3. **Global objects stand where instances should be passed** (the rules of the libraries): 
   `DrawViewStore` and `DrawViewSessions` (8 files, both apps and three libraries), `LatexEngine`
   (7 files), `LectureRecordings`, `ImageCache.Shared`, `DrawViewAndroid` (the Android context, 11
   files). Because of `DrawViewStore.default`, `drawview-store-room` depends on `drawview-ink`.
4. **Seven files carry several responsibilities each**: `SyncEngine` (1554 lines: connections,
   handshake, membership, operations, resources, attachments, addresses, presence),
   `DocumentTools` (1550: registry, sixteen handlers, schemas, DTOs), `LecturePanel`,
   `LectureAssistant` (1142), `KeyValueDocumentStore` (881), `EditorToolbar`,
   `FileExplorerViewModel` (792).
5. **The lecture assistant chooses its model by provider** in three places
   (`LectureAssistant`, `LectureRecorder`, `HandwritingReader`: `when (task.provider)`), and the
   search asks Gemini directly through `LectureAssistant.answerWithGemini`. Phase 4 of
   `docs/local-models.md` (the assistant on the device) is not started, by choice: the live notes
   stay on Gemini (`docs/local-notes.md`).
6. **A resource received by sync is kept in memory** until its last byte (`Download.bytes`, up to
   512 MiB), while an attachment is written to disk as it comes.
7. **`drawview-export` depends on `drawview-latex` and `drawview-pdf`** to draw the formulas as
   vectors in the PDF (`LatexEngine.drawing`): `FormulaRenderer` gives bitmaps only.
8. **The secondary window of PencilNotes** (`PencilNotesDocumentWindow`, desktop) has no lecture
   assistant, no collaboration, no search topics.
9. **Smaller ones**: the page backgrounds drawn three times (renderer, page grid, template picker,
   with different constants); `codePointAt` and `SwitchRow` copied across modules;
   `drawview-agent-ui`, `drawview-search-ui` and `drawview-lecture-ui` translated in English (and
   Italian) only.
10. **To agree with the user**: `LanSync` and `IrohSync` stop and choose between two connections
    differently (protocol behaviour); `SecureStore` is in clear on the JVM (a stored format);
    `windows-aarch64` is not packaged.

## Requirements

1. The extension API can grow (new hooks) without breaking the binaries built on it, and is fixed
   before the first release.
2. An extension reaches only what an extension needs, through a stable surface; the managers of the
   editor become internal.
3. No global mutable state in the APIs of the libraries: the applications create the store, the
   engines, the sessions, and pass them.
4. Each file of the seven has one responsibility per class, with the same behaviour, proved by the
   tests of today (no test rewritten to pass).
5. Nothing stored by the user changes without a tested migration; nothing that travels changes
   without the user's agreement and a re-recorded `ProtocolFormatTest`.
6. Every phase keeps the checks of `CLAUDE.md` green, and records each change of the ABI on purpose.

## Design

### A. The editor ports (requirements 1 and 2)

* **Protected Variations.** `EditorExtension` becomes an `abstract class` whose members are all
  `open` with a default (`toolbarActions`, `canvasOverlay`, `commands`, `panels`, `readOnlyNote`,
  `pageGridActions`, and the new `textBox` hook of the suggestions): a new open member does not
  break the subclasses already compiled. A builder function
  (`editorExtension { … }`) keeps the short form of today; `plus` becomes an extension that joins
  two of them (Pure Fabrication: `CombinedExtension`).
* **Indirection.** `EditorContext` stops exposing `controller`: it becomes an interface of narrow
  ports, by what the extensions use today:
  * `document`: id, title, pages (read), observed;
  * `pages`: the selection, the page most visible, `jumpTo(page)`;
  * `edits`: add custom elements, start a placement, observe the changes of the session;
  * `presence`: the edits refused, the collaborators.
  The controller implements it (Controller), and nothing else of it is public to the extensions.
* **Information Expert / Low Coupling.** Each port is answered by the manager that holds the
  information (`PageManager`, `PlacementManager`, the session); the managers' constructors become
  internal, as `docs/modularization.md` planned when it chose no `EditorHost`.
* The ink latency of the sample is a tool of the sample: it reads it from its own controller, not
  through the context.

### B. The instances passed in (requirement 3)

* **Creator.** The application builds the store, the sessions, the engines once at its entry point
  (`main`, `MainActivity`, the iOS `ComposeView`) and gives them down: `DrawViewStore` becomes a
  function that builds a `RoomDocumentStore` for a data directory, `DrawViewSessions` an instance
  of the application (`EditorSessions`) passed to the windows and to the agent editors.
* **Indirection.** A `CompositionLocal` carries them in the UI only where a parameter would cross
  many layers (as `LocalDrawViewEngines` does today), never as a default value of a library.
* **Low Coupling.** With the store built by the application, the stroke modeller is given by it:
  `drawview-store-room` no longer depends on `drawview-ink`.
* `LatexEngine` becomes an engine instance (`LatexEngine(resources)`), given to `DrawViewEngines`
  as the PDF engine is; `LectureRecordings` and `ImageCache.Shared` go into the instances that own
  them (the lecture assistant, the engines). `DrawViewAndroid` stays the one Android `Context`
  holder of the platform module, initialized by the application, but read through the instances
  that need it.

### C. The monoliths (requirement 4)

* **High Cohesion / Pure Fabrication**, file by file, without changing behaviour:
  * `SyncEngine` → `SyncEngine` (the coordinator: links and their lifecycle, Controller),
    `Handshake`, `DocumentReplication` (operations, known versions), `ResourceTransfer` and
    `AttachmentTransfer`, `PresenceRelay`, `AddressExchange`. Each owns its state and its lock.
  * `DocumentTools` → a registry, one handler class per domain (documents, pages, elements,
    library, trash, shapes), the JSON schemas and the DTOs in files of their own.
  * `KeyValueDocumentStore` → the catalog, the operation log, the persistence, the preferences,
    each over the same `KeyValueStorage`.
  * `LectureAssistant` → the lectures (state), the requests (scheduling), the models; 
    `LecturePanel` and `EditorToolbar` split by panel and by group of tools;
    `FileExplorerViewModel` → navigation, selection, operations.
* **Protected Variations.** The public API of each module stays as it is in this step (the split is
  internal), except where A or B already changed it.

### D. Behaviour and engines

* **Polymorphism.** The lecture models answer through one `ModelBackend` per provider, chosen once
  by the settings; the three `when (task.provider)` and `answerWithGemini` go. The engine of the
  local models is `drawview-ml`'s, without a second catalog. This is phase 4 of
  `docs/local-models.md`, whose own plan stays the reference: the live notes may stay on Gemini.
* **Information Expert.** The resource downloads of `SyncEngine` are written to a temporary file as
  they come, checked by their hash, then moved, as the attachments are (no change of what travels).
* **Indirection.** `FormulaRenderer` gives the vectors of a formula (`drawing(latex, size)`, the
  operations `TexOp` already has), so `drawview-export` takes the formulas from `DrawViewEngines`
  and no longer depends on `drawview-latex`. It keeps `drawview-pdf`: writing PDFs is what it is
  for, and a port of the writer waits for a second one (`docs/vector-formulas.md`).
* **Controller.** The secondary window of PencilNotes is built by the same function as the main
  editor, with the assistant, the collaboration and the search of the application (they become
  instances shared by the windows, from B).

### E. The smaller ones

* One drawing of the page backgrounds: a public `drawPageBackground` of `drawview-canvas` used by
  the renderer, the page grid and the template picker (the thumbnails change look: screenshots
  recorded again, shown to the user first).
* `codePointAt` in `drawview-model` (text), `SwitchRow` in the design components of `drawview-ui`.
* The translations of the three modules in the languages of `drawview-ui` (de, es, fr, ja, ko).

## Phases

| Phase | Content | Depends on | Status |
|-------|---------|------------|--------|
| 1 | A. The editor ports: `EditorExtension` open class + builder, `EditorContext` of ports, managers internal; the six extensions moved | – | done (`docs/editor-ports.md`) |
| 2 | A. The `textBox` hook of the extensions (what the suggestions while writing will use) | 1 | done (`docs/text-box-hook.md`) |
| 3 | B. The store and the sessions built by the applications; `store-room` without `ink` | – | done (`docs/instances.md`) |
| 4 | B. `LatexEngine`, `LectureRecordings`, `ImageCache` as instances | 3 | done (`docs/engine-instances.md`) |
| 5 | D. The secondary window of PencilNotes with the assistant, the collaboration, the search | 3 | done (`docs/document-window.md`) |
| 6 | C. `SyncEngine` split (plus the resource downloads on disk, D) | – | downloads done, split later (`docs/sync-engine-split.md`) |
| 7 | C. `DocumentTools` split | – | done (`docs/document-tools.md`) |
| 8 | C. `KeyValueDocumentStore`, `FileExplorerViewModel` split | 3 | done (`docs/store-explorer-split.md`) |
| 9 | C. `LectureAssistant`, `LecturePanel`, `EditorToolbar` split | – | done; the assistant in 11 (`docs/lecture-ui-split.md`) |
| 10 | D. Vector formulas through `FormulaRenderer`; `export` without `latex` | 4 | done (`docs/vector-formulas.md`) |
| 11 | D. The lecture assistant on `ModelBackend` and `drawview-ml` (phase 4 of `docs/local-models.md`) | 9 | done: one backend per provider, the search through it, the assistant split, `DeviceBackend`; choosing the device models (a new setting) waits for the user (`docs/lecture-backends.md`) |
| 12 | E. Page backgrounds, cross-module duplicates, translations | – | duplicates and Italian done; backgrounds and other languages later (`docs/small-cleanups.md`) |

Each phase is one branch (`feat/<name>`) from main, its own plan first, one commit per step of that
plan, merged by fast-forward when the user says so. Phases without a dependency between them can
run at the same time, each in its own worktree; 6, 7 and 9 touch different modules.

## Decisions

Taken alone, revertible:

* **The order**: the extension API first, because it breaks the ABI and must be fixed before the
  first release, and the suggestions while writing wait for it; then the instances (the rule the
  libraries break today); the splits after, since they change no API.
* **`EditorExtension` as an abstract class with open members**, not an interface: the libraries
  compile the defaults of an interface into `DefaultImpls` (see the dumps of `drawview-model`),
  so a new member of an interface would break the implementations already compiled; a new open
  member of a class does not. (An interface becomes possible if the libraries compile with
  `-jvm-default=no-compatibility`, a change of every ABI of its own.)
* **`EditorContext` without `controller`**: an extension that needs more asks for a port; the
  sample's ink latency is read by the sample from its own editor.
* **`DrawViewAndroid` stays**: Android gives one application context per process, and the platform
  module is where the libraries find it; only the reads move into the instances.
* **The splits keep the public API** of their module, so they can be reviewed on behaviour alone.

## Left to the user

* **`LanSync` / `IrohSync`**: the same stop and the same choice between two connections. It is
  protocol behaviour between the user's devices (`PROTOCOL_VERSION` 1): a decision of the user,
  then a plan of its own with `ProtocolFormatTest`.
* **`SecureStore` encrypted on the desktop** (the Keychain on macOS, the Credential Manager on
  Windows, libsecret on Linux): a stored format with its migration.
* **`windows-aarch64`** packaged by CI: a runner of its own.
* **The phases of `feat/handwriting`** (0: the user's handwriting set, 4: the formula model) and
  `feat/lecture-eval` on the Windows PC, outside this plan.
