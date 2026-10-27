# Search in the notes: words, meaning, and questions

The library finds the notes by what they hold, not by their names only: the texts, the text of
the PDFs, the handwriting (formulas included), the lectures. A search finds the same words
(full-text) and the same meaning said otherwise (embeddings of EmbeddingGemma 2), and leads to the
place: the page and its area, or the minute of the lecture. "Ask your notes" answers a question
with the passages found, citing them (retrieval-augmented generation). Everything runs on the
device, on the runtime of [docs/local-models.md](local-models.md).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* **The search of the library looks at the names only** (`FileExplorerViewModel.search`, a
  `contains` over the names of the notes and of the folders); the design wanted the typed text and
  the text of the PDFs too ([docs/ui-design.md](ui-design.md), "Not as the file").
* **What the notes hold**:
  * the texts: `TextEntity.text`, Markdown with LaTeX;
  * the PDF pages: their text is read by `PdfiumDocument.getPageText` of `drawview-pdf`, an
    optional engine, reached by the canvas through its PDF port;
  * the elements of other developers: nothing says their text;
  * the handwriting, most of the notes: it is text only in the notebooks with a lecture, read by
    `HandwritingReader` into `lectures/doc-<id>/handwriting.json` (by block of strokes, at most
    5,000 blocks, shared with the devices of the notebook);
  * the lectures: `lecture.json`, with the transcript in timed segments, the suggestions and the
    review, shared with the devices of the notebook.
* **Ink as an image**: `InkBlock` and `groupStrokes` (`drawview-lecture`) group the strokes of a
  page into blocks; `NotebookSource.renderInk` renders a block of the notebook open in the editor
  (`drawview-lecture-ui`); `DocumentThumbnails` (`drawview-explorer`) draws stored pages
  offscreen.
* **Stores**: the documents in Room (`DrawDatabase`, version 18, `BundledSQLiteDriver`);
  `DocumentCatalog.documents` (a `Flow` of summaries with `modifiedAt`);
  `OperationLog.versionVector(documentId)`.
* No embeddings anywhere.

## EmbeddingGemma 2 (October 2026)

* Released on 6 October 2026, Apache 2.0. One space of 768 dimensions for text, code, images and
  audio; the vision and audio encoders are optional (text only: 270M parameters, 165 MB in int4 for
  LiteRT-LM). Context of 8,192 tokens; more than 100 languages (Italian not named).
* Matryoshka: 768, 512, 256, 128 dimensions, normalized again after the cut. MTEB multilingual
  (mean of the tasks): 61.36, 61.17, 60.41, 57.89.
* Prompts (model card): a query `task: search result | query: {query}`, a question
  `task: question answering | query: {question}`, a document `title: {title} | text: {content}`
  (`title: none` without one). The page of LiteRT-LM shows `task: search query | text: ...`:
  phase 0 checks which one the model of LiteRT-LM expects.
* An embedding of 128 tokens (LiteRT-LM): 27 ms on the CPU of a Galaxy S26 Ultra, 31 ms on the CPU
  of a MacBook Pro M5, 72 ms on the CPU and 19 ms on the GPU of a Dell XPS 16.

## Requirements

* One search over the names, the texts, the text of the PDFs, the handwriting (formulas as
  LaTeX), the lectures (transcript, review, suggestions), and the elements of other developers
  (rule 6).
* The same words and the same meaning; Italian first, the other languages of the notes too.
* A result says where it is and leads there: the page and its area, the lecture and its minute.
* "Ask your notes": an answer from the passages found, with its citations; "not in your notes"
  when they do not say it.
* Offline: the index and the queries never leave the device. Gemini answers a question only when
  the student chooses it, told what is sent.
* Incremental, in the background: what changed is read again, and only that; a lecture being
  recorded is never slowed down.
* The index is derived data: it is not part of the documents, it is not synchronized, and it can
  be built again from them at any time. The documents are never written by the search.
* It degrades: without the runtime, the words only; without a model for the ink, the handwriting
  read by the lectures only.
* Headless library, explicit API, ABI recorded; the interface is an extension of the library.

## Design

### Modules

```
drawview-search      store, ml          the index, the sources, the indexer, the search, the answers
drawview-search-ui   search, explorer   the results in the library, "Ask your notes", the settings
drawview-explorer    session            LibrarySearch: the port of the results (no dependency on search)
drawview-lecture-ui  lecture, search    LectureSearchSource
```

