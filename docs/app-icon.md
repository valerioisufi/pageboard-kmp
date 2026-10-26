# Application icon on desktop platforms

The desktop applications of DrawView (PencilNotes and the sample application) show their brand icon
instead of the generic Java coffee cup icon across all desktop platforms: in the macOS Dock and
application switcher (both in development and when packaged), in the native window title bar and
taskbar on Windows and Linux, and in native packages (.dmg, .msi, .deb).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Desktop window icon**:
   * `pencilnotes-desktop` and `sample-desktop` use standard Compose `Window` without an `icon`
     parameter (`icon: Painter? = null`).
   * When opening secondary document windows in multi-window desktop mode (`PencilNotesDocumentWindow`,
     `SampleDocumentWindow`), no window icon is supplied either.
   * On **Windows** and **Linux**, Compose Desktop windows without an explicit icon display the
     default Java coffee cup in the title bar and taskbar entry.
2. **macOS Dock icon**:
   * On **macOS**, `Window(icon = ...)` only calls `Window.setIconImage`, which has no effect on the
     macOS Dock.
   * When running unpackaged during development (`./gradlew :pencilnotes-desktop:run` or
     `./gradlew :sample-desktop:run`), the macOS Dock displays the default Java Duke coffee cup
     icon unless set through `java.awt.Taskbar.getTaskbar().setIconImage(...)` or AppKit's
     `NSApplication.sharedApplication.applicationIconImage`.
3. **Native distribution packaging**:
   * Neither `pencilnotes/desktopApp/build.gradle.kts` nor `sample/desktopApp/build.gradle.kts`
     configures `iconFile` in `compose.desktop.application.nativeDistributions`.
   * Packaged builds (`packageDmg`, `packageMsi`, `packageDeb`) produce bundles with default generic
     icons.
4. **Application identities**:
   * **PencilNotes**: The visual identity defined in `PencilNotesApp.kt` (`PencilNotesMark`) is a
     squircle (`RoundedCornerShape(size * 14 / 40)`) in primary forest green (`Color(0xFF3E691A)`)
     containing the stylized pencil icon from `DrawViewIcons.Pencil` in light green
     (`Color(0xFFBDF292)`).
   * **DrawView Sample**: The visual identity defined in `drawview-ui` is `logo.webp`, showing the
     DrawView pencil emblem with a transparent background.

## Requirements

1. **macOS runtime Dock icon**:
   * When running in development via Gradle or unpackaged JVM, the macOS Dock and Cmd+Tab switcher
     must display the application icon immediately upon launch.
2. **Windows & Linux runtime window icon**:
   * All windows (the main library window and any independently opened document windows) must display
     the application icon in their title bar and taskbar entries.
3. **Packaged application distributions**:
   * Native distribution configurations in Gradle (`nativeDistributions`) must include platform-appropriate
     icon assets:
     - macOS: Apple ICNS (`.icns`) with standard sizes (16x16 up to 1024x1024, @1x and @2x),
       following the macOS icon grid.
     - Windows: Windows Icon (`.ico`) containing multi-resolution layers (16, 24, 32, 48, 64, 128, 256).
     - Linux: PNG icon (`.png`) formatted at 512x512.
4. **Visual fidelity**:
   * PencilNotes desktop icon must faithfully reproduce `PencilNotesMark`: `#3E691A` background squircle
     with `#BDF292` `DrawViewIcons.Pencil` vector.
   * DrawView Sample desktop icon must faithfully represent `logo.webp`.
5. **Architectural consistency & GRASP**:
   * Dock icon application on macOS belongs in `drawview-ui` (jvmMain) as a Pure Fabrication
     (`DesktopAppIcon` / `MacWindowHelper`), safe to invoke on any platform without breaking headless
     runs or non-macOS environments.
   * The ABI of published modules remains stable (`checkKotlinAbi`).

## Design

### Responsibilities and GRASP patterns

| Responsibility | Class / Component | Module | GRASP |
|---|---|---|---|
| Setting macOS host Dock icon at runtime via Taskbar / AppKit | `DesktopAppIcon` / `MacWindowHelper` | `drawview-ui` (jvmMain) | Pure Fabrication, Low Coupling |
| Providing application icon painter and AWT image for desktop windows | `DesktopAppIcon` / resource accessors | `drawview-ui`, `pencilnotes-shared` | Information Expert |
| Setting window icon for primary and document windows | `Window(icon = ...)` in `main.kt` | `pencilnotes-desktop`, `sample-desktop` | Controller |
| Configuring native package icon paths in Gradle build | `nativeDistributions` in `build.gradle.kts` | `pencilnotes-desktop`, `sample-desktop` | Information Expert |
| Generating pixel-perfect multi-resolution icon assets (.icns, .ico, .png) | Asset generation scripts / resources | `pencilnotes/desktopApp`, `sample/desktopApp` | Creator, High Cohesion |

### macOS Dock icon integration

On macOS, Java provides `java.awt.Taskbar.getTaskbar().setIconImage(Image)`. When supported, calling
this immediately sets the Dock icon of the running process. To ensure maximum compatibility and zero
failure risk:
1. `DesktopAppIcon.setDockIcon(Image)` verifies `Taskbar.isTaskbarSupported()` and
   `Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)` before applying.
2. In addition, `DesktopAppIcon.setDockIcon` safely degrades on headless environments or systems
   where the desktop taskbar is unavailable.
3. `DrawViewWindowDecor` or the application entry point invokes this early during startup before the
   first window appears.

### Window icon wiring

Compose Multiplatform's `Window` composable accepts `icon: Painter?`.
1. In `pencilnotes-desktop`: A shared `rememberPencilNotesIcon(): Painter` (or `painterResource`) is
   passed to both the Library window and every secondary document window.
2. In `sample-desktop`: The sample window passes `painterResource(Res.drawable.logo)` to its windows.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1. Icon asset generation | Generate multi-resolution icons (.icns, .ico, .png) for PencilNotes (`PencilNotesMark`) and DrawView (`logo.webp`) | Done |
| 2. Desktop runtime icon support | `DesktopAppIcon` in `drawview-ui` (jvmMain) setting macOS Dock icon and loading window icons | Done |
| 3. Application wiring | Wire window icons and macOS Dock setup in `pencilnotes-desktop` and `sample-desktop`; configure `nativeDistributions` in build scripts | Done |
| 4. Verification | Test runtime dock and window icons, verify ABI with `checkKotlinAbi`, run tests with `jvmTest` | Pending |

### Decisions

* **Vector rendering of `PencilNotesMark` for icon generation**: Rather than scaling a raster image,
  the master icon is rendered from the exact SVG path of `DrawViewIcons.Pencil` and the exact theme
  colors (`#3E691A` and `#BDF292`) on a 1024x1024 canvas with the standard macOS squircle margin (824x824
  icon on 1024x1024 canvas), and exported to `.icns` using Apple's `iconutil`.
* **Dock icon via `java.awt.Taskbar` with graceful degradation**: Standard Java SE API supported on
  macOS 10.14+ without needing custom JNI bindings, degrading safely if running in headless test
  environments.
* **Consistency across all open windows**: When opening secondary document windows in multi-window
  mode, the icon is passed to every `Window(...)` call so the window manager always associates the
  window with the application.

### Left for later

* Dynamic Dock icon badges (e.g. badge count for syncing status or active lecture recording).
