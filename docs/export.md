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
| Draw the texts and the formulas; the lines of a text as it lays them out (the invisible text of the PDF, over the visible one) | `TextTileAdapter.drawText`, `TextTileAdapter.lines` (interface of `drawview-core`; `ComposeTextTileAdapter` of the editor) | Information Expert, Indirection, Protected Variations |
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
| Highlight, underline, strikeout (`DrawTextMarkup`) | an annotation of the same kind (QuadPoints, color), recognized by the other readers |
| Image (`DrawImage`) | an image object; a JPEG is embedded as it is, the others as pixels |
| Text, Markdown, LaTeX (`DrawText`) | an image of the block at 300 dpi (as the editor draws it), with its text written invisible over it (searchable, copied) |
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
  turned or not).
* Tests (`drawview-pdf`, `drawview-export`, JVM): the PDFs written are read again with PDFium
  (pages, sizes, text of the copied pages and of the texts, colors of the strokes, images,
  backgrounds and highlights at their place, pages copied with a rotation or a crop box compared
  pixel by pixel with the original); the order of the elements and the files missing with a
  recording writer; the export of a document through `OpenDocuments`. A PDF exported is also
  drawn as expected by PDFKit (macOS), with its highlight listed as an annotation.
* Limits: the invisible text of the texts keeps the Latin-1 characters only (Helvetica of the
  standard fonts); the formulas are images without text.

