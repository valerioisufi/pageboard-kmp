# The engines as instances

Phase 4 of `docs/architecture.md` (design B, requirement 3): the last global objects of the
libraries' APIs, after the store and the sessions (`docs/instances.md`), become instances the
applications create and pass. Nothing stored and nothing that travels changes.

The responsibilities are assigned following the GRASP principles.

## Starting point

1. **`LatexEngine` is a public `object`** (`drawview-latex`): its readiness (`isReady`), its
   initialization, the fonts of the text of the formulas (`setTextFonts`, kept in the internal
   `TexTextFonts`) and `formulaRenderer`, the renderer given to `DrawViewEngines`. Used by both
   applications (four `DrawViewEngines`), by `PdfExporter` (`LatexEngine.drawing`, the formulas as
   vectors) and by the tests of `drawview-latex`, `drawview-export`, `drawview-explorer`.
   Under it, MicroTeX is one native engine per process (`LatexPlatformEngine`, JNI and cinterop):
   the native library is loaded and initialized once.
2. **`ImageCache.Shared`** (`drawview-canvas`) is the default of `TileRenderer.images`: every
   renderer of the process (the editors, the thumbnails, the export of images, the pages read by the
   agents) shares it without being given it. `MediaManager` puts an imported image in it.
3. **`LectureRecordings` is a public `object`** (`drawview-lecture`): each `LectureAssistant`
   registers itself in it when created, and is never removed. The desktop of PencilNotes asks it
   whether a lecture is being recorded before closing (`isRecording`) and saves the recordings
   before exiting (`finishAll`), because the assistant is created inside the composition of the
   main window (`rememberLectureAssistant`, a `ViewModel`), out of the reach of `main`.
4. `DrawViewEngines.None` is a shared instance: with 2 it would carry a shared cache.

## Requirements

1. No public global object in `drawview-latex`, `drawview-canvas` (the image cache) and
   `drawview-lecture`: the applications create the engine, the cache and the lecture assistant and
   pass them.
2. The windows of an application share them, as they do today (one cache of images, one
   assistant recording one lecture).
3. The same behaviour: the formulas drawn and exported as today (screenshots and the PDF tests
   unchanged), an imported image drawn at once, the desktop asks before closing during a recording
   and saves it before exiting.
4. The checks of `CLAUDE.md` green; the changes of the ABI recorded on purpose.

## Design

### The formula engine

* **Polymorphism / Indirection.** `LatexEngine` becomes a class that *is* a `FormulaRenderer`
  (`public class LatexEngine : FormulaRenderer`): the applications write
  `DrawViewEngines(formulas = latex)` instead of `LatexEngine.formulaRenderer`, which goes. Its
  public members: `isReady`, `prepare(textFonts)` (sets the fonts, then initializes), `initialize()`,
  `sanitize`, `render`, `drawing`. `renderFormula` (two overloads), `init(resourcesDirectory)` and
  `setTextFonts` stop being public: nothing outside the module calls them except the tests, which
  use `prepare`.
* **Pure Fabrication.** The native engine stays one per process, behind the internal
  `LatexPlatformEngine` and `TexTextFonts`: that is a fact of MicroTeX, not of the API. The KDoc of
  `LatexEngine` says so: its instances share the native engine, the fonts of the text are those of
  the last `prepare`. No JNI name changes (the native methods stay on `LatexPlatformEngine`).
* **Creator.** Each application creates one, next to its store: `PencilNotesData.latex`,
  `SampleData.latex`; its windows give it to their `DrawViewEngines`.
* **Protected Variations (temporary).** `PdfExporter` takes the formulas from the renderer it is
  given (`formulas: FormulaRenderer?`, the `engines.formulas` of the export sheet) and draws them as
  vectors when that renderer is a `LatexEngine`, else as the raster it draws today for a formula it
  cannot write. Phase 10 of `docs/architecture.md` replaces the cast with a port of
  `FormulaRenderer` (`drawing`) and frees `drawview-export` from `drawview-latex`.

