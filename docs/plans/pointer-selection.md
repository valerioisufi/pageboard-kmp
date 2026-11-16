# Selecting with a pointer at the ends of a format

The first and the last characters of a bold or an italic text could not be selected alone with a
pointer in the text being written. This document gives the cause, the requirement, the design and
the plan. It continues `docs/plans/text-fidelity.md` (phase 4, the caret stops) and starts from `main` at
d04b4613, 2026-11-12.

The responsibilities are assigned following the GRASP principles (Information Expert, Controller,
Protected Variations).

## Starting point

Measured on the JVM on `EditorScreen` in a window of 1200 × 900, with injected mouse and touch events
(`TextBoxEdgeSelectionTest`). Nothing was run on Android or iOS.

The delimiters of a format are not shown, so several offsets of the source are shown at one place:
in `**bold**` the offsets 0, 1 and 2 are all before the `b`. `CaretStops.moved` settles what the field
gives the editor, and treats every change of an end of the selection as a move by a key: an end
landing where it was shown before goes on in its direction to the next place (`settle`), so that every
press of an arrow moves the caret.

A pointer does not move by the characters of the source: it gives the offset under it, from the text
as shown. Treated as a move, that offset is pushed past the character it is on:

| # | What the user does | What happened | Cause |
|---|---|---|---|
| 1 | Drags a mouse over the first character of a format, from its left edge | Nothing selected, a caret after the character | The press puts the caret before the delimiters (offset 0); the drag gives the anchor after them (2), shown at the same place: `settle` moves it on to 3 |
| 2 | Goes on to the second character | Both selected | The field sends nothing while the pointer stays on the same character, so 1 stays wrong until the next one |
| 3 | Drags back over the last character of a format followed by text | The character, its closing delimiters and the space after them | The press puts the caret inside the format; the drag gives the anchor after the delimiters, shown at the same place: moved on past the space |
| 4 | Holds a handle of a selection ending at a format | The same as 3, at every other event of the finger | The handle gives the offset after the delimiters, one event out of two |
| 5 | Clicks where the caret is shown, the caret after the closing delimiters | The caret before the last character | The click gives the offset inside the format: a move to the left, moved on |

With the keys everything was right: Shift and an arrow select the first and the last character.

## Requirements

1. **A pointer selects the characters it passes over**, the first and the last of a format too, alone,
   in both directions, wherever the caret was.
2. **A selection holds what it shows**: no delimiter, no character the pointer did not pass over.
3. **The keys do not change**: every press moves the caret on the screen.
4. Nothing stored and nothing that travels changes.

## Design

| Responsibility | Who | GRASP |
|---|---|---|
| Settling a selection among the offsets shown at its ends, moved on only when a key moved it | `CaretStops.moved`, with `pointed` (`drawview-ui`) | Information Expert |
| Knowing that a pointer gave the selection | `TextBoxEditor`: its own gestures (a finger, a pen, the handles, the caret asked by a touch) say so; for a mouse, which the field handles itself, while a button is down on the field | Controller |

* `CaretStops.moved(old, new, mapping, pointed)`: with `pointed`, the ends are taken as given and only
  settled among the offsets shown there (`inside`: the start after the opening delimiters, the end
  before the closing ones; a caret in the format on its left). Without it, as before.
* `TextBoxEditor.change(value, smartNewline, pointed)`. A pointer input before the field notes a mouse
  button going down, and clears it once the field has taken the release (the final pass of that
  event), so that the caret put by the press and every step of the drag are pointed.

## Phases

| # | Phase | Status |
|---|---|---|
| 1 | `pointed` in `CaretStops` and in the editor; tests: `CaretStopsTest` (a pointer at both ends of a format, a handle held, a click on the caret), `TextBoxEdgeSelectionTest` (a mouse over the first and the last character of formats at the start, in the middle and at the end of a line, in both directions; a handle held still) | Done |

## Decisions

Taken alone, revertible.

1. **A place given by a pointer is never moved on**, a caret too (problem 5), not only the anchor of a
   selection: the pointer is where the user sees it.
2. **A mouse is known by its button being down on the field.** A key pressed during a drag is then
   taken as pointed: its selection is settled and not moved on. Rare, and harmless.
3. **The drag starting on an object** (a block of code, a formula, a cell) stays as it was: it goes to
   the editor without the caret stops.

## Left for later

* A pen could not be injected in the tests of the JVM: its drag takes the same path as a finger after
  a long press (`touchSelectionGestures`), which is pointed.
* On Android and iOS the field is the same code; nothing was run there.
