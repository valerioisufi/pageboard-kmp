# The plans

Every feature, refactor or change of the interface of Pageboard and PencilNotes starts with a plan
here, written before the code and kept up to date while the work goes on (see
[CONTRIBUTING.md](../../CONTRIBUTING.md)). Each plan holds:

* the starting point: the code as it was when the work began;
* the requirements;
* the design, with the responsibilities assigned by the GRASP patterns;
* the phases, with a table of their status;
* the decisions, the ones agreed with the maintainer and the ones taken alone as revertible;
* what is left for later.

They are records, not the reference: a plan describes the code of its time, and later work may
have changed it. What holds today is the code, [docs/reference.md](../reference.md) and the
`Module.md` of each library. The status of a phase is the one written when the phase was done.
The libraries were named DrawView until November 2026 ([pageboard.md](pageboard.md)): the plans
written before keep the names of their time (`drawview-*`, `DrawViewController`, `DrawDocument`).

The plans were written with the help of an AI coding assistant, following these same rules, and
reviewed by the maintainer. "The maintainer" is the author of the project; "the user" is a person
who uses the applications or the libraries.

| Plan | Title |
|---|---|
| [adaptive-ui](adaptive-ui.md) | Adaptive interface |
| [agent-coverage](agent-coverage.md) | What the application does and the models cannot yet |
| [agent-ergonomics](agent-ergonomics.md) | The tools of external models, after a real session |
| [agent-full-access](agent-full-access.md) | Full access for external models |
| [agent-placement](agent-placement.md) | Where the text of a model goes |
| [agent-preview](agent-preview.md) | What a model proposes, shown on the page |
| [agent-tools](agent-tools.md) | External models on the documents |
| [android-release](android-release.md) | The release of PencilNotes on Google Play |
| [app-icon](app-icon.md) | Application icon on desktop platforms |
| [app-identity](app-identity.md) | The visual identity of PencilNotes |
| [architecture](architecture.md) | The architecture after the cleanup |
| [archive](archive.md) | The archive of the application: export and import |
| [ask-citations](ask-citations.md) | Citations of "Ask your notes" |
| [auto-placement](auto-placement.md) | Automatic placement |
| [build-speed](build-speed.md) | The speed of the build |
| [camera-bounds](camera-bounds.md) | Camera bounds |
| [checklists](checklists.md) | Checklists and the levels of the headings |
| [ci-fixes](ci-fixes.md) | The workflows after the rename |
| [ci-screenshots](ci-screenshots.md) | Screenshots that anyone can verify and record |
| [ci-time](ci-time.md) | The time of the GitHub workflows |
| [cleanup](cleanup.md) | Cleanup |
| [code-blocks](code-blocks.md) | Code blocks |
| [code-review-2026-11](code-review-2026-11.md) | Review of the codebase, November 2026 |
| [collaboration](collaboration.md) | Multi-user collaboration |
| [cross-document-paste](cross-document-paste.md) | Copy and paste between documents |
| [document-tools](document-tools.md) | The tools of the external models, by group |
| [document-window](document-window.md) | The secondary window of PencilNotes |
| [editor-ports](editor-ports.md) | The editor ports |
| [engine-instances](engine-instances.md) | The engines as instances |
| [export-metadata](export-metadata.md) | The dates of the notes exported |
| [export-ux](export-ux.md) | The experience of the export |
| [export](export.md) | Export of the documents |
| [find-in-document](find-in-document.md) | Find in the document |
| [handwriting](handwriting.md) | Handwriting recognition |
| [header-fit](header-fit.md) | Bars that fit their window |
| [html-paste](html-paste.md) | Pasting formatted text |
| [ink-antialiasing](ink-antialiasing.md) | Antialiased ink |
| [ink-latency](ink-latency.md) | Ink latency |
| [instances](instances.md) | The store and the sessions as instances |
| [internet-sync](internet-sync.md) | Synchronization over the internet |
| [ios-stability](ios-stability.md) | The crashes and the lag of the iPhone |
| [last-place](last-place.md) | The last place of a document |
| [lecture-assistant](lecture-assistant.md) | Lecture assistant: the transcription model chosen, the review on request |
| [lecture-backends](lecture-backends.md) | The lecture models behind one backend per provider |
| [lecture-cost](lecture-cost.md) | The cost of the lecture assistant |
| [lecture-ui-split](lecture-ui-split.md) | The lecture panel and the editor toolbar, by section |
| [library-search](library-search.md) | The search inside the library |
| [linear-parsing](linear-parsing.md) | Linear parsing of the notation |
| [list-numbering](list-numbering.md) | Numbered lists: numbers that follow, styles to choose, lists in lists |
| [local-models](local-models.md) | Local models: one runtime on every device |
| [local-notes](local-notes.md) | The live notes of a local model: measured on more lectures, filtered before the student sees them |
| [markdown-export](markdown-export.md) | Markdown export of a note, with the notes of its lectures |
| [modularization](modularization.md) | Modularization and publishing |
| [new-page-format](new-page-format.md) | The format of a new page |
| [open-source](open-source.md) | Opening the repository and the first release of the libraries |
| [page-actions](page-actions.md) | Duplicating pages, adding a page before another |
| [page-grid-updates](page-grid-updates.md) | The page grid following the document |
| [page-selection](page-selection.md) | Selecting several pages |
| [pageboard](pageboard.md) | The libraries named Pageboard |
| [pane-bottom-room](pane-bottom-room.md) | The room at the bottom of the navigation pane |
| [performance](performance.md) | Performance |
| [phone-toolbar](phone-toolbar.md) | The floating tools of a phone |
| [placement-text-size](placement-text-size.md) | The size of the text placed |
| [play-console](play-console.md) | PencilNotes on Google Play: the answers of the Play Console |
| [pointer-selection](pointer-selection.md) | Selecting with a pointer at the ends of a format |
| [quiescent-compaction](quiescent-compaction.md) | Compaction while connected |
| [search-snippet-latex](search-snippet-latex.md) | Search snippets without the LaTeX source |
| [search](search.md) | Search in the notes: words, meaning, and questions |
| [selection-formatting](selection-formatting.md) | Formatting a selection |
| [shape-adjust](shape-adjust.md) | Shapes: following the pen after the snap, lines joined to the boxes |
| [shape-insertion](shape-insertion.md) | Inserting shapes |
| [shapes](shapes.md) | Geometric shapes |
| [shared-folders](shared-folders.md) | Shared folders and library |
| [small-cleanups](small-cleanups.md) | The smaller ones: duplicates and translations |
| [small-screen-modals](small-screen-modals.md) | Dialogs and sheets on small screens |
| [store-explorer-split](store-explorer-split.md) | The key-value store and the library view model, by responsibility |
| [store-listing](store-listing.md) | The store listing of PencilNotes and the images of the repository |
| [sync-engine-split](sync-engine-split.md) | The sync engine split, the resources received on disk |
| [test-data](test-data.md) | Test data |
| [text-autosave](text-autosave.md) | Text saved while written |
| [text-blocks](text-blocks.md) | Quotes, heading levels, headings with their text, symbols |
| [text-box-fixes](text-box-fixes.md) | Text boxes: large boxes, pasting, flowing across pages |
| [text-box-hook](text-box-hook.md) | The text box hook |
| [text-boxes](text-boxes.md) | Text boxes: lists, tables, backgrounds, boxes across pages |
| [text-editing](text-editing.md) | Text editing |
| [text-editor-pages](text-editor-pages.md) | Moving the text being written to another page |
| [text-fidelity](text-fidelity.md) | Text fidelity: written as the page draws it |
| [text-heights](text-heights.md) | The box of a text, laid out |
| [text-import](text-import.md) | Importing text |
| [text-parity](text-parity.md) | Text parity |
| [tile-sharpness](tile-sharpness.md) | Tile sharpness |
| [tile-zoom-out](tile-zoom-out.md) | Tiles after a large zoom out |
| [title-bar](title-bar.md) | The title bar: renaming and the window controls |
| [touch-gestures](touch-gestures.md) | Touch and two-finger gestures |
| [transcription-queue](transcription-queue.md) | Transcriptions in a queue, and a window that stays smooth |
| [trash-actions](trash-actions.md) | The Trash: a preview, several items at once, ⌘Z |
| [trash](trash.md) | The trash |
| [ui-design](ui-design.md) | Interface design |
| [ui-fit](ui-fit.md) | Dialogs at their width and hovers in their shape |
| [ui-ux-review](ui-ux-review.md) | A review of the interface: what gets in the way |
| [untrusted-members](untrusted-members.md) | Members not trusted |
| [vector-formulas](vector-formulas.md) | The vectors of the formulas through the formula port |
| [window-integration](window-integration.md) | System window integration |
| [windows-paths](windows-paths.md) | Paths on Windows |
| [writing-suggestions](writing-suggestions.md) | Writing suggestions in PencilNotes |
| [z-order](z-order.md) | Z-order |
