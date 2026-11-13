# The last place of a document

A document opens where it was left: on the page, the point of it and the zoom the user was
looking at when it was closed. Today every document opens at its top, at the zoom that fits the
pages to the width of the window. This document starts from the code as it is (main at 53dc7f0a,
2026-11-13): how a document opens today, then the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Opening.** `DocumentEditorViewModel.openDocument(documentId, then)` (`drawview-session`)
   opens the document through `OpenDocuments`, makes its `DrawViewController` and gives it to
   `then` (e.g. the search, to jump to a page). PencilNotes opens every document this way
   (`PencilNotesApp`, one view model per document).
2. **The first layout.** `Viewport.setSize` (`drawview-canvas`) lays the pages out the first time
   the canvas has a size. Without an anchor it shows the top of the document at scale 1 (the pages
   as wide as the window); with an anchor (`setInitialAnchor(pageIndex, normX, normY)`) it centers
   on that point of that page, at scale 1.
3. **Jumping before the layout.** `PageManager.jumpToPage(pageIndex, bounds)` centers on a page at
   the current zoom; before the first layout it sets the initial anchor. So a jump asked by `then`
   already wins over the top of the document.
4. **Where the editor is looked at.** `Viewport.currentAnchor()` (private) gives the page under the
   center of the canvas and the point of it there, normalized; `mostVisiblePageIndex()` the page
   most visible. `CameraPhysicsEngine.moveCount` is observable state, incremented at every move
   (gestures, fling, bounce, jumps).
5. **Nothing remembers the place.** The view model keeps the preferences of the editor in the
   `PreferenceStore` of the store (`drawview.editor`), the place of a document nowhere.
6. **The preferences.** `PreferenceStore` is a local key-value store (the `preferences` table of
   Room, `KeyValueDocumentStore` on the other stores), never synchronized.
7. **Closing.** `closeSession()` finishes the text being written and closes the session; on desktop
   exit `DrawViewSessions.saveWriting()` then `flushAll()`. On Android and iOS the process may be
   killed in the background without `closeSession`.

## Requirements

1. **A document opens where it was left**: the same page, the same point of it at the center of
   the window, the same zoom.
2. **A place asked wins**: a search result, a lecture note, a proposal of an agent opening the
   document on a page go there, not to the last place.
3. **The place survives a killed process**: it is written when the application goes to the
   background (the system kills a process only there), not only when the document is closed.
4. **Pages changed meanwhile**: the place is kept by the id of the page, so pages added or moved
   before it (here or by another device) do not move it; a page deleted falls back to the page at
   the same number, clamped to the last page.
5. **Another window size**: the point of the page and the zoom are kept, the camera bounds
   (`docs/camera-bounds.md`) applied after as at every layout.
6. **Per device, never sent**: the place is a view state of this device, kept with the local
   preferences; nothing of the protocol changes.
7. **Stored format**: a new preference key, a new small format; it must decode tolerantly (a
   value not understood is ignored and the document opens at its top), never fail the opening.
   No Room schema change.
8. **Every platform**: the logic lives in common code (`drawview-canvas`, `drawview-session`).

## Design

### The experience

* Close a document on page 12, zoomed in on its bottom half: reopen it, it shows the bottom half of
  page 12 at the same zoom.
* Open a document from a search result: it opens on the result, as now.
* A new document, or one never opened since this change: its top, as now.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The place being looked at: page id, page number, normalized point, zoom | `Viewport.currentPlace(): DocumentPlace?` (`drawview-canvas`) | Information Expert (the viewport knows the layout and the camera) |
| Showing a place at the first layout (or at once when laid out) | `Viewport.showPlace(place)`, through the initial anchor extended with a scale | Information Expert, Protected Variations (the same path as `jumpToPage`, so the camera bounds apply) |
| The value kept: what a place is and its text | `DocumentPlace` (`drawview-canvas`, public data class with `encode()` / `decode()`) | Pure Fabrication (a small value with a tolerant format), High Cohesion |
| When the place is read and written, and where | `DocumentEditorViewModel` (`drawview-session`): read before `then`, written while the camera settles and at close | Controller (the session already reads and writes the preferences of the editor), Low Coupling (the canvas knows no store) |
| Keeping it | `PreferenceStore`, key `drawview.place.<documentId>` | Indirection (any store keeping preferences), Protected Variations (no schema change) |

### The place

```kotlin
/** Where a document is looked at: a point of a page and the zoom. */
public data class DocumentPlace(
    val pageId: String,
    /** 1-based, used when the page is not found any more. */
    val pageNumber: Int,
    val x: Float, // 0..1 of the width of the page
    val y: Float, // 0..1 of its height
    val scale: Float
)
```

* Encoded as a short JSON object with a version field (`{"v":1,...}`); `decode` returns `null` on
  anything not understood, a scale outside the bounds of the camera is clamped.
* `Viewport.setInitialAnchor` gains the scale (`null`: 1, as now); `jumpToPage` keeps passing none.

### The session

* In `load`, after the controller is made and before `then`: the place read from the preferences,
  given to `viewport.showPlace`. `then` runs after, so a jump wins (requirement 2).
* When the screen of the editor stops (the application in the background) or goes, as for the
  text being written (`EditorSession.saveWriting`), the place is written; at `closeSession` it is
  written with the changes of the document, and on the desktop exit by `flushAll`.
