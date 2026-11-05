# Pasting formatted text

A text copied in a browser, a word processor or a chat assistant keeps its formatting when pasted
in a notebook: the HTML the other application puts in the clipboard becomes the Markdown of the
texts (`RichTextParser`). This document starts from the code as it is (`main` at 7afc0d29,
2026-11-05): what a paste does today, the libraries that exist, then the design and the plan. It
takes up "the rich text of the clipboard" left for later in `docs/text-import.md`.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Only the plain text is read.** On the canvas `EditorCommands.pasteAt` reads
   `ClipboardManager.getText()`. In a text being written, the paste belongs to `BasicTextField`
   (`TextBoxEditor`), and `TextFieldSelectionManager.paste` reads
   `LocalClipboard.current.getClipEntry()?.readAnnotatedString()`. No code reads an HTML flavor.
2. **Plain text is cleaned afterwards.** `PastedText.clean` turns bullets into lists and joins
   lines broken by the width of the source; a long text opens the sheet of the import
   (`TextImport`, `TextCleanup`). In the field, a paste is recognized from the difference between
   two values (`TextEditorManager.cleanedPaste`).
3. **The notation.** `RichTextParser.Kind`: bold, italic, strikethrough, code, heading (six
   levels), formula, escape, list item (with `ListMarker`), block of code, link, table, quote. It
   has no underline, color, subscript or image in a text, and no task lists.
4. **What Compose gives (read in the sources of 1.12.1).**
   * Desktop: `ClipEntry.asAwtTransferable` (experimental) gives the AWT flavors, HTML among them.
   * Android: `ClipEntry.clipData`, whose items have `getHtmlText()`.
   * iOS: `ClipEntry` holds plain text only; `Clipboard.nativeClipboard` is the `UIPasteboard`.
   * The field takes its clipboard from `LocalClipboard`, so a `Clipboard` provided around it
     decides what it pastes, without a fork of the field.

## The libraries

| Library | What it is | Verdict |
|---|---|---|
| `io.github.mltheuser:khtmltomarkdown` 1.0.1 (MIT) | The one HTML to Markdown converter for Kotlin Multiplatform found, on `ksoup-html` | Not adopted: 11 commits and 1 star; its documentation mentions neither `style` attributes, unknown tags, `script`/`style` nor a depth limit, and its tables have "basic" support. The rules that matter here (below) would all be custom |
| `com.fleeksoft.ksoup:ksoup` 0.2.6 (MIT, February 2026) | A port of jsoup: a tolerant HTML parser with a DOM, no conversion | **Adopted for the parsing**, after the check of phase 1 |
| `com.mohamedrejeb.ksoup:ksoup-html` 0.6.0 (Apache-2.0, June 2025) | A parser with callbacks, no DOM | The fallback if Ksoup fails phase 1 |
| Turndown (JS), flexmark `html2md` (Java) | Mature converters | Not usable: the former is JavaScript, the latter is for the JVM only, and iOS is a target |
| `dev.kreuzberg:html-to-markdown-android` | Bindings to a native converter | Not evaluated further: a native library per platform for what is text processing |

Parsing HTML as browsers do is the hard part that deserves a library. The conversion is specific to
this notation, and small.

## Requirements

1. A paste from another application with an HTML flavor keeps: headings, bold, italic,
   strikethrough, inline code, blocks of code with their language, links, lists (nested, numbered
   from their start), quotes, tables.
2. **The sources as they really are:** formatting by `style` (`font-weight`, `font-style`,
   `text-decoration`, `font-family`), Google Docs (its outer `<b style="font-weight:normal">` is not
   bold), Word, and formulas of KaTeX and MathJax (their TeX `annotation` becomes `$…$`, so a formula
   copied from a chat assistant stays a formula).
3. **In both places:** on the canvas (new text, or the sheet of a long text, as Markdown) and in a
   text being written, at the caret.
4. **Literal characters stay literal:** a `*`, `_`, `$`, `|` or `#` of the source is escaped.
5. **Never lose text.** What cannot be expressed keeps its text (underline, color and unknown tags
   drop only the formatting); a conversion that fails or loses words falls back on the plain text.
6. **A way out:** "Paste as plain text" (⇧⌘V, Ctrl+Shift+V elsewhere).
7. **The clipboard is untrusted input:** bounded size and depth, no network request, `script` and
   `style` ignored, only `http`, `https` and `mailto` links kept.
8. **It degrades:** without an HTML flavor, the paste of today. The same on every platform.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| HTML turned into the notation, a pure function of a string | `HtmlToMarkdown` (`drawview-html`, a new headless module) | Pure Fabrication, High Cohesion; Protected Variations (the parser stays behind it) |
| The tree of HTML walked, the blocks written | `MarkdownWriter` (internal, `drawview-html`) | Pure Fabrication |
| A block of text: spaces, delimiters | `InlineText` (internal, `drawview-html`) | High Cohesion |
| The formatting a node asks for, tag and `style` together | `HtmlFormat` (internal, `drawview-html`) | Information Expert |
| What Word copies rewritten as plain HTML | `WordHtml` (internal, `drawview-html`) | Polymorphism, Protected Variations |
| A formula of KaTeX, MathJax or MathML read back as its source | `HtmlFormula` (internal, `drawview-html`) | Information Expert |
| An equation of Word written in LaTeX | `OmmlToLatex` (internal, `drawview-html`) | Pure Fabrication |
| Characters of the source that would read as notation escaped | `MarkdownEscape` (internal, `drawview-html`), tested against `RichTextParser` | Information Expert |
| The HTML of the clipboard, per platform | `expect fun htmlIn(clipboard)` (`drawview-ui`) | Polymorphism, Indirection |
| What a paste contains: HTML converted, or plain text, for canvas and field alike | `ClipboardText` (`drawview-ui`) | Controller, Low Coupling |
| The field pasting the converted text | `FormattedPasteClipboard`, provided to `BasicTextField` in `TextBoxEditor` | Indirection |
| A block pasted in a line given a line of its own | `PastedText.placed` (`drawview-model`) | Information Expert |

