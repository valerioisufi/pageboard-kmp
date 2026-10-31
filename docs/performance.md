# Performance

Writing, opening a document, searching and scrolling do more work than they need: every stroke
makes the library query the whole catalog again, the search and the thumbnails load whole
documents (every stroke modeled again by Ink) to read their texts or their first page, and the
model looks for an element by its identifier through the whole document. This document starts
from the code as it is (main at c4a57f5b, 2026-10-31): what costs, then the design and the plan.
Every change is measured before and after, and kept only when it gains.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

The costs below are read from the code; phase 0 measured them (see "Measures"). Modeling the
strokes costs by far the most, and the search and the thumbnails pay it for whole documents; the
writes of a stroke cost a little; the lookups of the model, the passes of the indexer with nothing
changed and the order of the tiles cost almost nothing on the Mac.

### Writing

1. **An operation is written in 3 to 4 transactions** (`ChangePersister.onChange`): the elements
   (`saveStrokes`, `saveText`…), the page (`savePage`: its `contentVersions` change at every
   operation), the log (`appendOperation`) and the date of the document (`touchDocument`), each
   DAO call in its own transaction.
2. **The catalog flows are not shared** (`RoomDocumentStore.documents`, `folders`, `trash`): each
   is a `combine` of Room flows, run again by every collector at every write of the tables it
   reads. While a note is written in PencilNotes they are collected by `FileExplorerViewModel`
   (documents, trash, folders), `SearchIndexer` (documents twice, folders), `EditorSessionScreen`
   (documents and folders, for the name of the folder of the document) and `PencilNotesApp`
   (documents and trash). The query of the documents (`getAllDocumentsWithContentFlow`) counts the
   pages and looks for a PDF of every document.

### Search and thumbnails

3. **The search loads whole documents** to read their texts: `DocumentSearchSource.chunks`,
   `PdfSearchSource.chunks` and `LectureSearchSource.chunks` (for the numbers of the pages) call
   `DocumentStore.loadDocument`, which models every stroke again.
4. **The text of the PDFs is extracted again at every change of the document**:
   `PdfSearchSource.version` is made of `modifiedAt`, which a stroke changes.
5. **A pass of the indexer counts the chunks to embed after every document**
   (`SearchIndex.unembeddedCount`: a `COUNT(DISTINCT)` with a `LEFT JOIN` on every chunk), and
   `DocumentSearchSource.version` reads the folders and the version vector of every document (3
   queries). `LectureSearchSource.version` reads every lecture record of every document.
   Measured: a pass with nothing changed takes 46 ms over 101 notes, without lectures.
6. **A thumbnail opens the whole document** to draw its first page (`DocumentThumbnails.draw`), and
   is drawn again when `modifiedAt` changes: a stroke on the last page draws the first one again.

### The model

7. **An element is looked for in the whole document**: `AddElements` and `RemoveElements` call
   `findAnyElement` for each element, which reads the page given, then every page
   (`allPages()` makes a new list) and every removed element. A new stroke, which is not there,
   reads them all. The stroke drawn is committed while `TileManager.bakeStrokesDirectlySync` holds
   the lock of the tiles; the operations received are applied on the thread of the document (the
   UI thread, `SyncEngine.onOperations`).
8. **A page is built one element at a time**: `DrawPage.add` copies the list of its kind (the
   tiles read the content of a page on other threads), so loading a page copies O(n²) elements.
9. **The stack of a page is sorted again after every change** (`PageElements.stack`), though the
   list of each kind is in order already, and `PageContentSnapshot.paintOrder` makes a new list at
   every read (once per tile).

### Opening a document

10. **Every stroke is modeled again by Ink** (`DrawDocumentRepository.loadDocument`), the removed
    ones too, on one thread, about 1 to 2.4 ms each: the bridge updates the shape of the stroke at
    every input (`UpdateShape` in `ink_stroke_builder_add_point`), as the stroke being drawn needs,
    though the inputs of a stroke loaded are all known. `InkStrokeEngine.buildMesh` makes a native
    builder for each stroke, which costs nothing more (measured: 0.93 ms a stroke of 120 inputs
    either way).

### The editor

