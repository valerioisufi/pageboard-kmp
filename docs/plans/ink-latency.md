# Ink latency

The ink should follow the pen as closely as paper does. Today the stroke in progress goes
through the same frames as the rest of the canvas: the input waits for the next frame of Compose,
the whole canvas is drawn again, and the frame waits in the queue of the compositor before it
shows; nothing predicts where the pen is going. This document starts from the code as it is
(main at 8f620a64, 2026-11-01): the path of an input, what it costs, then the design and the
plan, approved by the maintainer on 2026-11-01. As in `docs/plans/performance.md`, every change is measured
before and after, and kept only when it gains.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

### The path of an input

1. **The input.** On Android the canvas asks for unbuffered stylus events
   (`Modifier.unbufferedStylusInput`, `View.requestUnbufferedDispatch` on the first down of a
   stylus): a stylus event comes as soon as the digitizer sends it, not batched to the frame. The
   finger is batched. On iOS and the desktop the input comes as Compose gives it; whether Compose
   gives the coalesced touches of the Apple Pencil (240 Hz) as historical changes is not checked.
2. **The gesture.** `CanvasGestureHandler.onMove` (UI thread) gives every historical sample and
   the current one to `InkStrokeEngine.addInput`: the bridge enqueues it in the
   `ink::InProgressStroke` and updates its shape (`ink_stroke_builder_add_point`, one
   `UpdateShape` per input). Then it takes `engine.currentMesh()`: the whole mesh (positions and
   triangles) and the whole outline copied out of the native engine, wrapped in a new
   `StrokeMesh` (the triangle indices converted to shorts), and hands it to
   `CanvasLiveState.setLiveMesh`, which bumps `tick`. It also reports the activity for the others
   (`reportActivity`), which copies every input of the stroke (`engine.inputs.toList()`), shared
   or not.
3. **The frame.** `tick` is read in the one draw block of `DrawCanvas`, so the next frame draws
   the whole canvas again: the desk, the paper sheets, the tiles, the overlays, the collaborators,
   then the stroke in progress (`StrokeMeshPainter`: its outline rebuilt as a path and filled).
4. **The display.** Android: the RenderThread draws the frame, the buffer is queued to
   SurfaceFlinger and shown at a later vsync. iOS: the Metal layer of Compose, paced by its
   display link. Desktop: Skiko, with vsync.
5. **The pen up.** `DrawViewController.commitInkStroke` bakes the stroke into every cached tile it
   covers, at every level of detail, under the lock of the tiles, on the UI thread
   (`TileManager.bakeStrokesDirectlySync`), then submits the change. In handwriting the next
   stroke often starts 100 to 300 ms later: what the pen up costs on the UI thread delays it.
6. **Prediction.** None. The bridge hands Ink an empty predicted batch at every input
   (`EnqueueInputs(real_batch, predicted_batch)`), though Ink models predicted inputs with the
   brush of the stroke and replaces them at the next update.

### What it costs

Measured on the Mac (M5, JVM, `LiveInkBenchmark`, a stroke of 4 s at 240 Hz, one input per event,
the live painter on a raster canvas of 2000 × 1200 at 8 px/mm):

| Inputs so far | Modeling an input (pen / marker / highlighter) | Mesh and outline copied out | Stroke in progress drawn |
|---|---|---|---|
| 60 | 5.7 / 4.0 / 36 µs | 1.7 µs | 0.03 ms |
| 240 | 6.0 / 4.3 / 33 µs | 3.7 µs | 0.12 ms |
| 480 | 6.9 / 4.8 / 34 µs | 6.3 to 8.6 µs | 0.30 ms |
| 960 | 7.8 / 5.3 / 35 µs | 12 to 15 µs | 0.75 ms |

On the Mac the work of an input is small: the latency is in the structure, not in the work.

