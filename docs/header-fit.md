# Bars that fit their window

The bars at the top of the library and of the editor are crowded where the window is narrow: the
name of PencilNotes is cut, the title of a document gets a few letters. The editor hides the status
bar also where a notch or an island keeps its room taken anyway, and on Android 10 the status bar
comes back for good after a menu is opened. On an Android tablet the group of the tools looks too
big. This document starts from the code as it is (main at 83c1c07c, 2026-10-31): the causes, then
the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

### The bar of the library

1. **`LibraryAppBar`** (`drawview-explorer`, `LibraryHeader.kt`) is a `Row` 56 dp high: the brand
   in a `Box` with `weight(1f)`, then the search (in the grid layout, 260 dp + 8 dp), the Trash and
   the actions of the application as `DesignIconButton`s. The brand is what is left after the rest.
2. **The buttons are 48 dp with a finger** (`InputCapabilities.minimumInteractiveSize`, given by
   `AdaptiveRoot` as `LocalMinimumInteractiveComponentSize`), 40 dp with a mouse.
3. **PencilNotes gives three actions** (join a shared note, the settings of the search, about) and
   the Trash makes four buttons. Its brand (`PencilNotesBrand`) is the mark (40 dp), 12 dp, and the
   wordmark in Cherry Bomb One at 28 sp, about 165 dp: about 217 dp in all.
4. **So the wordmark is cut**:

   | Layout | Width | Room for the brand | Cut until |
   |---|---|---|---|
   | List (under 600 dp) | 360 dp phone | 360 − 24 padding − 16 spacing − 4 × 48 = 128 dp | about 450 dp |
   | Grid (600–1000 dp) | 600 dp | 600 − 48 − 268 search − 20 − 4 × 48 = 72 dp | about 750 dp |
   | Panes (1000 dp and more) | — | the brand is in the pane of the navigation, 264 dp | never |

   The actions never leave the bar, whatever its width; the search never narrows.

### The top bar of the editor

5. **`EditorTopBar`** (`drawview-ui`, `EditorToolbar.kt`): back, the page grid, the title
   (`DocumentInfoSelector`), the tools when they fit in one bar, the actions of the extensions,
   "more". `ToolbarLayout` (pure, unit tested) decides where the tools go and whether the page grid
   keeps its button (`pageGridFits`), counting `TitleMinWidth` (160 dp) for the title.
6. **The actions of the extensions are never moved.** They are composables
   (`EditorExtension.toolbarActions`), measured after they are drawn (`onExtensionsWidth`). In
   PencilNotes they are three (the lecture, the export, the sharing): on a 360 dp phone the bar
   holds back, the three actions and "more", 5 × 48 = 240 dp plus the margins, and the title keeps
   about 100 dp, not its 160. The page grid is the only button that gives way.

### The system bars

7. **Two effects hide them, both on while a document is open in PencilNotes:**
   - `HideSystemBarsEffect` (`drawview-ui`, `FilePicker.*.kt`), called by `DrawScreen` always,
     the sample too;
   - `SystemBarsHidden(hidden)` (`drawview-explorer`, `platform/SystemBars.*.kt`), called by
     `DocumentLibrary` when `fullScreenEditor` (PencilNotes passes `true`).

   On iOS both replace `prefersStatusBarHidden` and `prefersHomeIndicatorAutoHidden` of the same
   view controller, each with its own global flag: the one installed first answers, the other
   flag is ignored.
8. **They hide the status bar on any device.** On Android `enableEdgeToEdge` lets the content go
   into the cutout, and the editor pads its top by `WindowInsets.safeDrawing`, which holds the
   cutout: with a notch or a punch hole at the top, the band of the status bar stays empty, and
   the clock and the battery are gone for nothing. On an iPhone with a notch or the Dynamic Island
   the safe area keeps its height without the status bar: the same.
9. **On Android 10 the status bar comes back after a menu.** Under Android 11
   `WindowInsetsControllerCompat.hide` sets the old flags of `systemUiVisibility` on the view of
   the window. A menu (`DropdownMenu`, the "more" of the editor) is a window of its own, focused,
   without those flags: the system shows the bars and clears the flags of the window below. When
   the menu closes, nothing hides them again: the effect ran once, when the editor opened.

