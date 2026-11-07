# The dates of the notes exported

When several notes are exported in a ZIP ("One PDF per note"), every file of the archive comes
out dated 1 January 1980, and the PDFs themselves tell nothing of when the notes were written.
Whoever unpacks the archive loses the order of the notes by date, which the library shows. This
plan gives the files exported the dates of their notes.

This document starts from the code as it is (main at af9631de, 2026-11-07). The responsibilities
are assigned following the GRASP principles.

## Starting point

* `ZipWriter` (`drawview-export`, internal) writes every entry with a fixed DOS date and time:
  `0x21` and `0`, that is 1980-01-01 00:00, in the local header and in the central directory. It
  writes no extra field and no entry for the directories.
* `DocumentExport.exportPdfPerDocument` adds one PDF per note at `FolderDocumentItem.relativePath`;
  the `DocumentSummary` of the note is at hand (`createdAt`, `modifiedAt`, in milliseconds since
  the epoch, UTC), and is not passed on.
* `DocumentExport.exportPages` (IMAGES, several pages) builds a ZIP of PNGs the same way.
* `PdfWriter.save(path, PdfInfo(title, creator))` writes the Info dictionary of the PDF: `/Title`
  and `/Creator` only, no `/CreationDate` and no `/ModDate`. `DocumentContent` has no dates.
* The folders of the archive are made by the unzipping tool, dated when it unpacks them.

What a reader of ZIP files takes:

| Field | Where | Read by |
|---|---|---|
| DOS date and time (local time, 2 s, 1980–2107) | Local header and central directory | Every tool |
| Extended timestamp `0x5455` (UTC seconds) | Extra field | Info-ZIP `unzip`, macOS (Archive Utility, `ditto`), 7-Zip, libarchive |
| NTFS times `0x000A` (modification, access, creation; UTC, 100 ns) | Extra field | Windows Explorer, 7-Zip (the creation date too) |

## Requirements

1. Every PDF of a ZIP of notes is dated by the last change of its note (`modifiedAt`), in every
   unzipping tool, and by its creation (`createdAt`) where the tool keeps it.
2. The folders of the archive are dated by the folders of the library (`FolderSummary.modifiedAt`).
3. The PNGs of the pages of a note in a ZIP are dated by the last change of the note.
4. Every PDF exported (one note, one per note, all together) tells in its Info dictionary when the
   note was created and last changed (`/CreationDate`, `/ModDate`).
5. Nothing stored changes: no Room schema, no format of the documents, no protocol.

## Design

| Responsibility | Who | Pattern |
|---|---|---|
| Turning an instant into the fields of a ZIP entry (DOS in the local zone, `0x5455`, `0x000A`) | `ZipWriter`, from a `ZipEntryTimes(modified, created)` given to `addEntry` and to a new `addDirectory` | Information Expert (the writer knows its format), Pure Fabrication |
| The local time zone of the DOS fields | A `TimeZone` given to `ZipWriter` (the system one by default, a fixed one in the tests) | Protected Variations |
| Which date an entry takes | `DocumentExport`, which has the summaries of the notes and of the folders | Information Expert, Controller |
| The paths of the folders in the archive with their summaries | `DocumentExport.resolve`: `ExportContents.folders` (path → `FolderSummary`) beside `documents` | Information Expert |
| The dates of a PDF | `PdfInfo(createdAt, modifiedAt)`, written by `PdfWriter.save` in the PDF date format (`D:YYYYMMDDHHmmSS+HH'mm'`) | Information Expert |
| The dates of what is exported | `DocumentContent(createdAt, modifiedAt)`, filled by `DocumentExport.content` from the summary | Creator (the use case has the summary) |
| The dates of a combined PDF | `PdfExporter.exportCombined`: the oldest creation, the latest change of its notes | Information Expert |

`ZipWriter` stays internal. The new parameters of `PdfInfo` and `DocumentContent` are optional
and at the end, so the callers of today keep compiling; the ABI dumps of `drawview-pdf` and
`drawview-export` change.

## Phases

| # | What | Status |
|---|---|---|
| 1 | `ZipWriter` with the dates of each entry (DOS in the local zone, clamped to 1980–2107; `0x5455` with the modification; `0x000A` with modification and creation) and the entries of the directories; `DocumentExport` dates the PDFs of the notes, the folders (`ExportContents.folders`) and the PNGs of the pages; tests that read the archive back with `java.util.zip.ZipFile` (last modified and creation times), and with `unzip -l` by hand | Done |
| 2 | `PdfInfo` and `DocumentContent` with the dates; `/CreationDate` and `/ModDate` in the PDF of one note, of each note and of all of them together; tests on the Info dictionary written; `updateKotlinAbi` | Proposed |

## Decisions

Revertible, taken alone:

* **The DOS fields in the local time zone** of the device exporting, as every ZIP tool expects;
  the extra fields carry UTC, which wins where they are read.
* **Both extra fields** (`0x5455` and `0x000A`): 9 + 36 bytes per entry, so that macOS, Linux and
  Windows all keep the dates; the creation date only where the format holds it (NTFS).
* **The access time** of `0x000A` is the modification, as the archive is not an access.
* **A combined PDF** is dated from the oldest creation to the latest change of its notes, not by
  the moment of the export.
* **The folders** get an entry of their own only when they hold something exported (as today,
  no empty folder appears).
* **The archive is written in the order of the tree**: each directory just before all it holds,
  the notes of a directory before its subdirectories, the directories by name. `ditto` (and the
  Archive Utility of macOS) dates a directory when it leaves it, and loses the date when a later
  entry goes back into it; `unzip` dates the directories at the end. The progress follows the
  same order.

## What was checked

* Phase 1, an archive of `FolderExportTest` unpacked on macOS: `unzip` keeps the dates of the
  notes and of the folders to the second; `ditto` keeps them too, but puts the summer dates one
  hour later, as it does for the archives of `/usr/bin/zip` of macOS itself (it reads the DOS
  fields without the summer time): a fault of the tool, not of the archive.

## Left for later

* The date of the file written on disk for a single PDF or PNG: it is a new file, written where
  the system dialog chose (on Android a `content://` whose date cannot be set); its PDF carries
  the dates.
* Other metadata of the PDF: `/Author`, `/Keywords` (from tags, if they come), `/Subject`.
