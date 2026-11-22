# The libraries ready for their first release

What a review of the build, the API, the native libraries and the workflows found on 2026-11-22,
before the first release of the libraries on Maven Central (0.1.0, phase 6 of
[open-source.md](open-source.md)), and how each finding is fixed. This document starts from the
code as it is: `fix/ci-main` at `ac224997`, two commits ahead of `main`.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

### The platforms

* **The desktop JVM.** `jvm()` has no `jvmTarget` (`KmpLibraryConventionPlugin`): the classes of
  the jars are of Java 21 (class version 65), the JDK of the build, and their Gradle metadata says
  no `org.gradle.jvm.version`. A JDK 17 fails when it loads them (`UnsupportedClassVersionError`),
  not when it resolves them. The consumer checks run on JDK 21.
* **Android 10 to 14.** `ChunkTranscript.grouped()` (`pageboard-lecture`, `SpeechSource.kt`) calls
  `removeLast()` on a `MutableList`: against the `android.jar` of API 37 it is compiled as
  `java.util.List.removeLast()`, a member of API 35, so `RecordingTranscriber` fails with
  `NoSuchMethodError` on Android 10 to 14 (the minimum is API 29). `javap` of the Android classes
  of every library finds no other such call. No lint runs on the libraries: the Android-KMP plugin
  creates its lint tasks only with `com.android.lint`, which they do not apply.

### The data of an application

* `pageboardDesktopDataDirectory` (`pageboard-platform`) is the system property
  `pageboard.dataDir`, or `~/.pageboard` when it is not set. Two applications on the libraries, or
  the sample and another one, share the database, the resources and the secrets (`secure/`).
  PencilNotes sets the property itself, to `~/.pencilnotes`; the sample does not set it.
* `PageboardAndroid.init(context)` can be called again with another context at any time;
  `SecureStore` and `PlatformFiles` are objects reading these roots. The rule of
  [modularization.md](modularization.md), no global mutable state in the APIs, says otherwise.
* `checkDataDirectoryProperty()`, the guard of the property of before the rename
  (`drawview.dataDir`), is public.

### The release

* `release.yml` runs on the tags `pageboard-v*`, which a ruleset keeps from being moved or deleted.
  Each run uploads to the Central Portal (`publishToMavenCentral`, released there by hand), creates
  the release of GitHub and deploys the documentation. A release candidate cannot be uploaded,
  checked and dropped (phase 6 of [open-source.md](open-source.md)) without spending the tag of its
  version.
* Its actions are not pinned (`softprops/action-gh-release@v2`, `gradle/actions/setup-gradle@v4`,
  ...) in the jobs that hold the keys of Maven Central and of the signature; `native-caches` pins
  its own, and `native.yml` is at v5.
* Its comment says that what is published is built from its sources alone, but `setup-gradle` and
  `org.gradle.caching=true` may take the JNI libraries from the build cache
  (`buildDesktopNativeLibs` is cacheable).
* The signature is added only when `signingInMemoryKey` is given: a release without it uploads
  unsigned artifacts, which the Portal refuses at validation.
* `main` fails `check.yml` and `native.yml` (2026-11-21); `fix/ci-main` passes both.

### iOS

* `pdfium.def` links `-framework PDFium`, a dynamic framework that the application embeds: the
  final link of an application with `pageboard-pdf`, also through `pageboard-export` and
  `-export-ui`, fails without it. Its `Module.md` says "embedded by the application" only; the
  framework is an asset of the release of GitHub (`PDFium.xcframework.zip`). No consumer check
  builds for iOS.

### The API

* `publicResClass = true` in `pageboard-ui` (299 lines of its JVM dump of 1133) and
  `pageboard-explorer` (138 of 234): every string, drawable and font is public API. The other
  modules take four resources of `pageboard-ui` (`common_action_close`, `about_dialog_title`,
  `name_error_exists`, `date_months_short`) and none of `pageboard-explorer`.
