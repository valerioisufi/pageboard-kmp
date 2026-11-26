# Blocks of code: copying, indenting, languages, first line

Stage D of the roadmap of `docs/plans/text-editor-enhancements.md`: what `docs/plans/code-blocks.md`
left for later about the blocks of code of the texts. It starts from `feat/tables` after stage C
(`docs/plans/boxes-tables-quotes.md`).

The responsibilities are assigned following the GRASP principles.

## Starting point

1. **Copying** (`EditorClipboard`, the field): a selection copies the text with its notation; copying
   the code of a block means selecting it without its fences, which the field shows as one object.
2. **Indenting** (`RichTextEditing.codeEnter`): Enter in a block keeps the indentation of the line of
   the caret, whatever the language: after `{` or `:` the next line is not indented further.
3. **Languages** (`RichTextParser.codeBlock`, `CodeGrammars`): a block colors the code of the language
   written after its fence; with none written it is plain, and the name is typed with no help.
4. **Line numbers** (`LINE_NUMBERS`): `numberLines` (or `{.numberLines}`) numbers the lines from 1;
   Pandoc's `startFrom` is not read.

## Requirements

1. **Copy the code** of the block of the caret alone, without its fences: a command of the menu of
   the editor and of the context menu, when the caret is in a block.
2. **Indentation that knows the language**: Enter after a line opening a block of its language (`{`,
   `(`, `[` in the languages of braces, `:` in Python and YAML) indents the new line one step more,
   the step of the block (its first indented line, else four spaces); a `}` typed alone on an
   indented line goes one step back.
3. **Languages**: a block with no language written is colored in the language its code looks like
   (only shown, never written); the name typed after a fence is completed from the languages known,
   as the commands of LaTeX are (`LatexCompletionPopup`).
4. **The first line number**: `startFrom="10"` (or `startFrom=10`) after the fence numbers the lines
   from 10, as Pandoc does; the editor writes it from the bar of a numbered block.
5. **No note changes**: a block written before reads the same (no language detected written into it,
   no `startFrom`); every platform, the 7 languages, light and dark.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The code of the block of the caret | `RichTextEditing.codeOfBlockAt`, `EditorCommands.copyCode` | Information Expert, Controller |
| The step of indentation, the openers of a language | `CodeIndent` (`pageboard-model`, `text.code`), `RichTextEditing.codeEnter` | Information Expert, Pure Fabrication |
| The language a code looks like | `CodeGrammars.guess` (`pageboard-model`) | Information Expert |
| The names of the languages completed | `CodeGrammars.names`, the popup of the completions (`pageboard-ui`) | Pure Fabrication, Low Coupling |
| The first number of the lines | `RichTextParser.codeBlock` (`Span.number`), `CodeBlockLayout` (`pageboard-ui`) | Information Expert |

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | Copy the code of a block; indentation that knows the language; tests | Done |
| 2 | The first line number (`startFrom`): read, drawn, written from the bar; tests | Done |
| 3 | The language guessed for coloring; the names of the languages completed while typed; tests | Pending |

## Decisions

Taken alone, revertible:

1. **A guessed language is only shown**: the source keeps what was written, so that a guess can get
   better without rewriting a note.
2. **The step of a block is the least indentation of its lines** (after the spaces of its fence),
   a tab when its first indented line has one, else four spaces (two in JSON and YAML, as they are
   mostly written): a block keeps the step it was written with.
3. **The openers belong to the grammar of the language** (`CodeGrammar.openers`): a bracket in most
   languages, `:` too in Python and YAML, parentheses only in SQL. An opener in a comment or a string
   opens nothing; a block with no language known keeps the indentation of the line, as before.
4. **Enter between an opener and its closer** (`{|}`) puts the caret on a line of its own, one step
   in, and the closer on the next line at the indentation of the opener, as code editors do.
5. **A closer typed goes back one step only alone on its line** (spaces before it, nothing after),
   never past the spaces of the fence of a block in a list item.
6. **Copying the code** is a command of the menu Edit and a button of the formatting bar while the
   caret is in a block: the text field has no context menu of its own, and the toolbar of a touch
   selection is the platform's (copy, cut, paste, select all).
7. **`startFrom` is written as Pandoc writes it** (`startFrom="10"`, inside the braces of the
   attributes when the information has them), only for a number other than 1, from 0 to 99 999;
   choosing it numbers the lines. A number written wrong reads as 1, and an attribute (a word with
   `=`, or `#id`) is never taken for the language.
8. **The first number is chosen by steps of 1 and 10** in a picker of the bar beside the numbers
   of the lines, as the start of a list is: a field to type in would take the focus from the text.

## Left for later

- Real-device checks of the completion on soft keyboards, left to the maintainer.
