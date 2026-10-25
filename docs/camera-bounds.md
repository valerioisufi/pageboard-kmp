# Camera bounds

How far the canvas can be moved: a page narrower than the window stays centered, a page wider
than the window stops at its edges, and a scroll of a wheel or of a trackpad follows the axis it
started on. This document starts from the behavior reported on a wide desktop window (a document
slightly zoomed in drifts far out of its bounds while scrolling, instead of staying centered) and
from the code as it is (main at d337d589, 2026-10-25).

The responsibilities are assigned following the GRASP principles (Information Expert, Controller,
Low Coupling, Pure Fabrication, Protected Variations).

## Starting point

1. **The pages are capped.** `CalcPage.calcPagesRectOnWindow` scales the pages to fit the width
   of the canvas, at most `maxPageWidth` (880 dp) wide, centered: a wide window keeps the desk
   beside them. A margin of 8 dp (`horizontalPadding`, in world space) is left on each side.
2. **The content is as wide as the window.** The same function computes `contentRect`, the
   rectangle the camera is bounded by, from `leftMostPosition` and `rightMostPosition`, which
   start at the edges of the window instead of the edges of the pages. The content is never
   narrower than the window: on a window wider than about 900 dp it holds the empty desk on both
   sides of the pages. This was harmless while the pages always filled the width.
3. **The camera centers only what fits with a margin.** `CameraPhysicsEngine.updateDynamicBoundaries`
   locks the horizontal translation at the center when the scaled content is narrower than the
   canvas less `horizontalPaddingPx` (40 px) on each side; otherwise it lets the content move
   from one edge to the other, 40 px beyond them. Since the content is as wide as the window, it
   never fits once the zoom is above 1: the horizontal axis is free over the whole desk, scaled.
   The bounds also assume the content starts at 0, which held only because of point 2.
4. **The margins are in pixels.** `horizontalPaddingPx`, `topPaddingPx` and `bottomPaddingPx` are
   40 px at every density: 20 dp on a Mac, 13 dp on a phone at 3×.
5. **Scrolling is free on both axes.** `CanvasGestureHandler` moves the camera by the whole delta
   of every scroll: the wheel and the Windows precision touchpads (`onScroll`, without phases),
   the two-finger scroll of a macOS trackpad (`onScroll` between the `TrackpadScrollPhase`s
   `FINGERS_DOWN` and `FINGERS_UP`), the trackpads of Android and iOS (`onTrackpadPan`, the
   `PointerEventType.Pan*` events). A two-finger scroll is never exactly vertical: with the
   horizontal axis free, its small horizontal parts add up, and the fling keeps them. AWT reports
   the horizontal part of a macOS trackpad scroll as separate events with Shift. Nothing in the
   handler locks an axis.
6. **Touch.** One finger with the pan tool (`Mode.PAN`) and two fingers (`Mode.PINCH`) move the
   camera freely, with a fling (`docs/touch-gestures.md`).

Measured on main with a temporary test driving `CalcPage` and `CameraPhysicsEngine` (an A4 page,
the canvas pushed as far as it goes to the left and to the right):

| Window | Zoom | Page | Expected | Today |
|---|---|---|---|---|
| 1440 dp, 2× | 1.0 | 880 dp | centered, still | moves by ±20 dp |
| 1440 dp, 2× | 1.25 | 1100 dp (fits) | centered, still | drifts over 400 dp: 370 dp of desk on one side, 30 dp cut on the other |
| 1440 dp, 2× | 1.5 | 1320 dp (fits) | centered, still | 440 dp of desk on one side, 320 dp cut on the other |
| 1440 dp, 2× | 2.0 | 1760 dp (wider) | stops near its edges | up to 580 dp of desk beyond an edge |
| 390 dp, 3× | 1.0 | 374 dp | centered, still | moves by ±13 dp, 5 dp cut |

## Requirements

1. **A page that fits stays centered.** When the pages, with their margin, are not wider than the
   canvas, the horizontal translation is locked at the center, at every zoom and on every window.
