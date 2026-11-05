# Quotes, heading levels, headings with their text, symbols

What the note `E06 - 03 novembre` showed of the text boxes, apart from the lists
(`docs/list-numbering.md`): block quotes written as they are, levels of headings that look the
same, a heading left alone at the end of a page, symbols drawn by whatever font the system has.
It starts from main at fe9ce837, 2026-11-05. The responsibilities are assigned following the GRASP
principles.

## Starting point

"Measured" was rendered on the JVM with the renderer of the application, from pieces of that note
copied into a test. "Read" was read in the code or in the plans only.

### Block quotes (measured)

1. `RichTextParser` has no quote: `>` stays in the text, and a wrapped line goes back to the margin.
2. A list in a quote is not a list: `> * FIQ: …` shows `> *`, because the patterns of the items
   ask for the marker at the start of the line.
3. The note has 12 quotes on 7 of its 9 pages, two of them with lists. `TextCleanup` already leaves
   `>` lines as they are, as Markdown (read), so an imported text brings them.

### Heading levels (measured, read)

4. `MarkdownTextUtil.headingScale` gives 1.4, 1.25, then 1.1 for all the rest: `### 1.2` and
   `#### 1.2.1` have the same size, so the hierarchy of the note is lost from the third level.

### Headings alone (read in the note and in the code)

5. Three boxes of the note end with a heading whose text is in the next box: `#### 1.2.2`, `### 1.5`,
   `### 5.5`, each followed by a block of code.
6. `ChainFlow.unitEnds` keeps whole a block of code, a table, a list item, a display formula. A
   heading is a line like any other, so a cut may fall right after it.

### Symbols (measured)

7. The text font has few symbols. The coverage of the bundled fonts:

   | Font | Has | Lacks |
   | :--- | :--- | :--- |
   | Noto Sans (all 4) | `–` `×` `…` `•` | `→` `↔` `←` `↑` `↓` `⇒` `⇔` `≤` `≥` `≠` `∞` `◦` `▪` `✔` `✓` `✗` |
   | Noto Sans Mono (both) | all of them but the next | `✔` `✓` `✗` |

8. So **every arrow of the text comes from a fallback of the system**. On macOS `↔` came as the
   colored emoji in a light blue square, touching the code after it; the same text on Android, iOS or
   another computer has other shapes and other widths, against "the same on every platform" of
   `NotesFonts`.
9. In a PDF the characters of no bundled font become images of their words (`docs/export.md`, read):
   a line with `→` is partly an image, which cannot be selected or searched.

## Requirements

### Quotes

1. `> text` is a quote: a thin bar at its left, its lines indented from the bar, wrapped lines too,
   `>` hidden. Consecutive quoted lines are one block, a line with `>` alone included.
2. In a quote: all the formats of the line, formulas, and lists of every kind, with their nesting
   and their numbers.
3. Everywhere a text goes, the same: the page, the editor, the PDF, the assistant (`MarkdownText`),
   the thumbnails, the flow across pages.
4. **Writing one**: `> ` typed at the start of a line, a button of the bar, a shortcut, the Format
   menu. Enter goes on in the quote, Enter on an empty line leaves it, Backspace at the start of the
   text takes the line out of it, and Tab, the buttons of the lists and the renumbering of
   `docs/list-numbering.md` work inside it.
5. **`>5` stays text**: only `> ` with its space, or `>` alone on a line, is a marker.

### Headings

6. The six levels are told apart, bold from 1.4 down to below the text; the first three keep the
   size they have, so the notes look the same at the levels that already work.
7. A heading stays with what follows it across boxes, blank lines between them included. With nothing
   else fitting it stays where it is: a box always takes something.

### Symbols

8. Arrows, mathematical operators, geometric shapes and check marks come from a bundled font: the
   same shapes and widths on every platform, of the color of the text, never an emoji.
9. An emoji asked for (U+FE0F after the character) and the emoji themselves stay as they are.
10. In a PDF they are real text.

## Design

### Quotes in the notation

`Kind.QUOTE`: from the start of its first line to the end of its last, a run of lines each with up
to 3 spaces, then `> ` or `>` alone.

The offsets must stay those of the source, and the patterns of the items and of the headings want
their marker at the start of a line. So they look for them in a **copy of the same length with the
markers of the quotes blanked**, which they read as indentation. The start of an item in a quote is
then moved past its marker, so that an item never includes the `>` and `ListNumbering`,
`ListRenumbering` and `ListMarker` see an item as anywhere else. The code and the formulas are still
scanned on the source, so a fence in a quote stays text (see "Left for later").

`ListNumbering.breaksList` reads the `>` of an empty quoted line as blank, and a list never goes
from inside a quote to outside it.

### Who does what

| Responsibility | Class | Patterns |
| :--- | :--- | :--- |
| The marker of a quote on a line, the only place knowing it | `BlockQuote` (`drawview-model`, `model.text`) | Information Expert, Pure Fabrication, Protected Variations |
| The spans of the quotes, the items and formats in them | `RichTextParser` | Information Expert |
| The lines of a quote indented, the marker hidden, the bars asked for | `RichTextPresenter` (`PresentedText.quotes`) | Information Expert |
| The rectangles of the bars from a layout | `TextLayoutResult.quoteBars`, beside `codeChips` | Pure Fabrication |
| Drawing them | `ComposeTextTileAdapter`, `TextBoxEditor`, `MarkdownText`, the PDF through the adapter | Polymorphism, Low Coupling |
| The gestures in a quote: Enter, Backspace, `toggleQuote`, the lines of the lists after the marker | `RichTextEditing` | Information Expert |
| The command, its button | `EditorCommands.quote`, `TextFormatTarget.toggleQuote`, `TextFormatBar` | Controller, Indirection |
| The sizes of the headings | `MarkdownTextUtil.headingScale` | Information Expert |
| A heading kept with what follows | `ChainFlow.cutFor` | Information Expert |
| Which characters come from the symbols | `NotesFonts.symbols`, `TextSymbols` (`drawview-model`), pure | Pure Fabrication, Protected Variations |
| The symbols in their font | `RichTextPresenter`, on the text shown | Information Expert |
| The symbols in a PDF | `TextFace.SYMBOLS`, `NotesFontFiles` | Polymorphism |
| The file of the font | `tools/fonts/make_symbols.py`, as `make_struts.py` | Pure Fabrication |