`drawview-search` is headless. The explorer declares the port of a search of the contents
(`LibrarySearch`) and shows its results; `drawview-search-ui` implements it, as
`drawview-export-ui` and `drawview-lecture-ui` extend the library (Indirection, Low Coupling).

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The index of a device: chunks, words (FTS5), vectors, what was indexed of every document, its format | `SearchIndex` (search) | Pure Fabrication |
| The searchable pieces of a document, each with its place | `SearchSource`, `SearchChunk`, `SearchAnchor` (search) | Polymorphism, Protected Variations |
| Names, folders, texts and the elements of other developers (`ElementType.searchText`) | `DocumentSearchSource` (search) | Information Expert |
| The text of the PDF pages, through the PDF port of the canvas | `PdfSearchSource` (search-ui) | Indirection |
| The lectures: the transcript a minute at a time, the review, the suggestions, the handwriting they read | `LectureSearchSource` (lecture-ui) | Information Expert |
| A block of ink drawn as an image, of any stored page | `InkRenderer` (search), implemented in search-ui with the drawing of the thumbnails | Indirection |
| The handwriting of every notebook read by Gemma 4 (formulas as LaTeX), a block once | `HandwritingRecognizer` (search) | Pure Fabrication |
| Index what changed, when the device allows it | `SearchIndexer`, `IndexingConditions` (search) | Controller, Protected Variations |
| The text of a chunk and of a query as the embedding model wants them | `EmbeddingPrompts` (search) | Information Expert |
| A search: words and meaning, merged, grouped, with their snippets | `SearchEngine` (search) | Controller, Information Expert |
| An answer from the passages found, with its citations | `NotesAnswerer` (search) | Pure Fabrication |
| The results in the library, the answer pane, the settings and the progress of the index | `ContentSearch` (search-ui) on `LibrarySearch` (explorer) | Controller |

### The index

* A database of its own, `search/index.db` in the data directory (androidx.sqlite with
  `BundledSQLiteDriver`, as the store): never the database of the documents, so the schema of
  the documents and its migrations do not change.
* Its tables:
  * `chunks`: document, source, place, text, hash;
  * `chunks_fts`: FTS5, tokenizer `unicode61 remove_diacritics 2`;
  * `vectors`: 512 dimensions in int8 with their scale;
  * `handwriting`: the blocks read, by key of block;
  * `documents`: the version indexed of each;
  * `meta`: the format, the embedding model, its dimensions, the version of the prompts.
* When `meta` does not match the code (a new format, another embedding model), a new index is
  built beside the old one and replaces it when complete. This is the only data the search ever
  deletes; it is built from the documents and the lectures.
* Restricted to the user (as the settings of the assistant); never synchronized.

### The sources and the chunks

* **Names**: a chunk per document, with the path of its folders.
* **Texts**: a chunk per text, cut at the headings and the paragraphs beyond about 400 tokens; the
  marks of Markdown removed, the LaTeX kept. The built-in text and the elements of other developers
  say their text in the same way, `ElementType.searchText(data): String?` (rule 6; `null` by
  default).
* **PDF pages**: a chunk per page, cut at the paragraphs; the text through a `pageText` of the PDF
  port of `drawview-canvas`, implemented by `drawview-pdf` (absent: no chunk).
* **Lectures**: the transcript in windows of about a minute on the segments, with their start;
  the review by section; the suggestions; the handwriting of `handwriting.json`.
* **Handwriting**:
  * `InkBlock` and `groupStrokes` move to `drawview-model`, used by the lectures and by the search.
  * A block already read by a lecture is not read again.
  * The others are drawn by `InkRenderer` and read by Gemma 4: the prompt asks the formulas in
    LaTeX between `$`.
  * The key of a block changes when a stroke is added or erased, so only that block is read again.
* **Places** (`SearchAnchor`): `Document(documentId)`, `Page(documentId, pageId, bounds)`,
  `Lecture(documentId, lectureId, startMs)`.

### Indexing

* `SearchIndexer` follows `DocumentCatalog.documents` and the changes of the sources.
* A document whose version changed is chunked again; only the chunks with a new hash are embedded
  again; the chunks of a deleted document are removed.
* An edit waits 30 seconds of quiet.
* `IndexingConditions`, given by the application, says when to work:
  * on a computer at once;
  * on a phone or a tablet, when it is charging and idle;
  * never during a recording.
* Reading the handwriting is the costly part: it can be switched off in the settings, which say
  the pages left.

### A search

