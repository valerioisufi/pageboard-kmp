# Touch and two-finger gestures

Two-finger pan, fling and pinch-to-zoom on touch screens (iOS, Android), and interaction when a
drawing tool is active. This document starts from the behavior reported on iPhone: two-finger
gestures do not fling, accidentally zoom the document during scrolling, and make navigation
difficult while drawing tools are selected (2026-10-19).

The responsibilities are assigned following the GRASP principles (Information Expert, Controller,
Low Coupling, Protected Variations).

## Starting point

1. **Two-finger pan and pinch.** When two fingers touch the canvas, `CanvasGestureHandler` enters
   `Mode.PINCH`. In `onPinchMove`, the camera is moved by the centroid delta:
   `controller.viewport.camera.onDrag(delta.x, delta.y, distance / pinchDistance, centroid.x, centroid.y)`.
2. **Missing fling.** `Mode.PINCH` does not feed any `VelocityTracker`. When all fingers are
   lifted (`onAllUp`), velocity is discarded and passed as zero:
   `controller.viewport.camera.onRelease(0f, 0f)`.
   In contrast, single-finger `Mode.PAN` and trackpad pan compute release velocity and start fling
   deceleration via `CameraPhysicsEngine`.
3. **Hyper-sensitive scale.** Every frame in `onPinchMove` computes `distance / pinchDistance` without
   any deadzone or threshold (`touchSlop`). Natural human micro-movements of fingers (1–2 pixels)
   continuously zoom in and out, shaking the document and causing origin shifts around the centroid.
4. **Tool interaction on touch-down.** When a drawing tool (pen, highlighter, eraser, markup) is
   active on a device without a stylus (e.g. iPhone), two fingers touching the glass almost never
   register in the exact same millisecond. The first finger triggers `onFirstDown`:
   - Pen / highlighter starts a stroke (`engine.startStroke`), which is cancelled when the second finger lands.
   - Eraser (`Tool.ERASER`) executes `eraseAlongSegment`, erasing strokes under the first finger, and
     `onAdditionalPointer` commits the gesture (`commitGesture()`), permanently deleting strokes.

## Requirements

1. **Two-finger fling.** Two fingers dragging across the canvas pan the document and, when lifted while
   moving, initiate inertial fling deceleration using the release velocity of the gesture.
2. **Fling on asymmetric finger lift.** Lifting one finger slightly before the other (standard human
   release) must preserve the fling velocity rather than stopping abruptly or spiking.
3. **Fling cancellation on pause.** Pausing movement before lifting fingers must stop the camera with
   zero velocity (no fling).
4. **Stable scale (zoom deadzone).** A two-finger pan must not change the document scale unless the
   finger distance changes beyond an intentional zoom threshold (`touchSlop * 2`).
5. **Smooth zoom transition.** Once the zoom threshold is crossed, scale changes smoothly without
   a sudden jump.
6. **Safe tool touch-down.** If the second finger lands shortly after the first, any accidental
   destruction (such as eraser marks) must be reverted or discarded rather than committed.
7. **No regressions on trackpad, single-finger pan or desktop.** Existing trackpad pan/pinch and
   single-finger pan behavior (`Tool.PAN`, `isStylusOnlyMode`) must remain intact.
8. **Public ABI unchanged.** All modifications remain internal to `drawview-canvas`.

## Design

* **Information Expert.** `CanvasGestureHandler` tracks active pointers, touch positions, timestamps,
  and gesture states. It holds a `pinchVelocityTracker` and a cumulative `pinchPanPosition` (mirroring
  `trackpadPanPosition`) to track translation velocity smoothly without coordinate jumps when pointer
  counts change.
* **Controller.** `CanvasGestureHandler` coordinates `controller.viewport.camera` (`onDragStart`,
  `onDrag`, `onRelease`) and `controller.eraserManager`.
* **Low Coupling & Protected Variations.** `CameraPhysicsEngine` already implements spring physics
  (fling, friction, bouncing, rubber-band) through `onRelease(velocityX, velocityY)`. The gesture
  handler feeds velocity into this existing engine without altering the physics model.
* **Command / Undo.** `EraserManager` provides `cancelGesture()` (rolling back the current history
  group with `undo()`) so that accidental touches when starting a two-finger scroll do not erase notes.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | Two-finger fling and zoom deadzone in `CanvasGestureHandler`: cumulative `pinchPanPosition` with `pinchVelocityTracker`, zoom threshold (`touchSlop * 2`) with smooth scaling, release velocity passed to `camera.onRelease`; unit tests in `TouchGesturesTest` | Done |
| 2 | Safe touch-down for tools: `EraserManager.cancelGesture()` called on additional pointer down during `Mode.ERASE`; unit tests | Done |

### Phase 1 results

* Two-finger translation deltas are accumulated into `pinchPanPosition` and fed into `pinchVelocityTracker`.
  On release (`Mode.PINCH` in `onAllUp`), velocity is passed to `controller.viewport.camera.onRelease(velocity.x, velocity.y)`,
  initiating inertial fling with deceleration and rubber-band bounce.
* Asymmetric finger lift preserves fling velocity without coordinate jumps. Pausing before release zeros out velocity.
* Zoom deadzone (`touchSlop * 2`) locks scale factor to 1.0f during normal two-finger panning. When intentional pinch
  deviates beyond the deadzone, zoom engages smoothly from the threshold.
* Tested with 6 automated tests in `TouchGesturesTest` covering pan, fling, zoom slop, zoom magnification, pause, and asymmetric lift.

### Phase 2 results

* Added `EraserManager.cancelGesture()`, which closes the current group and calls `undo()` to restore any strokes removed
  during live eraser contact.
* In `CanvasGestureHandler.onAdditionalPointer`, when switching from `Mode.ERASE` to `Mode.PINCH` upon the second finger touching down,
  `cancelGesture()` is invoked. Accidental single-finger touch-down while beginning a two-finger scroll does not delete content.
* Verified with automated unit tests for eraser restoration and pen stroke cancellation on two-finger touch.

### Decisions

* **Cumulative delta tracking for fling (`pinchPanPosition += delta`):** Instead of feeding absolute
  centroid coordinates directly into `VelocityTracker`, tracking cumulative pan offset prevents
  velocity spikes when one finger lifts earlier than the other.
* **Relative zoom slop:** `touchSlop * 2` provides an immediate, natural deadzone (~16–24 dp depending
  on density) where vertical or horizontal scrolling remains completely locked to 1.0x scale.

### Left for later

* **Quick Pan / Navigation mode for phones:** A dedicated one-finger navigation mode or temporary
  hold-to-pan toggle on compact screens to further reduce finger fatigue on small devices.
