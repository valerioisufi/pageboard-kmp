# Module drawview-pdf

PDF documents read, drawn and written by PDFium (desktop, Android with `drawview-native-pdfium`,
iOS with the PDFium framework embedded by the application): `PdfiumEngine` opens them, gives the
editor its PDF pages (`PdfiumEngine::createTileAdapter` in `DrawViewEngines`) and the text of
their characters; `PdfWriter` writes PDFs (the export).
