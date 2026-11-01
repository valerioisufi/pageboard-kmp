# Ink latency

The ink should follow the pen as closely as paper does. Today the stroke in progress goes
through the same frames as the rest of the canvas: the input waits for the next frame of Compose,
the whole canvas is drawn again, and the frame waits in the queue of the compositor before it
shows; nothing predicts where the pen is going. This document starts from the code as it is
(main at fefe1e2a, 2026-11-01): the path of an input, what it costs, then the design and the
plan, approved by the user on 2026-11-01. As in `docs/performance.md`, every change is measured
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
   for the compositor (one or two more vsyncs). On a 60 Hz display such as the MatePad 10.4 of the
   user, that is typically two to three frames, 35 to 50 ms, before the digitizer is counted.
   Only drawing the ink outside these frames (a front buffer) or predicting it hides this.
2. **The whole canvas is drawn for each change of the stroke**: tiles and overlays are recorded
   again though only the stroke changed. Small on the Mac; to be measured on the MatePad, where a
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
   in-app (median and 95th percentile over a stroke); on the MatePad also from the pen to the
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
window. The sample shows the reports over the canvas. On the MatePad the user films the pen moving
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

The bridge takes the predicted inputs with the real ones in one call
(`ink_stroke_builder_add_points` with a predicted batch): Ink models them with the brush of the
stroke and drops them at the next update, so the predicted tail looks like the stroke and never
stays. `InkStrokeEngine.addInputs(real, predicted)` keeps `inputs` real; `finishStroke` and
`cancelStroke` clear the prediction, so the mesh committed has none.

The gesture handler asks the `InkPredictor` after the real inputs of an event. The prediction
reaches at most one frame ahead (the interval of the display), and none while the pen slows down to
a stop: the hold that makes a shape and the end of a stroke show no tail beyond the nib.

* `KalmanInkPredictor` (common, written here): position, velocity and acceleration of the pen
  filtered per axis, the pressure held. The desktop, the finger, and every device where nothing
  better exists.
* `MotionEventInkPredictor` (Android): `androidx.input:input-motionprediction` (Apache 2.0) fed by
  the `MotionEvent`s the canvas already spies; it uses the predictor of the system, tuned for each
  device, on Android 14 and later, and a Kalman filter like ours before. The MatePad (Android 10)
  gains nothing from it over ours.
* iOS: the predicted touches of UIKit (`UIEvent.predictedTouches(for:)`), read by a gesture
  recognizer on the view of the composition that never recognizes and cancels nothing, the same
  way `PencilInteractionEffect` reaches the view. The same recognizer gives the coalesced touches
  if Compose does not.

### Front-buffered ink on Android

`FrontBufferedLiveInkLayer` draws the stroke in progress with `CanvasFrontBufferedRenderer` of
`androidx.graphics:graphics-core` (Apache 2.0, Android 10 and later: the MatePad is Android 10) in a
`SurfaceView` over the canvas. At each event, not each frame, the layer renders the change of the
stroke into the front buffer: the region Ink updated (`GetUpdatedRegion`, plus the old and the new
predicted tail) is cleared and the whole stroke is filled again clipped to it, so a translucent
stroke stays painted once. The frame of Compose, the RenderThread and the queue of SurfaceFlinger
are skipped; where the device has no front buffer the library still presents the layer with
transactions of its own, outside the frames of the application. How much each gains on the MatePad
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

Order by the gain expected on the MatePad: 0, 1, 2, 4, 6, then 3 and 5 (they need an Android 14
device and an iPad with a Pencil to be measured).

| Phase | Content | Status |
|---|---|---|
| 0 | The measures: `InkLatencyMeter` and `DrawViewController.inkLatency` (ABI recorded on purpose), shown in the sample; `LiveInkBenchmark` (M1, the work of an input), the pen up on the UI thread (M2); in-app latency on the desktop, the Android emulator and the iOS simulator (M3), and on the MatePad with the video (M4, by the user); whether Compose gives the coalesced touches of the Pencil (M5, from its sources or an iPad) | proposed |
| 1 | `LiveInkLayer` and `ComposeLiveInkLayer`: the stroke and the cursors in a canvas of their own, the outline taken once per frame, the activity reported once per frame; tests of the gestures as they are, screenshots | proposed |
| 2 | Prediction: the predicted batch in the bridge (JNI and cinterop), `InkStrokeEngine.addInputs(real, predicted)` (ABI of `drawview-ink` recorded on purpose), `InkPredictor` and `KalmanInkPredictor`; tests: nothing predicted is committed or sent, no tail on a hold, the shapes and the lasso as they are | proposed |
| 3 | Android: `MotionEventInkPredictor` (`androidx.input`), kept if it beats the Kalman filter on an Android 14 device or emulator | proposed |
| 4 | Android: `FrontBufferedLiveInkLayer` (`graphics-core`), the hand-off at the pen up, the fallback; measured on the MatePad (in-app and video) and on an emulator | proposed |
| 5 | iOS: the predicted (and, after M5, coalesced) touches of the Pencil from a gesture recognizer; measured on an iPad | proposed |
| 6 | The pen up: only the level of detail on screen baked on the UI thread, the others on the workers, if M2 shows a cost | proposed |

## Decisions

* **Every phase is measured and kept only if it gains**, as in `docs/performance.md`.
* **A front buffer of our own over our engine, not `androidx.ink`** (revertible): one native
  engine, the stroke drawn live is the stroke committed.
* **The prediction is Ink's predicted inputs** (revertible): modeled with the brush, replaced at
  every update, never in `inputs`, never stored or sent.
* **The predictor is a port with one implementation per source** (revertible): ours in common code,
  the system's on Android, UIKit's on iOS.
* **The meter stays in the library, off by default** (revertible): a public flag and report, so
  that any device can be measured again, at the price of a small public API.
* **Nothing stored or sent changes**: no migration, no change of the protocol.

## Left for later

* A Metal layer of our own for the ink on iOS, and the desktop without vsync, if the frames still
  dominate there after the prediction.
* The pressure of the historical samples on Android: Compose gives none
  (`HistoricalChange` has no pressure), so the samples of an event take the pressure of the last
  one. The `MotionEvent`s spied in phase 3 have it.
* Unbuffered events for the finger when it draws (today the stylus only).
* Displays faster than 60 Hz (`Surface.setFrameRate`, ProMotion): nothing of the user's yet.