* Only in the `DocumentEditorViewModel` given a `PreferenceStore`; without one, nothing changes.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `DocumentPlace` with its format and tests (round trip, versions and values not understood, clamps); `Viewport.currentPlace` and `showPlace`, the initial anchor with a scale; tests in `drawview-canvas` (place restored before and after the layout, page moved, page deleted, another window size, a jump after the place winning); ABI of `drawview-canvas` recorded again on purpose | Done |
| 2 | `DocumentEditorViewModel` reads the place before `then` and writes it while the camera settles and at close; tests in `drawview-session` with a `PreferenceStore` in memory (reopen at the place, a `then` jump winning, a value not understood); the desktop application on a scratch directory; the checks of the phase | Done |

No screenshot should change (`verifyRoborazziJvm` of ui, explorer).

### Phase 1 results

* `DocumentPlace` (`drawview-canvas`, package `camera`): the page id, its number (1-based), the
  point at the center of the canvas as fractions of the page, the zoom, and the version of the
  format (`"v":1`). `encode()` writes JSON; `decode()` gives `null` on any text not understood
  (not JSON, a field missing, another version, a page number below 1, a zoom not positive or not
  finite), clamps the point to the page and ignores fields it does not know.
* `Viewport.currentPlace()`: the page under the center of the canvas, the point of it there and the
  zoom (`null` before the first layout, or the place it will show when one was given).
  `Viewport.showPlace(place)`: the page by its id, else by its number clamped to the last page;
  shown at once when the canvas is laid out, else at its first layout. The initial anchor of the
  viewport (a private `Anchor`, which replaced the `Triple`) carries a zoom: `null` for
  `jumpToPage`, which keeps showing the pages as wide as the canvas when it comes before the
  layout. The camera clamps the zoom to its bounds and `restoreToBounds` applies, as at every
  layout.
* Tests: `DocumentPlaceTest` (`drawview-canvas`, 11: the round trip, the texts not understood, the
  clamped point and unknown fields; the document shown again where it was left at its zoom, a place
  shown at once on a canvas laid out, a page moved found by its id, a page deleted by its number
  and beyond the last page, another window size keeping the point and the zoom, a jump asked
  after the place winning, a zoom beyond the camera brought within its bounds, no place before the
  first layout). ABI of `drawview-canvas` recorded again on purpose: `DocumentPlace`,
  `Viewport.currentPlace` and `showPlace` added, nothing removed.
* Nothing stored or sent changed yet: the place is kept by phase 2.

### Phase 2 results

* `DocumentEditorViewModel.load` reads `drawview.place.<documentId>` with the preferences of the
  editor, before the editor is made, and gives the place to `viewport.showPlace` once the editor
  is made: `then` runs after, so a place asked by whoever opens the document wins. A text not
  understood is ignored (`DocumentPlace.decode` gives `null`): the document opens at its top.
* `EditorSession` keeps the place, given the `PreferenceStore` of the view model (none: nothing
  kept, as before):
  * `notePlace()` notes the place on the main thread; `savePlace()` notes it and writes it in the
    persistence scope; the write is skipped when the text is the one kept already (read at the
    opening or written last), under a mutex.
  * `closeSession()` notes the place; `flush()` writes it after the changes of the document (which
    matter more, and keep their timing), before `store.flush()`. So the desktop exit
    (`saveWriting` then `flushAll`) keeps it too.
  * `saveWriting()` (the screen stopped, the application in the background, the editor going)
    saves the place too, so a process killed in the background keeps it. Nothing is written while
    the camera moves (decision 3).
* Tests: `LastPlaceTest` (`drawview-session`, 5, each "process" a store of its own on the same
  storage: a document closed opens again on its page at its zoom; the place written when the
  screen stops, nothing closed or flushed, and read by another process; a jump asked at the opening wins;
  a text not understood opens the top; each document has its own place). The tests of the text
  being written still pass.
* Checks: `jvmTest` of every module (the whole suite, `--rerun-tasks`), the Android and iOS
  compilations, the two Android applications, the screenshots of ui unchanged. `checkKotlinAbi`
  fails on `drawview-session` (the order of `flush` and `flushAll` in the klib dump) and
  `drawview-ui` (string resources of another change), as on main at 53dc7f0a: no change of this
  work. The desktop application run on a scratch directory (`-Ddrawview.dataDir`): it starts,
  makes its database, no error; its window was not driven by hand, the tests going through the
  same calls. Trying it on the user's devices (close a document on a page, reopen it) is left to
  the user.
* Nothing sent changed; the Room schema did not change (a row of the `preferences` table per
  document opened). The public API of `drawview-session` did not change.

## Decisions

Proposed on 2026-11-13, each one revertible:

1. **The zoom is restored too**, not only the page: the user finds the document as they left it.
   The other choice, the page only at the zoom of the opening, is one line to change.
2. **Kept in the local preferences**, per device, one key per document: no Room migration, no
   protocol change. Two devices of the user each remember their own place.
3. **Written when the screen stops or goes, at close and at exit**, so a process killed in the
   background keeps it. A crash of the application loses the place since it went to the
   background last. A first version also wrote it 1 s after the camera rested
   (`snapshotFlow { camera.moveCount }` in the scope of the view model): with it the tests of
   `drawview-agent-ui`, whose editors are never closed, failed under the load of the whole suite
   (Compose laid out on two threads), and passed without it; it was dropped.
4. **By the id of the page**, with its number as fallback.
5. **A jump asked at the opening wins** over the last place.

## Risks

* **A preference row per document opened**: a few dozen bytes each. A document deleted for good
  leaves its row (see "Left for later").
* **The public API of `drawview-canvas` grows** (`DocumentPlace`, `currentPlace`, `showPlace`):
  nothing is published yet, the ABI is recorded again on purpose.

## Left for later

* Forgetting the place of a document deleted for good (the trash emptied).
* Syncing the place between devices ("continue where you left off on the tablet").
* The place of the page grid (open or closed) and of the text being edited.
