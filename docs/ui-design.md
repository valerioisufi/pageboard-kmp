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
| What a module says of a document (e.g. it has a lecture) | `DocumentTag` (ui), given by `DocumentLibrary(tags = …)` | Polymorphism, Protected Variations: the library does not know the lectures |
| The actions of the application in the library (join a shared document, about) | `LibraryAction` (ui), placed by the library | Polymorphism, Low Coupling: a button of the bar or of the pane |
| The brand of the application in the library | `title` of `DocumentLibrary` | Low Coupling: PencilNotes draws its mark and wordmark |
| Why a document may only be read ("Valerio’s Mac decides who edits") | `EditorExtension.readOnlyNote` (ui), given by the collaboration | Protected Variations: the editor shows "Read only" without knowing the sharing |
| What the sharing dialog shows and what the user does there | `SharingState`, `SharingActions` (sync-ui), drawn by `ShareContent` | Pure Fabrication: the dialog gathers them from `Collaboration`; the content is drawn without it (the screenshots) |
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
| 2. Library | Page count and PDFs in the summaries; header, search, filters, folders, note cards, rows, navigation with counts and brand, details pane, FAB; `DocumentTag` and the tag of the lectures; recording pill | done |
| 3. Library flows | The new item menu, the sheets of an item and of its details, the selection bar, the dialogs (new folder, rename, move, delete), the path inside a folder, the empty states, About | done |
| 4. Editor | The top bar (title and its line, the tool group, the actions), the tool button with its ink, the floating tools, the page indicator, desk and paper, the popovers of the tools, the "more" menu | done |
| 5. Texts and selection | The format bar, the selection bar, the bar of the marks of the PDFs, the context menu, the messages | done |
| 6. Pages | The page grid, the configurator (dialog and sheet), "Apply to all pages?", the information of the document, the tooltips | done |
| 7. Export and sharing | The export dialog and sheet with their states; the sharing dialog (owner, participant), joining, the requests, the people on the page, read only | done |
| 8. Lecture assistant | The panel (recording, tabs, notes, transcript and player, outline, review), the notices, the settings, the recording pill, the phone recording only | done |

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

### Phases 2 and 3 results

* **Data**: `DocumentSummary.pageCount` and `hasPdf`, counted by the store (a query of Room over
  the pages and the PDFs not removed; the key-value store when it writes a document).
* **What the library shows** (`FileExplorerViewModel`): the sections (`Section`: the folders from
  the root, the notes opened last, the shared ones) with their counts, the search in the names of
  the whole library, the filters (`LibraryFilter`: all, the notes written, a tag), the view of the
  notes (`ViewMode`: cards or rows), the notes of each folder at any depth, the path of the folder
  shown, the notes made on a paper in a folder (`createNote`).
* **Tags** (`DocumentTag`, drawview-ui): the PDFs and the shared documents are tags of the
  library, the lectures a tag of `drawview-lecture` (`LectureAssistant.documentTag()`, from
  `documentsWithLectures`): the badge of a card and a filter, the library not knowing them.
* **Actions of the application** (`LibraryAction`, drawview-ui): `actions` of `DocumentLibrary`
  are no longer a row of buttons but actions the library places: icons of its bar, or wide
  buttons of its pane (`prominent`, joining a shared document: `Collaboration.joinAction()`).
  `aboutLibraryAction()` is the default.
* **Screen**: a phone (the brand and the actions, the title with what it holds, the search, the
  filters, the folders in a row, the notes in rows), a tablet (the search in the bar, the views
  and the order beside the title, the folders and the notes as cards), a wide window (the pane of
  navigation with the brand, the sections and the folders with their counts, making a folder and
  the actions of the application; the header with the search, the views and the order; the
  details of the selection beside: an item, or how many with moving and deleting). The floating
  button makes a note beside the pane, opens the choice of a note or a folder elsewhere.
