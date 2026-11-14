# The editor ports

Phase 1 of `docs/architecture.md`: the API by which an extension adds to the editor, fixed before
the first release of the libraries. This document starts from the code as it is (main at
62f75094, 2026-11-14).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **`EditorExtension` is a final class of six lambdas** (`drawview-ui`, `extension/`):
   `toolbarActions`, `canvasOverlay`, `commands`, `panels`, `readOnlyNote`, `pageGridActions`,
   given to its constructor, joined by `plus`. A new hook is a new parameter of the constructor:
   every extension compiled before breaks (`NoSuchMethodError`).
2. **`EditorContext(controller)` gives the whole `DrawViewController`**, with `documentId`. It is
   built by `EditorSessionScreen` (`drawview-session`), by two tests and by the consumer check
   `EditorAlone`.
3. **Six extensions** use it: the lecture assistant (`drawview-lecture-ui`), the collaboration
   (`drawview-sync-ui`), the review of the proposals (`drawview-agent-ui`), the export
   (`drawview-export-ui`), and two of the sample (shapes, ink latency). They pass the controller
   down to their layers and panels. What they read of it, counted in their modules:
   * **the document**: `document` (pages, title, `findResource`, `findElement`), `isReadOnly`;
   * **the pages and the view**: `pageManager.selection`, `pageManager.jumpToPage`,
     `viewport.mostVisiblePageIndex`, `firstVisiblePageIndex`, `visiblePages`,
     `viewport.pageMmToScreen`, and `version` with `viewport.camera.moveCount` read to redraw an
     overlay when the canvas moves;
   * **the changes**: `session.submit`, `session.addObserver`/`removeObserver`,
     `historyManager.group`, `customElementManager.add`, `selectionManager.selectText` and
     `clearSelection`, `textEditorManager.isEditing`/`finishEditing`, `pendingTextImport`;
   * **the placement**: `placementManager.start`, `spotsFor`, `pagesFor`, `pdfTextOf`, `cancel`;
     `textPlacer.start`, `sizeOfPage`, `partsByTopic`, `canPlaceByTopic`;
   * **the rendering**: `engines` (formulas, code), `measureText`, and
     `tileManager.tileRenderer` (images of regions of pages, the text of a PDF page) for the
     notebook the lecture assistant reads;
   * **the others**: `presence` (`collaborators`, `activity`, `editsRefused`);
   * **the sample**: `isInkLatencyMeasured`, `inkLatency`.
4. **The managers are built by the controller with `this`** (`SelectionManager(this)`,
   `PageManager(this)`…), and their constructors are public.
5. `docs/modularization.md` chose no `EditorHost` between the managers (they collaborate), as long
   as the managers stay behind the controller: the extensions reach them today.

## Requirements

1. A new hook of the extensions (phase 2: the text box) is added without breaking an extension
   compiled before.
2. An extension reaches the editor through ports: the document, the pages and the view, the
   changes, the placement, the rendering, the presence. Not `DrawViewController`, not a manager,
   not the tiles.
3. The six extensions do what they do today, with the same screenshots.
4. The hosts (`EditorSessionScreen`, the tests, the consumer check) still build a context from a
   controller.
5. The managers are created by the controller only.
6. Nothing stored nor sent changes.

## Design

* **Protected Variations (the extensions).** `EditorExtension` becomes an `abstract class` with
  `open` members, each with the default of today. A new open member does not break a subclass
  compiled before. (An interface would: the libraries compile the defaults of an interface into
  `DefaultImpls`.) It is written in two ways:
  * a subclass (`object : EditorExtension() { override … }`);
  * the builder `EditorExtension { toolbarActions { context -> … } commands { … } }`: a function
    with a receiver whose setters are members, so a new hook is a new setter, not a new
    parameter.
  `plus` stays, final, and returns a `CombinedExtension` (Pure Fabrication) that runs both.
