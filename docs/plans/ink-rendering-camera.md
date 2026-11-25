# Ink Latency, Antialiasing Memory/Cache, Tiles, Camera Bounds & Quick Pan

This plan completes the remaining items from `docs/plans/ink-latency.md`, `docs/plans/ink-antialiasing.md`, `docs/plans/tile-zoom-out.md`, `docs/plans/camera-bounds.md`, and `docs/plans/touch-gestures.md`.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator, Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected Variations).

## Starting point

1. **Antialiasing memory & path rebuilds** (`StrokeMesh`, `HitTesting`, `NativeStrokeEngineDelegate`, `StrokeMeshPainter`): native strokes copy both triangle meshes and polygon outlines (+57% geometry memory), and `StrokeMeshPainter` rebuilds Skia/Android `Path`s on every draw call.
2. **Tiles** (`TileCache`, `TileManager`, `TileQueue`): fixed 64 MB tile budget regardless of screen pixel count; during fast zoom-out, descendant search stops at 2 generations and workers rasterize transient intermediate levels.
3. **Camera bounds & touch** (`CalcPage`, `CameraPhysicsEngine`, `ScrollAxisLock`, `CanvasGestureHandler`): horizontal bounds use the widest page in the entire document; vertical margins use fixed `40f` px instead of dp; scroll axis lock has no user toggle; phones have no one-finger Quick Pan mode.
4. **Ink prediction & display pipeline** (`InkPredictor`, `PlatformInput`, `LiveInkLayer`): `KalmanInkPredictor` is used on all platforms; Android lacks `MotionEventInkPredictor` + historical sample pressures + unbuffered finger dispatch + `Surface.setFrameRate`; iOS lacks `UIEvent.predictedTouches` + `coalescedTouches` pressure + dedicated `CAMetalLayer` rendering; desktop lacks display refresh rate horizon and low-latency vsync toggle.

## Requirements

1. **Reduce outline antialiasing memory & cache paths**: native committed strokes store `StrokeOutline` without duplicate triangle buffers; `HitTesting` tests capsule/polygon intersections directly against `StrokeOutline`; `StrokeMeshPainter` caches `Path`s in a bounded LRU cache for strokes redrawn across frames.
2. **Dynamic tile budget & sharper zoom-out**: scale `TileCache` budget dynamically to viewport pixel area (`64..192 MB`), search 3 descendant generations during zoom-out, and pre-schedule target coarser LODs when zooming out rapidly.
3. **Adaptive camera bounds, dp margins, axis-lock toggle & Quick Pan**: bound horizontal camera translation to the visible pages' width; express vertical margins in dp (`20f * density`); add `EditorPreferences.isScrollAxisLockEnabled`; add one-finger Quick Pan mode for phones (touch pans while stylus still draws).
4. **Native ink prediction & low-latency rendering**:
   - Android: `MotionEventInkPredictor` (`androidx.input:input-motionprediction`) with Kalman fallback, historical sample pressure buffer, unbuffered finger dispatch while drawing, and `Surface.setFrameRate` for >60 Hz displays on `FrontBufferedLiveInkLayer`.
   - iOS: passive `UIGestureRecognizer` capturing `predictedTouchesForTouch` and `coalescedTouchesForTouch` pressures, ProMotion refresh rate query, and `MetalLiveInkLayer` (`CAMetalLayer` + Skia `DirectContext` with safe fallback to `ComposeLiveInkLayer`).
   - Desktop: display refresh rate horizon and opt-in `skiko.vsync.enabled=false` / `DesktopLiveInkLayer` low-latency support.
5. **Safe fallbacks**: every platform enhancement degrades cleanly to `KalmanInkPredictor` / `ComposeLiveInkLayer` in headless tests, emulators, and simulators.

## Design

| Responsibility | Class / Module | GRASP |
|---|---|---|
| Outline-only bounds & exact capsule/polygon hit-testing | `StrokeMesh`, `HitTesting` (`pageboard-model`), `NativeStrokeEngineDelegate` (`pageboard-ink`) | Information Expert, Pure Fabrication |
| Bounded LRU path cache for repeated draws | `StrokeMeshPainter` (`pageboard-canvas`) | Pure Fabrication, Protected Variations |
| Viewport-scaled cache budget & zoom-out scheduling | `TileCache`, `TileManager`, `TileQueue` (`pageboard-canvas`) | Information Expert, Controller |
| Visible-page horizontal bounds & dp vertical margins | `CalcPage`, `CameraPhysicsEngine`, `Viewport` (`pageboard-canvas`) | Information Expert |
| Axis-lock preference & Quick Pan gesture routing | `EditorPreferences`, `PageboardController`, `CanvasGestureHandler`, `EditorScaffold` (`pageboard-canvas`, `pageboard-ui`) | Controller, Low Coupling |
| Native predictors, historical pressures & display refresh horizon | `PlatformInput`, `InkPredictor`, `MotionEventInkPredictor` (Android), `IosPencilInkPredictor` (iOS) (`pageboard-canvas`) | Polymorphism, Protected Variations |
| High-refresh front buffer & Metal/desktop low-latency layers | `FrontBufferedLiveInkLayer` (Android), `MetalLiveInkLayer` (iOS), `DesktopLiveInkLayer` (JVM) (`pageboard-canvas`) | Polymorphism, Protected Variations |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | Memory reduction (`StrokeMesh` outline-only support, `HitTesting` outline capsule/polygon, `NativeStrokeEngineDelegate` dropping duplicate triangles) & LRU `Path` cache in `StrokeMeshPainter` (Skiko & Android); tests & benchmark; ABI recorded | Done |
| 2 | Dynamic viewport `TileCache` budget and sharper zoom-out (3-gen descendants + coarser LOD pre-scheduling); tests | Done |
| 3 | Adaptive camera bounds for mixed-width pages (`CalcPage`, `CameraPhysicsEngine`), dp vertical margins, `isScrollAxisLockEnabled` preference + command, and Quick Pan mode (`PageboardController`, `CanvasGestureHandler`, phone floating pill, 7 locales); tests; ABI recorded | Done |
| 4 | Native ink prediction (`MotionEventInkPredictor` on Android, `IosPencilInkPredictor` on iOS), historical pressure lookup, unbuffered finger dispatch on Android, >60 Hz `Surface.setFrameRate` / ProMotion / desktop refresh horizon, `MetalLiveInkLayer` on iOS & low-latency desktop support; JVM/Android/iOS build & tests | Done, but the Metal layer of iOS and a desktop layer of its own (decisions 11 and 12) |