1. The words (FTS5, BM25, the last word as a prefix while it is typed): 50 chunks.
2. The meaning: the query embedded (`task: search result`), compared with every vector in memory
   (brute force: 50,000 chunks are 25 MB): 50 chunks.
3. Merged by Reciprocal Rank Fusion (k = 60), grouped by place and document, with a snippet.

Without the embedding model, step 1 only.

### Ask your notes

* The question is embedded as a question (`task: question answering`), and 8 to 12 passages are
  found as in a search.
* Gemma 4 answers on the device, in the language of the question, citing the passages by number;
  each citation opens its place.
* Gemini answers only when the student chooses it, told that the passages are sent to Google: it
  is given by `drawview-lecture`, which has the key.

### Sync

Nothing of the search travels: every device indexes the documents and the lectures it has, and
indexes again what arrives. `PROTOCOL_VERSION` does not change.

## Phases

0. **Measure** (with phase 2 of [docs/local-models.md](local-models.md)), on a copy of some
   notebooks in a scratch directory:
   * 20 to 30 searches of the student, in Italian, each with the page it should find;
   * the first five results and the rank of the right one, for the words, for the vectors (512
     and 256 dimensions) and for both merged;
   * the two prompts of EmbeddingGemma 2;
   * the handwriting read by E2B and E4B;
   * the time to index the copy on the computer and on the tablet.

   The results go here; the dimensions and the defaults follow.
1. **The words**:
   * `drawview-search`: the index, FTS5 checked on Android, the JVM and iOS, `DocumentSearchSource`
     with `ElementType.searchText`, the incremental `SearchIndexer`, `SearchEngine` on the words;
   * `drawview-search-ui` and `LibrarySearch` of the explorer: the search of the library finds
     the texts.

   No model needed. Both modules as every new library: `drawview.kmp.library` and
   `drawview.compose.library`, `settings.gradle.kts`, the Dokka aggregate, `Module.md`, the `api/`
   dump.
2. **The PDFs and the lectures**: `pageText` of the PDF port, `PdfSearchSource`,
   `LectureSearchSource`.
3. **The meaning**: EmbeddingGemma 2 through `Embedder` (`drawview-ml`), the vectors, the merge,
   the model downloaded from the settings of the search. After phases 1 and 3 of
   [docs/local-models.md](local-models.md).
4. **The handwriting of every notebook**: `InkBlock` in `drawview-model`, `InkRenderer`,
   `HandwritingRecognizer` on Gemma 4, `IndexingConditions`, the progress. After phase 4 of
   [docs/local-models.md](local-models.md).
5. **The results**:
   * the snippets and the kind of each result;
   * the editor opened at the page and its area (a place given to `drawview-session` and to the
     explorer when a document is opened), the lecture at its minute;
   * the screenshots.
6. **Ask your notes**: `NotesAnswerer`, the pane with the citations, Gemini on request.
7. **Documentation**: README, `docs/modularization.md`, CLAUDE.md, the `Module.md` of the new
   modules; the models on iOS when phase 7 of [docs/local-models.md](local-models.md) is done.

## Progress

| Phase | State |
|---|---|
| 0. Measure | done on the computer (the tablet with phase 3 of docs/local-models.md) |
| 1. The words | done |
| 2. The PDFs and the lectures | done |
| 3. The meaning | done |
| 4. The handwriting of every notebook | left for later (to be replaced by another implementation) |
| 5. The results | done |
| 6. Ask your notes | done |
| 7. Documentation | not started |

### Phase 0 results (2026-10-18)

On the Mac of the student (M5, 16 GB), by `SearchBench` of the bench of the assistant
(`drawview-lecture`, see phase 2 of [docs/local-models.md](local-models.md)):

* **The corpus**: the 427 pages of the 11 notebooks of the lectures of the workspace of
  lecture-eval (a read-only copy): the text of the slides and the notes typed on them, a chunk a
  page. Most slides are in English, the notes and the searches in Italian.
* **The searches**: 30, in Italian, written for the bench by the assistant reading the pages (the
  student asked for it), each with the page it should find: 14 with words of their page, 16
  paraphrased. Written knowing the pages, they favour no method on purpose, but they are not the
  searches of the student.
