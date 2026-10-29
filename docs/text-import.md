# Importing text

A text written elsewhere (notes of a friend, a summary written with a chat assistant, a page of a
book, an exported document) brought into a notebook: from a file or from the clipboard, cleaned
into the Markdown of the notebook, placed next to the pages it is about, in the style of the page;
and, when the student asks, turned by a model into notes of a kind, each next to the words of the
notebook it explains. This document starts from the code as it is (`feat/placement-text-size` at
ccf5ef78, 2026-10-29, after `docs/auto-placement.md` and `docs/placement-text-size.md`): what can be
done with a text today, then the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Pasting only.** A text of 600 characters or more, of several paragraphs, pasted on a page
   (`TextEditorManager.pasteText`) is flowed through the free space of that page, then onto new
   pages after it (`TextFlow`), with the preview of the placement, in the style of a new text
   (`textDefaults`). A shorter text becomes one text in the middle of the page. No file can be
   imported: the file pickers know images and PDFs only (`PickerType.IMAGE`, `PDF`; the menu
   Insert has "Import PDF" and "Import image"; a PDF or an image dropped on the canvas is
   imported, `fileDropTarget`).
2. **Taken as it is.** The text is read as the Markdown of the notebook: a plain text keeps its
   hard line breaks (a text copied from a PDF breaks every line), its bullets ("•", "–"), its
   headings as plain lines, its words cut by a hyphen at the end of a line.
3. **By topic for the review only.** The sections of the review of a lecture go next to the pages
   they are about (`TextFlow.sectionsOf`, `TopicFinder` of `drawview-lecture`, given by
   PencilNotes from `SearchEngine.pagesAbout`). The editor does not know it: a pasted text goes
   where it is pasted.
4. **Notes of a kind come from the lecture only.** The lecture assistant asks its models for notes
   (`AnalysisNote`: kind, title, content, page, anchor) and places them by their kind, next to the
   words they quote, at the size of their page (`NoteInserter.startPlacementAll`, `NotePlacement`,
   `TextSizePolicy`). Nothing turns an imported text into such notes.
5. **The models.** The lecture assistant has the backends of its tasks (Gemini, Ollama on a
   computer of the network, the local models), chosen in its settings (`AssistantSettings.task`);
   a request to Gemini is billed.
6. **The extension points.** `EditorExtension` gives the editor commands (`commands`), and
   `DrawViewEngines` the engines given by the application (PDF, formulas, elements, code).
   `EditorExtension` is a class of lambdas: a new hook breaks its binary compatibility once
   published (see CLAUDE.md, "Planned work").

## Requirements

1. **"Import text…"** in the menu Insert (with ⇧⌘T, Ctrl+Shift+T elsewhere), in the menu of the
   page and among the imports of the toolbar: a `.md` or `.txt` file; a text file dropped on the
   canvas does the same.
2. **A sheet before the placement**, also offered when a long text is pasted:
   * where: **by topic** (each section next to the pages it is about; when the search is there),
     **from this page** (its free space, then new pages after it), **at the end** of the notebook;
   * **clean the formatting** (on for a `.txt` and a pasted text, off for a `.md`), with a short
     preview of the first lines as they will be;
   * **as notes, with the assistant** (when the lecture assistant is there): see requirement 5.
3. **Cleaning without a model**, deterministic and the same on every device:
   * the lines of a paragraph broken by the width of the source joined, a word cut by a hyphen at
     the end of a line joined;
   * bullets ("•", "◦", "–", "*", "·") as "-", numbered items ("1)", "a.") as "1." / kept;
   * a short line alone between blank lines, without a final period, followed by a paragraph, as a
     heading ("##"); a numbered title ("2.3 Limits") as a heading of its level;
   * formulas, code blocks and what is already Markdown left as they are.
4. **The imported text is the student's**: their color, at the size of the page it goes on
   (`TextSizePolicy`, like the notes), placed with the preview, undone in one step.
5. **As notes, with a model** (asked explicitly, never by default):
   * the text and the description of the notebook (the pages as the lecture assistant describes
     them) are sent to the model of the review of the lecture assistant, which answers notes of a
     kind with their page and their anchor (the format of the notes of the lecture);
   * the notes are placed as the notes of a lecture: by kind, next to the words they quote, at
     the size of their page, in the blue of the assistant (they were written by it);
   * with Gemini, the sheet shows the estimate of the request (characters, tokens) before sending;
     a local model is free;
   * what the model leaves out of the text is not lost: the sheet says how many characters of the
     text are in the notes, and offers to place the rest as text.
6. **It degrades**: without the search, no "by topic"; without the lecture assistant or its models,
   no "as notes"; without a measure of the text, heights estimated.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| A plain text turned into the Markdown of the notebook (paragraphs, hyphens, bullets, headings), Markdown left as it is | `TextCleanup` (`drawview-model`, package `text`) | Pure Fabrication, High Cohesion |
