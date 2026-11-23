# Dialogs and sheets on small screens

On a narrow desktop window the About & licenses dialog squeezes each license into a column a few
letters wide: the description wraps one word per line, then one letter per line ("T h e F r e e
T y p …"). It is not the only one. Some dialogs do not fit a small window. The bottom sheets
scroll in different ways: some open half way and some open full, and some keep their title and
buttons in sight while others scroll them away. The panels of the editor cannot be dragged by
their content at all. This plan starts from the code at `82c39a90` of `feat/ios-stability` (2026-11-23), which has
`windowHeightLimit`. It lists every
dialog and sheet, the causes of the problems, the design and the phases.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

### The frames

1. **`AdaptiveSheet`** (`pageboard-ui`, `adaptive/AdaptiveSheet.kt`) is the frame for the larger
   sheets. It shows a `ModalBottomSheet` when the window is *compact in width* (under 600 dp), and
   a `BasicAlertDialog` otherwise. It has an optional title, a body that the caller must make
   scroll itself ("it scrolls its own content"), and optional actions pinned at the bottom.
   - Width: up to 560 dp a dialog is as wide as its content. Above that it is `maxWidth`, or the
     window less 24 dp margins.
   - Height: `windowHeightLimit` (since `98c8900d` of `feat/ios-stability`, not yet on `main`) bounds the dialog to the
     window less its margins.
   - The bottom sheet uses the default `SheetState`, which does not skip the partial state. A
     sheet taller than about half the window opens half way, and the first drag on its content
     expands it instead of scrolling. A shorter sheet opens fully. The sheet does not pad for the
     keyboard.
   - The choice depends on width only. A narrow *desktop* window (360 dp, operated with a mouse)
     gets a bottom sheet with a drag handle. A phone in landscape (891 × 411) gets a dialog
     bounded to 363 dp of height.
2. **`AlertDialog` of Material 3** is used directly 13 times. It is at least 280 dp wide and at
   most 560 dp. Its `text` slot takes the room left but **does not scroll**, so a long text, a
   growing list or a text field above the keyboard is cut off in a low window. Its buttons sit in
   one row that does not wrap.
3. **`BasicAlertDialog` laid out by hand** is used 3 times: About, Move to, Color picker. Each has
   its own padding, title style and button row, and no height limit.
4. **`Dialog` full screen**: the Trash preview.
5. **`PanelSheet`** (`pageboard-ui`, `EditorScaffold.kt`) holds the panels of the editor (the
   lecture, the agent's review…) on a compact window. It is not modal. Only its handle can be
   dragged (`detectVerticalDragGestures`: up makes it tall, down makes it short or closes it).
   Its content scrolls on its own, and dragging the content never moves the sheet. In a
   `ModalBottomSheet` the content drags the sheet.

The smallest windows the applications meet:

- the desktop minimum, 360 × 480 dp (`WindowMinimumSize`);
- small Android phones and split screen, 320–360 dp wide;
- phones in landscape, about 360–411 dp high.

The reference windows of the screenshot tests (`ReferenceWindow`) start at 411 dp. None is
narrower, and the only low one is `PHONE_LANDSCAPE`, 891 × 411.

### The license rows

6. `LicenseRow` (`AboutLicensesDialog.kt`) is a `Row` of three parts:
   - the name and description in a `Column(weight(1f))`;
   - the license chip, `maxLines = 1`, not weighted;
   - an 18 dp arrow.

   The chip and the arrow are measured first at their full width. "The FreeType Project License
   (FTL)" is about 200 dp, so in a 266 dp list the text gets almost nothing. That is the screenshot.
7. The dialog is a `BasicAlertDialog` and never becomes a sheet. The header (mark, name, version)
   and the description sit above a `LazyColumn` of fixed `heightIn(max = 360.dp)`, inside a dialog
   that does not scroll. In a 480 dp window the list keeps 360 dp and the Close button falls off the
   bottom. In a taller one the list scrolls inside a dialog that does not.

### Inventory

How each one scrolls and what happens at small widths (W, about 320–360 dp) and small heights (H,
about 360–480 dp). "Slots" means that it uses the `title` / `actions` of `AdaptiveSheet`.

| # | What | Where | Component | Scrolling | Small screens |
|---|---|---|---|---|---|
| 1 | About & licenses | `pageboard-ui` `AboutLicensesDialog` (via `aboutLibraryAction`, `LibraryDialog.About`) | `BasicAlertDialog` by hand | nested `LazyColumn` (max 360 dp) inside a dialog that does not scroll | **W: the rows break (screenshot). H: the Close button is cut.** Never a sheet. |
| 2 | New folder, Rename | `pageboard-explorer` `RequestNameDialog` (`NewFolderDialog`) | `AlertDialog` | none | H with the keyboard: the field and buttons are covered (to check on iOS) |
| 3 | Delete, Empty trash… | `ConfirmActionDialog` | `AlertDialog` | none | text and note grow without scrolling. H: cut |
| 4 | Move to | `MoveFileDialog` | `BasicAlertDialog` by hand | `LazyColumn` in a box 148–320 dp | **H: no height limit, cut. W: the breadcrumb `Row` does not scroll and overflows with deep paths** |
| 5 | New note | `NewNoteDialog` | `AdaptiveSheet`, no slots | its own `verticalScroll`, **with the title, Close and "Create" inside it** | H / keyboard: "Create" is below the fold, and the title scrolls away |
| 6 | Item sheet / details | `LibraryDetails.ItemSheet` | `AdaptiveSheet`, own header | commands in their own scroll, header pinned; details with a second scroll and a pinned button row | works; the two modes scroll differently |
| 7 | Trash preview | `TrashPreview` | full-screen `Dialog` | `LazyColumn` | fine |
| 8 | Export | `pageboard-export-ui` `ExportSheet` | `AdaptiveSheet`, slots | body `verticalScroll` | fine: the model to follow |
| 9 | Import archive | `ImportArchiveDialog` | `AlertDialog` | none | short; fine |
| 10 | Share (document, folder, library) | `pageboard-sync-ui` `ShareDialog` / `ShareContent` | `AdaptiveSheet`, own header (Close hidden on phones) and footer | its own `weight` + `verticalScroll` | works; it duplicates the slots by hand |
| 11 | Stop sharing / leave | `StopSharingDialog` | `AlertDialog` | none | fine |
| 12 | Join request | `JoinRequestDialog` | `AlertDialog` | none | text grows with long names. H: cut |
| 13 | Join | `JoinDialog` | `AlertDialog` | none | **progress and problems grow without scrolling. H / keyboard: cut** |
| 14 | Connection check | `CheckDialog` | `AlertDialog` | own `verticalScroll`, `heightIn(max = 420.dp)` | **H: 420 dp is more than a 411 dp landscape** |
| 15 | Lecture offer | `pageboard-lecture-ui` `LectureDialogs` | `AlertDialog` | none | fine |
| 16 | Quit during a lecture | `LectureExitDialog` (desktop `main.kt`) | `AlertDialog` | none | fine |
| 17 | Delete lecture | `DeleteLectureDialog` | `AlertDialog` | none | fine |
| 18 | Lecture settings | `AssistantSettingsDialog` | `AdaptiveSheet` (`maxWidth` 940, `padded = compact`), own header and actions | compact: chips + body scroll + pinned actions. Wide: 240 dp rail (not scrolling) + body scroll | **H (phone landscape, two panes at 363 dp): the rail does not scroll and is cut. W 600–700: 240 dp rail leaves little room** |
| 19 | Notes from a text | `TextNotesDialog` | `AdaptiveSheet`, slots | **none** | long messages cut at H |
| 20 | Search settings | `pageboard-search-ui` `SearchSettingsDialog` | `AdaptiveSheet`, no slots | its own scroll **with the title and Close inside** (the comment says they stay in sight; they do not) | the title and Close scroll away |
| 21 | Delete a model | `SearchSettingsDialog` | `AlertDialog` | none | fine |
| 22 | Agent access | `pageboard-agent-ui` `AccessSheet` | `AdaptiveSheet`, slots | body scroll | fine |
| 23 | Handwriting to text | `pageboard-handwriting-ui` `ConversionDialog` | `AlertDialog` | none | **field + up to N alternatives grow without scrolling. H / keyboard: cut** |
| 24 | Page / pages configurator | `pageboard-ui` `DocumentScreen` → `PageTemplateConfigurator` | `AdaptiveSheet` (760), own header and buttons | the column of options scrolls; preview fixed | **H (landscape dialog): the wide variant has a fixed 260 × 360 preview, taller than the room** |
| 25 | Document configurator | same | same | same | same |
| 26 | Overwrite backgrounds? | `DocumentScreen` | `AlertDialog` | none | buttons in one row, long labels |
| 27 | Text color | `TextColorPickerDialog` | `BasicAlertDialog` by hand | **none** | **W: a 280 dp wheel + 48 dp padding is more than 320. H: about 480 dp tall with no limit** |
| 28 | Import a text | `TextImportSheet` | `AdaptiveSheet`, slots | body scroll | fine |
| 29 | Panels of the editor (compact) | `EditorScaffold.PanelSheet` | its own sheet (not modal) | **handle only**; the content scrolls inside | does not behave like the modal sheets |
| 30 | Menus, tool popovers | `DesignMenu`, `ToolPopover` | `DropdownMenu` / `Popup` | bounded by `windowHeightLimit` (`4a80449c`) | out of scope |
| 31 | Startup error | desktop `main.kt` | `JOptionPane` | — | out of scope |

In short there are four ways to frame a modal, at least five ways to scroll one, and three places
where the title and the main button live (pinned, scrolled, or missing).

## Requirements

1. Every dialog and sheet is usable at **320 dp wide** and at **360 dp high**: nothing is cut, and
   no text is narrower than about 160 dp. This covers the desktop minimum of 360 × 480 dp, small
   phones, and phones in landscape.
2. **One way to scroll.** In every modal:
   - the header (title, Close) stays in sight;
   - the body scrolls, and there is only one scroll in it;
   - the actions stay pinned at the bottom;
   - the keyboard never covers the focused field or the actions.
3. **One sheet behaviour.** Every bottom sheet:
   - opens at its content height, up to the full window, never half way;
   - drags down to close from its handle or from its content scrolled to the top;
   - when it is not modal (the panels of the editor), its content moves it as the handle does.
4. **The right frame for the window and the input.** A phone gets a bottom sheet. A tablet or a
   desktop gets a dialog. A narrow desktop window operated with a mouse gets a dialog that fills
   it, not a sheet to drag. Short confirmations stay centred dialogs everywhere, as in Material.
5. The rows of lists (licenses, folders, settings) **wrap their parts instead of shrinking the
   text**. A chip or badge goes under the text when the two do not fit side by side.
6. The libraries keep their rules: explicit API, ABI recorded, no global state, no Compose in the
   headless modules (all of this is in UI modules).
7. Screenshots at small sizes guard each of these, recorded in the Docker image.

## Design

### Responsibilities

| Responsibility | Assigned to | Pattern |
|---|---|---|
| Choosing the frame (bottom sheet or dialog) from the width, the height and the input | `ModalFrame.of(AdaptiveInfo)`, pure and unit tested (`pageboard-ui/adaptive`) | Information Expert (`AdaptiveInfo` knows the window and the input), Pure Fabrication |
| The frame of a sheet: header (title, Close), one body scroll, pinned actions, sizes, keyboard, sheet state | `AdaptiveSheet`, reworked | Polymorphism (sheet or dialog, same slots), Protected Variations (callers no longer depend on `ModalBottomSheet` or `BasicAlertDialog`) |
| The frame of a short alert or confirmation: icon, title, scrolling text, buttons that wrap, width and height bounded by the window | `AdaptiveDialog`, new (`pageboard-ui/adaptive`), in place of every direct `AlertDialog` / `BasicAlertDialog` | Polymorphism, Protected Variations, High Cohesion |
| Dragging a non-modal sheet from its content (expand before scrolling, collapse or close after scrolling to the top) | `SheetDragConnection`, a `NestedScrollConnection` used by `PanelSheet` | Pure Fabrication, Indirection |
| Laying out a row whose side parts (chip, badge, arrow) go under the text when they do not fit | `WrappingRow`, a small `Layout` in `pageboard-ui/components` (internal unless a module outside `pageboard-ui` needs it) | Pure Fabrication, High Cohesion |
| What each modal shows | each modal, as today | Information Expert |

### `ModalFrame`

```kotlin
public enum class ModalFrame { BottomSheet, Dialog, FullWindowDialog }
```

- `BottomSheet` when the width is compact and the input is touch or stylus (a phone, a tablet in
  narrow split screen).
- `FullWindowDialog` when the width is compact and the input is a pointer (a narrow desktop
  window). It is a dialog that takes the window less 8 dp margins, with no drag handle.
- `Dialog` otherwise. A low window (phone in landscape) also gets `Dialog`, bounded to the window
  height; its body scrolls and its header and actions stay pinned.

### `AdaptiveSheet`

- New parameters:
  - `onClose: (() -> Unit)?` shows a Close button in the header, the same in every sheet.
  - `body: SheetBody = SheetBody.Scrolling` means that the sheet scrolls its content itself, with
    one `ScrollState` and the 24 dp horizontal margins. `SheetBody.Fixed` is for content that has
    its own lazy list or panes (Move to, lecture settings). It gets a bounded height and fills it.
- Bottom sheet:
  - `rememberModalBottomSheetState(skipPartiallyExpanded = true)`;
  - the title and the actions outside the scroll;
  - `imePadding` on the column, so the actions rise with the keyboard;
  - the content's nested scroll is left to Material: dragging down from the top closes the sheet.
- Dialog: as today (widths, `windowHeightLimit`), plus `FullWindowDialog`.
- The callers stop adding `verticalScroll` and stop drawing their own headers and footers.

### `AdaptiveDialog`

```kotlin
@Composable
public fun AdaptiveDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    text: (@Composable ColumnScope.() -> Unit)? = null,
)
```

It mirrors the slots of `AlertDialog`, so the migration is mechanical.

- Width: `min(560 dp, window − 2 × 24 dp)`. It drops the 280 dp minimum when the window is
  narrower than that plus the margins.
- Height: `windowHeightLimit`. The text slot scrolls with a single `ScrollState`.
- Buttons: in a `FlowRow` aligned to the end, so long labels wrap onto a second line instead of
  being cut.
- The keyboard: `imePadding`.

It stays a centred dialog in every frame, because confirmations are short.

### `PanelSheet`

`SheetDragConnection` is attached with `Modifier.nestedScroll` to the frame of the panels:

- `onPreScroll` upward while short: makes the sheet tall and consumes the drag, so the content
  scrolls only once the sheet is tall;
- `onPostScroll` downward with the content at its top: makes the sheet short, then closes it;
- `onPreFling` and `onPostFling`: the same decisions for a fling.

The handle keeps its gesture. This is the behaviour of `ModalBottomSheet`, so the panels and the
modal sheets feel the same.

### The license rows

`LicenseRow` becomes:

- line 1: the name with weight, and the arrow;
- line 2: the description, full width;
- line 3: the license chip (`maxLines = 1`, ellipsized at the row width).

On a wide dialog the chip goes back beside the name through `WrappingRow` when both fit. The About
dialog moves to `AdaptiveSheet` with `onClose`, `body = Scrolling` and a plain `Column` of rows.
That removes the nested `LazyColumn` of 360 dp: there are about ten licenses. The footer button
"Close" stays only in the dialog frame, since the header already has Close.

### The other layouts that break

- **Move to**: `AdaptiveSheet(body = Fixed)`. The folder list takes the room left (weight) instead
  of 148–320 dp. The breadcrumb scrolls horizontally, scrolled to its end.
- **New note**: the title and Close in the header slot. "Create" moves to the actions. The two
  import buttons go in a `FlowRow`.
- **Search settings, Share, Lecture settings, Page configurators, Text notes**: they use the
  header, body and actions slots.
  - Share keeps its two panes inside the body.
  - Lecture settings keep the rail for `Dialog` only when the height is not compact, and the rail
    scrolls.
  - The configurators show the large preview only when the height has room, and the small one in
    the header otherwise.
- **Text color**: the wheel is as big as the room: `min(280 dp, width, height left)`.
- **Check dialog**: its own `heightIn(420)` is dropped, since `AdaptiveDialog` bounds it.
- **Item sheet**: its two modes use the same slots, so they scroll the same way.

### Modules and API

- `pageboard-ui` gains the public `ModalFrame`, `AdaptiveDialog` and `SheetBody`, and the new
  parameters of `AdaptiveSheet`. That changes its ABI: `updateKotlinAbi` runs in the commits that
  change it, and the commits say so.
- The other UI modules (`pageboard-explorer`, `-export-ui`, `-sync-ui`, `-lecture-ui`,
  `-search-ui`, `-agent-ui`, `-handwriting-ui`) only change their calls. Their ABI should not
  change, because the modals are internal; `checkKotlinAbi` will tell.
- Nothing changes what is stored or what travels between devices.

## Plan

The work goes on a branch `feat/small-screen-modals`, with one commit per phase.

| Phase | Content | Status |
|---|---|---|
| 0. Plan | This document, with the inventory | approved |
| 1. License rows and About | `LicenseRow` on lines (with `WrappingRow`); About on `AdaptiveSheet` with one scroll. Screenshots of About in a 360 × 480 window, on a 320 × 640 phone and at 1000 × 760 | done |
| 2. The frames | `ModalFrame` with its unit tests; `AdaptiveSheet` with `onClose`, `SheetBody`, sheet state, keyboard and `FullWindowDialog`; `AdaptiveDialog`; layout tests like `AdaptiveSheetWidthTest` (heights, wrapping buttons, 280 dp minimum dropped). ABI updated | done |
| 3. The sheets | New note, Item sheet, Move to, Search settings, Share, Lecture settings, Text notes, Page and Document configurators moved onto the slots, with their own scrolls and headers removed. Screenshots at 360 × 480 and 891 × 411 | done |
| 4. The alerts | the 13 `AlertDialog`s and Text color on `AdaptiveDialog`. Screenshots of the conversion in a phone in landscape | done |
| 5. The panels of the editor | `SheetDragConnection` on `PanelSheet`, with a test of the drag (tall before scrolling, short then closed from the top) | done |
| 6. On the devices | desktop app on a scratch data directory at 360 × 480; Android emulator (small phone, landscape, keyboard); iPhone simulator (SE, landscape, keyboard); screenshots verified in Docker or by the workflow | to do |

Each phase runs the checks of `CLAUDE.md` (`jvmTest checkKotlinAbi`, the Android and iOS
compilations, both apps). The screenshot references are recorded only in the Docker image or with
the `record-screenshots` label.

### Phase 1 results

* `WrappingRow` (`pageboard-ui/components`, internal) puts its side part beside the main one when
  the main one keeps 160 dp, under it otherwise. `LicenseRow` is that row (name and description,
  license chip) and the arrow. `LicenseRowTest` checks it at 264 dp (the chip under a description
  at least 160 dp wide) and at 600 dp (beside).
* About is an `AdaptiveSheet`: the header, the description and the licenses in one scroll, the
  licenses a plain `Column`. Where the name would not keep a line (under 300 dp of content) the
  mark is drawn at 48 dp, the one of the application scaled from its 64 dp.
* The screenshot of a 320 dp phone showed the sheet opened half way, its Close button below the
  edge: the half state of the sheets, removed in phase 2.

### Phase 2 results

* `ModalFrame` (`BottomSheet`, `Dialog`, `FullWindowDialog`) and `currentModalFrame()`: a sheet
  where the window is compact and touched (a finger or a stylus seen or reported), a dialog taking
  the window less 8 dp where it is compact and reached only with a mouse, a dialog elsewhere.
  `ModalFrameTest` checks the reference windows.
* `AdaptiveSheet` takes `onClose` (a close button at the end of the header) and `body`
  (`SheetBody.Scrolling`, the default: the sheet scrolls the content and gives it 24 dp at the
  sides; `SheetBody.Fixed`: the room left, for lists and panes). The bottom sheet skips its half
  state and pads for the keyboard; the actions go on a second line rather than being cut. The
  sheets of the other modules are `SheetBody.Fixed` until phase 3 moves them.
* `AdaptiveDialog`: the slots of `AlertDialog` (icon, title, text, confirm and dismiss buttons,
  container color, properties). Its width is up to 560 dp and never more than the window less
  24 dp, under the 280 dp of Material too; its height is bounded by the window; the text scrolls
  between the title and the buttons, which wrap. `AdaptiveDialogTest` checks a 300 dp window, a
  360 dp high one with a long text, and two long buttons at 320 dp.
* `windowHeightLimit` leaves out the keyboard where it is drawn over the window (iOS), and the
  dialogs pad for it.
* About uses the header slot (its mark, name and version beside the close button, in sight) and
  the scroll of the sheet; its Close button only where it is a dialog.
* The ABI of `pageboard-ui` grows (`ModalFrame`, `SheetBody`, `AdaptiveDialog`,
  `currentModalFrame`, the parameters of `AdaptiveSheet`), recorded with `updateKotlinAbi`.

### Phase 3 results

* **New note**: its title and close button in the header, "Create" in the actions, both in sight
  while the name, the paper and the folder scroll; the two imports on two lines when they do not
  fit.
* **Move to**: an `AdaptiveSheet` (a sheet on a phone, no longer a dialog there), its buttons in
  the actions. The folders take the room left (120 to 420 dp) instead of a fixed box; the path
  scrolls sideways, to its end when a folder is entered, each name at most 200 dp.
* **Search settings**: the title and the close button in the header (they scrolled away), the
  content in the scroll of the sheet.
* **Lecture settings**: the chips above the settings also in a window low in height (a phone in
  landscape), and the rail scrolls.
* **Notes from a text**: in the scroll of the sheet.
* **Page and document configurators**: the large preview only where the body keeps 480 dp of
  height, shrinking to the room up to 360 dp; the buttons under the controls that scroll, in
  sight.
* **Share** and the **item sheet** keep their own header and body (the explanation of the sharing,
  the commands reaching the edges), already pinned and in one scroll. In a narrow desktop window
  they show their close button, since that dialog cannot be dragged.
* **Export, agent access, import of a text** already had the parts in place: they stay
  `SheetBody.Fixed` with their single scroll, so that their content (shared with their screenshot
  tests) keeps its margins.
* Screenshots: the configurator in a phone in landscape (`page-configurator-landscape`), the search
  settings in a 360 × 480 window (`search-settings-narrow`).

### Phase 4 results

* The 13 `AlertDialog`s are `AdaptiveDialog`s, with the same slots: new folder and rename, the
  confirmations of the library, the import of an archive, the conversion of the handwriting, the
  offer and the deletion of a lecture, quitting during a lecture, the deletion of a model, ending
  the sharing, a request to join, joining, the check of the connection, overwriting the
  backgrounds.
* `AdaptiveDialog` keeps the width of the platform as `AlertDialog` does (the dialogs are as wide
  as before, as their screenshots show), bounded by the window less 24 dp at each side.
* The check of the connection loses its own scroll of 420 dp (more than a phone in landscape):
  the dialog scrolls its lines.
* The color picker is an `AdaptiveDialog`: the wheel as large as the window lets it be (up to
  280 dp, at least 120), its buttons in sight.
* Screenshot: the conversion in a phone in landscape (`convert-to-text-low`), the other readings
  scrolling above the buttons.
* `LecturePanelQueueTest` failed once in the whole run and passed three times alone: a timing of
  the test under load, not of the dialogs.

### Phase 5 results

* `SheetDragConnection` (`pageboard-ui/screen`, internal), a `NestedScrollConnection` on the
  sheet of the panels:
  - dragged up while the sheet is short, the content makes it tall after 48 dp, and keeps its
    place until then;
  - pulled down past its top by more than 48 dp, it makes a tall sheet short, or closes a short
    one, when the drag ends: one step per drag;
  - a fling up while short makes the sheet tall.
* The handle keeps its own gesture. A mouse wheel never closes the sheet, since a wheel has no end
  of drag (the modal sheets do the same).
* `SheetDragConnectionTest` checks the four cases: up while short, down twice from tall, a short
  pull, the content scrolling back to its top before the pull.

## Decisions

To agree with the maintainer:

* **A narrow desktop window gets a dialog that fills it, not a bottom sheet** (`FullWindowDialog`).
  The choice is by input (pointer or touch), which `AdaptiveInfo` already knows. Revertible: one
  line of `ModalFrame.of`.
* **Bottom sheets skip the half state.** They open at their content height, up to the full window.
  Material's default half state is what makes the long sheets (Export, Share, settings) open
  differently from the short ones.
* **Confirmations stay centred dialogs on phones**, as in Material, and do not become sheets.
* **The public API of `pageboard-ui` grows** (`ModalFrame`, `AdaptiveDialog`, `SheetBody`, new
  parameters of `AdaptiveSheet`). The libraries are not released yet, so the parameters are
  changed in place, without overloads kept for binary compatibility.

Revertible, taken alone:

* **The branch starts from `82c39a90` of `feat/ios-stability`**, which has `windowHeightLimit`
  (`98c8900d` there, `4a80449c` before its rebase) and is not on `main` yet: `main` takes
  `feat/ios-stability` first.
* **`ReferenceWindow` is not extended.** The small sizes (320 × 568, 360 × 480) are given in the
  tests themselves, so that the enum and its ABI stay as they are.
* **The license list is a plain `Column`**, not a lazy one: about ten rows.

## Left for later

* The menus and the popovers (`DesignMenu`, `ToolPopover`), already bounded by `4a80449c`.
* A side sheet (from the right) for the settings on wide windows, in place of the dialog.
* The status bar showing for a moment when a dialog opens over the editor on Android 10
  (`header-fit.md`).
* The startup error dialog of the desktop app (`JOptionPane`).
