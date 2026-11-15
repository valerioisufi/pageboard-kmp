# A review of the interface: what gets in the way

A pass over the screens of the library and of the editor, looking for what makes them harder to
use than they need to be: controls that cannot be seen, a theme where the cards disappear, labels
that say little or say it in different ways. Every item below was seen in the screenshots recorded
by the modules (`*/screenshots`) and traced to its code; the plan fixes them without changing what
the screens do.

The responsibilities follow the GRASP principles, as in [ui-design.md](ui-design.md).

## Starting point

The design of [ui-design.md](ui-design.md) is in place (its eight phases are done), and
[ui-fit.md](ui-fit.md) fixed the width of the dialogs and the hovers. What is left:

1. **The floating tools on a phone are cut.** `FloatingTools` (`EditorToolbar.kt`) scrolls the
   tools when they do not fit, but nothing says so: on a phone in portrait the bar ends with half
   an icon (the lasso), and the hand is out of sight. The tool chosen can be scrolled out of view
   too.
2. **The cards disappear in the dark theme.** `NoteCard` and `FolderCard` (`LibraryItems.kt`)
   are drawn on `surfaceContainerLow`, which in the dark scheme is the surface itself (`Color.kt`:
   "N10, as the surface"). In light they are cards; in dark the folders are a tile and a name
   floating on the background, and of a note only the well of the thumbnail is left.
