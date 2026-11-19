# Checklists and the levels of the headings

Two things the texts lack, left for later by `docs/plans/text-editing.md` ("Checklists") and
`docs/plans/text-blocks.md` ("A choice of the heading level in the bar"). This document starts from the
code as it is (main at ee07934d, 2026-11-14).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The lists** are read by `RichTextParser` (`LIST_ITEM` spans) and laid out by `ListNumbering`
   (`drawview-model`): a label per item, a bullet glyph or a number, inserted by
   `RichTextPresenter` (`drawview-ui`) in place of the hidden marker, for the editor, the tiles and
   the PDF alike. `- [ ] text` is a bulleted item whose text starts with `[ ] `: the brackets are
   shown as they are.
2. **The editing of the lists** is `RichTextEditing` (`drawview-canvas`): the buttons, Enter, Tab,
   Backspace on a marker. `TextFormatTarget` gives them to the bars and the commands.
3. **The headings**: `#` to `######` are read and drawn at six sizes (`MarkdownTextUtil.headingScale`),
   but the button of the bar and ⌥⌘1 only make a level 1 or take it away.
4. **The symbols font** (`notes_symbols.ttf`, `tools/fonts/make_symbols.py`) has the white square
   and the check marks, not the ballot boxes `☐` `☑`; the text font has neither.

## Requirements

1. **A checklist is Markdown**, as GitHub writes it: `- [ ] to do`, `- [x] done` (`[X]` read too).
   No new field, nothing stored or sent changes: a note written elsewhere with boxes shows them.
2. **Drawn as boxes** in the column of the labels, the same on the page, in the editor and in the
   PDF; the text of an item checked struck through and faded.
3. **Made and checked** from the bar (a button next to the lists), the menu Format, ⇧⌘9; checked
   with ⌘↩ and with a tap on the box, in the editor or on the page (a tap on a text opens it).
4. **Enter** goes on with an empty box, leaves the list on an empty item; the gestures of the lists
   (Tab, Shift+Tab, Backspace) work on them as on the bulleted items.
5. **The level of a heading** chosen: a chevron next to the button opens normal text, heading 1, 2
   and 3, each written at its size; ⌥⌘0 to ⌥⌘3; in the menu Format.
6. The seven languages; every platform.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The notation of a box: reading it, writing it | `TaskBox` (`drawview-model`, `model.text`) | Information Expert, Pure Fabrication, Protected Variations (the only place knowing `[ ]`) |
| A box read with its item, the content after it | `RichTextParser` (`Span.checked`) | Information Expert |
| The label of an item of a checklist | `ListNumbering` (`Item.checked`, `boxGlyphOf`) | Information Expert |
| Drawing the box and the text done; where the boxes are shown | `RichTextPresenter` (`PresentedBox`, `boxAt`) (`drawview-ui`) | Information Expert, Polymorphism (a label like the others) |
| The glyphs of the boxes | `notes_symbols.ttf`, composed by `make_symbols.py` from □ and ✓ | Pure Fabrication |
| Making a checklist, checking, Enter; the level of a heading | `RichTextEditing` (`toggleChecklist`, `toggleChecked`, `toggleCheckedAt`, `handleEnter`, `setHeading`, `headingLevelOf`) | Information Expert |
| Giving them to the bars and the commands | `TextFormatTarget` (`inChecklist`, `headingLevel`, …), `TextEditorManager` | Indirection, Low Coupling |
| The commands, the bar, the picker of the levels | `EditorCommands` (`checklist`, `checkItem`, `heading2`, `heading3`, `normalText`, `headingLevels`), `TextFormatBar`, `HeadingButton` | Controller, High Cohesion |
| The tap on a box | `TextBoxEditor` (a gesture before the selection; the caret request of a tap on the page) | Controller |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `TaskBox`, `Span.checked`, `Item.checked`, the boxes in the symbols font and `TextSymbols`; the presenter (box, text done, `PresentedBox`); `RichTextEditing` (checklist, check, Enter, the heading levels); `TextFormatTarget`; the commands, the button, the picker of the levels, the tap on a box; the strings in the seven languages; the notation told to the agents; tests; ABI of `drawview-model`, `-canvas`, `-ui` recorded on purpose | Done |

### Phase 1 results

* `TaskBox.at` reads `[ ]`, `[x]`, `[X]` followed by a space, a tab or the end of the line, after the
  marker of a bulleted item only; `Span.contentStart` is after the box, so the editor hides it with
  the marker and the continuation lines of the item start at its text.
* `ListNumbering.Item.label` of an item of a checklist is `☐` or `☑`, `bullet` `null`; the boxes and
  the bullets of a level are one list (siblings).
* `notes_symbols.ttf` has U+2610 and U+2611, composites of the white square (U+25A1) and the check
  mark (U+2713) at 70%: `make_symbols.py --boxes` adds them to the font already made, and the full
  build composes them too. `TextSymbols.RANGES` has `☐..☑`.
* The presenter draws the box in the symbols font (0.816 em wide) and the text of an item checked
  struck through at 55% of its color. `PresentedText.boxes` says where the boxes are; a tap within
  a quarter of a line around one toggles its item (`boxAt`).
* Tests: `ChecklistParsingTest` (`drawview-model`: the boxes read, what stays text, the labels, the
  continuation lines, the glyphs in the symbols font), `ChecklistEditingTest` (`drawview-canvas`:
  making and removing a checklist, the caret, checking a selection, the tap on a box, Enter, the
  heading levels).
* The klib dumps of `drawview-canvas` and `drawview-ui` were written by hand on Windows (the dump
  comes out empty there): `checkKotlinAbi` on a Mac checks them.

## Decisions

Taken alone on 2026-11-14 (the maintainer asked to go on without asking), every one revertible:

1. **The notation of GitHub**, not a new field: the texts stay Markdown, readable by the agents and
   by any other viewer, and nothing stored or sent changes. A note with `- [ ] ` in it already
   shows boxes now instead of brackets: the meaning it had in Markdown.
2. **Bulleted items only** take a box (`1. [ ]` stays text), as GitHub draws them.
3. **The boxes are glyphs of the symbols font**, composed from its square and check mark, rather
   than drawn by hand: they go wherever a label goes (tiles, editor, PDF) with no new code there.
4. **A tap on a box on the page opens the editor and checks it**: the page has no gesture of its
   own on the texts; a second tap is not needed to check another one.
5. **Backspace at the start of the text of an item** of a checklist makes it text, as for a bulleted
   item (the box goes with the marker).
6. **The picker of the levels offers 1 to 3**: the levels 4 to 6 are drawn but hardly used; they
   can be typed (`####`).
7. **⌘↩ checks**, as in Notion and Google Keep; ⇧⌘9 makes a checklist, as in Google Docs.

## Left for later

* Turning a checklist numbered (`1. [ ]`), and a count of the items done in the bar.
* Moving the items done to the end of their list.
* A box on the page that checks without opening the editor (a gesture of the canvas on texts).
* Pasting the boxes of other applications (`☐ item`, `[ ] item` at the start of a line) as items.
