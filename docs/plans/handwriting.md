# Handwriting recognition

The handwriting of a note read as text on the device, in milliseconds, from the strokes as they
were written (online recognition): found by the search, read by the lecture assistant and by the
external models, and converted into a text box when the user asks. The text is read by the engine
of each platform; the formulas wait for a reader that may ship, chosen by measuring (phase 0) and
planned on its own. This document starts from the code as it is (main at e4b00af4, 2026-11-20).

It replaces a first version, written on 2026-10-29 from main at 89a72c69 and never committed. That
version recorded phases 1 to 3 and 5 to 7 as done, but their code (`drawview-handwriting`,
`InkSample`, `TextInkRecognizer`, `vision_bridge_mac.m`) is in no branch of this repository, here or
on GitHub, nor in the backup of the history taken before its rewrite on 2026-11-19, nor anywhere
else (the maintainer, 2026-11-20): the work starts again from main. What it designed is kept where
it still holds; what the code and the platforms changed since is said below.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **A stroke keeps its samples.** `StrokeMesh.points` holds the `StrokePoint`s of a stroke (`x`,
   `y`, `pressure`, `timestampMs`); in `StrokeElement.mesh` they are in page millimeters, the
   transform of the stroke applied (`StrokeMesh.transformed`). Room stores them before the transform
   (`StrokeInputCodec`, the column `inputs`), and they travel with the stroke (`ElementData`). A
   stroke may have none (`points` defaults to empty).
2. **The handwriting is read as an image by a language model, in the notebooks with a lecture
   only.**
   * `InkBlock`, `groupStrokes` (strokes closer than 12 mm), `blockKey` and `unionOf` are in
     `pageboard-lecture` (`lecture/handwriting/InkBlocks.kt`); `inkBlocksOf` and `isHandwriting`
     (neither highlighter, eraser nor lasso) are private to `ControllerNotebookSource`
     (`pageboard-lecture-ui`).
   * `HandwritingReader` (`pageboard-lecture`, internal) renders a block (`NotebookSource.renderInk`)
     and sends it, `ModelLimits.handwritingBatch` at a time, to the model of the live notes: Gemini,
     Ollama, or Gemma 4 on the device (`DeviceBackend`: desktop and Android, not iOS, whose phase 7
     of [local-models.md](local-models.md) is not started). The texts are kept by block key in
     `lectures/doc-<id>/handwriting.json` (at most 5,000), shared with the devices of the notebook.
   * What it read is used by `LectureSearchSource` (`SearchChunkKind.HANDWRITING`), by the external
     models (`AgentEngines.handwriting`, given by PencilNotes from
     `LectureAssistant.handwritingByPage`) and by the writing suggestions (`NoteVocabulary` counts
     the words a document has besides its typed texts).
3. **What was measured** ([local-models.md](local-models.md); `FormulaBench`, which reads
   MathWriting as InkML; 60 formulas of its test split): Gemma 4 E4B reads 15–16 % of the formulas
   exactly, in 4–6 s a request; the 12B 26–33 %, in 16–25 s. The models write plausible text that is
   not on the page. Italian handwriting was not measured: the notebooks measured held almost none
   (six strokes).
4. **The search reads no handwriting of its own** (phase 4 of [search.md](search.md), left for
   later "to be replaced by another implementation"), and it reads the documents without their
   strokes: `DocumentStore.loadOutline` gives the texts, the PDF pages and the custom elements
   (a9b956b4: a pass after a stroke on a note of 30 pages went from 0.82 s to 57 ms).
   `DocumentStore.pageFingerprint` tells when a page changed without modeling it; `loadDocument` and
   `loadPage` model the strokes they load (`StrokeModeller`). A `SearchSource` gives the chunks of a
   whole document (`version`, `chunks`). The index is at `FORMAT_VERSION` 1, a new format builds it
   again whole, vectors included; its tables are created at every open (`createSchema`).
5. **Formulas are text.** A text box holds rich text with formulas, `$…$` in the line and `$$…$$`
   in display style (`RichTextParser`), drawn by MicroTeX (`pageboard-latex`).
