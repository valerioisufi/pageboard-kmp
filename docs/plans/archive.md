# The archive of the application: export and import

A format of the export sheet that keeps notes whole (every page, every element, the removed ones,
the PDFs and the images) in one file, and "Import archive…" in the library bringing them back, on
the same device or on another one. `docs/plans/export.md` left `ArchiveExporter` and its import for later,
"to define near the first release, when its format becomes a contract". This document starts from
the code as it is (main at ee07934d, 2026-11-14); the branch is stacked on `feat/markdown-export`
(the two change the formats of the sheet).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The whole state of a note** is `DocumentSnapshot` (`drawview-model`): its pages, the removed
   ones, every element with the stamps of its changes, its resources (their identifiers, types and
   hashes; not their files). The synchronization sends it to a device that receives a note.
2. **The store** adds a note received with all its content (`DocumentStore.insertDocument`), and
   makes folders (`createFolder`).
3. **The export** writes ZIPs (`ZipWriter`, stored entries); nothing reads them back.
4. **Backups** today are the copy of the data directory: all or nothing, for one device.

## Requirements

1. **Export**: "Archive" among the formats, for one note and for several notes or folders; the
   file `Name.drawview`, a ZIP; the folders of the notes kept as paths.
2. **Import**: "Import archive…" in the library; the notes come as **new notes** (new
   identifiers) in a **new folder** named after the file ("Backup", "Backup (2)"...), their folders
   made again under it. **Nothing of the library is changed or replaced**, whatever the archive
   holds (even the notes it was made from).
3. **An archive is untrusted input**: it is read whole and checked (its entries, their checksums,
   the manifest, every note decoded) before anything is added; one that cannot be read adds nothing
   and says why; a later version is refused, never read wrong.
4. **The format is versioned** (`manifest.json`, `version` 1): a later version of the application
   reads the archives of this one; a change of what an archive holds raises the version, with a
   tested reading of the older ones.
5. The seven languages; every platform (the file picker of each one).

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The format: its name, its version, its entries, its extension | `ArchiveFormat` (`drawview-export`) | Information Expert, Protected Variations (the version) |
| Reading the entries of a ZIP, bounded and checked | `ZipReader` (internal) | Pure Fabrication |
| Writing notes as an archive: their snapshots taken in their sessions, their files | `ArchiveExporter` | Information Expert, Creator |
| Bringing an archive back: checked whole, then new notes in a new folder | `ArchiveImporter` | Controller, Creator |
| Why an archive cannot be read | `ArchiveException` (`NOT_AN_ARCHIVE`, `TOO_NEW`) | Information Expert |
| The format in the sheet; exporting it | `ExportFormat.ARCHIVE`, `DocumentExport.exportArchive`, `ExportSheet` | Controller, Polymorphism |
| Choosing the file, the dialog, the action of the library | `PickerType.ARCHIVE` (`drawview-ui`), `ImportArchiveDialog`, `DocumentExportUi.importAction` (`drawview-export-ui`); PencilNotes | Controller, Low Coupling |

### The file

```
Backup.drawview (ZIP, entries stored)
├── manifest.json            {"format": "drawview-archive", "version": 1,
│                             "documents": [{"name": "Limits", "folders": ["Math"],
│                                            "entry": "documents/1/document.json",
│                                            "files": {"res-1": "documents/1/files/res-1.png"}}]}
└── documents/1/
    ├── document.json        DocumentSnapshot as JSON (the keys not known refused)
    └── files/res-1.png      the file of a resource
```

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `ArchiveFormat`, `ZipReader`, `ArchiveExporter`, `ArchiveImporter`, `ArchiveException`; `ExportFormat.ARCHIVE` and its export; the sheet (four formats on two rows, no pages or paper for an archive); `PickerType.ARCHIVE` on Android, the desktop and iOS; `ImportArchiveDialog`, `DocumentExportUi.importAction` in PencilNotes; the strings; `ArchiveTest`; ABI recorded on purpose | Done |
| 2 | An import gives new identifiers to the pages, the elements and the resources of every note, and to every reference to them (`ArchiveIdentifiers`); `ArchiveTest` imports an archive into the library it came from | Done |