### The tools on a tablet

10. **A tool is the same size on every device with a finger**: `ToolButton` 44 dp (rounded 16 dp,
    its icon 24 dp) inside the 48 dp of the input, `BarButton` (undo, redo, imports) a 40 dp
    `IconButton` with a 22 dp icon inside 48 dp. The group (`ToolGroup`) adds 8 dp each side and
    4 dp above and below: about 56 dp high, 12 buttons and 2 dividers ≈ 620 dp wide.
11. **On a tablet with a low density bucket** (many Android tablets report 600–800 dp on their
    short side for 8–10 inches) a dp is larger than on a phone, and these sizes, made for a thumb,
    look big. To check on the tablet of the user (`adb shell wm size`, `wm density`) before
    deciding.

## Requirements

1. The name of the application is whole wherever the library shows it, down to the narrowest
   phone (320 dp); what does not fit beside it goes into a menu, never off the bar.
2. The search of the grid layout narrows before an action leaves the bar.
3. The title of a document keeps `TitleMinWidth` in the top bar of the editor: the actions of the
   extensions give way before it, still reachable in one tap more.
4. The status bar is hidden only where hiding it gives its room to the editor: not with a cutout
   at the top edge (Android), not with a notch or an island (iOS). The navigation bar and the home
   indicator stay as now.
5. The bars hidden stay hidden after a menu, a popover or a dialog closes, on Android 10 too.
6. One effect hides the system bars, in one module, with one state on iOS.
7. The group of the tools on a tablet has the size of a toolbar for a pen, not for a thumb, the
   targets staying at least 44 dp (the minimum of Apple, and above the 40 dp of a mouse).
8. No change to a stored format or to the protocol. Public API changes only where a phase says so,
   recorded in `api/`.

## Design

### The bar of the library

| Responsibility | Assigned to | Pattern |
|---|---|---|
| Which actions stay in the bar and how wide the search is, from the width of the bar, of the brand, of a button, the number of actions and the bounds of the search | `LibraryBarLayout.fit` → `LibraryBarFit(searchWidth, actionsInBar, overflow)` (pure, unit tested) | Pure Fabrication |
| Measuring the brand at its own width (its max intrinsic width) and placing the pieces as decided | `LibraryAppBar`, a `Layout` instead of the `Row` | Information Expert (the bar knows its children) |
| The actions left out, in a "more" button with a `CommandMenu`-like menu of their icons and labels | `LibraryAppBar` (`LibraryAction` already has icon and label) | High Cohesion |

The order of giving way: the search narrows from 260 to 200 dp, then the actions leave from the
last one (about, then the settings of the search, then joining), then the Trash, into one "more"
button. If the brand still does not fit (a window under ~300 dp) it is ellipsized, as today.
`LibraryAppBar` keeps its signature: no change to the public API.

### The top bar of the editor

| Responsibility | Assigned to | Pattern |
|---|---|---|
| Whether the actions of the extensions fit beside a title of `TitleMinWidth` | `ToolbarLayout.extensionsFit(width, button, extensionsWidth, buttons, decorWidth)` (pure, unit tested) | Pure Fabrication |
| Showing them in the bar, or behind one button ("Extensions", its icon a puzzle or a grid of dots) that opens them in a small popover row | `EditorTopBar` | Information Expert |
| Measuring them where they are not shown | a `SubcomposeLayout` measuring `toolbarActions` without placing it (their width is known before they are drawn, instead of `onExtensionsWidth` after) | Indirection |

The extensions keep their API (`toolbarActions` stays a composable): the same composables are shown
in the popover, so the lecture keeps its time of recording and its state. `EditorExtension` is not
touched (see "Completion and suggestions while writing" in `CLAUDE.md`: its shape is decided
separately). The page grid gives way first, as today, then the extensions.

### The system bars