### The look of a quote

A bar 0.15 em wide in the color of the text at 30 %, its text 0.9 em from its left, the text in its
normal color and style. A list in a quote starts from that indent. A bar goes from the top of the
first line to the bottom of the last one, and a quote going on in another box has its bar there too.

### The sizes of the headings

| Level | Today | Plan |
| :--- | :--- | :--- |
| 1 | 1.4 | 1.4 |
| 2 | 1.25 | 1.25 |
| 3 | 1.1 | 1.1 |
| 4 | 1.1 | 1.0 |
| 5 | 1.1 | 0.95 |
| 6 | 1.1 | 0.9 |

All bold. Levels 4 to 6 of an existing note get lower lines, which frees room and pushes nothing
over a page.

### The symbols font

A subset (arrows, operators, geometric shapes, check marks and crosses) of Noto Sans Math and Noto
Sans Symbols 2, both under the OFL, so fine for an application in the stores: 484 characters in 48 KB,
with the ascent and descent of Noto Sans, so that a symbol never makes its line higher. The script
prints what it covers, `TextSymbols` is that list, and a test checks that the file and the list agree.

Compose gives a span one family and does not go through a list of files glyph by glyph, so the
presenter gives the symbols a `SpanStyle` of that font, as it does for the strut fonts. Not inside
code, which has the mono font. The font has one weight: a symbol in bold text stays regular (seen in
`text-saved-symbols`), which reads well enough for arrows and is left as it is.

## What does not change

* **Room, documents, preferences, records**: nothing stored changes, no migration. A note with
  `> ` lines has the same source and a new look, which is the point.
* **The sync protocol**: no frame or message changes, these are characters of a text. An application
  without this work shows `> text` as it does today.
* **Existing boxes**: a chain is cut again only when its text is edited, so the three headings of the
  note stay where they are until then. A line with a symbol may wrap differently, because the symbol
  has new metrics; the heights are laid out, not stored (`docs/text-heights.md`).
* **The public API**: the ABI of `drawview-model`, `-canvas` and `-ui` changes, recorded in the commits.

## Phases

| Phase | Content | Status |
| :--- | :--- | :--- |
| 1 | Symbols: the script and the font, `TextSymbols`, `NotesFonts.symbols`, the presenter, `TextFace.SYMBOLS` and the PDF, the screenshot of `↔`, an emoji left alone, the license listed in the application | done |
| 2 | Headings: the six sizes, `ChainFlow` keeping a heading with what follows, the placement of an import if it cuts the same way, screenshots and tests | proposed |
| 3 | Quotes in the notation: `BlockQuote`, `Kind.QUOTE`, the items in a quote, `ListNumbering`; old texts (`>5`, `a > b`), bounds of the parser | proposed |
| 4 | Quotes drawn: presenter, bars, page, editor, PDF, assistant, flow across boxes; the search and the copy checked; screenshots of the 12 quotes of the note | proposed |
| 5 | Writing quotes: Enter, Backspace, Tab, the lists inside, `toggleQuote`, button, icon, shortcut, menu, the strings in 7 languages; the desktop app on a scratch directory | proposed |

Phases 1 and 2 stand alone; 4 needs 3, and 5 needs 4. Each one is a commit with its status here.

## Decisions

1. **A bar, without italics or a background**: the quotes of the note are long and hold code in the
   line, and a background would compete with the one of the code. Revertible by a constant.
2. **`> ` with its space**, stricter than CommonMark (`>text` is a quote there): `>5` and `>=` at the
   start of a line are common in notes. Revertible.
3. **A blanked copy for the patterns**, rather than parsing each quote apart: the offsets stay those
   of the source and the items stay what they are for everyone reading them.
4. **The marker hidden even with the caret on it**, as for the list items, not revealed as for the
   headings: Backspace takes it away, and there is nothing to edit in it.
5. **One symbols font**, rather than taking arrows from the mono font: it has no check marks, and its
   arrows are as wide as a column of code, large in a line of text.
6. **The shortcut** ⌘⇧. is taken by a larger text, so ⌘⌥Q, to be checked against the others in phase 5.
7. **The first three heading sizes stay**: a note must not change where it already works.

## Not in this plan

- **The colors of `__asm__` and `__volatile__` in C**: excluded by the user. Adding the words one case
  at a time neither extends nor works well; the highlighting needs its own thinking, if ever.
- **The two narrower boxes on page 7 of the note**, not linked to each other: a state of that note,
  to be looked at by the user, not a defect of the application as far as known.

## Left for later

- Quotes nested (`> > text`): the second `>` stays text. A block of code or a table in a quote: its
  lines stay text, as the note has none. A heading in a quote. A quote inside a list item.
- A heading kept with the first lines of a long paragraph, and an introduction ending with `:` kept
  with its block (page 4 of the note): only headings for now.
- The `\text{}` of the formulas, serif on the page and Noto Sans in the PDF (`docs/export.md`, read).
- A choice of the heading level in the bar: the button only makes a level 1 today.
- Symbols in a block of code that the mono font lacks (`✔`): from the system, as today.
