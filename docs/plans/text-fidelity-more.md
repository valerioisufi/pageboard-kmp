# The text written: formats at the caret, the whole source, the strip, selections, deletions

Stage E of the roadmap of `docs/plans/text-editor-enhancements.md`: what
`docs/plans/text-fidelity.md` left for later about the text being written, and a text written here
while another device removes it. It starts from `feat/code-blocks` after stage D
(`docs/plans/code-blocks-more.md`).

The responsibilities are assigned following the GRASP principles.

## Starting point

1. **A format at the caret** (`InlineFormatting.toggleAtCaret`): ⌘B (or the bar) with no selection
   writes `****` and puts the caret between; the four stars are shown until a character is typed in
   them, and stay in the text when the caret goes away.
2. **The source** (`RichTextPresenter`): the field shows the text formatted, its notation hidden;
   what the caret is in is written in the strip (`SourceStrip`), one thing at a time. There is no
   view of the whole source.
3. **The strip** (`SourceStrip`): above what it belongs to, below it when the formatting bar is
   there. It may cover the line next to it, and on a phone (the bar docked over the keyboard) it
   may go under the bar or out of the window; the completions (`CompletionPopup`) are placed by a
   guess of where the strip is.
4. **Selections and blocks of code** (`CodeBlockCaret`): the caret goes through the code by
   characters, but a selection drawn by a mouse from the text into a block, or made by Shift and the
   arrows across a block, takes the block whole.
5. **A text removed elsewhere while written here** (`TextEditorManager.onChange`): the editor is
   closed and what was typed since the last save is lost (the removal wins over a change, see
   `docs/plans/collaboration.md`, "Working apart for long").
6. **Conflicts** (`writeText` of `DocumentChange`): a content changed on two devices keeps the most
   recent one and the other in a copy under it, the same copy on every device, whatever the order
   (`ConvergenceTest.aTextChangedApartKeepsTheContentLostAsACopy`,
   `SyncEngineTest.aTextChangedApartOnTwoDevicesKeepsBothContents`,
   `TextAutosaveTest.aTextChangedOnAnotherDeviceIsNotLost`): nothing left to do there.

## Requirements

1. **A format chosen at the caret** is waiting there: the button and the menu show it on, nothing is
   written; the characters typed next are written in it (`**word**`); moving the caret, or choosing
   it again, drops it. Several formats wait together (bold and italic). Typed by an input method,
   the text composed goes in the format once committed.
2. **The whole source on demand**: a command of the menu View (and its shortcut) shows the text
   being written as it is written, its notation in the monospace of the notes, and back; the caret
   and the selection stay on the same characters. It is not saved: the next text opens formatted.
3. **The strip on small screens**: on a phone, the strip stands on the formatting bar docked over
   the keyboard, the width of the window; elsewhere it stays by what it belongs to, never out of the
   window. The completions stand by the strip, wherever it is.
4. **Selections into blocks of code**: a mouse drawing a selection from the text into a block, and
   Shift and the arrows across a block, select its code by characters, as in the text.
5. **Nothing written is lost**: a text removed on another device while it is written here becomes a
   new text with what was written, where it was, when what was written differs from what the
   other device removed; else the editor closes as today.
6. **No note changes**: no stored format and nothing that travels changes; every platform, the 7
   languages, light and dark.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The formats waiting at the caret, written around what is typed | `PendingFormats` (`pageboard-canvas`, `text`), `TextEditorManager` | Pure Fabrication, Information Expert |
| Whether the source is shown whole | `TextEditorManager.showsSource`, `EditorCommands.viewSource`, `TextBoxEditor` | Controller, Information Expert |
| Where the strip and the completions stand | `StripPlacement` (`pageboard-ui`), `SourceStrip`, `CompletionPopup` | Pure Fabrication, Low Coupling |
| The selection going through the code of a block | `CodeBlockCaret.extended` (`pageboard-ui`) | Information Expert |
| A text removed while written, kept as new | `TextEditorManager.onChange` | Information Expert, Protected Variations |

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | A text removed elsewhere while written kept as a new text; tests | Done |
| 2 | The formats waiting at the caret; tests | Done |
| 3 | The whole source on demand; tests | Done |
| 4 | The strip and the completions on small screens; tests, screenshots | Done |
| 5 | Selections into and across blocks of code; tests | Done |

## Decisions

Taken alone, revertible:

1. **The text kept takes a new identifier**: the one removed stays removed on every device (a
   removal wins, see `docs/plans/collaboration.md`), and the new text travels as any text added.
2. **A format waiting is not written**: an empty `****` was text no one meant, left behind when the
   caret went away.
3. **The whole source is a view of the editor only**: the page and the export show the text
   formatted, as always.
4. **What is compared is the text written**: a text removed elsewhere whose words were not changed
   here (its size or color only) closes the editor, as before. The editor stays open on the new
   text, which is undone with the writing.
5. **On a phone the strip stands on the bar** only when the bar is docked in the lower half of the
   window (over the keyboard); the completions stand by the strip where it was placed, as it reports
   it, rather than by a guess of its height.
6. **A mouse selection is read before the field** (the pass `Initial` of its events): where it was
   pressed and where it is, as offsets of the source through the blocks; the field's own selection
   is kept when neither end is in the code of a block.

## Left for later

- Scribble of the Apple Pencil in the field, and the checks of the input methods on real devices,
  left to the maintainer.
