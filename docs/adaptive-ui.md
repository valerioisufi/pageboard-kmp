# Adaptive interface

The applications built on DrawView must look and work well on every platform and every kind of
device: phones, tablets with a stylus, desktop windows of any size, with touch, stylus, mouse,
trackpad and keyboard. The interface adapts along three independent axes, never by asking which
platform it runs on:

1. **Space**: the size class of the window (or of the part of it a component has), not the kind
   of device: an iPad in Split View is a phone, a desktop window made narrow too.
2. **Input**: what the user has at hand (stylus, finger, precise pointer that hovers, hardware
   keyboard): an iPad with a Magic Keyboard wants the shortcuts, a Windows touch laptop large
   targets.
3. **Conventions of the platform**: the menu bar of macOS (and of iPadOS 26), Cmd or Ctrl, the
   back of Android, the gestures of the Apple Pencil.

Like the other features, the adaptivity belongs to the modules: an application built on
`DocumentLibrary` gets it without code of its own, the extensions (lecture, collaboration,
export) contribute content and let the editor decide where it goes.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* **Editor** (`DrawScreen`): the same layout at every size. A top bar 44 dp high (back, page grid,
  document information, actions of the extensions, drawing mode, more options) and, in drawing
  mode, a second row 48 dp high scrolled horizontally (undo, redo, pen, highlighter, text markup,
  eraser, text, lasso, pan, import PDF, import image), both always at the top. `ToolButton` is
  36 dp: under the 48 dp of a finger, fine for a stylus or a mouse. The settings of a tool are a
  popup of its button (tap on the selected tool, or long press).
* **Each action is written where it is shown**: undo is a `ToolButton` calling
  `controller.undo()` in `DrawScreen`, and exists nowhere else; there is no description of the
  actions that a menu, a shortcut or a tooltip could use.
* **Keyboard**: no shortcut. Escape works through the system back (`NavigationBackHandler` of the
  editor and of the library), Ctrl / Cmd with the wheel zooms the canvas. `SelectionManager` has
  `copySelection`, `cutSelection`, `pasteSelection`, `deleteSelection`; `DrawViewController` has
  `zoomIn`, `zoomOut`: reachable only by touch.
* **Canvas**: already aware of the input (`CanvasGestureHandler`): the first stylus contact
  switches to stylus-only mode, the right button opens the context menu, the middle button pans,
  the hover shows the eraser cursor and the preview of a pending placement, the trackpad of macOS
  scrolls and pinches with its phases (`libtrackpad_bridge`).
* **Extensions** (`EditorExtension`: `toolbarActions`, `sidePanel`, `canvasOverlay`): the lecture
  decides itself where its panel goes (`panelBeside()`, 600 dp of the window: `LecturePanel`
  beside the canvas, else `LectureSheet` from the bottom through `canvasOverlay`). Collaboration
  and export contribute a toolbar button each.
* **Sheets**: the configurators of the page and of the document background, the details of a
  file of the library are `ModalBottomSheet`s at every size: on a desktop window they rise from
  the bottom of a 27" screen.
* **Library** (`ExplorerScreen`, `FileList`): a `LazyColumn` of rows with an icon of the type
  (no thumbnail) at every width; long press selects; no right-click menu, no multiple selection
  with Cmd / Shift, no drag and drop. `TileRenderer.renderThumbnail` already draws a page as a
  thumbnail (used by the page grid).
* **Theme**: the color schemes of DrawView and of PencilNotes do not set the `surfaceContainer*`,
  `surfaceBright` and `surfaceDim` roles: where the dynamic colors of Android 12+ are missing
  (desktop, iOS, older Android) dialogs, menus, sheets and cards take the neutrals of the Material
  baseline, slightly purple, beside the background of the palette. No density for a precise
  pointer: the desktop has the sizes of a phone.
* **No tooltip, no pointer icon** (except the eraser cursor of the canvas).
* **Platforms**: the desktop window opens at 1280×860 without minimum size, menu bar or saved
  state; the iOS app targets iPhone and iPad (`TARGETED_DEVICE_FAMILY = 1,2`); the Android
  activity handles the changes of size itself (`configChanges`), so resizing it in split screen
  or in a desktop window does not recreate it.
* **Tests**: no test of the interface; any change of the layouts is verified by hand.

## Design

### Principles

* **The expert of the space is the container.** The structure of a screen (one pane or two, a
  sheet or a side panel) follows the size class of the window; a component (the toolbar, a
  card) follows its own constraints, because a side panel can make the canvas narrow in a wide
  window.
* **The input is observed, not guessed.** A stylus, a mouse or a key seen once say what the user
  has; the platform only gives the first guess.