6. **The extensions reach the editor through its ports** (`EditorContext`: `document`, `pages`,
   `edits`, `placement`, `rendering`, `presence`; the controller is internal since 21468009).
   `EditorDocument.group` makes one change to undo of several. No port gives the selected elements
   (`EditorEdits` only clears the selection), and the context menu of a selection shows cut, copy,
   delete and the arrangement, nothing of the extensions (`SelectionMenuItems`). `EditorExtension`
   is an abstract class: a new contribution is an open member with a default (b5f5c2c0).
7. **Shapes are recognized already** (`ShapeRecognizer`, [shapes.md](shapes.md)), and the size of
   the handwriting of a page is known (`PageTextSizes.handwriting`).
8. **Runtimes**: `pageboard-ml` (the catalog, the downloads verified by SHA-256) and
   `pageboard-ml-litert` (LiteRT-LM, language and embedding models, on the JVM and Android). No
   runtime for a small model of our own.
9. **The platforms, November 2026:**
   * **Apple**: PencilKit reads handwriting from strokes on iOS, iPadOS, macOS and visionOS 27
     (`PKStrokeRecognizer`, WWDC26 session 203). On the device, with the model of the system, in 29
     languages (`supportedLanguages`, `preferredLanguages`). `updateDrawing` takes a `PKDrawing`, or
     a subset of its strokes, from any canvas; `recognizedText()` gives the most likely text,
     `indexableContent` one string with every candidate, for a search; `search(_:)` the places of a
     word (`bounds`); `recognizerVersion` changes when the results may improve. It is a Swift actor,
     with no Objective-C: Kotlin/Native cannot reach it through cinterop alone. Not said: Italian,
     formulas, readings with scores. The iOS applications target iOS 18.2.
   * **Android**: ML Kit Digital Ink Recognition 19.0.0 (August 2025, unchanged by the releases of
     2026): candidates with scores, the text before (`preContext`), the size of the writing area
     (`WritingArea`), a model of about 20 MB a language downloaded by `RemoteModelManager`. Whether
     it downloads on a device without Google Play services is not known. On iOS it ships as a
     CocoaPod only, with problems reported on the arm64 simulator.
   * **Windows**: `Windows.UI.Input.Inking.InkRecognizerContainer` reads strokes with the
     handwriting recognizers installed with the languages of Windows (none: the container is
     `null`), with candidates for each word (`GetTextCandidates`). Its use from a desktop process is
     to be checked.
   * **No platform reads formulas.** The public datasets of handwritten formulas are
     non-commercial: MathWriting (Google, CC BY-NC-SA 4.0), CROHME, HME100K; so is much of what is
     trained on them.

## Requirements

1. **Read text from the strokes**, Italian and English at least, on the device: on Android; on
   iOS, iPadOS and macOS 27; on Windows if phase 0 says so. A line read while the user waits (the
   target: 100 ms on a mid-range tablet of a few years ago). The most likely reading, the others when
   the engine gives them, and a text for the search with every candidate.
2. **Formulas** are read by a reader chosen in phase 0 and planned on its own (the target stays:
   half or more of the 60 formulas of `FormulaBench` read exactly, 200 ms a formula). Until then the
   port exists, "Convert to formula" is not offered, and the lecture reads the formulas with its
   model as today.
3. **A drawing is not read as words**: the parts of a block that are not writing (diagrams, arrows,
   large strokes) are left out, measured on the set of phase 0, so that misreadings fill neither the
   index nor the suggestions.
4. **The search finds the handwriting of every notebook**, with or without a lecture:
   * a block read once, again only when it changes or its reader changes;
   * the other candidates found by the words, so that a word read wrong is still found;
   * a library of a thousand pages read in minutes, in the background, under the
     `IndexingConditions` of the indexer;
   * the other sources keep reading the outline, without the strokes.
5. **The lecture assistant and the external models read the same readings**: no image sent to a
   language model for a block that a reader of the device reads.
