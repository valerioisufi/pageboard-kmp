# Interface design

The interface of PencilNotes and of the DrawView modules follows the design drawn in Figma
(*PencilNotes — UI Design*, https://www.figma.com/design/NdCxFKGnb6zm6XncFB7MzT): the tokens
(colors, type, shapes, elevation, icons), the components built on them and the screens of the
library, the editor, the texts and the selection, the pages, the export, the sharing and the
lecture assistant, each at the sizes of [adaptive-ui.md](adaptive-ui.md).

The design keeps the behavior of the application: every screen of the file shows something the
modules already do, drawn again. A few elements are new and are marked as such in the file and
below (the search, the filters, the empty states, the counts of the navigation).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* **Theme**: `DrawViewTheme` (drawview-ui) has a blue palette and Nunito; `PencilNotesTheme` a
  green palette (the one of the design) and the default typography of Material but the title in
  Cherry Bomb One. Shapes are the defaults of Material; no elevation of its own.
* **Icons**: Material icons (filled and outlined mixed) and a few vector drawables of the tools.
  The design uses Lucide on a 24 dp grid with a 2 dp rounded stroke.
* **Canvas**: the desk is `surfaceVariant`, the page has no shadow; the rule of the ruled paper and
  the grid are a lavender of their own (`PageBackground`), the guides of the snapping a pink.
* **Library**: a centered title bar with the name of the application, the sort as a text button
  and an arrow, cards of folders with a large folder image, cards of documents with the date
  written as "Last modified: …"; a list with icons of the type on a phone; the navigation pane
  of a wide window with Home, the folders and the recent notes; the details beside it as a list
  of commands. No search, no filter, no count, no badge but "shared".
* **Editor**: a top bar 48 dp high with a centered title in the primary color, the tools in a row
  36 dp each with a primary container behind the selected one, the drawing mode toggle; the
  settings of a tool in a dropdown (the presets of the pen expand inline in the bar).
* **Panels, dialogs, sheets**: Material defaults (`AlertDialog`, `ModalBottomSheet`,
  `DropdownMenu`, `PrimaryTabRow`).

## Design

### Tokens

| Token | Figma | Code |
|---|---|---|
| Colors | collection *Color*, modes Light and Dark, the roles of Material 3 | `ColorScheme` of `DrawViewTheme`: the palette of the design is the default of DrawView, an application can give its own |
| Canvas | *Canvas*: desk, paper, paperRule, paperMargin, paperGrid, guide | `CanvasColors` (`LocalCanvasColors`): the desk and the paper of the editor, the lines of the papers, the guides |
| Ink | *Ink* (6) and *Highlight* (5) | `InkPalette`: the swatches of the pen, the highlighter, the text, the marks of the PDFs |
| Type | Plus Jakarta Sans: Headline L/M, Title L/M/S, Body L/M/S, Label L/M/S | `drawViewTypography()`; Cherry Bomb One for the brand stays in PencilNotes |
| Shapes | radius 4, 8, 12, 16, 20, 24, 28, full | `DrawViewShapes` (Material `Shapes`) and `DrawViewRadius` |
| Elevation | Elevation 1, 2, 3, Paper/Shadow | `DrawViewElevation`: the shadow of cards, floating bars and popovers, of the page |
| Icons | Lucide (ISC) | `DrawViewIcons`: `ImageVector`s generated from the icons of the file |

### Components

| Component | Where | Notes |
|---|---|---|
| Buttons (filled, tonal, outlined, text; M 40, L 52) | Material buttons with the shapes of the theme | full radius |
| Icon button (standard, tonal, filled; selected) | `DrawIconButton` | 40 dp |
| Tool button | `ToolButton` | 44 dp, radius 16, primary container when chosen, the dot of the ink |
| Tool group | `ToolGroup` | a pill of `surfaceContainer` (radius 22) around undo, redo, the tools |
| Chip | `FilterChip` | 36 dp, full radius, outlined / primary container |
| Badge | `LabelBadge` | 22 dp, the tones green, blue, red, yellow |
| Navigation item | `NavigationItem` | 48 dp, full radius, with a count |
| Search field | `SearchField` | 48 dp, `surfaceContainerHigh`, the shortcut where there is a keyboard |
| Floating action button | Material extended / icon FAB | `secondaryContainer`, radius 20 |
| Note card, folder card, list row | explorer | the thumbnail in a well, the tile of the folder in its tone |
| Recording pill | lecture | `inverseSurface`, the live dot, stop |
| Suggestion card | lecture | kind, important, the quote, Insert / Listen / I already have it / Dismiss |
| Menu item | `CommandMenu` | 40 dp, radius 12, the shortcut at the end |
| Text field, switch, avatar, snackbar | Material with the tokens | |
| Selection frame, format bar, selection bar, markup bar | editor | already drawn by the code as in the file (`FrameStyle`); colors and sizes of the bars |

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The tokens of the design: palette, type, shapes, elevation | `DrawViewTheme`, `drawViewTypography`, `DrawViewShapes`, `DrawViewElevation` (ui) | Information Expert, Pure Fabrication |
| The colors of the canvas and of the inks | `CanvasColors`, `InkPalette` (ui) | Information Expert: the editor gives them to `DrawThemeColors`, the tools to their swatches |
| The icons | `DrawViewIcons` (ui) | Pure Fabrication, Low Coupling (no Material icons in the screens) |
| The number of pages and the PDFs of a document | `DocumentSummary` (data), from the store | Information Expert: the store has the pages |
| What the library shows: the section (home, recent, shared), the search, the filter, the counts | `FileExplorerViewModel` (explorer) | Information Expert, Controller |
| What an application says of a document (e.g. it has a lecture) | `DocumentTag` (explorer), given by `DocumentLibrary(tags = …)` | Polymorphism, Protected Variations: the library does not know the lectures |
| The brand of the application in the library | `title` of `DocumentLibrary` | Low Coupling: PencilNotes draws its mark and wordmark |
| The look of each screen | the composables of each module | High Cohesion: each module draws its own screens with the components of `drawview-ui` |

### Modules

```
drawview-ui        theme/ (DrawViewTheme, typography, shapes, elevation, CanvasColors, InkPalette,
                   DrawViewIcons), components/ (buttons, chips, badges, search, navigation, tools),
                   the editor (top bar, tools, popovers, bars, page grid, configurator, menus)
drawview-data      DocumentSummary.pageCount, hasPdf
drawview-explorer  the library: header, search, filters, folders, cards, rows, navigation,
                   details, dialogs, empty states; DocumentTag
drawview-sync-ui,  their dialogs, buttons and layers with the components of drawview-ui
drawview-export,
drawview-lecture
pencilnotes        the brand (mark and wordmark), the tag of the lectures
```

## Plan

| Phase | Content | Status |
|---|---|---|
| 1. Foundations | Plus Jakarta Sans, the type scale, the shapes, the elevation, `CanvasColors`, `InkPalette`, `DrawViewIcons`; the palette of the design as the default of DrawView; PencilNotes on `DrawViewTheme` | done |
| 2. Library | Page count and PDFs in the summaries; header, search, filters, folders, note cards, rows, navigation with counts and brand, details pane, FAB; `DocumentTag` and the tag of the lectures; recording pill | |
| 3. Library flows | The new item menu, the sheets of an item and of its details, the selection bar, the dialogs (new folder, rename, move, delete), the path inside a folder, the empty states, About | |
| 4. Editor | The top bar (title and its line, the tool group, the actions), the tool button with its ink, the floating tools, the page indicator, desk and paper, the popovers of the tools, the "more" menu | |
| 5. Texts and selection | The format bar, the selection bar, the bar of the marks of the PDFs, the context menu, the messages | |
| 6. Pages | The page grid, the configurator (dialog and sheet), "Apply to all pages?", the information of the document, the tooltips | |
| 7. Export and sharing | The export dialog and sheet with their states; the sharing dialog (owner, participant), joining, the requests, the people on the page, read only | |
| 8. Lecture assistant | The panel (recording, tabs, notes, transcript and player, outline, review), the notices, the settings, the recording pill, the phone recording only | |

Each phase ends with the screenshot tests recorded again (`recordRoborazziJvm`) and compared with
the frames of the file.

### Phase 1 results

* **Palette**: the palette of the design (the one of PencilNotes) is the default of
  `DrawViewTheme`, which takes the schemes of an application (`lightColors`, `darkColors`);
  `PencilNotesTheme` is `DrawViewTheme` and keeps only its wordmark (Cherry Bomb One).
* **Type**: Plus Jakarta Sans (regular, medium, semibold, bold, extrabold) replaces Nunito; the
  scale of the file for every role of Material (`drawViewTypography`).
* **Shapes**: `DrawViewRadius` and `DrawViewShapes` (4, 8, 12, 16 and 28 for the roles of Material).
* **Elevation**: `DrawViewElevation` (levels 1, 2, 3 and the paper) as pairs of shadows
  (`Modifier.elevation`, the `dropShadow` of Compose), as the effects of the file.
* **Canvas**: `CanvasColors` (the desk: the dimmest surface in the light theme, the darkest in the
  dark one), given by the theme and used by the editor; `InkPalette` for the swatches.
* **Icons**: `DrawViewIcons`, 143 Lucide icons as `ImageVector`s: the 98 of the file, exported
  from its components, and 45 more of Lucide for the actions the file does not draw.
* Licenses: Plus Jakarta Sans and Lucide added, Nunito removed.
