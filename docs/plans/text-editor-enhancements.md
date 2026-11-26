# Text editor enhancements: emphasis, links, fonts, LaTeX completion, snapping, and lasso formatting

The features left for later in `docs/plans/text-editing.md`, `docs/plans/selection-formatting.md`,
and `docs/plans/linear-parsing.md`, together with the consolidated Room and sync protocol upgrade
shared with `docs/plans/list-numbering.md` and `docs/plans/text-boxes.md`. This document starts from
the code as it is on `implement_text_editor_features` (Room version 25, `PROTOCOL_VERSION` 4).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Schema and protocol.** `TextElement`, `TextData`, and `TextEntity` (Room 25) have `background`,
   `next`, and `continuesLine`, but no field for a custom font (`fontFamily`), cross-box list
   continuation (`continueNumberingFrom`), or manual chain box height (`maxHeight`).
   `PROTOCOL_VERSION` is 4.
2. **Emphasis.** `RichTextParser` scans `*`, `**`, `_`, `__` independently in five linear passes:
   `***x***` parses as bold `*x` followed by a literal `*`, and flanking/rule-of-3 rules of
   CommonMark are not implemented.
3. **Links and images.** `RichTextParser` parses `[text](url)` (`Kind.LINK`), drawn underlined in
   the editor and on the page, with its URL shown in `SourceStrip`. Bare URLs (`https://…`, `www.…`)
   stay plain text, `![alt](url)` is skipped, tapping a link does not open it, and exported PDFs do
   not emit `/Subtype /Link` annotations.
4. **Lasso formatting and placement snapping.** `SelectionManager.SelectedTexts` delegates size,
   color, alignment, and background, with `formatsCharacters = false`, so character formatting
   cannot be applied to lasso-selected texts. `PlacementManager` drags and places notes freely without
   calling `SnapManager`.
5. **Fonts.** Texts always use `NotesFonts.text` (Noto Sans), although `plus_jakarta_sans_*.ttf` and
   `noto_sans_mono_*.ttf` are already bundled in `pageboard-ui`.
6. **LaTeX completion.** Typing `\` inside a formula shows no command completion list.

## Requirements

1. **Consolidated schema & protocol upgrade.** Add optional nullable fields `fontFamily`,
   `continueNumberingFrom`, and `maxHeight` to `TextElement`, `TextData`, and `TextEntity` in a
   single Room migration (26 → 27, after the layers of `docs/plans/z-order-layers.md` took 26) tested with `MigrationTestHelper` and one sync protocol bump
   (`PROTOCOL_VERSION` 4 → 5). Existing notes keep `null` defaults without losing data.
2. **Full CommonMark emphasis.** `***x***` parses as bold + italic, alongside nested emphasis,
   Unicode/ASCII flanking rules, and the rule of 3, all in strict `O(n)` time (`RichTextParserBoundsTest`
   passes within bounds on 1 MiB inputs on JVM and iOS simulator).
3. **Autolinks, images, link tapping, and PDF link annotations.**
   - Bare URLs (`https://`, `http://`, `mailto:`, `www.`) become autolinks (`Kind.LINK` with empty
     delimiters); `![alt](url)` becomes `Kind.IMAGE` with its URL in `SourceStrip`.
   - Only `http`, `https`, and `mailto` schemes are openable (`www.` normalized to `https://`).
   - Tapping a link on the page (in read/pan mode or with Cmd/Ctrl) or Cmd/Ctrl-clicking / tapping the
     open-link action in `SourceStrip` opens it via the platform URI handler.
   - `PdfExporter` writes PDF link annotations (`/Subtype /Link`) for links in text elements.
4. **Lasso character formatting & placement snapping.**
   - `SelectionManager.SelectedTexts` supports character-level and block-level toggles across
     selected texts as one `UpdateTexts` undo step.
   - `PlacementManager` snaps dragged/placed notes via `SnapManager` (lines, margins, other
     elements, lined paper baseline), respecting `Alt`/⌥ free movement and clearing guides on finish.
5. **Custom font selection.** Users can pick Sans (Noto Sans), Jakarta (Plus Jakarta Sans), Mono
   (Noto Sans Mono), or Serif on edited texts, lasso-selected texts, and tool defaults; pages, editor,
   thumbnails, and PDF export render with the chosen font.