11. **The editor recomposes at every change of the document**: `DrawScreenContent` and
    `EditorTools` call `observeChanges()`, to know whether the document has PDF pages and the
    formats of its pages.
    `TextBoxEditor` reads `controller.version` and the camera in its composition: the text field
    being written recomposes at every frame of a pan.
12. **The tiles are rasterized in the order they were asked for** (`TileManager.enqueueTile`, two
    workers), and a tile asked for is rasterized even when it has left the screen: its job is
    cancelled only when the page is laid out at another size. After a fling or a zoom across the
    levels, the workers draw tiles no longer shown before the ones on screen. `drawTiles` reads
    every page at every frame (`ensurePageGeometry` takes a lock for each). Measured: the screen
    is filled about 20 ms after a jump or a fling of 20 pages on the Mac, the workers keeping up.
13. **A tile reads every element of its page** (`TileRenderer.renderPageContent`), and the path of
    the outline of a stroke is built again at every draw (`StrokeMeshPainter`), for every tile and
    level it is drawn in.
14. **A PDF page is loaded and closed for every tile** (`pdfium_bridge.cpp`), and on the desktop
    its pixels are copied three times (`IntArray`, `ByteBuffer`, Skia bitmap). The texts are
    measured and drawn under one lock (`ComposeTextTileAdapter`): the two workers draw texts one
    at a time.

The images are decoded at most 2048 pixels on their longest side (`decodeImageFile`): they are
not a cost.

## Requirements

1. **The notes are not touched**: no change of the Room schema or of a stored format. The search
   index, a cache rebuilt by itself, may be chunked again once (its embeddings are kept by the hash
   of their text).
2. **Nothing sent changes**: the protocol as it is, `ProtocolFormatTest` unchanged.
3. **What is drawn stays the same**: the screenshots of `verifyRoborazziJvm` unchanged. A stroke
   modeled from its inputs at once has the shape of the one modeled input by input; the user
   accepted that its edges may move by a few hundredths of a millimeter (phase 6, decision 12).
4. **Measured**: every phase gives its numbers before and after (phase 0), written in this
   document; a change that gains nothing is not kept.
5. **The stores of the applications keep working**: every new read of `DocumentStore` has a
   default made from `loadDocument`; the contract tests (`drawview-store-testing`) check it for the
   Room store and the key-value store.
6. **The measures never touch the data of the user**: in-memory databases or scratch directories.
7. **Every platform**: Android, the desktop JVM and iOS; a change of a native bridge is built and
   tested on each.
8. **The public API changes are recorded** (`updateKotlinAbi`) and said in the commit.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Writing the effects of an operation at once | `DocumentPersistence.transaction` (model), done by `DrawDocumentRepository` with `useWriterConnection` (store-room), used by `ChangePersister` | Protected Variations (by default the block runs as it is), Information Expert (the store knows its transactions) |
| One query per change of the catalog, whoever follows it | `RoomDocumentStore`: the Room flows shared (`shareIn`) | Information Expert, Low Coupling (the collectors stay as they are) |
| Reading what can be searched, without the strokes | `DocumentStore.loadOutline` → `DocumentOutline` (store), done by Room with the queries of the texts, the elements of the applications, the PDF pages and the resources | Information Expert, Protected Variations (default from `loadDocument`), Pure Fabrication (a value that cannot be saved back) |
| The version of the text of the PDFs of a document | `PdfSearchSource.version`, from the PDF pages of the outline | Information Expert |
| Knowing whether the first page changed, and reading it alone | `DocumentStore.pageFingerprint`, `DocumentStore.loadPage` → `LoadedPage` (store), done by Room from the stamps of the rows and the rows of one page | Information Expert, Protected Variations (defaults from `loadDocument`), Pure Fabrication (a page that cannot be saved back) |
| Drawing a thumbnail again only when its page changed | `DocumentThumbnails` | Controller |
| Finding an element of a page by its identifier | `DrawPage`: an index of its elements, shown and removed | Information Expert |
| Building a page from stored or received elements | `DrawPage.load(shown, removed)`, used by the stores and the snapshots | Creator |
| The stack of a page in one pass, the elements in the order they are painted | `PageElements`: kept as it is, measured (decision 20) | Information Expert |
| What the editor shows of the document, as it changes | `DrawDocument.observe` (canvas): a derived state, read by `DrawScreen`, `EditorToolbar` and `DocumentInfoSelector` (ui) | Information Expert (the view says what it reads), Low Coupling (the model stays out of Compose) |
| Whether there is something to undo or redo | `HistoryManager.canUndo`, `canRedo` (canvas): derived from its stacks | Information Expert |
| Where the field of the text being written is on the screen | `TextBoxEditor` (ui): its scale composed, its place read while it is placed | Information Expert |
| Modeling a stroke whose inputs are all known | `InkStrokeModeller.buildMesh` (ink), with `ink_stroke_builder_add_points` (the bridge: the inputs given at once, the shape updated once) | Information Expert |
| Modeling the strokes of a document at the same time | `DrawDocumentRepository.loadDocument` (the key-value store on one thread, decision 11) | Controller |
| The elements of a page in a region | `PageElements`: a grid of cells, `PageContentSnapshot.elementsIn` | Information Expert |
| A PDF page kept loaded between its tiles | `pdfium_bridge` and `PdfiumEngine` (pdf) | Information Expert |