6. **Convert a selection** of strokes into a text box: a preview with the reading editable and the
   other readings, then the strokes replaced by the text box as one change to undo, at their place,
   in their color, at the size of the handwriting.
7. **It degrades.** Without a reader for a language, on a system before 27, without samples in a
   stroke: the feature is missing or falls back to what is there (the model of the lecture), never
   the build or the application.
8. **Nothing of the user is lost or changed**: the readings are derived data, kept outside the
   documents and rebuilt when lost. No change to the Room schema, to the stored documents, to the
   lecture records (`handwriting.json` keeps its format) or to what travels (`PROTOCOL_VERSION`
   stays 1).
9. **Downloads on request**: the language models of ML Kit are downloaded when the user asks, on
   Wi-Fi by default, and deleted only by the user; PencilKit needs none; the recognizers of Windows
   are the user's to install in Windows.
10. **Everything shipped may be published on the stores**, a paid application included: every SDK,
    runtime, model and the data a model of ours is trained on allow commercial use. A dataset with a
    non-commercial license measures, never trains what ships. The licenses go in
    `THIRD_PARTY_LICENSES.md`.

## Design

### The readers of each platform

| Platform | Reader | Given | Gives |
|---|---|---|---|
| Android | ML Kit Digital Ink | a line of samples, the line before, the size of the area | candidates with scores |
| iOS, iPadOS, macOS 27 | PencilKit `PKStrokeRecognizer` | a `PKDrawing` made of the samples (`PKStrokePoint`: location, time offset, force) | the most likely text, a text for the search, the places of a word |
| Windows (phase 7) | `InkRecognizerContainer` | `InkStroke`s made of the samples | candidates for each word |
| Linux, Apple systems before 27 | none | | |

The reader of images of the first version (Vision) is dropped: on the same systems PencilKit reads
the samples, and before 27 the model of the lecture is there.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| A block of strokes, its key, the strokes of a page grouped, which strokes are writing | `InkBlock`, `groupStrokes`, `blockKey`, `isHandwriting` moved to `pageboard-model` (`model.ink`) | Information Expert |
| The samples of the strokes of a document, read without modeling them | `DocumentStore.loadInk` and `DocumentInk` (`pageboard-store`; Room reads the inputs and the transforms, no change of schema), `checkInk` (`pageboard-store-testing`) | Information Expert, Protected Variations |
| The strokes of a block as samples: page millimeters, the transform applied, in the order written, the strokes without samples counted | `InkSample` (`pageboard-handwriting`), from `StrokeElement` or `DocumentInk` | Information Expert |
| Reading text from samples | `TextInkRecognizer`, `InkReading` (`pageboard-handwriting`) | Polymorphism, Protected Variations |
| Reading a formula from samples as LaTeX | `MathInkRecognizer` (`pageboard-handwriting`), implemented by the plan of the formulas | Polymorphism |
| The language models to download, their size, their deletion | `InkLanguageModels` (`pageboard-handwriting`) | Protected Variations |
| The lines of a block, and the parts left out as drawing | `InkLines` (`pageboard-handwriting`), by rules | Pure Fabrication, High Cohesion |
| Reading a block: its lines (or the whole block, for a reader that splits it itself) to the reader of the language, each with the line before | `HandwritingReading` (`pageboard-handwriting`) | Controller, Indirection |
| The readers present, by language | `HandwritingEngines` (`pageboard-handwriting`), given by the application | Creator, Low Coupling |
| ML Kit | `MlKitTextRecognizer`, `MlKitLanguageModels` (`pageboard-handwriting-mlkit`, Android) | Polymorphism |
| PencilKit | `PencilKitTextRecognizer` (`pageboard-handwriting-pencilkit`: iosMain through cinterop, jvmMain on macOS through JNI), on one Swift bridge (`native/bridge/pencilkit_bridge.swift`: an `@objc` class for cinterop, `@_cdecl` functions for JNI) | Polymorphism, Indirection |
| Windows ink (phase 7) | `WindowsInkRecognizer` (`pageboard-handwriting-windows`, jvmMain): C++/WinRT through JNI, in the jar | Polymorphism |
| The readings by block key, with their reader, its version and the language | the table `handwriting` of the search index (`pageboard-search`) | Information Expert, Pure Fabrication |
| The handwriting of every notebook in the search | `HandwritingSearchSource` (`pageboard-search`): its version from the fingerprints of the pages and the versions of the readers, a block read again only when it changed | Polymorphism (a `SearchSource`) |
| The notes read by the lecture assistant | `HandwritingReader` reading through `HandwritingReading` when given (`NotebookSource.inkSample`), the model otherwise; `handwriting.json` unchanged | Protected Variations |
| The handwriting given to the external models | `AgentEngines.handwriting`, given by PencilNotes from the index for every notebook | Indirection |
| The selected elements, and the actions of an extension on them | `EditorEdits.selection` (the ids by page, `pageboard-canvas`), `EditorExtension.selectionActions` (in the context menu of a selection, `pageboard-ui`) | Indirection, Protected Variations |
| Convert to text: the command, the preview, the replacement | `HandwritingConversion`, `HandwritingEditorUi` (`pageboard-handwriting-ui`, an `EditorExtension`) | Controller |
| The reading on or off, the languages downloaded | the settings of `pageboard-search-ui` | Controller |