2. **A page wider than the canvas stops at its edges**: the canvas moves until the edge of the
   widest page, plus its margin, reaches the edge of the canvas, and no further (the rubber band
   still pulls beyond it while a gesture holds it, and springs back).
3. **No jump between the two**: zooming across the width of the canvas changes the bounds
   continuously; the page does not move when the zoom crosses it.
4. **No wobble at the zoom of the opening**, on the phone too, where the pages fill the width.
5. **A scroll of a wheel or of a trackpad follows the axis it started on** (the predominant axis,
   as `NSScrollView` does by default on macOS): a vertical scroll of a zoomed page does not drift
   sideways, its fling neither. A scroll started clearly diagonal stays free.
6. **Touch and stylus are free**, as in the note-taking applications of the iPad: the lock is for
   the wheel and the trackpads only.
7. **Nothing else changes**: the vertical bounds, the zoom limits, the rubber band, the fling and
   the bounce; the page shown at the opening and after a resize.
8. **Public ABI unchanged**: everything is internal to `drawview-canvas`. No stored format and
   nothing that travels changes.

## Design

### The bounds

* `CalcPage` is the expert of where the pages are: `contentRect` becomes the union of the pages
  with their margin (`horizontalPadding` on each side, 8 dp at the zoom of the opening), not the
  window. Its top and bottom do not change.
* `CameraPhysicsEngine` is the expert of the translation: `updateDynamicBoundaries` reads the
  origin of the content as well as its size. Horizontally:
  * the content scaled is not wider than the canvas (within a pixel, for the rounding of the
    layout): the translation is locked where the content is centered;
  * otherwise it goes from the right edge of the content on the right edge of the canvas to its
    left edge on the left edge.

  The margin beside the page is the margin of the content, which grows with the zoom (8 dp at 1×,
  16 dp at 2×); `horizontalPaddingPx` is removed. At the width of the canvas both rules give the
  same translation, so the bounds are continuous (requirement 3), and on a phone the pages with
  their margin fill the width exactly, so they are locked (requirement 4).
* Vertically nothing changes.
* A document mixing page widths is bounded by its widest page, as in Preview and Acrobat in
  continuous scroll: bounds following the page in view would move the camera sideways at every
  change of page.

### The axis of a scroll

* `ScrollAxisLock` (new, internal, Pure Fabrication) decides the axis of one gesture from its
  deltas, which it filters:
  * it adds the deltas up until their length passes a slop (8 dp for a trackpad), holding them
    back meanwhile;
  * then it locks the axis whose part is greater, when the other is at most half of it (within
    about 27° of the axis), or leaves the gesture free;
  * the deltas held back are given once the axis is chosen, filtered; a gesture ending before
    that gives them as they are, so that a short scroll still moves;
  * an axis the content cannot move on (`CameraPhysicsEngine.canMoveX`, `canMoveY`) is left out:
    its rubber band would only show the sideways parts of the fingers, and a gesture locked on it
    would not move at all. With one axis left there is nothing to choose.

  It works on the deltas added up, not on single events, since AWT reports the two axes of a
  macOS trackpad in separate events. A wheel without phases has no slop: its end is known only
  from the next scroll, so nothing is held back, and its first delta chooses (on the desktop each
  of its events carries one axis).
* `CanvasGestureHandler` stays the controller of the input and tells the lock where a gesture
  starts and ends:
  * a macOS trackpad: `FINGERS_DOWN` and `FINGERS_UP` (the inertia of the system is already
    replaced by the fling of the canvas);
  * Android and iOS: `PanStart` and `PanEnd`;
  * a wheel without phases (a mouse, the Windows precision touchpads): a pause of 300 ms without
    scroll starts a new gesture.

  The filtered deltas feed the camera and the velocity tracker, so the fling follows the locked
  axis too. `Mode.PAN` and `Mode.PINCH` (touch, stylus) do not use the lock.
