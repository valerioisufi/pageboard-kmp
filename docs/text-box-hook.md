# The text box hook

Phase 2 of `docs/architecture.md`: the point by which an extension of the editor suggests the
words that follow while the user writes in a text box, the base of the suggestions while writing
(see "Planned work" in `CLAUDE.md`). This document starts from the code as it is (main at
65e9eea1, 2026-11-14). It gives the hook and the way the editor shows a suggestion; which model
suggests, and on which device, is the plan of the suggestions themselves.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The text being written** is held by `TextEditorManager` (`drawview-canvas`): its source in
   the notation of the notes (`value`, a `TextFieldValue`: text and selection), its page
   (`activeTextPageIndex`), its size and color. `updateValue` changes it as the user does, undone
   with the other edits of the box.
2. **The field** is `TextBoxEditor` (`drawview-ui`, 809 lines): a `BasicTextField` whose
   `VisualTransformation` shows the source as the page draws it (`RichTextPresenter`: formulas,
   lists, tables, code), with an `OffsetMapping` between source and shown text. Its keys go through
   `onPreviewKeyEvent`: Tab indents a list item (`editor.indent()`), moves between cells in a table;
   the arrows move between the cells and the chained boxes.
3. **The extensions** (`EditorExtension`, `docs/editor-ports.md`) contribute to the bars, the
   overlay, the commands, the panels, the page grid: nothing reaches the text box.
   `EditorScreen` hands their contributions to `DrawScreen` as parameters; the text box is composed
   under `DrawScreen` (`TextEditorOverlay` → `TextEditorSession` → `TextBoxEditor`).
4. Since phase 1, a new open member of `EditorExtension` breaks no extension compiled before.

## Requirements

1. An extension can suggest, for the text being written, what follows the caret.
2. The editor asks when the user stops typing for a moment, never while typing, and forgets a
   request whose text changed before its answer.
3. The suggestion is shown after the caret, in the text as the page draws it, in a lighter color;
   it is not part of the text until accepted.
4. Tab (or the right arrow at the end of the text) accepts it; Escape, typing anything else, moving
   the caret or leaving the box drops it. On a touch screen, a tap on the suggestion accepts it.
   Accepting is one edit, undone as the others.
5. Without a suggestion, Tab and the arrows do what they do today (indent, tables, chains).
6. The libraries send nothing anywhere: what the extension does with the text is the application's
   choice.
7. Nothing stored nor sent between devices changes.

## Design

* **Protected Variations (the hook).** `EditorExtension` gains an open member
  `textSuggestions: TextSuggester?` (default `null`), a setter of the builder, and the rule of
  `CombinedExtension`: the first extension that suggests something wins. The types, in
  `ui.extension`:
  * `fun interface TextSuggester { suspend fun suggest(context: EditorContext, writing: TextBeingWritten): TextSuggestion? }`;
  * `TextBeingWritten`: the source of the text, the caret (an offset of the source), the page index,
    the id of the text box;
  * `TextSuggestion`: the text to insert at the caret. A class, so that later versions add what a
    suggestion may carry (e.g. a span to replace) without breaking the suggesters.
* **Indirection.** The suggester reaches the text box through a `CompositionLocal` of
  `drawview-ui` (`LocalTextSuggestions`), provided by `EditorScreen` with its context, instead of a
  parameter through `DrawScreen`, `TextEditorOverlay` and `TextEditorSession`.
* **Pure Fabrication.** `SuggestionSession` (in `ui.editor`) owns the state of a suggestion: it
  waits for the pause after the last change (`SUGGESTION_PAUSE_MS`, 400 ms), asks, keeps the answer
  only if the source and the caret are still those it asked for, and drops it on any change. It
  has no Compose UI, so it is tested alone.
* **Information Expert.** The shown text is the presenter's: `GhostText` wraps the transformation
  of the field, inserting the suggestion at the caret's place in the shown text, styled lighter,
  and wraps its `OffsetMapping` so that the caret and the selection never enter it (each of its
  offsets maps to the caret). The page draws nothing of it.
* **Controller.** `TextBoxEditor` takes the keys: with a suggestion shown, Tab and the right
  arrow at the end accept it (`editor.updateValue` with the text inserted, as typing it), Escape
  drops it; without one, the keys are as today. A tap whose offset falls in the suggestion accepts
  it.

## Phases

| Phase | Content | Status |
|-------|---------|--------|
| 1 | The hook: `TextSuggester`, `TextBeingWritten`, `TextSuggestion`, `EditorExtension.textSuggestions` with its builder setter and the rule of `+`, `LocalTextSuggestions` provided by `EditorScreen` | done |
| 2 | `SuggestionSession` (pause, stale answers, drop) and `GhostText` (the transformation and its mapping), tested alone | done |
| 3 | The text box: the suggestion shown, accepted by Tab, the right arrow at the end and a tap, dropped by Escape and by any change; the keys unchanged without one | to do |
| 4 | The sample: an extension suggesting the end of a word from the words of the document, and screenshots of a suggestion shown | to do |

Each phase is one commit on `feat/text-box-hook`, after the checks of `CLAUDE.md`, the screenshots
of `drawview-ui` and `drawview-explorer`, `consumer-checks`, and the desktop sample run by hand on a
scratch data directory at the end.

## Decisions

Taken alone, revertible:

* **A suggestion is text inserted at the caret**, in the notation of the notes. It is shown on one
  line, as plain text without its marks of emphasis; once accepted, the page shows it with its
  notation.
* **A suggestion is shown with the caret at the end of the text only** (where one writes, most of
  the time): what the presenter laid out after the caret (formulas, tables, blocks) never moves,
  and the mapping only grows at its end (`SourceMapping.withShownAtEnd`). The middle of a text is
  left for later.
* **The pause is 400 ms**, the time after which the editor asks; the extension may answer later,
  and its answer is dropped if the text changed meanwhile.
* **Tab accepts only while a suggestion is shown**: indenting a list item is one Escape (or one
  more character) away while a suggestion is there.
* **The first extension that suggests wins** when several are combined: the order of `+` is the
  order of preference.
* **No suggestion in a table cell or in a block of code** in this phase: their carets move by their
  own rules, and the suggestions of code are another matter.

## Left for later

* The suggester of the applications (a local model, `drawview-ml`): the plan of the suggestions
  while writing.
* Suggestions of several lines, in the middle of a text, or replacing what is before the caret.
* A suggestion in the handwriting (the canvas, not the text box).
