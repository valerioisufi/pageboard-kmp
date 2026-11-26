# Texts from elsewhere: files, the clipboard, copies to other applications

Stage F of the roadmap of `docs/plans/text-editor-enhancements.md`: what
`docs/plans/text-import.md` and `docs/plans/html-paste.md` left for later. It starts from `main`
after stage E (`docs/plans/text-fidelity-more.md`).

The responsibilities are assigned following the GRASP principles.

## Starting point

1. **Files** (`DocumentScreen.importTextFile`, `TextImport`): a `.txt` or `.md` file chosen or
   dropped opens the sheet of the import; `.html`, `.docx` and the text of a PDF are not read.
2. **The HTML of the clipboard** (`HtmlToMarkdown`, `pageboard-html`): pasted in the notation, Word's
   equations as LaTeX (`OmmlToLatex`); a subscript or a superscript keeps its text without the shift
   (`H<sub>2</sub>O` becomes `H2O`), an image is left out.
3. **RTF** (`public.rtf`, `text/rtf`): not read; an application with no HTML flavor pastes its plain
   text.
4. **Copying** (`EditorCommands.copy`): a text selected or written goes to the other applications as
   its notation, plain text.

## Requirements

1. **`.html` and `.docx` files** chosen or dropped are imported as text: their headings, paragraphs,
   lists, tables, bold, italic, strikethrough, links and equations (Word's, as LaTeX) in the
   notation, through the sheet of the import.
2. **The text of a PDF chosen as a text**: a PDF chosen in the import of a text gives its text, page
   after page, cleaned as a text from elsewhere; a PDF with no text says so.
3. **Subscripts and superscripts** pasted or imported keep their shift: in Unicode when every
   character has one (`H₂O`, `x²`), else as a formula (`$x^{ab}$`).
4. **RTF** is read when the clipboard has no HTML (the desktop and iOS; Android's applications copy
   HTML): paragraphs, bold, italic, strikethrough, the shifts, the characters in Unicode and in the
   code page 1252, the rest left out.
5. **Copying as HTML**: a text copied from the notebook goes to the other applications as HTML too
   (headings, lists, bold, italic, code, tables, links, formulas as their LaTeX), the plain text
   staying its notation.
6. **What is read is untrusted**: a file or a clipboard is the user's but may come from anywhere;
   nothing is allocated past what it holds, an archive is read within bounds (entries, sizes, the
   ratio of what it inflates to), a nesting too deep fails the reading, never the application.
7. **No note changes**: no stored format changes; every platform, the 7 languages, light and dark.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The entries of a ZIP archive, deflated or stored, within bounds | `ZipEntries`, `Inflate` (expect/actual: `java.util.zip` on the JVM and Android, zlib on iOS) (`pageboard-html`) | Pure Fabrication, Protected Variations |
| A Word document as the notation | `DocxToMarkdown` (`pageboard-html`), reusing `MarkdownWriter`, `OmmlToLatex` | Information Expert |
| The text of a PDF as a text | `PdfEngine.text` (the engine given by the application), `DocumentScreen` | Information Expert, Low Coupling |
| A shift kept in Unicode or a formula | `ScriptShift` (`pageboard-html`), `MarkdownWriter` | Information Expert |
| RTF as the notation | `RtfToMarkdown` (`pageboard-html`), the clipboard of each platform | Information Expert, Polymorphism |
| The notation as HTML | `MarkdownToHtml` (`pageboard-html`), `EditorCommands.copy`, the clip entry of each platform | Information Expert, Polymorphism |

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | `.html` files imported; the ZIP entries and their inflating; `.docx` files imported; tests | Done |
| 2 | The text of a PDF chosen as a text; tests | Done |
| 3 | Subscripts and superscripts kept; RTF pasted; tests | Done |
| 4 | Copying as HTML on every platform; tests | Pending |

## Decisions

Taken alone, revertible:

1. **No new notation for the shifts**: Markdown has none, and Pandoc's `^x^` and `~x~` would change
   how notes written before read (a `~` or a `^` in them). Unicode where it can, a formula elsewhere.
2. **A Word document is read into the tree of HTML of the paste** (`DocxToMarkdown`), which
   `MarkdownWriter` writes: a document and what Word copies read the same, through one writer.
   Without formatting, or when the writer would lose words, its plain text is taken.
3. **The archive is read by its central directory** (`ZipEntries`), not by the local headers as the
   archives of the application are (`ZipReader`): Word writes the sizes after the data. Deflate is
   the platform's (`java.util.zip.Inflater`, zlib on iOS), to the size the directory gives and no
   more, at most 64 MiB and 1000 times the compressed size.
4. **On Android the text is chosen by its media types** (`OpenDocument`): `GetContent` takes one type,
   and a Word document is not `text/*`.
5. **A PDF is a text only when chosen in the import of a text**: dropped, or chosen in the import of
   a PDF, it stays pages of PDF as before. The engine reads it from a file of its own, written in
   the resources and deleted after (`MediaManager.importPdfText`), since it reads files by their path.
6. **A shift of Unicode wins over a formula**: `x²` stays text that searches and copies find; a
   formula only for what Unicode has no shifted form of (`q`, most capitals of the subscripts), with
   `\text{}` around letters; a script LaTeX would read as commands stays as it is, unshifted.
7. **RTF is read in the code page 1252** whatever it says (`\ansicpg`): nearly every RTF is, and
   the characters of the others come as `\u` escapes too.

## Left for later

- Images pasted or imported as image elements next to the text: a plan of their own (where they go,
  their size, an image pasted alone).
- The task of the assistant of its own for "notes from a text" (`docs/plans/text-import.md`,
  "Left for later"): the model of the review answers it today; the lecture assistant on local
  models (`CLAUDE.md`, "Planned work") will change its models.
