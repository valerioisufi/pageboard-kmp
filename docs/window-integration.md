# System window integration

Better integration of PencilNotes and DrawView applications with the host windowing systems
across desktop (macOS, Windows, Linux) and mobile (iPadOS/iOS, Android). This document builds
upon the foundation laid in [adaptive-ui.md](adaptive-ui.md) (phase 5: window state persistence,
minimum window size, and platform conventions).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Desktop window frame (JVM)**:
   * `pencilnotes-desktop` and `sample-desktop` use a standard Compose `Window` with
     `rememberDrawViewWindowState` (persisting size, position, and placement in
     `window-<name>.properties`), `WindowMinimumSize(360.dp, 480.dp)`, and
     `System.setProperty("apple.laf.useScreenMenuBar", "true")`.
   * The window decoration is the standard operating system chrome.
   * On **macOS**, an opaque title bar sits above the application toolbar. The traffic light
     buttons (close, minimize, zoom) take a dedicated vertical strip, pushing the app content
     down and breaking the unified appearance expected in modern macOS applications (like Notes,
     Freeform, Pages, and Safari). There is no document proxy icon and no dirty dot
     (`Window.documentModified`) on the close button when changes are pending flush.
   * On **Windows 10/11**, the native title bar remains light/white even when the application is
     running in the dark theme, unless the DWM immersive dark mode attribute is applied.
   * **Single-window lifecycle**: both desktop applications run as single-window apps
     (`application { Window(...) { ... } }`). Opening a note in `DocumentLibrary` replaces the
     view inside the same window. Users cannot open two notes side-by-side or view a reference
     note on a second monitor.
2. **Android**:
   * `MainActivity` enables edge-to-edge drawing (`enableEdgeToEdge()`) and installs the splash
     screen.
   * `SystemBars.android.kt` hides system bars during fullscreen editing using
     `WindowInsetsControllerCompat`.
   * The activity does not declare multi-instance or document launch modes; opening notes from
     external intents or links replaces the task, preventing side-by-side note comparison in
     split-screen mode or on desktop environments like Samsung DeX and Pixel Desktop.
   * Drawing with a pen or finger near the screen edges can accidentally trigger Android's
     predictive back or system navigation gestures because no gesture exclusion rects
     (`View.setSystemGestureExclusionRects`) are registered along the canvas boundary.
3. **iPadOS / iOS**:
   * `ContentView.swift` wraps `ComposeView` with `.ignoresSafeArea()`.
   * `SystemBarsHidden` is a no-op on iOS (`drawview-explorer/src/iosMain/.../SystemBars.ios.kt`
     is empty); the status bar and home indicator remain visible during fullscreen drawing.
   * The app is configured with a single `WindowGroup` in SwiftUI without document scene
     activations, disallowing iPadOS Stage Manager and Split View multi-window workflows.

## Requirements

1. **macOS unified title bar (Full Window Content)**:
   * The content of the application must extend into the title bar area
     (`apple.awt.fullWindowContent = true`, `apple.awt.transparentTitleBar = true`).
   * The top bar of `EditorTopBar` and `ExplorerScreen` must leave clearance for macOS traffic
     lights on the leading side (~78 dp) when floating in windowed mode, and adjust automatically
     when entering native macOS fullscreen mode.
   * The header area must provide draggable regions (`WindowDraggableArea`) so users can move the
     window naturally by dragging empty title space.
2. **Windows 10/11 dark title bar**:
   * The native Windows title bar must match the active theme (light or dark), using the DWM
     window attribute (`DWMWA_USE_IMMERSIVE_DARK_MODE`).
3. **Desktop multi-window support**:
   * Users must be able to open notes in separate desktop windows:
     * Context menu action on a document card/row in the library: "Open in New Window".
     * Shortcut: ⌥⌘O / Ctrl+Alt+O, or ⌘-Click / Ctrl-Click on a note.
   * Each window manages its own document session through `DrawViewSessions`, and saves its
     individual window state (`window-doc-<id>.properties`).
   * Closing a document window flushes its session without terminating the entire application;
     closing the main library window when document windows are open can either minimize to dock or
     remain running until all windows are closed.
4. **macOS document state**:
   * When a note is open on macOS, indicate unsaved or pending flush changes via the window's
     modified dot (`Window.documentModified`).
