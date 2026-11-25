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
| 4 | Native ink prediction (`MotionEventInkPredictor` on Android, `IosPencilInkPredictor` on iOS), historical pressure lookup, unbuffered finger dispatch on Android, >60 Hz `Surface.setFrameRate` / ProMotion / desktop refresh horizon, `MetalLiveInkLayer` on iOS & low-latency desktop support; JVM/Android/iOS build & tests | Pending |

## Decisions (revertible)

1. **Outline-only meshes on native strokes**: keeps triangles only for the Kotlin fallback modeller; all native strokes drop `positions`/`indices` after modeling, saving ~60% of geometry memory.
2. **Bounded LRU path cache (256 entries) inside `StrokeMeshPainter`**: avoids global mutable state and ensures Skia native `Path` instances are deterministically closed on eviction.
3. **Quick Pan is session state, not persisted**: toggled quickly from the phone floating pill when navigating, leaving tool selection untouched.
4. **`Viewport.panBy` moves what is drawn by as much, past the bounds too** (`CameraPhysicsEngine.panDrawnBy`): beyond the bounds the camera draws its excess with the resistance of a drag, so a pan measured on the screen (the caret brought into sight above the keyboard, `TextPlacer`) used to land short by that resistance. With the margins in dp the bottom bound came 20 px closer at density 1, and `TextBoxLargeTest.aPanByHandIsNotUndoneBeforeTheNextKey` showed it: the caret came back 22 px short. `panBy` now inverts the resistance; a drag by hand still resists.
5. **Outline-only native strokes in the sample test**: `SharedLogicDesktopTest` asked the native modeller for triangles, which phase 1 dropped on purpose; it now asks for triangles or an outline.

## Left for later

* Physical device slow-motion (240 fps) latency measurements on Android 14 (with `MotionEventInkPredictor` and 90/120 Hz display) and iPad with Apple Pencil (ProMotion + `MetalLiveInkLayer`), left to the maintainer.
