# Paths on Windows

The paths of the platform are split by the platform, never on `/` alone. This document starts
from the checks of 2026-10-18 on the Windows machine of the user, where the lecture evaluation
tool (`tools/lecture-eval`, branch `feat/lecture-eval`) and five tests of `drawview-lecture`
failed because a path listed by Windows has backslashes.

The responsibilities are assigned following the GRASP principles (Information Expert, Pure
Fabrication, Protected Variations).

## Starting point

* Paths are built with `/` (`"$root/doc-$id"`), which every platform accepts, and split with
  `substringAfterLast('/')` / `substringBeforeLast('/')`. A path given by the file system of
  Windows (`File.absolutePath`, `kotlinx.io.files.Path.toString()`) has `\`, so on Windows:
  * **`dataDirectory()`** (`drawview-platform`) is
    `resourcesDirectory().substringBeforeLast('/')`: the resources directory has no `/`, so the
    data directory is `<data>\resources`, not `<data>`. Everything placed beside the resources
    is inside them on Windows:

    | What | Owner | On macOS, Linux | On Windows today |
    |---|---|---|---|
    | The lectures: `device-id`, `assistant-settings.json`, `doc-*/` | `drawview-lecture` | `<data>/lectures` | `<data>\resources\lectures` |
    | The speech models of whisper.cpp | `drawview-lecture` | `<data>/whisper` | `<data>\resources\whisper` |
    | The thumbnails of the documents (a cache) | `drawview-explorer` | `<data>/thumbnails` | `<data>\resources\thumbnails` |
    | The name of the device chosen by the user | `drawview-platform` | `<data>/device-name.txt` | `<data>\resources\device-name.txt` |

    The database, the secure store, the formulas and the window states use
    `drawViewDesktopDataDirectory` directly, and are where they should be.
  * **`Lecture.id`** is `directory.trimEnd('/').substringAfterLast('/')`. `LectureLibrary.list`
    gives the folder of a lecture as `Path.toString()` (backslashes), so the id of a lecture
    loaded from the library is its whole path. A new lecture (`newLectureDirectory`, built with
    `/`) has the right id.
  * The same lecture has two directory strings (`<root>/doc-5/<name>` built,
    `<root>\doc-5\<name>` listed), and `LectureAssistant`, `LecturePanel` and
    `LectureAttachments` compare them with `==`: the lecture being recorded is not found in the
    list, the progress of the audio arriving is not shown, a lecture followed live is not
    matched.
  * `LectureRecorder` puts `handwriting.json` in `directory.substringBeforeLast('/')`: on
    Windows, for a lecture of the library, inside the folder of the lecture instead of beside it.
  * `exportRecording` looks for the extension in `target.substringAfterLast('/')` (the whole
    path on Windows: a folder with a dot, as `C:\Users\a.b\lecture`, cuts the path there), and
    the result of an export shows the whole path as the name of the file.
  * `SyncEngine` takes the extension of a resource from `path.substringAfterLast('/')`, and the
    settings of the assistant show the whole path of a whisper model.
  * `RecordedLecture.id` (`feat/lecture-eval`) is the same expression as `Lecture.id`, and
    `LectureReader.lectures` lists like `LectureLibrary.list`. The evaluation tool reads a copy
    of the data (`<data>/lectures`) and never calls `dataDirectory()`, so it never moves the
    data of the user.
* What a Windows device may already have stored because of it:
  * `lecture.json` keeps `LectureRecord.id`, written from `Lecture.id`: a lecture loaded from the
    library and saved again on Windows has its whole path there. That record is also what
    travels to the other devices (`LectureAttachments`, `Item.Record`). A receiving device keeps
    its own id (`LectureRecords.merge` starts from `mine`, a new lecture takes the id of its key),
    so no other device stores a path; but the version of the record (`LectureRecords.version`,
    a hash that includes the id) differs from theirs, and the record is exchanged again at every
    meeting.
  * `sharing.json` keeps `requestedAudio`, the lectures whose audio was asked for: written from
    `Lecture.id`, so a path on Windows, and never matched again.
  * `assistant-settings.json` keeps `whisperModel`, the path of the chosen model: on Windows in
    `<data>\resources\whisper`.
  * The keys of the attachments never carry a path: `indexLecture` and `Item.parse` accept names
    only (`isName`). `deleted.json` is written from `Path.name`, so it has names.
  * On Android and iOS the paths have `/` only: nothing there is affected.
* Failing on Windows: `LectureLibraryTest.lecturesAreListedNewestFirstAndDeleted`,
  `LectureRecorderTest.theHandwritingIsReadOnceAndSentAsText`, three tests of
  `LectureSharingTest`; on `feat/lecture-eval`, six tests of `DataSnapshotTest`,
  `LecturePreparationTest`, `ReviewSessionTest`.

## Requirements

1. On every platform, the id of a lecture is the name of its folder, and the data directory is
   the parent of the resources directory.
2. One directory string per lecture: the one the library builds, also when it lists.
3. Nothing stored on a Windows device is lost:
   * what is in `<data>\resources` is moved beside it, where the fixed `dataDirectory()` looks;
   * the paths and ids stored in the files are read as the fixed code writes them, without
     writing anything to "start clean";
   * every migration is tested.
4. Nothing that travels changes shape: the fields of the messages and of the lecture records
   stay as they are, and `PROTOCOL_VERSION` stays 1. Only the value of the id of a record sent
   by Windows changes, to the one the other devices already send.
5. Tests that run on the paths of the platform (backslashes on Windows), and the tests above
   passing on Windows and on macOS.

## Design

* **Information Expert: the file system of the platform splits its paths.**
  `PlatformFiles.name(path)` (the last segment) and `PlatformFiles.parent(path)` (the directory
  holding it, `null` for a single segment), members of the `expect object PlatformFiles`:
  `java.io.File` on the JVM and on Android (both `/` and `\` on Windows), `/` on iOS.
  `dataDirectory()` is `parent(resourcesDirectory())`. `drawview-sync` (the extension of a
  resource) and `drawview-lecture-ui` (the name of a whisper model) use them.
  `drawview-lecture` already works with `kotlinx.io.files.Path` (`Path.name`, `Path.parent`),
  which splits on the separators of the platform too: it keeps using it.
* **Information Expert: the library builds the directory of a lecture.** `LectureLibrary.list`
  gives `lectureDirectory(documentId, path.name)`, the string it builds for a new lecture and
  for the attachments, so every comparison of directories holds. `Lecture.id` is
  `Path(directory).name`; `LectureRecorder` reads the handwriting in `Path(directory).parent`.
* **Pure Fabrication: `DesktopDataLayout`** (`drawview-platform`, JVM) moves what
  `dataDirectory()` put in the resources directory before the fix: `lectures`, `whisper`,
  `thumbnails`, `device-name.txt`, a list closed with the fix.
  * It runs once per data directory and process, when `drawViewDesktopDataDirectory` is first
    read: before anything reads or writes the data.
  * An entry missing beside the resources is moved whole (a rename, on the same volume).
  * An entry on both sides (a build without the fix run again after one with it) is merged:
    what is missing beside the resources is moved there, a file on both sides stays where it is
    (the one beside the resources wins), a folder emptied is removed. Nothing is overwritten or
    deleted.
  * A move that fails (a file held by another process) leaves the entry where it was, logged:
    the next start moves it.
  * It runs on every JVM host: on macOS and Linux it finds nothing to move, and its tests run on
    both.
* **Migrations at read time (Protected Variations: the files keep their format).**
  * `LectureLibrary.readRecord` gives the record the name of its folder as id: a `lecture.json`
    written by Windows with a path reads as the others, so its version and what is sent match
    the other devices. The file is written with the name the next time the lecture is saved.
  * `LectureLibrary.sharingOf` reads each id of `requestedAudio` as its last segment, on `/` and
    `\` on every platform (an id is a folder name, which holds neither).
  * `AssistantSettingsStore` reads a `whisperModel` in the old directory of the models
    (`<resources>/whisper`) as the same file in `WhisperTranscriber.modelsDirectory()`, where
    `DesktopDataLayout` moved it.
* **Not migrated:** a `handwriting.json` left inside the folder of a lecture by
  `LectureRecorder` on Windows. It holds the handwriting read by the model, generated again when
  needed; it is left where it is, never deleted.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `drawview-platform`: `PlatformFiles.name` / `parent`, `dataDirectory()` on them, `DesktopDataLayout`; the whisper model of the settings in its new directory; tests of the layout (moved, merged, nothing lost) and of the settings; ABI of `drawview-platform` | Done |
| 2 | `drawview-lecture`: the ids and the directories of the lectures, `lecture.json` and `sharing.json` read as fixed, the handwriting beside the lectures, the exported audio; `drawview-sync`, `drawview-lecture-ui`; tests on the paths of the platform, the five tests passing on Windows | |
| 3 | `RecordedLecture.id` and `LectureReader.lectures` as in phase 2, one commit on top of `feat/lecture-eval` (on `fix/lecture-eval-windows-ids`: `feat/lecture-eval` is checked out in the main checkout), to be picked there; `:lecture-eval:test` passing on Windows | |

### Phase 1 results

* `DesktopDataLayoutTest` (the entries moved, the entries on both sides merged with every file
  kept, a directory up to date left as it is, the layout brought up to date when the data
  directory is first read, the paths of Windows split on both separators) and
  `PlatformFilesPathTest` pass on Windows.
* `WhisperTranscriberTest.aModelChosenInTheResourcesIsReadWhereItWasMoved`: the settings read a
  model of the old directory in the new one, and the file is written again only at the next
  change.
* `./gradlew jvmTest checkKotlinAbi compileAndroidMain compileKotlinIosSimulatorArm64` on Windows:
  the failures are those of this host before the change (`checkKotlinAbi` of the modules with
  Compose or Room, `TrackpadGesturesTest`, the five tests of `drawview-lecture` of phase 2).
  `LectureRecorderTest.aRecordingIsTranscribedInParallelAndInOrder` failed once in the whole
  run and passes alone (its timing, under load). The iOS code of `drawview-platform` compiles
  on Windows; the iOS compilations of the modules with cinterop are skipped on this host.
* `:pencilnotes-android:assembleDebug` and `:sample-android:assembleDebug` pass (a first run
  failed in the NDK build of PDFium on a transient error of Windows opening a header).

## Decisions taken by me (revertible)

* The name, `feat/windows-paths`, and the members `PlatformFiles.name` / `parent` (instead of
  top-level functions, or `kotlinx-io` as a dependency of `drawview-platform`).
* `drawview-lecture` keeps `kotlinx.io.files.Path` for its paths, instead of moving to the
  helpers of the platform.
* The merge of an entry on both sides keeps the file beside the resources, and leaves the other
  one where it is.
* `DesktopDataLayout` remembers the directories it brought up to date in a private set of the
  process (not part of an API).
* The `handwriting.json` left in a lecture folder is not migrated.

## Left for later

* The names of the files exported on Windows: a document title with `\ : * ? " < > |` makes a
  name that Windows refuses (`LecturePanel`, the export of the documents).