5. **Mobile immersive experience & gesture safety**:
   * **Android**: Register system gesture exclusion rects (`View.setSystemGestureExclusionRects`)
     for the canvas drawing surface so edge strokes do not trigger the system back navigation.
   * **Android**: Enable multi-instance document launching (`documentLaunchMode="intoExisting"`
     or `FLAG_ACTIVITY_MULTIPLE_TASK`) for tablet split-screen and Samsung DeX multi-window.
   * **iOS**: Implement `SystemBarsHidden` to hide the status bar and set
     `prefersHomeIndicatorAutoHidden = true` while drawing in fullscreen mode.
6. **Architecture & ABI compliance**:
   * Follow the modular boundaries: common window decor utilities in `drawview-ui`, platform-specific
     mechanisms in their respective source sets (`jvmMain`, `androidMain`, `iosMain`), and wiring
     in `pencilnotes` and `sample`.
   * Public APIs must maintain explicit binary compatibility (checked via `checkKotlinAbi`).

## Design

### Responsibilities and GRASP patterns

| Responsibility | Class / Component | Module | GRASP |
|---|---|---|---|
| Insets for window controls (traffic lights clearance on macOS, zero elsewhere or in fullscreen) | `WindowDecorInsets` | `drawview-ui` | Information Expert, Pure Fabrication |
| Unified window scaffold with draggable header and system controls clearance | `WindowDecorScaffold` / `WindowDecor` | `drawview-ui` (jvmMain) | High Cohesion, Indirection |
| Windows DWM immersive dark mode attribute application | `WindowsDwmHelper` | `drawview-ui` (jvmMain) | Pure Fabrication, Low Coupling |
| macOS document modified property and proxy icon | `MacWindowHelper` | `drawview-ui` (jvmMain) | Pure Fabrication, Information Expert |
| Multi-window lifecycle coordinator on desktop (tracking open library and document windows) | `DesktopWindowManager` | `drawview-ui` (jvmMain) | Controller, Creator |
| Per-document window state persistence | `rememberDrawViewWindowState(name)` | `drawview-ui` (jvmMain) | Information Expert |
| System bars and immersive mode controller | `SystemBarsHidden` | `drawview-explorer` / `drawview-ui` | Polymorphism, Protected Variations |
| System gesture exclusion rects on Android canvas | `CanvasGestureExclusionModifier` | `drawview-canvas` (androidMain) | Information Expert, Low Coupling |
| Multi-window / multi-instance activity launch on Android | `MainActivity` / `openDocumentIntent` | `pencilnotes-android` | Controller |
| iPadOS multi-scene activation for notes | `iOSApp.swift` (`WindowGroup(for:)`) | `pencilnotes/iosApp` | Controller, Polymorphism |

### Desktop Unified Window Decor

```
+-------------------------------------------------------------------------+
| [O O O]   <- (macOS traffic lights)                                      |
| [78dp pad]  Library Search / Editor Title     [Actions / More] [ _ [] X ]|  <-- Unified Bar (Draggable)
+-------------------------------------------------------------------------+
|                                                                         |
|                                                                         |
|                            Content Area                                 |
|                     (Library Grid or DrawCanvas)                        |
|                                                                         |
|                                                                         |
+-------------------------------------------------------------------------+
```

* On macOS, `apple.awt.fullWindowContent` places the content under the title bar, while
  `apple.awt.transparentTitleBar` removes the title bar's solid background.
* `WindowDecorInsets.current`:
  * Returns `PaddingValues(start = 78.dp, top = 0.dp)` when running on macOS and the window is
    not in fullscreen.
  * Returns `PaddingValues(0.dp)` on Windows, Linux, or when in macOS native fullscreen.
* Draggable regions: Compose Desktop provides `WindowDraggableArea` within `FrameWindowScope`.
  The empty regions of the top bars are wrapped in `WindowDraggableArea` so that the window
  remains movable by dragging.

### Windows 10/11 Dark Title Bar

* On Windows, Java AWT frames can have the dark mode attribute set on their HWND via DWM:
  `DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, &value, sizeof(value))`.
* Applied whenever the window is created and updated if `LocalDrawViewColors.current.isDark`
  changes.

