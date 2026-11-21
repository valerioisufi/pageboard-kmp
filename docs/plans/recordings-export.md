# Batch recordings export and export concurrency

The export sheet offers a new format, `RECORDINGS`, to export all finished lecture audio recordings
of one or more selected documents or folders into a single ZIP archive preserving the library folder
hierarchy (with an option to include transcripts), while removing concurrency conflicts across
recording exports.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **Document export** (`pageboard-export`, `pageboard-export-ui`): `ExportSheet` exports a single
   note, a selection of items, a folder, or Home via `ExportJobs` (serialized through a `Mutex`) and
   shows progress, cancellation, and outcomes in `ExportStatusHost`. `ZipWriter` buffers the entire
   ZIP in memory (`ByteOutput`), which fits PDFs and images but not large audio archives.
2. **Lecture export** (`pageboard-lecture`, `pageboard-lecture-ui`): `LectureAssistant.exportAudio`
   and `exportTranscript` run immediately in parallel on `Dispatchers.IO`, overwrite a single
   `exportResult` field, write `<target>.frames` alongside the output path in `M4aWriter`, do not
   guard `RecordingFile.pageIndex()` across concurrent reads, and do not prevent `delete` while an
   export is running.
3. **Platform file savers/sharers** (`pageboard-ui`): Android stages exported files directly under
   `cacheDir/export/<suggestedName>` and clears the shared cache directory on each share; iOS uses
   a single `DelegateHolder` that drops earlier picker delegates when a second export finishes while
   a picker or share sheet is still open.
4. `pageboard-export` cannot depend on `pageboard-lecture` (`docs/plans/modularization.md`): lecture
   recordings must be provided through a port supplied by the application.

## Requirements

1. **New format in `ExportSheet`**: `ExportFormat.RECORDINGS` (`zip`, `application/zip`), available
   for both single-document and multi-document scopes when the application supplies lecture
   recordings (`DocumentRecordings`).
2. **Audio-first with optional transcripts**: exports finished lecture recordings as `.m4a` files,
   with a switch in the sheet (`includeTranscripts`, default off) to include `.txt` transcripts
   alongside the audio.
3. **Emulate folder structure**: mirrors the library folder tree and timestamps in the ZIP, placing
   each note's recordings under its folder path (`<folder>/<Note> - <YYYY-MM-DD HH.mm>.m4a`),
   disambiguating duplicates, skipping notes without recordings (without creating empty folders),
   and reporting skipped missing-audio lectures in `ExportOutcome`.
4. **Streaming ZIP**: writes directory/file entries incrementally to disk (`StreamingZipWriter`)
   without buffering all `.m4a` payloads in RAM, respecting cancellation and cleaning up partial
   output and temp files.
5. **No conflicts in concurrent recording exports**: serialize recording/transcript exports in
   `LectureAssistant` with progress and queued count, keep all completion notices in
   `exportResults`, use unique `.frames` temp paths, make `RecordingFile.pageIndex()` thread-safe,
   protect active export directories from deletion, isolate temp staging dirs on Android/iOS, and
   serialize iOS picker/share sheet presentation cleanly.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Recordings format, extension (`zip`), media type | `ExportFormat.RECORDINGS`, `ExportOptions.includeTranscripts` (`pageboard-export`) | Information Expert |
