# The box of a text, laid out

The box of a text is what its lines take, as this device lays them out: the document keeps where
a text may go, never the size it took. This document starts from the behavior reported on
2026-10-25 (a note of the lecture assistant, a bold title and two bulleted items over a slide,
shows all its lines at one zoom and loses its last lines at another, cut in the middle of a line)
and from the code as it is (main at 6a445240, 2026-10-25).

A first plan corrected the stored heights when a document opens. The user asked instead for a
document that does not depend on the layout of the version that wrote it: the application is not
published yet, the documents of the user are kept, and the code is not to keep layers for the
versions of its early development. This plan replaces it.

The responsibilities are assigned following the GRASP principles (Information Expert, Pure
Fabrication, Low Coupling, Polymorphism, Protected Variations).

## Starting point

1. **A text stores its box.** `DrawText` has `x`, `y`, `width`, `height` and `rotation` in page
   millimeters, a `RotatableItem` like the images. `height` is what its lines took when it was
   last measured; the user never sets it. It is written by the editor (`TextEditorManager`,
   measured, at least 4 mm), by the handles of the width and the size of the font
   (`SelectionManager`, measured), by the scaling of a selection (the height times the scale), by
   the lecture assistant (`NoteInserter`, measured), and once per opening for the texts with
   fences of code (`DrawViewController.measureTextsWithCode`, added on 2026-10-24).
2. **It is kept and sent.** The height is a column of `texts` (Room, schema 20), a field of
   `TextData` (the operations, the snapshots, the presence of the other devices) and of
   `BoxPlacement`; the log of the operations keeps them as JSON (`operations.change`, decoded
   strictly), the proofs of the operations of the other devices as signed CBOR.
3. **The rotation turns around the middle of the box**, `(x + width / 2, y + height / 2)`: the
   place of a turned text depends on its height.
4. **A text whose width follows it** (`autoWidth`, a text started by a tap or pasted) stores the
   width it took: its longest line and 1 mm for the caret, at most up to the margin of the page,
   its box growing from the side of its alignment (`TextEditorManager.fitWidth`).
5. **Everything reads the stored box**: the tiles choose the texts they draw by it
   (`TileRenderer`), the tiles invalidated by a change are its (`ChangeEffects.dirtyRegions`), and
   so are the frame of the selection, the lasso, the taps, the snapping, the presence of the
   other devices (`CollaboratorsOverlay`), the copy of a text lost to another
   (`DocumentChange.writeText`), the export (`PdfExporter`).
6. **The layout changed under stored heights.** On 2026-10-20 (`fc240016`) the items of a list
   got a bullet and a hanging indent; a text measured before takes more lines now. The note of the
   screenshots, rebuilt and measured (Noto Sans, 11 pt, 66.5–67.25 mm wide, its line breaks the
   ones of the screenshots): 14 lines and 74.2 mm today, 12 lines and 63.6 mm before. Its box is
   two lines short: the tile holding the bottom of the box draws the two lines below it as far as
   its own edge, the tile under it does not draw the note; the edges of the tiles (512 px, at
   levels of zoom that are powers of 2) fall elsewhere at each level.
7. **Each zoom laid the text out again.** `ComposeTextTileAdapter.drawText` laid the text out at
   the pixels of the tile: its height moved by up to 1.3 mm around the measured one from 3 to
   32 px/mm, and took a line more at 1 px/mm. Fixed already on this branch (phase 1 below): the
   text is laid out once, at the resolution of `measure`, and drawn scaled.

## Requirements

1. **Every line of a text is drawn, at every zoom**, and nothing of it is left behind when it moves
   or changes.
2. **The box is the text**: its frame, its taps, the lasso, the snapping take every line.
3. **A document does not depend on the layout that wrote it**: a later change of the fonts, of the
   notation or of the layout changes how its texts look, never leaves a box that does not hold
   them, and needs no correction of the documents.
4. **The documents of the user are kept**, looking as they do (their texts with the lines of
   today): a migration of the database, tested, no destructive fallback.
5. **No layer for the early versions**: no pass correcting the documents at every opening, no
   field kept only to be ignored.
6. **Opening a document stays fast**: a text is laid out when it is first needed, and once.

## Design

### What a text keeps

* **Kept** (decided by the user): its content and its format, its alignment, its font size, its
  color, its rotation, and **where it may go**: the corner it starts from (`x`, `y`) and the width
  its lines wrap on (`width`).
* **Laid out** (the result): the height of its lines, and for a text whose width follows it, the
  part of its width its lines take. Never stored, never sent.

### The height, laid out

* `TextData`, `BoxPlacement` (for a text) and the table `texts` lose the height. `DrawText.height`
  stays as the height its lines take **on this device**, not stored: an estimate when the text
  is created (its lines times `TextSizes.estimatedLineHeightMm`), then the height of its layout.
