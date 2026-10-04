# DrawView-KMP

DrawView-KMP is a set of [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) and [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) modules for drawing, handwriting and note-taking applications on **Android**, **iOS**, **Desktop (JVM)** and **Web (Wasm / JS)**: a stylus-oriented canvas editor (Google Ink strokes, text with Markdown and LaTeX, images, PDF pages), the storage of the documents and a document library with folders.

The repository also contains a [sample application](./sample) showing how to build an application with the modules, and **[PencilNotes](./pencilnotes)**, a note-taking application built on them.

---

## Modules

* [`/drawview-core`](./drawview-core) handles drawing logic, strokes, brushes, and rendering abstractions.
* [`/drawview-data`](./drawview-data) handles persistence (Room / SQLite, localStorage on the web): documents, folders and preferences.
* [`/drawview-ui`](./drawview-ui) contains the editor user interface (`DrawScreen`, `EditorSessionScreen`) and its components.
* [`/drawview-explorer`](./drawview-explorer) provides the document library: folders and notes (create, rename, move, delete, multiple selection), sorting, recent documents, and `DocumentLibrary`, a complete note-taking application (library + editor) themed by the caller.
* [`/drawview-pdf`](./drawview-pdf) provides PDF document rendering with PDFium (Desktop JVM, Android and iOS).
* [`/drawview-latex`](./drawview-latex) handles dynamic mathematical formula rendering via MicroTeX.
* [`/drawview-lecture`](./drawview-lecture) provides the lecture assistant: it records the lecture and, with the Gemini API, suggests what is missing from the notes (an `EditorExtension` of the editor; recording on the desktop for now).
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

[`/sample`](./sample) is the DrawView sample application: [`shared`](./sample/shared) (`:sample-shared`, the `App` composable) and the entry points [`androidApp`](./sample/androidApp) (`:sample-android`), [`desktopApp`](./sample/desktopApp) (`:sample-desktop`), [`webApp`](./sample/webApp) (`:sample-web`) and [`iosApp`](./sample/iosApp) (Xcode).

* **Android app**: `./gradlew :sample-android:assembleDebug`
* **Desktop app**:
  * Hot reload: `./gradlew :sample-desktop:hotRun --auto`
  * Standard run: `./gradlew :sample-desktop:run`
* **Web app**:
  * Wasm target: `./gradlew :sample-web:wasmJsBrowserDevelopmentRun`
  * JS target: `./gradlew :sample-web:jsBrowserDevelopmentRun`
* **iOS app**: Open the [`/sample/iosApp`](./sample/iosApp) directory in Xcode and run it on a simulator or device.

---

## PencilNotes

PencilNotes is the note-taking application designed for stylus input, ported from the original
Android app to Kotlin Multiplatform. It is the DrawView document library (`DocumentLibrary`) with
the PencilNotes identity: theme (dynamic colors on Android 12+), Cherry Bomb One title, About
information, icons and full-screen editor.

* [`/pencilnotes/shared`](./pencilnotes/shared) (`:pencilnotes-shared`): the `PencilNotesApp` composable, theme and resources.
* [`/pencilnotes/androidApp`](./pencilnotes/androidApp) (`:pencilnotes-android`), [`/pencilnotes/desktopApp`](./pencilnotes/desktopApp) (`:pencilnotes-desktop`) and [`/pencilnotes/iosApp`](./pencilnotes/iosApp) (Xcode): the entry points (Android, desktop and iOS).

### Lecture assistant

On the desktop, the microphone button of the editor opens the lecture assistant:

* **Recording**: the lecture is recorded (`lectures/doc-<id>/<date>/audio.wav` in the data directory, with `lecture.json`: transcript, outline, suggestions and final review).
* **Live suggestions**: about every minute (cut at a pause of the speech), Gemini receives the new audio, the memory of the lecture (outline, notes already suggested and what the student did with them), the visible pages and the pages around them, a close-up of what was written since the previous update, the typed texts and the PDF slides of the notebook (uploaded once with the Files API). It answers with the transcript, the page the lecturer is talking about and only the content that is neither on the slides nor in the notes: each suggestion can be inserted in the page (blue Markdown text with formulas), listened to in the recording, marked as already noted or dismissed.
* **I missed something**: analyzes the last minutes right away.
* **Final review**: when the recording stops, the whole recording is reviewed once more: complete notes (insertable as new pages) and the content still missing.

Settings (gear of the panel): the Gemini API key (or the `GEMINI_API_KEY` environment variable), the model (`gemini-3.8-flash` by default), the update interval, the pages sent, the slides, the course context and terminology. The key is saved in `lectures/assistant-settings.json` (readable by the user only). On macOS the microphone must be allowed to PencilNotes, or to the terminal running `./gradlew :pencilnotes-desktop:run`. The audio, the pages and the slides are sent to Google: ask the lecturer before recording.

Running:

* **Android**: `./gradlew :pencilnotes-android:assembleDebug`
* **Desktop**: `./gradlew :pencilnotes-desktop:run` (data in `~/.pencilnotes`, or `-Ddrawview.dataDir=<dir>`)
* **iOS**: open [`/pencilnotes/iosApp`](./pencilnotes/iosApp) in Xcode and run it on a simulator or device.

---

## Running Tests

* **Modules**: `./gradlew :drawview-<module>:jvmTest` (also `iosSimulatorArm64Test`, `wasmJsTest`, `jsTest`), e.g. `:drawview-data:jvmTest`, `:drawview-explorer:jvmTest`, `:drawview-lecture:jvmTest`
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
* **[Nunito Font](https://github.com/googlefonts/nunito)** ([SIL OFL 1.1](./drawview-ui/NUNITO_OFL_LICENSE.txt)) - Application typography.
* **[Cherry Bomb One Font](https://github.com/satsuyako/CherryBomb)** ([SIL OFL 1.1](./pencilnotes/shared/CHERRY_BOMB_ONE_OFL_LICENSE.txt)) - PencilNotes title typography.
* **Computer Modern, Doublestroke & AMS Fonts** ([Knuth / dsrom / OFL](./drawview-latex/src/commonMain/composeResources/files/tex/fonts/licences/)) - LaTeX mathematical typography.

For complete license texts, copyright notices, and disclaimers, please see [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md).