### Writing an operation at once

```kotlin
public interface DocumentPersistence {
    /** Runs [block], whose writes are stored all or none (by default as it is). */
    public suspend fun <T> transaction(block: suspend DocumentPersistence.() -> T): T = block()
}

// DrawDocumentRepository
override suspend fun <T> transaction(block: suspend DocumentPersistence.() -> T): T =
    database.useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
```

`ChangePersister` writes each operation in one `transaction`. The DAO calls made in the block take
the connection of the transaction from the coroutine context (`ConnectionElement`), which the
`withContext(Dispatchers.IO)` of the repository keeps. The tables written by an operation are told
once to the flows that read them, and `modifiedAt` is stored with the content it dates, which the
thumbnails need (phase 3).

The flows of `RoomDocumentStore` share the four Room flows they are made of (all the documents,
with their content and without, the folders, the shared documents), in a scope of the store:

```kotlin
private val allDocumentsWithContent = fileRepository.getAllDocumentsWithContentFlow().sharedQuery()

override val documents: Flow<List<DocumentSummary>> =
    combine(allDocumentsWithContent, allFolders, sharedIds) { … }.distinctUntilChanged()

private fun <T> Flow<T>.sharedQuery(): Flow<T> {
    val query = this
    return shareIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS)).onSubscription { emit(query.first()) }
}
```

A query runs once for all its collectors when its tables change. A new collector gets a value read
for it first, not the last value shared: the library reads what it has just written
(`Collaboration.sharedFolder` creates the folder of the documents received and looks for it in
`folders.first()`, `StoreSharedDocuments.save` looks for a document just received), and a cache
could still be the list before the write (decision 15). `documents.first()` therefore queries as
before; the indexer reads it once a pass.

### Reading what can be searched

```kotlin
/** The pages of a document with what can be read of them, without the strokes: a value, never saved. */
public class DocumentOutline(
    public val id: String,
    /** The pages shown, in their order. */
    public val pages: List<PageOutline>,
    public val resources: List<DrawResource>
)

public class PageOutline(
    public val id: String,
    public val texts: List<DrawText>,
    public val customElements: List<DrawCustomElement>,
    public val pdfs: List<DrawPdf>
)

public interface DocumentStore {
    /** What can be read of the document [documentId] (see [DocumentOutline]), or `null`. */
    public suspend fun loadOutline(documentId: String): DocumentOutline? = loadDocument(documentId)?.outline()
}
```

A `DrawDocument` without its strokes is not used for this: given by mistake to `OpenDocuments` or
to the persistence, it would lose them.

The Room store reads the pages of the document with their texts, PDF pages and elements of the
applications alone (`PageDao.getPageOutlines`, a relation without the strokes, the images and the
marks), keeps the pages shown (`DrawPage.isHidden`) and the elements shown, in their order. The
key-value store reads the outline from the document stored without taking it as the document
open: its `loadDocument` does, and an outline read while the editor has the document open would
replace the instance the editor changes (decision 13). `checkOutline` (`drawview-store-testing`)
checks every store against the outline of the document it loads whole.

