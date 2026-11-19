# The libraries named Pageboard

The libraries are renamed from DrawView to Pageboard before their first release and before the
repository is public, while a rename costs nothing to anyone but this repository. Everything takes
the new name: the modules, the packages, the types, the build, the docs, and also what the
applications store and what devices send each other. The repository keeps no code for the names of
before: the notes of the maintainer are carried over once, outside of it. This document starts
from the code as it is (main at cd691afa, 2026-11-20).

The responsibilities are assigned following the GRASP principles (Information Expert, Low
Coupling, High Cohesion, Protected Variations).

## Starting point

* **The name says less than the libraries do.** "DrawView" reads as one Android view to draw in.
  The libraries are an editor of pages: handwriting, rich text, PDF and formulas, export and print,
  sync between devices and with others, lectures, search, tools for models. PencilNotes is the
  application built on them.
* **Why Pageboard:** a board made of pages, which is what sets the editor apart from the infinite
  canvases. Searched on GitHub and the web (2026-11-19): a deprecated editor of web contents with
  5 stars, and two projects with one star or none; nothing active in notes, ink or whiteboards. The
  other names searched were taken in the same field (Tratto, ScribeKit, PageKit, NoteCanvas,
  CoCanvas, Inkboard, Quaderno, Lavagna and others). Trademarks and domains were not searched.
* **Where the name is**, about 34,000 occurrences:
  * 34 modules `drawview-*` (directories, Gradle projects, Maven artifacts, Android namespaces);
  * the packages `io.github.valerioisufi.drawview.*`, in about 990 files, with the JNI symbols of
    the native code (`Java_io_github_valerioisufi_drawview_*`) and the cinterop definitions;
  * the public types `DrawView*` and the model types `Draw*` (below);
  * the convention plugins (`drawview.kmp.library`, `drawview.compose.library`,
    `drawview.android.native`, `drawview.jvm.natives`, ...), the Gradle properties
    (`drawview.native.prebuilt`, `drawview.android.debug.abis`, `drawview.publish`,
    `drawview.bench.*`, ...) and the environment variables (`DRAWVIEW_PDFIUM_DIR`, ...);
  * the Room schemas, kept in a directory named after the database class
    (`drawview-store-room/schemas/io.github.valerioisufi.drawview.store.room.db.DrawDatabase`);
  * the docs, `CLAUDE.md`, the workflows and the consumer checks.
* **The Maven artifacts were never published**: no one depends on these coordinates.
* **The names stored or sent**, found by a search of the string literals:

  | Name | Where it is | Kind |
  |---|---|---|
  | `drawview/sync/1` | ALPN of the connections over iroh (`IrohTransport`) | protocol |
  | `drawview://join` | the invitations to a shared document (`Invitation`) | protocol |
  | `_drawview._tcp` | the Bonjour service of the LAN sync, also in the `Info.plist` of PencilNotes | protocol |
  | `drawview-archive`, `.drawview` | the manifest and the extension of the archives of notes (`ArchiveFormat`), and the `format` of the export tool of the agents | stored format |
  | `drawview.shape` | the type of the shapes: the column `type` of `custom_elements`, the operations (`operations.change`), the snapshots and the archives (`ShapeElement`) | stored format |
  | `io.github.valerioisufi.drawview.sample.shape` | the type of the shape of the sample | stored format |
  | `io.github.valerioisufi.drawview` | the service of the Keychain on iOS (`SecureStore`): the keys of the devices and the tokens | stored data |
  | `drawview-secure-store`, `drawview_secure_store` | the key of the Android Keystore and the preferences it encrypts (`SecureStore`): the same secrets on Android | stored data |
  | `drawview://document/<id>`, `io.github.valerioisufi.drawview.DOCUMENT_ID` | the shortcuts of documents pinned on the home screen of Android | stored by the system |
  | `drawview-sync` | the notification channel of Android | stored by the system |
  | `.drawview_tex_resources` | the marker of the TeX files extracted in the data directory | stored data |
  | `~/.drawview`, `io.github.valerioisufi.drawview` | the data directory of the desktop sample and the application id of the Android sample | stored data |
  | `drawview.dataDir` | the system property of the data directory on desktop, set by PencilNotes to `~/.pencilnotes` and given to every check | the rule that keeps real notes safe |

* **PencilNotes keeps its application id** (`com.studiomath.pencilnotes`, and
  `io.github.valerioisufi.pencilnotes` for debug builds) and its data directory: an updated install
  opens the same database. The package of a class does not reach the database: Room names tables
  after the entities, and the file of the database has no `drawview` in it. Of the names above,
  only the type of the shapes is inside the notes.
* **The serial names** of the sealed classes that are stored or sent (`DocumentChange`,
  `ElementData`, `CatalogChange`, `SyncMessage`) are given by `@SerialName`; a class without one
  would be named after its package, which the rename changes.