**A module of its own.** `drawview-model` has no parser today, and every library would inherit Ksoup
through it. `drawview-html` is headless, without Compose, and `drawview-ui` uses it as an
`implementation` dependency. A future import of `.html` files uses the same module.

**The conversion.** The DOM is walked once, with the depth bounded; each block writes its lines
with the prefix of its container (quote, list), and the inline formatting is written around the
text it covers, with the spaces kept outside the delimiters, as the notation requires. A cell of a
table stays on one line. A `colspan` adds empty cells, since the notation has no spans.

**In the field.** `FormattedPasteClipboard` answers `getClipEntry` with an entry of the converted
text and delegates the rest. It is built per platform (`ClipEntry(StringSelection)`,
`ClipData.newPlainText`, `ClipEntry.withPlainText`). The field also calls `getClipEntry` to know
whether a paste is possible, so the conversion is cached by its HTML. `cleanedPaste` knows the text it
gave (`formattedPaste`) and leaves it as it is, apart from the line a block needs. In a block of code
the plain text is pasted.

**Data.** The result is the text of a `TextData`, Markdown as already stored: no change to the Room
schema, to a stored format or to the sync protocol.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | Ksoup checked on the targets (`iosArm64`, `iosSimulatorArm64`, JVM, Android, with Kotlin 2.4.20); `drawview-html` with its `Module.md`, `api/` dump, Dokka and `THIRD_PARTY_LICENSES.md`; blocks, inline formats, `style`, escapes; every result read back with `RichTextParser` in the tests | done |
| 2 | The real sources: fixtures of HTML in the forms that Chrome, Firefox, Google Docs, Word and a chat assistant copy, `WordHtml`, `HtmlFormula`; lists, tables, blocks of code, formulas; limits of size and depth; the fallback on plain text | done |
| 3 | `htmlIn` per platform, `ClipboardText`, the paste on the canvas and the sheet of a long text, "Paste as plain text" on the canvas | done |
| 4 | The field (`FormattedPasteClipboard`, `TextEditorManager.formattedPaste`, `PastedText.placed`), "Paste as plain text" in the field | done |
| 5 | On the devices: desktop on a scratch data directory, the Android tablet, iOS; the pasteboard types of Safari. On Windows: the checks, Android builds, consumer checks. Left to the user: real copies from a browser and Word on each device, the iOS sources and the klib dump of `drawview-canvas` on the Mac | in progress |

## Decisions

Taken alone, revertible:

1. **Ksoup for the parsing, the conversion written here**, rather than `khtmltomarkdown`.
2. **A new module** `drawview-html`, rather than a dependency of `drawview-model`.
3. **HTML wins over plain text when it is there**, with "Paste as plain text" as the way out.
4. **Images are dropped** (their `alt` is kept as text when there is one): a text cannot hold them,
   and fetching them would mean network requests caused by a paste.
5. **Up to 2 MB of HTML**; beyond it, the plain text.
6. **HTML without any formatting is left to the plain text** (`convert` answers `null`): nothing is
   gained, the cleaning of `PastedText` still applies, and a Markdown copied as text stays Markdown
   instead of having its `*` escaped.
7. **A separator is a break between blocks only:** the notation has no separator, and a line `---`
   would be drawn as three dashes.
8. **Italics next to a `*` are written with `_`** (`**_a_**`), and a change of emphasis in the middle
   of a word separates the two with a zero-width space (`**a**` and `*b*`): the notation does not read
   them otherwise. It is rare and invisible, and the text can be edited as any other.
9. **Word gets a normalization of its own** (`WordHtml`), which rewrites its tree into plain HTML
   before the conversion: a new source adds a normalization and its fixtures, not a new converter.
   Google Docs needs none, since `style` winning over the tag covers its outer `<b>`.
10. **An equation of Word is a formula** (`OmmlToLatex`): Word copies an image, with its OMML in a
    comment. What cannot be written (an unknown element, too deep) becomes a visible placeholder, never
    a formula with a piece missing. Asked by the user after a real copy, 2026-11-05.
11. **A formula copied as text between dollars is a formula**, when the notation reads it as one and it
    looks like one (a sign of a formula, or at most 3 characters): Gemini copies its formulas so, as a
    real copy from Chrome showed (2026-11-05). Any other `$` stays a character.

## Open points

* **Safari on iOS.** Old sources (2011–2013) say that it copies a web archive
  (`Apple Web Archive pasteboard type`), not `public.html`. Phase 5 reads the types on a device:
  `public.html` first, then the main resource of the web archive, then plain text.
* **The keyboards of Android.** A clipboard suggestion tapped on the keyboard commits plain text to
  the field, past the `Clipboard`: it should stay the paste of today.
* **The context menu of iOS.** Whether its Paste goes through `LocalClipboard` is to be checked in
  phase 4.
* **Applications that decorate what they copy.** Code editors copy `<div>`s with colors: a fragment
  entirely monospaced should become a block of code. To be checked on the fixtures.

## Left for later

* Subscripts and superscripts: their text is kept, without the shift.
* Importing `.html` and `.docx` files.
* RTF (`public.rtf`), for the applications with no HTML flavor.
* Images pasted as image elements next to the text.
* Copying from the notebook to other applications with an HTML flavor.
