# Numbered lists: numbers that follow, styles to choose, lists in lists

Numbers that stay right when an item is deleted or moved, a numbered list inside any other list
made with one gesture, and a style of numbering chosen by the user. It starts from main at
d79f6482, 2026-11-05. The responsibilities are assigned following the GRASP principles.

## Starting point

The source of a list keeps its markers as written; `ListNumbering` computes the labels at drawing
time: the ordinal among the siblings, from the number of the first sibling, written `1.` → `a.` →
`i.` by the numbered items above. `RichTextEditing` edits the markers line by line, with no idea of
the list they belong to.

Measured on `ListNumbering` and `RichTextEditing` on 2026-11-05, from `1. a`, `2. b`, `3. c`:

| Action | Source after it | Labels shown | Expected |
| :--- | :--- | :--- | :--- |
| Middle line deleted | `1. a`, `3. c` | `1.` `2.` | right, the source is stale |
| **First line deleted** | `2. b`, `3. c` | **`2.` `3.`** | `1.` `2.` |
| **Backspace on the first marker** | `a`, `2. b`, `3. c` | **`2.` `3.`** | `1.` `2.` |
| **Backspace on the second marker** | `1. a`, `b`, `3. c` | `1.`, **`3.`** | `1.`, `2.` |
| **Tab on the third item** | `1. a`, `2. b`, `  3. c` | `1.` `2.`, **`c.`** | `a.` |
| **Tab on the second item** | `1. a`, `  2. b`, `3. c` | `1.`, **`b.`**, `2.` | `a.` |
| **Tab on the first item** | `  1. a`, `2. b`, `3. c` | no change, spaces added | nothing |

The reported bug is the first bold rows: the start of a list is the number of its first item, so
the first item taken away takes the start with it.

From `- a`, `  - b`, `  - c`, `- d`:

| Action | Result | Problem |
| :--- | :--- | :--- |
| Numbered, the caret on `b` | `1. b`, `◦ c` | a numbered item among bullets: a level in two lists |
| Numbered, the caret on `d` | `• a`, `1. d` | the same at the first level |
| **Numbered, everything selected** | `1. a`, **`b.`**, **`c.`**, **`2. d`** | one counter over the levels: the nested list starts from `b` |
| Enter on an empty nested item | `- a` and a blank line | leaves all the levels at once |
| Backspace on a nested marker, a sibling after it | `• a`, `b`, `2. c` | the list broken, its next item starts from 2 |

So a numbered list inside another list **can** be drawn, and can be made (Tab, then the button); what
is missing are numbers that hold and a gesture that does the expected thing.

There is no way to choose a style: the parser reads digits and `.` only (`NUMBERED_LIST`, `OpenItems`).
`a. uno`, `A. Rossi` and `1) tre` are plain text today, and must stay so in the existing notes.

## Requirements

1. **The numbers are always right**, after any edit: deleting (a line, a selection, the marker),
   moving a level, pasting, undoing. On the page, in the export and in the source (`read_page`,
   the copy, the search read the source).
2. **The start belongs to the list, not to its first item**: a list started from 5 still starts
   from 5 when its first item goes.
3. **A list in any list**: numbered in bullets, bullets in numbered, any depth, with the gestures of
   the good editors (table below).
4. **A style for each list**: automatic by level (as today), `1.` `a.` `A.` `i.` `I.`, with `.` or
   `)`, and the number it starts from. A style applies to a list (the siblings), not to an item.
5. **No existing note changes**: what is plain text stays so, what is a list is drawn the same, and
   nothing is rewritten until the user edits that list. No destructive step of any kind.
6. Every platform: the pill and the docked bar, mouse, pen, touch, keyboard; tooltip, Format menu,
   shortcut; the 7 languages; light and dark.

### The gestures

| Gesture | Where | What happens | Today |
| :--- | :--- | :--- | :--- |
| Tab | an item with a sibling before it | nested under that sibling, from 1 | keeps its old number |
| Tab | the first item of a list | nothing | invisible spaces added |
| Shift+Tab | a nested item | one level out, the numbers of both levels follow | the numbers are stale |
| Enter | an empty nested item | one level out | leaves the whole list |
| Enter | an empty first-level item | out of the list | same |
| Backspace at the start of the text | a nested item | one level out | breaks the list |
| Backspace at the start of the text | a first-level item | plain text; the items after it go on counting | they keep their old number |
| Numbered button, no selection | an item | **the whole level** turns numbered (see decision 2) | one item only |
| Numbered button, a selection | the lines selected | those lines, each level counted on its own | one counter over the levels |
| Chevron of the button | anywhere | the picker of the styles | none |

## Design