1. **The ink waits for the frames.** An input waits for the next frame (up to one frame, half on
   average), the frame is drawn (UI thread, then the RenderThread or Metal), and the buffer waits
   for the compositor (one or two more vsyncs). On a 60 Hz display such as the one of the
   user, that is typically two to three frames, 35 to 50 ms, before the digitizer is counted.
   Only drawing the ink outside these frames (a front buffer) or predicting it hides this.
2. **The whole canvas is drawn for each change of the stroke**: tiles and overlays are recorded
   again though only the stroke changed. Small on the Mac; to be measured on the test tablet, where a
   frame that misses its vsync adds a whole frame.
3. **The stroke in progress is drawn whole at every frame**: its outline rebuilt as a path and
   filled, which grows with the stroke (0.75 ms on the Mac after 4 s, several times that on a
   tablet).
4. **The mesh is copied at every event, not at every frame**: with unbuffered stylus events at
   240 Hz on a 60 Hz display, three copies out of four are never drawn; and the triangles are
   copied though the live painter fills the outline only.
5. **The activity copies the whole stroke at every event** (O(n²) over a stroke), also when the
   document is shared with nobody.
6. **The pen up bakes every level of detail** on the UI thread before the next stroke can start.
   Not measured yet.

## Requirements

1. **Measured, on every platform**: the latency from an input to the frame that shows it,
   in-app (median and 95th percentile over a stroke); on the test tablet also from the pen to the
   photons, by a slow-motion video (the only true measure). Every phase gives its numbers before
   and after; a change that does not gain is not kept.
2. **The ink drawn live is the ink committed**: the same engine, the same brush, no jump or flash
   at the pen up, no frame without the stroke and no frame with it twice (a translucent
   highlighter would show it darker). Only the predicted tail goes away at the pen up.
3. **The prediction is never stored or sent**: the stroke committed and the activity sent to the
   others hold the real inputs only. Nothing stored or sent changes: no Room migration, no change
   of the protocol (`ProtocolFormatTest` as it is).
4. **A translucent stroke is painted once** where it overlaps itself, as today.
5. **The libraries keep their rules**: `drawview-ink` stays headless; a platform without front
   buffer, or a device where it fails, falls back to the Compose layer, never to a broken canvas;
   the public API is explicit and its ABI recorded.
6. **No frame is asked for while nothing moves** (the battery), as today.
7. **The other gestures do not get slower**: the eraser, the lasso, the hold that makes a shape,
   the palm rejection, the stroke over two pages.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Measuring the latency of the ink: input time against the frame that draws it | `InkLatencyMeter` (`drawview-canvas`), off by default, read by `DrawViewController.inkLatency` | Pure Fabrication (no domain object owns the measure), Protected Variations (a device can always be measured again) |
| Drawing the stroke in progress, apart from the committed content | `LiveInkLayer` (`drawview-canvas`): the port; `ComposeLiveInkLayer` (common, a draw node of its own over the tiles) and `FrontBufferedLiveInkLayer` (Android) | Polymorphism, Protected Variations (the gesture handler does not know where the ink is drawn), High Cohesion (`DrawCanvas` no longer draws the ink) |
| Choosing the layer of the platform, and falling back | `rememberLiveInkLayer()` (expect/actual) | Creator (the canvas owns the layer), Protected Variations |
| The shape of the stroke, real and predicted | `InkStrokeEngine` (`drawview-ink`): the predicted inputs given with the real ones, the outline alone for the live layer | Information Expert (it owns the native stroke) |
| Where the pen will be | `InkPredictor` (`drawview-canvas`): `KalmanInkPredictor` (common), `MotionEventInkPredictor` (Android, `androidx.input`), the predicted touches of UIKit (iOS) | Polymorphism, Protected Variations (each platform its best source), Pure Fabrication |
| Feeding the inputs and the prediction, ending the stroke | `CanvasGestureHandler`, as today | Controller (it owns the pointer events and the stroke in progress) |
| The hand-off from the live layer to the tiles at the pen up | `DrawViewController.commitInkStroke` and the layer (`LiveInkLayer.handOff`) | Information Expert (the controller knows the tiles, the layer knows when it is clear) |

### Measuring