* `CameraPhysicsEngine` does not know about the lock (Low Coupling): the bounds and the axis are
  apart, and either can change without the other (Protected Variations).

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The rectangle of the pages with their margin, in world space | `CalcPage.contentRect` (`drawview-canvas`) | Information Expert |
| The range of the translation: centered when the content fits, else its edges, continuous | `CameraPhysicsEngine.updateDynamicBoundaries` | Information Expert |
| The axis of a scroll gesture and the deltas it lets through | `ScrollAxisLock` | Pure Fabrication |
| Where a scroll gesture starts and ends, for every source; feeding the camera and the fling | `CanvasGestureHandler` | Controller |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | Bounds from the pages: `contentRect` from the pages and their margin; `updateDynamicBoundaries` with the origin of the content, locked at the center when the content fits (within a pixel), else its edges; `horizontalPaddingPx` removed; tests (`CameraBoundsTest`: a wide window at 1×, 1.25×, 1.5× keeping the page centered after a pan to each side, at 2× and 3× stopping at the edges with their margin, a phone at 1× without wobble, a zoom across the width without jump, mixed page widths, a pinch springing back to the center); the screenshots checked unchanged; the desktop application on a scratch directory | Done |
| 2 | Predominant axis: `ScrollAxisLock`; `CanvasGestureHandler` feeding it for the macOS trackpad (phases), the trackpads of Android and iOS (`Pan*` events) and the wheel without phases (pause), the fling from the filtered deltas; tests (`ScrollAxisLockTest`: vertical, horizontal, diagonal, deltas held back, a short scroll; `TrackpadGesturesTest`: a mostly vertical scroll of a zoomed page and its fling moving vertically only, a new gesture choosing again, a wheel after a pause, the touch pan left free); the desktop application on a scratch directory | Done |

Each phase is one commit on `feat/camera-bounds`, with the checks of `CLAUDE.md` (`jvmTest`,
`checkKotlinAbi`, the Android and iOS builds, `verifyRoborazziJvm`). The ABI is not expected to
change.

### Phase 1 results

* `CalcPage.contentRect` is the union of the pages with their margin (`horizontalPadding` on each
  side); its top and bottom are as before.
* `CameraPhysicsEngine.updateDynamicBoundaries` reads the origin of the content: content not wider
  than the viewport (within a pixel) is held at its center, wider content moves from its right
  edge on the right edge of the viewport to its left edge on the left edge. `horizontalPaddingPx`
  is removed; the vertical bounds are unchanged.
* `CameraBoundsTest` (`commonTest`): a window of 1440 dp at 2× keeps the page centered at 1×,
  1.25× and 1.5× after a pan to each side, and stops it at its edges with their margin at 2× and
  3×; the left edge moves by less than 2 px per step of 0.001 while the zoom crosses the width of
  the window; a phone of 390 dp at 3× and one of 1080 px at 2.625× keep their pages still at the
  zoom of the opening; a portrait and a landscape page are bounded by the landscape one; a pinch
  and a pull off the center spring back to it. Run on the code before this phase, five of the six
  fail (the one on the continuity passes: the old bounds were free, not jumping).
* The other tests pass unchanged, and so do the screenshots (`verifyRoborazziJvm` of explorer,
  ui, sync-ui, lecture-ui, export-ui): the zoom of the opening shows the pages where it did. The
  ABI is unchanged.
* The desktop application ran on a scratch directory (`-Ddrawview.dataDir`): it starts, makes its
  database, no error. Its window was not driven by hand; trying the zoom and the scroll on the
  user's devices is left to the user.

### Phase 2 results

* `ScrollAxisLock` (`drawview-canvas`, internal): `begin`, `filter(delta, canMoveX, canMoveY)`,
  `end`; the slop read in pixels when it is needed (the density of the canvas may change),
  `MAX_CROSS_RATIO` = 0.5, `SLOP_DP` = 8, `WHEEL_GESTURE_PAUSE_MS` = 300.
* `CameraPhysicsEngine.canMoveX` and `canMoveY`: whether the bounds let the content move on an
  axis.
