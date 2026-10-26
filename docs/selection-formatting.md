# Formatting a selection

What happens to the selection of the text being written when the user types a delimiter over it,
presses a button of the formatting bar or pastes a link on it; and what the bar shows of the
formats around the caret. The responsibilities are assigned following the GRASP principles.

## Starting point

* The buttons and the shortcuts of the character formats (`EditorCommands.characterFormat`) call
  `TextFormatTarget.toggle(format)`, which `TextEditorManager` implements with
  `RichTextEditing.toggle`, then `toggleDelimiters`:
  * at the caret it inserts the two delimiters, the caret between them;
  * on a selection it removes the delimiters when the characters right around the selection are
    the delimiter, otherwise it adds them around the selection.
* Typing a character over a selection replaces it, as in any field: `TextEditorManager.updateValue`
  receives the new value from the `BasicTextField` and only handles the line breaks of the lists
  (`handleSmartNewline`) and the deletion of concealed delimiters (`handleConcealedDeletion`).
* The buttons of the formats never show a state: `characterFormat`, the heading and the lists have
  no `isChecked` (the line numbers and the alignment have one).
* The notation has no links: `RichTextParser.Kind` has no `LINK`, so `[text](url)` is shown as it
  is written.

### Problems found

| # | Problem | Cause |
|---|---|---|
| P1 | Italic on bold text turns it into italic | Selecting `x` in `**x**` and pressing *Italic*: `toggleDelimiters` sees `*` on both sides and removes one of each (`*x*`) |
| P2 | A selection with its delimiters is wrapped again | Selecting the whole `**x**` and pressing *Bold* gives `****x****` instead of removing the bold |
| P3 | A selection ending with a space breaks the notation | A double click selecting `word ` gives `**word **`, which is not bold (a closing delimiter follows a character other than a space) |
| P4 | A selection over several lines breaks lists and headings | `**` added before `- item` makes the line no longer a list item |
| P5 | Part of a formatted text cannot be unformatted | Selecting `b` in `**abc**` and pressing *Bold* adds `**` around `b` |
| P6 | Formats inside code and formulas | *Bold* on a selection inside `` `code` `` or `$x$` writes the delimiters in the code or the formula |
| P7 | No state on the buttons | See above |

## Requirements

1. **Typing a delimiter over a selection surrounds it** instead of replacing it: `*`, `_`, `~`,
  `` ` ``, `$`, and the pairs `(` `)`, `[` `]`, `{` `}`, `"` `"`, `'` `'`, `“` `”`, `‘` `’`, `«` `»`.
   * The selection stays on the same characters, so typing `*` twice makes the text bold and `~`
     twice strikes it through.
   * Typing always adds (never removes), so that the formats can be built up; the bar toggles.
   * The notation delimiters leave the spaces at the ends of the selection outside (P3); the
     brackets and the quotes surround the selection as it is.
   * In a block of code and in inline code nothing is surrounded (the characters are code); in a
     formula only the brackets are.
   * It works with a hardware keyboard and with the software keyboards (the change is recognized
     from the value, not from the key), and never during a composition of an input method (a dead
     key `'` composing `é`).
2. **The bar toggles a format by what the text is, not by the characters around the selection**
   (the spans of `RichTextParser`):
   * the selection in the content of a span of the format, or on the whole span with its
     delimiters, removes the format (P1, P2);
   * a selection strictly inside a span of the format takes the format off that part only, the
     rest keeping it: `**a|b|c**` → `**a**b**c**` (P5);
   * a selection covering spans of the format and text without it formats the whole: the inner
     delimiters go, one pair surrounds all;
   * the spaces at the ends of the selection stay outside (P3);
   * a selection over several lines is formatted line by line, after the markers of lists and
     headings, empty lines left alone; the format is removed only when every line has it (P4);
   * a format next to the same character uses the other delimiter of the notation (italic in bold
     is `**_x_**`, bold in italic `*__x__*`), so the parser reads both;
   * inside inline code or a formula the other formats do nothing (P6);
   * at the caret: inside a span of the format, the span loses it; elsewhere the two delimiters are
     inserted, the caret between them (as now).
3. **The buttons show the formats at the caret or on the selection** (P7): bold, italic,
   strikethrough, code, formula, heading, bulleted and numbered list; also the checks of the
   Format menu, which come from the same commands.