| The pages of the document a text is about, given by the application (the search) | `PageTopics` (fun interface, `drawview-canvas`), an engine of `DrawViewEngines.topics` | Indirection, Protected Variations (the search behind an interface) |
| The sections of a text placed by topic, from a page or at the end, at the size of their page, in the style given (the student's or the assistant's) | `TextPlacer` (`drawview-canvas`), out of `NoteInserter.startReviewPlacement` | Controller, Creator; the review uses it too (High Cohesion) |
| The file chosen or dropped, the sheet of the import, the paste of a long text opening it | `TextImportSheet`, `PickerType.TEXT` (`drawview-ui`), `EditorCommands.importText` | Controller |
| A text turned into notes of a kind by a model, with their page and anchor | `TextToNotes` (`drawview-lecture`): its prompt and schema next to those of the lecture | Information Expert (the assistant knows its models, its prompts and its notes) |
| "As notes" offered in the sheet when the lecture assistant is there | a command of the extension of the lecture assistant (`EditorExtension.commands`, existing), opened by the sheet by its id | Polymorphism, Low Coupling (no new hook in `EditorExtension`) |
| The estimate of a request before sending it | `TextToNotes.estimate` (characters and tokens, as `docs/lecture-cost.md` counts them) | Information Expert |

### By topic for every text

`TopicFinder` (in `drawview-lecture`, for the review) becomes `PageTopics` in `drawview-canvas`,
given by the application with the engines (`DrawViewEngines(topics = …)`): the editor and the
lecture assistant read it from the controller (`controller.engines.topics`). PencilNotes gives it
from `SearchEngine.pagesAbout` as today; the sample gives none. `LectureAssistant.topicFinder` is
removed (no stored or published data use it).

### The placement of a text

`NoteInserter.startReviewPlacement` (parts by page, flowed, one size per part) moves into
`TextPlacer` of the canvas, with the style as a parameter (color; size from the page by
`TextSizePolicy`); the review calls it with the blue of the assistant, the import with the color
of a new text. `pasteText` of a long text opens the sheet instead of placing directly.

### Notes from a text

`TextToNotes` asks the model of the task of the review (`LectureTask.FINAL_REVIEW`) with:

* the instructions of the notes of the lecture (kinds, Markdown, anchor) adapted: "turn this text
  into notes for this notebook; keep everything important; never add what the text does not say";
* the pages of the notebook described as for the review (`LecturePrompts.pagesText`, bounded);
* the text, bounded (beyond the bound the sheet offers the text without notes).

The answer is the list of notes of the review (`FinalReview.missing` without the Markdown of the
review), read with `LectureAnalysis` (its depth checked as every answer). The notes are placed by
`NoteInserter.startPlacementAll`, without becoming suggestions of a lecture.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | `TextCleanup` in `drawview-model`: tests on texts copied from a PDF (broken lines, hyphens), from a chat, with bullets, numbered titles, Markdown, formulas and code left as they are | done |
| 2 | `PageTopics` in `DrawViewEngines`, `TextPlacer` in the canvas (the review and the long paste moved onto it), PencilNotes wiring; the tests of the review kept | done |
| 3 | "Import text…": `PickerType.TEXT` on every platform, the command and its shortcut, the file dropped, the sheet (where, clean, preview), the long paste opening it (`TextImport`, `DrawViewController.pendingTextImport`, `TextPlacer.startImport`). Screenshot of the sheet | done |
| 4 | "As notes, with the assistant": `LecturePrompts.textNotesPrompt`, `LectureAnalysis.textNotesSchema`, `TextNotes`, `LectureAssistant.estimateTextNotes` and `textNotes`, the command and the dialog of the lecture extension (`TextNotesDialog`), the notes placed; tests with a fake answer. No paid request is run by the tests | done |

## Decisions

Taken alone, revertible:

1. **`.md` and `.txt` only**: a `.docx`, an `.html` or a PDF of text need converters of their own
   (left for later); the clipboard covers most of them as plain text.
2. **Cleaning by rules, not by a model**: deterministic, free, the same on every device; the model
   is for turning a text into notes, asked explicitly.
3. **The imported text is the student's** (their color); **notes from a model are the
   assistant's** (its blue).
4. **`PageTopics` is an engine** of `DrawViewEngines`, not a hook of `EditorExtension`: an engine is
   given by the application as the PDF one, and `EditorExtension` stays as it is.
5. **"As notes" lives in the lecture assistant**: its models, settings, prompts and notes are
   there; the editor only offers it when the extension gives the command.
6. **The model of the review** answers the notes from a text: the task that already reads the whole
   notebook.

## Left for later

* `.docx`, `.html`, the rich text of the clipboard (its HTML flavor), the text of a PDF chosen as a
  text.
* Images in an imported Markdown (`![...](...)`): kept as text today.
* A task of its own for "notes from a text" in the settings of the assistant, if the model of the
  review is not the right one.