* `CanvasGestureHandler`: the trackpad gesture (`startTrackpadPan`, `trackpadPanBy`,
  `endTrackpadPan`, for the fingers of a macOS trackpad and the `Pan*` events of Android and iOS)
  goes through `trackpadAxis`, whose filtered deltas feed the camera and the velocity tracker, so
  the fling follows the axis; what a short gesture held back moves the canvas when it ends. The
  wheel without phases goes through `wheelAxis`, begun again after a pause of 300 ms. The zoom
  with Ctrl or ⌘, the inertia of the system and the touch pans are as before.
* The slop is 8 dp, not the touch slop of the platform: on the desktop it is 18 dp (36 px on a
  Retina screen), which would hold back the start of every scroll.
* Tests: `ScrollAxisLockTest` (`commonTest`: vertical, horizontal, diagonal, held back then given,
  a scroll shorter than the slop, a new gesture, a fixed axis left out, no slop);
  `TrackpadGesturesTest` (a mostly vertical scroll of a macOS trackpad with sideways deltas apart,
  its fling vertical too; a new gesture choosing again; a diagonal scroll free; a page that fits
  not following the sideways part, without rubber band; a wheel without phases keeping its axis
  until a pause; the two-finger pan of Android and iOS on its axis); `TouchGesturesTest` (a
  two-finger drag of a zoomed page moving on both axes). With the lock disabled, the five tests
  of the trackpad and of the wheel that expect it fail; the diagonal and the touch pass.
* The other tests, the screenshots and the ABI are unchanged.
  `LectureRecorderTest.aRecordingIsTranscribedInParallelAndInOrder` (`drawview-lecture`, which
  does not depend on the canvas) failed once in the first build of the worktree and once in three
  runs after it: it depends on timing, apart from this work.
* The desktop application ran on a scratch directory: it starts, makes its database, no error.
  Its window was not driven by hand; trying the scroll on the trackpad of the user is left to the
  user.

## Decisions

Proposed on 2026-10-25 and taken by the user on 2026-10-25 ("write the plan with the two phases"),
every one revertible:

1. The bounds come from the pages, the widest one for the whole document.
2. The predominant axis for the wheel and the trackpads; touch and stylus stay free.

Taken alone while writing the plan, revertible:

3. The margin beside a page wider than the canvas is the margin of the layout (8 dp at the zoom of
   the opening, growing with the zoom), not a margin of the screen: a margin of the screen wider
   than the one of the layout would make the pages that fill the width of a phone wobble, and the
   bounds jump when the zoom crosses the width of the canvas.
4. A gesture locks within about 27° of an axis (the smaller part at most half the greater); a
   diagonal start stays free, as the directional lock of `UIScrollView`.
5. The deltas within the slop are held back until the axis is chosen; the slop is 8 dp (phase 2:
   the 18 dp of the desktop held back too much).
6. A pause of 300 ms separates two gestures of a wheel without phases, whose first delta chooses
   its axis (phase 2: its end is known too late to hold anything back).
7. The vertical bounds and their margins in pixels are left as they are.
8. An axis the content cannot move on is left out of a scroll of a wheel or a trackpad, its rubber
   band included, as `NSScrollView` does with an automatic elasticity (phase 2: a gesture locked on
   it would not move, and its rubber band showed the sideways parts of the fingers).

## Risks

* **Zooming with Ctrl + wheel while the page fits** keeps it centered: the point under the pointer
  moves sideways (as in Preview). Beyond the width, the zoom keeps the point under the pointer as
  today.
* **Panning diagonally on a trackpad**, zoomed in, needs a diagonal start; a gesture started along
  an axis stays on it until the fingers are lifted.
* **A mouse wheel turned right after Shift + wheel** (within 300 ms) is left out, its gesture
  being horizontal; the touchpads of Windows and Linux cannot be told apart from a mouse.
* **A short document that fits the window** no longer bounces vertically under a scroll of a wheel
  or a trackpad (decision 8); touch still bounces it.
* **Tests assuming the old bounds** (a horizontal pan at the zoom of the opening) are corrected in
  the phase that changes them, saying why.

## Left for later

* Bounds following the pages in view, for documents mixing widths.
* The vertical margins in dp.
* A preference turning the lock of the axis off.