### Phase 1 results

* `ArchiveTest`: two notes (one in a folder, with a text and an image) written and imported into
  another library holding a note: a new folder, the folder of the note made again under it, new
  identifiers, the text and the bytes of the image as they were, the note there untouched; a
  second import in "Backup (2)". A file that is not a ZIP, a later version, a note that cannot be
  decoded, a damaged entry: refused, nothing added (no folder either).
* The import of iOS (`UTTypeData` in the document picker) is compiled on the Mac only: not checked
  here (Windows).
* The screenshots of the sheet change (four formats on two rows): `export-sheet-light` is to be
  recorded again on the Mac.
* The klib dumps of `drawview-export`, `-export-ui` and `-ui` written by hand on Windows.

### Phase 2: the identifiers of an import

Found on 2026-11-16 (`docs/plans/android-release.md`, phase 5): an archive of a note imported into the
library that still holds the note took the pages of the original. `ArchiveImporter` gave the note
a new identifier but kept those of its pages, elements and resources, while `drawview-store-room`
keys its pages and elements by their identifier alone: the import wrote them again under the copy,
the original was left with none, and the editor added a blank page to it. `ArchiveTest` imported
into another library only, where identifiers never meet.

* **`ArchiveIdentifiers`** (internal, `drawview-export`; Pure Fabrication): the identifiers of a
  snapshot (its pages and removed pages, their elements and removed elements, its resources) each
  given a new one, replaced everywhere in the JSON of the snapshot (the values and the keys: the
  references of an element to a resource, to the next text, the versions keyed by element), so
  that a reference added later to the model is renewed too. The files of the archive follow their
  resources.
* **`ArchiveImporter`** (Controller) renews the identifiers of every note before it inserts it.
* **Test**: the archive of a library imported into the same library; the notes there keep their
  pages, elements and resource; the copies share no identifier with them; their content and the
  bytes of their files as they were.
* A note whose pages were taken this way is not repaired: its pages are in the copy imported
  (the folder of the import), where the user finds them.

### Phase 2 results

* `ArchiveIdentifiers.renew` in `ArchiveImporter.import`, before every note is inserted; internal,
  so the ABI is the same.
* `ArchiveTest`: `anArchiveImportedIntoTheLibraryItCameFromLeavesTheNotesThereAsTheyWere` (it fails
  without the change: the copy had the identifiers of the original) and
  `theIdentifiersOfASnapshotAreRenewedWithEveryReferenceToThem` (no old identifier left anywhere in
  the JSON, the files following their resources). The tests of `drawview-export` and
  `-export-ui` pass, but `ExportScopeTest.nothingToExportAndUnwritableFilesAreTold`, which fails on
  this Windows host before the change too.
* Not run in the application on a device here: the store of the tests is `KeyValueDocumentStore`,
  where the pages live inside their document; the identifiers shared, which made the loss in
  `drawview-store-room`, are what the test checks.

## Decisions

Taken alone on 2026-11-14 (the maintainer asked to go on without asking), every one revertible until a
version of the format is published:

1. **The snapshot of the synchronization as the note**: one description of a whole note, tested by
   the sync; the archive takes its JSON form. A change of `DocumentSnapshot` is then a change of the
   archive too: the version of the archive grows with it, and its reader keeps reading version 1.
2. **Always new notes, in a new folder**: the safest import (CLAUDE.md: never lose notes). Merging
   an archive into the notes it came from (keeping the newer pieces) is the work of the sync, not of
   an archive.
3. **The lectures are not in the archive**: they are records of their own (`LectureLibrary`), their
   format a migration of its own; the Markdown export carries what they said.
4. **`.drawview`**, a ZIP: readable by any tool, its entries stored (no compression, as the
   writer).
5. **Read whole in memory**: the archives of notes are megabytes; a streaming reader is left for
   later.

## Left for later

* The lectures in the archive, with their records' format.
* Opening a `.drawview` from the system (the type registered with each platform).
* Archives larger than the memory (a streaming reader and writer).
* Choosing the folder of the import.