## Requirements

1. Everything takes the new name, the stored formats and the protocol included, in a way that fits
   what each thing is: no name made by gluing "Pageboard" in front of an old one.
2. The repository keeps no code that reads the names of before.
3. The notes the maintainer keeps on the computer survive the update, with their lectures, folders
   and settings.
4. The protocol changes: `ProtocolFormatTest` is re-recorded, and the devices are updated together.
   A device on the old build and one on the new build cannot sync.
5. The Room schema does not change. The changes of the stored formats are named here; with no
   release of the libraries or of the new PencilNotes, they have no migration in the code.
6. Every check that gives the data directory keeps working: a check given the old property never
   opens a default directory.

## Design

| Responsibility | Where | GRASP |
|---|---|---|
| Guard the serial names | a test that records the serial name of every subclass of the stored and sent sealed classes, before the packages move | Protected Variations |
| The data directory on desktop | `pageboardDesktopDataDirectory` in `pageboard-platform`: `pageboard.dataDir`; a run given the old `drawview.dataDir` alone stops with an error | Information Expert |
| The build | the convention plugins and `settings.gradle.kts`, renamed together | High Cohesion |
| The libraries and the applications | the libraries named Pageboard; PencilNotes and the sample depend on them as before | Low Coupling |
| The notes of the maintainer | a one-off conversion of the database of the computer, outside of the repository | none in the code |

### The names

The name of the library goes where the library itself is named: its controller, its theme, its
icons, its engines, its database. The model names what is on a page, so it takes the plain words,
with `Element` where a plain word would clash with Compose (`Text`, `Image`, `Canvas`, `Stroke`,
`Resource`) and to match `ShapeElement`, which is already there.

| Today | Renamed |
|---|---|
| `DrawView` (the composable) | `Pageboard` |
| `DrawViewController` | `PageboardController` |
| `DrawViewTheme`, `DrawThemeColors` | `PageboardTheme`, `PageboardColors` |
| `DrawViewIcons`, `DrawViewRes` | `PageboardIcons`, `PageboardRes` |
| `DrawViewEngines` | `PageboardEngines` |
| `DrawViewElevation`, `DrawViewRadius`, `DrawViewShapes` | `PageboardElevation`, `PageboardRadius`, `PageboardShapes` |
| `DrawViewWindow`, `DrawViewWindowDecor`, `DrawViewMenuBar` | `PageboardWindow`, `PageboardWindowDecor`, `PageboardMenuBar` |
| `DrawViewAndroid`, `DrawViewFileProvider` | `PageboardAndroid`, `PageboardFileProvider` |
| `drawViewDesktopDataDirectory` | `pageboardDesktopDataDirectory` |
| `DrawDatabase`, `DrawDatabaseConstructor` | `PageboardDatabase`, `PageboardDatabaseConstructor` |
| `DrawDocument`, `DrawPage` | `Document`, `Page` |
| `DrawDocumentRepository` | `DocumentRepository` |
| `DrawElement`, `DrawCustomElement` | `PageElement`, `CustomElement` |
| `DrawStroke`, `DrawText`, `DrawImage`, `DrawPdf`, `DrawTextMarkup` | `StrokeElement`, `TextElement`, `ImageElement`, `PdfElement`, `TextMarkupElement` |
| `DrawResource` | `DocumentResource` |
| `DrawPreferences` | `EditorPreferences` |
| `DrawCanvas` (the composable) | `PageCanvas` |
| `DrawScreen` (internal) | `DocumentScreen` |

`FormulaDrawing.DrawLine`, `DrawRect` and `DrawRoundRect` keep their names: they are commands to
draw, not the library. Where a nested `Document` or `Page` already exists (`SearchSource`,
`ShareFolder`, `LectureAttachments`) the code that refers to the model qualifies it.

### What else changes

* `drawview-*` becomes `pageboard-*`: directories, Gradle projects, artifacts
  (`io.github.valerioisufi:pageboard-canvas`), Android namespaces
  (`io.github.valerioisufi.pageboard.canvas`), Compose resources.
* `io.github.valerioisufi.drawview` becomes `io.github.valerioisufi.pageboard`, with the JNI
  symbols in the C++ and Rust code, the cinterop definitions and the Swift code of the iOS apps.
* The plugins (`pageboard.kmp.library`, ...), the Gradle properties (`pageboard.native.prebuilt`,
  ...) and the environment variables (`PAGEBOARD_PDFIUM_DIR`, ...).
* The Room schema directory moves with the database class; the JSON files inside do not change,
  and the schema version does not either.
* The protocol: the ALPN `pageboard/sync/1`, the invitations `pageboard://join`, the Bonjour service
  `_pageboard._tcp` (with the `Info.plist` of both iOS apps).