* **Flows**: the sheet of an item (its commands, "Info" showing its details), the selection bar,
  the dialogs (new folder, rename, move with the path, delete with the shared notes said), the
  sheet of a new note (its name, its paper, its folder, importing a PDF or an image; the note made
  is opened), the empty states (no note, an empty folder or section, a search or a filter without
  results), the label of the items dragged ("Move to Physics"), About & Licenses (the mark and the
  wordmark of the application, `AboutAppInfo.mark` and `nameFontFamily`).
* **Menus** (`DesignMenu`, `MenuItem`, `CommandMenu`): the container of the surfaces rounded
  16 dp, the items 40 dp rounded 12 dp, the shortcuts at the end, `Command.destructive` in the
  color of the errors.
* **Not as the file**: the search looks at the names only (the file says the typed text and the
  text of the PDFs too); the empty library does not mention the lectures (the library does not
  know them); "Share…" is not among the actions of a note of the library (the sharing starts from
  the editor).
* Tests: the search, the filters by kind and by tag, the sections and their counts, the notes
  made on a paper in a folder (`LibraryViewTest`); the pages and the PDFs of the summaries; the
  screenshots of a search without results and of a folder.

### Phase 4 results

* **Top bar** (`EditorTopBar`): back and the page grid, the title of the document with its folder
  and its pages (on a phone the page looked at) opening its information (`DocumentInfoSelector`:
  folder, dates, pages, format, elements), the tools in their group (`ToolGroup`, a pill of the
  container of the surfaces) between two spaces, the actions of the extensions (the assistant
  tonal, highlighted while its panel is open; export and share as icons, `EditorActionButton`),
  the "more" menu. 64 dp, 52 on a phone; the folder comes from the store (`EditorSessionScreen`).
* **Drawing mode**: no longer a button of the bar (the file has none): a state of the "more" menu
  and of the menu bar, with its shortcut.
* **Tool button**: 44 dp rounded 16, the primary container when chosen, the dot of its ink (the
  color of the pen, of the highlighter, of the marks, of the texts).
* **Popovers of the tools** (`ToolPopover`, `ToolSettings.kt`): under the tool, the high container
  rounded 24; the pen and the highlighter (their stroke, the size, the presets as cards with their
  stroke and size, "+" and deleting one, the inks of `InkPalette` and the color wheel), the eraser
  (its ring, the size in millimeters, what it erases), the lasso (two option cards), the text (the
  size stepper, the inks, the alignment), the marks of the PDFs (their presets, the kind and the
  color of the one chosen). The presets no longer expand inside the bar.
* **Phone**: the tools floating at the bottom (a pill of the high container, level 2), the page
  indicator above them; elsewhere the indicator "3 / 12 · 100%" (the zoom is the one of the
  opening, the pages as wide as their width) at the bottom of the canvas.
* **Canvas**: the pages at most 880 dp wide at the zoom of the opening (`CalcPage`), the desk
  beside them on a wide window, as in the file.
* **Panels**: beside the canvas they take the whole height (the top bar covers the canvas only),
  on the low container, their header 64 dp; the bars choose their arrangement by the width of
  the canvas.
* **Menus and controls**: the commands of the editor with the icons of the design (Lucide), a
  state on with a check at the end, deleting in the color of the errors; the size of the texts
  as a pill stepper, the format toggles 36 dp rounded 10, the color as a ring.
* **Defaults**: the presets of the pen, of the highlighter and of the marks and the color of the
  texts take the inks of the design (new installations).
* Screenshot tests of the components that open over the screens (`drawview-ui/screenshots`,
  `ComponentScreenshotTest`): the popovers of the tools and a menu, light and dark.

### Phase 5 results

* **Bars over the canvas** (`ActionBar`, `BarAction`, `BarDivider`, `DoneAction`): the high
  container rounded 24 dp, level 2, their actions 40 dp.
* **Format bar**: the formats of the characters first (toggles 36 dp rounded 10, on in the primary
  container), then the size, the color and the alignment, then the check on the primary color
  closing the text. On a phone it lies on the keyboard the whole width (`docked`), the check always
  in sight; the page indicator hides while a text is written.
* **Selection bar**: editing a text alone, cut, copy, delete (in the color of the errors), the
  style of the texts selected.