`InkLatencyMeter` keeps, for the stroke in progress, the time of the oldest input not yet drawn;
when a frame draws the ink it records `frame time - input time`, and at the pen up it gives the
median and the 95th percentile of the stroke (`InkLatencyReport`). The clocks are those of the
platform: on Android the `uptimeMillis` of the events and the frame time of the Choreographer
share their base; on the desktop and iOS phase 0 checks the bases and matches them once per stroke
where they differ; on Android the time to the display is added from `FrameMetrics` of the
window. The sample shows the reports over the canvas. On the test tablet the maintainer films the pen moving
at a steady speed at 240 fps: the gap between the nib and the end of the ink over the speed of the
pen is the latency from the pen to the photons.

### The stroke in progress in a layer of its own

`DrawCanvas` draws the committed content in its canvas and puts the `LiveInkLayer` over it. In
Compose the layer is a canvas of its own, which alone reads the state of the stroke: a change of the
stroke records only the ink, not the tiles and the overlays. The cursors of the eraser and of the
ink, and the box of a new text, move with it (they change at every event too).

The layer takes the shape once per frame, not once per event: the gesture handler only tells it
that the stroke changed, and the layer asks the engine for the outline when it draws
(`InkStrokeEngine.currentOutline()`, no triangles, no `StrokeMesh`); the Kotlin tessellator, which
has no outline, keeps giving its triangles. The activity for the others is reported once per frame
too, from the same place.

### Prediction

The bridge takes the predicted inputs after the real ones of an event
(`ink_stroke_builder_predict`): Ink models them with the brush of the stroke and drops them at the
next update, so the predicted tail looks like the stroke and never stays.
`InkStrokeEngine.predict(points)` keeps `inputs` real; `finishStroke` and `cancelStroke` clear the
prediction, so the mesh committed has none.

The gesture handler asks the `InkPredictor` after the real inputs of an event. The prediction
reaches at most one frame ahead (the interval of the display), and none while the pen slows down to
a stop: the hold that makes a shape and the end of a stroke show no tail beyond the nib.

* `KalmanInkPredictor` (common, written here): position, velocity and acceleration of the pen
  filtered per axis, the pressure held. The desktop, the finger, and every device where nothing
  better exists.
* `MotionEventInkPredictor` (Android): `androidx.input:input-motionprediction` (Apache 2.0) fed by
  the `MotionEvent`s the canvas already spies; it uses the predictor of the system, tuned for each
  device, on Android 14 and later, and a Kalman filter like ours before. The test tablet (Android 10)
  gains nothing from it over ours.
* iOS: the predicted touches of UIKit (`UIEvent.predictedTouches(for:)`), read by a gesture
  recognizer on the view of the composition that never recognizes and cancels nothing, the same
  way `PencilInteractionEffect` reaches the view. The same recognizer gives the coalesced touches
  if Compose does not.

### Front-buffered ink on Android

`FrontBufferedLiveInkLayer` draws the stroke in progress with `CanvasFrontBufferedRenderer` of
`androidx.graphics:graphics-core` (Apache 2.0, Android 10 and later: the test tablet is Android 10) in a
`SurfaceView` over the canvas. At each event, not each frame, the layer renders the change of the
stroke into the front buffer: the region Ink updated (`GetUpdatedRegion`, plus the old and the new
predicted tail) is cleared and the whole stroke is filled again clipped to it, so a translucent
stroke stays painted once. The frame of Compose, the RenderThread and the queue of SurfaceFlinger
are skipped; where the device has no front buffer the library still presents the layer with
transactions of its own, outside the frames of the application. How much each gains on the test tablet
is what the phase measures.

The camera does not move while a stroke is drawn (a finger landing is a palm under the stylus and
cancels a stroke of the finger, the canvas does not auto-scroll for the ink), so the front buffer keeps the transform of the stroke from its
start. At the pen up the controller bakes the stroke into the tiles as today, and the layer clears
the front buffer only once the frame of Compose that draws those tiles is on screen; the phase
checks frame by frame that the hand-off shows neither a gap nor the stroke twice. The layer is over
the bars of the editor while the pen draws under them, as the ink is over the page; the phase
clips it to the canvas.

