# Find in the document

A bar in the editor finding a text in the note open, its places marked on the pages, the current
one shown. `docs/plans/search.md` and `docs/plans/library-search.md` search the notes from the library
(`ContentSearch`, FTS5 and the meaning); inside a note there is no search. This document starts from
the code as it is (main at 9d7e1184, 2026-11-14).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The texts** are Markdown with formulas (`DrawText.text`); what the page shows is laid out by
   the adapter of the texts (`TextTileAdapter`, `ComposeTextTileAdapter` in `drawview-ui`), which
   also gives its words with their place in millimeters (`layout`, `TextBlockLayout`): the export
   writes them in the PDF.
2. **The PDF pages** give their characters with their boxes (`PdfTextLayout`, through
   `TextMarkupManager.layoutOf`), which the marks of their text use.
3. **Showing a place**: `PageManager.jumpToPage(pageId, number, bounds)` centers the camera on a
   rectangle of a page, as the results of the library do.
4. **The live layer** (`DrawCanvas`) draws over the tiles what changes often: the selection, the
   guides, the text of the PDFs being selected (`TextMarkupOverlay`).

## Requirements

1. ⌘F (Ctrl+F) and Edit ▸ Find in Document open a bar at the top right of the editor, its field
   focused, what was looked for last selected.
2. What is looked for is found in the words the page shows (the notation of Markdown hidden, so
   `**teorema**` is found by `teorema`, not by `**`) and in the text of the PDF pages; without telling
   capitals, the accents of the Latin letters and the typographic quotes and dashes apart; over the
   lines of a text.
3. Every place is marked on the page like a highlighter, the current one stronger and outlined; the
   bar says "3 of 12" or "No results"; Enter and ↓ go to the next, Shift+Enter and ↑ to the
   previous, going round; Escape and Close close the bar and take the marks away.
4. The first place shown is the first one at or after the page on screen; a change of the document
   while the bar is open finds the places again without moving the view.
5. Nothing stored or sent changes. The seven languages; every platform (the bar works with a
   keyboard and with touch).

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| What is looked for, the places, the current one, next and previous, finding them again after a change | `DocumentFind` (`drawview-canvas`, `controller.find`) | Controller, Information Expert |
| The words a text shows as one text, and where each character is | `ShownWords` (`drawview-canvas`), from `TextTileAdapter.layout` | Pure Fabrication, Protected Variations (the adapter lays the text out, whatever it is) |
| The text of a PDF page | `PdfTextLayout` through `TextMarkupManager.layoutOf` | Information Expert |
| Comparing without capitals and accents | `DocumentFind.fold` | Pure Fabrication |
| Marking the places | `FindOverlay` (`drawview-canvas`), in the live layer | High Cohesion |
| The bar, the command ⌘F | `FindBar`, `EditorCommands.find` (`drawview-ui`) | Controller, Low Coupling |

* A place keeps the rectangles of its lines in millimeters of its page; the overlay maps them to
  the screen as it draws, so the marks follow the camera with nothing computed again.
* A character of a word is placed by its share of the word (the advances of the characters are not
  in the layout): exact at the edges of the words, close inside them.
* The pages are read one after the other on the thread of the controller, yielding between them;
  the first places are shown as soon as they are found.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `DocumentFind`, `ShownWords`, `FindOverlay`; `FindBar` and the command ⌘F; the strings in the seven languages; `DocumentFindTest`; ABI of `drawview-canvas` and `drawview-ui` recorded on purpose | Done |

### Phase 1 results

* Tests (`DocumentFindTest`, with an adapter laying out words of 2 mm a character): the order of the
  pages, capitals and accents, a place where its words are, a place over two lines, next and
  previous going round, a change of the document found again after it settles, closing.
* The PDF pages go through the same code with the layouts of PDFium; they are not in the tests (no
  PDF engine in the tests of `drawview-canvas`).
* The klib dumps of `drawview-canvas` and `drawview-ui` written by hand on Windows.

## Decisions

Taken alone on 2026-11-14 (the user asked to go on without asking), every one revertible:

1. **The words shown, not the source**: what the user reads is what is found; the formulas are not
   searched (their source is hidden on the page).
2. **No handwriting**: reading the ink is the costly part of `docs/plans/search.md`; the bar finds what is
   typed and the PDFs.
3. **Without capitals and accents, always**: no options in the bar (case, whole words).
4. **The bar at the top right**, as in the browsers, not over the selection bars at the top centre.
5. **No replace**: the texts are edited in their boxes.

## Left for later

* The places in the handwriting, from the index of `drawview-search`.
* The places inside the formulas (their source) and in the elements of other types.
* A list of the places with their context, beside the pages.
* Replace.