* **An action is defined once** and presented in many ways: toolbar button, menu bar item,
  shortcut, context menu entry, tooltip.
* **Extensions give content, the editor places it.** The lecture describes its panel; the editor,
  which knows the space and the other panels, decides beside or below.
* **One interface, many layouts.** No screen is forked per platform: the variations are
  polymorphic pieces chosen by the adaptive information.

### Adaptive information

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Size class of a width and a height (compact < 600 dp, medium < 840 dp, expanded; compact height < 480 dp) | `WindowLayout.of(width, height)` (`drawview-ui`) | Information Expert, Pure Fabrication |
| Size class of the window, updated when it is resized | `currentWindowLayout()`, from `LocalWindowInfo.containerSize` | Protected Variations (the source can become `material3-adaptive` without touching the screens) |
| What the user has at hand: stylus, precise pointer, hover, hardware keyboard | `InputCapabilities` (`drawview-ui`), updated by the pointer and key events seen at the root of the application (`Modifier.observeInput`, initial pass, nothing consumed) | Information Expert (the events know their type), Low Coupling |
| First guess of the input, before any event | `platformInputHints()` (expect / actual: the desktop a pointer and a keyboard; Android `Configuration.keyboard` and the input devices; iOS `GCKeyboard`) | Polymorphism, Protected Variations |
| Conventions of the platform: primary modifier (Cmd on Apple, Ctrl elsewhere), where the menus live (menu bar or in the window), names of the keys | `PlatformConventions` (expect / actual, `drawview-ui`) | Polymorphism, Protected Variations |
| Give the three to every composable | `AdaptiveInfo` (window layout, input, conventions) in `LocalAdaptiveInfo`, provided by `AdaptiveRoot`: `DocumentLibrary` and `EditorScreen` declare one, the application may declare one around its dialogs (the inner ones then only show their content); `currentAdaptiveInfo()` outside of a root | Indirection, Low Coupling (the theme belongs to the application: PencilNotes has its own) |
| Density by input: minimum interactive size 48 dp with touch, 40 dp with a precise pointer only | `InputCapabilities.minimumInteractiveSize`, given by `AdaptiveRoot` as `LocalMinimumInteractiveComponentSize`; `ToolButton` drawn 36 dp and as large as it | Information Expert (the input knows what it can reach) |
| Complete color schemes (every role, `surfaceContainer*` included) | `Color.kt` of DrawView and of PencilNotes, generated from their seed | Information Expert |

`isStylusOnlyMode` stays in the controller: it is a behavior of the editor (fingers pan), chosen
by the user; `InputCapabilities.hasStylus` only says that a stylus exists.

### Commands