* **`TextBoxes`** (new, `drawview-canvas`, Pure Fabrication) keeps the heights of the texts of the
  document of an editor: given the text adapter, it lays out every text of a page the first time
  the page is needed, and a text again when a change reaches it (local or from another device,
  through the observer of the session), and invalidates the tiles of the box it had and of the
  box it has. The adapter caches the layouts (one per text, the same drawn by the tiles since
  phase 1), so a layout serves both.
* **The tiles choose a text by its layout** (`TileRenderer` asks the adapter, cached), not by a
  field: the thumbnails and the export, without an editor, draw every line too.
* The places that write a height today stop writing it: the editor, the handles, the size of the
  font, the scaling of a selection, the lecture assistant (it still measures its notes to place
  them and to fill its pages, as a layout, not as a field).
* `measureTextsWithCode` goes: there is nothing to correct.
* Where nothing lays a text out (the model, the synchronization): the estimate, used only to place
  the copy of a text lost to another below it.

### The rotation, around the corner

* A text turns around its top-left corner (`x`, `y`), which does not depend on its lines: its
  lines go along its turned axes from there. The images and the elements of the applications keep
  turning around their middle (their height is theirs).
* `DrawText` keeps the operations of `RotatableItem` (bounds, contains, to local) with this pivot;
  the selection, the editor, the export and the adapter turn it so.

### A width that follows the text (proposed, see [Decisions](#decisions))

* A text whose width follows it keeps the room its lines may take, from the side of its alignment
  to the margin of the page, as `fitWidth` computes it: its lines are laid out on that width and
  aligned in it (start, middle, end), as the adapter already aligns the lines of every text in
  its width.
* Its box is the part its lines take, and 1 mm for the caret: from the layout (the left of its
  leftmost line, the right of its rightmost line), like its height.
* A font laying out wider no longer wraps a line that fitted when it was written.

### The migration (Room 20 → 21)

The documents of the user are rewritten once, so that they look as they do:

* `texts`: the column `height` is dropped (the table rebuilt). For a turned text the corner is
  computed from its middle with the stored height, so it stays where it is. For a text whose
  width follows it (if approved) the width becomes its room, `x` its start, from its alignment and
  the width of its page, so its lines stay where they are.
* `operations`: the changes are JSON. Each text and each placement of a text in them is rewritten
  the same way (the height they carry gives the corner), then the height is dropped from them.
