# The secondary window of PencilNotes

Phase 5 of `docs/architecture.md` (design D, Controller): a document opened in a window of its own
(desktop) gets the editor of the main window, with the lecture assistant, the collaboration and the
search of the application. Nothing stored and nothing that travels changes.

The responsibilities are assigned following the GRASP principles.

## Starting point

1. **The secondary window has an editor without the application.** `PencilNotesDocumentWindow`
   (`pencilnotes/shared`) gives its editor the export and the agents only: no lecture panel or
   button, no sharing or presence of the other devices, and the texts are placed without the
   search (its `contentSearch` is never set, so `PageTopics` finds nothing).
2. **The services of the application live in the composition of the library window.**
   `PencilNotesApp` creates them:
   * the collaboration through `rememberCollaboration` (`drawview-sync-ui`), held by a `ViewModel`
     of the window, which stops it when cleared;
   * the search through `rememberContentSearch` (`drawview-search-ui`): its index is opened in a
     `remember`, closed when the composition goes, the indexer running in a `LaunchedEffect`;
   * the export (`rememberDocumentExportUi`), the engines, the local models, the wiring between
     them (the nearby hosts for the lecture models, the indexing paused while a lecture is
     recorded, the lectures of the documents deleted for good).
   On the desktop each window has its own `ViewModel` store, and the library window can be closed
   while documents stay open (`DesktopWindowManager.closeLibrary`): its collaboration then stops,
   its search index closes, and the windows left work without them. A second window creating
   them again would listen on the network twice and open the index twice.
3. **The dialogs of the application** (`CollaborationDialogs`: the invitations opened from a link,
   the join requests; `LectureDialogs`: the lectures received to transcribe) are shown by the
   library window only.
4. The lecture assistant, the formula engine and the image cache are instances of the process
   already (`PencilNotesData`, phase 4).

## Requirements

1. A document in its own window has the editor of the main window: the lecture assistant, the
   collaboration (sharing, presence, the edits refused), the export, the agents, and texts placed
   next to the pages they are about.
2. One collaboration, one search index, one export per process, shared by the windows, living
   until the application exits, whichever windows are open.
3. Each window binds what belongs to a window: the permission of the microphone and of the local
   network asked from it, the connections refreshed when it comes back to the foreground.
4. A dialog of the application is shown once, in one window.
5. On Android the collaboration stops when the activity finishes for good, as it does today; on
   the desktop the application stops it and closes the index before exiting.
6. The checks of `CLAUDE.md` green, the changes of the ABI recorded on purpose.

## Design

### The libraries: create, then bind to a window

* **Creator / Pure Fabrication.** Each service gets a plain factory, usable outside a composition,
  and a composable that binds it to a window:
  * `drawview-sync-ui`: `createCollaboration(documents, attachments, internet, scope)` (the
    discovery of the platform, the folder of the documents received named in the language of the
    application) and `@Composable CollaborationAccess(collaboration)`: the request of the local
    network from this window, the start when some documents are shared, the refresh when the
    window comes back to the foreground. `Collaboration` takes the name of its folder as
    `sharedFolderName: suspend () -> String` (read when the first document is received), so the
    factory needs no composition. `rememberCollaboration` and its `ViewModel` go: nothing else uses
    them.
  * `drawview-search-ui`: `createContentSearch(...)`, the parameters of `rememberContentSearch`
    (which is built on it and stays, for the applications with one window, tested by
    `ContentSearchLifecycleTest`), and `ContentSearch.close()`, which closes its index (after the
    scope given to `start` is cancelled).
  * `drawview-export-ui`: `DocumentExportUi` is created by its public constructor already.
* **Protected Variations.** The behaviour of each service does not change, only who creates it and
  how long it lives.

### PencilNotes: the services of the application

* **Creator / High Cohesion.** A new `PencilNotesServices` object (`pencilnotes/shared`) creates,
  once per process and on first use, what the windows share: the engines (`DrawViewEngines` with
  the formula engine, the image cache and the topics of the search), the lecture assistant, the
  collaboration, the local models, the search, the export. `PencilNotesData` keeps the notes (the
  store, the documents, the sessions); the formula engine, the image cache and the lecture
  assistant of phase 4 move to the services.
* **Controller.** The services wire themselves in their own scope (the main thread, a
  `SupervisorJob`): the search started, the nearby hosts given to the lecture models, the indexing
  paused while a lecture is recorded, the lectures of the documents deleted for good forgotten.
  `stop()` stops the collaboration, cancels the scope and closes the index:
  * the desktop calls it in `exit()`, after the sessions are flushed;
  * `MainActivity` stops the collaboration when it finishes for good (`isFinishing`), as the
    `ViewModel` did; a new activity starts it again (`CollaborationAccess`).
* **Controller / Low Coupling.** One function builds the extension of every editor of PencilNotes
  (`editorExtension(agents)`: lecture, collaboration, export, agents), used by the library and by
  the secondary window; one composable binds a window (`PencilNotesWindow`: the theme, the adaptive
  root, the commands, the microphone, the local network, the agent pages, and the dialogs of the
  application when this window shows them).
* **Requirement 4.** `main` of the desktop chooses the window showing the dialogs of the
  application: the library when it is open, else the first document window. Android and iOS have
  one window.

## Phases

| Step | Content | Status |
|------|---------|--------|
| 1 | The libraries: `createCollaboration` + `CollaborationAccess` (`rememberCollaboration` goes), `createContentSearch` + `ContentSearch.close()`; tests | done |
| 2 | `PencilNotesServices`: one set of services per process, started and stopped by the application; the library window on it | done |
| 3 | The secondary window with the editor of the application and the dialogs in one window; run on the desktop | done |

One commit per step on `feat/document-window`, each with its ABI dumps updated on purpose and the
checks of `CLAUDE.md`. Step 3 is checked by `DocumentWindowTest` (`pencilnotes/shared`, on the data
directory of the tests): a document in its own window has the button of the lecture assistant and
the action Share (the test fails on the window of before). The desktop of PencilNotes starts on a
scratch data directory; opening a document window and closing the library are left to a test by
hand.

## Decisions

Taken alone, revertible:

* **Services of the process** rather than created by each window or by the `application` of the
  desktop: on the desktop no `ViewModel` store sits above the windows, and the process is what
  lives as long as the application on every platform.
* **`PencilNotesServices` beside `PencilNotesData`**: the notes on one side, what works on them on
  the other.
* **The folder name read when needed** (`suspend () -> String`) rather than a composable factory.
* **`rememberCollaboration` removed**, `rememberContentSearch` kept: the first has no other user,
  the second is the simple form for an application with one window.
* **The dialogs of the application in the library, else in the first document window**, rather
  than in the focused one: a dialog does not jump between windows.
* **The search shared by the windows**: only the library shows its query and results; the other
  windows use its engine to place the texts.

## Left for later

* `InvitationLinks` (`drawview-sync-ui`) is still a global object: the links opened from the
  system reach the application before any window exists. It can become an instance of the
  services when the platforms give the link to the application instead of the library.
* The sample keeps one set of engines per window (it has no collaboration, lectures or search).
