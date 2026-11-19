# Text boxes: lists, tables, backgrounds, boxes across pages

Four improvements to the text boxes asked by the user on 2026-10-31: better lists (their bullets,
nested numbered lists, Shift+Enter staying in the item), tables, a background for a box, and a
text that goes on from one page to the next. This document starts from the code as it is (main at
797897e2, 2026-10-31), then gives the requirements, the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The notation.** A text is Markdown with formulas, parsed by `RichTextParser`
   (`drawview-model`) into `Span`s. A list item (`Kind.LIST_ITEM`) is **one source line**
   (`BULLET_LIST`, `NUMBERED_LIST`: `^([ \t]*)([-*+]|\d+\.)[ \t]+(.*)$`), its level the leading
   spaces / 2 (`indentLevel`), its `number` the digits written. There are no tables.
2. **The presentation.** `RichTextPresenter` (`drawview-ui`) builds the text of the field of the
   editor and of the canvas from the spans. A list item shows `MarkdownTextUtil.listPrefix`
   instead of its marker: two spaces per level, then `•`, `◦`, `▪` (by level, as **glyphs of the
   font**) or the number as written (`1. 1. 1.` shows `1. 1. 1.`, every level `1.`), its wrapped
   lines indented by `TextIndent(restLine = prefixWidth)`. The width of a prefix is estimated
   (`listPrefixWidthPx`: 0.55 em a digit) unless the presentation measures it. A block of code is
   already an object taking its lines (`CodeBlockLayout`, drawn by `drawChrome`), written in the
   field when the caret is in it: the model for a table.
3. **The editing.** `RichTextEditing` (`drawview-canvas`): `toggleBulletList`,
   `toggleNumberedList`, `handleEnter` (Enter continues the item, ends the list on an empty item),
   `handleSmartNewline` (the same for a newline typed by a soft keyboard), `indent` / `outdent`
   (Tab, Shift+Tab: two spaces). In `TextBoxEditor`, **Shift+Enter inserts a bare `\n`**
   (`lastKeyWasShiftEnter`): the next line has no marker and no indent, so it is a paragraph out
   of the list, drawn at the left edge, not under the text of the item.
4. **The box.** `DrawText` keeps its corner (`x`, `y`), the width its lines wrap on (`width`,
   `autoWidth`), rotation, content, alignment, color, size; its height is laid out, never stored
   (`docs/plans/text-heights.md`). `TextData` is what changes, the operations log (JSON) and the sync
   (CBOR, `PROTOCOL_VERSION` 1) carry; the table `texts` keeps it (Room 22). There is no fill.
5. **A text longer than its page.** The tiles of a page draw only that page: the lines of a text
   below the bottom of its page are cut. While written, the box may grow under the page
   (`TextEditorManager.reportLayoutHeight`; `moveTo` keeps `MIN_VISIBLE_MM` on the page). A long
   text pasted or imported is flowed by `TextFlow` (`drawview-model`) into **independent** texts
   on the following pages (`TextPlacer`): editing one of them never moves its lines to the next.
6. **Formatting.** The format commands (`EditorCommands`, Format menu, shortcuts, the bar
   `TextFormatBar`) act on a `TextFormatTarget`: the text being written or the texts selected.

## Requirements

### Lists

1. **Bullets the same everywhere**: a disc, a circle, a square by level (repeating), from a
   bundled font, never from a fallback of the system, without making their line higher.
2. **Nested numbered lists**: numbers by level `1.` → `a.` → `i.` (repeating), counted by the
   position of the item among its siblings from the number of the first one (CommonMark), so that
   `1. 1. 1.` shows `1. 2. 3.`; the source keeps the digits.
3. **One column for the markers**: the numbers right-aligned in it (`9.` and `10.` start their text
   at the same place); a nested item indented to the text of its parent.
4. **Shift+Enter stays in the item**: a new line inside the item, without a marker, aligned with
   its text. Enter on it starts the next item; Backspace at its start joins it back.
