# The title bar: renaming and the window controls

Two changes to the bars at the top of the windows: the document is renamed from the title in the
bar of the editor, and no screen puts its buttons under the traffic lights of macOS. It follows
[window-integration.md](window-integration.md) (phases 5 and 6: the unified title bar and the bars
beside the traffic lights).

## Starting point

1. **Renaming.**
   * A document is renamed only from the library: the command "Rename" (`LibraryCommands`) opens
     `RequestNameDialog`, checked by `FileExplorerViewModel.validateFileName` (not empty, no `/` or
     `\`, no other item of the folder with that name), and `OpenDocuments.rename` submits a
     `RenameDocument` to the document (the devices it is shared with rename it too) after
     `DocumentCatalog.isDocumentNameAvailable`.
   * In the editor the title is `DocumentInfoSelector`: a tap opens a popover with the title, the
     folder, the dates, the pages, the format and the elements, nothing to change.
   * `EditorScreen` (`drawview-ui`) knows the controller only, not the store; `EditorSessionScreen`
     (`drawview-session`) has the store and the `OpenDocuments` of the session.
   * The document windows of the desktop take their title from the document (`docTitle` in
     `main.kt`), read once: `PencilNotesDocumentWindow` keyed it on `document.name`, which is not
     Compose state, so a rename did not reach the window.
2. **The window controls on macOS.**
   * `DrawViewWindowDecor` gives `LocalWindowDecorInsets` (78 dp at the start, 52 dp at the top)
     while the window is not in full screen; the bars that count it are the editor
     (`EditorTopBar`), the library (`LibraryAppBar`, `SelectionBar` of the list) and the navigation
     pane of the library.
   * The page grid (`PageGridOverlay`) covers the whole editor with its own bar, which starts 12 dp
     from the edge: its close button (and Done of `PageSelectionBar`) lies under the traffic
     lights, and the bar cannot drag the window.
   * The other screens that reach the top-leading corner of the window were not checked.

## Requirements

1. **Rename from the title bar.**
   * In the popover of the title, the title is a text field when the document may be changed: the
     new name is taken with Enter or when the popover is closed, Escape gives the old one back. A
     document read only shows its title as today.
   * The name is checked as in the library: not empty, no `/` or `\`, no other document of its
     folder with that name. The error is shown under the field, and the name is not taken.
   * The name is the same everywhere at once: the title of the bar, the subtitle of the page grid,
     the library, the title of the document window on the desktop, the devices it is shared with.
   * A command "Rename…" in the "more" menu and in the menu bar opens the popover with the field
     focused and its text selected.
   * An editor without a store (`EditorScreen` given only a controller) keeps a title that cannot
     be changed: renaming is given by whoever keeps the document.
2. **No buttons under the traffic lights.**
   * The bars of the page grid (the grid and the selection of pages) start after the start inset
     of the window controls, and their empty room drags the window, as the bar of the editor.
   * Every screen that reaches the top-leading corner of the window is checked on macOS, in a
     window and in full screen, and fixed the same way.

## Design

### Responsibilities and GRASP patterns

| Responsibility | Class / Component | Module | GRASP |
|---|---|---|---|
| Renaming the document of the editor, with the check of its folder | `DocumentRenamer` (a `fun interface`, `suspend fun rename(name): Boolean`) | `drawview-ui` | Protected Variations, Indirection |
| The renamer of a document of a store: `OpenDocuments.rename` after the checks | `EditorSessionScreen` | `drawview-session` | Information Expert, Creator |
| The checks of a name that need no store (empty, `/`, `\`) | `DocumentNames.syntaxError(name)` | `drawview-ui` | Pure Fabrication, High Cohesion |
| The title as a field, its focus, Enter and Escape, the error | `DocumentTitleField` in `DocumentInfoSelector` | `drawview-ui` | Information Expert |
| The title of the document window, as the document is renamed | `PencilNotesDocumentWindow` (`document.observe { name }`) | `pencilnotes` | Information Expert |
| The command "Rename…" opening the popover | `EditorCommands.rename` | `drawview-ui` | Controller |
| The start of a bar along the top of the window | `LocalWindowDecorInsets` (as today) | `drawview-ui` | Information Expert |
| The bars of the page grid after the traffic lights, draggable | `PageGridOverlay`, `PageSelectionBar` | `drawview-ui` | Information Expert |

### Renaming

```
EditorTopBar ── DocumentInfoSelector ── popover ── DocumentTitleField ── Enter / focus lost
                                                                     │
                                         EditorScreen(renamer) ──────┘
                                                  │
                    EditorSessionScreen: DocumentRenamer { name -> OpenDocuments.rename(id, name) }
                                                  │
                                    RenameDocument submitted to the open session
                                (observed by the title, the grid, the library, the window, sync)
```

* `EditorScreen` takes `renamer: DocumentRenamer? = null`; `null` (an editor without a store) keeps
  the title read only. `DrawScreen` passes it to `EditorTopBar` and to the commands.
* `DocumentRenamer.rename` answers `false` when the folder has the name: the field shows it, and
  the popover stays open. The syntax is checked as one types (`DocumentNames.syntaxError`), and a
  name it refuses is never asked. The strings of the errors are those of the library, moved to
  `drawview-ui` (`name_error_*`), where the explorer takes them too.
* The draft of the name is kept by `DocumentInfoSelector`, not by the field: closing the popover
  (a tap outside, the title tapped again) takes it in the scope of the bar, which outlives the
  popover. A name taken by then is dropped, as Escape would.
* The popover takes the focus with a field in it (`ToolPopover(takesText)`), as the menus with one
  (`DesignMenu(takesText)`): under Android 11 the others do not.
* "Rename…" (`EditorCommands.rename`, in the File menu and in the "more" menu) counts its requests
  (`renameRequests`, as `paletteRequests`): the title opens its popover with the name selected.
  The command is registered only where there is a renamer.
* `OpenDocuments.rename` opens the document again: the session open in the editor is reused
  (`users++`), so the `RenameDocument` reaches the controller like a change from another device,
  and the title, already observed (`document.observe { name }`), changes. The name trimmed and the
  same name are not submitted.
* Renaming is not in the undo of the editor, as in the library: it is not a change of the pages.
* Nothing new travels: `RenameDocument` is already a change of the protocol. The stored formats
  do not change.

### The page grid on macOS

* The two bars of the grid add the start inset of the window controls to their start padding
  (`LocalWindowDecorInsets.calculateStartPadding`), and the title of the grid (and the count of
  the selection) is wrapped in `WindowDraggable`, the room the bar leaves to drag the window.
* The bars of the grid are 56 dp tall, centered at 28 dp: beside the traffic lights, centered at 26
  points, as the bar of the editor.
* The audit is a screenshot test (`AdaptiveScreenshotTest.macWindow`): the scene of the library
  given the insets of macOS and the traffic lights drawn where macOS puts them (14 points wide, 6
  apart, from 19 points, centered at 26), in a wide window (`DESKTOP`) and a narrow one (`PHONE`
  wide), recorded as `<screen>-mac-<window>-light.png`. A button under the lights shows at once,
  and it stays checked by `verifyRoborazziJvm`.
* What it found:
  * The page grid and its selection: their first button under the lights, in both windows. Fixed.
  * The library (list, panes, a selection, the trash) and the editor (one bar, floating tools):
    after the lights already.
  * The sheet of the background of the document in a narrow window: it rises from the bottom,
    under the bar of the editor, and leaves the corner alone.
  * The panels of the extensions and the details of the library are at the trailing side, and the
    dialogs are centered: none reaches the corner.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1. Rename from the title | `DocumentRenamer`, `DocumentNames`; the title a field in the popover of `DocumentInfoSelector`; "Rename…" in the "more" menu and the menu bar; `EditorSessionScreen` gives the renamer; the strings of the errors shared with the explorer; the title of the document window follows the name; tests of the field (Enter, Escape, errors, closing, read only, the command) and of a rename reaching the open session; ABI recorded | Done |
| 2. The window controls on macOS | The bars of the page grid after the start inset and draggable; the audit of the other screens as screenshots of a window of macOS (`macWindow`): only the page grid needed a fix | Done |

### Decisions

Taken alone, revertible:

* **The title edited in its popover**, as in Pages or Freeform on macOS, rather than in the bar
  itself: the bar keeps its room and its tap, and the popover already shows the title.
* **Renaming is not undone with ⌘Z**: it is not a change of the pages, as in the library.
* **A renamer given by whoever keeps the document** (`DocumentRenamer`): `drawview-ui` stays free of
  the store, and an editor without one keeps its title read only.
* **One plan for the two changes**: both are about the bar at the top of the window, and they are
  two phases of their own.

### Left for later

* Renaming from the title of the window itself on macOS (the proxy menu of the title of a
  document window), which needs the title visible.
* Moving the document to another folder from the popover of the title.