If the renderer cannot be created or fails, the layer falls back to `ComposeLiveInkLayer` for the
rest of the session.

### Not this way

* **`androidx.ink` (`InProgressStrokesView`) on Android**: it is front-buffered and predicts, but
  it carries its own copy of the Ink engine and its own brushes: two native engines in the APK,
  and a stroke drawn live by one and committed by the other, which may not match.
* **A Metal layer of our own on iOS, vsync off on the desktop**: they skip the frames of Compose
  there as the front buffer does on Android, but are much more work; only if the measures show
  that the frames dominate there once prediction is in (left for later).

## Plan

Order by the gain expected on the test tablet: 0, 1, 2, 4, 6, then 3 and 5 (they need an Android 14
device and an iPad with a Pencil to be measured).

| Phase | Content | Status |
|---|---|---|
| 0 | The measures: `InkLatencyMeter` and `DrawViewController.inkLatency` (ABI recorded on purpose), shown in the sample; `LiveInkBenchmark` (M1, the work of an input), the pen up on the UI thread (M2); in-app latency on the desktop, the Android emulator and the iOS simulator (M3), and on the test tablet with the video (M4, by the maintainer); whether Compose gives the coalesced touches of the Pencil (M5, from its sources or an iPad) | Done; M3 on the desktop and M4 left to the maintainer |
| 1 | `LiveInkLayer` and `ComposeLiveInkLayer`: the stroke and the cursors in a canvas of their own, the outline taken once per frame, the activity reported once per frame; tests of the gestures as they are, screenshots | Done |
| 2 | Prediction: the predicted batch in the bridge (JNI and cinterop), `InkStrokeEngine.predict` (ABI of `drawview-ink` recorded on purpose), `InkPredictor` and `KalmanInkPredictor`; tests: nothing predicted is committed or sent, no tail on a hold, the shapes and the lasso as they are | Done |
| 3 | Android: `MotionEventInkPredictor` (`androidx.input`), kept if it beats the Kalman filter on an Android 14 device or emulator | Left for later: nothing to measure it on (the test tablet is Android 10) |
| 4 | Android: `FrontBufferedLiveInkLayer` (`graphics-core`), the hand-off at the pen up, the fallback; measured on the test tablet (in-app and video) and on an emulator | Done; by hand with its pen left to the maintainer |
| 5 | iOS: the predicted (and, after M5, coalesced) touches of the Pencil from a gesture recognizer; measured on an iPad | Left for later: nothing to measure it on (no iPad with a Pencil); the Kalman filter of phase 2 predicts there |
| 6 | The pen up: only the level of detail on screen baked on the UI thread, the others on the workers, if M2 shows a cost | Not needed: 0.14 to 0.43 ms (M2) |

### Phase 0 results

* `InkLatencyMeter` (`drawview-canvas`, `controller/InkLatency.kt`) is told the inputs by the gesture
  handler and the frames by the layer that draws the stroke; `DrawViewController.isInkLatencyMeasured`
  (Compose state, off by default) switches it on, and `inkLatency` gives an `InkLatencyReport` at the
  end of each stroke: the median and the 95th percentile of the time from an input to the first
  frame that drew it, of the age of the tip of the ink when a frame drew it, and how far the
  prediction reached. A frame that draws no new input is not counted; the hold that makes a shape
  ends the measure (the pen held still is no latency). ABI of `drawview-canvas` recorded on purpose.
* The clock of the pointer events (`pointerClockMillis`): the uptime on Android (`eventTime`), the
  wall-clock time of the AWT events on the desktop (`InputEvent.getWhen`, read in
  `ComposeSceneMediator.desktop.kt`), the uptime on iOS (`UITouch.timestamp`).
* The sample: a clock in the bar of the editor switches the measure on; the report of the last
  stroke is shown over the canvas and printed (`DrawView ink latency: ...`).
