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

## Plan

| Phase | Content | Status |
|---|---|---|
| — | Before phase 1, on its own branch: the parsing of `RichTextParser` made linear, its rules and tests unchanged ([linear-parsing.md](./linear-parsing.md)) | Done |
| 1 | Notation and highlighting: `Kind.CODE_BLOCK` and `Span.language` (fences, language, content as it is, a fence not closed), `CodeHighlighter`, `BuiltInCodeHighlighter` and the grammars; tests of the rules (the notation not read inside, `#` and `-` in a block, the fences of CommonMark), of every grammar on real snippets, of the bounds (random input, unclosed strings and comments at every place, long code in linear time); ABI of `drawview-model` | Done |
| 2 | On the page: `CodeTheme`, `CodeBlockLayout`, the blocks and the code in the line in `toCanvasText`, drawn, measured and laid out by the adapter (`TextBlockLayout.boxes`), `DrawViewEngines.code`; the PDF export of the backgrounds; the automatic width; the texts with fences counted on a copy of the database of the user (asked first, the original untouched), and measured again on load if any; tests (layout, wrapping, tabs, empty lines, alignment, PDF), screenshots; ABI of `drawview-canvas`, `drawview-ui` | Done |
| 3 | In the editor: the block away from the caret drawn as on the page over its character, its source under the caret on its background and in its colors, the code in the line on its background, the caret on the tapped character of a block; tests of the offsets, screenshots | Done |
| 4 | Writing code: `RichTextEditing` (Enter, Tab, ⇧Tab and the formats in a block, the fence closed, the block toggled, ⌘E on several lines), `EditorCommands.codeBlock` (⌘⌥C, Format menu, tooltip), the button of `TextFormatBar` and its icon, the strings in the 7 languages; tests of the editing and of the command | To do |
| 5 | Lecture assistant: `MARKDOWN_RULE` and the reading of the handwriting with the fenced blocks; the fence stripped by `LectureAnalysis` checked with answers holding code; tests | To do |

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
* Counted on a copy of the database of the user (the original untouched): 517 texts, 7 with blocks
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

## Decisions

Proposed with the plan and approved by the user on 2026-10-22, every one revertible:

1. Fences of backticks or tildes only; no block indented by 4 spaces (it would take the nested
   list items).
2. A fence not closed runs to the end of the text (CommonMark); the editor closes the fences it
   sees typed.
3. Blocks at the top level only, not inside list items.
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
12. No line numbers for now.
13. The texts whose fences are drawn as blocks are measured again when their document opens, with
    a change of their height only, if the count on the copy of the database finds any.

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

## Left for later

* On devices: the caret, the input methods and the selection handles of a field showing a block
  of code as one character (Android, iOS), and a tall block on Android.
* An opaque background, in the color of the paper: the lines of a lined page show through the
  background of the code, which is translucent because the adapter of the texts does not know the
  page it draws on.
* Line numbers.
* Blocks inside list items.
* The language detected from the code; the names of the languages completed while typed.
* Copying the code of a block alone (the selection copies the text with its notation).
* Indentation that knows the language (after `{`, `:`).