6. **LaTeX command autocomplete.** Typing `\` followed by letters inside a formula shows a compact
   completion popup (`LatexCompletionPopup`) with Tab/Enter/tap acceptance and Escape dismissal.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Stored text attributes (`fontFamily`, `continueNumberingFrom`, `maxHeight`) | `TextElement`, `TextData` (`pageboard-model`), `TextEntity`, `Migration26To27` (`pageboard-store-room`), `PROTOCOL_VERSION = 5` (`pageboard-sync-protocol`) | Information Expert, Protected Variations |
| Linear CommonMark emphasis, `Kind.IMAGE`, bare autolinks, safe URL helper | `RichTextParser`, `CommonMarkEmphasis` (`pageboard-model`) | Information Expert, Pure Fabrication |
| Link bounds and hit testing in laid-out texts | `TextTileAdapter.linkAt`, `TextLink` (`pageboard-canvas`), `ComposeTextTileAdapter` (`pageboard-ui`) | Information Expert, Indirection |
| PDF link annotations | `PdfWriter.addLink` (`pageboard-pdf`), `pdfium_page_add_link` (`native/bridge`), `PdfExporter` (`pageboard-export`) | Protected Variations, Polymorphism |
| Opening links from canvas & editor | `PageboardController.onOpenLink`, `CanvasGestureHandler` (`pageboard-canvas`), `TextBoxEditor`, `SourceStrip` (`pageboard-ui`) | Controller, Low Coupling |
| Lasso character/block formatting | `SelectionManager.SelectedTexts` (`pageboard-canvas`) | Polymorphism, Information Expert |
| Snapping lecture notes during placement | `PlacementManager`, `CanvasGestureHandler` (`pageboard-canvas`) | Controller, Low Coupling |
| Text font catalog, resolution, and selector | `TextFont` / `TextFonts` (`pageboard-model`), `NotesFonts.familyFor` (`pageboard-ui`), `FontFamilyButton` (`pageboard-ui`) | Information Expert, Protected Variations |
| LaTeX command catalog, matching, and popup | `LatexCommands` (`pageboard-model`), `LatexCompletionPopup`, `TextBoxEditor` (`pageboard-ui`) | Pure Fabrication, Controller |

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | Consolidated schema & protocol foundation: `fontFamily`, `continueNumberingFrom`, `maxHeight` in `TextElement`/`TextData`/`TextEntity`, Room 26 → 27 (`Migration26To27`), `PROTOCOL_VERSION = 5`, `ProtocolFormatTest` | Done |
| 2 | Full CommonMark emphasis (`***x***`, flanking, rule of 3) in linear time; parser & bounds tests | Done |
| 3 | Autolinks, `![image](url)`, tap-to-open links (canvas, editor, `SourceStrip`), and PDF `/Link` annotations | Done |
| 4 | Lasso character-level formatting (`SelectedTexts`) and `PlacementManager` snapping | Done |
| 5 | Custom font selection (`TextFonts`, `NotesFonts`, `ComposeTextTileAdapter`, `TextBoxEditor`, `TextStyleControls`, PDF export) | Done |
| 6 | LaTeX command autocomplete while typing (`LatexCommands`, `LatexCompletionPopup`, `TextBoxEditor`) | Done |

## Decisions

Taken by the maintainer on 2026-11-25:

1. **Consolidated Room migration (written 25 → 26, renumbered 26 → 27 when rebased on the layers of `docs/plans/z-order-layers.md`) and `PROTOCOL_VERSION` 4 → 5** for all new `TextElement`
   fields (`fontFamily`, `continueNumberingFrom`, `maxHeight`) across stages A, B, and C.
2. **Proceed phase by phase** on `implement_text_editor_features` with one atomic commit per phase.

Taken alone, revertible:

3. **Keep `~~` strikethrough** as a standalone linear scan alongside `CommonMarkEmphasis` (`*`, `_`).
4. **Normalize `www.` autolinks to `https://`** on open/export; reject non-`http`/`https`/`mailto`
   schemes in `openableLinkUrl` to avoid unsafe URIs from untrusted synced notes.
5. **Use already-bundled font assets** (`Noto Sans`, `Plus Jakarta Sans`, `Noto Sans Mono`, plus
   system `Serif`) for `TextFonts` so every device renders the same bundled fonts without inflating
   binary size.

## Left for later

- Real-device manual verification of Android/iOS IME, handles, and Apple Pencil Scribble.
- The stages after this one, each in a plan of its own:
  - **B, checklists and lists** (`docs/plans/checklists-and-lists.md`): done.
  - **C, boxes, chains, tables and quotes** (`docs/plans/boxes-tables-quotes.md`): done.
  - **D, blocks of code** (`docs/plans/code-blocks-more.md`): done.
  - **E, fidelity, autosave and conflicts**: a format chosen at the caret before typing, the whole
    source on demand, the strip of the source on small screens, selecting into and across blocks
    of code, conflicts of copies converging, a text deleted elsewhere while written kept as new.
  - **F, import, clipboard and assistant**: `.html`, `.docx` and the text of a PDF imported as
    text, RTF and `<sup>`/`<sub>` pasted, images pasted as images, copying as HTML, a task of the
    assistant for notes from a text.
  - **G, writing suggestions**: the next words from a local model, the lecture being transcribed,
    suggestions over lines, in handwriting.