* **M1** (Mac): the table of the starting point.
* **M2** (Mac, a screen of tiles of 400 strokes a page): the pen up takes 0.14 ms for a stroke of
  60 inputs, 0.43 ms for one of 480. Far from the 100 to 300 ms between two strokes of handwriting:
  phase 6 is not needed (decision below).
* **M3**, Android emulator `Tablet_API29` (Android 10, 1200 × 2000, 320 dpi, 60 Hz), five strokes of
  0.7 s injected as a stylus (`adb shell input stylus swipe`, about 70 events a second): from an
  input to the frame that draws it 12 to 16 ms (95th percentile 18 to 24 ms), tip age 9 to 10 ms
  (95th percentile 12 to 24 ms). The measure stops where the UI thread draws the frame: the
  RenderThread and the compositor come after it.
* **M3**, iOS simulator (iPad Pro 11-inch M5, iOS 27.2), two strokes dragged by the simulator at 60
  and 120 Hz: from an input to the frame that draws it 12 to 13 ms (95th percentile 36 to 48 ms),
  tip age 12 ms (95th percentile 16 to 36 ms).
* **M3 on the desktop** needs a hand on the mouse or the trackpad: left to the maintainer, with the
  sample.
* **M4**, left to the maintainer: film the test tablet at 240 fps (slow motion of a phone) while drawing a long
  line at a steady speed with its pen; the gap between the nib and the end of the ink, over the
  speed of the pen (the distance of two frames of the video), is the latency from the pen to the
  photons. Before phase 2, after phase 2 and after phase 4.
* **M5**: Compose 1.12.1 gives the coalesced touches of UIKit as the historical changes of a move
  (`historicalChangesForTouch` in `ComposeSceneMediator.ios.kt`, from `coalescedTouchesForTouch`,
  with their timestamps): the 240 Hz of the Pencil already reach the gesture; phase 5 needs the
  predicted touches only.

### Phase 1 results

* `LiveInkLayer` (`drawview-canvas`): the gesture handler tells it `strokeChanged(engine, color,
  pageIndex)` and `strokeEnded()`; `ComposeLiveInkLayer` takes the shape from the engine when its
  canvas draws, once per frame. `DrawCanvas` is a `Box` of two canvases, each in a `graphicsLayer`
  of its own: the content (tiles, overlays, the collaborators) and the live state (the stroke, the
  cursors of the eraser and of the pens, the box of a new text).
* `InkStrokeEngine.currentOutline()` (ABI of `drawview-ink` recorded on purpose) gives the outline
  alone; `StrokeMeshPainter.drawOutline` fills it. The Kotlin tessellator, without an outline, still
  draws its triangles.
* The activity for the others is reported at most every 16 ms of the events during a gesture (and at
  its start and end), not at every event of the stylus.
* **M1** (Mac): the outline alone costs half of the mesh (0.7 µs against 1.5 µs after 60 inputs,
  7.3 µs against 15.9 µs after 960), and is taken once per frame instead of at every event: with
  stylus events at 240 Hz on a 60 Hz display, 7 µs per frame instead of 64 µs after 960 inputs.
* **M6** (Mac, `LiveInkFrameBenchmark`, a frame while drawing over a page of 400 strokes, recorded
  and rasterized by Skia on the CPU): 1.80 ms before, 1.77 ms after. The raster of the tiles, which
  both pay, dominates.
* **M3 on the test tablet** (connected, two rounds of five strokes each, after a
  warm-up): from an input to the frame that draws it 15 to 17 ms before and after (95th percentile
  21 to 25, then 20 to 23 ms), tip age 12 to 14 ms before and after; the frames of the window
  (`dumpsys gfxinfo`) 14 to 15 ms at the median before, 13 to 14 after, 17 to 22 ms at the 90th
  percentile before, 17 after. Every frame of the editor nearly fills the 16.7 ms of the display:
  the frame itself is where the time goes, which only the front buffer (phase 4) skips.
