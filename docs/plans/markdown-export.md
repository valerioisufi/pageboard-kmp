# Markdown export of a note, with the notes of its lectures

The export sheet offers a third format for one note: Markdown, the text of the note followed by
the outlines, the summaries and the notes of its lectures. `docs/plans/export-ux.md` left "Exporting the
notes of the lectures (Markdown) from the same sheet" for later. This document starts from the code
as it is (main at 9d7e1184, 2026-11-14).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The export** (`drawview-export`): `ExportFormat` PDF and IMAGES for one note, a PDF per note
   or one combined for several; `DocumentExport.export` writes them with `ExportWriters`; the sheet
   (`drawview-export-ui`, `DocumentExportUi`) offers the formats of the scope.
2. **The texts of a note** are Markdown with LaTeX formulas (`DrawText.text`), boxes placed on the
   pages.
3. **The lectures** (`drawview-lecture`) are records kept apart from the documents
   (`LectureLibrary`, `lecture.json` per lecture): an outline, a final review (the summary), the
   notes the assistant suggested with their kind and their minute, the transcript. The transcript
   alone can be exported as text from the panel of the lecture.
4. `drawview-export` cannot depend on `drawview-lecture` (`docs/plans/modularization.md`): the export of
   the lectures must come from the application.

## Requirements

1. **Markdown for one note** in the sheet (the library and the editor), with the pages chosen (all,
   the page on screen, the pages selected, a range), shared or saved as `Name.md`.
2. **The text**: the name of the note as its title, the texts of each page in the order they are
   read (from the top, then from the left), a rule between two pages, the pages without a text left
   out. The strokes, the images and the PDFs are not text.
3. **The lectures after it**, the oldest first: the date and the length of each, its outline, its
   summary, its notes not dismissed grouped by kind with their minute. The headings of the
   assistant go under those of the lecture. Nothing when there is no lecture.
4. In the language of the application: the headings of the lectures, the names of the kinds, the
   dates as the panel writes them.
5. A note with no text and no lecture cannot be exported as Markdown ("nothing to export").
6. **Nothing stored changes**: the lectures are read as they are (none recovered while a lecture is
   being recorded); no migration.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The format, its extension (`md`) and media type (`text/markdown`), offered for one note | `ExportFormat.MARKDOWN`, `ExportScope.formats` (`drawview-export`) | Information Expert |
| Writing a note as Markdown, the texts in the order they are read | `MarkdownExporter` (`drawview-export`), in `ExportWriters.markdown` | Pure Fabrication, Polymorphism (a writer per format) |
| What the application adds to the Markdown of a note | `MarkdownAppendix` (`drawview-export`, a port) | Protected Variations, Indirection (the export knows no lectures) |
| Exporting: the content of the note, nothing to export, the file | `DocumentExport.exportMarkdown` | Controller |
| The lectures of a note as Markdown | `LectureMarkdown` (`drawview-lecture`, headless), `LectureLibrary.records` (read only), `LectureAssistant.markdownOf` | Information Expert, Pure Fabrication |
| The words in the language of the application | `lectureMarkdownLabels`, `LectureAssistant.markdownInLanguage` (`drawview-lecture-ui`) | Pure Fabrication |
| The format in the sheet; the appendix given by the application | `ExportSheet`, `DocumentExportUi(markdownAppendix)` (`drawview-export-ui`); `PencilNotesServices` | Controller, Low Coupling |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `ExportFormat.MARKDOWN`, `MarkdownExporter`, `MarkdownAppendix`, `DocumentExport.exportMarkdown`; `LectureMarkdown`, `LectureLibrary.records`, `LectureAssistant.markdownOf`, `lectureMarkdownLabels`, `markdownInLanguage`; the format in the sheet (its card, no paper option), `DocumentExportUi(markdownAppendix)`; PencilNotes giving the lectures; the strings (seven languages in the export, English and Italian in the lectures, as the module has); tests; ABI recorded on purpose | Done |

### Phase 1 results

* Tests: `MarkdownExporterTest` (`drawview-export`: the reading order, the rule between pages, a page
  without text left out, the appendix after the texts, a note without text, the format offered for
  one note only); `LectureMarkdownTest` (`drawview-lecture`: a lecture with its sections, the notes
  by kind and minute, a note dismissed left out, the order of the lectures, a lecture with nothing
  left out, the headings of the assistant nested).
* The sheet shows three cards for one note (checked on the image rendered on Windows); the option of
  the paper is hidden for Markdown. The reference `export-sheet-light` changes with the third card:
  it is to be recorded again on the Mac (`recordRoborazziJvm` of `drawview-export-ui`), the
  references being of the Mac.
* The klib dumps of the modules with Compose written by hand on Windows.

## Decisions

Taken alone on 2026-11-14 (the user asked to go on without asking), every one revertible:

1. **One Markdown file for the note and its lectures**, rather than a file of the lectures alone:
   the text the student wrote and what the lecture added belong together.
2. **No transcript**: it is long, and it is exported on its own from the lecture.
3. **The handwriting is not read**: the Markdown has what is typed and what the assistant wrote.
4. **The formulas stay LaTeX between `$`**, as the notes write them, readable by Obsidian, Typora,
   Pandoc.
5. **The images and the PDF pages are not written** (no files beside the Markdown): an archive with
   them is the archive of the application (`docs/plans/archive.md`).
6. **The appendix is a port given by the application**, so that `drawview-export` keeps knowing no
   lecture, and another application can add what it has.

## Left for later

* The lectures alone, as Markdown, from the panel of the assistant.
* Markdown for several notes (one file each in a ZIP).
* The images of the pages beside the Markdown.
