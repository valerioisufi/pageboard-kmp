# DrawView-KMP

DrawView-KMP is a modern cross-platform drawing, handwriting, and note-taking application targeting **Android**, **iOS**, **Desktop (JVM)**, and **Web (Wasm / JS)**, built with [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) and [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/).

---

## Project Structure

* [`/iosApp`](./iosApp) contains the iOS application entry point (SwiftUI wrapper).
* [`/desktopApp`](./desktopApp) contains the desktop application entry point.
* [`/androidApp`](./androidApp) contains the Android application entry point.
* [`/webApp`](./webApp) contains the Wasm and JS web browser entry points.
* [`/drawview-core`](./drawview-core) handles drawing logic, strokes, brushes, and rendering abstractions.
* [`/drawview-data`](./drawview-data) handles persistence, Room / SQLite database, and documents store.
* [`/drawview-ui`](./drawview-ui) contains the shared Compose Multiplatform user interface and components.
* [`/drawview-pdf`](./drawview-pdf) provides PDF document rendering with PDFium (Desktop JVM, Android and iOS).
* [`/drawview-latex`](./drawview-latex) handles dynamic mathematical formula rendering via MicroTeX.
* [`/native`](./native) contains the native C++ engines and JNI bridges for Google Ink, MicroTeX, and PDFium.

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

## Running the Apps

* **Android app**: `./gradlew :androidApp:assembleDebug`
* **Desktop app**:
  * Hot reload: `./gradlew :desktopApp:hotRun --auto`
  * Standard run: `./gradlew :desktopApp:run`
* **Web app**:
  * Wasm target: `./gradlew :webApp:wasmJsBrowserDevelopmentRun`
  * JS target: `./gradlew :webApp:jsBrowserDevelopmentRun`
* **iOS app**: Open the [`/iosApp`](./iosApp) directory in Xcode and run it on a simulator or device.

---

## Running Tests

* **Android tests**: `./gradlew :shared:testAndroidHostTest`
* **Desktop tests**: `./gradlew :shared:jvmTest`
* **Web tests**:
  * Wasm: `./gradlew :shared:wasmJsTest`
  * JS: `./gradlew :shared:jsTest`
* **iOS tests**: `./gradlew :shared:iosSimulatorArm64Test`

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
* **Computer Modern, Doublestroke & AMS Fonts** ([Knuth / dsrom / OFL](./drawview-latex/src/commonMain/composeResources/files/tex/fonts/licences/)) - LaTeX mathematical typography.

For complete license texts, copyright notices, and disclaimers, please see [THIRD_PARTY_LICENSES.md](./THIRD_PARTY_LICENSES.md).