* On the emulator the same (13 to 15 ms before, 10 to 15 after, frames of 5 ms both); the first
  rounds after a cold boot of the emulator are no measure (a frame of 46 ms at the median).

### Phase 2 results

* `ink_stroke_builder_predict` (bridge, JNI, cinterop) gives Ink a predicted batch and updates the
  shape; a real input takes the prediction away. Ink leaves the shape as it is in `UpdateShape` after
  `FinishInputs` when nothing is queued, so the bridge takes the prediction away before finishing a
  stroke: the mesh committed has no tail. Ink models the last real inputs of a stroke that was
  predicted a little differently: up to a few hundredths of a millimeter at the end of a stroke of the
  pressure pen, in the mesh of that session only (the inputs stored are the same).
* `InkStrokeEngine.predict(points)` (ABI of `drawview-ink` recorded on purpose), `InkPredictor` and
  `KalmanInkPredictor` (`drawview-canvas`): a Kalman filter per axis in dp, up to one frame (16 ms)
  ahead, no prediction under 0.05 dp/ms or beyond where a pen slowing down would stop. The gesture
  handler asks it after the real inputs of an event, for the pens and the lasso. The Kotlin
  tessellator predicts nothing.
* **The prediction, off-line** (`InkPredictorTest`, synthetic strokes with noise of a fifth of a dp,
  at 240, 120 and 60 Hz): where the pen really is 16 ms after a sample, against the tip predicted and
  against the last sample (what is drawn without prediction), in dp: handwriting 1.9 to 2.0 against
  6.0 to 6.3 (95th percentile 3.2 to 3.5 against 8.7 to 9.0), a circle 0.9 to 1.0 against 6.5, a line
  starting and stopping smoothly 0.9 to 1.1 against 8.1 to 8.7. A pen that stops is not drawn beyond
  where it stays.
* **M7** (Mac): an event costs 0.25 µs for the filter and 9.5 µs for Ink modeling the tail.
* **M3**, emulator `Tablet_API29`, two rounds of five strokes each: tip age 8 to 10 ms before, -5 to
  -7 ms after (95th percentile 10 to 22 ms before, -4 to 2 ms after), with 16 ms predicted; from an
  input to its frame 12 to 15 ms before and after. The tip drawn stands for a moment 5 to 7 ms after
  the frame: the display still shows it later, by the time the RenderThread and the compositor take.
* The tests of the gestures (`InkPredictionGestureTest`): the tip beyond the pen while it moves, only
  the inputs of the pen sent and committed; the tests of the shapes and of the lasso as they are.
  `PredictionTest` (`drawview-ink`) passes on the JVM and on the iOS simulator.

### Phase 4 results

* `FrontBufferedLiveInkLayer` (`drawview-canvas`, Android): `CanvasFrontBufferedRenderer` of
  `androidx.graphics:graphics-core` 1.0.4 in a `SurfaceView` over the canvas. At each event the outline
  and the region Ink updated (`InkStrokeEngine.takeUpdatedRegion`, `ink_stroke_builder_take_updated_region`,
  ABI of `drawview-ink` recorded on purpose) wait for the renderer, merged with what it has not drawn
  yet; the pass clears the region and fills the stroke clipped to it. During a stroke the window draws
  one frame, the one of the hand-off (`dumpsys SurfaceFlinger --latency`).
* A translucent stroke stays in the frames of Compose, and so does every stroke without a surface, a
  renderer or an outline: `rememberLiveInkLayer` gives `ComposeLiveInkLayer` on iOS and the desktop.
