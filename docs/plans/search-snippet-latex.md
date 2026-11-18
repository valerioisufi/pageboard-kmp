# Search snippets without the LaTeX source

The snippets of the library search show the formulas of a text as written: a text box
`**Squeeze:** $\cos x \le \frac{\sin x}{x} \le 1$` is shown as
`Squeeze: $\cos x \le \frac{\sin x}{x} \le 1$` (found while taking the store screenshots,
`docs/plans/store-listing.md`). The answer of "Ask your notes" above it renders its Markdown and
formulas; a snippet should read as math too. This document starts from the code as it is (main at
e38d376e, 2026-11-18).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The text indexed** (`drawview-search`, `cleanMarkdownForSearch`): the marks of Markdown are
   removed (so `**` is gone already), the formulas `$…$` and `$$…$$` are kept on purpose, so a
   search by `frac` or `sin` finds them and a model reading the passages reads LaTeX.
2. **The snippet** (`snippetOf`, called by `SearchEngine.toSearchHit`) is a window of at most 140
   characters of the chunk, on one line, around the first word of the query found in it.
   `SearchHit` keeps the whole chunk (`text`) and the snippet (`snippet`).
3. **Who reads `SearchHit.snippet`**: the library (`ContentSearch.toLibraryMatch` →
   `LibrarySearchMatch.snippet`, drawn by `SearchMatchesList` of `drawview-explorer` as one line
   of `Text`, ellipsized), and the agent tools (`ReadingTools`, `PencilNotesAgents`), for which the
   LaTeX is the right form.
4. **The renderer of the answer** (`MarkdownText`, `drawview-ui`) draws formulas as images through
   the formula engine of `LocalDrawViewEngines`, once that engine is ready; until then, or without
   one, it shows the notation styled. It has no `maxLines` nor ellipsis, and a rendered fraction is
   taller than a line of `bodySmall`.
5. **Highlighting**: the row of the library draws the snippet as plain text; what follows the
   match is the window of `snippetOf`, centered on the word found.

## Requirements

1. A snippet of a text (`SearchChunkKind.TEXT`) or of the notes of a lecture
   (`LECTURE_NOTES`) shows its formulas as readable math on one line: `cos x ≤ sin x/x ≤ 1`, no
   `$`, no `\frac`, no `\le`.
2. The window keeps following the match: the word of the query found stays in the snippet.
3. One line, ellipsized, at the cost of a string conversion: nothing waits for the formula engine,
   and the row keeps its height.
4. What is indexed, `SearchHit.snippet` and what the agent and the answer read do not change. The
   text of the PDFs, of the handwriting and of the transcripts is not touched (a `$` there is a
   price, not a formula).
5. No public API changes; nothing stored changes.

## Design

1. **`ReadableMath`** (Pure Fabrication, High Cohesion), internal to `drawview-search-ui`: turns
   the formulas of a text into plain Unicode math. The formulas are found by `RichTextParser`
   (Information Expert of the notation: the same `$` the page takes as a formula); each is
   converted by a small recursive reader of LaTeX:
   * symbols by their character (`\le` → `≤`, `\alpha` → `α`, `\to` → `→`, `\infty` → `∞`,
     `\mathbb{R}` → `ℝ`), functions by their name (`\sin` → `sin`);
   * `\frac{a}{b}` → `a/b`, a side of more than one term in parentheses (`(x+1)/2`);
     `\sqrt{x}` → `√x`, `√(x+1)`;
   * `^` and `_` by superscript and subscript characters when all of them have one (`x²`,
     `aₙ`), else `^(…)` and `_(…)`;
   * text and font commands (`\text`, `\mathrm`, `\mathbf`, `\operatorname`) by their content;
     sizes and spacing (`\left`, `\right`, `\big`, `\,`, `\quad`) dropped or a space; an unknown
     command by its name;
   * the nesting is bounded (a deeper group is kept as written): the text is the user's.
2. **`ContentSearch`** (Controller) makes the snippet shown: for a hit of a text or of lecture
   notes, `snippetOf(ReadableMath.of(hit.text), query)`; it does so in `searchNow`, on the
   dispatcher of the search, so the composition only reads strings. The other hits keep their
   snippet. Converting the whole chunk before cutting the window, never a cut snippet, means a
   formula is never cut in half before it is read.
3. **Not chosen: rendering the snippet with `MarkdownText`** (revertible). It would need the
   formula engine in the library's row, a `maxLines` and an ellipsis that `MarkdownText` does not
   have (drawn formula images are placed after layout and would draw past the ellipsis), a row
   taller than the others for a fraction, and a snippet cut by its window could split a formula
   into raw notation again. `LibrarySearchMatch.snippet` would also have to become Markdown, a
   change of the port of `drawview-explorer`.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | `ReadableMath`, used by `ContentSearch` for the snippets of texts and lecture notes; tests of the conversion and of the snippet of a text with a formula; the screenshot of the results with a formula | done |

## Decisions

1. Plain Unicode math rather than rendered formulas (Design 3), revertible.
2. Only `TEXT` and `LECTURE_NOTES` hits: the others have no notation of the notes.
3. The conversion lives in `drawview-search-ui`, internal: what the library shows. It could move
   to `drawview-search` as a public function if another reader wants it.

## Left for later

1. Highlighting the words of the query in the snippet (the row of `drawview-explorer` draws a
   plain `String` today).
2. Formulas of the handwriting (recognized as LaTeX) in their snippets, once they have a notation
   of their own in the chunk.