* `pageboard-latex` publishes the package of MicroTeX, `io.nano.tex` (`Font`, `Graphics2D`,
  `LaTeX`, `Rect`, `TeXRender`), as public; no public type of
  `io.github.valerioisufi.pageboard.latex` uses it. The native code reaches it by name
  (`native/tex/port`: `jni_latex.cpp`, `jni_render.cpp`, `jni_def.*`, `graphics_android.cpp`), and
  so do the keep rules of R8 (`consumer-rules.pro`).
* Types of `implementation` dependencies in the API: Room (`RoomDatabase.Builder`,
  `PageboardDatabase : RoomDatabase`, in `pageboard-store-room`), Ktor (`HttpClient`, in
  `pageboard-lecture`), Material 3 (`ColorScheme`, `SnackbarData`, in `pageboard-ui`), the
  resources of Compose (the `Res` classes above), and kotlinx.serialization (`KSerializer`,
  `Encoder`, `Decoder`, in `pageboard-sync-protocol`: found by `checkApiDependencies`, phase 4). An application that closes the database or gives
  a builder declares Room itself, as the tests of `pageboard-explorer`, `-session`, `-search` and
  `-sync` do. (`atomicfu`, `api` in `pageboard-platform` and `pageboard-canvas`, is in no JVM
  signature but is in the API: the public typealias `SyncLock` and the inline `syncLock` reach it,
  and on the JVM the typealias is `Any`.)
* 39 artifacts share one version and nothing aligns them: an application may resolve `pageboard-ui`
  0.1.0 with `pageboard-canvas` 0.2.0, which a 0.x may break.
* One opt-in annotation, `@ExperimentalSyncApi`; nothing marks what is public only for another
  module of Pageboard.
* Material 3 is `1.12.0-alpha03` since the first commit, for `MotionScheme.expressive()` in
  `PageboardTheme`, its only expressive API: an application gets the alpha with the libraries.
  `compose.materialIconsExtended` (1.7.3, no longer updated, a large artifact) is in `pageboard-ui`
  and `pageboard-lecture-ui` for six icons that `PageboardIcons` (Lucide) has: `Close`, `Code`,
  `Trash` (for `Delete`), `Undo`, `Redo`, `Mic`.
* The libraries write 44 lines with `println` and `System.err.println`, some with the names of
  files. `pageboard-lecture` reads the key of Gemini from the environment variable
  `GEMINI_API_KEY` on the desktop (`AssistantSettingsStore.apiKey`): a choice of the application,
  made by the library.

### The native libraries

* **Linux**: the JNI libraries are built on `ubuntu-24.04` and `ubuntu-24.04-arm` (glibc 2.39, GCC
  13), with libstdc++ linked dynamically: probably not loaded by older distributions (Ubuntu
  22.04, Debian 12). Not measured.
* **Intel Macs**: `darwin-x86_64` is cross-built on Apple silicon and never tested (`native.yml`,
  `test: false`), but published.
* **The size**: every desktop jar holds the JNI libraries of the five platforms, about 19 MB for
  Windows alone in the modules with native code and about five times as much in all, which every
  desktop application ships.
* **Android**: `pageboard-ink`, `-latex`, `-pdf`, `-lecture` and `-sync-iroh` depend on their
  `pageboard-native-*` only `if (findProject(...) != null)`: a build without the Android SDK would
  publish them without it.
* Fine as they are: macOS 13.0 and iOS 15.0 as deployment targets, the static C++ runtime on
  Windows, the ELF segments of 16 KB on Android (the prebuilt PDFium included), the JNI libraries
  of every platform required in a release (`checkDesktopNativeLibraries`), the keep rules of the
  JNI classes.

### The publication and the documentation

* Every publication of every module carries the javadoc of Dokka: 4.7 MB for `pageboard-model`,
  five times (the root, the JVM, Android and the two iOS targets).
* The `sourceLink` of Dokka points to `blob/main`, not to the sources of the version.
* `pageboard-handwriting-mlkit` publishes empty JVM and iOS variants, `-windows` empty Android and
  iOS ones, `-pencilkit` an Android one that reads nothing.
