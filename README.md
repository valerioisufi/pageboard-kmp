# DrawView-KMP

DrawView-KMP is a set of [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) and [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) modules for drawing, handwriting and note-taking applications on **Android**, **iOS** and **Desktop (JVM)**: a stylus-oriented canvas editor (Google Ink strokes, text with Markdown and LaTeX, images, PDF pages), the storage of the documents and a document library with folders.

The repository also contains a [sample application](./sample) showing how to build an application with the modules, and **[PencilNotes](./pencilnotes)**, a note-taking application built on them.

---

## Modules

* [`/drawview-core`](./drawview-core) handles drawing logic, strokes, brushes, and rendering abstractions. Every change of a document is a serializable `DocumentChange` submitted to the `DocumentSession` of the document (see [docs/collaboration.md](./docs/collaboration.md)).
* [`/drawview-data`](./drawview-data) handles persistence (Room / SQLite): documents, folders and preferences.
* [`/drawview-ui`](./drawview-ui) contains the editor user interface (`DrawScreen`, `EditorSessionScreen`) and its components.
* [`/drawview-explorer`](./drawview-explorer) provides the document library: folders and notes (create, rename, move, delete, multiple selection), sorting, recent documents, and `DocumentLibrary`, a complete note-taking application (library + editor) themed by the caller.
* [`/drawview-pdf`](./drawview-pdf) provides PDF document rendering with PDFium (Desktop JVM, Android and iOS), and the text of the pages with the position of every character.
* [`/drawview-latex`](./drawview-latex) handles dynamic mathematical formula rendering via MicroTeX.
* [`/drawview-lecture`](./drawview-lecture) provides the lecture assistant: it records the lecture and, with the Gemini API, suggests what is missing from the notes (an `EditorExtension` of the editor; the lectures are recorded on the desktop, iOS and Android, followed by the models on the desktop).
* [`/drawview-export`](./drawview-export) exports the documents (see [docs/export.md](./docs/export.md)): a PDF of the pages, written with PDFium (the pages of the imported PDFs copied with their vectors and text, the marks of their text as annotations, the strokes as vector outlines, the texts as images with their text searchable), or the image of a page (PNG); "Export" on a document of the library and in the editor (`DocumentExportUi`).
* [`/native`](./native) contains the native C++ engines and JNI bridges for Google Ink, MicroTeX, and PDFium.

A minimal application is a theme around `DocumentLibrary`:

```kotlin
@Composable
fun App() {
    DrawViewTheme {
        DocumentLibrary(title = { Text("My Notes") }, modifier = Modifier.fillMaxSize())
    }
}
```

### Trackpad

On the canvas, two fingers on a trackpad move the pages like a finger on a touch screen (beyond the edges with a rubber band, with inertia when they are lifted) and the pinch zooms around the pointer, beyond the zoom limits with a rubber band too. The wheel with Ctrl / Cmd zooms as well (the pinch of the Windows precision touchpads arrives this way). On macOS, the phases of the scroll and the pinch, which AWT does not deliver, come from `libtrackpad_bridge` (an event monitor of the application, bundled in the `drawview-core` jar).

### Keyboard and menus

Every action of the editor and of the library is a command (see [docs/adaptive-ui.md](./docs/adaptive-ui.md)), shown by the buttons (with a tooltip where a mouse or a trackpad rests), by the menu bar of the desktop and by the keyboard: ⌘Z / ⇧⌘Z undo and redo, ⌘X ⌘C ⌘V and ⌫ on the selection, ⌘+ / ⌘- zoom, ⌘0 the width of the pages, PgUp / PgDn the previous and next page, ⌘G the page grid, ⇧⌘P / ⇧⌘I import a PDF or an image, ⇧⌘E export, ⌘W close the document; the tools with a single key (P pen, H highlighter, U text markup, E eraser, T text, L lasso). In the library: ⌘N a note, ⇧⌘N a folder, ⌘A select all, ⌫ delete, ⌘↑ the enclosing folder, and a right click on an item opens its menu. Ctrl replaces ⌘ on Windows, Linux and Android. An application shows the menu bar with `DrawViewMenuBar(host)` and runs the shortcuts of its window with `Window(onKeyEvent = { host.dispatch(it) })`, the host given to `CommandRoot` around its screens.

### Text of the PDFs

The text of the PDF pages can be selected, highlighted, underlined and struck out (where the PDF has a text layer: not on a scanned page).

* **Text markup tool** (toolbar, shown with PDF pages): the stylus, the mouse or a finger sweeps the text, which is marked with the active preset (kind of mark and color; tap the selected preset to change them, "+" to add one). A tap on a mark selects it.
* **Long press** on the text (in reading mode too): selects a word, whose ends are dragged by their handles; the bar above the canvas copies the text or marks it with a preset of the tool. A long press on a mark selects it: copy its text, change its style or delete it.
* The marks follow the text where the page draws the PDF, even when the format of the page changes; the highlights multiply the colors of the PDF, like a highlighter on paper. The eraser deletes them, and every change can be undone.

