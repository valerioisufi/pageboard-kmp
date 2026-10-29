# The experience of the export

Exporting is how the notes leave the application: to send them to someone, to print them, to
keep a copy. The engine is good (`docs/export.md`: real text, vector formulas, PDF pages kept);
the way to reach it and to use it is not. This plan gives the export one flow, the same for a
page, a note, several notes, a folder and the Home, reachable where the user looks for it, that
gives the file to where it is needed (saved, shared, shown) without blocking the application.

This document starts from the code as it is (main at 62116fed, 2026-10-29): the entry points,
the dialogs and their problems, then the requirements, the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

### The entry points

| Where | What | Notes |
|---|---|---|
| Card or row of a note: context menu and sheet (`LibraryCommands.forItem`) | "Export", icon Download | Section 2 of the menu |
| Card or row of a folder: same menus | "Export", icon Download | Same label as for a note |
| Selection bar, GRID layout only (`SelectionBar(withActions = true)`) | Under ⋮, only when one note **or** one folder is selected | Hidden; nothing with two items or more; nothing in the LIST and PANES layouts |
| Header of the library (`LibraryViewControls`, LIST title) | A tonal icon button Download beside the sort button, only in `Section.HOME` | An icon alone, the same as "download", next to the view buttons: it reads as a view control |
| Editor top bar (`DocumentExportUi.editorExtension`) | Icon Share (IosShare), ⇧⌘E | Another icon than the library for the same action |
| Menu bar of the desktop | The commands above, enabled by the selection | No ⇧⌘E in the library |
| Page grid of the editor | Nothing | The pages selected (`docs/page-selection.md`) cannot be exported |

### The dialogs

* Two composables, `ExportDialog` (a note) and `FolderExportDialog` (a folder or the Home), copy
  the same state machine (`Choosing → Running → Done | Failed`), the same progress, the same
  outcome, the same switch rows.
* **A note from the library** has no choice but the pattern of the background: the format and the
  pages appear only in the editor, where the PNG is "the page on screen" and nothing else.
* **The choices are the engine's, not the user's**: "Image of the page on screen (PNG) — As the
  editor draws it, at 200 dpi", "Lines, grid and dots of the background", "Every page, with the
  text of the PDFs and of the notes searchable". Nothing shows what will come out: no preview, no
  number of pages, no name of the file before the system dialog.
* **The title is the only context**: `Export “Lecture 1 — Limits”`, the same for a note and a
  folder; the folder shows "Notes to export: 12" at the bottom, under the switches.
* **The flow is modal from start to end**: the choices, then the system dialog, then a progress
  bar inside the sheet that cannot be left (dismissing cancels), then "The document has been
  exported." and Close. Nothing to open the file, show it in the Finder, share it or retry.
* **On the phones the file is only saved** (the document picker): sending a note by mail or chat,
  the most common reason to export on a phone, takes the Files app afterwards.
* **Nothing is remembered**: the format and the background are chosen again every time.
* **The errors are one sentence** ("The document could not be exported."), the cause printed on
  the console (`println`); the elements skipped are a count without a name.
* **The progress is a bar without words**: for a folder of 40 notes, no "12 of 40", no name.

### The engine

* `DocumentExport.export(documentId, exporter, ExportOptions(pages, backgroundPattern), path)` for
  one note; `exportFolder(folderId, …, FolderExportFormat.ZIP | COMBINED_PDF)` for a folder;
  `folderDocuments` resolves a folder (the trash left out by `DocumentCatalog.documents`).
* `PageImageExporter` writes the **first** page asked only (one PNG).
* `rememberFileSaver(title, mimeType)` → `FileTarget(path, deliver)`: the save dialog of the
  desktop, `CreateDocument` on Android, the document picker on iOS. No sharing, no reveal.
* `DocumentAction` and `FolderAction` (`drawview-ui`) take one target each.

## Requirements