* The stored formats: archives `pageboard-archive` with the extension `.pageboard`, the shape type
  `pageboard.shape`, the shape of the sample `io.github.valerioisufi.pageboard.sample.shape`.
* The rest: the Keychain service, the key and the preferences of the Android Keystore, the deep
  links `pageboard://document/<id>` and their extra, the notification channel, the marker of the
  TeX files, the data directory and the application id of the sample, the Ogg vendor string.
* The docs: `README.md`, `docs/reference.md`, every `Module.md`, `CONTRIBUTING.md`, `SECURITY.md`,
  `CHANGELOG.md`, `CLAUDE.md`, `docs/plans/modularization.md` and the Dokka aggregate.
* The repository on GitHub: `valerioisufi/pageboard-kmp`. GitHub redirects the old address.

### What the update loses

* **Archives written before** are not read: they say `drawview-archive`.
* **Shapes stored before** are not read as shapes, until the conversion below.
* **The secure store** (the Keychain on iOS, the Keystore on Android): the keys of the device are
  made again, and the tokens and keys of services are given again in the settings. The device pairs
  again and is invited again to the shared documents. On desktop the secure store is a directory in
  the data directory and keeps its contents.
* **The shortcuts on the home screen of Android** of the old build stop opening their document.
* **The settings of the notification channel** of the sync go back to the defaults.
* **The sample** is a new application on Android, with an empty library, and opens `~/.pageboard`
  on desktop.

### The notes of the maintainer

The notes on the computer stay in `~/.pencilnotes`, and the new PencilNotes opens them in place.
Only the shapes need converting, once, before the first start of the new build: in the database,
`custom_elements.type` and the text of `operations.change` go from `"drawview.shape"` to
`"pageboard.shape"`. The conversion is a few SQL statements, kept out of the repository:

1. tried first on a scratch data directory, filled with notes and shapes by the old build;
2. then run by the maintainer on `~/.pencilnotes`, PencilNotes closed, after a copy of the whole
   directory.

The signatures of the operations of shared documents do not match the converted text any more;
the shares are made again anyway, with the new keys.

## Phases

One commit per phase. The checks of `CLAUDE.md` run at the end of each phase from 2 on.

| Phase | Content | Status |
|---|---|---|
| 1 | This plan | Done |
| 2 | The guard of the serial names, before anything moves | Done: every subclass already had its `@SerialName` |
| 3 | The modules and the build: directories, `settings.gradle.kts`, the plugins, the properties, the environment variables, the data directory, the workflows, the consumer checks | Done, compiled for the JVM; the full checks run after phase 6 |
| 4 | The packages, the JNI symbols, the cinterop and Swift code, the Room schema directory | Done, compiled for the JVM (with the `-D` options of CMake missed by phase 3) |
| 5 | The types, as in the table; `updateKotlinAbi` for the new names | Done, compiled for the JVM; the ABI dumps renamed with the code, checked after phase 6 |
| 6 | The protocol and the stored names: ALPN, invitations, Bonjour, archives, shape type, secure store, deep links, channel; `ProtocolFormatTest` re-recorded | To do |
| 7 | The docs, `CLAUDE.md` and the index of the plans | To do |
| 8 | Verification: the conversion tried on a scratch directory with notes of the old build, PencilNotes on desktop opening them; two devices on the new build syncing | To do |
| 9 | The repository renamed on GitHub, the remote updated | To do, after the merge |

## Decisions

Agreed with the maintainer:

* **The name Pageboard.**
* **Everything is renamed**, the protocol and the stored names included, each in a way that fits
  what it is.
* **No code for the names of before in the repository.** Only the notes of the maintainer matter,
  and they are converted once. This sets aside, for this change only, the rule of `CLAUDE.md` that
  every change of a stored format has a tested migration: nothing has been released with the old
  names.

Taken alone, all revertible:

* **The notes are converted in the database, not through an archive.** An archive does not hold the
  recordings of the lectures, the trash or the shares, and the database keeps them all.
* **`PROTOCOL_VERSION` stays 1.** The ALPN and the Bonjour service already keep the old builds
  apart, and the devices are updated together, as for the changes of `feat/internet-sync` and
  `feat/untrusted-members`. To agree with the maintainer.
* **The repository is `pageboard-kmp`**, like `drawview-kmp` today; `pageboard` alone also works.
* **The names of the model** in the table above.
* **No aliases for the old Gradle properties and plugins.** The old `drawview.dataDir` given alone
  stops the run, so that an old command never opens a default directory; this check goes away with
  the next release.

## Left for later

* Trademarks and domains of the name.
* The local folder of the repository (`D:\DrawView-KMP`): renaming it is up to the maintainer.
* The check of the old `drawview.dataDir`, removed once the commands of the maintainer use the new
  property.