3. **Two ways of writing a label.** The library writes in sentence case ("New note", "New folder",
   "Search notes", "Select all"), the editor in title case ("Add Page", "Fit Width", "Stylus Only
   Mode", "Document Pages", "Page Configuration", "Insert Row Above", "Empty Trash"), sometimes in
   the same menu ("Add page after" beside "Delete Page"). Material 3 writes sentence case.
4. **"Draw" in the "more" menu says little.** The check of `drawingMode` (`EditorCommands.kt`)
   switches between drawing and reading, but the label is a verb with the pen icon, beside "Stylus
   Only Mode": it reads as a tool, not as a mode.
5. **The titles of the popovers of the tools are not alike.** "Pen", "Highlighter", "Eraser" but
   "Text Tool Settings" and "Lasso Mode"; "Size" for the pen, "Eraser Size" for the eraser.
6. **The page is said twice on a phone.** The subtitle of the title is "Page 1 of 3" on a phone,
   and the pill at the bottom says "1/3" just the same; elsewhere the subtitle is "3 pages" and the
   pill the page looked at.
7. **Leaving the Trash is an arrow up.** `LibraryHeader` uses `ArrowUp` both for the folder above
   (right: it is up) and for leaving the Trash, which is not above anything: it goes back to Home.
8. **The selection shows a bare number.** On a phone the bar of the selection is "× 1": the number
   alone, in a large style, with no word.
9. **Two "more" icons.** The library opens the actions of an item with a horizontal ⋯, the page
   grid with a vertical ⋮, for the same kind of menu.
10. **A request to join reads badly.** "Lab PC wants to join (Can only read)": a capital in the
    middle of a sentence, between brackets.

11. **The search settings close out of sight.** Found while checking: "Close" was the last line of
    a dialog that scrolls (`SearchSettingsDialog`), below its edge as soon as the models fill it,
    where the other dialogs close with the × of their title.

## Requirements

* Every tool of the floating bar can be found: the bar shows when there is more on a side, and the
  tool chosen is in view.
* In both themes a card is told apart from the background, with the contrast of the light theme.
* One case for every label of the modules: sentence case, in English; the translations keep the
  convention of their language (they already write sentence case, they are checked).
* Every label says what the control does or is; a mode reads as a mode.
* No change to what the screens do, to the stored data or to the protocol. The keys of the strings
  stay, so the translations stay where they are.
* The screenshot tests show each fix; they are re-recorded and looked at.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Showing that a row scrolls: a fade on the side with more | `fadingHorizontalScroll` (a `Modifier` of `drawview-ui`, `ScrollFade.kt`, from the `ScrollState`), used by `FloatingTools`, `ToolGroupRow`, `ActionBar`, `TextFormatBar`, `ChipRow`, the rows of the popovers | Pure Fabrication, High Cohesion (one way for every scrolled row) |
| Knowing which tool is chosen and where it is in the row | `ToolButton` (ui): a `BringIntoViewRequester`, asked once the button is selected | Information Expert |
| The color of a card in each theme | `DrawViewTheme` (ui): the dark containers four tones above the surface (N10) and apart from each other: `surfaceContainerLow` N14, `surfaceContainer` N16, `surfaceContainerHigh` N20, `surfaceContainerHighest` N24 | Information Expert, Protected Variations (the cards keep naming `surfaceContainerLow`) |
| The words of the labels | the `strings.xml` of each module, in English | Information Expert |
| The mode of the editor in the menu | `EditorCommands.drawingMode` (ui): "Drawing mode", the check on when the tools write | Information Expert |
| The subtitle of the title | `documentSubtitle` (ui): "Analysis · 3 pages" on every size; the page looked at stays in the pill | Information Expert, High Cohesion |
| Leaving the Trash | `LibraryHeader` (explorer): `ArrowLeft` for the Trash (made public in `DrawViewIcons`, recorded in `api/`), `ArrowUp` for the folder above | Information Expert |
| The count of the selection | `explorer_selected_count` (explorer): "1 selected", in the title style of a bar | Information Expert |
| The "more" of an item | `DrawViewIcons.MoreHorizontal` everywhere: the page grid (ui), the members of the sharing (sync-ui), the lecture (lecture-ui), as the library | High Cohesion |
| Closing the search settings | `SearchSettingsContent` (search-ui): the × beside the title, as the other dialogs | High Cohesion |
| The request to join | `sync_request_short` (sync-ui): "Lab PC wants to join · Can only read", the role after a separator as in the other lines of the dialog | Information Expert |

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | The scrolled rows: `fadingHorizontalScroll` (`ScrollFade.kt`), the chosen tool in view (`ToolButton`); on the floating tools, the tool group, the action and format bars, the chip rows, the rows of the popovers | done |
| 2 | The dark cards: the dark containers N14, N16, N20, N24 (`Color.kt`); the library and the editor in dark looked at | done |
| 3 | The words: sentence case in the English strings of every module and in the Italian, Spanish and French ones; "Drawing mode"; the titles of the popovers ("Text", "Lasso", "Size"), translated; the request to join; "1 selected" in the title style | done |
| 4 | The small ones: the subtitle "N pages" on every size, leaving the Trash with an arrow back, one "more" icon; `DrawViewIcons.ArrowLeft` public (the ABI dumps of JVM and Android checked; the klib dump by hand, as `updateKotlinAbi` empties it on Windows) | done |
| 4b | The search settings closed by the × of their title | done |
| 5 | Check: the screenshots re-recorded (explorer, ui, sync-ui, lecture-ui, export-ui, search-ui, agent-ui) and looked at; the desktop app on a scratch data directory; Android on the tablet | pending |

## Decisions

All revertible.

* **Sentence case, not title case.** The library, the sheets and the dialogs already write it, and
  Material 3 asks for it. The menu bar of macOS writes title case by habit; the commands share
  their labels with the menus of the other platforms, so they follow the rest. The names keep
  their capital: the sections (Trash, Home, the Cestino of the Italian), the Files app of iOS
  ("Save to Files"), Internet in French. German writes its nouns with a capital, Japanese and
  Korean have no case: they are left as they are.
* **The selection said in words** ("1 selected") rather than a plural resource: the string was
  already there and translated; only the style of the number changed.
* **The dark containers move, not the cards.** Giving the cards another role would fix them alone;
  the token fixes every surface drawn on `surfaceContainerLow` in dark (the panels, the wells).
* **"Drawing mode" checked**, not "Reading mode" checked: the check keeps meaning what it means
  today.
* **The subtitle as on the other sizes** rather than the pill hidden: the pill also says the zoom
  where there is one, and the subtitle then says something the pill does not (where the note is).

## Left for later

* A pass on the real application of the other flows (the first start, the errors of sync and of
  the lecture assistant), which the screenshots do not show.
* The thumbnails of grid paper at the size of a row: the grid is too faint to be seen
  ("Exercises" on a phone is white); it is a matter of the renderer of the thumbnails.
