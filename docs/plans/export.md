# Export of the documents

The documents are exported as files to keep, send or print: a PDF of the whole notebook first,
then the image of a page, later a file of the application itself (a copy of the document with its
resources, to bring it to another device without the synchronization). Like the synchronization,
the export is an opt-in module: the applications add `drawview-export`, the others pay nothing.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* Every layer of a page is drawn by `TileRenderer.renderPageContent` on a Compose `Canvas`: the
  order of the layers is written there only, the patterns of the backgrounds twice
  (`PageBackgroundRenderer`, and `PageRenderer`, no longer used).
* Skiko exposes no PDF backend of Skia (SVG only), so a PDF cannot be drawn through the canvas
  on the desktop and on iOS; drawn so on Android, the PDF pages would be images.
* PDFium, bundled on every platform for reading, can also write PDFs: new documents, pages of
  another document as form objects (vectors and text kept), paths, images, annotations, saving.
  The bridge exposes its reading functions only.
* The strokes are triangle meshes; Ink also gives their outlines, filled with the non-zero
  winding rule (as its Skia renderer draws them): the shape of the stroke in a few points,
  without seams between the triangles in the PDF readers.
* `FileTarget` and `rememberFileSaver` (where to write a file the user saves: the dialog of the
  desktop, the document pickers of the phones) are internal to `drawview-lecture`.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Use case "export a document": get the document, copy its content, pick the format, progress and cancellation, deliver the file | `DocumentExport` (`drawview-export`) | Controller, High Cohesion |
| Give the document, open or not, without loading a second copy | `OpenDocuments.open(id)` then `OpenDocument.edit { }` | Creator, Information Expert (one session per document) |
| Copy of the content of a page that stays as it is (its elements copied too: they change in place) | `DrawPage.snapshotContent().detached()` | Information Expert |
| The page being looked at in the editor (exported alone) | `DrawViewController.mostVisiblePageIndex` (moved from the lecture panel) | Information Expert |
| A format | `DocumentExporter`: `PdfExporter`, `PageImageExporter`, later `ArchiveExporter` | Polymorphism, Protected Variations |
| Order of the layers of a page (PDF pages, marks, images, texts, strokes) | `PageContentSnapshot.paintOrder` (`drawview-core`) | Information Expert |
| Pattern of a background (lines and dots in millimeters) | `PageBackground.pattern` (`drawview-core`) | Information Expert |
| Outline of a stroke | `InkStrokeEngine.outline` (the Ink bridge), the triangles of the mesh when the engine cannot model it again | Information Expert |
| Lines of a mark in millimeters | `DrawTextMarkup.pointsToPageMm` | Information Expert |
| Place of a PDF page in a page | `fitPdfPage` | Information Expert |
| Write a PDF with primitives: pages, paths, images, marks, pages of another PDF, saving | `PdfWriter` (`drawview-pdf`), created by `PdfiumEngine.create()` | Pure Fabrication, Creator, Low Coupling |
| Draw the texts and the formulas; the words and the formulas of a text as it lays them out (the text of the PDF) | `TextTileAdapter.drawText`, `TextTileAdapter.layout` (interface of `drawview-core`; `ComposeTextTileAdapter` of the editor) | Information Expert, Indirection, Protected Variations |
| Where to save the file | `FileTarget`, `rememberFileSaver` (moved to `drawview-ui`) | Indirection, Protected Variations |
| Actions of the application on a document of the library | `DocumentAction` (`drawview-ui`, beside `EditorExtension`), shown by `DocumentLibrary` in the bar of the selection | Low Coupling, Protected Variations |
| "Export" in the library and in the editor | `DocumentExportUi` (`drawview-export`): a `DocumentAction` and an `EditorExtension`, both opening the export dialog | Low Coupling, Protected Variations |

* The polymorphism is on the format, the variation that matters: the model gives the data
  (outline, lines of the marks, pattern, place of the PDF page), never the output, so that it
  knows nothing of the PDF. The kinds of elements are told apart by an exhaustive `when` on the
  sealed `DrawElement`: a new kind does not compile until the exporters handle it.