* **Found** (the right page in the first five; within one page, for a slide continued on the
  next; the reciprocal rank):

  | Search by | First 5 | Within one page | Reciprocal rank | With the words | Paraphrased |
  |---|---|---|---|---|---|
  | words (BM25, as FTS5) | 63 % | 80 % | 0.45 | 92 % | 37 % |
  | vectors, 768 dimensions | 96 % | 100 % | 0.76 | 92 % | 100 % |
  | vectors, 512 dimensions | 96 % | 100 % | 0.76 | 92 % | 100 % |
  | vectors, 256 dimensions | 93 % | 100 % | 0.75 | 85 % | 100 % |
  | words + vectors 512, RRF (k = 60) | 83 % | 93 % | 0.61 | 100 % | 68 % |
  | words × 0.5 + vectors 512, RRF | 83 % | 93 % | 0.64 | 100 % | 68 % |

  The prompts of a query of the model card (`task: search result | query:`) and of the page of
  LiteRT-LM (`task: search query | text:`) find the same (within 0.01 of reciprocal rank).
* **The merge as designed loses**, with the words weighing half too: the words, wrong on most
  paraphrases, push the right page out of the first five. The vectors miss one search the words find ("deadline monotonic è migliore
  di rate monotonic": two names of algorithms the slides of that course repeat on many pages); the
  words miss eleven the vectors find.
* **Time**: the vectors of the 427 pages in 16.7 s on the GPU of the Mac (39 ms a page, the load
  of the model included). The tablet in phase 3 of [docs/local-models.md](local-models.md).
* **The handwriting**: none in the notebooks of the student; Gemma 4 on handwritten formulas is
  measured in phase 2 of [docs/local-models.md](local-models.md).

## Checks

* `./gradlew jvmTest checkKotlinAbi` and the builds of CLAUDE.md at every phase;
  `./gradlew updateKotlinAbi` for the new modules (phase 1), `drawview-model` and
  `drawview-explorer` (phase 1), `drawview-canvas` (phase 2), `drawview-lecture` (phase 4),
  `drawview-session` (phase 5), said in the commits; `./consumer-checks/check.sh` from phase 1.
* `verifyRoborazziJvm` of explorer and of search-ui from phase 1.
* Tests:
  * the index on an in-memory database: chunks, words, vectors, the version of each document,
    a new index when the format changes, the documents never written;
  * every source on the documents and the lectures of the tests;
  * the search on a small corpus with known answers.
* The desktop application on a scratch directory only:
  `JAVA_TOOL_OPTIONS="-Ddrawview.dataDir=<scratch dir>" ./gradlew :pencilnotes-desktop:run`.

## Decisions

* EmbeddingGemma 2 for the embeddings, Gemma 4 for the handwriting and the answers, on LiteRT-LM
  (the student, 2026-10-17).
* The index is a database of its own, built again when its format or its embedding model changes:
  the only data it deletes is its own, never the documents, the preferences or the lectures.
* The index stays on its device; nothing travels.
* 512 dimensions in int8 (as good as 768 in phase 0), compared by brute force; the prompt of a
  query of the model card (revertible).
* The vectors rank the results; the words do not take part in Reciprocal Rank Fusion as designed,
  which lost the paraphrases in phase 0. They find what the vectors miss (exact terms, names): how
  they join the vectors is decided by phase 3, on the searches of phase 0 (revertible).
* `HandwritingRecognizer` (phase 4) is left for later, to be replaced by another implementation;
  the handwriting read by the lectures (`handwriting.json`) is indexed by `LectureSearchSource`
  (the student, 2026-10-27).
* "Ask your notes" answers on the device; Gemini only when chosen.
* `drawview-search-ui` extends the library through `LibrarySearch`; `LectureSearchSource` lives in
  `drawview-lecture-ui`, so that `drawview-lecture` does not depend on the index (revertible).

## Left for later

* The images and the drawings of the notes found by their content (the vision encoder of
  EmbeddingGemma 2), the audio of the lectures by the audio encoder.
* A reranker of the first results.
* The handwriting read shared with the devices of the notebook (a change of what travels).
* The search inside the open document, with the words highlighted on the page.
* One reading of the handwriting for the lectures and the search (the assistant reading the
  index).
* The writing suggestions from the notes (a hook of `EditorExtension`, see CLAUDE.md).

## Risks

* Reading the handwriting of a whole library on a tablet takes hours (a block a second or more
  with E2B), and every device reads on its own.
* Gemma 4 may write text that is not on the page: a result shows its page, so that the student
  sees it.
* FTS5 in the bundled SQLite of each platform; no stemming for Italian (the vectors find the other
  forms of a word).
* The index grows with the library (about 50 MB for 50,000 chunks).