| Port for reading lecture audio & transcripts without coupling | `DocumentRecordings`, `ExportedRecording` (`pageboard-export`) | Protected Variations, Indirection |
| Incremental ZIP writing on disk with CRC-32 | `StreamingZipWriter`, `Crc32` (`pageboard-export`) | Pure Fabrication, High Cohesion |
| Batch recordings orchestration & folder structure | `DocumentExport.exportRecordings` (`pageboard-export`) | Controller |
| Thread-safe page index, unique temp frames, cancellable progress | `RecordingFile`, `M4aWriter`, `exportRecording` (`pageboard-lecture`) | Information Expert, High Cohesion |
| Serialized export queue, multi-result notices, delete guard, batch API | `LectureAssistant`, `ExportableRecording`, `formatRecordingTimestamp` (`pageboard-lecture`) | Controller |
| Running/queued/all-result notices in lecture panel | `LectureHeader` (`pageboard-lecture-ui`) | Controller |
| Isolated staging dirs & serialized iOS presentation | `rememberFileSaver`, `rememberFileSharer` (`pageboard-ui`) | Protected Variations |
| Format card, transcript switch, wiring | `ExportSheetState`, `ExportSheet`, `DocumentExportUi` (`pageboard-export-ui`), `PencilNotesServices` | Controller, Low Coupling |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | GRASP plan; lecture & platform export concurrency fixes (`RecordingFile`, `M4aWriter`, `AudioCodec`, `LectureAssistant`, `LectureHeader`, Android & iOS `FileSaver`); `StreamingZipWriter`, `DocumentRecordings`, `ExportFormat.RECORDINGS`, `DocumentExport.exportRecordings`; `ExportSheet` / `DocumentExportUi` / `PencilNotesServices` wiring and 7-locale strings; tests and ABI update | Done |

### Phase 1 results

* Tests:
  * `RecordingsExportTest` (`pageboard-export`): `StreamingZipWriter` multi-chunk streaming and timestamps, batch export folder hierarchy, duplicate name/timestamp disambiguation, `includeSubfolders`, optional transcripts (`includeTranscripts`), skipped missing-audio lectures, `NOTHING_TO_EXPORT` / `NOT_WRITABLE` errors, coroutine cancellation cleanup.
  * `ExportSheetStateTest` (`pageboard-export-ui`): `ExportFormat.RECORDINGS` visibility with/without `DocumentRecordings`, `.zip` / `application/zip`, ignoring page range errors, `includeTranscripts` persistence and fallback.
  * `RecordingExportTest` (`pageboard-lecture`): concurrent `LectureAssistant.exportAudio` serialization, keeping all `exportResults` (including errors), delete protection during active export, `exportableRecordings` filtering/ordering, `writeRecordingAudio` encoding and cancellation, `formatRecordingTimestamp`.
* Verified `jvmTest` across `:pageboard-export`, `:pageboard-export-ui`, `:pageboard-lecture`, `:pageboard-lecture-ui`, `:pageboard-ui`, plus `checkKotlinAbi`, Android debug builds (`:pencilnotes-android:assembleDebug`, `:sample-android:assembleDebug`), and iOS builds (`compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`, `:pencilnotes-shared:compileKotlinIosSimulatorArm64`).

## Decisions

Taken on 2026-11-21, every one revertible:

1. **Port `DocumentRecordings` on `ExportWriters` / `DocumentExportUi`** (mirroring
   `MarkdownAppendix`): keeps `pageboard-export` independent of `pageboard-lecture` and avoids
   showing the format in apps that do not integrate lectures (`sample`, screenshot tests).
2. **Flat per-note prefix inside the library folders** (`<folder>/<Note> - <YYYY-MM-DD HH.mm>.m4a`)
   rather than an extra folder per note: keeps single-lecture notes uncluttered while keeping multi-lecture
   notes grouped and sortable inside their library folders.
3. **Serialize single-lecture exports inside `LectureAssistant`** with unique `.frames` temp paths,
   unique staging subdirectories on Android/iOS, serialized iOS modal presentation, and a list of
   `exportResults`: eliminates races on shared UI state, cache files, and iOS modal presenters while
   giving immediate progress feedback.
4. **Two-pass streaming ZIP (`StreamingZipWriter`)** with STORE method: computes CRC-32 and byte
   length on disk before streaming the file into the ZIP sink, avoiding holding hundreds of
   megabytes of audio in memory.

## Left for later

* ZIP64 support if a single recordings export ever exceeds 4 GiB.
* Selective export of a subset of lectures within a single note directly from the sheet.