* `PdfWriter` knows nothing of the documents: `drawview-pdf` stays a PDF library, the
  translation of a document into a PDF is all in `PdfExporter` (tested with a fake writer too).
* `DocumentExport` copies the content in the session of the document (`OpenDocument.edit`), so
  that a change of another device arriving meanwhile cannot make a PDF of two states, then writes
  the file on `Dispatchers.IO`. The editor and the library give it the identifier of the
  document and the pages: `DrawViewController` is not involved.
* The elements hidden while selected, moved or edited (`isHidden`) are exported: the renderer
  skips them because the editor draws them over the tiles.

### The PDF

| Element | Written as |
|---|---|
| Page | a page of the same size (millimeters to points) |
| Background | its color and its pattern, vector lines and dots (an option leaves the pattern out) |
| PDF page (`DrawPdf`) | the page of the original PDF as a form object, placed where the page shows it: vectors, text and fonts kept (searchable, selectable); its annotations flattened into it first |
| Highlight, underline, strikeout (`DrawTextMarkup`) | an annotation of the same kind (QuadPoints, color), recognized by the other readers; the underlines and the strikeouts drawn where and as thick as the editor draws them |
| Image (`DrawImage`) | an image object; a JPEG is embedded as it is (turned by its place when its Exif turns it), the others as pixels |
| Text, Markdown, LaTeX (`DrawText`) | its words as text of the PDF, where the editor lays them out, in the font of the editor (a subset of Noto Sans embedded); its formulas as vectors; the words with characters out of the font as images at 300 dpi (see [Quality of the PDF](#quality-of-the-pdf)) |
| Stroke (`DrawStroke`) | its outline filled (non-zero winding), with the opacity of its color |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | Core: order of the layers in `PageContentSnapshot`, pattern of the backgrounds in `PageBackground` (renderer using it, `PageRenderer` removed), outlines of the strokes from the Ink bridge (JNI, cinterop) with the triangles as fallback | Done |
| 2 | `PdfWriter` in `drawview-pdf`: writing functions of the PDFium bridge (C, JNI, cinterop) | Done |
| 3 | `drawview-export`: `DocumentExporter`, `PdfExporter`, `DocumentExport`; tests exporting documents and reading the PDFs again with PDFium | Done |
| 4 | Interface: `FileTarget` / `rememberFileSaver` in `drawview-ui`, `DocumentAction` in the library, "Export" in the editor and in the library (format, pages, background), PencilNotes | Done |
| 5 | `PageImageExporter`: the page on screen as PNG | Done |
| Later | `ArchiveExporter` and its import (to define near the first release, when its format becomes a contract); the notes of the lectures as Markdown | |

### Results

* `PdfWriter` (`drawview-pdf`) on the writing functions of the PDFium bridge: paths (non-zero
  winding), JPEGs embedded as they are (their header read first: PDFium does not tell an unreadable
  one), pixels with their transparency, pages of another PDF as form objects (their annotations
  flattened first, the rotation and the crop box of the page followed), markup annotations
  (printed, their appearance generated by PDFium when the page is finished, so that the readers
  without a generator of their own show them too), invisible text in Helvetica.
* The outlines of the strokes come from Ink (`ink_stroke_builder_get_outlines`), all of them
  turned the same way (`StrokeOutline`): a translucent stroke is filled once where its outlines
  overlap. Without the native engine, the triangles of the mesh.
* A JPEG turned by its Exif orientation is decoded and embedded as pixels (the systems decode it
  turned or not); since Q6 it is embedded as it is, turned by its place.
* Tests (`drawview-pdf`, `drawview-export`, JVM): the PDFs written are read again with PDFium
  (pages, sizes, text of the copied pages and of the texts, colors of the strokes, images,
  backgrounds and highlights at their place, pages copied with a rotation or a crop box compared
  pixel by pixel with the original); the order of the elements and the files missing with a
  recording writer; the export of a document through `OpenDocuments`. A PDF exported is also
  drawn as expected by PDFKit (macOS), with its highlight listed as an annotation.
* Limits, lifted by the [quality phases](#quality-of-the-pdf): the invisible text of the texts
  kept the Latin-1 characters only (Helvetica of the standard fonts); the formulas were images
  without text.

## Quality of the PDF

The PDF is written the same way on every platform, by PDFium, from data the model and the
editor give in common code: no drawing of a platform is captured.

* The text of the notes is real text of the PDF (selected, searched, copied in every language
  its font covers), placed word by word where the editor lays it out. Its font is the one of the
  editor on every platform: Noto Sans (and Noto Sans Mono for the code), shipped with
  `drawview-ui`; each PDF embeds a subset of it (the characters used). The characters the font
  does not have (emoji, Chinese, Japanese, Korean), drawn by a font of the system, are images of
  their words.
* The formulas are vectors: MicroTeX describes them the same way on every platform (characters of
  its TrueType fonts, lines, rectangles), and the outlines of the characters are read from the
  fonts. The `\text{}` of the formulas is in Noto Sans too.
* The pages of an imported PDF are imported once per file (one call: their fonts and images are
  shared, not copied for every page), as pages of the document: their links stay, placed with
  them.
* The underlines and the strikeouts have the thickness and the place of the editor; the images
  their full resolution (a JPEG turned by its Exif embedded as it is, turned by its placement);
  the document its title and the application that made it.

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The fonts of the notes, for the editor and the PDF | `NotesFonts`, `NotesFontFiles` (`drawview-ui`) | Information Expert |
| The words of a text as it is laid out: face, size, color, place, width; its formulas | `TextTileAdapter.layout` → `TextBlockLayout` (`drawview-core`), `ComposeTextTileAdapter` | Information Expert |
| Read a TrueType font: glyphs of the characters, outlines, subset | `TrueTypeFont` (`drawview-pdf`) | Pure Fabrication |
| A formula as data, the same on every platform | `LatexEngine.drawing` → `TexDrawing` (`drawview-latex`) | Information Expert, Protected Variations |
| A formula as vectors | `TexPaths` (`drawview-export`) | Pure Fabrication |
| Fonts and text of the PDF, pages of another PDF as pages | `PdfWriter.loadFont`, `addText`, `importPdfPages`, `beginPage(base)` | Pure Fabrication, Creator |
| Where a mark of the text of a PDF is drawn, in the editor and in the PDF | `TextMarkupPainter.markRect` (`drawview-core`) | Information Expert |
| How a JPEG is turned to be shown, by the decoding and in the PDF | `jpegOrientation`, `exifOrientation` (`drawview-core`) | Information Expert |
| Title and application of a PDF | `PdfInfo` (`drawview-pdf`), `appName` of `DocumentExportUi` | Pure Fabrication, Protected Variations |

| Phase | Content | Status |
|---|---|---|
| Q1 | Noto Sans and Noto Sans Mono for the text of the notes (editor, editing, notes of the lectures) | Done |
| Q2 | `TrueTypeFont` (cmap, outlines, subset); `PdfWriter.loadFont` / `addText` | Done |
| Q3 | The words of the texts as real text of the PDF (`TextBlockLayout`); images for the characters out of the font | Done |
| Q4 | The formulas as vectors (`TexDrawing`); `\text{}` in Noto Sans | Done |
| Q5 | The pages of the imported PDFs imported once per file, with their links | Done |
| Q6 | Underlines and strikeouts as in the editor; images at full resolution; title and creator of the PDF | Done |

### Results of the quality

* The editor, the editing overlay and the notes of the lectures write in Noto Sans (Noto Sans
  Mono for the code) on every platform (`rememberNotesFonts`); the files are read again by the
  export (`NotesFontFiles`).
* `TrueTypeFont` reads the glyphs of the characters (cmap 4 and 12), their advances, their
  outlines (simple and composite glyphs) and the strikeout of the font, and writes a subset
  keeping the glyph numbers. Each face used is embedded once, as a subset of its characters.
* `ComposeTextTileAdapter.layout` gives the words of a text as the editor lays them out (face,
  size, color, box, strikethrough) and the boxes of its formulas; each word is written at its
  place and stretched to its width, so that the PDF matches the editor whatever the reader's
  text layout. A word with a character out of the font (emoji, Chinese, Japanese, Korean) is the
  image of its box in the block drawn at 300 dpi.
* MicroTeX gives a formula as operations (`TexDrawing`), the same on every platform; `TexPaths`
  replays them as the graphics of the platforms do (scales and translations on the coordinates,
  rotations on the canvas, synthesized bold and italic) with the outlines of the glyphs of the
  TeX fonts. The `\text{}` of the formulas is in Noto Sans (`LatexEngine.prepare`).
* Tests: the text of a PDF read again in every script of the font (Greek), the PDF of a text
  with formulas drawn by PDFium overlapping the editor's drawing (more than 75 % of the ink), no
  image in it; an emoji word as an image.
* The pages of the PDFs covering the pages they are shown on (as the import of a PDF makes
  them) are imported in one call per file (`PdfWriter.importPdfPages`, `FPDF_ImportPagesByIndex`):
  the fonts and the images they share are written once (five pages sharing a picture of 200 KB:
  208 KB, 1 MB when each page is copied). A page begun on one (`beginPage(base)`) is it, moved to
  its place, its content enclosed between `q` and `Q` and left as it is, as it is displayed
  (rotation, crop box): what is drawn over it is mapped from its displayed corner to its user
  space. Its annotations are flattened into it, the others removed (they would bring the pages
  they point to), its links to the web added again as links. The pages showing a PDF page
  otherwise (a page shown twice, a page of another size) copy it as a form object, drawn opaque on
  white like the editor draws it, over the background.
* The underlines and the strikeouts of the PDFs are annotations drawn by their own appearance,
  the rectangles of the editor (`TextMarkupPainter.markRect`); the highlights keep the one PDFium
  generates (the line multiplied by its color, as the editor). PDFKit draws an appearance without
  resources in black: an opaque mark is written opaque to 254/255, so that PDFium gives its
  appearance the graphics state of its opacity.
* A JPEG is embedded as it is, also when its Exif turns it: the turn is part of its place
  (`exifOrientation`). The editor shows it turned on every platform (Skia turns it, Android turns
  it after `BitmapFactory`, which does not). The other images are pixels, up to 4096 a side.
* The title of the document and the application that made it (`PdfInfo`, `appName` of
  `rememberDocumentExportUi`) are written in the information dictionary, added by the bridge as
  an update of the file PDFium saved (PDFium cannot write it).
* Limits: the links of the PDFs to their own pages are not kept (PDFium cannot write their
  destinations).

## Export of a folder and Home

> Replaced by `docs/plans/export-ux.md`: `ExportScope`, `ExportFormat` and `DocumentExport.export` took
> the place of `exportFolder`, `FolderExportFormat`, `FolderExportOptions` and
> `FolderExportResult`; the export sheet took the place of `FolderExportDialog`. What follows is
> kept as the record of the first version.

Users can export all notes belonging to a specific folder or to the Home (the root of the
library). The export produces either a ZIP archive containing individual PDF documents for each
note (preserving directory hierarchy) or a single combined PDF document concatenating all notes.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

### Starting point

* `DocumentExport` (`drawview-export`) exports a single document given its `documentId` via
  `DocumentExporter` (`PdfExporter`, `PageImageExporter`).
* `DocumentExportUi` (`drawview-export-ui`) exposes `documentAction` (`DocumentAction` for a single
  selected document in the library) and `editorExtension` (top bar action in the editor).
* `DocumentAction` (`drawview-ui`) models actions on a single document:
  `content: @Composable (documentId: String, title: String, onDone: () -> Unit) -> Unit`.
* `DocumentLibrary` (`drawview-explorer`) displays folders and notes. A folder row or card has
  rename, move, and delete actions, but no application action extension point. When viewing a folder
  or Home, there is no action to export all contained documents.

### Requirements

1. **Trigger points in the UI**:
   * **In the library header**: when viewing a folder or the Home (`viewModel.section == Section.HOME`),
     an action button or menu item ("Export folder…" or "Export all documents…").
   * **On folder items**: option "Export folder" in the three-dots action sheet and right-click
     context menu of folder cards and list rows.
   * **In selection mode**: available when a single folder is selected.
   * **Commands**: registered in `LibraryCommands` so that shortcuts and the window menu bar (desktop)
     trigger the export of the current folder or Home.
2. **Export formats**:
   * **ZIP archive (individual PDFs)** (default): a `.zip` archive containing each document as a
     standalone `.pdf`. If subfolders are included, folder hierarchy is preserved inside the archive
     (`Folder/Subfolder/Note.pdf`).
   * **Single combined PDF**: all pages of all selected documents merged sequentially into one PDF
     document.
3. **Export options**:
   * Switch *Include subfolders* (default: true). Dynamically updates the count of notes to be exported.
   * Switch *Background pattern* (default: true). Controls whether paper lines/grids/dots are rendered.
   * Note count summary: displays how many notes will be exported (if 0 notes, disables export).
4. **Platform integration & performance**:
   * File delivery via `rememberFileSaver` with `application/zip` or `application/pdf`, working
     identically on Desktop JVM, Android, and iOS.
   * Sequential document processing (one open session snapshot at a time) to maintain flat memory
     usage regardless of the number of notes in the folder.
   * Linear progress tracking and cancellation handling.

### Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Action of the application on a folder or root/home | `FolderAction` (`drawview-ui`) | Low Coupling, Protected Variations, Polymorphism |
| Controller for exporting multiple documents (folder/home) | `DocumentExport.exportFolder` (`drawview-export`) | Controller, High Cohesion |
| Minimal zero-dependency ZIP archive writer (STORE method, CRC-32) | `ZipWriter` (`drawview-export`) | Pure Fabrication, Low Coupling |
| Formatting options and result of folder export | `FolderExportFormat`, `FolderExportOptions`, `FolderExportResult` (`drawview-export`) | Information Expert |
| UI dialog for folder export: format, options, note count, file saver, progress | `FolderExportDialog` (`drawview-export-ui`) | Information Expert, Indirection |
| Exposing folder export to the library | `DocumentExportUi.folderAction` (`drawview-export-ui`) | Low Coupling, Protected Variations |
| Support folder actions in library, item menus, header, and commands | `DocumentLibrary(folderActions = ...)`, `LibraryCommands` (`drawview-explorer`) | Low Coupling, Protected Variations |

### Plan

| Phase | Content | Status |
|---|---|---|
| F1 | `drawview-export`: `ZipWriter` (pure Kotlin, STORE method, CRC32), `FolderExportFormat`, `FolderExportOptions`, `DocumentExport.exportFolder` (folder traversal, sequential snapshotting, ZIP and combined PDF writing); unit tests | Done |
| F2 | `drawview-ui` & `drawview-explorer`: `FolderAction` in `drawview-ui`, `folderActions` parameter in `DocumentLibrary` and `ExplorerScreen`, `LibraryCommands.forItem` for folders, header action in `LibraryTitle`, and tests | Done |
| F3 | `drawview-export-ui` & `pencilnotes`: localized resources, `FolderExportDialog`, `DocumentExportUi.folderAction`, wiring into `PencilNotesApp`, tests | Done |
| F4 | ABI dumps (`updateKotlinAbi`), build verification (`jvmTest`, `checkKotlinAbi`), verification on desktop JVM scratch data directory | Done |

### Decisions

* **ZIP STORE method**: PDFs are already internally compressed. Storing them without recompression
  is fast, requires zero external compression libraries or native C dependencies, and produces
  fully standard ZIP files compatible with all platforms.
* **Sequential document opening**: Documents are opened, snapshotted, exported, and closed one by
  one via `OpenDocuments` to keep memory consumption low.
* **Relative paths in ZIP**: Notes in subfolders are placed under `SubfolderName/NoteName.pdf` inside
  the ZIP archive to prevent naming collisions.