---

## Native Libraries

No native binary is versioned: Gradle builds or downloads them on demand.

* **Google Ink & MicroTeX** are built from the sources in [`/native`](./native) with CMake:
  * Android: by the NDK (`:drawview-native`), for `arm64-v8a`, `armeabi-v7a` and `x86_64`.
  * iOS: `./gradlew buildIosNativeLibs-<iphoneos|iphonesimulator>` (static libraries linked through cinterop, macOS only).
  * Desktop JVM: `./gradlew buildDesktopNativeLibs` (JNI libraries of the host platform, bundled in the jvm jars; `-Pdrawview.desktop.platform=darwin-x86_64` cross-builds the Intel macOS libraries on Apple silicon). Windows hosts need Git Bash and Visual Studio with the C++ Clang tools.
* **PDFium** is the prebuilt [pdfium-binaries](https://github.com/bblanchon/pdfium-binaries) release set in [`native/pdfium/pdfium.properties`](./native/pdfium/pdfium.properties), downloaded and verified against its SHA-256 checksums. On iOS it is `build/pdfium/PDFium.xcframework`, linked and embedded by the Xcode project.
* The [`Native libraries`](./.github/workflows/native.yml) workflow builds and tests every platform and assembles jvm jars bundling the desktop libraries of macOS (arm64, x86_64), Linux (x86_64, arm64) and Windows (x86_64).

---

## Sample Application

[`/sample`](./sample) is the DrawView sample application: [`shared`](./sample/shared) (`:sample-shared`, the `App` composable) and the entry points [`androidApp`](./sample/androidApp) (`:sample-android`), [`desktopApp`](./sample/desktopApp) (`:sample-desktop`) and [`iosApp`](./sample/iosApp) (Xcode).

* **Android app**: `./gradlew :sample-android:assembleDebug`
* **Desktop app**:
  * Hot reload: `./gradlew :sample-desktop:hotRun --auto`
  * Standard run: `./gradlew :sample-desktop:run`
* **iOS app**: Open the [`/sample/iosApp`](./sample/iosApp) directory in Xcode and run it on a simulator or device.

---

## PencilNotes

PencilNotes is the note-taking application designed for stylus input, ported from the original
Android app to Kotlin Multiplatform. It is the DrawView document library (`DocumentLibrary`) with
the PencilNotes identity: theme (dynamic colors on Android 12+), Cherry Bomb One title, About
information, icons and full-screen editor.

* [`/pencilnotes/shared`](./pencilnotes/shared) (`:pencilnotes-shared`): the `PencilNotesApp` composable, theme and resources.
* [`/pencilnotes/androidApp`](./pencilnotes/androidApp) (`:pencilnotes-android`), [`/pencilnotes/desktopApp`](./pencilnotes/desktopApp) (`:pencilnotes-desktop`) and [`/pencilnotes/iosApp`](./pencilnotes/iosApp) (Xcode): the entry points (Android, desktop and iOS).

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
* **Shared notebooks**: the devices working on a notebook (see [docs/collaboration.md](./docs/collaboration.md)) share its lectures, audio included: a lecture is seen on the others while it is recorded, with its audio as it is recorded; a lecture recorded without the models is followed live by the desktop (asked first by default: its notes and transcript reach the tablet during the lecture, the review at the end), or transcribed and reviewed there afterwards ("Generate the review"), and what it writes reaches the device that recorded it. The notes inserted, already noted or dismissed, the course context, the handwriting read and the lectures deleted are shared too; of what two devices change apart, the most recent change of each part of a lecture is kept.
* **Import and export** (every platform): a recording made elsewhere (a phone, a recorder: m4a, mp3, wav, aiff, caf, flac through Audio Toolbox on macOS and iOS, MediaCodec on Android; WAV on the other systems) becomes a lecture of the notebook, transcribed and then reviewed (on a device without the models, the audio only: a computer reviews it once shared); the audio of a lecture is exported as m4a (AAC-LC, about 14 MB per hour, the same encoder on every platform).
* **Final review**: when the recording stops, the whole lecture is reviewed once more, with the pages seen during the lecture (their text, saved with the lecture, and the last images of the visible ones): complete notes (insertable as new pages after the page on screen or at the end of the notebook) and the content still missing. By Gemini or for free on the computer (see below); a review can be written again by the other one.

**Online or on this computer, request by request** (settings, gear of the panel):

| Request | Online | On this computer |
|---|---|---|
| Transcription | Gemini, within the requests of the suggestions (the audio is sent to Google) | [whisper.cpp](https://github.com/ggml-org/whisper.cpp), built into the desktop app (Metal on macOS); the audio never leaves the computer and Gemini receives the transcript |
| Suggestions during the lecture | Gemini | a model of [Ollama](https://ollama.com) (e.g. `gemma4:e4b`, it must keep up with the lecture); it needs the local transcription |
| I missed something | Gemini | Ollama, on the transcript of the last minutes |
| Final review | Gemini (the complete recording when the audio may be sent) | Ollama (e.g. `gemma4:12b`), on the transcript and the notes |

The settings need no path or command: they say whether [Ollama](https://ollama.com) is running (with a link to install it), list its models (those that read images, as they must to read the notes of the student) and download the recommended ones, and download the speech recognition model of whisper.cpp (`large-v3-turbo-q5_0`, 574 MB, by default) into `whisper/` of the data directory; the language spoken is chosen from a menu. The local models are unloaded from the memory at the end of the lecture or of the review. The address of Ollama, a model file of one's own and the reasoning of Gemini are under "Advanced".

The other settings: the Gemini API key (or the `GEMINI_API_KEY` environment variable), the update interval, the language of the notes. The key is kept by the system: the Keychain on iOS, encrypted with a key of the Android Keystore on Android, a file of the data directory readable by the user only on the desktop. On macOS the microphone must be allowed to PencilNotes, or to the terminal running `./gradlew :pencilnotes-desktop:run`. The panel says what is sent to Google with the chosen providers (nothing when everything is local): ask the lecturer before recording.

Running:

* **Android**: `./gradlew :pencilnotes-android:assembleDebug`
* **Desktop**: `./gradlew :pencilnotes-desktop:run` (data in `~/.pencilnotes`, or `-Ddrawview.dataDir=<dir>`)
* **iOS**: open [`/pencilnotes/iosApp`](./pencilnotes/iosApp) in Xcode and run it on a simulator or device.

---

## Running Tests

* **Modules**: `./gradlew :drawview-<module>:jvmTest` (also `iosSimulatorArm64Test`), e.g. `:drawview-data:jvmTest`, `:drawview-explorer:jvmTest`, `:drawview-lecture:jvmTest`, `:drawview-export:jvmTest`
* **Screenshots** of the library and of the editor in the reference windows of the adaptive interface (phones, tablets, desktop; see [docs/adaptive-ui.md](./docs/adaptive-ui.md)): `./gradlew :drawview-explorer:verifyRoborazziJvm` compares the interface with the images of `drawview-explorer/screenshots`, `recordRoborazziJvm` records them again after a change of the interface
* **Sample**:
  * Android: `./gradlew :sample-shared:testAndroidHostTest`
  * Desktop: `./gradlew :sample-shared:jvmTest`
  * iOS: `./gradlew :sample-shared:iosSimulatorArm64Test`

---

## License & Third-Party Credits

DrawView-KMP is licensed under the [MIT License](./LICENSE).

### Third-Party Open Source Libraries
This project relies on and incorporates several open-source libraries:
* **[Google Ink](https://github.com/google/ink)** ([Apache 2.0](./native/ink/LICENSE)) - Freehand stroke generation and geometry modeling.
* **[Abseil C++](https://github.com/abseil/abseil-cpp)** ([Apache 2.0](./native/external/abseil-cpp/LICENSE)) - C++ standard library extensions used by Google Ink.
* **[libtess2](https://github.com/memononen/libtess2)** ([SGI Free Software License B](./native/external/libtess2/LICENSE.txt)) - Polygon tessellation used by Google Ink.
* **[PDFium](https://pdfium.googlesource.com/pdfium)** ([BSD 3-Clause](./native/pdfium/licenses/pdfium.txt)) & **[pdfium-binaries](https://github.com/bblanchon/pdfium-binaries)** ([MIT](./native/pdfium/LICENSE)) - PDF rendering engine for Desktop JVM, Android and iOS.
* **[The FreeType Project](https://www.freetype.org)** ([FTL](./native/pdfium/licenses/freetype.txt)) - *Portions of this software are copyright © 1996-2024 The FreeType Project (www.freetype.org). All rights reserved.*
* **[MicroTeX](https://github.com/NanoMichael/MicroTeX)** ([MIT](./native/tex/README.md)) - Dynamic LaTeX mathematical formula rendering.
* **[Opus](https://opus-codec.org)** ([BSD 3-Clause](./THIRD_PARTY_LICENSES.md#bsd-3-clause-license-opus)) - Compression of the lecture recordings.
* **[VisualOn AAC encoder](https://sourceforge.net/projects/opencore-amr/files/vo-aacenc/)** ([Apache 2.0](./THIRD_PARTY_LICENSES.md#apache-license-version-20)) - AAC encoding of the exported recordings.
* **[Nunito Font](https://github.com/googlefonts/nunito)** ([SIL OFL 1.1](./drawview-ui/NUNITO_OFL_LICENSE.txt)) - Application typography.
* **[Cherry Bomb One Font](https://github.com/satsuyako/CherryBomb)** ([SIL OFL 1.1](./pencilnotes/shared/CHERRY_BOMB_ONE_OFL_LICENSE.txt)) - PencilNotes title typography.
* **Computer Modern, Doublestroke & AMS Fonts** ([Knuth / dsrom / OFL](./drawview-latex/src/commonMain/composeResources/files/tex/fonts/licences/)) - LaTeX mathematical typography.

For complete license texts, copyright notices, and disclaimers, please see [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md).