`PdfSearchSource.version` is made of the name of the document and the hash of its pages in their
order, each with the resource, the hash of its file and the index of its PDF pages: a stroke does
not change it, a PDF page added, removed or moved does.

`LectureSearchSource.version` stays as it is: it reads every lecture record of every document,
twice (`LectureLibrary.list`, then `load`), which costs 53 ms a pass for 60 lectures of 90 minutes
on the Mac (M2), as much as the rest of a pass with nothing changed, in the background after a
quiet time (decision 14).

### The first page of a document

```kotlin
public interface DocumentStore {
    /** A text that changes whenever the page at [index] (among the pages shown) changes as it is drawn, or `null`. */
    public suspend fun pageFingerprint(documentId: String, index: Int): String? =
        loadDocument(documentId)?.pageFingerprint(index)

    /** The page at [index] (among the pages shown) loaded alone, to be drawn, or `null`. */
    public suspend fun loadPage(documentId: String, index: Int): LoadedPage? =
        loadDocument(documentId)?.let { LoadedPage.of(it, index) }
}

/** A page read alone to be drawn: a value, never saved back. */
public class LoadedPage(public val id: String, public val content: PageContentSnapshot, public val resources: List<DrawResource>)
```

The fingerprint is the hash of what the page is drawn from: its identifier, format and background
(the default of the document when it has none), the identifier of every element shown with every
stamp of its changes (`added`, `placed`, `ordered`, `updated`, and the content versions of a text),
the path and the hash of the files its images, PDF pages and marks show. Every change of an
element that shows writes the stamp of its register; an element erased leaves the elements shown,
one shown again or moved onto the page comes in, a page put before it or removed makes another
page the first. The changes of another page, and of an element removed, change nothing.

The Room store reads the fingerprint from the rows (`PageDao.getShownElementStamps`: the
identifiers and stamps of the elements shown, as stored text, nothing decoded), and loads a page
from its rows alone (`PageDao.getPageWithContent`), modeling its strokes shown only. The
key-value store reads both from the document stored, as its outline (decision 13).
`checkPageFingerprint` (`drawview-store-testing`) applies every kind of change to the first page of
a document, and to the second one, and checks that the pages loaded alone are the ones of the
document loaded whole. A store keeping the defaults loads a document whose first page changed
twice (its fingerprint, then the page), once before: the stores of DrawView override both.

`DocumentThumbnails.of` keeps its parameters. When the date of change of a document is not the one
its thumbnail was drawn at, it reads the fingerprint of the first page: the same, the thumbnail is
kept and dated again (in memory, and its key on disk, `date:width:fingerprint`); another one, the
page is loaded alone and drawn. The date comes from the store, written in the same transaction as
the content it dates (phase 1), so the thumbnails read the store, not the document open
(decision 17).

### Elements by identifier

```kotlin
public class DrawPage {
    /** The elements removed from the page, hidden: put by [load] and by the changes of the document. */
    public val removedElements: List<DrawElement>

    /** The element [id] shown on this page, or `null`: on the thread of the document, like [add]. */
    public fun find(id: String): DrawElement?

    /** Puts [shown] and [removed] on this page at once, each kind in its stacking order (a page being built). */
    public fun load(shown: Collection<DrawElement>, removed: Collection<DrawElement> = emptyList())
}
```

`DrawPage` keeps two maps of its elements by identifier, the ones shown and the ones removed,
changed with them by `add`, `remove` and the functions of the changes for the elements removed
(`keepRemoved`, `forgetRemoved`: hidden, shown again, moved hidden to another page, purged);
`removedElements` is no longer a `MutableList`. Like `add` and `remove`, they are used on the
thread of the document (decision 21). `DrawDocument.findElement` and `findRemovedElement` read
the maps of the pages shown, then of the pages removed (no list made): one lookup per page
instead of a comparison per element.

The stores and the snapshots build a page with `load`: the elements of each kind put in a list
sorted once, where `add` copied the list of its kind for every element. An element without order
gets the order `add` would give it (the next order of the elements of its layer built so, in the
order given), so a page loaded is the page built element by element (`PageIndexTest`). The
loaders say which elements are removed (decision 19).

