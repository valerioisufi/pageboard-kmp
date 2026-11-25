# Checklists and lists: numbered checklists, boxes on the page, numbers by level, lists that go on

The features left for later in `docs/plans/checklists.md` and `docs/plans/list-numbering.md`, stage B
of the roadmap of `docs/plans/text-editor-enhancements.md`. It starts from
`implement_text_editor_features` at 7fb58dc1 (stage A done: Room 26 and `PROTOCOL_VERSION` 5 with
`TextElement.continueNumberingFrom` already stored and sent).

The responsibilities are assigned following the GRASP principles.

## Starting point

1. **Checklists** (`TaskBox`, `RichTextParser`, `RichTextEditing`): only a bulleted item takes a box
   (`- [ ] x`); `1. [ ] x` is a numbered item whose text starts with `[ ]`. The bar has a toggle for
   checklists and nothing else: no count of the items done, no way to move them to the end.
2. **Pasting**: the boxes of other applications (`☐ milk`, `☑ eggs`, `[ ] item`) stay text.
3. **The boxes on the page** are drawn (`ComposeTextTileAdapter`) but checked only in the editor: a
   tap on a box of a text not being edited opens the editor.
4. **Numbers** (`ListNumbering`, `ListMarker`, `NumberingStyle`): `1.` `a.` `A.` `i.` `I.` with `.` or
   `)`; no numbers by level (`1.1`, `1.1.1`), no `(a)`; the bullets are always `•` `◦` `▪` by level.
5. **A list in another box**: the boxes of a chain go on counting (`ChainFlow`); a list in a box out of
   the chain always starts again, though `TextElement.continueNumberingFrom` is stored since stage A.

## Requirements

1. **Numbered checklists**: `1. [ ] x` is an item of a numbered checklist, labelled `1. ☐`; Enter goes
   on with `2. [ ] `; the numbering, the renumbering and the change to bullets keep the boxes.
2. **The progress in the bar**: the button of the checklists shows `done/total` of the checklist of
   the caret, and opens its options: numbered or not, the items done moved below the others now, and
   a preference moving them there each time one is checked (off by default).
3. **Pasting boxes**: `☐`, `☑`, `☒`, `✓`, `✔`, `✅`, `□`, `[ ]`, `[x]` at the start of a line, after a
   bullet or a number, become items of a checklist, also in a single line pasted.
4. **A box checked from the page**, without opening the editor: a tap on a box with the hand tool, in
   stylus-only mode with a finger, or with the text tool, checks it as one step of undo; any other tap
   on a text does what it did.
5. **Numbers by level and more styles**: `1.1`, `1.1.1` (the numbers of the parents before the own);
   `(a)`, `(1)`, `(i)` with both parentheses; a bullet chosen for a list (`•`, `◦`, `▪`, `–`) or
   automatic by level, as today.
6. **A list going on in another box**: a box can continue the numbering of a list of another text
   (Word's "continue numbering"), on the page, in the editor and in the PDF.
7. **No note changes**: what is text stays text unless written in a notation that was not read before
   (`1. [ ]`, a stored `continueNumberingFrom`); nothing stored is rewritten. Every platform, the
   pill and the docked bar, the 7 languages, light and dark.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The box of a numbered item read and written | `TaskBox`, `RichTextParser` (`NUMBERED_LIST`, `OpenItems`), `ListRenumbering.write` (`pageboard-model`) | Information Expert |
| Labels `1. ☐`, counts of a checklist | `ListNumbering.items`, `checklistCounts`, `checklistCountsAt` (`pageboard-model`) | Information Expert |
| Boxes of other applications | `TextCleanup.checkboxItemOf`, `PastedText.clean` (`pageboard-model`) | Pure Fabrication |
| Numbered checklists, items done moved below | `RichTextEditing.toggleNumberedChecklist`, `moveCompletedToEnd`, `checklistProgressOf` (`pageboard-canvas`) | Information Expert, Pure Fabrication |
| The state and the actions of the bar | `TextFormatTarget` (`checklistProgress`, `moveCompletedToBottom`, `toggleNumberedChecklist`, …), `TextEditorManager`, `SelectionManager.SelectedTexts`, `EditorPreferences.moveCompletedChecklistsToBottom` | Indirection, Polymorphism, Controller |
| The button, its count and its options | `ChecklistButton`, `ChecklistOptions` (`pageboard-ui`), `EditorCommands.numberedChecklist`, `moveCompletedChecklists`, `checklistOptions` | High Cohesion |
| Where the boxes are on the page | `TextTileAdapter.checkBoxAt` (`pageboard-canvas`), `ComposeTextTileAdapter` (`pageboard-ui`) | Information Expert, Indirection |
| A tap on a box of the page | `CanvasGestureHandler`, `PageboardController.toggleCheckBoxAt` | Controller |
| Numbers by level, `(a)`, the bullets chosen | `NumberingStyle` (`Numbering.OUTLINE`, `Delimiter`), `BulletStyle`, `ListMarker`, `ListNumbering` (`pageboard-model`), the picker (`pageboard-ui`) | Information Expert, Protected Variations |
| The start of a list continued from another text | `ListContinuation` (`pageboard-model`), `TextTileAdapter` (`listStart`), `TextBoxEditor`, `PdfExporter`, `NumberingControls` | Information Expert, Pure Fabrication |

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | Numbered checklists, the count in the bar and its options (numbered, the items done below, the preference), the boxes of other applications pasted; tests; ABI recorded | Done |
| 2 | A box checked from the page: `checkBoxAt` on the adapters, `toggleCheckBoxAt`, the tap in `CanvasGestureHandler`; tests | Pending |
| 3 | Numbers by level (`1.1`), `(a)` `(1)` `(i)`, the bullets chosen; the picker; the notation kept readable; tests | Pending |
| 4 | A list going on in another box (`continueNumberingFrom`): `ListContinuation`, the page, the editor, the PDF, the toggle in the picker; tests | Pending |

## Decisions

Taken alone, revertible:

1. **`1. [ ]` is a numbered checklist** as in GitHub Flavored Markdown: a note with a numbered item
   starting with `[ ] ` or `[x] ` now shows a box instead of the brackets. Nothing stored changes; the
   source stays the same text.
2. **The items done move below the others within their own list** (the siblings), with what they
   hold (nested items, continuation lines, blocks of code); a numbered list keeps counting from its
   start. With the caret in a checklist, that checklist only; out of a list, all of them.
3. **The preference "keep completed at the bottom" is off by default** and applies to the boxes
   checked from the bar, from the keyboard (⌘↩) and by a tap in the editor.
4. **The count is of the checklist of the caret**, the whole text when the caret is out of a list;
   for texts selected with the lasso, the sum of their checklists.

## Left for later

- Real-device checks of the bar and of the taps on the boxes (Android, iOS), left to the maintainer.
