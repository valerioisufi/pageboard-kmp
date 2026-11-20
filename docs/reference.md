# Pageboard reference

What the modules are, the features of the editor and of PencilNotes, how the native libraries are
built and how the tests run. The [README](../README.md) is the short version; the plans of the
features, with their decisions, are in [plans/](plans/).

## Modules

* [`/pageboard-model`](../pageboard-model) is the document without Compose (pages, elements, changes, clock, geometry), [`/pageboard-ink`](../pageboard-ink) models the strokes with Google Ink, [`/pageboard-canvas`](../pageboard-canvas) is the editor of a document and its tiled rendering. Every change of a document is a serializable `DocumentChange` submitted to the `DocumentSession` of the document (see [docs/plans/collaboration.md](plans/collaboration.md)).
* [`/pageboard-store`](../pageboard-store) is the storage of the documents without a database: its ports (`DocumentCatalog`, `OperationLog`, `SharingStore`, `PreferenceStore`, together `DocumentStore`), `OpenDocuments` (the documents being changed, shared by the editor and the synchronization) and a store on any key-value storage (in memory for the tests); [`/pageboard-store-room`](../pageboard-store-room) implements it with Room (SQLite), with the migrations of its schema; [`/pageboard-store-testing`](../pageboard-store-testing) holds the tests every store must pass.
* [`/pageboard-sync-protocol`](../pageboard-sync-protocol) is the protocol of the synchronization, without the documents (what a relay would need): the frames, the end-to-end encryption, the operations sealed with the key of their document (their identity in clear), the identities of the devices, the invitations and the membership; [`/pageboard-sync`](../pageboard-sync) is the sync engine, [`/pageboard-sync-lan`](../pageboard-sync-lan) the local network, [`/pageboard-sync-iroh`](../pageboard-sync-iroh) the internet (iroh: devices reached by their key, directly or through a relay; see [docs/plans/internet-sync.md](plans/internet-sync.md)), [`/pageboard-sync-ui`](../pageboard-sync-ui) sharing and joining in an application (see [docs/plans/collaboration.md](plans/collaboration.md)).
* [`/pageboard-ui`](../pageboard-ui) contains the editor user interface (`DocumentScreen`, `EditorScreen`), its components and its extension points (`EditorExtension`, which receives an `EditorContext`), without any store; [`/pageboard-session`](../pageboard-session) opens a document of a store in the editor and writes its changes (`DocumentEditorViewModel`, `EditorSessionScreen`).
* [`/pageboard-explorer`](../pageboard-explorer) provides the document library: folders and notes (create, rename, move, delete, multiple selection), sorting, recent documents, and `DocumentLibrary`, a complete note-taking application (library + editor) themed by the caller.
* [`/pageboard-pdf`](../pageboard-pdf) provides PDF document rendering with PDFium (Desktop JVM, Android and iOS), and the text of the pages with the position of every character.
* [`/pageboard-latex`](../pageboard-latex) handles dynamic mathematical formula rendering via MicroTeX.
* [`/pageboard-lecture`](../pageboard-lecture) provides the lecture assistant: it records the lecture and, with the Gemini API or local models, suggests what is missing from the notes (the lectures are recorded on the desktop, iOS and Android, followed by the models on the desktop); [`/pageboard-lecture-ui`](../pageboard-lecture-ui) shows it in an application (its panel, its settings, an `EditorExtension` of the editor), [`/pageboard-lecture-testing`](../pageboard-lecture-testing) has its test doubles.
* [`/pageboard-ml`](../pageboard-ml) is the port of the local models (the catalog, their download and verification, `Embedder` and `Generator`, the models kept loaded), without Compose; [`/pageboard-ml-litert`](../pageboard-ml-litert) runs them on LiteRT-LM (EmbeddingGemma 2, Gemma 4) on the desktop and Android, without Ollama (see [docs/plans/local-models.md](plans/local-models.md)).
* [`/pageboard-handwriting`](../pageboard-handwriting) reads handwriting from the strokes as they were written, without Compose: the samples of a block (`InkSample`), the ports of the readers of text and formulas, the lines of a block with the drawings left out, the reading of a block and InkML; the readers are [`/pageboard-handwriting-mlkit`](../pageboard-handwriting-mlkit) (ML Kit Digital Ink on Android) and [`/pageboard-handwriting-windows`](../pageboard-handwriting-windows) (the ink of Windows on the desktop), and [`/pageboard-handwriting-ui`](../pageboard-handwriting-ui) converts a selection of strokes into a text box. See [docs/plans/handwriting.md](plans/handwriting.md).
* [`/pageboard-search`](../pageboard-search) is the search in the notes, without Compose: an index of its own (words with FTS5, meaning with EmbeddingGemma 2), its sources (texts, PDF pages, handwriting, lectures), the incremental indexer and "Ask your notes" (`NotesAnswerer`, Gemma 4 on the device, Gemini only on request); [`/pageboard-search-ui`](../pageboard-search-ui) shows it in the library (`ContentSearch`, the snippets, the model settings, the answer with its citations). See [docs/plans/search.md](plans/search.md).
* [`/pageboard-agent`](../pageboard-agent) is what an external model can read of a document and propose for it, without Compose (tools, proposals, access, its messages as the Model Context Protocol wants them); [`/pageboard-agent-ui`](../pageboard-agent-ui) reviews the proposals in the editor. In PencilNotes for the desktop, `pencilnotes docs`, `read`, `search`, `preview`, `propose`, `status` and `mcp` reach them from a terminal. See [docs/plans/agent-tools.md](plans/agent-tools.md) and [docs/plans/agent-placement.md](plans/agent-placement.md).
* [`/pageboard-html`](../pageboard-html) turns HTML into the Markdown of the texts, without Compose: what other applications put in the clipboard. See [docs/plans/html-paste.md](plans/html-paste.md).
* [`/pageboard-export`](../pageboard-export) exports the documents (see [docs/plans/export.md](plans/export.md)): a PDF of the pages, written with PDFium (the pages of the imported PDFs copied with their vectors and text, the marks of their text as annotations, the strokes as vector outlines, the texts as images with their text searchable), or the image of a page (PNG); [`/pageboard-export-ui`](../pageboard-export-ui) is "Export" on a document of the library and in the editor (`DocumentExportUi`).
* [`/native`](../native) contains the native C++ engines and JNI bridges for Google Ink, MicroTeX, and PDFium.