### The notation

The style is written in the marker, so the text remains Markdown, readable anywhere, and goes
through everything that carries text with no new field. It follows the fancy lists of Pandoc:

| Written | Meaning |
| :--- | :--- |
| `1. text` (one space) | automatic: `1.` `a.` `i.` by level, **as today** |
| `1.  text` (two spaces) | decimal, at any level |
| `a.  text`, `A.  text`, `i.  text`, `I.  text` | letters, Roman numerals |
| `1)  text`, `a)  text`, … | the same with a parenthesis |

**Two spaces are what makes a new marker a marker**: `A. Rossi`, `a. uno` and `1) tre` stay plain
text, so no note written so far changes (requirement 5). Nobody sees them or types them: the marker
is hidden, and the editor writes it. Pandoc requires the two spaces after a capital letter for
the same reason.

The style of a list is the one of its first sibling. One letter other than `i`, `v`, `x` is a
letter, more letters are a Roman numeral when they are one. So a list in letters cannot start from
`i`, `v` or `x`, nor a Roman list from `l`, `c`, `d`, `m`: the picker skips them
(`ListMarker.canStart`, a marker that does not read back). `1.  ` with two spaces, already possible
in an old note at a nested level, now shows its number rather than a letter: the number written.

### Who does what

| Responsibility | Class | Patterns |
| :--- | :--- | :--- |
| A marker: reading it and writing it, the only place knowing the notation | `ListMarker` and `NumberingStyle` (`drawview-model`, `model.text`) | Information Expert, Pure Fabrication, Protected Variations |
| The spans with their style | `RichTextParser` (`Span.style`), through `ListMarker`; still linear | Information Expert |
| The labels and the start of a list | `ListNumbering` | Information Expert |
| The source of the lists an edit touched, made right | `ListRenumbering.after(old, new)` (`drawview-model`), pure | Pure Fabrication, High Cohesion |
| When it runs | `TextEditorManager.updateValue`, after the other rules of editing | Controller |
| The gestures on the lists | `RichTextEditing`, `setNumbering(value, style, start)` new | Information Expert |
| The state and the commands | `TextFormatTarget.numbering`, `EditorCommands.numbering` | Indirection, Low Coupling |
| The picker | `NumberedListButton`, `NumberingPicker`, beside `TableButton`, its previews written by `ListNumbering.format` | High Cohesion |
| The continuation of a list in a chain | `ChainFlow.renumbered`, through `ListMarker` | Information Expert |

### `ListRenumbering`

Given the text before and after an edit, it rewrites the markers of the lists the edit touched:

* the siblings counted from the start of the list; a list whose first item was taken away keeps the
  old start (requirement 2);
* a list cut by a line of plain text: the second part goes on from the first (`1. a`, `b`, `2. c`);
* a new nested list starts from 1, in the style of its level if it has one;
* the caret and the selection follow when a marker changes length (`9.` → `10.`);
* **it does nothing when the structure of the lists did not change**: typing in an item costs one
  comparison, and an IME composing is not disturbed. A list the edit did not touch keeps its
  markers as written (`1. 1. 1.` stays).

### The picker

The button of the numbered list becomes a split button: the icon toggles as today (⌘⇧7), a chevron
opens the picker: the popover of the table picker (`ToolPopover`), on every window, keeping the caret
and the keyboard.

```
 Automatic  1. a. i.
 ┌─────┐┌─────┐┌─────┐┌─────┐┌─────┐
 │ 1.  ││ a.  ││ A.  ││ i.  ││ I.  │     each tile a preview of three lines
 │ 2.  ││ b.  ││ B.  ││ ii. ││ II. │     the current one marked
 └─────┘└─────┘└─────┘└─────┘└─────┘     targets of 48 dp
 Delimiter  [ 1. | 1) ]
 Start from [ − ] 1 [ + ]
```

A choice applies at once to the list at the caret (or makes one), the picker stays open to try
another, one step of undo each. With a selection over several levels it applies to the level of the
first line. The Format menu has "Numbering…", opening the same picker.

### Typing a style

At the start of a line, `a. `, `i. `, `a) ` or `1) ` typed by hand become a list of that style (the
second space added by the editor), and a Backspace right after gives back what was typed. Not the
capital letters: `A. Rossi` at the start of a line is a name.

## What does not change

* **Room, documents, preferences, records**: no schema or format changes. No migration is needed
  because no stored text changes meaning; a test keeps it so, on texts with `a. x`, `A. Rossi`,
  `1) x`, `i. e.`, and on all the list texts of the existing tests.
* **The sync protocol**: no frame or message changes, the markers are characters of the text. An
  application without this work draws `a.  text` as plain text, readable and not lost. To be
  said in the commit of phase 3.