### The editor

```kotlin
/** What [read] reads of this document, as it changes: its readers composed again when it changes. */
@Composable
public fun <T> DrawDocument.observe(read: DrawDocument.() -> T): State<T>
```

The model is not Compose state: what shows the document follows its revision. `observeChanges()`
composes its caller again at every change; `observe` gives a derived state of the revision and of
what `read` reads, so that its readers are composed again only when what they read changes. The
editor reads with it whether the document has PDF pages (`DrawScreen`, `EditorTools`), how many
pages it has (the subtitle of the bar) and its title (`DocumentInfoSelector`). The sheets of the
formats of the pages and of the document follow every change while they are open, their content
calling `observeChanges()`; the information of the document (its popover) and the page grid
follow every change while they are shown, as before.

The buttons Undo and Redo read `HistoryManager.canUndo` and `canRedo`: derived from its stacks,
they change only when a stack becomes empty or stops being.

`TextBoxEditor` composes the scale of its page (a derived state of the camera and of the pages)
and reads where its box is on the screen while it is placed (`offset`, and the place of the
formula under the caret): a pan moves the field without composing it again. A zoom composes it at
the new scale, as before (left for later, 9).

### Modeling the strokes loaded

```c
/** Adds the [count] inputs of a stroke at once and updates its shape once (a stroke loaded). */
void ink_stroke_builder_add_points(InkStrokeBuilderHandle handle, const float* xs, const float* ys,
    const float* pressures, const int64_t* times_ms, int32_t count, int32_t tool_type);
```

`InkStrokeModeller.buildMesh`, whose inputs are all known, gives them in one call (through JNI on
Android and the desktop, cinterop on iOS): the bridge turns them into inputs as
`ink_stroke_builder_add_point` does (the same function, `NextInput`), enqueues them as one batch
and updates the shape once, then once more after `FinishInputs`. It is how Ink models a stroke
stored (`Stroke::RegenerateShape`). The stroke being drawn keeps updating its shape at every
input.

The meshes are not the same, bit for bit (`WholeStrokeModelingTest`). Without behaviors over time
or distance (the marker, the highlighter, the eraser) the vertices are the same but for a
rounding, on a grid of 0.01 mm the same shape. The pressure pen and the dashed line have such
behaviors, and Ink simplifies their outline in another way when it has every input at once: for
the strokes as written the edges of the pen move by 1% of its size at most (0.005 mm for a pen of
0.3 mm, 0.03 mm for 2 mm, 0.07 mm for 8 mm), 3.5% in a random scribble; the dashes of the dashed
line (the lasso, never stored) move along it, up to 14% of its size in a scribble. The user
accepted it (decision 12). The inputs are what is stored and synchronized, so nothing stored or
sent changes: a stroke just drawn keeps its mesh, and it may move by that much once the document
is opened again, put back by undo, or received by another device.

The Room store models the strokes of a document on every core as it loads it
(`DrawDocumentRepository.loadDocument`): tasks of 64 strokes on `Dispatchers.Default`, each call
with its own native builder, the strokes then put in their pages in order. A `StrokeModeller` is
therefore called from several threads at once, which its KDoc now says.

## Phases