1. **One flow for everything that can be exported**: the page on screen, the pages selected in
   the page grid, a note, several notes (also with folders among them), a folder, the Home. The
   same sheet, the same words, the same order.
2. **Found where it is looked for**, with one icon (Share, `IosShare`) and one label ("Export…")
   everywhere:
   * the context menu and the sheet of a note and of a folder;
   * the selection bar of every layout, as a visible button, for any selection (one or more
     notes, folders, both);
   * the menu "⋯" of the folder shown (and of the Home), labelled "Export folder…" / "Export
     all notes…", instead of the loose icon in the header;
   * ⇧⌘E in the library (the selection, or the folder shown when nothing is selected) as in the
     editor, and File › Export… in the menu bar;
   * the top bar of the editor, and the bar of the page grid when pages are selected.
3. **The sheet says what will come out** before anything is written:
   * a summary on top: a thumbnail of the first page, the name, "3 notes · 42 pages" or "1 page";
   * the format as a few large choices, only those that make sense for what is exported
     (see [Formats](#formats));
   * the pages, for one note: all, the page on screen, the pages selected, a range ("3–7");
   * the options under the format, in plain words: "Background lines and grid", "Include
     subfolders" (for a folder), each with a short consequence when it is not obvious;
   * the name of the file that will be written ("Lecture 1 — Limits.pdf", "Physics.zip"),
     editable;
   * a disabled export with its reason in place ("This folder has no notes") rather than a
     warning box below.
4. **The file goes where it is needed**:
   * **desktop**: "Export…" opens the save dialog in the last folder used; when done, "Show in
     Finder" (Explorer, the file manager) and "Open";
   * **phones and tablets**: "Share…" (the system share sheet: mail, chat, print, AirDrop, Save
     to Files) is the primary action, "Save to Files" the secondary one;
   * the same choices on every platform otherwise.
5. **Not modal while it works**: once the file is chosen, the sheet closes; the export goes on
   with a small progress indicator in the library and in the editor ("Exporting Physics · 12 of
   40", Cancel), the user keeps working, can export something else meanwhile (queued). When done,
   a snackbar: "Physics.zip exported" with Show / Open / Share; on failure, its cause and Retry.
   A note of one or two pages ends before the sheet animates out: the outcome is shown in the
   snackbar all the same.
6. **The choices are remembered** within the session of the application, per kind of export
   (format of a note, format of several, background), and offered again.
7. **The errors say what to do**: no space left, no permission to write there, a note that cannot
   be opened (its name), the elements left out because their file is missing (the notes they are
   in). Nothing on the console only.
8. **The same on every form factor** (`docs/adaptive-ui.md`): a bottom sheet on the phones, a
   dialog on tablets and desktop, the keyboard working (Enter exports, Escape closes, the arrows
   move between the choices), the screen readers reading the choices and the progress.
9. **No change to what is stored or to what travels**: no Room schema, no stored format, no
   protocol change. The choices remembered stay in memory (see [Decisions](#decisions)).

### Formats

| What is exported | Formats offered (first = default) |
|---|---|
| One page (on screen, or one selected) | PDF · Image (PNG) |
| Several pages of a note, or a whole note | PDF · Images (PNG, one per page, in a ZIP) |
| Several notes, a folder, the Home | One PDF per note (ZIP, folders kept) · One PDF with all the notes |

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| What is exported: pages of a note, notes, a folder, the Home | `ExportScope` (sealed, `drawview-export`): `Pages(documentId, pageIds?)`, `Documents(ids, folderIds)`, `Folder(folderId?)` | Information Expert |
| What a scope gives: its notes, their paths in an archive, the number of pages, the first page for the thumbnail | `DocumentExport.resolve(scope, includeSubfolders)` → `ExportContents` | Information Expert, Controller |
| The formats of a scope, its default, the name of its file | `ExportFormat` (sealed: `Pdf`, `Png`, `PdfPerDocument`, `CombinedPdf`) with `ExportFormat.offeredFor(contents)`, `fileName(contents)` | Polymorphism, Information Expert |
| Writing a format: one exporter per format, as today, plus the PNGs of several pages in a ZIP | `DocumentExporter` (`PdfExporter`, `PageImageExporter`), `ZipWriter` | Polymorphism, Protected Variations |
| Running the exports: queue, progress (fraction, "item n of m", name), cancel, retry, outcome | `ExportJobs` (`drawview-export`, a `StateFlow<List<ExportJob>>`, its own scope) | Controller, Pure Fabrication |
| Why an export failed | `ExportFailure` (sealed: `NoSpace`, `NotWritable`, `DocumentUnreadable(name)`, `Unknown`) and `ExportOutcome.skipped` per note | Information Expert |
| The choices of the sheet and their rules (format offered, pages valid, file name, export enabled and why not) | `ExportSheetState` (`drawview-export-ui`), plain state holder tested without Compose | Pure Fabrication, High Cohesion |
| The choices remembered | `ExportPreferences` (in memory, owned by `DocumentExportUi`) | Information Expert |
| The sheet: summary, format, pages, options, name, actions | `ExportSheet` (`drawview-export-ui`), replacing `ExportDialog` and `FolderExportDialog` | High Cohesion |
| The progress and the outcome outside the sheet | `ExportStatusHost` (`drawview-export-ui`), shown by the library and the editor | Indirection, Low Coupling |
| Where the file goes: save (with the last folder), share, show, open | `FileTarget` + `rememberFileSharer`, `FileReveal` (`drawview-ui`, `expect`/`actual`: `ACTION_SEND` with a `FileProvider` on Android, `UIActivityViewController` on iOS, `Desktop` / `open -R` on the desktop) | Protected Variations, Indirection |
| An action of the application on the items of the library | `LibraryItemsAction(icon, label, content(selection, onDone))` (`drawview-ui`), replacing `DocumentAction` and `FolderAction`; `LibrarySelection(documents, folders, currentFolder)` | Low Coupling, Protected Variations |
| Placing the actions: menus, selection bar of every layout, menu of the folder shown, ⇧⌘E, menu bar | `LibraryCommands` (`drawview-explorer`) | Information Expert, Controller |
| The actions on the pages selected in the grid | `EditorExtension.pageGridActions` (`drawview-ui`) | Protected Variations |

* **One scope, one sheet**: the sheet asks the scope for its formats and its summary; it knows no
  difference between a note and a folder. Adding a format (the archive of the application, later)
  adds a case to `ExportFormat` and an exporter, nothing in the sheet.
* **`ExportJobs` outlives the sheet**: it belongs to `DocumentExportUi` (one per application), so
  that closing the sheet, leaving the editor or opening another note does not stop an export. It
  writes into the file the system gave (`FileTarget`) and delivers it at the end, as today.
* **The documents are read as today**: one at a time, in their session (`OpenDocuments`), memory
  flat whatever the number of notes. The combined PDF stays one call, as `exportCombined` does.
* **The thumbnail** is the one the library already draws for the card of the first note; the
  sheet takes it from the library (`LibrarySelection`) and from the editor (`EditorContext`), and
  shows an icon when there is none.
* **The library API**: `DocumentAction` and `FolderAction` become one `LibraryItemsAction`, which
  takes the selection as it is. The ABI of `drawview-ui` and `drawview-explorer` changes
  (`updateKotlinAbi`, said in the commit); nothing is released yet.
* **The editor**: the export command takes the pages selected in the grid when there are some,
  else the page on screen is offered as a choice; `EditorExtension` gains `pageGridActions`, a
  hook decided now, before the first release (see "Planned work" in `CLAUDE.md`).

### The sheet

```
┌──────────────────────────────────────────┐
│ ▢▢  Lecture 1 — Limits                    │  thumbnail, name
│     1 note · 12 pages                     │  summary
│                                           │
│ Format                                    │
│ ┌──────────────┐ ┌──────────────┐        │  large choices, icon + name +
│ │ ▤  PDF       │ │ ▨  Images     │        │  one line of consequence
│ │ Text stays   │ │ One PNG per   │        │
│ │ searchable   │ │ page          │        │
│ └──────────────┘ └──────────────┘        │
│ Pages   (•) All  ( ) On screen  ( ) 3–7   │  only for one note
│ Background lines and grid          [on]   │  options
│                                           │
│ File   Lecture 1 — Limits.pdf      ✎      │  editable name
│                                           │
│               Cancel   [ Share… ] / [Export…] │  primary by platform
└──────────────────────────────────────────┘
```

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `drawview-export`: `ExportScope`, `DocumentExport.resolve` → `ExportContents`, `ExportFormat` with `offeredFor` and `fileName`; the export of several notes (ZIP, combined) from a scope, `exportFolder` built on it; the PNGs of several pages in a ZIP; tests | Done |
| 2 | `ExportJobs`: queue, progress with "n of m" and the name, cancel, retry, `ExportFailure` from the exceptions of the platforms, skipped elements per note; tests with a fake exporter | Done |
| 3 | `drawview-ui`: `rememberFileSharer` (Android `FileProvider` + `ACTION_SEND`, iOS `UIActivityViewController`), `FileReveal` / open on the desktop, the save dialog in the last folder used | Done |
| 4 | `drawview-export-ui`: `ExportSheetState` (tested), `ExportSheet` (summary, formats, pages and range, options, name, actions by platform), `ExportPreferences`; `ExportDialog` and `FolderExportDialog` removed; strings rewritten in the seven languages; screenshots of every state on phone, tablet, desktop, light and dark | Planned |
| 5 | `ExportStatusHost`: the progress and the outcome outside the sheet, in the library and the editor (Show, Open, Share, Retry); screenshots | Planned |
| 6 | Entry points: `LibraryItemsAction` and `LibrarySelection` replacing `DocumentAction` / `FolderAction`; the selection bar of every layout with a visible Export; the menu of the folder shown; ⇧⌘E and File › Export… in the library; the icon of the editor everywhere; PencilNotes and the sample; ABI | Planned |
| 7 | The page grid: `EditorExtension.pageGridActions`, Export on the pages selected; ABI | Planned |
| 8 | Verification: `verifyRoborazziJvm` (explorer, export-ui), the desktop app on a scratch data directory, Android and iOS simulators (share sheet, save to Files), a folder of many notes exported while working | Planned |

Phases 1–2 (engine) and 3 (platforms) are independent; 4 needs 1 and 2; 5 needs 2 and 3; 6 and 7
need 4.

## Decisions

Taken alone, revertible:

* **Share first on phones and tablets, save first on the desktop.** On a phone a file is sent far
  more often than kept; on the desktop the system dialog is the habit. Both are always there.
* **The choices are remembered in memory only**, for the session of the application: remembering
  them across launches would add preferences, a stored format to migrate. Later, if wanted.
* **`DocumentAction` and `FolderAction` merged** into `LibraryItemsAction`: the library has one
  selection, the actions take it as it is. Breaks the ABI of `drawview-ui` and
  `drawview-explorer`, not released yet.
* **The progress leaves the sheet** (non-modal). A small export ends in the snackbar too, so that
  the outcome is always in one place.
* **One icon, Share (`IosShare`)**, for the export everywhere, as in the editor today; Download no longer stands for it.
* **The page range** is written as text ("3–7, 10"), checked as it is typed, rather than a
  picker of pages: the page grid already selects pages for the cases where seeing them matters.

## Left for later

* The archive of the application (`ArchiveExporter`, `docs/export.md`) as a third format of a
  note, when its format becomes a contract.
* Exporting the notes of the lectures (Markdown) from the same sheet.
* An estimate of the size of the file in the sheet.
* Remembering the choices across launches (a stored preference, with its migration).
* Outlines (bookmarks) per note in the combined PDF, when the bridge of PDFium can write them.