* `operation_proofs` (signed CBOR of the operations of the other devices) cannot be rewritten:
  their texts keep the height, ignored on decoding (the CBOR of the synchronization ignores
  unknown keys), and a turned text in one of them would be placed by its middle as a corner. Only
  an operation of the other device not yet received by this one is still given on from a proof:
  the two devices of the user are synchronized before updating (see [Left for later](#left-for-later)).
* Tested with `MigrationTestHelper` on schema 20: a text, a turned text, a text whose width follows
  it in each alignment, operations of each kind carrying texts and placements; its result drawn
  as before (same corner of every line).

### What travels

A protocol change: `TextData` and the placement of a text lose the height, a turned text is placed
by its corner, a text whose width follows it by its room. `ProtocolFormatTest` is recorded again.
The version is to be agreed (see [Decisions](#decisions)).

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| What a text keeps: content, format, corner, width it wraps on, rotation | `DrawText`, `TextData` (`drawview-model`) | Information Expert |
| The layout of a text and its size, one per text, cached | `TextTileAdapter.measure` (`ComposeTextTileAdapter`, `drawview-ui`) | Information Expert |
| The heights of the texts of an editor, laid out when first needed and when a change reaches them; the tiles of their boxes invalidated | `TextBoxes` (`drawview-canvas`) | Pure Fabrication, Low Coupling |
| The texts a tile draws, by their layout | `TileRenderer` with the adapter | Information Expert |
| A text turned around its corner | `DrawText` (`RotatableItem` with its own pivot) | Polymorphism |
| The documents of the user rewritten once | `Migrations` 20 → 21 (`drawview-store-room`) | Pure Fabrication |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | One layout at every zoom: `drawText` lays a text out at the resolution of `measure` (20 px/mm) and draws it scaled, the formulas rendered again at their size on the tile; tests (`TextHeightTest`: the note drawn at 0.75 to 64 px/mm ends within a pixel of its measured height); the screenshots of the explorer recorded again (the glyphs a fraction of a pixel apart) | Done |
| 2 | The height laid out: `TextBoxes`; `DrawText.height` the height of its lines once laid out; the tiles by the layout of the adapter (sizes cached); `measureTextsWithCode` removed; the format unchanged (the height still written, the writers as they are) until phase 3; tests (`TextEditingTest`: the texts as high as their lines once the adapter is set, with no change of the document; a changed text as high as its new lines whatever height it carries; `TileEngineTest`: a tile under the old box of a text draws its lines, not below them) | Done |
| 3 | The format: the height out of `TextData`, `BoxPlacement` (texts) and `texts`; nothing writes a height any more (editor, handles, font size, scaling of a selection, lecture assistant); the corner as the pivot of a text; Room 21 and its migration of `texts` and `operations`; the protocol (`ProtocolFormatTest`); ABI of `drawview-model`; tests (`MigrationTestHelper` as above; a turned text scaled around the middle of its frame; a point of a turned text taken from its corner) | Done |
| 4 | A width that follows the text, laid out (if approved): its room kept, its box from its lines; in the migration of phase 3 (one schema, one change of the protocol); tests (each alignment, a wider font not wrapping, the editor growing the box as today) | Planned |

The commits of the first plan that corrected the stored heights (on opening, when scaled) are
dropped from the branch: phase 1 is the commit that remains. Each phase is one commit on
`fix/text-overflow`, with the checks of `CLAUDE.md`: `jvmTest`, `checkKotlinAbi`, the Android
and iOS builds, the sync modules on the iOS simulator (phase 3), the screenshots, the desktop
application on a scratch directory and on a copy of the database of the user (phase 3, asked
first, the original untouched).

### Phase 2 results

* `TextBoxes` (`drawview-canvas`, internal) lays every text out when the adapter is set or the
  texts are drawn differently (`textRenderingChanged`), off the main thread, keeping a height only
  for a text still as it was copied; it lays out again the texts a change saved, and every text
  when the pages are laid out again (pages that came); a new height invalidates the tiles of the
  old and the new box. It runs first in the observer of the controller, before the frames.
* `ComposeTextTileAdapter.measure` keeps the sizes it laid out (by content, font size, width,
  alignment; dropped with the other caches).
* `RotatableItem.getRotatedBounds(height)`: the tiles choose a text by the height of its layout
  (ABI of `drawview-model`).

### Phase 3 results

* `TextData` and the table `texts` have no height; `TextPlacement` (`text`) places a text by its
  corner, its width, its rotation, its font size and whether its width follows it; `BoxPlacement`
  is for the images and the elements of the applications only. A text read or received has the
  estimate of `TextSizes.estimatedHeightMm` until `TextBoxes` lays it out; the copy of a lost text
  is placed below its estimate.
* `DrawText` turns around its corner: `centerX`, `centerY`, `getRotatedBounds(height)`, `toLocal`
  and `DrawText.cornerFor` (the corner of a box from its middle). The selection keeps its frame
  around the middle of a text (`frame`) and places a text back by its corner; the handles of the
  width move the corner along the lines; the editor (`TextBoxEditor`), the adapter, the export
  and the frame of the other devices turn a text around its corner, as high as it is laid out.
* `Migration20To21`: the turned texts get their corner from their middle and their height, the
  table is rebuilt without the height; the log of the operations is rewritten the same way (a text
  is told by its height and its font size, with its type among elements and without it in
  `updateTexts`; a placement of a text becomes `text`). Tested on a database of version 20.
* The protocol stays at version 1, its format recorded again (`ProtocolFormats`).
* The screenshot of a turned text being written recorded again (`text-writing-turned`): the text
  of the test, placed by its corner now, is a few pixels lower, the bar of the editor with it.

## Decisions

To be approved by the user:

1. **The pivot of a turned text is its top-left corner** (recommended). The alternative keeps the
   middle: no positions migrated, but a turned text moves by half the change of its height along
   its turned axis whenever the layout changes.
2. **A width that follows the text is laid out too** (phase 4, recommended now): the format and the
   protocol change once instead of twice. The alternative keeps the width it took, and a font
   laying out wider wraps its longest line again.
3. **The version of the protocol stays 1**, the two devices of the user updated together and
   synchronized before (as on `feat/internet-sync` and `feat/untrusted-members`); or 2.

Taken alone, revertible:

4. **The height of a text stays a field of `DrawText`, not stored**, kept by `TextBoxes`: the
   bounds, the hit tests and the frames of the selection keep reading one property; a query to
   the adapter in each of them would spread the layout into the model.
5. **A page is laid out when it is first needed**, not the whole document on opening.
6. **The images and the elements of the applications keep their stored height and their middle
   as pivot**: their size is the user's.
7. **The first plan is dropped**, its commits too (they corrected what is no longer stored).

## Left for later

* An operation of the other device kept as a proof and not yet received here: its turned texts
  would be placed by their middle as a corner. Synchronizing the two devices before updating
  avoids it; a published application would need versions of the protocol that tell the two forms
  apart.
* The editor lays its text out at the zoom of the screen: a line may break elsewhere while it is
  edited at a small zoom, as against the tiles before phase 1.