5. Existing notes look as they did except for the improvements above: the notation does not change.

### Tables

6. **A table in a text**, written in the GitHub notation (`| a | b |`, a delimiter row
   `|---|:-:|---:|`), drawn as a grid: a header row in bold, column alignments, thin rules, cells
   wrapping their text, inline formatting and formulas in cells.
7. **Always a grid**, also while written: the caret moves from cell to cell (Tab / Shift+Tab,
   Enter to the cell below, a new row from the last one, the arrows out of the table at its
   edges); a tap goes to the cell tapped.
8. **Commands**: insert a table (a size picker), add a row above / below, a column left / right,
   delete a row, a column, the table, the alignment of a column; in the Format menu, the bar and
   with shortcuts.
9. Everywhere a text goes: canvas, editor, PDF export, search (the text of the cells), the flow
   of long texts (a table not broken between two pieces unless taller than a page).

### Backgrounds

10. **A fill color for a box**: none by default; chosen from a palette of tints (and the custom
    color), with an inner margin and rounded corners, for the text written and the texts
    selected.
11. The fill is part of the text: the lasso, the hit test and the tiles take it; the frame and
    its handles stay on the box of the lines.

### Boxes across pages

12. **A text goes on to the next page**: when a box reaches the bottom margin of its page, its
    next lines go into a box linked to it on the next page (created with the page, when missing),
    at the same horizontal place and width, from the top margin.
13. **Editing reflows the chain**: lines added push the following lines on, lines removed pull
    them back, through all the boxes linked; a box emptied at the end of a chain is removed.
14. **The caret follows the text**: typing at the end of a page goes on in the next box; the arrows
    and Backspace cross the boundary.
15. **One text for the user**: the style (size, color, alignment, background) applies to the whole
    chain; one change to undo; the chain marked when a box of it is selected or edited.
16. The texts pasted or imported long go into linked boxes.
17. **Untrusted input**: a link that is a cycle, or names a box that is not a text of the document,
    is read as no link; a reflow never changes a box another member owns beyond what the rules of
    `docs/plans/untrusted-members.md` allow.

### For all

18. **The notes of the user are kept**: every stored change is a Room migration tested with
    `MigrationTestHelper`; no destructive fallback.
19. **The protocol changes** (the background and the link travel in `TextData`): said, the
    format test re-recorded, the version agreed with the user.

## Design

### Lists

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The structure of a list: items with their continuation lines, levels, siblings | `RichTextParser` (`drawview-model`): a `LIST_ITEM` span covers its continuation lines (lines indented to its text, not a marker themselves) | Information Expert |
| The label of an item: its ordinal among its siblings, the style of its level (`1.`, `a.`, `i.`, disc, ring, square) | `ListNumbering` (`drawview-model`, new, pure): `labelOf(item, spans)` → `ListLabel` (`Bullet(shape)` / `Ordinal(text)`) | Pure Fabrication, Information Expert |
| Lay the label out: in a column, right-aligned, the text and the continuation lines indented at the text of the item | `RichTextPresenter` (`drawview-ui`): the label as text between two struts (the room before it in its column, the gap after it), the bullets in the monospace font of the notes | High Cohesion |
| Shift+Enter in an item, Enter on a continuation line, Backspace joining it | `RichTextEditing.lineBreakInItem`, `handleEnter`, `handleBackspace` (`drawview-canvas`); `TextBoxEditor` calls them | Information Expert, Controller |

The search (`SearchText`) and the assistant read the source, so they are unchanged.