4. **Links:** `[text](url)` is part of the notation; pasting a link over a selection writes
   `[selection](link)`.
   * A link is `http://`, `https://`, `mailto:` or `www.` followed by characters other than spaces;
     the selection is on a single line, is not a link itself, is not in code or a formula.
   * The editor conceals `[`, `](` and the address away from the caret, as the other delimiters;
     the page, the editor and the export draw the text of the link underlined.
5. Everything is undone by the undo of the field where it has one (desktop): one undo goes back to
   the text before the character or the paste.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The notation of links: `Kind.LINK`, its content the text, its delimiters `[` and `](url)`; the address of a link span | `RichTextParser` (`drawview-model`): links found after the code and the formulas, their addresses masked before the emphasis | Information Expert |
| The inline formats of a range of the text, and toggling one on a selection by the spans (requirement 2) | `InlineFormatting` (`drawview-canvas`, `canvas.text`), new: `formatsAt(text, selection)`, `toggle(value, format)`; `RichTextEditing.toggle` delegates to it (same public entry) | Information Expert, High Cohesion, Pure Fabrication |
| What the input does to a selection: a delimiter typed over it, a link pasted on it (requirements 1 and 4) | `SelectionInput` (`drawview-canvas`, `canvas.text`), new: `surround(old, new)`, `link(old, new)`, pure functions of two `TextFieldValue`s | Pure Fabrication, High Cohesion, Protected Variations (keyboards and input methods seen only as values) |
| Applying them as the user writes | `TextEditorManager.updateValue`, before the line breaks of the lists | Controller |
| The formats at the caret, for the bars and the menu | `TextFormatTarget.isActive(format)`, `inHeading`, `inBulletList`, `inNumberedList` (defaults `false`, the selected texts having no caret); `TextEditorManager` answers with `InlineFormatting` and the spans of the line | Information Expert, Polymorphism (target) |
| The state of the buttons | `isChecked` of the format commands in `EditorCommands`; `FormatButton` already draws it | Controller, Low Coupling |
| Showing links | `MarkdownTextUtil.contentStyle` (underline) for the editor and the canvas through `RichTextPresenter`; the export through `TextTileAdapter` | Protected Variations |

`RichTextEditing` keeps the blocks, headings, lists and indentation; the two new objects take the
inline formats and the input on a selection, so that it does not grow further (587 lines).

## Plan

Branch `feat/selection-formatting`, one commit per phase.

| Phase | Content | Status |
|---|---|---|
| 1 | `InlineFormatting`: formats of a range, toggle by the spans (P1–P6), `RichTextEditing.toggle` delegating; tests in `drawview-canvas` (each problem, nesting with the parser, line by line, caret) | Done |
| 2 | State of the buttons: `TextFormatTarget.isActive`, `inHeading`, `inBulletList`, `inNumberedList`; `isChecked` of the commands; tests of the target and of the commands (`CommandTest`), screenshot of the bar with bold active | Done |
| 3 | Surround on typing: `SelectionInput.surround` in `updateValue`; tests of the function (delimiters, pairs, spaces, code, formula, composition, twice `*`) and of the use case (`TextEditingTest`); a desktop test typing over a selection in the field, and its undo | Done |
| 4 | Links: `Kind.LINK` in the parser, concealed and underlined in the editor, the canvas and the export; `SelectionInput.link` on a paste; tests of the parser, the presenter, the parity editor/canvas (`TextParityTest`), screenshots | Planned |

Each phase: `./gradlew jvmTest checkKotlinAbi`, the Android and iOS compilations, the screenshots
(`verifyRoborazziJvm`) and the desktop app on a scratch data directory. The public API of
`drawview-model` (`Kind.LINK`) and `drawview-canvas` (`TextFormatTarget`, the new objects) changes:
`updateKotlinAbi`, said in the commits. No stored format changes: the texts stay their source.

## Decisions

Taken alone, revertible:

1. Typing always adds the delimiter; only the bar and the shortcuts remove a format.
2. A selection inside a formatted span loses the format on that part only (split), as in the word
   processors, rather than the whole span.
3. Links are drawn underlined in the color of the text (the user's color, printable), not blue.
4. `'` surrounds a selection like the other quotes; a dead key composing an accent is left alone.

## Left for later

* Opening a link: a tap in the editor or on the page (with which tool), and the link annotations
  in the PDF export.
* Bare addresses recognized as links (autolinks), and `![image](url)`.
* `***x***` read as bold and italic: the parser does not open a single `*` next to another one, so
  typing `*` three times gives bold with a visible `*`; the bar uses `**_x_**` instead.
* Formatting the texts selected with the lasso (whole texts): the character formats apply to the
  text being written only, as now.