* **Bar of the text of the PDFs**: "Copy" with its label, the presets of the marks (circles
  40 dp, the one of the mark selected ringed), deleting a mark.
* **Context menu of a page** (the long press, the secondary click): a menu of the design at the
  touch, paste with its shortcut, add a page after, move it, delete it, its background.
* **Messages**: `DesignSnackbar`, the inverse surface rounded 12 dp, the action in the inverse
  primary.
* Screenshot of the context menu of a page.

### Phase 6 results

* **Page grid**: its header (close, "Document Pages" with the document and its pages, adding a page:
  a tonal button, an icon on a phone), the pages as the cards of the library (the sheet in a well
  rounded 20, the one looked at when the grid opened ringed in the primary color, its name in the
  primary color), the menu of a page (add after, background, delete), the page dragged lifted and
  turned.
* **Configurator** (`PageTemplateConfigurator`): wider than 600 dp a dialog 760 dp wide with the
  preview in a well beside the controls and the button closing it; narrower a sheet with a small
  preview beside the title and the buttons the whole width. What it changes under the title (a
  page of a document, its pages); the format in a field of the design opening a menu, the custom
  size only for "Custom", the error under the fields; the pattern as a segmented choice
  (`SegmentedChoice`); the colors as the swatches of the tools; the sliders of the design (with
  steps, `DesignSlider`).
* **Apply to all pages?**: the icon of the layers, "Overwrite All" tonal.
* **Tooltips**: the inverse surface rounded 8, the shortcut lighter after the label.
* **Buttons**: `TonalButton` (the primary container, as the file; Material's tonal button takes the
  secondary one, the yellow of the floating button) and `OutlineButton` (the label in the primary
  color) for every module; `AdaptiveSheet` takes a wider dialog (`maxWidth`) and the containers of
  the design.
* Screenshots: the page grid (tablet, phone), the configurator as a dialog and as a sheet.

### Phase 7 results

* **Export** (`ExportDialog`, `ExportBody`): an `AdaptiveSheet` (a dialog, a sheet on a phone); the
  format and the pages as rows of a choice with their hints (the one chosen on the primary
  container, the pages of an image disabled), the pattern of the backgrounds as a switch with its
  check; exporting with its bar; done (with what was left out in a yellow box), failed; "Export"
  with the icon of the download. The progress gives no "Page 7 of 12" (the exporters give a
  fraction).