A minimal application is a theme around `DocumentLibrary`, given the documents of its store, which
it opens once per process:

```kotlin
// The documents in Room, in the data directory of the application (pageboard-store-room), their
// strokes modeled by Google Ink (pageboard-ink)
object MyNotes {
    val documents by lazy { OpenDocuments(RoomDocumentStore(createPageboardDatabase(), InkStrokeModeller)) }
    val sessions = EditorSessions()
}

@Composable
fun App() {
    PageboardTheme {
        DocumentLibrary(title = { Text("My Notes") }, documents = MyNotes.documents, sessions = MyNotes.sessions, modifier = Modifier.fillMaxSize())
    }
}
```

### Using the libraries

Every module is published as `io.github.valerioisufi:<module>` (`0.1.0-SNAPSHOT` until the first release): an application declares the ones it uses (e.g. `pageboard-explorer`, `pageboard-store-room`, `pageboard-pdf` and `pageboard-latex` for the library with PDFs and formulas) and gets nothing else; the optional engines are given to the editor in `PageboardEngines`. `./gradlew publishToMavenLocal` publishes them to the local repository.

* **Documentation**: `./gradlew :dokkaGenerate` writes the documentation of every library in `build/dokka/html` (the javadoc jar of each one is the same); each module has its page (`Module.md`).
* **Public API**: declared (explicit API mode) and recorded in the `api/` directory of each library (see Running Tests).
* **As another developer uses them**: `./consumer-checks/check.sh` publishes them to `mavenLocal()` and builds against them a JVM program with `pageboard-model` alone (no Compose on its classpath), a desktop editor without the PDF and formula engines, and an Android application with the canvas (only the native library of Google Ink in its APK).
* **Licenses**: every jar and Android library carries the license of Pageboard and `THIRD_PARTY_LICENSES.md` (`META-INF/pageboard/<module>/`).
* **Releases**: a tag `pageboard-vX.Y.Z` (equal to `VERSION_NAME`) runs `.github/workflows/release.yml`, which builds the JNI libraries of the five desktop platforms, publishes the libraries on Maven Central, signed (see its secrets), and the API documentation on GitHub Pages; the applications have tags `pencilnotes-v*` of their own. `.github/workflows/check.yml` checks every change.

### Trackpad