### The API

```kotlin
/** The strokes of a block as they were written: in page millimeters, in the order of their first sample. */
public class InkSample(
    public val strokes: List<List<StrokePoint>>,
    /** The strokes of the block that had no samples, left out. */
    public val missing: Int = 0
)

/**
 * A reading of handwriting.
 *
 * @param text the most likely text.
 * @param alternatives the other readings, the likelier first; empty when the reader gives none.
 * @param searchText every candidate of the reader, for the search.
 */
public class InkReading(
    public val text: String,
    public val alternatives: List<String> = emptyList(),
    public val searchText: String = text
)

/** Reads handwritten text from samples (ML Kit, PencilKit, Windows). */
public interface TextInkRecognizer {
    /** The reader and its version: what it read is read again when it changes. */
    public val version: String

    /** Whether it splits a block into lines itself (PencilKit); otherwise it is given a line at a time. */
    public val readsBlocks: Boolean get() = false

    /** The languages it reads now, as BCP 47 tags (with their models on the device). */
    public suspend fun languages(): Set<String>

    /**
     * The reading of [ink] in [language], `null` when it cannot read it (no model, an error of the system).
     *
     * @param context the text written before [ink] (the line before), which helps some readers.
     */
    public suspend fun recognize(ink: InkSample, language: String, context: String = ""): InkReading?
}

/** Reads a handwritten formula from samples as LaTeX. */
public interface MathInkRecognizer {
    public val version: String
    public suspend fun recognize(ink: InkSample): InkReading?
}
```

The scores of the first version are left out: PencilKit gives none, and the scores of two readers
cannot be compared, so no rule chooses between readers by them. `InkSample` keeps the samples only;
a reader that wants another form builds it (a `PKDrawing`, `InkStroke`s).

### What is writing

`InkLines` splits a block into lines by the vertical overlap of its strokes, and leaves out as
drawing a part much larger than the writing around it, or made of long strokes. What is left out is
not read: a misreading in the index is found by the searches and suggested while writing. The rules
are measured on the drawings of the set of phase 0; a small classifier of the strokes replaces them
if they fall short. Text and formulas are not told apart in this plan: the user tells them,
converting.

### Where the readings are kept

* **A table `handwriting` of the search index**, by block key: the reading, the reader, its
  version, the language. It is created as the other tables, at every open: `FORMAT_VERSION` stays
  1, so the vectors of the library are not made again. Derived data, read again in minutes when
  lost.
* **A chunk a block** (`SearchAnchor.Page` with the bounds of the block): its text is the most
  likely reading, embedded, shown in the snippets and counted by `NoteVocabulary`; the other
  candidates go to the words of the index only (`SearchChunk.extraWords`, FTS5), never embedded,
  shown or suggested.
* **A word seen only in the handwriting** is suggested while writing past a threshold of its own,
  measured on the set of phase 0.