| Responsibility | Class (module) | GRASP |
|---|---|---|
| An action: label, icon, group of the menus (File, Edit, View, Insert, Format, Tools, Help) and section, shortcuts, enabled, checked, perform | `Command` (`drawview-ui`) | Information Expert (it asks its expert: `canUndo`, the selection, the mode of the editor) |
| A combination of keys, written once for every platform (`primary + Z`: ⌘Z or Ctrl+Z), and its label (⇧⌘Z, Ctrl+Shift+Z) | `Shortcut` (not `KeyShortcut`, the class of the accelerators of Compose Desktop) with `PlatformConventions.shortcutModifier` | Information Expert, Protected Variations |
| The commands of the editor: close, undo / redo, cut, copy, paste, delete (of the lasso, of the text of the PDFs, of a mark), zoom, page grid, stylus only, add a page, import, document background, drawing mode, the tools, about | `EditorCommands` (`drawview-ui`), created by `DrawScreen` with its pickers and dialogs (`EditorScreenActions`) | Creator, Controller |
| The camera commands missing: fit the width, next / previous page, go to a page | `DrawViewController` (with `CameraPhysicsEngine`) | Information Expert |
| The commands of the library: new note, new folder, select all, rename, move, delete, enclosing folder, sort, about; those of an item (`forItem`: open, rename, move, delete, the actions of the application) | `LibraryCommands` (`drawview-explorer`), on `FileExplorerViewModel` (`deleteFiles`, `moveFiles` of any items) | Creator, Information Expert |
| The dialogs the commands open in the library (new item, rename, move, delete, about), one at a time | `LibraryDialog`, shown by `ExplorerScreen` (no longer by the sheet and the bar of the selection, each with its own) | High Cohesion |
| The commands of an extension: "Export…", "Share…", the panel of the lecture | `EditorExtension.commands`; the state of their dialog in the extension (`DocumentExportUi`, the extension of `Collaboration`), the dialog drawn over the canvas, so that the button and the command open the same | Low Coupling, Protected Variations |
| The commands of the screens shown, for the presenters outside of them (menu bar) and the shortcuts | `CommandHost` (`drawview-ui`): the screens register their commands while shown (`RegisterCommands`), the screen in front first; `dispatch` runs the enabled command of a key | Pure Fabrication, Indirection |
| The root of the commands: the host of the application (or its own), the keys the focused element leaves (a text field keeps its own ⌘Z), the focus when nothing has it (at first, when touched) | `CommandRoot`, declared by `DocumentLibrary`, `EditorScreen` and the application around its dialogs (the inner ones then only show their content) | Controller |
| Show the commands | `ToolButton(command)` and `CommandTooltip` / `HoverTooltip` (label and shortcut where a precise pointer rests: the long press stays to the controls), `CommandMenu` (a context menu; `Modifier.onSecondaryClick`), `DrawViewMenuBar` (`jvmMain`: a Compose `MenuBar`, the window running the shortcuts with `Window(onKeyEvent = host::dispatch)`), the menu of iPadOS and the shortcut helper of Android (see [Platforms](#platforms)) | Polymorphism |

`toolbarActions` stays for the content that is not an action (the time of a recording, the
avatars of the participants), next to the commands of the extension.

### Panels and sheets

| Responsibility | Class (module) | GRASP |
|---|---|---|
| A panel contributed to the editor: title, open state, closing, actions of its header, content | `EditorPanel` (`drawview-ui`), listed by `EditorExtension.panels` (replacing `sidePanel` and the sheet of the lecture in `canvasOverlay`) | Low Coupling, Protected Variations |
| Where the open panels go, by the width of the editor (not of the window): beside the canvas when it keeps 360 dp (a panel is 380 dp), several side by side or in tabs, else rising from the bottom over it | `PanelPlacement.of(width, open)` (pure, unit tested) | Pure Fabrication |
| The frame of the editor under its bars: the canvas and the panels where `PanelPlacement` puts them, their header (title, actions, close) and tabs, the handle of the sheet (taller, shorter, closed) | `EditorScaffold` (`drawview-ui`, the frame of `DrawScreen`) | Information Expert (it knows the space and every panel), Controller |
| A sheet of the application (configurators of the backgrounds, details of a file, settings of the lecture): bottom sheet in a compact window, dialog in a wider one; title, content and actions the same in both | `AdaptiveSheet` (`drawview-ui/adaptive`) | Polymorphism |

The lecture loses `panelBeside()` and `LectureSheet`: it describes its panel once. The effects
that follow the editor (the recording seeing the pages) and the dialogs of an extension are in
`canvasOverlay`, always composed with the editor whether the panel is shown or not.

### Editor

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The bars of the editor: the top bar (back, page grid, title, the tools when they fit, the actions of the extensions, drawing mode, "more"), the bar of the tools, the floating tools | `EditorTopBar`, `EditorTools`, `FloatingTools` (`drawview-ui`, `EditorToolbar.kt`), replacing the two rows of `DrawScreen` | High Cohesion |
| Where the tools go by the width of the editor, the height of the window and the size of the buttons of the input; which buttons of the top bar fit beside the title | `ToolbarLayout.arrange` → `ToolbarArrangement` (one bar, two bars, floating tools), `ToolbarLayout.pageGridFits` (pure, unit tested) | Pure Fabrication |
| The commands left out of the bars | the "more" menu, a `CommandMenu` of sections (the page grid and the imports when they do not fit, adding a page, fit width, stylus only, background, about) | Polymorphism |
| The settings of a tool (sizes, presets, colors): popup by its button | the current composables (`InkToolButton`, `TextToolSettings`…), moved with the tools | Information Expert |
| Cursor of the canvas on hover: the dot of the pen as large and as colored as its stroke, the circle of the eraser, the system cursor of the tool (crosshair, I-beam, hand) | `CanvasGestureHandler` and `CanvasLiveState.inkCursor` (they know the tool, the mode and the pointer), `pointerHoverIcon` of `DrawCanvas` | Information Expert |
| The camera commands: fit the width, next / previous page | `DrawViewController.fitWidth`, `showPage`, `showNextPage`, `showPreviousPage` | Information Expert |

### Library

| Responsibility | Class (module) | GRASP |
|---|---|---|
| How a folder is shown by the width of the library: a list, a grid of cards, the panes (folders, grid, details) | `LibraryLayout.of(width)` (`drawview-explorer`), used by `ExplorerScreen` | Polymorphism, Pure Fabrication |
| Thumbnail of a document: its first page copied on the thread of the document, drawn on another one document at a time, kept in memory (the last 48) and on disk with the date of its change, drawn again when the document changes or is shown larger | `DocumentThumbnails` (`drawview-explorer`), through `OpenDocuments` and `TileRenderer.renderThumbnail` of a page copied; `encodePng` moved to the core beside `decodeImageBitmap` | Pure Fabrication, Information Expert (the renderer draws, the library knows the date of the change) |
| A card of the grid (first page or folder, name, date, details) | `FileGridItem`, `FileGrid` | High Cohesion |
| What a pointer does on an item of the list or the grid: click opens (or selects in selection mode), ⌘ / Ctrl click adds to the selection, Shift click extends it, long press selects, right click opens its menu | `Modifier.fileItemInteraction`, `FileContextMenu`; the selection by `FileExplorerViewModel` (`extendSelection` from the item last selected, `selectOnly`) | Information Expert |
| The pane of the folders (the root, its folders, the notes opened since they were made) and the details of the item selected alone | `LibraryNavigationPane` (`FileExplorerViewModel.showFolder`, `rootFolders`), `LibraryDetailsPane` (the same `FileDetails` as the sheet) | High Cohesion |
| Items dragged with a mouse onto a folder (of the list, of the grid, of the pane, the root) | `LibraryDrag` (where the folders are, which one is under the pointer), `Modifier.draggableItem`, `Modifier.dropTarget`, `LibraryDragShadow`; `FileExplorerViewModel.moveFiles` | Pure Fabrication, Controller |
| Files dropped from the system (desktop): onto the editor imported, onto the library a note of them | `Modifier.fileDropTarget` (`drawview-ui/platform`, expect / actual); `MediaManager.importFile` (the kinds it imports, a PDF replacing the empty page of a note just made); `DocumentEditorViewModel.createAndOpenDocument(then)`, `FileExplorerViewModel.availableName` | Information Expert, Protected Variations |

### Layouts

| | Compact (phone; any window under 600 dp) | Medium (tablet in portrait, split screen) | Expanded (tablet in landscape, desktop) |
|---|---|---|---|
| Library | list (as now) | grid of thumbnails | navigation pane (root, folders, recent) + grid; the details of a file beside |
| Editor toolbar | top bar with back, title and overflow; the tools in a floating toolbar at the bottom, near the thumb | one bar when it fits, else two | one bar; on the desktop also the menu bar and the shortcuts |
| Panels (lecture…) | from the bottom over the canvas | beside the canvas when it keeps its minimum width, else from the bottom | beside the canvas |
| Sheets | bottom sheet | dialog | dialog or side pane |
| Compact height (phone in landscape) | the top bar on one row, the floating tools at the bottom | | |

Input changes the details, not the structure: tooltips, hover cursors and right-click menus with
a precise pointer, the shortcuts shown in menus and tooltips with a keyboard, the smaller density
with a pointer only.

### Platforms

| Platform | What it adds | Where |
|---|---|---|
| macOS | Menu bar from the commands; toolbar in the title bar (`apple.awt.fullWindowContent`, transparent title bar); size, position and maximized state of the window kept; minimum size | `DrawViewMenuBar`, `rememberDrawViewWindowState` (`drawview-ui`, `jvmMain`), `pencilnotes-desktop` |
| Windows, Linux | Ctrl shortcuts; the menu bar in the window; the same window state | as above |
| iPadOS | Shortcuts of a hardware keyboard (Compose delivers the key events); the list of shortcuts while Cmd is held and the menu bar of iPadOS 26 (`UIKeyCommand`, `UIMenuBuilder`, from the commands); Apple Pencil: double tap and squeeze following the preference of the system (`UIPencilInteraction`), hover of the Pencil where Compose delivers it; Stage Manager and Split View by the size classes | `iosMain` of `drawview-ui` (a bridge the app installs), `pencilnotes/iosApp` |
| Android | Shortcuts of a keyboard and their list (`onProvideKeyboardShortcuts`); the stylus button (eraser while held); stylus hover; split screen and desktop windows by the size classes | `androidMain` of `drawview-ui`, `pencilnotes-android` |

The gestures of the Pencil and of the stylus button are commands too (`PencilAction`: switch to
the eraser, to the previous tool, show the palette): the platform tells only which action the
user chose.

### Reference configurations

| Name | Size (dp) | Classes |
|---|---|---|
| Phone | 411×891 | compact |
| Phone landscape | 891×411 | expanded width, compact height |
| Tablet portrait | 800×1280 | medium |
| Tablet split screen | 640×800 | medium |
| Tablet landscape | 1280×800 | expanded |
| Desktop narrow | 720×800 | medium |
| Desktop | 1440×900 | expanded |

Each with the input profiles touch, stylus, pointer + keyboard, in light and dark. The screenshot
tests (JVM, where a size is set at once) draw the library, the editor, the panels and the sheets
in these configurations; the pure pieces (`WindowLayout`, `ToolbarLayout`, `ShortcutDispatcher`,
`InputCapabilities`) have unit tests.

### Modules

```
drawview-ui        adaptive/ (WindowLayout, InputCapabilities, PlatformConventions, AdaptiveInfo),
                   command/ (Command, KeyShortcut, CommandHost, ShortcutDispatcher, CommandButton,
                   CommandMenu), EditorScaffold, EditorPanel, AdaptiveSheet, EditorToolbar,
                   ToolbarLayout; jvmMain: DrawViewMenuBar, window state; iosMain / androidMain:
                   menus and shortcuts of the platform, Pencil and stylus button
drawview-core      cursors of the canvas, camera commands of DrawViewController
drawview-explorer  LibraryCommands, LibraryLayout, DocumentThumbnails, pointer selection, drag and drop
drawview-lecture,  commands and panels instead of toolbar buttons, sidePanel and their own sheet
drawview-sync-ui,
drawview-export
pencilnotes        menu bar and window state (desktop), bridge of the menus and of the Pencil (iOS)
```

## Plan

| Phase | Content | Status |
|---|---|---|
| 0. Foundations | Complete color schemes (DrawView, PencilNotes); `WindowLayout`, `InputCapabilities`, `PlatformConventions`, `AdaptiveInfo` given by `AdaptiveRoot`; density by input; screenshot tests on the reference configurations (current state as the first reference); the lecture on `WindowLayout` | done |
| 1. Commands | `Command`, `Shortcut`, `CommandHost`, `CommandRoot`; `EditorCommands`, `LibraryCommands`, commands of the extensions; tooltips with the shortcut; right-click menus in the library; menu bar of the desktop | done |
| 2. Panels and sheets | `EditorPanel`, `EditorScaffold`; `AdaptiveSheet` for the configurators, the details and the settings; the lecture moved | done |
| 3. Editor | `EditorToolbar` and `ToolbarLayout`; floating tools on the phone; one bar when it fits; cursors of the canvas; camera commands (fit, pages) | done |
| 4. Library | `DocumentThumbnails`; grid; navigation pane; details beside; pointer selection; drag and drop | done |
| 5. Platforms | Window state and minimum size of the desktop; the Apple Pencil (double tap, squeeze); the stylus button erasing; the shortcut list of Android | done |
| Later | The toolbar in the title bar of macOS; the menu bar and the shortcut list of iPadOS; files dropped on Android and iOS; a document per window (desktop, iPadOS); postures of the foldables; toolbar at the side for the left-handed; focus mode (the bars hidden while writing) | |

Each phase ends with its screenshots compared to the previous ones and a check on real devices:
an iPad with a Pencil, an Android tablet with a stylus, a phone, a Mac with a trackpad.

### Phase 0 results

* **Colors**: the color schemes of DrawView and of PencilNotes set every role. The surfaces are
  the tones of the neutral palette that made the colors already there (hue of the seed, chroma 4:
  its tones 10, 20, 90, 95 and 99 are those of the schemes), the fixed roles the tones 90, 80, 10
  and 30 of each palette. Dialogs, menus and sheets no longer take the purple neutrals of Material
  on the desktop, on iOS and on Android before 12.
* **`drawview-ui/adaptive`**: `WindowLayout` (size classes only, so that a window being resized
  changes the screens only when a class changes), `InputCapabilities` with `InputHints` and
  `Modifier.observeInput` (a key counts when it comes from a hardware keyboard: the soft keyboards
  of Android send some keys too), `PlatformConventions` (the modifier of the shortcuts),
  `AdaptiveInfo`, `AdaptiveRoot`, `ReferenceWindow`. The hints of the platform: a pointer and a
  keyboard on the desktop; the configuration and the input devices on Android; a touch screen and
  the Pencil-only drawing of the system on iOS.
* **`AdaptiveRoot`** is declared by `DocumentLibrary`, `EditorScreen` and PencilNotes
  around its dialogs (not by `DrawViewTheme`: PencilNotes has a theme of its own). It gives the
  minimum interactive size of the input to Material and to `ToolButton`, drawn 36 dp and taking
  48 dp for a finger, 40 dp for a pointer.
* **The lecture** asks `WindowLayout` whether its panel goes beside the canvas (the same 600 dp).
* **Screenshot tests** (`AdaptiveScreenshotTest`, `drawview-explorer`, Roborazzi): the library
  and the editor in the seven reference windows with their input, the phone and the desktop in
  the dark theme too, the background configurator on a phone, a tablet and a desktop; 21 images
  in `drawview-explorer/screenshots`, the same at every run (fixed clock of `KeyValueDocumentStore`,
  US locale, UTC). `recordRoborazziJvm` records them, `verifyRoborazziJvm` fails when the interface
  differs. They show the starting point of the next phases: the list of a phone on a 1440 dp
  window, the tools cut on a phone, the bottom sheet rising in the middle of a desktop.
* **The canvas asks for frames only while it moves.** Its loop of frames ran at every frame of the
  display while a document was open, at rest too: the main thread of a tablet woke up 60 to 120
  times a second during a whole lecture, and a test never saw the interface idle. The camera
  counts the animations it starts (`CameraPhysicsEngine.animationStarts`, a fling or a bounce
  after `onRelease` and `restoreToBounds`), the auto-scroll of a drag is observable, and the loop
  waits for one of them. `TileManager.isRasterizing` tells the tests when the pages are drawn.
* Tests: the size classes, the reference windows, the input learned and kept, the minimum size by
  input (`AdaptiveInfoTest`); the start of the animations of the camera.

### Phase 1 results

* **The commands** (`drawview-ui/command`): `Command`, `Shortcut`, `CommandHost`, `CommandRoot`,
  `RegisterCommands`, `CommandTooltip` / `HoverTooltip`, `CommandMenu`, `Modifier.onSecondaryClick`;
  `DrawViewMenuBar` on the desktop. A command is one object for every presenter: the bars run it
  and show its tooltip, the menu bar lists it with its state and shortcut, the keys run it.
* **The editor** (`EditorCommands`): its buttons run its commands (close, page grid, undo, redo,
  imports) and show their tooltips, the tools too (their click still opens their settings when
  chosen again); the bar of the selection shows the tooltips of cut, copy and delete. The tools
  have single keys (P pen, H highlighter, U text markup, E eraser, T text, L lasso), left to the
  text being written; delete removes the lasso selection or the selected mark of a PDF, copy
  copies the selected text of the PDFs too.

  | Command | Shortcut |
  |---|---|
  | Close the document | ⌘W |
  | Undo, redo | ⌘Z, ⇧⌘Z (also ⌘Y) |
  | Cut, copy, paste, delete | ⌘X, ⌘C, ⌘V, ⌫ (also ⌦) |
  | Zoom in, out | ⌘+ (also ⌘=), ⌘- |
  | Page grid | ⌘G |
  | Import PDF, image | ⇧⌘P, ⇧⌘I |
  | Export | ⇧⌘E |

  (Ctrl in place of ⌘ on Windows, Linux and Android.)
* **The library** (`LibraryCommands`): ⌘N new note, ⇧⌘N new folder, ⌘A select all, ⌫ delete
  the selection (asking first), ⌘↑ enclosing folder; the sort options as checked items of the
  View menu. A click of the secondary button on an item opens its menu (open, rename, move,
  delete, the actions of the application, e.g. export); its sheet lists the same commands. The
  dialogs are one per screen (`LibraryDialog`), the view model deletes and moves any items
  (`deleteFiles`, `moveFiles`, leaving them out of the selection).
* **The extensions**: "Export…" (⇧⌘E) and "Share…" in the File menu, the panel of the lecture as
  a checked item of the View menu; their buttons show the same commands.
* **The applications** declare `CommandRoot(host)` around their screens: PencilNotes and the
  sample take the host as a parameter, given on the desktop to `DrawViewMenuBar` and to
  `Window(onKeyEvent = { host.dispatch(it) })` (the menus of macOS in the bar of the system). A
  key the content or the window handles is consumed by Compose, so the accelerators of the menu
  never run a command twice.
* Tests: the shortcuts written and matched on both conventions, the host running the enabled
  command of the screen in front, every key of the commands named and every shortcut running
  one command (`CommandTest`); the commands of the editor through their experts; the library
  (selection, an item, sorting, deletion leaving the selection: `LibraryCommandsTest`); the keys
  pressed on the interface (`ShortcutsTest`: ⌘N opens the new note, E the eraser, ⌘G the page
  grid); two screenshots more on the desktop (the menu of a note, the tooltip of a button).
  `AdaptiveRoot` takes the conventions too: the screenshots write the shortcuts as on a Mac on
  every system.
* Not verified here: the menu bar of the system (a window is needed) and a hardware keyboard on
  an iPad or an Android tablet (the root of the commands takes the focus at first and when
  touched).

### Phase 2 results

* **The panels** (`EditorPanel`, `EditorScaffold`, `PanelPlacement`): an extension gives what its
  panel shows, its title and the actions of its header; the editor places it by the width it has
  (the expert of the space is the container: a panel opened in a wide window of a narrow editor
  goes to the bottom too). In the reference windows one panel goes beside the canvas on the
  tablets (portrait and landscape), the phone in landscape and the desktop; from the bottom on
  the phone, in split screen and in a narrow desktop window. Two panels go side by side on the
  desktop and in a tablet in landscape, in tabs on a tablet in portrait. The side panes keep clear
  of the bars and the cutouts of the system (a phone in landscape).
* **The lecture** gives `lecturePanel` (its settings in the header) and keeps its effect
  (`LectureEditorEffects`: the recording sees the pages while the editor is open) and its settings
  (`LectureSettings`, asked by the header and by a recording without a key) in `canvasOverlay`.
  The settings are an `AdaptiveSheet`: a sheet on a phone, a dialog elsewhere.
* **The sheets** (`AdaptiveSheet`): the configurators of the page and of the document background,
  the details of a file of the library, the settings of the lecture. On a desktop and a tablet
  they are dialogs, with all their content in sight (the configurator rose from the bottom of a
  1440 dp window with half of it hidden). The collaboration had no sheet nor panel: its dialogs
  stay alert dialogs.
* Tests: the placement of one and two panels, the reference windows (`PanelPlacementTest`); 11
  screenshots more: an editor panel in the seven reference windows, two panels on a tablet in
  portrait (tabs) and on a desktop (side by side), the details of a note on a phone (sheet) and
  on a desktop (dialog); the configurator on a tablet in landscape and on a desktop recorded again
  (now a dialog). 34 screenshots in all.

### Phase 3 results

* **The bars** (`EditorToolbar.kt`): `ToolbarLayout` measures the top bar (its buttons at the
  minimum size of the input, the actions of the extensions as they are laid out, a title of
  160 dp at least) and the tools (undo and redo, the tools, the imports): one bar when both fit
  (a tablet in landscape and a desktop, also with three extensions), two bars otherwise (a tablet
  in portrait or in split screen, a narrow desktop window), the tools floating at the bottom of
  the canvas in a compact window or a short one (a phone in portrait and in landscape), where the
  thumb reaches them. The floating tools stay above a panel rising from the bottom
  (`EditorScaffold` gives the canvas the height the panels take) and scroll when they do not fit.
  The page grid leaves the top bar for the "more" menu when the title would be too short, the
  imports when the tools float. The bars are 48 dp high.
* **The "more" menu** is a `CommandMenu` of sections; the menus show the shortcuts only where
  there is a keyboard (as the tooltips appear only where a pointer hovers).
* **The cursors**: a mouse or a hovering stylus shows the dot of the pen where the ink would land,
  as large and as colored as its stroke (ringed to show on any page), as the eraser shows its
  circle; the canvas takes the cursor of the tool (crosshair, I-beam for the texts and the text of
  the PDFs, hand for the pan, crosshair while placing).
* **The camera**: fit width (⌘0), previous and next page (PgUp, PgDn), showing the top of a page
  at the current zoom (`DrawViewController.showPage`).
* Tests: the arrangements of the reference windows, with and without extensions, the edge between
  one bar and two, the page grid in the menu (`ToolbarLayoutTest`); the pages shown one by one and
  the width fitted (`EditorCoreTest`); every editor screenshot recorded again (the 48 dp bars, the
  arrangements), one more: the "more" menu of a phone. The screenshot tests move the mouse off the
  canvas once the note is open, so that the dot of the pen does not show.

### Phase 4 results

* **The layouts** (`LibraryLayout`, by the width of the library): a list under 600 dp (a phone),
  a grid of cards from 600 dp (a phone in landscape, a tablet in portrait or in split screen, a
  narrow desktop window), the panes from 1000 dp (a tablet in landscape, a desktop): the folders
  of the root and the notes opened since they were made on the left, the grid, and the details of
  the item selected alone on the right (its "Info" button selects it there, a sheet elsewhere).
* **The thumbnails** (`DocumentThumbnails`): the first page of each document as the editor draws
  it (backgrounds, PDF pages, images, strokes, texts), drawn one at a time off the main thread at
  widths in steps of 120 px, kept in memory and in `thumbnails/` of the data directory (the
  documents of `DrawViewStore`; in memory for the others, the tests among them). A card shows the
  icon of the documents until its page is drawn.
* **The pointers**: ⌘ / Ctrl click adds an item to the selection, Shift click extends it in the
  order of the folder, a mouse drags the items (the selection when the item is in it) onto a
  folder of the list, of the grid or of the pane, the root included, ringing it while they can go
  there; a finger keeps scrolling and the long press selecting.
* **The files of the system** (desktop): a PDF or an image dropped onto the editor is imported
  into the note; dropped onto the library, it becomes a note named after it (a free name of the
  folder) opened with it. A PDF imported into a note of one empty page replaces that page (also
  from the toolbar), in the same change (one undo). Android and iOS do not take them yet.
* Tests: the layouts of the reference windows, Shift and the anchor of the selection, a folder
  shown from the root, items dropped onto a folder, onto themselves, beside the folders
  (`AdaptiveLibraryTest`); a thumbnail drawn, kept in memory and on disk, drawn again when changed
  or larger (`DocumentThumbnailsTest`); the free name of a note, a PDF replacing the empty page
  (`FileImportTest`); the library screenshots recorded again (grid, panes with the first pages,
  details beside).

### Phase 5 results

* **The desktop windows** (`rememberDrawViewWindowState`, `WindowMinimumSize`, `drawview-ui`
  `jvmMain`): the size, the position and the placement (maximized, full screen) of the window are
  kept in `window-<name>.properties` of the data directory, the floating size while it is
  maximized; a position no longer on a screen is left to the system. The window cannot be made
  smaller than 360 × 480 dp, where the layouts of a phone fit. PencilNotes and the sample use them.
* **The Apple Pencil** (`PencilInteractionEffect`, `iosMain`; `PencilAction`): the double tap and
  the squeeze of the Pencil Pro do what the user set in the system: the eraser and back, the tool
  before (`ToolManager.previousTool`, also the "Previous Tool" command of the Tools menu), the
  settings of the tool chosen (`EditorCommands.perform`, the bar of the tools opening them).
* **The stylus button** (`isStylusButtonPressed`, `androidMain` of the core; `false` elsewhere):
  a stylus whose button is held erases, as its eraser end does.
* **The shortcut list of Android** (`CommandHost.keyboardShortcutGroups`, `androidMain`): Meta + /
  lists the shortcuts of the screen shown by menu; `RegisterCommands` writes the labels in the
  language of the interface and `CommandRoot` the titles of the menus, kept by the host for the
  menus asked outside of the composition. PencilNotes gives them from its activity.
* **Hover of a stylus**: the canvas handles the hover of any pointer, so the dot of the pen and the
  circle of the eraser follow a hovering S Pen or Apple Pencil where Compose delivers it.
* **Left for later**, because they must be checked on the device: the toolbar in the title bar of
  macOS (the content under the title bar must leave its buttons and the dragging of the window);
  the menu bar of iPadOS 26 and its list of shortcuts (`UIKeyCommand` is matched by UIKit before
  the text fields of Compose, which would lose their own ⌘Z); the files dropped on Android and iOS
  (content URIs, item providers).
* Tests: the window kept and read again, a position no longer on a screen (`WindowStateTest`);
  the gestures of the Pencil, the labels kept by the host (`CommandTest`).

### Risks

* **Long press**: it opens the settings of a tool and selects a file; the tooltips of Material
  use it on touch screens. The tooltips are shown only where the pointer hovers.
* **Focus of the keys**: Compose delivers the keys to the focused element. The shortcuts are
  handled after it (`onKeyEvent`, not `onPreviewKeyEvent`) so that a text field keeps its own
  editing keys, and the root of the screen takes the focus when nothing has it.
* **The menu bar belongs to the window** (`FrameWindowScope` of the desktop, `UIMenuBuilder` of
  the app delegate): the modules give the commands through `CommandHost`, the application
  installs the menu; on iOS a few lines of Swift in the app.
* **Thumbnails**: drawing the first page of many documents (PDFs included) costs time and memory:
  drawn lazily for the visible items, small, on a background dispatcher, cached on disk.
* **Hover of the Pencil and of the Android stylus**: depends on what Compose delivers on each
  platform; verified in phase 5 before any bridge.
* **Regressions of the layouts**: every phase changes many screens; the screenshot tests of
  phase 0 come first.

### Decisions

Taken with the user on 2026-10-08:

* **Size classes**: computed by `WindowLayout` from `LocalWindowInfo`, without dependency; the
  library may adopt the scaffolds of `material3-adaptive` behind `LibraryLayout`.
* **Screenshot tests**: Roborazzi for Compose Desktop.
* **Toolbar of the phone**: the tools at the bottom, in a floating toolbar within reach of the
  thumb.
