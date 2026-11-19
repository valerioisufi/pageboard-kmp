# Module pageboard-canvas

The editor of a document and its rendering, in Compose: `DrawViewController` (the tools, the
history, the selection, the texts, the pages, the managers), its `Viewport` (the camera, the layout
of the pages, the conversions between screen and page millimeters) and its `CollaboratorPresence`;
`DrawCanvas`, drawn by tiles rasterized in the background.

The optional engines are ports given in `DrawViewEngines`: the PDF pages (`PdfTileAdapter`, see
`pageboard-pdf`), the formulas (`FormulaRenderer`, see `pageboard-latex`) and the element types of
the application (`ElementRenderers` of `ElementRenderer`s); without one, a PDF page is blank and a
formula shows its source. The colors of the code of the texts come from a `CodeHighlighter`
(`BuiltInCodeHighlighter` of `pageboard-model` unless the application gives another one).
