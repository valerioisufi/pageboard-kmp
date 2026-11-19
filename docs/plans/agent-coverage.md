# What the application does and the models cannot yet

A review of the code listed what PencilNotes can do and the 16 tools of its MCP server
(`DocumentTools`) cannot: twenty-eight points in seven areas. This plan checks each one against the
code, decides whether a model gets it, and orders what it gets in phases. It continues
`docs/plans/agent-tools.md`, `docs/plans/agent-full-access.md` and `docs/plans/agent-ergonomics.md`,
and starts from the code as it is (`main` at ed1a9b2a, 2026-11-19).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

Each point of the review, what the code does, and the verdict. "Yes" points get a phase, "No" points
get a decision, "Later" points are in [Left for later](#left-for-later).

### Elements of a page

| # | Point | What the code does | Verdict |
|---|---|---|---|
| 1 | Shapes cannot be created or restyled | `read_page` gives `ShapeEntry` (`ToolEntries.kt`); the generic items move, scale, reorder and remove them. `ShapeElement` and `ShapeElementType` are in `drawview-model`, so a shape is an `AddElements` of a custom element and its style an `UpdateElementContent`. `ShapeEditing` and `ShapeConnectors` are in `drawview-canvas` (handles and anchors of a pointer) | True. **Yes**: `add_shape`, `update_shape` (kind, style, heads, fill, dash, the ends of a line). Connectors: **Later** |
| 2 | Highlights, underlines and strikeouts of a PDF are missing | `DrawTextMarkup` (`TextMarkup.kt`) is an element of the markup layer; `read_page` does not list them; `RestyleMarkups` is in `DocumentChange`; `ProposalLimits` refuses to place a mark, and `remove_elements` takes one | True. **Yes**: marks read, added on a passage of the PDF text, restyled, removed |
| 3 | No rotation | `BoxPlacement` and `TextPlacement` carry `rotation`; `ElementItems` keeps the rotation it reads. Decision 12 of `agent-full-access.md` left it out | True. **Yes**: decision 12 is revised |
| 4 | No move to another page, no copy between documents | `PlaceElements.toPageId` exists, `ElementItems` always gives `toPageId = pageId`. `CopiedContent` and `CopiedPages` are in `drawview-model` (`docs/plans/cross-document-paste.md`, done) | True. **Yes**: `to_page`, `copy_elements`, `copy_pages` |
| 5 | No import of a PDF | `MediaManager.importPdf` is in the canvas and needs the `pdfAdapter` of a controller for the sizes of the pages | True. **Yes**, through a port the application gives (`AgentPdfs`), as `AgentImages` |
| 6a | `update_text` cannot go back to `autoWidth`, has no rotation; `insert_text` has no alignment | `ElementItems` sets `autoWidth = false` when a width is given and never `true`; `InsertText` has no `alignment` | True. **Yes** |
| 6b | Chained boxes cannot be linked by hand | `next` is made by the flow of `ProposalPlanner` on overflow | True. **No** (decision 6) |
| 7 | A stroke restyled gets a new id; no edit of its points, no erasing a part of it | `RestyleStroke` is a remove and an add (decision 7 of `agent-full-access.md`) | True. **No** (decision 7) |
| 8 | An image cannot be replaced, its file cannot be read | `render_page` draws the page; the resource is not given | True. Replace: **No** (remove then `add_image`). Read: **Yes**, `read_image` |
| 9 | The payload of a third-party custom element is neither read nor changed | Only its type, box and `base` | True. **Later**: a third-party type is opaque to the agent |

### Pages and the document

| # | Point | What the code does | Verdict |
|---|---|---|---|
| 10 | The removed pages cannot be listed or restored | `remove_page` makes a `RemovePage`; `PageManager.restorePages` submits `AddPage.of(removed page)`, a change of today | True. **Yes**: `list_removed_pages`, `restore_pages` |
| 11 | The defaults of a document are neither read nor changed | `SetDocumentDefaults` exists; no item, nothing in `read_document` | True. **Yes** |
| 12 | The background is a word only; metadata missing | `read_page` gives `PageItems.kindOf(background)`; `DocumentSummary` has the dates, `isShared`, `hasPdf` and `list_documents` does not give them | True. **Yes** |
| 13 | `create_document` has no background | `LibraryItem.CreateDocument` has a title, a folder and a size | True. **Yes** |

### Library, export and archives

| # | Point | What the code does | Verdict |
|---|---|---|---|
| 14 | No export | `DocumentExport` (`drawview-export`): PDF, PNG, Markdown, ZIP, `.drawview` | True. **Yes**, through a port (`AgentExports`): `drawview-agent` depends on `drawview-store` only |
| 15 | No import of a `.drawview` archive | `ArchiveImporter` (`Archive.kt`) | True. **Yes**, a library item |
| 16 | No duplicate of a document | No item | True. **Yes**, a library item made of `CopiedPages` |
| 17 | No delete for good, no purge of the trash | Decision 2 of `agent-full-access.md` | True. **No**, never |
| 18 | The editor cannot be opened on a document or a page | Only `openForReview` | True. **Yes**: `show_page`, a request the application may honor |
| 19 | No undo of the user's changes, no redo | `undo` reaches the batches of `AgentActivity` only | True. **No** (decision 9) |

### Lectures and handwriting

| # | Point | What the code does | Verdict |
|---|---|---|---|
| 20 | `read_page` never gives the handwriting | `AgentEngines.handwriting` is not given in `PencilNotesAgents` (decision 13 of `agent-tools.md`). `HandwritingReader` keeps what it read in `handwriting.json` of the document | True. **Yes**: what is already transcribed is read from that file, no model is run |
| 21 | Nothing of the lectures | `LectureLibrary`, `Lecture`, `LectureAssistant` in `drawview-lecture`, which keeps the Compose runtime | True. Reading (transcript, outline, review, suggestions, course): **Yes**, through a port. Recording, importing audio, transcribing, reviewing, the course changed: **No** (decision 10) |

### Search and answers

| # | Point | What the code does | Verdict |
|---|---|---|---|
| 22 | "Ask the notes" not exposed | `NotesAnswerer` (`drawview-search`) | True. **No** (decision 11) |
| 23 | `search` has no filter; lecture hits lose their anchor | `PencilNotesAgents.search` keeps `SearchAnchor.Page` only; `SearchAnchor.Lecture` has `lectureId` and `startMs` | True. **Yes**: `documents`, `folder`, and the lecture with its time |
| 24 | Nothing of the index or of the local models | — | True. **No**: not something a model acts on |

### Sharing and sync

| # | Point | What the code does | Verdict |
|---|---|---|---|
| 25 | Nothing of the sharing | `DocumentSharing`, `SpaceSharing`; only `read_only` in `read_document` | True. Reading whether it is shared, who and with which role: **Yes**. Sharing, inviting, roles, the sync: **No** (decision 12) |

### Platform and protocol

| # | Point | What the code does | Verdict |
|---|---|---|---|
| 26 | Desktop only; stage 2 proposed | `AgentHost` and `AgentCli` in `pencilnotes/desktopApp` | True, by decision 14 of `agent-tools.md`. Stays in that plan (phases 5–6) |
| 27 | No headless host | Decision 2 of `agent-tools.md` | True. **Later**, as there |
| 28 | MCP tools only; settings and grants unreachable | `AgentEndpoint`: `initialize`, `server/discover`, `ping`, `tools/*` | True. Resources, prompts, sampling: **No** (decision 13). Settings and grants: **No**, never (decision 14) |

## Requirements

1. **What a model gets is the editor's, by meaning.** Every new item becomes changes `DocumentChange`
   has today: `AddElements`, `PlaceElements`, `UpdateElementContent`, `RestyleMarkups`, `AddPage`,
   `SetDocumentDefaults`, `AddResource`. No new change, so **no protocol change** and no Room change:
   `PROTOCOL_VERSION` stays 1 and `ProtocolFormatTest` is not re-recorded. A phase needing either
   stops and asks.
2. **Never lose a note**, as in `agent-full-access.md`: every item checked against its `base`, stale
   rather than overwriting, what is removed hidden or in the trash, every batch undone by `undo` and
   Ctrl+Z.
3. **The access decides everything.** A tool reading two documents (`copy_elements`, `copy_pages`,
   `duplicate_document`) needs both allowed; one writing a file (`export`) or reading one (`import_pdf`,
   `import_archive`) goes through the application, bounded.
4. **It degrades.** Without the PDF engine no `import_pdf`; without the export no `export`; without
   the lectures no lecture tool; a tool whose port is missing is not in `tools/list`.
5. **Headless.** `drawview-agent` keeps `drawview-store` as its only module: the export, the
   lectures, the handwriting and the PDF come through ports of `AgentEngines`.
6. **Nothing stored changes.** The new fields of the answers are additions; no stored format is
   touched (the journal of `AgentJournal` gains item kinds, read by name: an old file still reads).

## Design

```
AgentEndpoint ─► DocumentTools ─► ReadingTools  ─┐ reads
                               ├► ProposalTools  ─┤ items → ProposalPlanner/Applier → DocumentChange
                               ├► LibraryTools   ─┤ items → LibraryApplier → DocumentCatalog
                               ├► LectureTools   ─┤ reads (new)
                               └► ExportTools    ─┘ files (new)
                                        │
                                  AgentEngines: pdfs, exports, lectures, handwriting, show (ports)
                                        │ given by PencilNotesAgents
                              drawview-pdf · drawview-export · drawview-lecture · the windows
```

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The items of shapes, marks, rotation, pages and copies, by meaning | `ProposalItem` (`drawview-agent`) | Polymorphism |
| A shape item turned into a `ShapeElement` and its box, a style into `UpdateElementContent` | `ShapeItems` (`drawview-agent`, internal) | Information Expert (`ShapeElement` knows its values), Pure Fabrication |
| A passage of the PDF text found and turned into a `DrawTextMarkup` with its rects | `MarkupItems` (`drawview-agent`, internal), on the PDF layout of `PageEngine` | Information Expert, Indirection |
| Elements and pages copied from another document | `CopyItems` on `CopiedContent` / `CopiedPages` (`drawview-model`) | Creator (the copy makes the new ids), Low Coupling |
| The lectures and the handwriting read | `LectureTools` (`drawview-agent`), `AgentLectures` port; `LectureReading` (`drawview-lecture`, public, read only) | High Cohesion, Indirection, Protected Variations (the format of the lectures stays theirs) |
| Export and import of files | `ExportTools` (`drawview-agent`), `AgentExports` and `AgentPdfs` ports; their adapters in `PencilNotesAgents` | Indirection, Low Coupling (no dependency on `drawview-export`) |
| A page shown in the editor | `AgentEngines.show` port | Indirection |
| The new library items and their undo | `LibraryItem`, `LibraryApplier` | Polymorphism, Pure Fabrication |

### The new items of a document

| Item | Becomes | Check |
|---|---|---|
| `add_shape(page, shape_kind, x, y, width, height, rotation?, color?, fill?, width_mm?, outline?, start_head?, end_head?)`; a line by `from`, `to` (`kind` names the item) | `AddElements` of a custom element `drawview.shape` | the values of `ShapeElement` (`MIN_WIDTH_MM`…), inside the page |
| `update_shape(shape, base, shape_kind?, color?, fill?, width_mm?, outline?, start_head?, end_head?, from?, to?)` | `UpdateElementContent`, plus a `BoxPlacement` when the ends move | `base`; a line stays a line (`withKind`) |
| `add_markup(page, quote, occurrence?, markup_kind, color?)` | `AddElements` of a `DrawTextMarkup` | the quote found in the PDF text of the page, once or at `occurrence`; at most a set length |
| `restyle_markups(page, markups: [id, base], markup_kind?, color?)` | `RestyleMarkups` | each `base` |
| `place_elements(…, rotation?, to_page?)` | `PlaceElements` with `rotation` added around the center of the group, and `toPageId` | each `base`, inside the target page; no PDF page or mark moved (as today) |
| `update_text(…, rotation?, auto_width?)`, `insert_text(…, alignment?)` | `TextPlacement`, `UpdateTexts` | as today |
| `copy_elements(from_document, from_page, [id, base], page, dx?, dy?)` | `ContentPaste` of `CopiedContent` | both documents allowed, each `base` |
| `copy_pages(from_document, [page], after?)` | the paste of `CopiedPages` | both documents allowed |
| `import_pdf(path, after?)` | `AddResource` + `AddPage` per PDF page with its `DrawPdf` | a file read by the application (`AgentPdfs`), a PDF, at most a set size and pages; kept when proposed, like `add_image` |
| `restore_pages([page])` | `AddPage.of` each removed page, in one change, as `PageManager.restorePages` | the page is in `removedPages`, not expired |
| `set_document_defaults(width_mm?, height_mm?, background?, spacing_mm?, background_color?)` | `SetDocumentDefaults` | the values of `set_page_format` |

### The new reading

* `read_page`: the marks (id, kind, color, text, `base`), the background with `spacing_mm` and
  `background_color`, the handwriting already transcribed.
* `read_document`: the defaults, the dates, `is_shared`, `has_pdf`, the members and their roles when
  shared. `list_documents`: the dates, `is_shared`, `has_pdf`.
* `list_removed_pages(document)`: id, former index, when removed, what is on it in numbers.
* `read_image(image)`: the bytes of the image resource, bounded as `render_page`.
* `search(query, documents?, folder?)`: a lecture hit with `lecture` and `start_ms`.
* `list_lectures(document)`, `read_lecture(lecture, parts?)`: the transcript (by pages of text, at
  most a set length per call), the outline, the final review, the suggestions, the course.

### The library and the files

| Item or tool | On | Undo |
|---|---|---|
| `create_document(…, background?, spacing_mm?, background_color?)` | as today | as today |
| `duplicate_document(document, title?, folder?)` | a new document from `CopiedPages` of all its pages | to the trash |
| `import_archive(path, folder?)` | `ArchiveImporter` | the documents made to the trash |
| `export(document or folder, format, pages?)` (a tool, not an item) | `DocumentExport` through `AgentExports` | — (nothing in the notes changes) |
| `show_page(document, page?)` (a tool) | the application opens or brings its editor to the page | — |

`export` writes a new file in the folder of the exports of the agents (`<data dir>/exports/agent`),
never over a file, and answers its path and size; the folder is the application's to clean.

### Threats

| Threat | Answer |
|---|---|
| A model copying a document it should not read into one it may | Both documents must be allowed (requirement 3) |
| `import_pdf`, `import_archive` reading any file | Only a file that parses as such, at most a set size, as `add_image`: a file of the user, whose client could already read it |
| `export` writing over the user's files | A new file in a folder of the application only |
| A PDF with a huge text or thousands of pages imported | Bounds on size, pages and the quote of a mark (`ProposalLimits`) |
| The lectures holding instructions for the model | Marked as data, like the documents |
| The members of a shared document read | Their names and roles only, what the editor shows; no key, no address |

## Phases

One commit per phase on `feat/agent-coverage`, with the checks of `CLAUDE.md`; every phase records the
ABI of `drawview-agent` on purpose; the command line reaches the new tools with `call <tool>`.

| Phase | What | Points | Status |
|---|---|---|---|
| 1 | Reading: marks, background details, dates, `is_shared`, `has_pdf`, the defaults in `read_document`, `list_removed_pages`, `read_image`. Tests: each field, a document of before reading as before, the bound of `read_image` | 2, 10, 11, 12, 8 | done |
| 2 | The small items: `rotation` and `to_page` in `place_elements`, `rotation` and `auto_width` in `update_text`, `alignment` in `insert_text`, `set_document_defaults`, `restore_pages`, the background of `create_document`. Tests: each item, stale, one undo, converging on another device through the written operation | 3, 4, 6a, 10, 11, 13 | done |
| 3 | Shapes: `add_shape`, `update_shape`. Tests: every kind, a line by its ends, refused values, a style kept by `withKind`, undo, the review marks | 1 | done |
| 4 | Marks: `add_markup`, `restyle_markups`, their removal. Tests: a quote found once, at an occurrence, not found, a scanned page, the rects on a page of another format, undo | 2 | done |
| 5 | Copies and the library: `copy_elements`, `copy_pages`, `duplicate_document`, `import_pdf` (`AgentPdfs`), `import_archive`. Tests: both documents allowed or refused, images and PDF pages carried, the undo of each, the bounds of the files | 4, 5, 15, 16 | proposed |
| 6 | Files and the editor: `export` (`AgentExports`, every format), `show_page`. Tests: a new file never over another, a folder exported, a closed document, the desktop application on a scratch directory | 14, 18 | proposed |
| 7 | Handwriting and lectures: `LectureReading` in `drawview-lecture` (public, read only), the handwriting of `handwriting.json` in `read_page`, `list_lectures`, `read_lecture`, the lecture hits of `search` and its filters. Tests: a page transcribed and one not, a lecture read by parts, the bounds, no model started | 20, 21, 23 | proposed |
| 8 | Sharing read: the members and roles in `read_document`. Tests: a shared document and one not, nothing of the keys answered | 25 | proposed |

Each phase is useful without the next. Phases 1–4 touch only `drawview-agent`; 5–8 add ports wired in
`PencilNotesAgents` (desktop).

## Decisions

Taken alone, revertible:

1. **Rotation and another page in `place_elements`**: decision 12 of `agent-full-access.md` is revised;
   `above` and `below` in `reorder_elements` stay out.
2. **Shapes as items of their own**, not as a generic payload: a model writes `kind`, `color`, `from`,
   `to`, never the bytes of `ShapeElementType`.
3. **A mark is found by its quote**, not by character indices: the model reads text, not the indices
   of the text layer.
4. **Copies need both documents allowed**, the source read like any other.
5. **Ports for the PDF, the export, the lectures and the handwriting**: `drawview-agent` stays on
   `drawview-store` only (rule 1 of the libraries).
6. **No chaining of boxes by hand**: the flow of the planner makes `next`, and a chain made by a
   model could make a text unreachable.
7. **No editing of the points of a stroke, no erasing part of it, a restyle keeps a new id**: the
   handwriting of the user is not rewritten (decision 5 of `agent-tools.md`), and keeping the id is a
   protocol change.
8. **No replacing the file of an image**: remove it and add the new one, two items of one batch.
9. **No undo of the user's changes, no redo**: the user has Ctrl+Z; a model undoes only what a model
   did, and proposes again rather than redoing.
10. **The lectures are read, not run**: recording needs the microphone (a permission of the system),
    a transcription or a review runs models for minutes and may cost; they stay the user's, in the
    application.
11. **No "ask the notes"**: the client is a model already, with `search` and the pages; a local model
    answering it would be slower and weaker than what it does with them.
12. **No sharing, inviting, roles or sync**: an invitation gives a device the keys of a document
    (`docs/plans/untrusted-members.md`); a prompt injection must never reach that.
13. **Tools only in MCP**: `read_guide` takes the place of prompts and resources; sampling would make
    the application call the client's model.
14. **No setting and no grant through the tools**, ever: a model cannot widen its own access.
15. **Exports in a folder of the application**, never at a path chosen by the model.
16. **The handwriting is the transcription already kept**: reading it starts no model; a page not
    transcribed answers none.

## Left for later

* Connectors and anchors of the shapes (`ShapeConnectors` is in the canvas, tied to the pointer).
* The payload of third-party custom elements: a type would give the agent a codec of its own.
* A transcription of the handwriting asked by a model, with its cost told.
* Changing the course of a lecture.
* The headless host, Android and iOS (`docs/plans/agent-tools.md`, stage 2).