### Reading the samples without modeling the strokes

`DocumentStore.loadInk(documentId)` gives, page by page, the strokes of writing that are shown:
their id, tool, bounds, and samples with the transform applied. Room reads them with a query on the
strokes, without modeling them; the default implementation goes through `loadDocument`. The
outline stays the path of the other sources; `HandwritingSearchSource` calls `loadInk` only for a
document whose pages changed.

## Phases

On `feat/handwriting`, one commit a phase, its status here in the same commit.

| Phase | Content | Status |
|---|---|---|
| 0 | **Measure first, on ink that exists or is made, none written for the bench.** The formulas: the 60 of `FormulaBench` (MathWriting, real writers). English text: IAM-OnDB (real writers, for research only: it measures, never trains; the registration is the maintainer's), if obtained. Italian text: synthetic ink, the sentences of the typed notes (a copy of the database in a scratch directory) written with a single-stroke script font (Hershey) with random slant, size, spacing, wobble and times. Drawings: Quick, Draw! (CC BY 4.0), set into blocks beside lines of text, to measure `InkLines`. Everything as InkML with its text as the label, the format `FormulaBench` reads. The readers measured on it, each by a small program of its platform in `tools/handwriting-bench` (never shipped): ML Kit on an emulator with Google Play services; PencilKit on macOS 27 and in the simulator of iPadOS 27 (Italian among `supportedLanguages`?); Windows ink in a desktop process; Gemma 4 as today. Synthetic ink is cleaner than real writing: its numbers are an upper bound, said so with the results. The formulas also on the 60 of `FormulaBench`: PencilKit, and the published models on images (Pix2Text MFR, UniMERNet, PP-FormulaNet, Texo; PosFormer and CoMER as references that may not ship), each with the license of its weights and of its training data. The characters and words wrong, the formulas exact, the right word among the candidates, the drawings read as words, the time a line (on the emulator and the computers: no device of the bench runs ML Kit). The results and the choices here; the plan approved again: the readers, Windows, the rules of `InkLines`, the plan of the formulas | Done on Windows for text and drawings (results below); left for the Mac: PencilKit, the formulas (MathWriting and the published models), Gemma 4 on the same lines |
| 1 | **The headless module and the samples.** `pageboard-handwriting` (`InkSample`, `InkReading`, the two ports, `InkLanguageModels`, `InkLines`, `HandwritingReading`, `HandwritingEngines`); `InkBlock`, `groupStrokes`, `blockKey`, `isHandwriting` moved to `pageboard-model` (`model.ink`); `DocumentStore.loadInk` in the stores, `checkInk`; tests on strokes drawn from shapes and on the InkML of phase 0; `Module.md`, `settings.gradle.kts`, the Dokka aggregate; the ABI of the new module, of model, store, store-room, store-testing, lecture and lecture-ui recorded on purpose | Done before phase 0, whose tools use it: `InkSample.retimed` gives one clock to the strokes (the recorded times start again in each session); `isHandwriting` is `StrokeTool.isHandwriting`; the lines of `InkLines` tested on strokes drawn from shapes, the InkML of phase 0 to come. On Windows: the klib dumps of store-room and lecture edited by hand (to check on the Mac), iOS not compiled |
| 2 | **Android.** `pageboard-handwriting-mlkit`: ML Kit 19.0.0, `MlKitTextRecognizer`, `MlKitLanguageModels`; the license; run on an emulator with Google Play services, not only compiled; on a device without them the reader has no languages (a test) | Done: the models downloaded and HELLO read on an emulator of Android 15 (`MlKitBenchTest`, which is also the bench of phase 0); the packages of ML Kit 19 are `com.google.mlkit.vision.digitalink.recognition`; the samples given in tenths of a millimeter. A device without Google Play services not tried (none here) |
| 3 | **Apple.** `pageboard-handwriting-pencilkit`: the Swift bridge built by Gradle into a static library for iOS (cinterop) and into the JNI library of macOS (`buildDesktopNativeLibs`); `PencilKitTextRecognizer` on the systems 27 and later, no languages before; a test reading block capitals drawn as strokes, on the Mac and in the simulator | Planned |
| 4 | **The search.** The table `handwriting`; `SearchChunk.extraWords`; `HandwritingSearchSource`; the threshold of the words of the handwriting in `NoteVocabulary`; the section of the settings (read or not, the languages of ML Kit); PencilNotes gives the readers of each platform, in the language of the system; tests (`HandwritingSearchSourceTest`, the index kept at format 1 with its vectors), the screenshots of the settings | Planned |
| 5 | **The lecture and the external models.** `HandwritingReader` reads with the readers first, from `NotebookSource.inkSample`; the model reads the blocks without samples, or all of them without a reader of the language; `handwriting.json` as today (a test that the same readings write the same bytes); `AgentEngines.handwriting` from the index for every notebook; tests | Planned |
| 6 | **Convert a selection.** `EditorEdits.selection` and `EditorExtension.selectionActions` (the ABI of canvas and ui); `pageboard-handwriting-ui`: "Convert to text" in the context menu of a selection of strokes, offered when the device reads the language; the sheet with the reading editable and the other readings; the text box at the place of the strokes, as wide as they are, at `PageTextSizes.handwriting`, in their color; one change to undo (`EditorDocument.group` of `RemoveElements` and `AddElements`, nothing new that travels); the strings in the seven languages; tests (`HandwritingConversionTest`), the screenshots; PencilNotes adds it; tried by hand where a reader runs (the simulator of iPadOS 27, an emulator with Google Play services, macOS 27) | Planned |
| 7 | **Windows**, if phase 0 says it reads well: `pageboard-handwriting-windows`, C++/WinRT in the JNI library of Windows, the recognizers of the languages installed; a test in a desktop process | Planned |

### Phase 0 results on Windows (2026-11-20)

On a Windows 11 computer (x86-64), the ink made by `HandwritingBench` (`generate`): the 60 Italian
and 20 English lines of `tools/handwriting-bench` written with the Hershey fonts `scripts` and
`cursive` (seed 2026), and 40 blocks of two Italian lines beside or under a doodle of Quick, Draw!
(10 categories, the first 4 of each). IAM-OnDB was not used (it needs a registration).

| Reader | Where | Language | CER | WER | Words among the candidates | Lines exact | Time a line (median / p90) |
|---|---|---|---|---|---|---|---|
| ML Kit 19.0.0 | emulator, Android 15, Google Play services, on the computer | Italian | 1.6 % | 8.0 % | 98.1 % | 50 % | 156 / 176 ms |
| ML Kit 19.0.0 | same | English | 0.7 % | 2.3 % | 99.2 % | 85 % | 162 / 298 ms |
| Windows ink (`InkRecognizerContainer`, "Riconoscimento grafia italiana Microsoft") | a desktop process | Italian | 2.5 % | 13.0 % | 92.2 % | 40 % | 44 / 57 ms |
| Windows ink ("Microsoft English (US) Handwriting Recognizer") | same | English | 0.0 % | 0.0 % | 100 % | 100 % | 37 / 57 ms |

* Most errors of both are the `o` of the Hershey script read as `a` ("massima", "processa"): an
  error of the font more than of the readers. The numbers are an upper bound for real writing.
* ML Kit downloads its models and reads on an emulator with Google Play services; the time of the
  emulator is not the time of a tablet.
* The ink of Windows reads from a desktop process (an STA thread, `tools/handwriting-bench/windows`),
  with the recognizers installed with the languages of Windows, and gives candidates for each word.
* `InkLines` on the 40 blocks, before and after its rules were changed by this bench (the bands of
  the lines made of the middles of their strokes, a stroke touching a drawing without writing
  beside it part of the drawing):

  | Measure | First rules | Rules now |
  |---|---|---|
  | Blocks split into their two lines | 10 of 40 | 34 of 40 |
  | Doodles left out (half of their strokes or more) | 27 of 40 | 36 of 40 |
  | Strokes of the doodles left out | 39.5 % | 83.0 % |
  | Strokes of writing left out by mistake | 0 % | 0 % |

  The doodles left in are made of small strokes (a sun, a bicycle).

Choices, revertible: ML Kit is the reader of Android; the ink of Windows is good enough for phase 7
(it is faster than ML Kit and a little worse in Italian).

The formulas get a plan of their own after phase 0: a published model that may ship, or one
trained on data we may use (formulas laid out by MicroTeX and written with permissive glyphs, the
formulas of the maintainer), with its runtime on every platform.

## Checks

* The checks of `CLAUDE.md` at every phase; `./consumer-checks/check.sh` for the phases that add
  published modules or dependencies (1, 2, 3, 7); `updateKotlinAbi` only for what a phase means to
  change, said in its commit.
* The screenshots of phases 4 and 6 verified and recorded as `CLAUDE.md` says.
* The desktop application on a scratch directory only
  (`JAVA_TOOL_OPTIONS="-Dpageboard.dataDir=<scratch dir>"`); the database of phase 0 a copy in a
  scratch directory; on a device that holds real notes, the debug build is updated, never
  uninstalled.

## Decisions

Agreed with the maintainer:

* What ships may be published on the stores: the non-commercial datasets measure, never train
  (2026-10-29).
* No test set written by hand: phase 0 measures on public and synthetic ink (2026-11-20).
* The Android devices without Google Play services are not supported: ML Kit has no languages
  there, and the lecture keeps reading with its model (2026-11-20).

Taken alone, all revertible until the maintainer approves the plan:

* Online recognition from the samples on every platform; no reader of images.
* PencilKit on iOS, iPadOS and macOS 27, nothing on the systems before; ML Kit on Android, not on
  iOS.
* One Swift bridge for iOS and macOS, built by Gradle. If linking Swift into the framework of iOS
  costs too much, the iOS applications give the reader in Swift, on the same port.
* Windows only if phase 0 measures it well; no reader on Linux.
* The readings live in the search index, in a table added at format 1; nothing of them travels; the
  documents, the lecture records, the Room schema and the protocol do not change.
* The candidates are strings, without scores; the other candidates are found by the words only.
* The lines and the drawings by rules; text and formulas told apart by the user.
* The formulas in a plan of their own, after phase 0.
* The actions on a selection are a new contribution of `EditorExtension`, with a default.
* The module names `pageboard-handwriting`, `-mlkit`, `-pencilkit`, `-windows`, `-ui`.

To be decided by the maintainer:

* **ML Kit downloads its language models from Google** (about 20 MB each); the reading itself stays
  on the device. The privacy forms of the stores say it.
* **IAM-OnDB**: registering for it (research use), or measuring English on synthetic ink too.

## Left for later

* A reader for the Apple systems before 27 (Vision, or ML Kit through CocoaPods); Linux.
* The readings shared with the other devices of the notebook (a change of what travels).
* The handwriting read while the user writes (suggestions in the ink, a scratch to erase).
* The word found highlighted on the page (PencilKit gives its bounds; ML Kit and Windows the
  strokes of each word).
* A whole page converted into a text box, text and formulas in their order.
* The readers adapted to the writing of the user.

## Risks

* **Android without Google Play services** has no reader, by decision: some tablets, the one the
  maintainer writes on included.
* **PencilKit**: a Swift actor, so the bridge is the only way in, and a Swift build is new to the
  repository (Gradle for iOS, the native build of macOS); its languages may not include Italian; no
  other readings; in the simulator only the languages written in Latin letters; only on the systems
  27 and later.
* **Segmentation** is the hard part: lines that touch, a formula inside a sentence, arrows and
  diagrams between the words. The readers are only as good as the parts they are given.
* **Misreadings** reach the search and the suggestions while writing.
* A tablet of a few years ago may need more than 100 ms a line: the conversion waits, the library
  takes longer to read.
* **No real Italian handwriting is measured**: synthetic ink is cleaner (no joined letters, no
  loose lines), so the first real measure is the use, the readings the users correct when
  converting.
* The license of a published formula model may not say what it was trained on.