* The hand-off: at the pen up the last shape, without the prediction, is drawn in the buffer, which is
  hidden once the tiles are known to show the stroke, not after a fixed count of frames (the first
  version waited 4, and the maintainer saw strokes disappear for a few frames at the pen up, sometimes in
  some tiles only). The frame must have recorded the tiles with no cell of the stroke waiting for its
  first tile (`TileManager.isScreenRegionDrawn`), the renderer must have submitted it
  (`registerFrameCommitCallback`), and 3 frames must have passed for the compositor; after 30 frames
  it is hidden anyway. On the test tablet, 45 strokes by `adb`: tiles recorded in 14 ms (max 21), frame
  submitted in 32 ms (max 36), buffer hidden in 91 ms (max 95). Those strokes never showed a late
  frame, so the cause seen by hand is not reproduced that way. A stroke given up, or the lasso, hides
  it at once. A stroke that starts meanwhile
  keeps it. Recorded on the emulator at 60 fps (six strokes): a finished stroke never disappears; for 3
  frames its edges are antialiased twice (about 11% more dark pixels), then as they stay.
* On Android 10 the library has no `USAGE_FRONT_BUFFER`, and hwui draws at most once per vsync (17 ms
  between passes on the test tablet); the buffer is ready 6.2 ms after the pass is recorded (90th percentile
  7.8 ms).
* **M8, the lead on the screen, on the test tablet** (the measure that shows the compositor, which the meter
  does not): Compose draws a marker where the last input is, while the ink is drawn as it will be
  shipped; a screenshot during a line at 1.29 px/ms (10 screenshots each, composited output, not the
  scan-out of the panel) gives how far the tip of the ink is ahead of the marker. A temporary build, not
  committed:

  | The ink | Tip ahead of the marker | Gained on the ink as it was |
  |---|---|---|
  | Compose, no prediction (as it was) | -8 px | |
  | Compose, prediction | +21 px | 22 ms |
  | Front buffer, no prediction | +43 px | 40 ms |
  | Front buffer, prediction (as shipped) | +64 px | 56 ms |

  At the constant speed of a straight line, which favors the prediction. The -8 px are the modeler of
  Ink, about 6 ms behind the input.
* **M3 on the test tablet**: tip age 12 to 14 ms (phase 1), -3 to -6 ms now, with 16 ms predicted; the frames
  of the window drop out of it.
* Checked on the test tablet by adb: the ink shows during a stroke (a screenshot at its middle), the
  highlighter, the eraser, the lasso; a finger swipe behaves as at phase 0. Not checked: the pen by
  hand, its hovering, two fingers, the hold that makes a shape on the device (tested on the JVM).

## Decisions

* **Every phase is measured and kept only if it gains**, as in `docs/plans/performance.md`.
* **A front buffer of our own over our engine, not `androidx.ink`** (revertible): one native
  engine, the stroke drawn live is the stroke committed.
* **The prediction is Ink's predicted inputs** (revertible): modeled with the brush, replaced at
  every update, never in `inputs`, never stored or sent.
* **The predictor is a port with one implementation per source** (revertible): ours in common code,
  the system's on Android, UIKit's on iOS.
* **The meter stays in the library, off by default** (revertible): a public flag and report, so
  that any device can be measured again, at the price of a small public API.
* **Nothing stored or sent changes**: no migration, no change of the protocol.
* **Phase 1 kept** (revertible): it gains on the work of an input (M1) and not on the latency
  measured (M3, M6), and the front buffer of phase 4 needs the layer apart.
* **A translucent stroke stays in the frames of Compose** (revertible): the hand-off cannot be atomic
  before Android 13, and a translucent stroke shown twice would flash darker.
* **Phases 3 and 5 left for later** (revertible): the plan keeps what is measured to gain, and no device
  of the maintainer runs them; the Kalman filter already predicts on iOS and on Android 14.
* **No phase 6** (revertible): the pen up costs 0.14 to 0.43 ms on the Mac (M2); a device that shows
  more can bring it back.

## Left for later

* A Metal layer of our own for the ink on iOS, and the desktop without vsync, if the frames still
  dominate there after the prediction.
* The pressure of the historical samples on Android: Compose gives none
  (`HistoricalChange` has no pressure), so the samples of an event take the pressure of the last
  one. The `MotionEvent`s spied in phase 3 have it.
* Unbuffered events for the finger when it draws (today the stylus only).
* Displays faster than 60 Hz (`Surface.setFrameRate`, ProMotion): nothing of the maintainer's yet.
