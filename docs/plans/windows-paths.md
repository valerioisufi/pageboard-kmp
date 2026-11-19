# Paths on Windows

The paths of the platform are split by the platform, never on `/` alone. This document starts
from the checks of 2026-10-18 on the Windows machine of the maintainer, where the lecture evaluation
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
    matched. `LectureAttachments.indexOf` gives the folders it lists to `recoverInterrupted`,
    which asks whether one is being recorded here: the lecture being recorded is not
    recognized, is taken for one interrupted by a crash and saved `FINISHED` while it is
    recorded (found later, by the session of the writes refused by Windows).
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
| 2 | `drawview-lecture`: the ids and the directories of the lectures, `lecture.json` and `sharing.json` read as fixed, the handwriting beside the lectures, the exported audio; `drawview-sync`, `drawview-lecture-ui`; tests on the paths of the platform, the tests failing on Windows because of the separators passing | Done |
| 3 | `RecordedLecture.id` and `LectureReader.lectures` as in phase 2, one commit on top of `feat/lecture-eval` (on `fix/lecture-eval-windows-ids`: `feat/lecture-eval` is checked out in the main checkout), to be picked there; `:lecture-eval:test` passing on Windows | Done (`fix/lecture-eval-windows-ids`) |
| 4 | Rebased on `main` of 2026-11-15, where `4bcfa788` (2026-11-13) had made `dataDirectory()` the parent of the resources on Windows without moving anything: from then on a Windows build looked for the lectures, the models and the name of the device beside the resources, and found none of those kept inside them (nothing deleted). The layout moves them now; `models`, `litert-cache` and `search` (2026-10-27, after phase 1) were put in the resources on Windows too, and are moved with the others | Done |

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
* `consumer-checks` pass. `publishToMavenLocal` fails only on the iOS metadata of the modules
  with Compose or Room, as on this host before the change.

### Phase 2 results

* On Windows, `LectureLibraryTest.lecturesAreListedNewestFirstAndDeleted`,
  `LectureRecorderTest.theHandwritingIsReadOnceAndSentAsText` and
  `LectureSharingTest.theAudioIsSharedAsTheDevicesChoose` pass. The new tests pass too: a record
  saved with the path of its folder is read with its name and the version of the other devices,
  without being written (`LectureLibraryTest`); the audio asked for with the path of its lecture
  is read with its name (`LectureLibraryTest`); the id of a lecture on a path of Windows is the
  name of its folder (`LectureRecorderTest`); a recording is exported in a folder with a dot
  (`AudioImportTest`).
* Two tests of `LectureSharingTest` still fail on Windows, as before the plan: the writes of
  `lecture.json` refused by Windows (see [Left for later](#left-for-later)), not the separators.
* Added after the phase: `LectureAttachments.indexOf` gives `recoverInterrupted` the directory
  the library builds. `LectureAttachmentsTest.theLectureBeingRecordedHereIsNotRecoveredByTheIndex`
  fails on Windows without it (the lecture being recorded saved `FINISHED`) and passes with it;
  no other listing of folders gives a directory that is compared.
* `./gradlew jvmTest checkKotlinAbi compileAndroidMain compileKotlinIosSimulatorArm64` and the two
  Android applications: no other failure than those of this host before the change.
  `verifyRoborazziJvm` of `drawview-lecture-ui` differs in the antialiasing of the text of the
  four screenshots on Windows (recorded on macOS); their content and layout are the same, and
  the name of a whisper model chosen by hand is not in them.
* Not run here: the tests on macOS and the iOS compilation of `drawview-lecture` (skipped on a
  Windows host).

### In the application

PencilNotes desktop (`:pencilnotes-desktop:run`) on a scratch data directory laid out as
Windows left it (`resources/device-name.txt`, `resources/lectures/` with a `device-id` and a
`lecture.json` whose id is a path of Windows, `resources/thumbnails/`): at the start the three
entries are beside `resources/`, which is left empty, with the same content; the `lecture.json`
is not written by reading it.

### Phase 3 results

* One commit on `fix/lecture-eval-windows-ids`, on top of `feat/lecture-eval`: it touches
  `LectureReader.kt` only, so it is picked there as it is. `feat/lecture-eval` with it and this
  branch have no file in common: they merge without conflicts (`git merge-tree`).
* On Windows, `:lecture-eval:test` passes (35 classes): `DataSnapshotTest`,
  `LecturePreparationTest` and `ReviewSessionTest` included, which fail without the commit (the
  six tests seen on 2026-10-18). `LectureReaderTest` passes.

## Decisions taken by me (revertible)

* Phase 4: the resolution of the rebase keeps `PlatformFiles.parent` for `dataDirectory()` (the
  `parentDirectory` of `4bcfa788` and its test removed, `PlatformFilesPathTest` covers it). A
  Windows device that ran a build of `main` between 2026-11-13 and this phase may have new files
  beside the resources (a new `lectures/device-id`, a new search index): the merge keeps those,
  and leaves the older ones inside the resources, never deleted.

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
* **Writes of `lecture.json` that fail on Windows** (its own plan: it is not a matter of
  separators). `writeAtomically` replaces the file with `SystemFileSystem.atomicMove`, which on
  Windows throws `java.nio.file.AccessDeniedException` while the file is held open (seen in
  `LectureSharingTest`, a few times per run; reading it through NIO, which shares the file for
  deletion, is not enough). The record is written again at the next change, but the last change
  of a lecture can stay in memory only. `LectureSharingTest.aLectureRecordedOnATabletIsFollowedLiveOnAComputer`
  and `aLectureRecordedOnATabletIsReviewedOnAComputerAndSharedBack` fail on Windows because of
  it and of `indexOf` (now fixed here): both are needed. Planned in `docs/windows-file-locks.md`
  (`feat/windows-file-locks`). Close to it: deleting the folder of a lecture just finished
  fails on Windows while one of its files is still open, and is not tried again
  (`theLecturesOfADocumentDeletedGoWithIt`, now and then under load).