### Tables

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The notation of a table: rows, cells (their source ranges), alignments of the columns | `RichTextParser`: `Kind.TABLE` with `TableShape` (`drawview-model`) | Information Expert |
| The edits of a table on the source: insert, add / delete rows and columns, align, move to a cell, keep the pipes aligned in the source | `TableEditing` (`drawview-canvas`, pure, on `TextFieldValue`) | Information Expert, Pure Fabrication |
| Lay a table out: column widths (from the content, fitting the line), cells wrapped, row heights, rules | `TableLayout` (`drawview-ui`), an object of the presenter like `CodeBlockLayout`, for the canvas, the field and the export | High Cohesion, Protected Variations |
| The grid while written: the table stays an object of the one field, its caret and selection drawn in the cell of the caret, the keys typed in the source of that cell (one `TextFieldValue`: one undo history, one draft) | `TextBoxEditor` with `TextEditorManager.tableCaret` (`drawview-ui`, `drawview-canvas`) | Indirection, Protected Variations |
| The commands and the table bar shown with the caret in a table | `EditorCommands` (`insertTable`, `addRowAbove`, …) on `TextFormatTarget`; `TableBar` | Controller |
| Keep a table whole when a long text is flowed | `TextFlow.blocksOf` | Information Expert |

A table is chosen as notation inside the text (decided by the user): no new element, no change
of what is stored or sent. A cell holds inline notation only (no line breaks, no lists, in
GitHub's notation as well); the source keeps one line per row.

### Backgrounds

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The fill of a box: its color or none | `DrawText.background: Int?`, `TextData.background` (`drawview-model`) | Information Expert |
| Its geometry: inner margin, corner radius, the bounds it adds | `TextBackground` (`drawview-model`): `PADDING_EM`, `RADIUS_EM`, `outsetMm(fontSize)`; `DrawText.getRotatedBounds` includes it | Information Expert |
| Kept and migrated | `TextEntity.background`, Room 23 (`MIGRATION_22_23`, a nullable column) | Protected Variations |
| Drawn under the text | `ComposeTextTileAdapter`, `TextBoxEditor`, `PdfExporter` | Polymorphism |
| Chosen | `TextFormatTarget.setBackground`, the command `textBackground` and its palette in `TextFormatBar` and the selection bar | Controller |

The fill goes round the box by an inner margin proportional to the size of the font, so that
the text keeps its place when a fill is added; the palette offers light tints of the colors of the
pens, readable under black text.

### Boxes across pages

The boxes of a chain keep their own text (a part of the whole): the search, the export, the
tiles, the sync of a single text and its conflicts work as they do. A box knows the next one.

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The link: the box this one goes on into | `DrawText.next: String?`, `TextData.next` (`drawview-model`); Room 24 (`MIGRATION_23_24`) | Information Expert |
| Read the chains of a document: from its first box, links to texts of the document only, cycles cut | `TextChains` (`drawview-model`, pure) | Information Expert, Protected Variations |
| Split a whole text into the rooms of the boxes: between lines; a line taller than an empty box between words (the next box marked `continuesLine`, its inline formatting closed and opened again); a table between rows | `ChainFlow` (`drawview-model`, pure, with `heightOf` like `TextFlow`) | Pure Fabrication |
| Reflow while written: when the lines of the box written go below the margin of its page, the chain is saved flowed and the editor opens again on the box of the caret; on closing, the chain flowed again from the box written (lines taken away pull the next ones back) | `TextEditorManager.flowChain` (`drawview-canvas`) | Controller, Information Expert |
| Commit: the texts of the chain changed, boxes and pages added or removed, in one `CompositeChange` | `TextEditorManager.finishEditing` | Creator |
| The style of the chain: applied to every box | `TextFormatTarget` for a chain | Polymorphism |
| The mark of a chain on the frame (at the bottom of a box that goes on, at the top of one that continues) | `SelectionFrame` | High Cohesion |
| Long texts placed into linked boxes | `TextPlacer` with `ChainFlow` | Information Expert |

The room of a box of a chain goes from its corner to the bottom margin of its page
(`TextRoom.PAGE_MARGIN_MM`). A box not in a chain behaves as today until it reaches that margin
while written; then it becomes the first box of a chain. The whole text of a chain is its parts
joined with a line break (`\n`), or without one for a box marked `continuesLine`.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | Lists, structure and look: continuation lines in the parser; `ListNumbering` (ordinals by position, `1.` / `a.` / `i.`, bullets by depth); labels in a column, numbers right-aligned, nested items at the text of their parent; tests of the parser and the numbering, screenshots of nested lists | done |
| 2 | Lists, editing: Shift+Enter a line in the item, Enter on a continuation line, Backspace joining, the toggles and Tab on items with continuation lines; tests of `RichTextEditing` and of the field | done |
| 3 | Backgrounds, stored: `DrawText.background`, `TextData`, Room 23 with its migration test, the operations log, the sync (the format test re-recorded, protocol to agree), the bounds with the margin | done |
| 4 | Backgrounds, drawn and chosen: tiles, field, PDF export; the palette in the format bar and the selection bar; screenshots | done |
| 5 | Tables, notation and drawing: `Kind.TABLE`, `TableLayout` on the canvas, in the field and in the export; the search; tests and screenshots | done |
| 6 | Tables, editing: `TableEditing`, the caret drawn in its cell (Tab, Enter, arrows, taps), the insert command with its size picker, the menu of the table, the Format menu and a shortcut; tests and screenshots | done |
| 7 | Chains, stored: `DrawText.next`, `continuesLine`, Room 24 with its migration test, the sync (format test, protocol), `TextChains` with untrusted links (cycles, missing boxes) | done |
| 8 | Chains, flow: `ChainFlow`; reflow while written, the caret across boxes, the next box and page created, emptied boxes removed, one change; the style on the chain; tests of the flow and of the editor | done |
| 9 | Chains, around: the mark on the frame, the lasso on a box of a chain, long texts pasted and imported into chains, the export; screenshots; the desktop app on a scratch directory | done |

Stages are independent: lists (1-2), backgrounds (3-4), tables (5-6), chains (7-9); the order
above goes from what needs no stored change to what needs the most.

## Results

### Phase 1

* `RichTextParser`: a `LIST_ITEM` takes its continuation lines (`withContinuations`: the next lines
  not blank, indented at least to the text of the item, not an item nor in a block of code); the
  lookups of the blocks are binary searches, the parse stays linear (`RichTextParserBoundsTest`).
* `ListNumbering` (`drawview-model`, new): the parent of each item by the indent of its marker, the
  lists broken by a line of other text (not by a blank line nor a block under an item), the
  ordinal from the first number, `1.` / `a.` / `i.` by the numbered items above, the bullets by
  depth (`ListNumberingTest`).
* `RichTextPresenter`: the label between two struts (its room in the column of its list, its widest
  label, and a gap of 0.4 em), the text at `textLeftPx`, a nested item at the text of its parent,
  the continuation lines at the text of their item (their spaces hidden), the blocks of code under
  an item at its text. `MarkdownTextUtil.bulletGlyph`, `listPrefix`, `listPrefixWidthPx` removed.
* Screenshots: `text-saved-nested-lists` (new), the lists of `markdown-text`, `text-import`,
  `lecture-outline`, `text-saved-code-list` recorded again (the labels in their column).

### Phase 2

* `RichTextEditing.lineBreakInItem` (Shift+Enter in an item: `\n` and the spaces of the column of
  its text, the spaces after the caret dropped); `handleEnter` on a continuation line starts the
  next item (the whole line when the caret is at its start); `handleConcealedDeletion` joins a
  continuation line back on Backspace at its text; `keepCaretOutOfMarkers` keeps the caret out of
  its hidden indent; the toggles of the lists take whole items (a continuation line goes to the
  column of the new marker, or to the indent of its item out of the list); Tab and Shift+Tab move
  an item with its lines.
* `ListNumbering.textColumnOf`, `continuationIndentAt` (public: the editing is in `drawview-canvas`).
* `TextEditorManager.lineBreakInItem`, called by the field on Shift+Enter; out of the items Shift+Enter
  stays a plain line break.
* Tests: `RichTextEditingTest` (five new), the field driven by the keys in
  `text-list-line-break` (explorer, a screenshot and the text it writes).

### Phase 3

* `DrawText.background` (ARGB or `null`), `backgroundOutset`; `getRotatedBounds` and
  `containsPoint` take the fill; `TextData.background`, part of the content (`hasContentOf`,
  `writeContent`, `applyTo`, `toData`); `TextBackground` (`drawview-model`): margin and radius
  0.35 em, the seven tints.
* Room 23: `texts.background` (nullable), `Migration22To23` tested on a database of version 22 with
  a text and an operation of the log written before the fill (`DrawDatabaseMigrationTest`); the
  round trip of the repository keeps it (`DrawDatabaseTest`).
* **The protocol changes**: `TextData` has `background` (optional, not sent when `null`), recorded
  in `ProtocolFormats` under version 1 (decision 15). A device of the previous format reads the
  texts without a fill and fails on one with a fill.
* `TextFormatTarget.takesBackground`, `background`, `changeBackground` (the editor: its draft and
  the presence carry the fill; the selection: one `UpdateTexts`), tested in `TextEditingTest`.

### Phase 4

* Drawn under the lines, rounded: on the tiles (`ComposeTextTileAdapter.drawRichText`), as the
  first background of the layout given to the PDF export (`layout`), under the field of the
  editor (`TextBoxEditor`); the drafts of the other devices carry it (`applyTo`).
* `TextBackgroundButton` (`TextStyleControls`): a box showing the fill, its popover with none, the
  seven tints and the color wheel; in the bar of the editor and of the selection, not in the
  settings of the tool (`takesBackground`). Strings in the seven languages.
* The floating bar of the editor keeps its button closing the editor in sight, its controls
  scrolling, and a gap on each side (it went out of a tablet held upright, already before this
  phase, more so with one button more).
* Tests: the fill in the layout of the export (`ComposeTextTileAdapterTest`); screenshots
  `text-backgrounds` (desktop, phone, tablet), the bars of the editor and of the selection
  recorded again.
* No Format menu entry: a fill is a color to pick, as the color of the text, which has none.

### Phase 5

* `RichTextParser`: `Kind.TABLE` (a header row with a `|`, a delimiter row of as many cells with a
  `|`, the rows with a `|` up to a blank line), looked for in the masked text (a `|` in code or
  escaped is text), each line searched once (`RichTextParserBoundsTest`); the formatting in the
  cells parsed as elsewhere.
* `TableShape` (`drawview-model`, new): the cells of each row (the header first, as many as the
  header), the alignments of the columns, the lines of the rows, the cell of an offset
  (`TableShapeTest`).
* `TableLayout` (`drawview-ui`, new): each cell presented by `RichTextPresenter` (its formatting,
  its formulas), the header in bold on the faint background of the code, the columns as wide as
  their content when the line has room, else the narrow ones keeping theirs and the others sharing
  the rest (a column never narrower than its widest formula), the alignments, thin rules
  (`TableLayoutTest`). `Presentation` gets `measureText` and `paperColor`; a table is an object of
  the presenter taking its lines, drawn by the tiles, the field of the editor and `MarkdownText`;
  the export gets its backgrounds, rules, words and formulas.
* The search keeps the words of the cells, without the pipes and the delimiter row
  (`SearchTextTest`).
* `TextFlow.blocksOf` needed nothing: a table has no blank line, it is one block.
* Screenshots: `text-tables` (desktop, phone, tablet: saved and being written).

### Phase 6

* `TableEditing` (`drawview-canvas`, new, pure): the table and the cell of the caret; inserting a
  table on lines of its own (a line after it, to leave it); Tab and Shift+Tab from cell to cell (a
  row added after the last cell), Enter to the cell below, the arrows by a character in a cell and
  across cells and out of the table; the rows, columns, alignments and the deletions, the table
  written again one row a line; `keepInCells`: a `|` typed or pasted escaped, a line break pasted
  a space, Backspace and Delete never taking a `|` (`TableEditingTest`).
* The caret is never a separate field: the table stays one object of the field, whose cursor is
  hidden while the caret is in a table; the caret (or the selection) and an outline of the cell
  are drawn in the cell, the cell of the caret shows the source it touches (a formula being
  written). A tap on a cell puts the caret on the character tapped. The keys typed go to the source
  of the cell as in any text. Headings, lists and blocks of code are not applied in a table.
* `TextFormatTarget.inTable`, `insertTable`, `applyTableAction`; `TextEditorManager.tableCaret`,
  `tableNextCell`, `tableArrow` (Tab is `indent`, Enter `handleEnter`).
* Commands: `format-table` (⌘⌥T, 3 × 3) and the ten commands of the table in the third section of
  the menu Format; in the bar, the button of the table: out of a table a grid of 8 × 8 choosing the
  size, in a table the menu of its commands (`TableButton`). Strings in the seven languages.
* The presenter: an object (a table, a block of code) followed by the last line break of the text
  ends its paragraph, the empty line after it is a paragraph of its own: it took the height of the
  object (`theEmptyLineAfterATableIsALineOfTheText`).
* Screenshots: `text-table-written` (written with the keys, its text checked), `text-table-touched`
  (the caret on the character tapped), `text-table-menu`; the bars recorded again (one button
  more; on a tablet held upright the controls scroll, the button closing the editor in sight).

### Phase 7

* `DrawText.next` (the text it goes on into) and `continuesLine` (its first line goes on the last
  line of the text before it); in `TextData`, part of the content (`hasContentOf`,
  `writeContent`).
* Room 24: `texts.next` (nullable), `texts.continuesLine` (0 by default), `Migration23To24` tested
  on a database of version 23 with a text with a fill (`DrawDatabaseMigrationTest`).
* **The protocol changes again** (`next`, `continuesLine` optional in `TextData`), recorded under
  version 1 (decision 15).
* `TextChains` (`drawview-model`, new): the chains of a document from the links, a link to a text
  not in the document, a cycle or a text another one takes already read as no link; the whole text
  of a chain (`joined`) (`TextChainsTest`).

### Phase 8

* `ChainFlow` (`drawview-model`, new, pure): the whole text cut into the rooms of the boxes (from
  their corner to the bottom margin of their page), more boxes as needed; cut between lines, never
  in a block of code, a table, a display formula or a list item with its lines; a line taller than
  a new box cut between its words (`continuesLine`); a numbered list cut between two boxes goes on
  with its number (`ListNumbering.Item.ordinal`) (`ChainFlowTest`).
* `TextEditorManager`: the draft keeps `next` and `continuesLine`; after each change of the text,
  lines below the bottom margin of the page flow into the chain (`flowIfOverflowing`): the box
  written, the boxes after it, the new boxes on new pages (inserted after the last box, of its
  format, at its top margin, at the left and width of the box written) and the boxes left empty
  removed, in one `CompositeChange` (the new pages included: `PageManager.newPagesAfter`); the
  editor opens again on the box of the caret, its page shown. Closing the editor flows the chain
  again from the box written. The style of the box written goes to the whole chain.
* At the ends of a box of a chain the arrows go into the box before or after, Backspace at its
  start joins its first line to the last line of the box before (`moveInChain`,
  `backspaceIntoPreviousBox`, called by the field).
* Tests: `TextChainEditingTest` (a text going below its page, lines pulling back, the style, the
  caret across the boxes, one step to undo).
* A turned text, or one moved to another page while written, is saved alone (no flow).

### Phase 9

* The frame of a text of a chain, written or selected, has a chevron on the edge it goes on from
  and on the one it goes on into (`SelectionFrame.chainedAbove`, `chainedBelow`,
  `TextEditorManager.chainComesFrom`, `chainGoesOn`).
* A long text imported (`TextPlacer`): a piece going on at the top of a new page is chained to
  the piece before it (`EditorCoreTest`).
* **A field of an ended session sends nothing**: after a flow the editor opens on another box, and
  the field of the box before could still send its text into it (found by the screenshot of a
  chain: the second box took the whole text). The field keeps the session it was made for and its
  changes from another session are dropped.
* The lasso moves a box of a chain alone (its link kept); the export draws each box with its part:
  nothing to change.
* Screenshot `text-chain-written` (the text written below its page going on at the top of a new
  one, the box marked, its text checked).
* Checked: all the JVM tests, the ABI, Android and iOS builds, the tests of the sync modules and of
  Room on the iOS simulator, the screenshots, the consumer checks; PencilNotes desktop started on
  a scratch directory (its database created at version 24).

## Decisions

Taken by the user on 2026-10-31:

1. Tables are notation inside a text (GitHub), shown as a grid also while written; not a new
   element.
2. A text longer than its page goes on into linked boxes on the next pages, reflowed when edited.
3. Nested numbered lists are labeled `1.` → `a.` → `i.`.
4. The background of a box is a fill color with an inner margin and rounded corners, no border.

Taken alone, revertible:

5. **Bullets in the monospace font of the notes** (Noto Sans Mono), not drawn as shapes: Noto Sans
   lacks `◦` and `▪` (they came from a fallback font of each system), Noto Sans Mono has the three
   with the vertical metrics of Noto Sans, so a line keeps its height; as text they reach the PDF
   export with no new primitive. In another font (the panels of the application) they are made
   smaller when its lines are lower. The circle and the square sit a little lower than the disc:
   raising them would make their lines higher.
6. **The ordinal from the position** (CommonMark): the source keeps `1. 1. 1.` and shows `1. 2. 3.`;
   the number of the first item is kept as the start.
7. **Cells without line breaks** (as in GitHub's notation); a long cell wraps.
8. **The caret of a table is drawn by the editor** in the one field (not a field per cell): the
   input methods, the undo and the drafts stay those of the text.
9. **The parts of a chain are texts of their own** with a link to the next, not one text shared by
   several boxes: the sync, the search, the export and the conflicts of a text stay as they are.
10. **The room of a box of a chain ends at the bottom margin of its page**, not at a height set by
    the user.
11. **The chain is saved when the text flows**, not kept as drafts of the other boxes while
    written: the editor opens again on the box of the caret. Each flow is a step to undo (the
    writing before it in the same box with it).
12. **A new box goes on a new page**, inserted after the last box of the chain, never on a page
    that has its own content (which a box at its top could cover).
13. **The fill is outside the box** by a margin proportional to the font size, so that adding it
    does not move the text.
14. **The two stored changes are two Room versions** (23 for the background, 24 for the chain),
    one per stage, each with its migration.
15. **The protocol stays 1** for both, as on `feat/untrusted-members`, the two devices of the user
    updated together (the plan approved by the user on 2026-10-31 said so; to raise it, change
    `PROTOCOL_VERSION` and record the format under the new number).
16. **A long text imported is chained where it goes on onto a new page**; its pieces in the free
    space of a page with content stay texts of their own (their room would cover what is below).

## Left for later

- A height of a box of a chain set by the user (a box that stops above a figure).
- Merging and splitting cells, widths of the columns set by hand, tables pasted from HTML.
- A line break in an item typed on a soft keyboard (no Shift): a key of the bar, if needed.
- Checklists (`- [ ]`), still left for later from `docs/plans/text-editing.md`.
- Tables in the notes of the lecture assistant (its prompts).
- The boxes of a chain while another box is written: drawn as they are until the text flows.
- The pieces of an imported text are joined with a blank line; chained, they are joined by one
  line break when the chain flows again.
