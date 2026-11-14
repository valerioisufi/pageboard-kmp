# The vectors of the formulas through the formula port

Phase 10 of `docs/architecture.md` (design D, Indirection): the export writes the formulas as
vectors through `FormulaRenderer`, the port of `drawview-canvas`, instead of `LatexEngine`, and
`drawview-export` no longer depends on `drawview-latex`. Nothing stored or exported changes: the
PDFs are written as before.

The responsibilities are assigned following the GRASP principles.

## Starting point

* `LatexEngine.drawing` gives a formula as `TexDrawing` (its `TexOp` operations, its `TexFont`
  fonts), types of `drawview-latex`.
* `PdfExporter` (`drawview-export`) writes them as paths (`TexPaths`), taking them from the formula
  renderer it is given when that renderer is a `LatexEngine` (a cast, left by
  `docs/engine-instances.md` for this phase). `drawview-export` depends on `drawview-latex` for it.

## Requirements

1. The export takes the vectors of a formula from the port the editors draw them with.
2. `drawview-export` builds without `drawview-latex`; the PDFs are the same (`PdfExporterTest`).
3. No JNI or native change.

## Design

* **Indirection / Protected Variations.** The types of a drawn formula move to `drawview-canvas`
  beside the port, named after it: `FormulaDrawing`, `FormulaFont`, `FormulaOp`
  (`canvas.render.tile`). `FormulaRenderer` gets `drawing(latex, fontSizePx, colorArgb):
  FormulaDrawing?`, `null` by default (an engine drawing images only).
* **Polymorphism.** `LatexEngine` overrides `drawing`; the readers of MicroTeX
  (`ActionRecorder`, `TeXRender`, the platform engines) build the types of the canvas. The native
  code never names them (checked), so nothing native changes.
* **Information Expert.** `PdfExporter` asks its `formulas` for the drawing; `TexPaths` becomes
  `FormulaPaths`. The fonts of a formula are read as before: a file of the resources of the engine,
  or the text fonts of the notes.
* `TexTextFonts` stays internal to `drawview-latex`, in a file of its own.

## Phases

| Step | Content | Status |
|------|---------|--------|
| 1 | The types in `drawview-canvas`, `FormulaRenderer.drawing`, the export on the port, `drawview-export` without `drawview-latex` | done |

## Decisions

Taken alone, revertible:

* **`drawview-export` keeps `drawview-pdf`.** Writing PDFs with PDFium is what the module does; a
  port of the writer would have one implementation. `docs/architecture.md` said "no more than a
  port" for it: changed there.
* **The names follow the port** (`Formula*`), not the engine (`Tex*`).
