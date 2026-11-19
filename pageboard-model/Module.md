# Module pageboard-model

The document of Pageboard, headless (no Compose): a `Document` of `Page`s holding
strokes, texts, images, PDF pages, marks of their text and the elements of the types of the
applications (`ElementType`, `CustomElement`).

Every change is a serializable `DocumentChange` submitted to the `DocumentSession` of the
document: applied with the stamp of its operation (last writer wins, `HybridClock`), undone,
stored through the `DocumentPersistence` port and sent to the other devices as an `Operation`.
The strokes are modeled from their inputs by a `StrokeModeller` (`KotlinStrokeModeller` here,
Google Ink in `pageboard-ink`). The geometry is in page millimeters (`Rect`, `Point`, `Matrix2D`,
`AffineTransform`).

The texts are Markdown with formulas and blocks of code, read by `RichTextParser`; the tokens of
the code, for its colors, come from a `CodeHighlighter` (`BuiltInCodeHighlighter` here, for the
common languages).
