# Code blocks

The code in the texts of the pages: blocks of code fenced as in Markdown, drawn as code on the
page (monospace, on a background, with the colors of their language), the code in the line drawn
as code too, written in the editor as the page shows them and exported as they are drawn. This
document starts from the code as it is (2026-10-22): what the texts do with code today, then the
design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Notation.** `RichTextParser` (`drawview-model`, `model.text`) knows the code in the line
   only: `` `…` `` on one line, scanned with the escapes and the formulas before the other
   notation, so that its content is written as it is (`scanVerbatim`). There is no block of code:
   a text with ```` ```kotlin ```` shows its backticks, and the lines inside are read as notation
   (`# comment` of Python is a heading, `- x` a list item, `*p` an italic, `$x$` a formula).
2. **Drawing.** `MarkdownTextUtil.contentStyle` gives the code the monospace of the notes
   (`NotesFonts.monospace`, Noto Sans Mono, regular and bold) at the size of the text, and nothing
   else: no background, no padding. The editor (`toEditorText`) and the canvas (`toCanvasText`)
   style it the same way.
3. **Layout.** `ComposeTextTileAdapter` lays out a text as one `TextLayoutResult` on the width of
   its box; the formulas are placeholders, their images drawn after the text, a formula alone on
   its lines a placeholder of the whole line (centered). The thumbnails of the library, the
   lecture panel and the export dialogs build the same adapter.
4. **Editor.** `TextBoxEditor` is a `BasicTextField` with a visual transformation
   (`toEditorText`): the delimiters away from the caret are concealed, every formula away from the
   caret stands as one transparent character with the room of its image, drawn over it in
   `drawWithContent`. Tab and ⇧Tab indent the lines by 2 spaces (lists), Enter continues a list
   (`RichTextEditing.handleEnter`, `handleSmartNewline`).
5. **Commands.** "Code" (⌘E) wraps the selection in backticks; there is no command for a block.
6. **Export.** `PdfExporter` writes the words of `TextBlockLayout` with the font of their face
   (`TextFace.MONO` and `MONO_BOLD` included) and their own color, and the formulas as vectors;
   it fills nothing behind a text. The PNG export draws through the adapter.
7. **Copy.** `SelectionManager.selectedPlainText` copies the texts with their notation.
8. **Lecture assistant.** The rule of the Markdown given to the models (`LecturePrompts.MARKDOWN_RULE`)
   and to the reading of the handwriting do not mention code; `LectureAnalysis` strips the fence
   around an answer written as a fenced JSON.
9. **Heights.** Nothing measures a text again when its document opens: a change of how texts are
   drawn leaves the stored height of the texts it touches stale (frame, hit test, lasso) until
   they are edited.
10. **Untrusted input.** The texts come from the other devices too, and are parsed on the threads
    of the tiles and, in the editor, at every key on the main thread. The parsing is linear in the
    length of the text since [linear-parsing.md](./linear-parsing.md) (in `main` since
    2026-10-22): the emphasis found by a scan, the nesting with a stack, the formulas walked once.
    The blocks and their highlighting keep it so.

## Requirements

