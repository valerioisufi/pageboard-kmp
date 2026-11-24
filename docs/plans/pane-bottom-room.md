# The room at the bottom of the navigation pane

The navigation pane of a wide library ends with 80 dp left empty under the actions of the
application. It is there for a lecture being recorded, and it stays empty when none is. This
document starts from the code as it is (main at 0a7f3980, 2026-11-23): the cause, then the design
and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **`LibraryNavigationPane`** (`pageboard-explorer`, `LibraryPanes.kt`) ends with a `Spacer` 64 dp
   high, under the padding of 16 dp of the pane: "room for what the application draws over the
   bottom of the library (a recording)".
2. **What the application draws over the library** is `DocumentLibrary.libraryOverlay`, a
   `@Composable BoxScope.(openDocument) -> Unit`, composed in a `Box` over `ExplorerScreen`. The
   library does not know what it draws, nor where, nor whether it draws anything.
3. **PencilNotes draws two things there**: `LectureRecordingIndicator` (`pageboard-lecture-ui`) at
   the bottom start, 16 dp from the edges and 52 dp high, only while a lecture is recorded or
   reviewed; the status of the exports at the bottom center, which does not reach the pane.
4. **So the room is kept always**, and is empty nearly always. With no recording, the actions could
   go 64 dp lower.

## Requirements

1. The room under the actions is kept only while the overlay draws something over the pane, and
   as high as what it draws.
2. What the overlay draws never covers the actions of the pane.
3. The public API does not change: the applications keep their `libraryOverlay` as it is.

## Design

| Responsibility | Where | Pattern |
|---|---|---|
| Laying out the overlay as a `Box` does (`align`, `matchParentSize`) and measuring how high, from the bottom, what it places over the start band of the window reaches | `LibraryOverlayLayout` (internal, `pageboard-explorer`), its own `BoxScope` | Pure Fabrication, Information Expert (the layout is the one that places the content) |
| Passing that height from the overlay to the pane | a `bottomReach` state in `DocumentLibrary`, given to `ExplorerScreen` and to `LibraryNavigationPane` | Indirection, Low Coupling (the pane knows a height, not the overlay) |
| Keeping the room: the spacer as high as the reach less the padding of the pane, none when nothing reaches | `LibraryNavigationPane` | Information Expert |

The band measured is the width of the navigation pane (`LibraryLayout.NavigationWidth`) on the
start side, so that the exports at the center do not count. A layout of its own, rather than a
parameter of `DocumentLibrary` telling the height, keeps the API as it is and follows what is
drawn, whatever the application draws (Protected Variations).

## Plan

| Phase | What | Status |
|---|---|---|
| 1 | `LibraryOverlayLayout` and its test (`LibraryOverlayLayoutTest`: what reaches the band, what does not, the start side in both directions, the layout following what is drawn); the spacer of the pane following it, the padding of what is drawn being the gap (the indicator of PencilNotes reaches 84 dp: 68 dp of room over the 16 dp of the pane, 4 dp more than before); the screenshots of the panes recorded again (the actions 64 dp lower) | Done |

## Decisions

- The measure is taken from where the content is placed, not from a height the application gives:
  revertible, to a parameter of `DocumentLibrary` if a measure turns out to be wrong somewhere.
- While the indicator appears, the actions move up by its height (the spacer grows in one frame,
  no animation): revertible.

## Left for later

- Nothing.