* **The public API**: `drawview-model`, `-canvas` and `-ui` change their ABI, recorded with
  `updateKotlinAbi` in the commits.

## Phases

| Phase | Content | Status |
| :--- | :--- | :--- |
| 1 | The numbers: `ListRenumbering` wired in `updateValue`, the start kept, the source right; the tables above as tests, undo, caret, paste, chains. Fixes the reported bug | done |
| 2 | The gestures: Tab, Shift+Tab, Enter, Backspace by level, the button on the whole level, a selection counted by level | done |
| 3 | The notation: `ListMarker`, `NumberingStyle`, parser, labels, `ChainFlow`, `TextCleanup`; the old texts unchanged, bounds of the parser, ABI | done |
| 4 | The picker: split button, popover and sheet, `setNumbering`, start, "Numbering…" in the Format menu, the strings, screenshots | done |
| 5 | Typing a style, the instructions of the agents and of the assistant on the notation, the desktop app on a scratch directory | done |

Each phase can land alone: phases 1 and 2 are useful without a style to choose. The plan was
approved on 2026-11-05, with the decisions left to me.

### What phase 5 does

* `RichTextEditing.listFromTypedMarker` adds the second space to `a. `, `i. `, `a) `, `i) `, `1) ` typed
  at the start of a line; `TextEditorManager` gives back what was typed on the Backspace right after.
* The description of `propose` in `DocumentTools` says the notation of the styles. The prompts of the
  lecture assistant do not change: its notes do not need a style, and a run costs money.

### What was checked

* On the JVM: the tests of the parser, the numbering, the renumbering, the gestures, the chains, and
  the screenshots of the picker in the windows of a phone, a tablet and a desktop, light and dark.
* **Not by hand**: the Android and iOS apps were compiled, not run; the keyboard of a phone and the
  pen on a tablet are left to the maintainer.
* `text-saved-code` and `text-larger-than-window` fail `verifyRoborazziJvm` on d79f6482 too, by a
  scroll of 48 px that is not from this work. The first is left as it was (it still fails), the
  second is recorded again, because it has the bar.

### What phase 2 does

* `RichTextEditing.indent` moves an item under the sibling before it, with what is nested under it;
  on the first item of a list it does nothing. `outdent` takes an item to the level of its parent,
  however far it was written from it (the imported notes have 4 spaces).
* `handleEnter` and `handleConcealedDeletion` on a nested item: one level out, in the kind of the parent.
* `toggleListLines` turns the whole level when nothing is selected and the item is of the other
  kind (`ListRenumbering.withMarkers`); taking a list away stays on the lines selected.

### What phase 1 does

* `ListRenumbering.after` runs at the end of `TextEditorManager.updateValue`, on every change of the
  text, and costs one comparison when a word is typed.
* `ChainFlow.flow` writes the numbers of the whole text as shown (`ListRenumbering.asShown`) before
  cutting it: the numbers of the boxes after the one written are right when the chain flows. This
  replaces `ChainFlow.renumbered`, which wrote the first item of each part only.

## Decisions

1. **The style in the marker** rather than a field of the text box (a box holds many lists) or a
   comment in the source (visible in any other viewer). Revertible until phase 3 lands.
2. **The button with no selection turns the whole level**, as Google Docs and Word do, rather than
   the item alone, as Notion does. With a selection, only the lines selected. Left to me by the
   user (2026-11-05), revertible.
3. **Two spaces for every new marker**, for the existing notes. Revertible until phase 3 lands.
4. **The source is rewritten** by `ListRenumbering`, instead of only drawing the right label: the
   copy, the search and the agents read the source. Only on a list being edited.
5. **Automatic stays the default**: existing lists and new ones look as they do now.

6. **A list with blank lines goes on over them** (as it did), but a marker of a style it cannot write
   starts another list (as in CommonMark): `A.  x` after `1. y`. The letters and the Roman numerals of
   the same case are one kind here, because `i.` is the ninth letter too: `I.  x` right after a list
   in capital letters, parted by a blank line only, goes on as its next letter.
7. **The button that takes a list away acts on the lines selected**, not on the level.

## Left for later

- Numbers by level `1.1`, `1.1.1`: one more style, with labels depending on the parents.
- `(a)`, with both parentheses.
- A choice of the bullets, checklists (`- [ ]`, still left from `docs/plans/text-editing.md`).
- A list going on after text in another box that is not its chain (the "continue numbering" of Word).
- The boxes of a chain after the one written: their numbers follow when the chain flows, on closing
  (`docs/plans/text-boxes.md`), not while written.