1. Blocks of code fenced as in CommonMark (```` ``` ```` or `~~~`, with the name of their
   language), their content written as it is: no notation inside.
2. Readable on paper: monospace, on a background, with padding; the long lines wrapped keeping
   their indentation; the tabs, the spaces and the empty lines kept; aligned at the start whatever
   the alignment of the text.
3. The colors of the syntax of the common languages, the same on every platform; a language not
   known is drawn in one color.
4. The code in the line visible as code (a background of its own).
5. The editor shows a block as the page will, the caret lands where the user taps, and writing
   code is helped (indentation, the fence closed, Tab).
6. The PDF export keeps the code searchable and selectable, its colors and backgrounds as vectors;
   the PNG export is drawn as the canvas.
7. No change to the stored formats nor to the protocol: the code lives in the notation of
   `DrawText.text`. The texts already written keep their content; their heights are made right.
8. The text of another device never blocks nor fails the application: the work on the notation
   is linear in the length of the text and bounded.
9. The lecture assistant writes the code it notes in fenced blocks.
10. The rules of the libraries: the notation and the highlighting are headless (`drawview-model`),
    the highlighter is an instance given to the adapter, the public API is recorded.

## Design

### The experience

* **On the page.** A block takes the width of the box, on a background with rounded corners and
  padding, the name of its language small in its top-right corner. Its lines wrap at the width
  of the box, a wrapped line continuing under the start of its code (its indentation kept). The
  code is drawn at 0.9 times the size of the text (the monospace looks larger at the same size).
* **Colors.** Keywords, types, literals, strings, numbers, comments, annotations, function names
  and keys in the colors of a palette; identifiers and punctuation in the color of the text. The
  palette follows the color of the text: a dark text has the palette made for light paper, a
  light text the one made for dark paper. The background is the color of the text at 7 %, so that
  it reads on every paper and in every color of text.
* **In the line.** The code in the line on the same background, its corners rounded, reaching a
  little beyond the code on each side.
* **In the editor.** A block away from the caret is drawn as on the page; with the caret in it,
  the block shows its source (its fences faded, its code in the colors and on the background), as
  a formula under the caret does. A tap on a block puts the caret on the tapped character.
* **Writing code.** Inside a block, Enter keeps the indentation of the line and continues no
  list; Tab and ⇧Tab move the lines by 4 spaces (with no selection, Tab writes spaces up to the
  next multiple of 4); the formats of the characters, the headings and the lists do nothing. A
  fence typed on a line of its own followed by Enter is closed below the caret. "Code block" (⌘⌥C,
  the Format menu, the bar) fences the lines of the selection, or removes the fences of the block
  of the caret; ⌘E on a selection of several lines makes a block.

### The notation

* A block opens with a line of at least three backticks or tildes (indented by up to 3 spaces)
  and closes with a line of the same character, at least as long, with nothing else on it. What
  follows the opening fence is its information; its first word is the language (`kotlin`,
  `py`, …).
* The content is written as it is: the block is scanned with the code, the escapes and the
  formulas, before the other notation, which is not looked for inside it.
* A fence never closed runs to the end of the text (CommonMark); the editor closes the fences it
  sees typed.
* The blocks are at the top level of the text: not inside a list item (see
  [Left for later](#left-for-later)).
* The code in the line keeps its rules.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The notation of the blocks: fences, language, content, a fence not closed; scanned once, linearly, before the other notation | `RichTextParser` with `Kind.CODE_BLOCK` and `Span.language` (`drawview-model`, `model.text`) | Information Expert, Pure Fabrication |
| The tokens of a code in a language: one linear pass, no regular expression; the languages, their names and aliases | `CodeHighlighter` (the port) and `BuiltInCodeHighlighter` with a `CodeGrammar` per family of languages (`drawview-model`, `model.text.code`) | Pure Fabrication, Protected Variations |
| The highlighter given to whatever draws texts, replaceable by the application (e.g. a tree-sitter engine) | `DrawViewEngines.code` (`drawview-canvas`), `BuiltInCodeHighlighter` by default | Indirection, Low Coupling |
| The colors of the tokens and of the backgrounds for the color of a text | `CodeTheme` (`drawview-ui`, `ui.render`) | Information Expert |
| Lay out a block: the code in its colors, at its size, wrapped on the width less the padding with its indentation kept, its background, its label; its size and natural width; the character under a point; its words and boxes for the export | `CodeBlockLayout` (`drawview-ui`, `ui.render`), one for the canvas and the editor | Information Expert, High Cohesion |
| Present the notation: a block as a placeholder of its line on the canvas, as a concealed character drawn over (away from the caret) or as its colored source (under the caret) in the editor; the backgrounds of the code in the line | `MarkdownTextUtil.toCanvasText`, `toEditorText` (`drawview-ui`) | High Cohesion |
| Draw, measure and lay out a text with its blocks and the backgrounds of its code | `ComposeTextTileAdapter` (`drawview-ui`) with `CodeBlockLayout` | Information Expert, Protected Variations |
| The backgrounds of a text for the export | `TextBlockLayout.boxes` (`TextBox`: rectangle, corner radius, color; `drawview-canvas`) | Information Expert |
| Write the backgrounds in the PDF, under the words | `PdfExporter.TextWriter` (`drawview-export`) | Information Expert |
| Editing in a block: indentation on Enter, Tab and ⇧Tab, the fence closed, the block toggled, the formats not applied | `RichTextEditing` (`drawview-canvas`, `canvas.text`), reached by `TextEditorManager` | Information Expert, Controller |
| The command of the block, its shortcut, its place in the Format menu and in the bar | `EditorCommands.codeBlock`, `TextFormatBar`, `DrawViewIcons.CodeBlock` (`drawview-ui`) | Controller, Polymorphism (target) |
| The heights of the texts drawn differently: measured again when their document opens | `TextEditorManager` or a pass of `DrawViewController` on load (phase 2 decides with the count, see [Decisions](#decisions)) | Information Expert |

### Highlighting

* **Tokens.** `CodeToken(kind, start, end)`, the kinds `KEYWORD`, `TYPE`, `LITERAL` (`true`,
  `null`, `None`), `STRING`, `NUMBER`, `COMMENT`, `ANNOTATION` (annotations, decorators,
  preprocessor lines, Rust attributes), `FUNCTION` (a name before `(`), `PROPERTY` (keys of JSON
  and YAML, variables of the shell). What is no token is drawn in the color of the text.
* **Grammars.** A `CodeGrammar` is data: its line comments, its block comments (nested or not), its
  strings (delimiters, escapes, raw and multi-line forms, templates), its keywords, types and
  literals, whether case matters. One per family:
  * Kotlin, Java, C, C++, C#, JavaScript, TypeScript, Swift, Go, Rust (the family of C, each with
    its words and strings);
  * Python;
  * shell (`sh`, `bash`, `zsh`);
  * SQL (case not mattering);
  * JSON, YAML.

  The names and their aliases (`kt`, `kts`, `py`, `js`, `jsx`, `ts`, `tsx`, `c++`, `cpp`, `h`,
  `cs`, `rs`, `golang`, `yml`, …). A block with no language or one not known has no tokens.
* **Bounds.** The lexer moves forward at every step and reads every character once or twice; a
  block longer than `CodeHighlighter.MAX_HIGHLIGHTED` (64 KiB) is drawn in one color. The tokens
  are computed with the layout of the text (cached with it by the adapter), and in the editor
  again for the block of the caret only.

### Layout of a block

* `CodeBlockLayout` lays the code out apart, with the same `TextMeasurer` of the adapter (in the
  editor the one of the field): its own width (the box less the padding), its paragraphs, its
  start alignment. In the text, the block is a placeholder of the whole line and of the height of
  the block, as a formula alone on its lines; the adapter draws the block in it. The editor draws
  the same layout over the character standing for the block: the page and the editor draw the
  code with the same layout.
* Padding 0.6 em on the sides, 0.4 em above and below; corners of 0.3 em; the label of the
  language at 0.7 times the size of the code, the color of the text at 55 %, in a row of its own
  above the code when the block has a language.
* A wrapped line continues under the start of its code: the indentation of the line (in spaces,
  monospace) as the indent of its other lines. A tab stands as 4 spaces (the offsets mapped, for
  the caret).
* The natural width of a block is its longest line plus the padding: a text of automatic width
  grows with it up to the margin of the page, as with a formula alone on its line.

### Code in the line

* The background reaches 0.15 em beyond the content on each side, over the spaces around it,
  without taking room; one per line when the code wraps. Its size stays the size of the text.
  Neither changes the lines: the texts already written with code in the line keep their layout
  and their height.

### Stage 2: the paper, the line numbers, the lists, the panel

Four of the things left for later after phase 5, asked by the maintainer on 2026-10-24.

**Where they start.**

* The background of the code is the color of the text at 7 %, translucent: the adapter draws a
  text without knowing its page (`TextTileAdapter.drawText(canvas, text, mmToCanvas)`), so the
  lines of a ruled or squared page show through. The callers know the page and its paper
  (`PageBackground.backgroundColor`): the tiles (`PageContentSnapshot.background`), the selection
  being moved, the drafts of the other devices, the preview of a note being placed, the PDF and
  PNG exports.
* The information after the fence gives the language only; there are no line numbers.
* A fence counts with up to 3 spaces before it, so a block under a nested list item (4 spaces and
  more) is text; a block under a first-level item (`  ```c`, five of the seven texts of the maintainer)
  is drawn at the left edge and as wide as the box, not with its item. A list item is one line for
  the parser.
* The panel of the lecture assistant shows the Markdown of its notes with a composable of its own
  (`MarkdownText` in `LecturePanel`): `toCanvasText` without `layoutCodeBlock` (a block is code in
  the line) and the formulas as inline content; it does not know its width before it is laid
  out.

**The design.**

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The paper under a text, given with it to whatever draws or lays it out | `TextTileAdapter.drawText(…, paperColor)`, `layout(text, paperColor)` (`drawview-canvas`), every caller passing the background of its page (0 when it has none) | Information Expert (the page), Indirection |
| The background of the code on its paper: the color of the text at 7 % over the paper, opaque | `CodeTheme.of(text, paper)` (`drawview-ui`) | Information Expert |
| The blocks asking for their line numbers | `RichTextParser`: `Span.numbered` (`showLineNumbers`, `numberLines`, `.numberLines`, `{.numberLines}`, `linenums` among the words after the fence, in any case; the language is the first other word) | Information Expert |
| The numbers of the lines of a block: a column on its left, the numbers right aligned on the first line of each line of the code (not on the wrapped ones), in the color of the label; the code after it | `CodeBlockLayout` (`drawview-ui`) | Information Expert, High Cohesion |
| Asking for the numbers, or not: `showLineNumbers` added to the opening fence or its words taken off | `RichTextEditing.toggleLineNumbers`, `TextFormatTarget.toggleLineNumbers`, `EditorCommands.lineNumbers` (⌘⌥L, menu Format, checked when the block has them), a button of `TextFormatBar` shown while the caret is in a block | Controller, Information Expert |
| The list items a line belongs to, and the blocks of code under an item | `RichTextParser`: the items open at each line (the column of their text), a fence indented at least as their text and at most 3 spaces more is a block of the item (`Span.listItem`); its closing fence likewise | Information Expert |
| A block of an item aligned with the text of its item, as wide as the rest of the line | `MarkdownTextUtil` (`toCanvasText`, `toEditorText`), `CodeBlockLayout`, `ComposeTextTileAdapter` | Information Expert |
| The Markdown of a note out of the page, as the page draws it: formulas, blocks laid out at its width, code in the line on its background | `MarkdownText` (`drawview-ui`, public), used by the panel of the lecture assistant in place of its own | Pure Fabrication, High Cohesion, Low Coupling |

**The experience.**

* On paper with lines, a block and the code in the line hide the lines under them; on plain paper
  they look as before. Whatever is under a block (strokes, a page of PDF) is hidden as under any
  opaque background.
* A block with `showLineNumbers` has its numbers in a column on the left; "Line numbers" (⌘⌥L, the
  menu Format, a button of the bar while the caret is in a block) adds or takes them off. In the
  source under the caret the block shows its fence with the word, without the column.
* A block under a list item (its fence indented as the text of the item) is drawn with its item,
  aligned with its text; a nested item can have its blocks too.
* The notes, the outline and the review in the panel of the lecture assistant show the code as the
  page does: blocks in their colors, code in the line on its background.

## Plan

| Phase | Content | Status |
|---|---|---|
| — | Before phase 1, on its own branch: the parsing of `RichTextParser` made linear, its rules and tests unchanged ([linear-parsing.md](./linear-parsing.md)) | Done |
| 1 | Notation and highlighting: `Kind.CODE_BLOCK` and `Span.language` (fences, language, content as it is, a fence not closed), `CodeHighlighter`, `BuiltInCodeHighlighter` and the grammars; tests of the rules (the notation not read inside, `#` and `-` in a block, the fences of CommonMark), of every grammar on real snippets, of the bounds (random input, unclosed strings and comments at every place, long code in linear time); ABI of `drawview-model` | Done |
| 2 | On the page: `CodeTheme`, `CodeBlockLayout`, the blocks and the code in the line in `toCanvasText`, drawn, measured and laid out by the adapter (`TextBlockLayout.boxes`), `DrawViewEngines.code`; the PDF export of the backgrounds; the automatic width; the texts with fences counted on a copy of the database of the maintainer (asked first, the original untouched), and measured again on load if any; tests (layout, wrapping, tabs, empty lines, alignment, PDF), screenshots; ABI of `drawview-canvas`, `drawview-ui` | Done |
| 3 | In the editor: the block away from the caret drawn as on the page over its character, its source under the caret on its background and in its colors, the code in the line on its background, the caret on the tapped character of a block; tests of the offsets, screenshots | Done |
| 4 | Writing code: `RichTextEditing` (Enter, Tab, ⇧Tab and the formats in a block, the fence closed, the block toggled, ⌘E on several lines), `EditorCommands.codeBlock` (⌘⌥C, Format menu, tooltip), the button of `TextFormatBar` and its icon, the strings in the 7 languages; tests of the editing and of the command | Done |
| 5 | Lecture assistant: `MARKDOWN_RULE` and the reading of the handwriting with the fenced blocks; the fence stripped by `LectureAnalysis` checked with answers holding code; tests | Done |
| 6 | The paper: the paper color given with the texts to `drawText` and `layout` by every caller (tiles, selection, drafts, preview, exports, editor), `CodeTheme.of(text, paper)` opaque; tests, screenshots (ruled paper); ABI of `drawview-canvas`, `drawview-ui` | Done |
| 7 | Line numbers: `Span.numbered`, the column of the numbers in `CodeBlockLayout` (canvas, editor, PDF, taps after it, natural width), `toggleLineNumbers` and its command, button and strings; tests, screenshots; ABI | Done |
| 8 | Blocks in list items: the items open at each line in the parser, `Span.listItem`, fences and closing fences of an item; a block drawn with its item (canvas, editor, measure, PDF); the texts of the maintainer measured again on load as in phase 2; tests (bounds included), screenshots; ABI of `drawview-model` | Done |
| 9 | The panel: `MarkdownText` of `drawview-ui` (formulas, blocks at its width, code in the line), used by `LecturePanel` for the notes, the outline and the review; tests, screenshots of the panel; ABI of `drawview-ui` | Done |

No phase touches the sync modules nor the stored formats: `ProtocolFormatTest` and the Room
schema stay as they are.

### Phase 1 results

* `RichTextParser` reads the blocks of code (`Kind.CODE_BLOCK`): an opening fence of three or
  more backticks or tildes at the start of a line (up to 3 spaces before it, kept in
  `Span.level`; after backticks, no backtick on the rest of the line), its language
  (`Span.language`: the first word of its information, `{.python}` read as `python`), its
  content up to the line of a closing fence of the same character, at least as long and with
  spaces only after it, or up to the end of the text. The blocks are scanned with the code and
  the formulas, before the rest of the notation: nothing inside them is notation, an emphasis is
  looked for between them (it does not go across a block), a display formula stops at the fence
  of a block. Each line is read once.
* `CodeHighlighter` (`drawview-model`, `model.text.code`) tells the tokens of a code
  (`CodeToken`, of the nine kinds of `CodeTokenKind`), none for a language not known nor beyond
  `MAX_HIGHLIGHTED` (64 KiB). `BuiltInCodeHighlighter` reads the code once with `CodeLexer`, the
  languages being data (`CodeGrammar`, `CodeGrammars`): Kotlin, Java, C, C++, C#, JavaScript,
  TypeScript, Swift, Go, Rust, Python, shell, SQL, JSON and YAML, under 50 names and aliases.
* Until phase 2 lays them out, the presenters draw a block as the code in the line: in the
  monospace, its fences hidden (`MarkdownTextUtil.contentStyle`).
* Tests: the fences (their characters and lengths, the indentation, the spaces after the
  closing one, a backtick after an opening one, a fence not closed, an empty block, the
  language), the notation inside and around a block, an emphasis and a display formula meeting a
  block (`CodeBlocksTest`); every language on code as it is written (`CodeHighlighterTest`);
  every opening never closed read in linear time, repeated up to 256 KiB (four times the code
  highlighted) by every grammar and up to 1 MiB by the parser, random code and random texts with
  fences never making them throw (`CodeHighlighterBoundsTest`, `RichTextParserBoundsTest`). The
  bounds of the lexer took 307 s on the iOS simulator at 1 MiB, hence 256 KiB, where a quadratic
  time would still take minutes.
* The ABI of `drawview-model` recorded again: `Kind.CODE_BLOCK`, `Span.language` (the
  constructor and `copy` of `Span` change), the highlighter added.

### Phase 2 results

* The spike: in a layout of its own, a paragraph per line of code without the line breaks keeps
  the empty lines and indents the wrapped lines (`TextIndent.restLine`); with the line breaks in
  the paragraphs, Compose adds empty lines. Long words break where they must.
* `CodeBlockLayout` (`drawview-ui`, `ui.render`) lays a block out with the `TextMeasurer` of the
  adapter: the code at 0.9 times the size of the text in the monospace of the notes, a paragraph
  per line (the spaces of the fence taken off, a tab up to the next column of 4), its tokens in
  the colors of `CodeTheme` (the palette of light paper for a dark text, of dark paper for a light
  one; the background the color of the text at 7 %), the name of its language in a row above the
  code, on the right. It tells its size and its natural width (its longest line with the padding).
  The tokens of an engine are kept only sorted, apart and in the code; an engine that throws
  leaves the code in one color.
* `MarkdownTextUtil.toCanvasText` takes `layoutCodeBlock`: a block becomes a placeholder of its
  line (`CanvasCodeBlock`), the code in the line is told with its size (`InlineCode`, the size of
  its heading in a heading). Without it (the panel of the lecture assistant) a block is still code
  in the line.
* `ComposeTextTileAdapter` draws the backgrounds of the code in the line under the text (from
  0.9 em above the baseline to 0.25 em below it, 0.15 em beyond the characters, taking no room),
  then the text, then the blocks in their placeholders; it measures a block by its natural width
  (a text of automatic width grows with it) and gives the export the words of the blocks (in the
  monospace, in their colors, the name of the language too) and the backgrounds
  (`TextBlockLayout.boxes`, `TextBox`). The style of the words for the export is computed once per
  character: before, every character read every style of its text, quadratic.
* `DrawViewEngines.code` gives the highlighter (`BuiltInCodeHighlighter` by default, in
  `DrawViewEngines.None` too: it needs no engine); the editor, the thumbnails, the export dialogs
  and the lecture assistant pass it to their adapters.
* `PdfExporter` fills the backgrounds as rectangles with rounded corners (cubic curves) under the
  words: the code stays text, searchable, in its colors.
* Counted on a copy of the database of the maintainer (the original untouched): 517 texts, 7 with blocks
  of code (in C, five of them indented by two spaces under a list item, drawn at the top level as
  decided), 53 with a backtick. So `DrawViewController` measures again the texts with fences when
  it is given its text adapter: a height other than the stored one is corrected, one `UpdateTexts`
  per page with `ChangeOrigin.MERGE` (out of the history; its documentation now says so), which
  reaches the other devices as any change.
* Tests: a block in the monospace and the colors of its language on its background, the text
  under it, a block without a language known in one color, a long line wrapped under its
  indentation, the tabs and the empty lines, a block at the start of a centered text, the natural
  width in the measure, the code in the line on a background taking no room, an engine giving
  wrong tokens or failing (`ComposeTextTileAdapterTest`); the code in the line and its size in a
  heading, a block without a layout (`MarkdownTextUtilTest`); the code exported as text on its
  background, as the editor draws it (`PdfExporterTest`); the texts with code measured again out
  of the history (`TextEditingTest`); the screenshot `text-saved-code`.
* The ABI of `drawview-canvas` (`DrawViewEngines.code`, `TextBlockLayout.boxes`, `TextBox`) and of
  `drawview-ui` (`CodeBlockLayout`, `CanvasText.codeBlocks` and `inlineCode`, the parameter of
  `toCanvasText` and of `ComposeTextTileAdapter`) recorded again.

### Phase 3 results

* `MarkdownTextUtil.toEditorText` takes the highlighter and `layoutCodeBlock`. A block away from
  the caret stands as one character as wide as the line and as high as the block (its font size
  and its letter spacing, as a formula alone on its line), in the mapping of the offsets with the
  formulas; the editor draws over it the `CodeBlockLayout` the page draws. The block of the caret
  (the caret on it, its fences included) shows its source: its fences faded, its code in the
  monospace at 0.9 times the text and in the colors of its language, on its background
  (`EditorText.codeSources`, from the top of its first line to the bottom of its last one, the
  width of the box).
* The code in the line is on its background in the editor too (`EditorText.inlineCode`; the
  backgrounds computed by `codeChips`, shared with the canvas).
* The caret goes on the character tapped in a block: when a text is opened by a tap
  (`takeCaretRequest`), and when a block drawn in the field is tapped while writing (a gesture
  before the field's own, which would put the caret at an end of the character).
  `CodeBlockLayout.codeOffsetAt` maps the tap back to the code (the tabs and the indentation taken
  off, the end of a line before its line break).
* The editor keeps the blocks it laid out (16, the LRU cache of the adapter): typing outside a
  block does not lay it out again.
* A tall block ([Risks](#risks)): on the JVM a character of 20 000 px (a block of about 800 lines)
  is laid out in 0.2 ms and drawn ten times in 0.2 ms, its line as high as its size says; iOS
  draws with Skia too. Android, with a text engine of its own, is to be checked on a device.
* In the source a tab is as wide as the field draws it, narrower than the four columns of the
  page.
* Tests: a block away from the caret as one character as high as the block and its offsets, the
  block of the caret as its source in its colors with its fences faded, the code in the line in
  the offsets shown, a tap on a block on the character tapped (`EditorCodeTest`); the screenshots
  `text-writing-code` and `text-writing-code-source`.
* The public API does not change.

### Phase 4 results

* `RichTextEditing` knows the blocks of code (`inCodeBlock`: the selection in a block, its fences
  included). In a block the formats of the characters, the headings and the lists do nothing.
  Enter in its code keeps the indentation of the line and continues no list (from the keyboard of
  a phone too, through `handleSmartNewline`); at the end of the opening fence of a block not
  closed it closes the block under the caret; on the fences of a block closed it is a plain line
  break. Tab writes spaces up to the next column of 4 (a tab counted to its column), on a
  selection it indents the lines by 4; ⇧Tab takes off up to 4 spaces or a tab. The lists keep 2.
* `toggleCodeBlock` puts the whole lines of the selection between fences (one backtick longer
  than the longest run of backticks in them, 3 at least), or takes the block of the selection out
  of its fences, the selection on the same characters; ⌘E on a selection over several lines makes
  a block.
* `TextFormatTarget.inCodeBlock` and `toggleCodeBlock`, for the text being written
  (`TextEditorManager`, which reads the notation once per value). `EditorCommands.codeBlock`
  ("Code block", ⌘⌥C or Ctrl+Alt+C, in the menu Format after "Code") and its button in
  `TextFormatBar`, after the code, with the icon `SquareCode` of Lucide. In a block the formats of
  the characters, the heading and the lists are disabled (their buttons greyed), the block stays
  enabled. Its label in the seven languages.
* Tests: Enter in a block and from a phone, a fence closed by Enter, Tab and ⇧Tab in a block and
  in a list, the formats in a block, a block made and taken out with its fence, ⌘E on several
  lines (`RichTextEditingTest`); the command by its shortcut, the formats disabled in the block
  (`CommandTest`); the screenshots of the text being written recorded again (the bar has the
  button of the block).
* The ABI of `drawview-canvas` (`TextFormatTarget.inCodeBlock`, `toggleCodeBlock`,
  `RichTextEditing.inCodeBlock`, `toggleCodeBlock`) and of `drawview-ui` (the string of the
  command) recorded again.

### Phase 5 results

* The rule of the Markdown given to the models (`LecturePrompts.MARKDOWN_RULE`, in the live
  suggestions and in the final review) names the code in the line and the blocks of code between
  fences with their language; the notes and the final review ask for the code in blocks of code.
  The reading of the handwriting writes the code written by hand between fences, with its
  language when it is clear and its indentation.
* The answers holding code are read as they are written (`LectureAnalysis`):
  * `repairLatexEscapes`: in a block of code of a string (between fences at the start of its
    lines) only the escapes that are not valid are doubled: a tab before a word stays a tab, `\nu`
    a new line before "u". Outside the code, LaTeX is repaired as before.
  * `normalizeMarkdown` leaves the blocks of code as they are: the ` * ` of a comment is not a
    bullet, the `\n` of a string not a line break, a `<br>` code of HTML.
  * The fence around an answer in JSON is taken off when it closes at the end of the answer or
    does not close: before, the last fence of the answer was taken, which could be the one of the
    code in a string.
* No request to a model was made: the prompts changed in their words only (the evaluation of the
  assistant, on `feat/lecture-eval`, measures them).
* The panel of the assistant shows the code of a suggestion as code in the line (it lays its
  texts out without the blocks, see [Left for later](#left-for-later)); a note inserted in the
  page is drawn with its blocks.
* Tests: the prompts asking for blocks of code (`LecturePromptsTest`); the code of a note kept as
  it is written with LaTeX and bullets repaired around it, a fence around the answer closed or not
  (`LectureAnalysisTest`).
* The public API does not change.

### Phase 6 results

* `TextTileAdapter.drawText` and `layout` take the color of the paper under the text
  (`paperColor`; `TextTileAdapter.NO_PAPER`, a color of no alpha, when there is none). Every caller
  passes the background of its page: the tiles (and so the thumbnails and the PNG export), the
  selection being moved, the drafts and the selections of the other devices, the preview of a note
  being placed, the PDF export (the layouts and the images of the texts), the editor.
* `CodeTheme.of(text, paperColor)`: the background of the code is the color of the text at 7 %
  composited over the paper, opaque: the lines of a ruled or squared page do not show through the
  blocks nor the code in the line; on white paper the color is the one of before. Without a paper
  the background stays translucent. The adapter keeps its layouts by paper too.
* Tests: the backgrounds opaque on white and on a colored paper, translucent without one
  (`ComposeTextTileAdapterTest`); the screenshots of the code on ruled paper recorded again
  (`text-saved-code`, `text-writing-code`, `text-writing-code-source`).
* The ABI of `drawview-canvas` (the parameter of `drawText` and `layout`, `NO_PAPER`) and of
  `drawview-ui` (the adapter) recorded again.

### Phase 7 results

* `RichTextParser` reads the words after a fence: `Span.numbered` when one of them asks for the line
  numbers (`RichTextParser.LINE_NUMBERS`: `showLineNumbers`, `numberLines`, `linenums`, in any
  case, the braces and the dots of the attributes of Pandoc taken off); the language is the first
  other word.
* A block is laid out from a `CodeBlockSource` (its code, language, indentation, numbers and
  width: the callbacks of `toCanvasText` and `toEditorText` take it, the width ready for the lists
  of phase 8). `CodeBlockLayout` puts the numbers in a column on the left (as many digits as the
  last line, then a free one), right aligned, in the color of the label, each on the baseline of
  the first line of its line of code: a wrapped line has none, an empty line has its number. The
  code starts after the column (`codeLeftPx`): drawn, tapped, laid out for the export, the column
  counted in the natural width. The numbers are words of the layout, so text in the PDF.
* `RichTextEditing.toggleLineNumbers` writes `showLineNumbers` after the information of the fence,
  or takes the words off (a brace of Pandoc kept closed), the selection on the same characters;
  `codeBlockAt` tells the block of the selection. `TextFormatTarget.codeLineNumbers` and
  `toggleLineNumbers` (the text being written: the block of the selection read once per value).
  `EditorCommands.lineNumbers` ("Line numbers", ⌘⌥L or Ctrl+Alt+L, menu Format, checked when the
  block has them, enabled in a block) and its button in the bar, with the icon `Hash`, shown while
  the caret is in a block. Its label in the seven languages.
* Tests: the words asking for the numbers and the language among them (`CodeBlocksTest`); the
  numbers asked and taken off (`RichTextEditingTest`); the command by its shortcut, enabled and
  checked (`CommandTest`); the column, a number per line of code, on its baseline, a tap after the
  column (`EditorCodeTest`); the numbers as words before the code (`ComposeTextTileAdapterTest`);
  a block with its numbers in the screenshot `text-saved-code`, the bar with its button in
  `text-writing-code-source`.
* The ABI of `drawview-model` (`Span.numbered`, `LINE_NUMBERS`), `drawview-canvas` (the target,
  `RichTextEditing.codeBlockAt`, `toggleLineNumbers`) and `drawview-ui` (`CodeBlockSource`, the
  callbacks of `toCanvasText`, the string) recorded again.

### Phase 8 results

* `RichTextParser` follows the list items open at each line while it scans the verbatim parts
  (`OpenItems`: the column of the text of each item, the spaces before its marker, its marker and
  the spaces after it, a tab counted as 2; a line not blank and less indented closes it). A fence
  indented as the text of the innermost item and up to 3 spaces more opens a block of that item
  (`Span.listItem`, the item as the parser reads it); its closing fence likewise; a line less
  indented than the text of the item ends the block with the item (the block is then not
  closed). Out of the lists a fence keeps its 3 spaces at most. Each line is read once more at most.
* A block of an item is drawn where the text of its item starts, as wide as the rest of the line
  (`CanvasCodeBlock.leftPx`, `EditorCodeBlock.leftPx`; its layout made on that width): on the
  canvas, in the editor (its tap mapped after it), in the measure (its natural width counted from
  there) and in the export. The prefix of the item (its indentation, its bullet or number) is
  measured in the font of the text (`listPrefixWidth` of `toCanvasText` and `toEditorText`):
  `listPrefixWidthPx`, the approximation of the lists for their wrapped lines, put the blocks
  about 1 mm to the right of the text of their item. The wrapped lines of the items keep it, so
  that the texts already written keep their lines.
* The five texts of the maintainer with a block under an item are drawn narrower, so higher: measured
  again when their document opens (phase 2).
* Tests: a block under an item, under a nested item and under a numbered one, a line less indented
  ending it, the fences out of the lists (`CodeBlocksTest`); lists and fences at 1 MiB in linear
  time (`RichTextParserBoundsTest`, also on the iOS simulator); a block aligned with the text of
  its item and to the end of the line (`ComposeTextTileAdapterTest`); in the editor, at the text
  of its item and tapped (`EditorCodeTest`); the screenshot `text-saved-code-list`.
* The ABI of `drawview-model` (`Span.listItem`) and of `drawview-ui` (`CanvasCodeBlock.leftPx`, the
  parameter `listPrefixWidth`) recorded again.

### Phase 9 results

* `MarkdownText` (`drawview-ui`, `ui.components`, public): the Markdown of a note out of the page
  as the page draws it, laid out by `toCanvasText` at the width given to it (`BoxWithConstraints`):
  the formulas of `LocalDrawViewEngines` in their placeholders, the blocks of code drawn in theirs
  (their colors, their numbers, under their list item), the code in the line on its background
  (`codeChips`). A text with formulas the engine cannot render yet shows its notation styled, as
  before; a text without formulas is drawn at once, also without a formula engine.
* `LecturePanel` shows its notes, the outline and the review with it, in place of its own
  composable: the code of the notes of the assistant in blocks.
* `toCanvasText` takes the `density` of where the text is shown: the sizes of its styles are in sp.
  The panel gave it pixels, so its headings were as many times larger as the density of the
  screen (twice on a Retina display); on the canvas the density stays 1.
* Tests: the sizes of the styles in sp (`MarkdownTextUtilTest`); the screenshot `markdown-text`
  (a heading, code in the line, a block with its numbers, a block under a list item); the
  screenshots of the panel recorded again (`lecture-outline`, `lecture-review`: in the tests, without
  the formula engine, the panel showed the notation of the lists and its headings at another
  size; now as the page draws them).
* The ABI of `drawview-ui` (`MarkdownText`, the `density` of `toCanvasText`) recorded again.
* Seen in the checks, not from this work: the screenshot `editor-context-menu` of the explorer
  shows "Paste" only when the clipboard of the computer running the tests holds a text, so it
  fails on a computer whose clipboard has none (left for later).

## Decisions

Proposed with the plan and approved by the maintainer on 2026-10-22, every one revertible:

1. Fences of backticks or tildes only; no block indented by 4 spaces (it would take the nested
   list items).
2. A fence not closed runs to the end of the text (CommonMark); the editor closes the fences it
   sees typed.
3. Blocks at the top level only, not inside list items (changed by 17).
4. The long lines wrap, their indentation kept: no horizontal scroll on paper, and shrinking the
   code as a formula would make it unreadable.
5. A lexer of our own, linear and bounded, behind `CodeHighlighter`, rather than a library
   ([Highlights](https://github.com/SnipMeDev/Highlights),
   [SyntaxMP](https://klibs.io/project/GallatinApps/SyntaxMP)): the code comes from the other
   devices, and an engine of regular expressions can be made to take quadratic or exponential
   time. The cost: more work and fewer languages; a better engine can be given through the port.
6. The languages of [Highlighting](#highlighting); a block without a known language in one color,
   no detection of the language.
7. Identifiers in the color of the text, the palette chosen by the luminance of that color.
8. The code of the blocks at 0.9 times the size of the text; the code in the line at the size of
   the text, its background taking no room (the texts already written keep their lines); the
   background the color of the text at 7 %; the language in the top-right corner.
9. In the editor, a block away from the caret as on the page, its source under the caret (as the
   formulas).
10. Tab in a block is 4 spaces; Enter keeps the indentation, without knowing the language.
11. ⌘⌥C (Ctrl+Alt+C elsewhere) for the block; ⌘E on several lines makes a block.
12. No line numbers for now (changed by 15).
13. The texts whose fences are drawn as blocks are measured again when their document opens, with
    a change of their height only, if the count on the copy of the database finds any.

Proposed with stage 2 and approved by the maintainer on 2026-10-24, every one revertible:

14. The paper comes with the text to the adapter (`drawText`, `layout`): the background of the
    code is opaque on its paper; without a paper (0) it stays translucent. What is under a block is
    hidden, as under any opaque background.
15. Line numbers per block, asked by a word after the fence (`showLineNumbers`, also `numberLines`,
    `.numberLines`, `{.numberLines}`, `linenums`): the notation keeps them with the text, on every
    device and in the export. The command writes `showLineNumbers`. The numbering starts at 1 (a
    start of its own left for later).
16. The numbers are text in the PDF (searchable, copied with the code), in the color of the label.
17. A block belongs to a list item when its fence is indented at least as the text of the item and
    at most 3 spaces more; it is drawn aligned with the text of the item. A line not blank and less
    indented than the text of an item closes the item (no lazy continuation of CommonMark).
18. "Line numbers": ⌘⌥L (Ctrl+Alt+L elsewhere), the menu Format, and a button of the bar shown
    only while the caret is in a block.
19. `MarkdownText` is public in `drawview-ui`: the panel of the lecture assistant uses it, and so can
    an application showing notes out of the page.

## Risks

* **A tall block in the editor.** The character standing for a block takes the height of the block
  from its font size, as the formulas do: a block of 60 lines makes a character of about a
  thousand points. To be measured in phase 3 (glyph caches, the input methods); fallback: the
  editor shows the source of every block, on its background and in its colors.
* **The indent of the wrapped lines.** Compose paragraph styles added empty lines around the line
  breaks in phase 2 of [text-editing.md](./text-editing.md); the lists replace their line breaks
  with paragraphs, and whether an empty paragraph keeps its line (the empty lines of the code) is
  to be checked. A spike opens phase 2; fallback: the wrapped lines start at the padding, marked
  with ↪.
* **The quality of a lexer without a grammar of the language.** String templates of Kotlin, the
  lifetimes of Rust against its characters, the regular expressions of JavaScript: each grammar
  is tested on real snippets; what it reads wrong is a color, never the text.
* **The source shown under the caret** moves the lines of the block when the caret enters it (its
  fences appear), as a formula does.
* **The lists in the parser** (stage 2): the items open at each line are followed while the
  verbatim parts are scanned, every line read once; the bounds tests take nested lists and fences
  at every indentation.
* **The texts already written** with a block under an item (five of the maintainer) get narrower and
  higher: measured again on load as in phase 2.

## Left for later

* On devices: the caret, the input methods and the selection handles of a field showing a block
  of code as one character (Android, iOS), and a tall block on Android.
* The language detected from the code; the names of the languages completed while typed.
* Copying the code of a block alone (the selection copies the text with its notation).
* Indentation that knows the language (after `{`, `:`).
* A number to start the lines from (`startFrom` of Pandoc).
* The screenshot of the context menu of the editor independent of the clipboard of the computer
  running the tests (a clipboard of the test, as `CommandTest` has).