| Phase | What | Status |
|---|---|---|
| 0 | Measures: synthetic documents (`drawview-store-testing`), benchmarks run with `-Pdrawview.benchmarks=true` (out of `jvmTest`), a driver counting the statements; the numbers before, in this document | done |
| 1 | `DocumentPersistence.transaction`, one per operation (test: an operation failing leaves none of its rows); the flows of `RoomDocumentStore` shared and distinct (test: one query of the documents per stroke, whatever the collectors, and a folder created read at once) | done |
| 2 | `DocumentStore.loadOutline` (both stores, contract test) used by the three search sources; the version of the PDFs from the outline (test: a stroke does not change it, a PDF page added does); `LectureSearchSource.version` measured and kept (decision 14) | done |
| 3 | `DocumentStore.pageFingerprint` and `loadPage` (contract test: every kind of change of the first page changes the fingerprint, a change of another page does not); the thumbnails drawn from them (test: a change of another page keeps the thumbnail, dated again) | done |
| 4 | The elements of a page by identifier, `DrawPage.load` used by the loaders (test: a page loaded is the page built element by element; the elements are found wherever 400 random changes put them, against the lists of the pages); the stack in one pass and `paintOrder` measured and kept as they are (decision 20) | done |
| 5 | The editor: `DrawDocument.observe` instead of `observeChanges()` in `DrawScreenContent`, `EditorTools` and the subtitle of the bar, Undo and Redo derived, the position of `TextBoxEditor` read while it is placed (test: over 100 strokes the editor enters no scope again, and shows a page added and a title renamed; over 100 frames of a pan the field of a text enters no scope again and moves with its box, and a zoom composes it at its scale) | done |
| 6 | The inputs of a stroke loaded in one native call and its shape updated once (bridge, JNI, cinterop), the strokes of a document modeled on every core (test: the shape of every brush, as drawn, on the JVM and the iOS simulator) | done |
| 7 | The tiles: the elements of a region (`elementsIn`), the paths of the outlines kept (if they gain) | proposed |
| 8 | The PDF pages kept loaded between their tiles and drawn with one copy; the texts drawn outside of the lock of the caches (if the measurer allows it) | proposed |

The phases after 1 do not depend on each other, but phase 3 needs phase 1. By what phase 0
measured, they go in this order: 6 (every document loaded pays the modeling: the editor, the
search, the thumbnails), 2, 3, 1, 4, 5; 7 and 8 only if a device shows the tiles costing.

### Measures

On the Mac (M5, JVM), 2026-10-31, before and after each phase. Each benchmark runs alone:
`./gradlew :<module>:jvmTest -Pdrawview.benchmarks=true` (`StoreBenchmark` in `drawview-store-room`,
`IndexerBenchmark` in `drawview-search`, `LectureSearchBenchmark` in `drawview-lecture-ui`,
`ThumbnailBenchmark` in `drawview-explorer`,
`ApplyBenchmark` in `drawview-model`, `ModelingBenchmark` in `drawview-ink`, `TileBenchmark` in
`drawview-canvas`, `EditorBenchmark` in `drawview-ui`). M8 counts the scopes the composition enters
again (a function called again, whether it runs or skips) with an observer of the compositions
(`CompositionCounter`, from the experimental API of the Compose runtime, in the tests only).

| Measure | Before | After | Phase |
|---|---|---|---|
| M1 statements per stroke committed, with the collectors of PencilNotes (200 notes) | 42.9 (20.0 queries, 8.2 ms of them) | 22.0 (4.0 queries, 0.73 ms of them) | 1 |
| M2 a search pass after a stroke on a note of 30 pages × 400 strokes (101 notes) | 28.8 s | 0.82 s (phase 6), 57 ms (phase 2) | 2, 6 |
| M2 indexing 101 notes from nothing | 2 min 53 s | 5.4 s (phase 6), 0.25 s (phase 2) | 2, 6 |
| M2 a search pass with nothing changed | 46 ms | 41 ms | — |
| M2 the versions of the lectures in a pass (101 notes, 20 with 3 lectures of 90 minutes, 294 KiB each) | 53 ms (measured in phase 2) | | — |
| M3 the thumbnail of a note of 50 pages × 600 strokes changed on its last page | 1.88 s (the opening of the whole note, M5) | 2.1 ms | 3 |
| M3 the same thumbnail changed on its first page | 1.97 s | 63 ms (the page alone) | 3 |
| M4 1,000 operations received on a document of 100,000 elements | 483 ms (0.48 ms each), 561 ms at the start of phase 4 | 21 ms (21 µs each) | 4 |
| M4 a stroke submitted on a document of 100,000 elements | 0.44 ms | 7.2 µs | 4 |
| M4 a page of 10,000 strokes built from the elements loaded | 39 ms (measured in phase 4) | 2.9 ms | 4 |
| M4 the paint order of a page of 2,000 strokes read after a stroke | 0.042 ms (measured in phase 4) | 0.026 ms (unchanged code) | — |
| M5 opening a note of 50 pages × 600 strokes, 10% erased | 71.9 s (24 ms of queries) | 1.89 s (26 ms of queries); 1.81 s after phase 4, within the noise | 6 |
| M5 a stroke of 120 inputs modeled from its inputs at once / input by input | 0.93 ms / 0.93 ms | 0.15 ms / 0.92 ms | 6 |
| M6 the screen filled after a jump of 20 pages / a fling over 20 pages | 21 ms / 18 ms | | — |
| M8 a stroke drawn, in the editor (1280 × 800, the tools in the top bar) | 52 scopes entered again, 0.22 ms of composition (measured in phase 5) | none (5 derived states read again, unchanged), 0.040 ms | 5 |
| M8 a frame of a pan while a text is written | 13 scopes entered again, 0.20 ms of composition (measured in phase 5) | none (1 derived state read again), 0.037 ms | 5 |
| M7 a tile of a page of 1,500 strokes, level 0 / level 2 | 10.4 ms / 2.1 ms | | 7 |
| A tile of a PDF page | measured at the start of phase 8 | | 8 |