| Responsibility | Assigned to | Pattern |
|---|---|---|
| Whether hiding the status bar gives room: Android, no cutout on the top edge of the window (`WindowInsetsCompat.Type.displayCutout()` top = 0, read again when the insets change, so a rotation that moves the cutout to the side hides it); iOS, the top of the safe area of the window without the status bar is 0 | `ImmersiveSystemBars` (`drawview-ui`, `platform/SystemBars.*.kt`, expect/actual) | Information Expert, Protected Variations |
| Hiding the navigation bar (Android) and the home indicator (iOS) as now | the same | High Cohesion |
| Hiding them again when the window gets the focus back (Android: `ViewTreeObserver.OnWindowFocusChangeListener`), so a menu, a popover or a dialog never leaves them shown | the Android actual | Information Expert |
| The one effect of the editor, asked by `DrawScreen` with a parameter `immersive` (default `true`, as today) | `DrawScreen` / `EditorScreen` | Controller |
| `DocumentLibrary(fullScreenEditor)` passing it to the editor instead of an effect of its own; `SystemBarsHidden` of `drawview-explorer` removed | `DocumentLibrary` | Low Coupling |

The iOS actual keeps one flag and installs its overrides once. The status bar shown in the editor
on a device with a notch takes the colors of the bar of the editor (light or dark icons as the
theme), as in the library.

### The tools on a tablet

| Responsibility | Assigned to | Pattern |
|---|---|---|
| The size of the tools: compact (phone, floating), regular (a stylus or a tablet: 40 dp buttons drawn 36 dp, 20 dp icons, 44 dp targets), pointer (40 dp, as today) | `ToolbarDensity` from `AdaptiveInfo` (input and size class) (pure) | Pure Fabrication |
| Using it | `ToolButton`, `BarButton`, `ToolGroup`, `ToolbarLayout.ToolWidth` | Information Expert |

The numbers are to be confirmed on the tablet of the user, with a screenshot of the bar before and
after. The phone keeps its sizes (the thumb), the desktop its sizes (the mouse).

## Plan

| Phase | Content | Status |
|---|---|---|
| 1. System bars | `ImmersiveSystemBars` in `drawview-ui` (Android, iOS, JVM no-op): the status bar hidden only where it gives room, hidden again on focus; `DrawScreen(immersive)`, `DocumentLibrary.fullScreenEditor` passed through, `SystemBarsHidden` removed; ABI updated. Checked on the Pixel 9 emulator (punch hole: status bar kept in portrait, hidden in landscape), on an Android 10 emulator (menu opened and closed), on the iPhone and iPad simulators | proposed |
| 2. Bar of the library | `LibraryBarLayout.fit` with its tests; `LibraryAppBar` as a `Layout` with the "more" menu; screenshots of the library at 320, 360, 412, 600, 700, 840 dp recorded | proposed |
| 3. Top bar of the editor | `ToolbarLayout.extensionsFit` with its tests; the extensions measured before they are drawn, in the bar or behind one button; screenshots of the editor with three extensions at 320, 360, 412 dp | proposed |
| 4. Tools on a tablet | `ToolbarDensity`; the sizes measured on the tablet of the user first; `ToolbarLayout` counting them; screenshots of the editor on the reference tablets | proposed, waits for the measures of the tablet |

## Decisions

Revertible, taken alone:

* **The actions of the library go into a menu, not the wordmark into the mark.** The brand is a
  composable of the application, the library cannot ask it for a short form without a new API.
  A brand with a short form (only the mark) is left for later.
* **The extensions of the editor stay composables**, shown elsewhere when they do not fit, not
  turned into commands: their shape is a decision of its own (see `CLAUDE.md`).
* **The navigation bar stays hidden** where the status bar is kept: it gives room on every device
  with buttons, and comes back with a swipe.
* **An Android 10 emulator** (system image API 29, about 1 GB) is needed to check phase 1; it is
  downloaded only with the user's yes.

## Left for later

* A short form of the brand of the application (the mark alone) for the narrowest windows.
* The extensions as commands, with a place in the menus and in the menu bar.
* Hiding the bars while writing (focus mode), already in "Later" of `docs/adaptive-ui.md`.