* The libraries are built with Kotlin 2.4.20, and nothing says which Kotlin an application needs.
* No `Module.md` says what a library adds to the manifest of an application: `pageboard-lecture`
  (`RECORD_AUDIO`, `POST_NOTIFICATIONS`, a foreground service of type `microphone`),
  `pageboard-sync-ui` (a foreground service of type `dataSync`), `pageboard-sync-lan` (`INTERNET`,
  `ACCESS_LOCAL_NETWORK`), `pageboard-ui` (a `FileProvider`). Google Play asks the application to
  declare its foreground services.
* Texts out of date: `CHANGELOG.md` has a section `0.1.0` already; [open-source.md](open-source.md)
  speaks of the tags `drawview-v*`; `gradle.properties` says "To change when the repository has its
  remote"; `CLAUDE.md` describes `EditorExtension` as built from lambdas, without a hook in the
  text box, while it is an abstract class with `textSuggestions` ([editor-ports.md](editor-ports.md)).
* Fine as they are: the POM (name, description, URL, MIT, developer, SCM), the sources and javadoc
  jars, the licenses in `META-INF/pageboard/<module>/`, the ABI dump of every library, the strict
  explicit API mode.

## Requirements

1. What is costly to change once on Maven Central is decided before 0.1.0: the API, the places
   where the data are kept, the shape of the artifacts.
2. A library runs where it says: Android 10 (API 29) and later; a JVM 17 or later on macOS 13
   (Apple silicon and Intel), on Linux with glibc 2.28 or later (x86_64, aarch64) and on Windows 10
   (x86_64); iOS 15 (arm64 devices and simulators).
3. The data of an application are its own: no place is shared by default with another application.
   A check given `pageboard.dataDir` never touches real notes ([CLAUDE.md](../../CLAUDE.md)).
4. The public API holds what an application uses; what is public only for another module of
   Pageboard says so; the type of every signature comes with its dependency.
5. A release is tried before it is published, and what cannot be undone (Maven Central, the tag of
   the version) comes last.
6. Every finding gets a check that fails if it comes back, where one is possible.
7. Nothing stored and nothing that travels changes: the Room schema, the stored formats,
   `~/.pencilnotes`, `PROTOCOL_VERSION` 1 and `ProtocolFormatTest` stay as they are.

## Design

| Responsibility | Where | GRASP |
|---|---|---|
| The version of Java of the desktop | `KmpLibraryConventionPlugin`: `jvmTarget` 17, `-Xjdk-release=17`, `org.gradle.jvm.version` in the metadata | Protected Variations |
| The members the minimum API has | `removeAt` in `ChunkTranscript.grouped()`; `lintAndroidMain` of every library (`NewApi`) | Protected Variations |
| Where the data of an application are | `PageboardDesktop.init(directory)` (`pageboard-platform`), called by the application like `PageboardAndroid.init` | Information Expert, Controller |
| The type of each signature with its dependency | `api` where a type is in the API; `checkApiDependencies` (build-logic) | Information Expert, Protected Variations |
| The API between the modules | `@InternalPageboardApi` (`pageboard-platform`), the resources internal, `PageboardStrings`, MicroTeX in the package of Pageboard | Protected Variations, Indirection |
| The targets of a module | `pageboard.targets` in the `gradle.properties` of the module | Information Expert |
| The versions taken together | `pageboard-bom`, a dependency of every library | Pure Fabrication |
| The dependencies of the interface | Material 3 stable if possible; `PageboardIcons` | Low Coupling |
| What the libraries log | `PageboardLog` (`pageboard-platform`): the log of each platform | Pure Fabrication, Indirection |
| The key of the environment | the application (`AssistantSettingsStore(environmentKey)`) | Information Expert |
| The JNI libraries of Linux and of Intel Macs | `native.yml`, `release.yml`: built in `manylinux_2_28`, their glibc checked; the x86_64 tests under Rosetta 2 | Protected Variations |
| The native libraries of Android in a release | `androidNative()` (build-logic) | Protected Variations |
| What an iOS application embeds | `pageboard-pdf/Module.md`, the README, the consumer check `ios` | Information Expert |
| Which tags release what | `release.yml`: `-rc.N` uploaded and dropped, the others released | Controller, Protected Variations |
| The JNI libraries of one platform | variants by operating system and architecture of the `-jvm` artifacts (tried first) | Polymorphism |