## Decisions

Taken alone, revertible:

1. **Measured first** (phase 0): every optimization is kept for what it gains, its numbers above.
2. **An operation is stored all or none**: today a failure in the middle leaves elements without
   their operation in the log (never sent to the other devices); with the transaction the content
   and the log agree.
3. **`modifiedAt` written at every operation**, as today: with the flows shared it costs one query,
   and the library stays sorted exactly. (The analysis proposed writing it less often.)
4. **`DocumentOutline` rather than a document without strokes**: one that could be saved would lose
   them.
5. **The index of the elements in each page**, not in the document: the pages are built apart (the
   loaders, the snapshots) and an index per page needs no owner.
6. **The synthetic documents in `drawview-store-testing`**, which the contract tests can use too
   (its ABI grows).
7. **The benchmarks out of `jvmTest`**, behind a Gradle property: the checks of a phase stay as
   fast as today.
8. **Phase 6 without a builder of many strokes** (`StrokeModeller.newBuilder` in the first version
   of this plan): an engine for all the strokes models them no faster (M5).
9. **No scheduler of the tiles, and no count of the chunks once per pass**: phase 0 measured no
   cost on the Mac (M6, M2 with nothing changed). They are left for later, for a device that
   shows one.
10. **The order of the phases by what they gain** (above), not by their number.
11. **The key-value store models its strokes on one thread**, as before: it loads a document under
    its lock, and only the tests use it; the applications use the Room store.
13. **The outline of the key-value store not taken as the document open**: its `loadDocument`
    makes the document loaded the one it writes back, so a search reading it while the editor
    had it open replaced the instance the editor changed (the test of the version of the PDFs
    lost a page that way). `loadDocument` keeps doing it, for `OpenDocuments`; the fingerprint
    and the page loaded alone (phase 3) read the document stored too.
14. **The version of the lectures kept**: 53 ms a pass on the Mac for 60 lectures of 90 minutes
    (M2), in the background; reading every record once would halve it, with a new function of
    `LectureLibrary`. Left for later (below).
15. **The queries of the library shared without a cache** (the first version of this plan kept the
    last list, `replay = 1`): a collector reads its own first value, so that a read after a
    write has the write. The store keeps its own scope (`SupervisorJob`, `Dispatchers.Default`):
    a scope given to it would change its constructor, and the queries stop 5 seconds after their
    last collector.

16. **The fingerprint of a page from every stamp of every element shown** (the first version of
    this plan counted the elements, removed ones included, with the latest stamp of each): a
    change received from another device may have a stamp older than the latest one of the
    element, when it wins a register of its own (its placement under a newer change of its
    content). The stamps are read as they are stored, as text, without the elements removed.
17. **The thumbnails read the store, not the document open**: they drew the document open in the
    editor when it was, which the store has as soon as the library sees its date of change (the
    key-value store writes a document open before reading it). The parameter `documents` of
    `DocumentThumbnails` stays, for its store: the API does not change.