On the canvas, two fingers on a trackpad move the pages like a finger on a touch screen (beyond the edges with a rubber band, with inertia when they are lifted) and the pinch zooms around the pointer, beyond the zoom limits with a rubber band too. The wheel with Ctrl / Cmd zooms as well (the pinch of the Windows precision touchpads arrives this way). On macOS, the phases of the scroll and the pinch, which AWT does not deliver, come from `libtrackpad_bridge` (an event monitor of the application, bundled in the `pageboard-canvas` jar).

### Keyboard and menus

Every action of the editor and of the library is a command (see [docs/plans/adaptive-ui.md](plans/adaptive-ui.md)), shown by the buttons (with a tooltip where a mouse or a trackpad rests), by the menu bar of the desktop and by the keyboard: ⌘Z / ⇧⌘Z undo and redo, ⌘X ⌘C ⌘V and ⌫ on the selection, ⌘+ / ⌘- zoom, ⌘0 the width of the pages, PgUp / PgDn the previous and next page, ⌘G the page grid, ⇧⌘P / ⇧⌘I import a PDF or an image, ⇧⌘E export, ⌘W close the document; the tools with a single key (P pen, H highlighter, U text markup, E eraser, T text, L lasso). In the library: ⌘N a note, ⇧⌘N a folder, ⌘A select all, ⌫ delete, ⌘↑ the enclosing folder, and a right click on an item opens its menu. Ctrl replaces ⌘ on Windows, Linux and Android. The desktop windows keep their size, position and placement between launches (`rememberPageboardWindowState`). Android lists the shortcuts with Meta + / (`CommandHost.keyboardShortcutGroups`). An application shows the menu bar with `PageboardMenuBar(host)` and runs the shortcuts of its window with `Window(onKeyEvent = { host.dispatch(it) })`, the host given to `CommandRoot` around its screens.

### Library

The library follows the width of the window: a list on a phone, a grid of cards showing the first page of each note on a tablet or a narrow window, and on a wide one the folders and the recent notes beside the grid and the details of the selected item on the other side. With a mouse, ⌘ / Ctrl click adds an item to the selection, Shift click extends it, and items dragged onto a folder move there. On the desktop, a PDF or an image dragged from the files of the computer becomes a note, or is imported into the open one.

### Stylus

The first contact of a stylus switches the editor to stylus-only mode (fingers pan and zoom). A stylus whose button is held erases (Android), and the double tap or the squeeze of an Apple Pencil does what the user set in the system: the eraser and back, the previous tool or the settings of the tool. A hovering stylus or mouse shows where the ink of the pen would land.

### Text of the PDFs

The text of the PDF pages can be selected, highlighted, underlined and struck out (where the PDF has a text layer: not on a scanned page).

* **Text markup tool** (toolbar, shown with PDF pages): the stylus, the mouse or a finger sweeps the text, which is marked with the active preset (kind of mark and color; tap the selected preset to change them, "+" to add one). A tap on a mark selects it.
* **Long press** on the text (in reading mode too): selects a word, whose ends are dragged by their handles; the bar above the canvas copies the text or marks it with a preset of the tool. A long press on a mark selects it: copy its text, change its style or delete it.
* The marks follow the text where the page draws the PDF, even when the format of the page changes; the highlights multiply the colors of the PDF, like a highlighter on paper. The eraser deletes them, and every change can be undone.

---

## Native Libraries

No native binary is versioned: Gradle builds or downloads them on demand.

* **Google Ink & MicroTeX** are built from the sources in [`/native`](../native) with CMake:
  * Android: by the NDK, one Android library per engine (`:pageboard-native-ink`, `-tex`, `-pdfium`, `-audio`, each a dependency of the module using it), for `arm64-v8a`, `armeabi-v7a` and `x86_64`. A machine can build fewer of them in debug, with `android.debug.abis=arm64-v8a` in `local.properties`, or for one build with `-Ppageboard.android.debug.abis=arm64-v8a` (as `check.yml` does): a release always has the three.
  * iOS: `./gradlew buildIosNativeLibs-<iphoneos|iphonesimulator>` (static libraries linked through cinterop, macOS only).
  * Desktop JVM: `./gradlew buildDesktopNativeLibs` (JNI libraries of the host platform, bundled in the jvm jars; `-Ppageboard.desktop.platform=darwin-x86_64` cross-builds the Intel macOS libraries on Apple silicon). Windows hosts need Git Bash and Visual Studio with the C++ Clang tools.
