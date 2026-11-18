# Citations of "Ask your notes"

"Ask your notes" (Gemma 4 E2B on an Android emulator, while taking the store screenshots, see
`store-listing.md`) answered with a text citing `[1]` and `[2, 3]`, but under "Cited passages"
only `[1] Calculus I - Limits · p. 2` was listed. Every citation the answer uses must have its
chip, and a chip must exist only for a citation the answer uses. This document starts from the
code as it is (main at e38d376e, 2026-11-18).

The responsibilities are assigned following the GRASP principles.

## Starting point

1. **`NotesAnswerer`** (`drawview-search`) numbers the retrieved passages from 1 in the prompt
   (`buildPrompt`: `[1] Title (p. 2)`, then the text), and the system prompt asks the model to
   cite them "such as [1] or [2]".
2. **`extractCitations`** reads the citations back with `\[(\d+)]`: only a bracket holding one
   number. `[2, 3]`, `[2,3]` and `[1-3]` are not matched, so passages 2 and 3 got no chip. `[1][2]`
   already works. Numbers beyond the passages are dropped, and the order is that of first
   citation.
3. **The formulas are not skipped**: an interval in LaTeX such as `$[0, 1]$` would be read as a
   citation of passage 1 once lists are accepted.
4. **`AskNotesCard`** (`drawview-search-ui`) shows `answer.citations` as chips, but when the list
   is empty it shows a chip for *every* retrieved passage (`ifEmpty`), so an answer that cites
   nothing (for instance "the answer is not in the notes") lists passages it never used.

## Requirements

- The citation forms models write are read: `[1]`, `[1, 2]`, `[1,2]`, `[1][2]`, `[1-3]` (and an
  en dash, `[1–3]`, and `;` as a separator).
- Numbers beyond the passages given are ignored; a range is cut to them, so `[2-1000000]` costs
  no more than the passages.
- The chips keep the order of first citation, each passage once.
- Brackets inside math (`$…$`, `$$…$$`) and inline code are not citations.
- A chip only for a passage the answer cites: no chips when it cites none.

## Design

- **Information Expert, Pure Fabrication**: the parsing stays in `NotesAnswerer.extractCitations`
  (internal, it knows the numbering it gave in `buildPrompt`). One regex matches a bracket whose
  content is a list of numbers or ranges separated by `,` or `;`; the content is then split and
  each item expanded. Math and code spans are blanked out first (same length, so nothing else
  moves).
- **Low Coupling**: `AskNotesCard` shows `answer.citations` as they are, with no fallback of its
  own; the line "Answered by …" stays, and the title "Cited passages" with the chips only when
  there are citations.
- The public API does not change (`CitedPassage`, `NotesAnswer` are the same).

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | This plan | done |
| 2 | Parsing of the citation forms, math skipped, unit tests in `drawview-search` | done |
| 3 | `AskNotesCard`: chips only for the cited passages | done |

## Decisions

- A reversed range (`[3-1]`) is not a citation form and is ignored (revertible).
- A list holding a number beyond the passages keeps the others (`[2, 9]` with 3 passages cites 2).
- The prompt is unchanged: models will write lists whatever the example, so the parser follows
  them rather than the prompt forbidding them.

## Left for later

- Other forms some models use, such as `[Passage 2]` or `【1】`: not seen yet.
- Making the citations in the text clickable, next to the chips.