### Desktop Multi-Window Architecture

* Currently:
  ```kotlin
  application {
      Window(...) {
          PencilNotesApp(...)
      }
  }
  ```
* Multi-window model:
  ```kotlin
  application {
      val windowManager = rememberDesktopWindowManager()
      
      // Main library window (if visible)
      if (windowManager.isLibraryVisible) {
          Window(
              title = "PencilNotes",
              state = rememberDrawViewWindowState("library"),
              onCloseRequest = { windowManager.closeLibrary() }
          ) {
              PencilNotesLibraryWindow(...)
          }
      }
      
      // Secondary document windows
      for (docSession in windowManager.openDocumentWindows) {
          key(docSession.documentId) {
              Window(
                  title = docSession.documentName,
                  state = rememberDrawViewWindowState("doc-${docSession.documentId}"),
                  onCloseRequest = { windowManager.closeDocument(docSession.documentId) }
              ) {
                  PencilNotesDocumentWindow(docSession)
              }
          }
      }
  }
  ```
* Sessions: `DrawViewSessions` is already a session manager keyed by document ID. Opening a note
  in a new window acquires or reuses the session for that document. Closing the window flushes
  and releases that session.

### Android System Gestures & Multi-Tasking

* In `androidMain` of `drawview-canvas`, when drawing with a stylus or finger near the screen
  edge (within 24–36 dp of left/right screen edges), register rects via
  `View.setSystemGestureExclusionRects(rects)` so Android does not interpret an edge-to-center
  drawing stroke as a back navigation gesture.
* In `AndroidManifest.xml`, configure `android:documentLaunchMode="intoExisting"` and
  `android:resizeableActivity="true"`.

### iOS / iPadOS Multi-Window & Immersive Controls

* In `SystemBars.ios.kt`, control `UIViewController.prefersStatusBarHidden` and
  `prefersHomeIndicatorAutoHidden` via a root controller observer or `ComposeUIViewController`
  delegate.
* In `iOSApp.swift`, define:
  ```swift
  WindowGroup("PencilNotes", id: "library") {
      ContentView()
  }
  WindowGroup("Note", id: "note", for: String.self) { $documentId in
      NoteDocumentView(documentId: documentId)
  }
  ```

## Plan

| Phase | Content | Status |
|---|---|---|
| 1. Desktop Window Decor | macOS unified title bar (`apple.awt.fullWindowContent`, `transparentTitleBar`), `WindowDecorInsets` providing traffic-light clearance; `WindowDraggableArea` on headers; Windows DWM dark title bar; Roborazzi screenshot verification | Done |
| 2. Mobile Insets & Gestures | Android `setSystemGestureExclusionRects` for edge drawing safety; iOS `SystemBarsHidden` (status bar & home indicator auto-hide); safe area padding validation | Planned |
| 3. Desktop Multi-Window | `DesktopWindowManager`, "Open in New Window" command & shortcuts in library, independent document windows with per-document `rememberDrawViewWindowState`, session flushing, macOS modified dot | Planned |
| 4. Mobile Multi-Window | Android `documentLaunchMode` & multi-window intent handling (tablet split-screen, DeX); iPadOS `WindowGroup(for:)` scene support | Planned |

### Decisions

* **Native window decoration over bespoke CSD on Windows/Linux**: Use standard OS windows with
  DWM dark mode integration rather than completely custom undecorated windows (`undecorated = true`)
  to ensure native snap layouts, system animations, accessibility, and resize borders are
  retained without reimplementing window manager protocols.
* **macOS Unified Titlebar via standard AWT properties**: Use `apple.awt.fullWindowContent` and
  `apple.awt.transparentTitleBar`, which preserve native window resizing, double-click to zoom,
  and native traffic light behaviors while letting Compose draw directly behind the titlebar.
* **Per-window `CommandHost`**: Each desktop window holds its own `CommandHost` instance,
  ensuring that keyboard shortcuts and menu bar actions always target the focused window's
  active screen and document session.

### Left for later

* macOS native tabs synchronization (`NSWindow.tabbingMode = preferred` programmatic grouping).
* Windows 11 Mica / Acrylic backdrop styling.
* Picture-in-Picture for audio lecture recording/playback on mobile.