### Java 17 on the desktop

* `jvm { compilerOptions { jvmTarget = JVM_17; freeCompilerArgs.add("-Xjdk-release=17") } }` in
  `KmpLibraryConventionPlugin`: the classes are of Java 17, and the code compiles against the API
  of the JDK 17, so that a member of Java 18 to 21 is an error of the compiler, not of a user. The
  Android target stays on Java 11; the tests run on JDK 21 as now.
* `jvmApiElements` and `jvmRuntimeElements` carry `org.gradle.jvm.version` = 17: a JVM application
  with an older toolchain fails when it resolves them, with the reason.
* The consumer check `model-jvm` runs on a JDK 17 (its toolchain, found or downloaded by the foojay
  resolver, as in the root build).

### Android 10 to 14

* `groups.removeAt(groups.lastIndex)` in `ChunkTranscript.grouped()`.
* `KmpLibraryConventionPlugin` applies `com.android.lint`: the Android-KMP plugin then gives each
  library `lintAndroidMain`, here for `NewApi` only (`checkOnly`), as an error. The job `android` of
  `check.yml` runs it for every library, and `check` runs it too (the lint of the JVM target, on the
  same check, finds nothing there).
* It is shown to find `removeLast()` before the fix. The lint of the applications with
  `checkDependencies` was tried first: it does not reach the Android-KMP libraries ("No issues
  found" with the call still there).

### The data directory

* `PageboardDesktop` (`pageboard-platform`, desktop), the counterpart of `PageboardAndroid`:
  * `init(dataDirectory: File)`: the application names its directory at its start, before a
    library reads it.
  * `dataDirectory: File`, in place of `pageboardDesktopDataDirectory`: the property
    `pageboard.dataDir` when it is given (the checks and the tests: it always wins), else the
    directory of `init`, else an error saying to call `init`. There is no `~/.pageboard` any more.
  * `applicationDataDirectory(name: String): File`: where the system keeps the data of an
    application named `name`, for a new application: `~/Library/Application Support/<name>` on
    macOS, `%LOCALAPPDATA%\<name>` on Windows, `$XDG_DATA_HOME/<name>` (`~/.local/share/<name>`) on
    Linux.
* Given once: `init` with another directory after the first read fails, and `PageboardAndroid.init`
  keeps the application context of its first call and fails on another one. The root of the
  platform is then the one setting of a process, given by the application at its start and never
  changed, and the rule of [modularization.md](modularization.md) says so. `SecureStore` and
  `PlatformFiles` stay objects over it. iOS needs nothing: each application has its container.
* The guard of `drawview.dataDir` stays where the directory is read, no longer public: an old
  command still stops.
* PencilNotes calls `PageboardDesktop.init(File(home, ".pencilnotes"))` instead of setting the
  property: its notes stay where they are. The sample calls `init(File(home, ".pageboard"))`, where
  its data are today. Both are tried on a scratch directory (`-Dpageboard.dataDir`), and with
  `-Ddrawview.dataDir` alone, which stops them.
* The README and the reference show the two `init` calls in the minimal application.

### The dependencies of the API

* `api(libs.androidx.room.runtime)` in `pageboard-store-room`, `api(libs.ktor.client.core)` in
  `pageboard-lecture`, `api(libs.compose.material3)` and `api(libs.compose.components.resources)`
  in `pageboard-ui`, `api(libs.kotlinx.serialization.core)` in `pageboard-sync-protocol`; the
  resources of `pageboard-explorer` internal (nothing outside takes them). `atomicfu` stays `api`,
  for `SyncLock` and `syncLock`.
* `checkApiDependencies` (build-logic, part of `check`): the classes named by the JVM dump of a
  library that are not of the library, of Kotlin or of the JDK are in the jars of what it declares
  `api`, transitively (the variant `jvmApiElements` of each). A type that comes through an
  `implementation` dependency fails it.
* The consumer check `library-desktop`: the minimal application of the README with
  `pageboard-explorer`, `-store-room` and `-ink` only, compiled, and its store opened in a
  directory of the build (`PageboardDesktop.init`) and closed by a run on JDK 17.

### The API between the modules

* `@InternalPageboardApi` in `pageboard-platform`: `@RequiresOptIn(level = ERROR)`, between the
  modules of Pageboard, may change in any version. The libraries opt in through the convention
  plugin, the applications of the repository where they use it.
* `publicResClass = false` in `pageboard-ui` (in `pageboard-explorer` with phase 4). `pageboard-ui`
  gives the four resources the others take through `PageboardStrings` (`@InternalPageboardApi`):
  `close`, `aboutTitle`, `nameExists`, `monthsShort`; their translations stay in one place.
* MicroTeX moves to `io.github.valerioisufi.pageboard.latex.microtex`, its classes `internal` and
  their members public (JNI, and the names the JVM gives to `internal` members); the JNI names of
  `native/tex/port` and the keep rules move with them. iOS reaches MicroTeX through its C API and
  does not change.
* The other declarations public only for another module, found by a reading of the dumps in the
  phase, take the annotation: `SyncLock` and `syncLock`, `JvmNativeLibraries` (no application of
  the repository uses them).
* `pageboard.targets` in the `gradle.properties` of a module (read by `KmpLibraryConventionPlugin`;
  every target when it is not there): `android` for `-handwriting-mlkit`, `jvm` for
  `-handwriting-windows`, `jvm,ios` for `-handwriting-pencilkit` (its Android stub goes).
  PencilNotes already takes them from the source set of their platform. Without an Android target
  the JVM dump of a module is `api/<module>.api`, not `api/jvm/<module>.api`.

### The versions together

* `pageboard-bom` (`java-platform`, published with the others): a constraint on every library at
  its version. Every library depends on it (`api(platform(project(":pageboard-bom")))` in
  `commonMain`), so that Gradle takes all of them at the highest version an application asks for
  any of them.
* The README gives `implementation(platform("io.github.valerioisufi:pageboard-bom:0.1.0"))` and the
  modules without a version; the consumer checks do the same. Dokka leaves it out.

### The interface

* Material 3: the latest stable `org.jetbrains.compose.material3` that compiles with Compose
  Multiplatform 1.12.1. `MotionScheme.expressive()` stays if it has it; otherwise the theme leaves
  it, and the components move with the standard scheme. If no stable version fits, the alpha
  stays, and the README and the changelog say so.
* `PageboardIcons.Close`, `Code`, `Trash`, `Undo`, `Redo` and `Mic` in place of the Material icons;
  `compose.materialIconsExtended` leaves the main source sets of `pageboard-ui` and
  `pageboard-lecture-ui` (the tests of `pageboard-explorer` may keep it).
* The screenshots that change are recorded again in the reference environment (the label
  `record-screenshots` on the pull request).

### The logs and the key of Gemini

* `PageboardLog` (`pageboard-platform`, `@InternalPageboardApi`): `warn(message, error)` writes to
  the log of the platform: `android.util.Log` (tag `Pageboard`) on Android, a `System.Logger`
  named `Pageboard` on the desktop (the application routes it: to `java.util.logging` by default,
  or to its own logging), `NSLog` on iOS. The 44 lines go through it, and name a document by its
  id, never by a name or a path.
* `checkNoPrints` (build-logic, part of `check`): no `println`, `System.out` or `System.err` in the
  main sources of a library.
* `AssistantSettingsStore(environmentKey: () -> String? = { null })`: `pageboard-lecture` reads no
  environment. PencilNotes on the desktop gives `{ System.getenv("GEMINI_API_KEY") }`, as the
  reference describes; `usesEnvironmentKey` keeps its meaning.

### The native libraries

* **Linux**: the Linux jobs of `native.yml` and `release.yml` build in the container
  `manylinux_2_28` of their architecture (AlmaLinux 8, glibc 2.28, a recent GCC), with the JDK,
  CMake and rustup installed in it, and libstdc++ and libgcc linked statically
  (`-static-libstdc++ -static-libgcc`). A check after the build fails on a symbol newer than
  `GLIBC_2.28`, or on a `libstdc++.so` among the libraries needed. It is run first on the
  libraries of today, to measure what the finding supposes.
* **Intel Macs**: `native.yml` tests `darwin-x86_64` on the arm64 runner with an x86_64 JDK under
  Rosetta 2 (`setup-java` with `architecture: x64`): the tests of the native engines, as on the
  other platforms. An Intel runner if the image has no Rosetta.
* **Android**: `androidNative(":pageboard-native-ink")` (build-logic) in place of the five
  `if (findProject(...))`: the dependency when the project is there; without it, a release (a
  version without `-SNAPSHOT`) fails its publication, as `checkDesktopNativeLibraries` does for the
  desktop.
* **One platform per application** (the last phase, tried first on `pageboard-ink`): the `-jvm`
  artifact of a module with JNI libraries keeps the jar of every platform as its default variant,
  and adds one variant per platform with its libraries only, under the attributes
  `org.gradle.native.operatingSystem` and `org.gradle.native.architecture`. An application that
  asks for the two attributes on its runtime classpath (the reference says how) gets its platform
  only; one that asks nothing gets all of them, as today. If the component of the Kotlin plugin
  takes no such variants, or Gradle does not prefer the default when nothing is asked, this part
  is left for later and the reference gives the size of the jars.

### iOS

* `pageboard-pdf/Module.md`, the `Module.md` of `pageboard-export`, the README and the reference:
  an iOS application with these modules embeds the `PDFium.framework` of the release of the same
  version (`PDFium.xcframework.zip` of the release of GitHub), and gives its folder to the link of
  its Kotlin framework (`linkerOpts("-F...")`).
* `release.yml` writes the SHA-256 of the zip in the notes of the release.
* The consumer check `ios` (macOS only, skipped elsewhere): a Kotlin framework for
  `iosSimulatorArm64` with `pageboard-explorer`, `-store-room`, `-ink`, `-pdf`, `-latex`,
  `-export`, `-sync-iroh` and `-handwriting-pencilkit` from `mavenLocal()`, linked with the PDFium
  of `build/pdfium`: the static libraries of the klibs, the frameworks of their `.def` files and
  the Swift runtime are all found.

### The release

* The tags `pageboard-vX.Y.Z-rc.N` (with `VERSION_NAME` `X.Y.Z-rc.N`) run the same jobs: the
  artifacts are uploaded to the Portal and not released, with no release of GitHub and no
  documentation. The maintainer looks at the deployment in the Portal and drops it.
* The tags `pageboard-vX.Y.Z` are released when the Portal validates them
  (`publishAndReleaseToMavenCentral`), then the release of GitHub and the documentation follow, as
  now. A version is tagged only after a candidate that differs from it by `VERSION_NAME` alone.
* The actions of `release.yml` are pinned by their SHA, at the versions of `native.yml`.
* `--no-build-cache`, and `cache-disabled` on `setup-gradle`, in `release.yml`: what is published
  is built from the sources of the tag.
* `PublishConventionPlugin`: for a version without `-SNAPSHOT`, the tasks that publish to Maven
  Central fail without `signingInMemoryKey`. `mavenLocal()` is not concerned: the consumer checks
  publish there.
* `CHANGELOG.md` keeps `## Unreleased` until the tag; the section of a version is written, with
  its date, in the commit that sets `VERSION_NAME`.

### The publication and the documentation

* Empty javadoc jars (`JavadocJar.Empty()`): the documentation is the one of Dokka on GitHub
  Pages, published by `release.yml`.
* The `sourceLink` of Dokka points to the tag of the version (`blob/pageboard-v<VERSION_NAME>`),
  to `main` for a snapshot.
* The `Module.md` of `pageboard-lecture`, `-sync-ui`, `-sync-lan` and `-ui` get a section "On
  Android": what the module adds to the manifest of the application, and what Google Play asks the
  application to declare.
* The README and the reference: the platforms of requirement 2, Kotlin 2.4 or later, the BOM, the
  two `init` calls, PDFium on iOS, the size of the desktop jars.
* `CHANGELOG.md`, [open-source.md](open-source.md) (the tags `pageboard-v*`, its phase 6 with the
  candidate), the comment of `gradle.properties`, `CLAUDE.md` (`EditorExtension`).

## Phases

One commit per phase on `feat/release-readiness`. Each phase runs the checks of `CLAUDE.md`; on the
Windows machine the klib dumps of the Compose and Room modules are written by hand, and
`check.yml` verifies them on macOS.

| Phase | Content | Status |
|---|---|---|
| 1 | This plan | Done |
| 2 | Java 17 on the desktop (`jvmTarget`, `-Xjdk-release`, `org.gradle.jvm.version`, `model-jvm` on JDK 17); Android 10 to 14 (`removeAt`, `lintAndroidMain` of every library for `NewApi`) | Done: classes of version 61, `org.gradle.jvm.version` 17 in the metadata, every JVM source and test compiled against the JDK 17, `model-jvm` run on a JDK 17; `lintAndroidMain` found `removeLast()` before the fix and nothing after, in the 34 libraries. On Windows the known failures only (the klib dumps of the Compose and Room modules, `ExportScopeTest`, `TextHeightTest`, `LectureSharingTest`), and `RecordingsExportTest`, whose `/no/such/dir` is `D:\no\such\dir` there |
| 3 | The data directory: `PageboardDesktop`, given once, no default; PencilNotes and the sample; the rule of `modularization.md` | Done: `PageboardDesktop` (`init`, `dataDirectory`, `applicationDataDirectory`), `PageboardAndroid.init` given once, the guard of `drawview.dataDir` internal; PencilNotes stopped with `-Ddrawview.dataDir` alone and opened its library on a scratch directory. The tests of the modules touched pass on Windows but for the known failures (`TextHeightTest`; three tests of `pencilnotes-desktop`, `AgentCliTest` and `DataDirectoryLockTest`, which fail there before the change too) |
| 4 | The dependencies of the API: `api` where a type is in a signature; `checkApiDependencies`; the consumer check `library-desktop` | Done: `checkApiDependencies` found the four types expected and kotlinx.serialization in `pageboard-sync-protocol`, and passes in every library after the fix; the resources of `pageboard-explorer` internal, its klib dump written by hand (Windows). `library-desktop` runs with the consumer checks of `check.yml`: here the Compose modules cannot be published (their iOS metadata) |
| 5 | The API between the modules: `@InternalPageboardApi`, the resources internal and `PageboardStrings`, MicroTeX in the package of Pageboard, the targets of the readers | Done: `LatexNativeTest` renders a formula through the JNI names of the new package (Windows libraries built again); the sample titles itself (it took the name of `pageboard-ui`); the klib dumps of `pageboard-ui` and `pageboard-latex` written by hand, the one of `pageboard-latex` equal to what Windows writes. On Windows the known failures only (`TextHeightTest`, the klib dumps of the Compose and Room modules) |
| 6 | The interface: Material 3, `PageboardIcons` in place of the Material icons, the screenshots | Done: the Material icons were imported and no longer drawn (the code draws `PageboardIcons` already), so their imports and `materialIconsExtended` leave `pageboard-ui` and `pageboard-lecture-ui` and no screenshot changes. No stable Material 3 goes with Compose Multiplatform 1.12 (1.9.0 is the last stable one, 1.13.0 a release candidate): the alpha stays, said in the catalog, in the README and in the changelog (phase 12) |
| 7 | The logs (`PageboardLog`, `checkNoPrints`) and the key of the environment given by PencilNotes | Done: `PageboardLog` (Logcat, `System.Logger`, `NSLog`), the 44 prints through it, by id and without names or paths, `checkNoPrints` passing in every library; PencilNotes gives the key (`environmentGeminiKey`). The tests of the lecture no longer read the `GEMINI_API_KEY` of the machine, as on the CI: on Windows `LectureSharingTest.theAudioIsSharedAsTheDevicesChoose` then fails with three tests of its class, before this change too. The klib dumps of `pageboard-lecture` and `-lecture-ui` written by hand |
| 8 | The versions together: `pageboard-bom` | To do |
| 9 | The native libraries: Linux in `manylinux_2_28` with its check, `darwin-x86_64` tested under Rosetta 2, `androidNative()` | To do |
| 10 | iOS: PDFium in the documentation and in the release, the consumer check `ios` | To do |
| 11 | The release: the candidates, the release on validation, the actions pinned, no build cache, the signature required; the empty javadoc, the `sourceLink` of the version | To do |
| 12 | The texts: the `Module.md` of the manifests, the README and the reference, `CHANGELOG.md`, `open-source.md`, `gradle.properties`, `CLAUDE.md` | To do |
| 13 | One platform per application: the variants of the JNI libraries, tried on `pageboard-ink` first | To do |
| 14 | The checks on the Mac, then the candidate `pageboard-v0.1.0-rc.1`, checked in the Portal and dropped (with the maintainer); then `pageboard-v0.1.0` | To do |

Left to the maintainer: `main` fast-forwarded to `fix/ci-main`, which this branch starts from; the
namespace `io.github.valerioisufi` verified on the Central Portal and the four secrets of the
environment `maven-central` (phase 6 of [open-source.md](open-source.md)); the label
`record-screenshots` on the pull request; the tags.

## Decisions

### With the maintainer

* **No device keeps a library of before the rename** (2026-11-22): the names of the database and of
  the resources on Android and iOS (`pageboard.db`, `pageboard_resources`) stay as the rename left
  them.

### Taken alone, all revertible

* **This branch starts from `fix/ci-main`**, not from `main`, which fails its checks until it takes
  `fix/ci-main`. Once `main` is fast-forwarded to it, this branch is on `main`.
* **Java 17 on the desktop**, the Android target staying on 11: an older JDK is rare among desktop
  applications today, and 17 keeps the API the code may use.
* **glibc 2.28** (`manylinux_2_28`) on Linux: RHEL 8, Debian 10, Ubuntu 18.10 and later.
* **The root of the platform stays global, given once.** Making `SecureStore`, `PlatformFiles` and
  the directories instances passed in would change about 75 calls in twelve modules, for a process
  that has one application.
* **The sample keeps `~/.pageboard`**, where its data are; a new application takes
  `applicationDataDirectory`.
* **`%LOCALAPPDATA%` on Windows**, not `%APPDATA%`: the models and the recordings are too large to
  roam with a profile.
* **`PageboardStrings`** for the four resources shared, instead of copies of their translations in
  each module.
* **The lint of each library, for `NewApi` only**: the other checks of lint would need a baseline
  first.
* **Candidates dropped, versions released on validation**: the candidate is the check by hand, and
  the version that follows it needs none.
* **Empty javadoc jars**: no IDE reads them for Kotlin, and the documentation is on GitHub Pages.
* **Kotlin 2.4 or later**, written in the README, not a lower `languageVersion`.
* **The jar of every platform stays the default** of the desktop artifacts: an application that asks
  for nothing works as today.

## Left for later

* `SecureStore`, `PlatformFiles` and the data directory as instances, if a process ever needs two
  roots.
* A lower `apiVersion` and `languageVersion`, for applications on an older Kotlin.
* PDFium for iOS through the Swift Package Manager.
* The other checks of lint on the libraries, with a baseline.
* Windows on ARM (`windows-aarch64`): PDFium has a package, but the JNI libraries are not built.