* **Sharing** (`ShareContent`): "Share the document" with what it does and the close button; where
  it is wide the invitation on its card (the QR code on white in both themes with its hint, the
  link in its field with copy, "Joining as" segmented with icons, "Approve who joins" a switch,
  "Copy the link" tonal and "Revoke"; revoked, the options and "New invitation") beside who asks to
  join (a banner of the tertiary container with Refuse and Let in), who is on the document (their
  avatars with the initials in their colors, the crown of the owner, for the owner the menu "Only
  reading" / "Can edit" and "Remove from the document") and "Your name"; at the bottom "End
  sharing" in red and Close. On a phone, or for a member who is not the owner, one column: the
  notices (only the owner invites, no local network, another version) in their boxes, the people,
  "Leave the document" outlined in red beside Close. A new role or approval gives a new invitation
  in place of the current one (the old link stops working, the devices joined stay). Before
  sharing, the options of the first invitation, Cancel and Share.
* **Confirmations**: End sharing and Leave the document with the icon in the color of the errors
  and the button in that color.
* **Requests** (`JoinRequestDialog`): the icon in the tertiary color, the device on a card with its
  avatar and the role it asks, Refuse and Let in.
* **Joining** (`JoinDialog`, `JoinProgress`): the link of the invitation and its error; waiting
  with a spinner, in (a check, Close filled), refused (an X), without the local network a yellow
  box and where the document will appear.
* **Working together**: the avatars of who is on the document beside Share (not on a phone, they
  open the sharing), their number on the primary color; "… is writing this text" with a lock; the
  devices in the inks of the design.
* **Read only** (`ReadOnlyBadge`): where the tools would be, "Read only" and who decides who edits
  (`EditorExtension.readOnlyNote`); floating at the bottom on a phone; the lock of the bar removed.
* Screenshots: the export states (light, dark) in drawview-export; the sharing of the owner (light,
  dark), of a member on a phone, before sharing, joining and the avatars in drawview-sync-ui (new
  screenshot tests, as the dialogs open in their windows); "Read only" in drawview-ui.

### Phase 8 results

* **Panel** (`lecturePanel`, `PanelContent`): the sparkles of the assistant before its title (the
  icon of `EditorPanel`, on the tertiary container) and the settings as sliders; above the tabs
  what the assistant is doing, scrolled when it is long (at most a little more than half of the
  panel).
* **Before recording**: what it does, what leaves the device on the high container with the shield,
  "Record the lecture" large with the microphone, "Import a recording" outlined; the lectures of
  the notebook as rows with their tile, when, how long, the review ready, their menu (delete);
  "Write the notes on this device" with its switch where the notes may be written and are not (a
  phone, a tablet recording only; not while recording).
* **A lecture shown**: on a card opening its menu, finished and how long ("1 h 28 min"), when, how
  many lectures; the menu with the lectures, exporting the audio, transcribing again, how the
  audio is shared, deleting it (asked first: the trash, the button in the color of the errors).
* **Recording** (`RecordingCard`): the inverse surface rounded 24, the live dot, "Recording
  lecture", the time, the waveform of the microphone (its level in the last seconds), what the
  assistant is doing and stop; analyzing under it with a spinner, "The lecturer is on page 4 ·
  Topic" with Go, "I missed something" outlined with the lightning. Recording only: one dark line
  (what it records, the time, stop) and what happens to the lecture. There is no pause: the
  recorder has none.
* **Import**: a card with its tile, the step and the progress.
* **Notices** (`LectureNotice`): the problems in the container of the errors (the key, the quotas,
  the local model, the start, the microphone denied) with Settings where it helps, the warnings in
  the yellow one (retrying, the microphone taken or silent), what happens elsewhere in the blue
  one (recorded or followed on another device, the audio arriving or to download, recorded
  without the models), the facts in the high container (one lecture at a time, deleted elsewhere,
  the local review writing).
* **Tabs**: a segmented choice ("Notes · 2", Transcript, Outline, Review). The notes on outlined
  cards: the sparkles, the kind, "Important", the time on a chip, the title, the text, "Insert ·
  p. 3" tonal, listen, already noted, dismiss; those handled on their rows with Restore. The
  transcript under the player card (the position, the speed, 10 s back and forward, play as a
  round filled button), each time on its chip playing from there, the segment playing on the
  primary container; the player stays above the other tabs while it plays. The outline: the
  course context on its card (Edit, Write again with their icons), the topic of now on the
  primary container, the outline. The review: by which model with Regenerate, its text on a card,
  "Insert as new pages" with the two places as a choice and Insert, the requests on the high
  container. The outline stays the Markdown of the model (not a list with the times as in the
  file); the notes have no quote (the model writes none).
* **Placing a note**: the inverse pill with the plus, "Go to p. 3" on the inverse primary color,
  "Esc" where there is a keyboard (Cancel elsewhere).
* **Settings** (`AssistantSettingsDialog`, `AssistantSettingsContent`): a pane of the sections
  (Requests, Gemini, On this computer, Lecture, Advanced) beside them in a dialog 940 dp wide,
  chips above them in the sheet of a phone; each group on a card: the requests with online or this
  computer (a segmented choice with icons, the one not possible dimmed) beside the model, the
  language spoken, the review at the end as a switch, what is sent to Google; the key and the
  reasoning; Ollama with its models (reads images, recommended) and the speech recognition; the
  language of the notes, the updates, the lectures received; the address of Ollama and a model
  file. The updates keep their slider (every 15 s), not three choices.
* Screenshots (drawview-lecture, new): the panel before recording, a finished lecture in its four
  tabs, the recording cards and the notices, the settings.