* **Indirection (the ports).** `EditorContext` gives interfaces of `drawview-canvas`, one per
  role, in the package `canvas.port`:
  * `EditorDocument`: the document (read), `isReadOnly`, `submit(change)`, `group { }` (one undo),
    `observe(observer)` returning a handle to stop.
  * `EditorPages`: the selection of pages, the page most visible, the first visible and the visible
    ones, `jumpTo(pageIndex, box?)`, `pageToScreen(pageIndex)` (the matrix), and `frames`: a
    counter that changes when the canvas is drawn again or moves (in place of `version` and
    `camera.moveCount` read together).
  * `EditorEdits`: add a custom element, select a text, clear the selection, whether a text is being
    written and finish it, the text waiting to be imported.
  * `EditorPlacement`: the placement of items and of texts by topic (what `PlacementManager` and
    `TextPlacer` offer to the extensions).
  * `EditorRendering`: the engines, the size of a text, the image of a region of a page, the text of
    a PDF page.
  * `CollaboratorPresence`, already a class of its own that does not know the controller.
  Interfaces fit here: only the editor implements them, so a new member breaks no one.
* **Information Expert / Controller.** Each port is implemented by the class that holds its
  information: `EditorPages` by `PageManager` with the `Viewport`, `EditorPlacement` by
  `PlacementManager` with the `TextPlacer`, `EditorRendering` by the `TileManager`. The controller
  is the one that gives them to the context (`DrawViewController.ports`).
* **Low Coupling.** `EditorContext` keeps the controller `internal` for the editor of
  `drawview-ui` (`EditorScreen` and its parts); its public constructor stays for the hosts. The
  extensions of the repository pass the ports, not the controller, to their layers and panels.
* **Creator.** The constructors of the managers become `internal`: the controller creates them.
* The sample's ink latency is a measure of the editor, not of an extension: the sample reads it
  from the controller of its session and shows it in its own screen.

## Phases

| Phase | Content | Status |
|-------|---------|--------|
| 1 | The ports in `drawview-canvas`, implemented by the managers and given by the controller; no caller moved | done |
| 2 | `EditorContext` of ports; the extensions of the lecture assistant, the collaboration, the agent, the export, the sample moved; `controller` internal | done |
| 3 | `EditorExtension` as an abstract class with its builder and `CombinedExtension`; the extensions and the consumer check moved | to do |
| 4 | The constructors of the managers internal | to do |

Each phase is one commit on `feat/editor-ports`, after the checks of `CLAUDE.md`, the screenshots
(`verifyRoborazziJvm` of explorer, ui, sync-ui, lecture-ui, export-ui, agent-ui) and
`consumer-checks` (the API changes in every phase), with the desktop app run on a scratch data
directory at the end.

## Decisions

Taken alone, revertible:

* **The builder keeps the name `EditorExtension`** (a function named as the class), so the call
  sites read as today with blocks in place of named arguments.
* **One `frames` counter** in place of reading `version` and the camera: what an overlay needs is
  "the canvas changed", and how the editor counts it can change behind it.
* **The ports are interfaces, the extension a class**: the editor implements the first, the others
  the second.
* **No port for the ink latency**: it is the sample measuring the editor.
* **The ports are answered by small adapters** of the controller (`ControllerPorts.kt`), each
  delegating to the manager that holds the information, rather than by the managers implementing
  the interfaces: the managers keep their own API, and a port can gather two of them
  (`EditorPlacement`: the placement and the texts by topic).
* **`EditorPlacement` gives the occupancy of a page and the planner** too: the lecture assistant
  places several notes at once on the spots it plans itself, on what the page already holds.
* **The sample's ink latency reads the editor through `DrawViewSessions`**, by the document of the
  context, as the agent editors of the applications already do: the sample is a host, not an
  extension.

## Left for later

* The `textBox` hook of the extensions: phase 2 of `docs/architecture.md`, with its own plan.
* Whether other modules than the extensions (the explorer, the session, the applications) should
  also go through the ports: they are hosts of the editor today and build it.
