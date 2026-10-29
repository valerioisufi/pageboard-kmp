# The size of the text placed

The size of the characters of what the application places in a notebook (the notes of the lecture
assistant, its review) chosen from the page it goes on, as a student would write it there: like
their typed notes, or in proportion to the slide, or to their handwriting; a margin note a step
smaller rather than pushed away from what it is about. This document starts from the code as it is
(main at 6b543da7, 2026-10-29), after `docs/auto-placement.md`: how the size is chosen today, then
the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Fixed sizes.** The notes and the review are laid out at 11 points (`NOTE_FONT_PT`,
   `REVIEW_FONT_PT` in `NoteInserter`), on every page: an A4 page, a slide of 254 × 143 mm, a page
   where the student types at 16 points.
2. **A pasted text** takes the size of a new text (`DrawViewController.textDefaults`, "the style of
   the text written last", 16 points by default, kept in the preferences).
3. **The planner never changes the size.** `PlacementBlock` has a preferred width, a narrowest
   width and the height of its text at a width (`heightAt`); the planner narrows the block (it gets
   taller) and otherwise proposes a new page (`docs/auto-placement.md`). A margin note (`InMargin`,
   60 mm) that does not fit beside its anchor goes below it.
4. **What tells the sizes on a page.**
   * `DrawText.fontSize`, in typographic points (`TextSizes.PT_TO_MM`), for every typed text; the
     sizes offered are `TextSizes.PRESETS` (6 to 288).
   * The characters of the PDF page with their boxes (`PdfTextLayout`, in points of the page as
     displayed, placed on the page by `fitPdfPage`): the height of a box is about the size of its
     font.
   * The strokes of the handwriting with their bounds (`DrawStroke.bounds`), in blocks
     (`inkBlocksOf`, 6 mm apart).
5. **Nothing stored changes** with the size: it is a property of every text already.

## Requirements

1. A note of the assistant is written **at the size the student would use on that page**, in this
   order:
   1. the size of the texts the student typed on the page;
   2. on a page with a slide, a little smaller than the body of the slide (it annotates it);
   3. on a page of handwriting, the size of the handwriting;
   4. the size of a new text (`textDefaults`).
2. A size is always **readable and in the presets**: rounded to the nearest preset, between 8 and
   24 points for a note.
3. **A margin note is made one preset smaller** (never under 8 points) when that lets it stay
   beside what it is about; otherwise it keeps its size and goes below.
4. **The review** is written at the size of the page it flows from (requirement 1), and the new
   pages after it at the size of the texts of the notebook (the most frequent among the typed
   texts, else `textDefaults`).
5. **A pasted text** keeps the size of a new text: it is the student's own text.
6. The ghost shows the note at the size it will have; nothing stored or sent changes.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The size of the characters of a page: its typed texts (the size of most of their characters), the body of its PDF page (the most frequent height of its character boxes, in page points), its handwriting (the median height of its strokes of writing) | `PageTextSizes.of(page, pdfText)` (`drawview-model`, package `placement`) | Information Expert (the page knows its content) |
| The size a note takes on a page from those sizes and the default, rounded to a preset within its bounds | `TextSizePolicy` (`drawview-model`) with `forNote(sizes, default)`, `smaller(size)` | Pure Fabrication, Protected Variations (the rules of the sizes in one place) |
| The size of the texts of a document (the most frequent typed size) | `PageTextSizes.ofDocument(document)` | Information Expert |
| Trying a margin note one preset smaller when its size leaves it away from its anchor | `NoteInserter` (`drawview-lecture-ui`): two blocks, the second kept only when its best place is beside the anchor | Controller |
| The size of the review on its page and on the new pages | `NoteInserter.startReviewPlacement`: one size per part (`flowItems`) | Creator |

### The sizes of a page

* **Typed texts.** The size covering the most characters (a heading in a large size does not
  count more than the paragraphs). Texts of the assistant count too: they were placed at the size
  of the page, and the next note matches them.
* **The slide.** The heights of the boxes of its characters, converted to the points of the page
  (`fitPdfPage`: a slide shown smaller than its PDF has smaller characters), rounded to half a point;
  the most frequent is the body. A note on a slide is 85 % of it: it annotates the slide without
  competing with it.
* **The handwriting.** The median height of the strokes of writing (the highlights left out) is
  about the height of a lowercase letter with its stem; a font of size *s* has letters of about
  0.7 *s* points: the size is that height divided by 0.7, in points.
* Each size is known only with enough material: 20 characters of typed text, 40 of the slide,
  10 strokes; otherwise that rule is skipped.

### Margin notes one step smaller

`NoteInserter` builds the block at the size of the page; for `InMargin` kinds, when its best place
is not beside the anchor (no vertical overlap), it builds the block again one preset smaller
(`TextSizes.smaller`, never under 8 points) and keeps it if its best place is beside the anchor.
One step only: a note much smaller than the page would be hard to read.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | `PageTextSizes` and `TextSizePolicy` in `drawview-model`: tests on pages with typed texts of several sizes, a slide shown at another scale, handwriting, too little of each | done |
| 2 | The notes at the size of their page, margin notes one step smaller; the review at the size of its page and of the notebook; the ghosts at their size. Tests of `NoteInserter`, screenshots | planned |

## Decisions

Taken alone, revertible:

1. **Typed texts come first**: the notes look like the student's notes; the slide and the
   handwriting only when the student typed nothing there.
2. **85 % of the slide**, **8 to 24 points**, **one step smaller** for the margin: values to be
   tuned on real notebooks.
3. **A pasted text keeps the size of a new text**: it is the student's text, not a note.
4. **The size is chosen once per note**, before its places are proposed: all its places show it
   at the same size (but a margin note made smaller, see above).

## Left for later

* A size chosen by the student for the notes of the assistant (a preference), if the rules above
  are not enough.
* The size of the headings of the review, which follows the size of its text (`RichTextPresenter`).
