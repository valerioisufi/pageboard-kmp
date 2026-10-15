# Module drawview-model

The document of DrawView, headless (no Compose): a `DrawDocument` of `DrawPage`s holding
strokes, texts, images, PDF pages, marks of their text and the elements of the types of the
applications (`ElementType`, `DrawCustomElement`).

Every change is a serializable `DocumentChange` submitted to the `DocumentSession` of the
document: applied with the stamp of its operation (last writer wins, `HybridClock`), undone,
stored through the `DocumentPersistence` port and sent to the other devices as an `Operation`.
The strokes are modeled from their inputs by a `StrokeModeller` (`KotlinStrokeModeller` here,
Google Ink in `drawview-ink`). The geometry is in page millimeters (`Rect`, `Point`, `Matrix2D`,
`AffineTransform`).