## Decisions (revertible)

1. **Outline-only meshes on native strokes**: keeps triangles only for the Kotlin fallback modeller; all native strokes drop `positions`/`indices` after modeling, saving ~60% of geometry memory.
2. **Bounded LRU path cache (256 entries) inside `StrokeMeshPainter`**: avoids global mutable state and ensures Skia native `Path` instances are deterministically closed on eviction.
3. **Quick Pan is session state, not persisted**: toggled quickly from the phone floating pill when navigating, leaving tool selection untouched.
4. **`Viewport.panBy` moves what is drawn by as much, past the bounds too** (`CameraPhysicsEngine.panDrawnBy`): beyond the bounds the camera draws its excess with the resistance of a drag, so a pan measured on the screen (the caret brought into sight above the keyboard, `TextPlacer`) used to land short by that resistance. With the margins in dp the bottom bound came 20 px closer at density 1, and `TextBoxLargeTest.aPanByHandIsNotUndoneBeforeTheNextKey` showed it: the caret came back 22 px short. `panBy` now inverts the resistance; a drag by hand still resists.
5. **Outline-only native strokes in the sample test**: `SharedLogicDesktopTest` asked the native modeller for triangles, which phase 1 dropped on purpose; it now asks for triangles or an outline.
6. **The port of the platforms is `InkPlatform`** (`rememberInkPlatform`, `Modifier.inkInput`): the pressures of the samples (`SamplePressures`, looked up by their time and place), the predictor of the platform with the Kalman filter as fallback (`PreferredInkPredictor`), a frame of the display as the horizon (`frameMillisOf`). The gesture knows no platform; the tests give it a platform of their own (`PageCanvas` internal overload).
7. **Android 14's own `MotionPredictor` instead of `androidx.input:input-motionprediction`**: the library delegates to it on Android 14 for the devices that predict their pen, and below it runs a Kalman filter of its own, no better than ours. So the system predictor where `isPredictionAvailable` says it knows the pen, our filter everywhere else, and no new dependency. Its gain is still to be measured on an Android 14 device with a pen.
8. **Unbuffered events for a finger only while it draws** (a pen chosen, not in stylus-only mode or Quick Pan): a finger that pans keeps its batched events, which cost less.
9. **The fastest refresh of the display only while a stroke is in the front buffer** (`Surface.setFrameRate`, Android 11): at rest the display chooses, for the battery.
10. **iOS: the touches seen by an observer recognizer of our own** (`PBTouchObserver`, Objective-C in `native/bridge/touch_observer.m`, built by `native/scripts/build_touch_observer.sh` into a static library for cinterop): Kotlin/Native cannot override the methods `UIGestureRecognizer` leaves to its subclasses. It never recognizes, cancels and delays nothing. UIKit may give it an event after the recognizer of Compose: a prediction that comes late predicts the stroke again (`InkPlatform.setPredictionListener`); a pressure that comes late is not found, and the sample keeps the pressure of its event, as before.
11. **No Metal layer of our own on iOS yet** (`MetalLiveInkLayer`): Compose keeps the drawables of its Metal layer in Objective-C so that Kotlin/Native does not hold them (a fourth `nextDrawable` blocks the main thread), presents with the transactions of UIKit when views are mixed in, and the hand-off to the tiles needs its frames counted as on Android. Without an iPad with a Pencil to measure it, a layer that may stall or flash is a risk for a gain no one has seen; the predicted touches of UIKit come first. Left for later, with the measure.
12. **No layer of our own on the desktop**: Skiko already draws without waiting for the vsync with `-Dskiko.vsync.enabled=false` (`JAVA_TOOL_OPTIONS`), which is the opt-in to measure; nothing to add in the code.

## Left for later

* Physical device slow-motion (240 fps) latency measurements on Android 14 (the system predictor, a 90/120 Hz display) and an iPad with an Apple Pencil (the predicted touches, ProMotion), left to the maintainer.
* The Metal layer of the ink on iOS (decision 11), once the measures say the frames still dominate.
* Whether UIKit gives the observer of the touches an event before the recognizer of Compose (decision 10): on an iPad with a Pencil, the coalesced pressures found (`SamplePressures`) say it.
