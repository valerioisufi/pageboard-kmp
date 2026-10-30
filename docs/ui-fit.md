# Dialogs at their width and hovers in their shape

The wide dialogs of the applications (the settings of the assistant, sharing, the configurators of
the page and of the document, the settings of the search) open narrower than they were designed,
so their content stacks and their labels are cut. Many buttons with rounded corners show a
rectangular background when the pointer is over them. This document starts from the code as it is
(main at 0ccf495e, 2026-10-30): the causes, then the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

### The width of the dialogs

1. **`AdaptiveSheet`** (`drawview-ui`) shows its content in a `BasicAlertDialog` of Material 3
   (1.12.0-alpha03) in a window that is not compact, with `Modifier.widthIn(max = maxWidth)` and
   `usePlatformDefaultWidth = maxWidth <= 560.dp`.
2. **`BasicAlertDialog` bounds its box to 280–560 dp** (`DialogMinWidth`, `DialogMaxWidth`): its
   modifier is `modifier.sizeIn(minWidth = 280.dp, maxWidth = 560.dp)`. A `widthIn(max = 940.dp)`
   before it leaves the incoming constraints at 0–940 dp, and `sizeIn` narrows them to 280–560 dp.
   Only a fixed width before it (`width`, `fillMaxWidth`) wins over `sizeIn`, as its KDoc says.
3. **So every `maxWidth` above 560 dp is ignored.** The dialogs that ask for more:

   | Dialog | Module | `maxWidth` | Its content at 560 dp |
   |---|---|---|---|
   | Settings of the assistant (`AssistantSettingsDialog`) | `drawview-lecture-ui` | 940 dp | sections 240 dp, a section 320 dp: under `TaskRowMinWidth` (520 dp) the tasks stack, the segmented choices and the menus are cut ("This comp…", "large-v3-turbo-q5_0 · ") |
   | Sharing, with invitations (`ShareDialogWidth`) | `drawview-sync-ui` | 860 dp | to check |
   | Page format and document configurators (`DrawScreen`) | `drawview-ui` | 760 dp | to check |
   | Settings of the search (`SearchSettingsDialog`) | `drawview-search-ui` | 580 dp | 20 dp narrower |

4. **The fix proposed by the user** (`fillMaxWidth()` on the `Row` of the settings) makes the row
   as wide as the dialog lets it, 560 dp, not 940: it is not enough. The weight of the column on
   the right already fills a bounded row.
5. **The screenshot tests do not see it**: `LecturePanelScreenshotTest`, `SharingScreenshotTest`,
   `SearchScreenshotTest` and `ExportScreenshotTest` draw the content of the sheet in a `Column` of
   a fixed width (940 dp, `ShareDialogWidth`, …), not in `AdaptiveSheet`. What they check is the
   content at the width it should have, never the width it gets.

### The hovers

6. **The indication of a `clickable` (hover, press, focus) is drawn on the bounds of the node it is
   applied to**, clipped only by a `clip` before it. A `background(color, shape)` or a `border`
   with a shape draws the shape, but does not clip what comes after.
7. **The components where the hover is a rectangle** around a rounded shape:

   | Component | File | Shape | Why |
   |---|---|---|---|
   | `NavigationItem` (the sections of the settings, the panes of the library) | `DesignComponents.kt` (`drawview-ui`) | pill | `background(…, CircleShape)` then `clickable` |
   | `SegmentedChoice` (its options) | `DesignComponents.kt` | pill | the same |
   | `FormatCard` (the formats of the export) | `ExportSheet.kt` (`drawview-export-ui`) | 16 dp | `selectable` in the modifier of a `Surface`, outside of its shape |
   | `GenerateHereCard` | `LecturePanel.kt` (`drawview-lecture-ui`) | 20 dp (`PanelCard`) | `toggleable` in the modifier, before the background of the card |
   | The buttons of the actions of a file | `LibraryDetails.kt` (`drawview-explorer`) | circle | `background(…, CircleShape)` then `clickable` |
   | `PaperOption` (the paper of a new note) | `Dialogs.kt` (`drawview-explorer`) | 10 dp | `clickable` on the column with the preview and the label |

   The others with a rounded shape (about 30: `ToolButton`, `CommandMenu`, the text style
   controls, the cards of the library, the grid of the pages, …) clip before their `clickable`.
8. **Rows with a switch** that reach the margins of their content, not the edges of the sheet
   (`SwitchRow` of the settings of the assistant, `ApprovalSwitch` and the internet switch of
   sharing): their hover is a rectangle floating inside the padding. The rows of a sheet or of a
   list that reach its edges (`SheetCommand`, `LicenseRow`, the rows of the library in a list) are
   list items, rectangular in Material 3, and stay as they are.

## Requirements

1. A dialog that asks for a width above 560 dp gets it, or the width of the window less its
   margins when the window is narrower; its content adapts below that, as it does on a phone.
2. A dialog up to 560 dp stays as today (Material 3: 280–560 dp, as wide as its content).
3. The compact window (a bottom sheet) does not change.
4. A test fails when a wide dialog opens narrower than it asks, with the real `AdaptiveSheet`.
   The other wide dialogs need their whole engine (sharing, the editor), so the test of
   `AdaptiveSheet` stands for them.
