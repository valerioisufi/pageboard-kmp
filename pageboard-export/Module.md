# Module pageboard-export

The export of the documents: `DocumentExport` (the use case) writes a document with a
`DocumentExporter`, `PdfExporter` (a PDF: the pages of the imported PDFs copied with their
vectors and text, the marks of their text as annotations, the strokes as vector outlines, the
texts searchable) or `PageImageExporter` (the image of a page).

On iOS the application embeds the PDFium framework that `pageboard-pdf` links (see its page).