* **The iroh bridge** ([`native/iroh-bridge`](../native/iroh-bridge)) is a Rust crate built by cargo, with the toolchain of its `rust-toolchain.toml` (rustup) and the versions of its `Cargo.lock`. Gradle finds cargo in `local.properties` (`rust.cargo=/path/to/cargo`), in `~/.cargo/bin` or on the `PATH`. It builds in `native/build/rust`, or in the directory of `rust.targetDir` in `local.properties`, which the checkouts of a machine can share to reuse the dependencies: two builds in it wait for each other.
  * Android: `:pageboard-native-iroh` (for the ABIs of the variant, with the NDK of the module).
  * iOS: `./gradlew :pageboard-sync-iroh:buildIrohBridgeIos` (static libraries linked through cinterop, macOS only).
  * Desktop JVM: with the other JNI libraries (`./gradlew buildDesktopNativeLibs`).
  * The notices of its crates (`THIRD_PARTY_NOTICES.md`) are written by [cargo-about](https://github.com/EmbarkStudios/cargo-about) when `Cargo.lock` changes: `cargo about generate about.hbs -o THIRD_PARTY_NOTICES.md`.
* **PDFium** is the prebuilt [pdfium-binaries](https://github.com/bblanchon/pdfium-binaries) release set in [`native/pdfium/pdfium.properties`](../native/pdfium/pdfium.properties), downloaded and verified against its SHA-256 checksums. On iOS it is `build/pdfium/PDFium.xcframework`, linked and embedded by the Xcode project.
* The [`Native libraries`](../.github/workflows/native.yml) workflow builds and tests every platform and assembles jvm jars bundling the desktop libraries of macOS (arm64, x86_64), Linux (x86_64, arm64) and Windows (x86_64).

---

## Sample Application

[`/sample`](../sample) is the Pageboard sample application: [`shared`](../sample/shared) (`:sample-shared`, the `App` composable) and the entry points [`androidApp`](../sample/androidApp) (`:sample-android`), [`desktopApp`](../sample/desktopApp) (`:sample-desktop`) and [`iosApp`](../sample/iosApp) (Xcode).

* **Android app**: `./gradlew :sample-android:assembleDebug`
* **Desktop app**:
  * Hot reload: `./gradlew :sample-desktop:hotRun --auto`
  * Standard run: `./gradlew :sample-desktop:run`
* **iOS app**: Open the [`/sample/iosApp`](../sample/iosApp) directory in Xcode and run it on a simulator or device.

---

## PencilNotes

PencilNotes is the note-taking application designed for stylus input, ported from the original
Android app to Kotlin Multiplatform. It is the Pageboard document library (`DocumentLibrary`) with
the PencilNotes identity: theme (dynamic colors on Android 12+), Cherry Bomb One title, About
information, icons and full-screen editor.

* [`/pencilnotes/shared`](../pencilnotes/shared) (`:pencilnotes-shared`): the `PencilNotesApp` composable, theme and resources.
* [`/pencilnotes/androidApp`](../pencilnotes/androidApp) (`:pencilnotes-android`), [`/pencilnotes/desktopApp`](../pencilnotes/desktopApp) (`:pencilnotes-desktop`) and [`/pencilnotes/iosApp`](../pencilnotes/iosApp) (Xcode): the entry points (Android, desktop and iOS).

### Export

A document is exported from the library (select it, then the export button of the bar) or from the editor (the export button of its top bar):

* **PDF**: every page a page of the same size. The pages of the imported PDFs keep their vectors, fonts and text (searchable and selectable), with their own annotations; the highlights, underlines and strikeouts are annotations of the PDF (other readers list them and print them); the strokes are vector shapes (their outlines from Ink); the images are embedded (a JPEG as it is); the texts and formulas are images at 300 dpi with their text written invisible over them, so that it can be searched and copied (the characters out of Latin-1 are left out of this text). From the editor, the page on screen alone.
* **Image**: the page on screen as PNG, drawn as the editor draws it (200 dpi).
* The lines, grid and dots of the backgrounds can be left out. The file is saved where the user chooses (the dialog of the desktop, the documents of Android, the Files of iOS).

### Lecture assistant

The microphone button of the editor opens the lecture assistant (followed by the models on the desktop; on iOS and Android the lectures are recorded only, see below):

* **Recording**: the lecture is recorded (`lectures/doc-<id>/<date>-<random>/audio.opus` in the data directory: Opus at 24 kbit/s, about 11 MB per hour, written page by page so that a crash loses at most the last second; with `lecture.json`: transcript, outline, suggestions and final review).
* **Live suggestions**: about every minute (cut at a pause of the speech), the model receives what was said (the audio or its transcript), the memory of the lecture (outline, notes already suggested and what the student did with them) and the notebook, described in the same way for every model: the pages around the visible ones in text (the text of the slides read from the PDFs, what the student typed), the image of the visible page as it looks and a close-up of what was written since the previous update. It answers with the transcript, the page the lecturer is talking about and only the content that is neither on the slides nor in the notes: each suggestion can be inserted in the page (blue Markdown text with formulas: a preview follows the pointer and a click or tap places it, while the canvas can still be moved; Escape cancels), listened to in the recording, marked as already noted or dismissed.
* **Cost**: the minutes without speech are not analyzed, the outline is rewritten every few minutes, the slides are sent as text and only the visible page as an image (at medium resolution); the Review tab shows the requests and the tokens of the online ones by kind (text, images, audio).
* **I missed something**: analyzes the last minutes right away.
* **Handwriting**: the strokes of a page are grouped in blocks; after every update, the blocks the student is done with are transcribed once (Markdown and LaTeX) by the model of the live suggestions and then sent as text; the transcriptions are kept in `lectures/doc-<id>/handwriting.json` for all the lectures of the notebook.
* **Course context**: when the first lecture of a notebook starts, the assistant writes the course, its topics and its terms from the slides and the notes (`lectures/doc-<id>/context.json`); it is shown in the Outline tab, where it can be edited or written again, and helps the transcription (with the first lines of the visible slides) and the models.
* **Lectures kept**: every lecture of a notebook is saved and listed in the panel (date and duration) with its transcript, its suggestions and what was done with them, its outline and its review; a lecture can be deleted (audio included). The recording goes on when the notebook is closed (an indicator over the library opens it again or stops the recording); exiting the app saves it, and its review can be generated when the notebook is opened again.
* **Listening again**: a player of the whole recording (play and pause, 10 s back and forward, the position, the speed from 1× to 2× without raising the pitch of the voice); a time of the transcript or Listen on a note plays from there, and the segment being played is highlighted.
* **Tablets and phones** (iOS, Android): the lecture is recorded with the pages looked at (captured every 30 s), also with the screen locked or the app in the background (the background audio of iOS; on Android a foreground service of the microphone, with its notification), and listened to again; the notes and the review are written by a device with the models, or by the tablet itself when "Write the notes on this device" is on in the settings (Gemini, with the audio transcribed online; Ollama of a computer of the local network for the review: "Find on the network" looks for it on the devices found, which must start it with `OLLAMA_HOST=0.0.0.0`). The microphone (and on Android the notifications) is asked for the first time. A call or Siri interrupts the microphone: the panel says so and the recording goes on when the system gives it back (on iOS also after the headphones are plugged or unplugged); the playback pauses when the headphones are unplugged or a call comes.
* **Shared notebooks**: the devices working on a notebook (see [docs/plans/collaboration.md](plans/collaboration.md)) share its lectures, audio included: a lecture is seen on the others while it is recorded, with its audio as it is recorded; a lecture recorded without the models is followed live by the desktop (asked first by default: its notes and transcript reach the tablet during the lecture, the review at the end), or transcribed and reviewed there afterwards ("Generate the review"), and what it writes reaches the device that recorded it. The notes inserted, already noted or dismissed, the course context, the handwriting read and the lectures deleted are shared too; of what two devices change apart, the most recent change of each part of a lecture is kept.
* **Import and export** (every platform): a recording made elsewhere (a phone, a recorder: m4a, mp3, wav, aiff, caf, flac through Audio Toolbox on macOS and iOS, MediaCodec on Android; WAV on the other systems) becomes a lecture of the notebook, transcribed (a few pieces at a time with Gemini) and then reviewed (on a device without the models, the audio only: a computer reviews it once shared); the audio of a lecture is exported as m4a (AAC-LC, about 14 MB per hour, the same encoder on every platform).
* **Transcribe again**: a lecture already recorded can be transcribed again by another model, online or on the computer, from its menu; the new transcript replaces the old one when it is complete.
* **Final review**: when the recording stops (or only when asked, if so chosen in the settings), the whole lecture is reviewed once more, with the pages seen during the lecture (their text, saved with the lecture, and the last images of the visible ones): complete notes (insertable as new pages after the page on screen or at the end of the notebook) and the content still missing. By Gemini or for free on the computer (see below); a review can be written again by the other one.

**Online or on this computer, request by request** (settings, gear of the panel):

| Request | Online | On this computer |
|---|---|---|
| Transcription | Gemini 3.5 Transcribe Live by default: the words appear while the lecturer speaks (a session of the Live API renewed every 9 minutes at a pause, opened again with the audio kept when the connection drops); or Gemini 3.5 Transcribe at every update, or the model of the suggestions within its own requests. The language spoken and the terms of the course and of the slides on screen help it; the audio is sent to Google | [whisper.cpp](https://github.com/ggml-org/whisper.cpp), built into the desktop app (Metal on macOS); the audio never leaves the computer and Gemini receives the transcript |
| Suggestions during the lecture | Gemini | a model of [Ollama](https://ollama.com) (e.g. `gemma4:e4b`, it must keep up with the lecture), on the transcript (of whisper.cpp or of Gemini) |
| I missed something | Gemini | Ollama, on the transcript of the last minutes |
| Final review | Gemini (the complete recording when the audio may be sent) | Ollama (e.g. `gemma4:12b`), on the transcript and the notes |

The settings need no path or command: they say whether [Ollama](https://ollama.com) is running (with a link to install it), list its models (those that read images, as they must to read the notes of the student) and download the recommended ones, and download the speech recognition model of whisper.cpp (`large-v3-turbo-q5_0`, 574 MB, by default) into `whisper/` of the data directory; the transcription model of Gemini and the language spoken are chosen from menus. The local models are unloaded from the memory at the end of the lecture or of the review. The address of Ollama, a model file of one's own and the reasoning of Gemini are under "Advanced".

The other settings: the Gemini API key (or the `GEMINI_API_KEY` environment variable), the update interval, the language of the notes. The key is kept by the system: the Keychain on iOS, encrypted with a key of the Android Keystore on Android, a file of the data directory readable by the user only on the desktop. On macOS the microphone must be allowed to PencilNotes, or to the terminal running `./gradlew :pencilnotes-desktop:run`. The panel says what is sent to Google with the chosen providers (nothing when everything is local): ask the lecturer before recording.

Running:

* **Android**: `./gradlew :pencilnotes-android:assembleDebug`
* **Desktop**: `./gradlew :pencilnotes-desktop:run` (data in `~/.pencilnotes`, or `-Dpageboard.dataDir=<dir>`)
* **iOS**: open [`/pencilnotes/iosApp`](../pencilnotes/iosApp) in Xcode and run it on a simulator or device.

---

## Running Tests

* **Modules**: `./gradlew :pageboard-<module>:jvmTest` (also `iosSimulatorArm64Test`), e.g. `:pageboard-store-room:jvmTest`, `:pageboard-explorer:jvmTest`, `:pageboard-lecture:jvmTest`, `:pageboard-export:jvmTest`
* **Data**: every test task runs on a data directory of its own, `build/test-data/<task>` of its module (`pageboard.dataDir`), never on `~/.pageboard` or `~/.pencilnotes` (see [docs/plans/test-data.md](plans/test-data.md))
* **Public API**: every library declares it (explicit API mode) and records it in its `api/` directory; `./gradlew check` fails when it changes, `./gradlew updateKotlinAbi` records a change wanted
* **Screenshots** of the library and of the editor in the reference windows of the adaptive interface (phones, tablets, desktop; see [docs/plans/adaptive-ui.md](plans/adaptive-ui.md)): `./gradlew :pageboard-explorer:verifyRoborazziJvm` compares the interface with the images of `pageboard-explorer/screenshots` (the same in the other modules with screenshots). The references are recorded in the Docker image of `tools/screenshots/` only: the workflow Screenshots verifies them on every pull request and records them on the label `record-screenshots`, and `tools/screenshots/run.sh verify|record` runs the same image on any computer; elsewhere `recordRoborazziJvm` refuses to record (see [CONTRIBUTING.md](../CONTRIBUTING.md#screenshots) and [docs/plans/ci-screenshots.md](plans/ci-screenshots.md))
* **Sample**:
  * Android: `./gradlew :sample-shared:testAndroidHostTest`
  * Desktop: `./gradlew :sample-shared:jvmTest`
  * iOS: `./gradlew :sample-shared:iosSimulatorArm64Test`