18. **The keys of the thumbnails on disk with the fingerprint** (`date:width:fingerprint`): the
    old ones (`date:width`) are still read, and a thumbnail without a fingerprint is drawn again
    once its document changes. The thumbnails are a cache: nothing to migrate.

19. **`DrawPage.load(shown, removed)`** rather than `load(elements)` putting each element shown or
    removed by its stamp (the first version of this plan): the loaders know which elements are
    removed (a snapshot keeps them in a list of its own, and gives them their stamps after
    building them), so a page is loaded as they say, as before.
20. **The stack in one pass and `paintOrder` made once per content, not done**: phase 4 measured
    0.04 ms to read the paint order of a page of 2,000 strokes after a stroke (the sort of the
    four lists, each in order already, finds their runs), once for the first tile drawn after
    it, which takes about 10 ms (M7). Nothing to gain.
21. **The maps of the elements used on the thread of the document**, not made part of the content
    the tiles read: a copy of a map at every change would cost as much as the copies `load`
    saves. `find` was safe on any thread (it read the content); its callers outside of the model
    (`CustomElementManager`, `CollaboratorPresence`, `CollaboratorsLayer`) run on the main
    thread, the thread of the documents of the applications.

22. **`DrawDocument.observe` public, in `drawview-canvas`** beside `observeChanges()` (its ABI
    grows): the panels and overlays of the applications read the document as the editor does.
    It could be internal to `drawview-ui`, its only user today.
23. **The sheets of the formats follow the document while they are open**, their content calling
    `observeChanges()`: they read it as before, and only while they are shown.
24. **The title of the bar follows a rename** (`DocumentInfoSelector` reads it with `observe`):
    the test of phase 5 found that it did not, before this phase too. The bar was composed again
    at every change, but `DocumentInfoSelector`, given the same document, was skipped, so a title
    renamed on another device showed only once the note was opened again.
25. **Undo and Redo derived** (`HistoryManager`): they were composed again at every stroke, their
    stacks being state lists read whole. Not in the first version of this plan: once the editor
    read the document with `observe`, they were the 4 scopes still entered at a stroke (M8).

Decided by the user:

12. **A stroke loaded may differ from the one drawn** (2026-10-31, phase 6): its edges move by a
    few hundredths of a millimeter (above), for an opening 38 times faster (M5: 71.9 s, 15.8 s
    with the strokes modeled on every core but input by input, 1.89 s with their inputs at
    once).

## Left for later

These change or delete stored data, or need a plan of their own; they are for the user to decide.

1. **The log of the operations in CBOR**: today JSON (about 70 bytes a point of a stroke, against
   20 in its row), every stroke stored twice. A new stored format, with a migration of every row
   tested with `MigrationTestHelper`; the protocol does not change (CBOR already on the wire).
2. **Compacting the log and purging the removed elements of the documents never shared**: today
   only `SyncEngine.compact` does it, for the shared ones, so the others keep every operation and
   every stroke erased, modeled again at every opening. It deletes stored data.
3. **The removed strokes modeled only when shown again**: `DrawStroke` needs its mesh to be made.
4. **R8 and a Baseline Profile in PencilNotes for Android**: the libraries need consumer rules for
   their JNI members and kotlinx.serialization.
5. **The recovery of the sync in pages** (`operationsAfter` decodes the whole tail at once), and
   the proofs of the own operations kept rather than signed again.
6. **The tiles**: a pool of bitmaps, a budget following the screen, fewer objects made per frame.
7. **The points of a stroke in arrays**: `List<StrokePoint>` takes about 32 to 40 bytes a point;
   `StrokeMesh.points` is public.
8. **The stroke being drawn**: its whole mesh and outline are copied from Ink at every frame.
9. **The scale of `TextBoxEditor` during a zoom** by a layer: the text being written must stay as
   the page draws it (`docs/text-parity.md`).
10. **`org.gradle.parallel`** for the build.
11. **The tiles rasterized the most needed first** (a scheduler cancelling the tiles no longer
    shown), and the chunks to embed counted once per pass: no cost measured on the Mac (M6, M2).
12. **The version of the lectures without reading every record** at every pass (53 ms on the Mac
    for 60 lectures, decision 14): a version kept by `LectureLibrary` as it writes a record, or
    the records read once.