5. The hover, the press and the focus of a rounded control follow its shape, in every module.
6. A rule in `docs/ui-design.md` keeps it so: the shape clipped before the `clickable`, or a
   `Surface` with its `onClick`.
7. Nothing stored or sent changes: no migration, `ProtocolFormatTest` as it is. No public API
   changes (`checkKotlinAbi` as it is), unless phase 1 needs a parameter of `AdaptiveSheet`; then
   it is said in the commit.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Knowing how wide a dialog is | `AdaptiveSheet` (`drawview-ui`): with `maxWidth` above 560 dp it gives the box of `BasicAlertDialog` a fixed width, `widthIn(max = maxWidth).fillMaxWidth()`, with a margin of 24 dp from every edge of the window (the platform width is off, and with it the margins of the platform) | Information Expert (it has `maxWidth` and knows the dialog it uses), Protected Variations (the callers do not depend on the bounds of Material 3) |
| Laying out the content in the width it gets | each content (`AssistantSettingsContent`, `ShareContent`, …), as today | Information Expert |
| Checking the width a dialog gets | `AdaptiveSheetWidthTest` (`drawview-ui`): `AdaptiveSheet` in a window of 1280 or 800 dp, the box of the dialog measured (the node that names its pane); `AssistantSettingsDialogTest` (`drawview-lecture-ui`): the real settings, their requests beside their names | Pure Fabrication |
| Following the shape on hover | each component: `clip(shape)` before `clickable`/`selectable`/`toggleable`, or `Surface(onClick = …)`/`Surface(selected = …, onClick = …)` | Information Expert (the component knows its shape) |
| Rows with a switch inside a padded content | the same `clip(RoundedCornerShape(12.dp))` the other rows already use (`TextImportSheet`, `ExportSheet`), without an inner padding, so they stay aligned with the content above and below | High Cohesion (one look for the rows of the sheets) |

### The dialog

```kotlin
BasicAlertDialog(
    onDismissRequest = onDismissRequest,
    modifier = if (maxWidth > DialogWidth) Modifier.padding(24.dp).widthIn(max = maxWidth).fillMaxWidth()
        else Modifier.widthIn(max = maxWidth),
    properties = DialogProperties(usePlatformDefaultWidth = maxWidth <= DialogWidth)
)
```

`DialogWidth` (560 dp) is the bound of Material 3, named in `AdaptiveSheet` (it is internal in
Material 3). The height does not change: the content bounds itself (the settings:
`heightIn(max = 820.dp).fillMaxHeight(0.92f)`).

### The checks after the fix

Done in phase 3. What they found: the settings of the search, taller than a short window, reached
its edges (a wide dialog has no margins of the platform), and the model of the transcription was
still cut in its menu of 280 dp. The screenshot of the editor in the explorer
(`document-background-*`) shows the configurator of the document at 760 dp, the preview beside the
controls, as it was designed.

The plan was:

At the width they get, the wide dialogs are looked at on the desktop app (a scratch data
directory) and in their screenshots, wide and in a window of 800 dp (where the dialog is 752 dp
and the settings of the assistant stack their tasks again, as they should below 520 dp). What is
still cut there (the model in the menu of a task, the segmented choices) is fixed in phase 3.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | `AdaptiveSheet` gives the wide dialogs their width; the tests of the width of `AdaptiveSheet` (`AdaptiveSheetWidthTest`: wide, in a narrower window, narrow), failing before the fix, and of the real settings of the assistant (`AssistantSettingsDialogTest`) | done |
| 2 | The hovers in the shape of their component: `NavigationItem`, `SegmentedChoice`, `FormatCard`, `GenerateHereCard` (`PanelCardShape`), the actions of a file, `PaperOption`, the rows with a switch inside a padded content (`SwitchRow`, `ApprovalSwitch`, the internet switch); the rule in `docs/ui-design.md` | done |
| 3 | The wide dialogs checked at their width and in a narrow window; the screenshot tests of the wide dialogs on the real dialogs; the margins of a wide dialog above and below too (the settings of the search reached the edges of a short window); the choice of a request 320 dp wide (the model of whisper.cpp was cut); the screenshots re-recorded (explorer, ui, sync-ui, lecture-ui, export-ui, search-ui) | done |

## Decisions

Taken alone, revertible:

1. **The fix is in `AdaptiveSheet`, not in the callers**: every wide dialog had the same cause.
2. **A wide dialog has its width, not up to it**: its content is laid out for that width (fixed
   panes of navigation, rows of 520 dp), and a dialog that changed width with its content would
   jump between sections. The narrow ones keep the behavior of Material 3.
3. **The screenshot tests draw the real dialogs** (`AdaptiveSheet`, `AssistantSettingsDialog`,
   `SearchSettingsDialog`), not their content in a column of a fixed width: the capture of the
   window shows the dialog over its scrim, so the screenshots show the width the dialog gets.
4. **The full-width rows of a sheet stay rectangular** (list items in Material 3); only the rows
   inside a padded content are clipped.

## Left for later

- A check of the sources that finds a `clickable` after a shape without a `clip` (a lint of its
  own): the rule in `docs/ui-design.md` and the review are enough for now.
- The pointer as a hand on the desktop: macOS and Windows keep the arrow on the buttons of an
  application.