### The cache of the images

* **Information Expert / Creator.** The pixels of the images are a cache of the renderers of an
  application, so `DrawViewEngines` holds it: `images: ImageCache = ImageCache()`. Every renderer
  built from engines is given it: the editor (`TileManager(tileRenderer = TileRenderer(images =
  engines.images))` in `DrawViewController`), `DocumentThumbnails`, `PageImageExporter`,
  `CanvasPageEngine` (a new `images` parameter, given `engines.images` by their callers).
* `TileRenderer.images` keeps a default, `ImageCache()` (its own), for a renderer built alone (the
  tests, the benchmarks). `ImageCache.Shared` goes.
* `DrawViewEngines.None` becomes a getter that builds a new instance each time (the same ABI, a
  property), so that no cache is shared by the process through it.
* **Requirement 2.** PencilNotes and the sample build their engines in each window (`remember`):
  they pass the cache of the application, `PencilNotesData.images` and `SampleData.images`, so the
  windows share one cache (256 MiB at most) as today.

### The lecture assistant of the application

* **Information Expert.** The assistant knows whether it is recording (`isBusy`, public already)
  and how to save its recording (`finishRecording`, internal today, made public). The registry
  `LectureRecordings` has nothing of its own: it goes.
* **Creator.** PencilNotes creates its assistant once per process, as it does with the store:
  `PencilNotesData.lectureAssistant by lazy { createLectureAssistant() }`. `main` of the desktop
  asks it directly (`isBusy`, `finishRecording()`), and phase 5 will give the same assistant to the
  secondary windows.
* `drawview-lecture-ui`: `rememberLectureAssistant()` (the `ViewModel` holder) is replaced by
  * `createLectureAssistant(): LectureAssistant`, an assistant whose notifications of arrivals are
    in the language of the application (what the holder set);
  * `@Composable LectureMicrophone(assistant)`, which binds the request of the microphone to the
    window shown (what `rememberLectureAssistant` did at each composition).
* The assistant lives as long as the process instead of as long as the `ViewModel` store of the
  first window: on Android the same (the activity created again kept it already), on the desktop it
  no longer depends on the library window.

## Phases

| Step | Content | Status |
|------|---------|--------|
| 1 | `LatexEngine` a class and a `FormulaRenderer`; the applications create one; `PdfExporter` takes the renderer; tests and docs | done |
| 2 | `ImageCache` in `DrawViewEngines`, given to every renderer; `ImageCache.Shared` and the shared `None` go | done |
| 3 | The lecture assistant created by PencilNotes, `LectureRecordings` and `rememberLectureAssistant` go | done |

One commit per step on `feat/engine-instances`, each with its ABI dumps updated on purpose and the
checks of `CLAUDE.md`. Step 3 is checked by running the desktop of PencilNotes on a scratch data
directory (the window closes, the assistant is there); Android and iOS compiled.

## Decisions

Taken alone, revertible:

* **`LatexEngine` is the renderer** rather than holding one: one object fewer, and the cast of
  `PdfExporter` until phase 10 is on a type the export already knows.
* **The native engine stays process-wide**, documented, not hidden behind a check that a second
  instance would fail: two instances work, with the fonts of the last `prepare`.
* **The image cache in `DrawViewEngines`**, not a `CompositionLocal`: the engines already reach
  every renderer.
* **`None` builds a new instance at each read**: nothing compares it by identity (checked).
* **The assistant per process** in `PencilNotesData`, not a `ViewModel`.
* `DrawViewAndroid` stays as `docs/architecture.md` decided (the one `Context` of the platform
  module, set by the application).

## Left for later

* The formulas as vectors through `FormulaRenderer`, `drawview-export` without `drawview-latex` and
  `drawview-pdf` (phase 10).
* `PdfiumEngine` is an `object` too, around a native library loaded once; it holds no state of the
  application (it creates the adapters), so it stays one.
* The assistant, the collaboration and the search in the secondary window of PencilNotes (phase 5).
