# Modularization and publishing

The modules of DrawView are to be published on Maven and reused by other developers: each one
must compile, publish and run with only the dependencies it declares, so that an application
pays only for what it adds (the canvas without PDFium or MicroTeX, a server without Compose, the
storage without Room). Two decisions shape the work (2026-10-10):

* Other developers can add their own **element types** (a table, a shape, a recording...), with
  the same API as the built-in strokes, texts, images, PDF pages and marks.
* The **document model is headless**: no Compose (neither `compose-ui` nor `compose-runtime`) in
  the model, the changes and the session, so that tools, tests and later a server use it without
  Skia. The **relay server** is left for later: it forwards frames encrypted end to end, so it
  never reads the model; the protocol is prepared for it now (see [Sync and relay](#sync-and-relay)).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations). Nothing is released yet: no compatibility is kept for the stored documents, the
database schemas or the protocol (see the release checklist at the end).

## Starting point

* **Native code built as one block.** `drawview-native` is one Android library building the
  Google Ink bridge, MicroTeX, the PDFium bridge (with `libpdfium.so`) and the audio codecs
  (Opus, AAC); `drawview-core` depends on it on Android, so an application using the core ships
  all of them for three ABIs.
* **Engines reached as global singletons, past their ports.** `drawview-core` declares
  `PdfTileAdapter` and `LatexTileAdapter`, but `drawview-ui` calls `LatexEngine` and
  `PdfiumEngine` directly (`FormulaImages`, `TextBoxEditor`, `DrawScreen`,
  `DocumentEditorViewModel`), and so do `DocumentLibrary` and `DocumentThumbnails` in
  `drawview-explorer`: neither module compiles without `:drawview-latex` and `:drawview-pdf`.
  `LatexTileAdapter` and `LatexEngine.asTileAdapter()` are not used. `LatexEngine` keeps a global
  configuration (`setTextFonts`, `initialize`).
* **`DrawScreen` reaches into the rendering pipeline**: it sets the text adapter of the renderer
  and clears its caches through `controller.tileManager.tileRenderer.textAdapter` when the
  formula engine becomes ready.
* **The model is Compose state.** `DrawPage` and `DrawDocument` hold `SnapshotStateList` and
  `mutableStateOf`, their readers use `readSnapshot` for consistent reads from the tile workers,
  `DocumentSession.isReadOnly` is a `mutableStateOf`, `DrawImage` keeps its decoded
  `ImageBitmap`, and the model, the changes and the geometry use Compose `Rect`, `Offset` and
  `Color` (`TextFieldValue` in `RichTextEditing`). The changes themselves are close to headless:
  `DocumentChange`, `Operation` and `DocumentSnapshot` import nothing of Compose, `ElementData`
  only `Rect` and `Color`.
* **The element kinds are closed.** `DrawElement` and `ElementData` are sealed; the code
  specific to each kind is spread over `PageModels` (one list per kind), the changes, the
  persistence (one save method and one table per kind), `TileRenderer`, `CollaboratorsOverlay`,
  `SelectionManager` (one list per kind in a selection), `SnapManager`, `EraserManager`,
  `CanvasGestureHandler` and `PdfExporter`. `docs/export.md` chose the exhaustive `when` on
  purpose; that choice is replaced here.
* **`drawview-ui` depends on `drawview-data`** (`api`) because `DocumentEditorViewModel` and
  `EditorSession` live there; `EditorSession` exposes `OpenDocument`. The extensions use only
  `session.documentId` and the controller.
* **Storage.** `drawview-data` holds the store port (`DocumentStore`, `OpenDocuments`), the
  in-memory store of the tests and the Room store in one module, so Room and SQLite come with
  it; it also imports Compose (`toArgb`). `DocumentStore` mixes documents, folders, preferences,
  the operations log and the sharing secrets; `DrawPersistence` mixes the document with the user
  preferences. `DrawViewStore.default` is a global store.
* **`drawview-sync` is not headless**: sync → data → core brings Compose UI, Foundation,
  Material3 and Room. The protocol, the encryption and the engine are one module.
* **`DrawViewController`** (812 lines) holds the camera, the layout of the pages, the tools, the
  ink, the presence of the collaborators, the persistence queue and the text formatting; its
  eight managers receive the controller itself (`SelectionManager(this)`...), and all of them are
  public properties.
* **Headless code inside the UI modules**: about 40 of the 53 files of `drawview-lecture` use no
  Compose (audio, transcription, Gemini, Ollama); in `drawview-export` the exporters sit beside
  the dialog, and `DocumentExport` depends on `FileTarget` of `drawview-ui`.
* **Nothing is publishable yet**: no publishing plugin, coordinates or POM; no convention plugins
  (every module repeats its targets); `compose.desktop.currentOs` in the `jvmMain` dependencies of
  seven library modules (it pins the Skia runtime of the build machine in the POM); every library
  declares an iOS framework; the desktop jars hold the JNI libraries of the build machine only;
  Gradle writes the absolute path of `PDFium.framework` into `pdfium.def`; no explicit API mode
  (about 170 public top-level declarations in the core alone), no ABI check, no API docs;
  `compose.material3` is an unused `api` dependency of the core.

## Rules

1. A module compiles, publishes and runs with the dependencies it declares; an optional engine
   (PDF, formulas, native ink) is plugged in by the application, and its absence degrades the
   feature (a formula shown as its source), never the build.
2. Headless modules (no Compose): `drawview-platform`, `drawview-model`, `drawview-ink`,
   `drawview-store`, `drawview-store-room`, `drawview-sync-protocol`, `drawview-sync`,
   `drawview-sync-lan`, `drawview-lecture`.
3. A native library ships with the module that uses it, and only with it.
4. No global mutable state in the APIs: the engines, the stores and the registries are instances
   passed in (Creator, Low Coupling); a module may keep a default instance for convenience only
   behind an explicit call.
5. The public API is declared (explicit API mode, strict), checked (ABI validation) and
   documented (KDoc, Dokka); the rest is `internal`.
6. A third-party element type uses the same API as the built-in ones.

## Target modules

| Module | Content | Depends on | Compose | Native |
|---|---|---|---|---|
| `drawview-platform` | files, native library loading, device name and profile, secure store, Android context, time, locks | — | no | — |
| `drawview-model` | document, pages, element envelope, element types registry, changes, session, clock, stamps, snapshots, attachments, geometry values, text parsing, stroke modelling port and its Kotlin modeller | platform | no | — |
| `drawview-ink` | the Google Ink stroke modeller | model | no | Ink |
| `drawview-canvas` (was `drawview-core`) | controller, managers, canvas, gestures, tiles, camera, element renderers, image cache, ports of the engines | model, ink | yes | — |
| `drawview-pdf` | PDFium: reading, rendering (implements the PDF port), writing | canvas | yes | PDFium |
| `drawview-latex` | MicroTeX (implements the formula port) | canvas | yes | MicroTeX |
| `drawview-ui` | editor screen, bars, dialogs, commands, theme, `EditorExtension` | canvas | yes | — |
| `drawview-store` | store ports, `OpenDocuments`, in-memory store | model | no | — |
| `drawview-store-room` | the Room store | store | no | SQLite |
| `drawview-session` | opening a stored document in the editor, writing its changes (`DocumentEditorViewModel`, `EditorSessionScreen`) | ui, store | yes | — |
| `drawview-explorer` | library of documents and folders, thumbnails | session | yes | — |
| `drawview-sync-protocol` | frames, routing header, encryption, identity, invitations, transport ports | platform | no | — |
| `drawview-sync` | sync engine, messages, presence, membership | sync-protocol, store | no | — |
| `drawview-sync-lan` | LAN transport and discovery | sync | no | — |
| `drawview-sync-ui` | collaboration extension, dialogs | sync-lan, ui | yes | — |
| `drawview-lecture` | recording, audio codecs, transcription, AI backends, lecture records | model, store | no | audio codecs, whisper.cpp (desktop) |
| `drawview-lecture-ui` | lecture panel and extension | lecture, ui | yes | — |
| `drawview-export` | export use case, PDF and image exporters, element exporters | canvas, pdf | yes | — |
| `drawview-export-ui` | export dialog and extension | export, ui | yes | — |
| `drawview-native-ink`, `-tex`, `-pdfium`, `-audio` | Android shared libraries, one engine each | — | — | one each |

```
platform ← model ← ink ← canvas ← ui ← session ← explorer
              ↑            ↑   ↖ pdf, latex      ↖ sync-ui, lecture-ui, export-ui
              ├ store ← store-room                    (+ sync-lan, lecture, export)
              │   ↖ sync ← sync-lan
platform ← sync-protocol ↗          lecture → model, store;  export → canvas, pdf
```

`drawview-native-*` are dependencies of the Android source sets only (ink → native-ink, latex →
native-tex, pdf → native-pdfium, lecture → native-audio); the applications never declare them.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The data of a kind of element: identifier, format version, serializer, migration | `ElementType<T>` (`drawview-model`) | Polymorphism, Protected Variations |
| Reading the value of an element of a type | `ElementType.decode` (`drawview-model`): the model and the stores never read the payloads | Information Expert, Protected Variations |
| The element types an application draws | `ElementRenderers` (`drawview-canvas`), given by `DrawViewEngines.elements` to the editors, the thumbnails and the export | Pure Fabrication, Indirection |
| Adding an element of a type, replacing its value | `CustomElementManager` of the controller (`AddElements`, `UpdateElementContent`) | Creator, Controller |
| An element whose type is not installed: kept, synced, undone, drawn as a placeholder | `CustomElementData` with an opaque payload, decoded when its type is registered | Protected Variations |
| What every element has (identifier, page, order, stamps, presence, rotated box) | the element envelope (`DrawElement`, `RotatableItem`) | Information Expert |
| Placing, selecting, snapping, erasing, moving between pages any element | the editor through the envelope (`PlaceElements`, `BoxPlacement`, selection of elements) | Information Expert, Polymorphism |
| Drawing an element, its bounds, whether a point hits it | `ElementRenderer<T>` (`drawview-canvas`), one per type, built-in ones included | Polymorphism, Information Expert |
| Writing an element in a PDF | `ElementExporter<T>` (`drawview-export`); without one, the element is drawn by its renderer at 300 dpi | Polymorphism, Protected Variations |
| Modelling a stroke from its inputs | `StrokeModeller` port (`drawview-model`), Kotlin modeller there, Google Ink in `drawview-ink` | Protected Variations, Indirection |
| Consistent reads of a page from the tile workers | copy-on-write content of the pages (immutable lists replaced by each change) | Information Expert |
| Telling the UI what changed | `StateFlow`s of the model, observed by the Compose layer | Low Coupling, Indirection |
| Decoded images | `ImageCache` (`drawview-canvas`), keyed by resource | Pure Fabrication |
| The optional engines of an editor (PDF, formulas) | `DrawViewEngines` (`drawview-canvas`), given to the controller, the thumbnails and the library; `LocalDrawViewEngines` for the composables | Pure Fabrication, Indirection, Protected Variations |
| Rendering a formula, telling when the engine is ready | `FormulaRenderer` port (`drawview-canvas`), implemented by `drawview-latex` | Indirection, Protected Variations |
| Clearing the text caches when the formula engine becomes ready | the text adapter, subscribed to its `FormulaRenderer` | Information Expert |
| Storage of documents, folders, operations, sharing secrets, preferences | segregated ports in `drawview-store`, Room behind them in `drawview-store-room` | Protected Variations, High Cohesion |
| Saving the changes of an open document | `DrawPersistence` with element saves for every type; preferences in a port of their own | High Cohesion, Polymorphism |
| What an extension of the editor receives | `EditorContext` (document identifier, controller) in `drawview-ui` | Low Coupling |
| Opening a stored document in the editor and writing its changes | `DocumentEditorViewModel` (`drawview-session`) | Controller, High Cohesion |
| Viewport, page layout, coordinate conversions | `Viewport` (`drawview-canvas`) | High Cohesion |
| Presence of the collaborators in the editor | `CollaboratorPresence` (`drawview-canvas`) | High Cohesion |
| What a manager needs of the editor | narrow interfaces (`EditorHost`), not the controller | Low Coupling |
| Frames that a relay can route and store without reading them | routing header and sealed operations (`drawview-sync-protocol`) | Protected Variations, Indirection |
| Building, publishing and checking every module the same way | convention plugins (`build-logic`) | Pure Fabrication, High Cohesion |

### Open element types

* **Envelope and payload.** The model keeps what every element has (identifier, page, order,
  stamps, presence, a rotated box in page millimeters); a type adds its payload, an immutable
  serializable value. So a third-party element is moved, rotated, resized, snapped, selected,
  copied, undone, stored and synced without code of its type; its type draws it inside its box.
* **Types.**

  ```kotlin
  interface ElementType<T : Any> {
      /** The same on every device: a reverse domain name ("com.example.table"); "drawview.*" is reserved. */
      val id: String
      /** Version of the payload format; older payloads go through [migrate]. */
      val version: Int get() = 1
      val serializer: KSerializer<T>
      fun migrate(payload: ByteArray, fromVersion: Int): T? = null
  }
  ```

  The payload travels as CBOR bytes (`CustomElementData.payload`) in the changes, the snapshots,
  the stores and the sync: what does not know the type carries the bytes as they are. Its content
  changes with `UpdateElementContent` (the most recent wins, as the style of a mark).
* **Unknown types.** A device without the type keeps the element (stored, synced, undone, moved
  with a page), draws a placeholder in its box and does not let it be edited: it never destroys
  the elements of a device that has the type.
* **Behaviours by layer.** `ElementRenderer` (draw in the box, hit test) in the canvas, registered
  in `ElementRenderers` (`DrawViewEngines.elements`); the tool creating an element and its editor
  through `EditorExtension` and `CustomElementManager`; in the PDF export, the element drawn by its
  renderer at 300 dpi. The model needs no registry: it never reads a payload.
* **What the editor does with them.** Like the images: selected by a long press and by the lasso,
  moved, rotated and resized by their box, snapped and snapped to, copied, pasted, removed, drawn
  over the tiles while selected or moved by another device; the eraser leaves them (as it leaves
  the images).
* **What the built-in kinds keep.** Their data stays typed in the changes (`StrokeData`,
  `TextData`...): the texts merge contents made apart (`UpdateTexts`, content versions), the
  strokes are modelled again from their inputs, the marks follow the text of a PDF page; an
  opaque payload cannot express these rules. `ElementData` stays sealed, with
  `CustomElementData` for every other type.

### Headless model

* **Values.** `Rect`, `Offset` and `Color` of Compose leave the model for value types of
  `drawview-model` (colors as ARGB `Int`, as the texts already store them); the canvas converts
  at its border.
* **State.** The content of a page is one immutable value replaced by each change: a tile worker
  reads a page whole without a snapshot. The pages and the files of a document are copy-on-write
  lists, changed by the document only (`appendPage`, `movePage`, `addResource`...). The document
  tells its changes by its `revision` (`StateFlow`, advanced by every change the session applies),
  the session its read only: a composable showing the document calls `observeChanges()`. The
  decoded images move to `ImageCache`, the flags of the editor (`isDragging`,
  `isShownByCollaborator`) to `HiddenElements` of the canvas.
* **Strokes.** `StrokeData.toElement` models the stroke through the `StrokeModeller` of the
  document (`DrawDocument.strokeModeller`), not through a global engine: `KotlinStrokeModeller` in
  the model, `InkStrokeModeller` of `drawview-ink` given by the stores (they depend on it). The
  meshes are local (the inputs are what is synced), so two modellers never break the
  convergence.
* **Order of the work.** `drawview-model` is created empty; each package moves into it once it
  compiles without Compose, so that the compiler enforces the rule.

### Engines as instances

* A native engine is global to its process (one MicroTeX, one PDFium): `PdfiumEngine` and
  `LatexEngine` stay the facades of their engine, but no other library reaches them. They give the
  implementations of the ports of the canvas (`PdfiumEngine.createTileAdapter`,
  `LatexEngine.formulaRenderer`); the application builds a `DrawViewEngines` with them and gives
  it to the editor and the library; `drawview-ui`, `drawview-explorer` and `drawview-lecture` no
  longer depend on `drawview-pdf` and `drawview-latex`. Only `drawview-export` uses them directly
  (it writes PDFs and the formulas as vectors).
* The controller receives the engines: it creates the PDF adapter of its renderer (Creator) and
  draws the texts again when the formula renderer becomes ready (Information Expert), so that
  `DrawScreen` no longer reaches into the renderer.
* The formula renderer is prepared with the fonts of the text of the formulas (`\text{}` in the
  font of the notes) by whoever shows the editor (`DocumentLibrary`), through the port.
* Without a formula renderer the formulas stay as their LaTeX source; without a PDF renderer a
  PDF page is a blank page with its frame.

### Storage and editor session

* `drawview-store` keeps the ports and `OpenDocuments`: documents and folders, the operations log,
  the sharing secrets, the preferences, the persistence of the open documents; the in-memory store
  (tests, tools). `drawview-store-room` implements them with Room; custom elements go into a table
  of their own (type, version, payload, box, order, stamps).
* `EditorExtension` receives an `EditorContext` (document identifier, controller). The editor
  session bound to a store (`DocumentEditorViewModel`, `EditorSessionScreen`, `DrawViewSessions`)
  moves to `drawview-session`; `drawview-ui` no longer depends on any store.

### Controller

* The managers receive narrow interfaces (`EditorHost`: conversions between screen and page,
  submitting a change, redrawing, the selection) and become `internal`; `Viewport` (camera,
  layout of the pages, conversions) and `CollaboratorPresence` leave the controller.
* The public API of the controller is what the UI and the extensions use: tools, history,
  selection commands, viewport, messages.

### Sync and relay

* `drawview-sync-protocol` holds what a relay needs: the frames, the clear first frame (protocol
  version, document tag), the encryption, the identities, the invitations and the transport
  ports, without the model or a store. `drawview-sync` keeps the engine and the messages.
* **Store and forward.** A relay should also hand the operations to a device that comes online
  later (editing at home, opening the notebook at the university): every operation travels as a
  sealed operation, its identity in clear (replica, sequence number) and its content encrypted, so
  that a relay can store the operations of a document and send a device the ones after its version
  vector without reading them. The cost: a relay sees how many devices write a document and when.
* The relay server (`drawview-sync-relay`, a self-hostable Ktor server, and `RelayTransport`) is
  left for later; whether a public relay is offered for PencilNotes is a product decision.

### Publishing

* Coordinates `io.github.valerioisufi:<module>`, version `0.1.0-SNAPSHOT` until the first release
  (`gradle.properties`); the Vanniktech Maven Publish plugin (Central Portal, signing, sources,
  Dokka javadoc jars); POM with MIT license, developer, SCM.
* Convention plugins in `build-logic`: KMP library targets (Android, desktop JVM, iOS arm64 and
  simulator), Compose library, publishing, explicit API, ABI validation, Android native library.
* Native binaries: the desktop jars of a release hold the JNI libraries of the six desktop
  platforms (built by a CI matrix, `-Pdrawview.native.prebuilt=true`; publishing a release
  without them fails); ink, MicroTeX and the audio codecs are static libraries inside the iOS
  klibs; PDFium is a dynamic framework on iOS: the klib links `-framework PDFium` without a path
  and the XCFramework is published with the release, with the steps to embed it.
* Every artifact carries the licenses of the code it contains (PDFium and its dependencies,
  MicroTeX, Google Ink, Opus, vo-aacenc, whisper.cpp).
* **Consumer checks**: a separate Gradle build resolving the modules from `mavenLocal()`: a JVM
  program using `drawview-model` alone (no Compose on its runtime classpath), a desktop
  application using `drawview-ui` without `drawview-pdf` and `drawview-latex`, an Android
  application using `drawview-canvas` (only `libink_bridge.so` in its APK).

## Plan

| Phase | Content | Status |
|---|---|---|
| 0. Build foundation | `build-logic` convention plugins applied to every library; publishing skeleton (`publishToMavenLocal` of every library); explicit API measurable (`-Pdrawview.explicitApi=warning`, strict module by module in phase 5); `currentOs` out of the libraries; no iOS framework in the libraries; `material3` out of the core | Done |
| 1. Native per engine | `drawview-native` split into `drawview-native-ink`, `-tex`, `-pdfium`, `-audio`; `pdfium.def` without absolute paths in the published klib; check of the desktop platforms when publishing a release | Done |
| 2. Engines as ports | ports `FormulaRenderer` and `PdfTileAdapter`, `DrawViewEngines` (given to the controller, the library, the thumbnails; `LocalDrawViewEngines`); `drawview-ui`, `drawview-explorer` and `drawview-lecture` without `drawview-pdf` / `drawview-latex`; the text adapter clears its own caches; dead `LatexTileAdapter` removed | Done |
| 3a. Model values | `drawview-platform` (files, native libraries, secure store, device, locks, time, SHA-256) and `drawview-model` created; `Point`, `Rect` and ARGB colors in the model, converted at the border of the canvas (`ComposeGeometry`); moved: stamps, versions, element order, fractional index, `Matrix2D`, `AffineTransform`, measures, hit testing, PDF page fit, stroke points, tools, meshes, outlines, brushes, PDF text layout, rich text parsing, text sizes, backgrounds, alignment, attachments | Done |
| 3b. Headless state | `drawview-ink` (Google Ink, its bridges) and the `StrokeModeller` of the document (Kotlin in the model, Ink in the stores); `HiddenElements` and `ImageCache` in the canvas instead of flags and pixels in the elements; the content of a page one immutable value, copy-on-write pages and files, `DrawDocument.revision` and the read only of the session as `StateFlow`s (`observeChanges` in the composables); document, changes and session moved; `drawview-core` renamed `drawview-canvas` | Done |
| 3c. Open element types | `ElementType` (CBOR payloads, versions, migration), `DrawCustomElement`, `CustomElementData`, `UpdateElementContent`, unknown types kept as bytes; `ElementRenderer` / `ElementRenderers` (placeholders for the unknown types) in the tiles, the overlays, the selection, the snapping, the thumbnails, the image and PDF export; `CustomElementManager`; Room table `custom_elements`; the shapes of `sample` | Done |
| 4. Storage and session | `drawview-store` / `drawview-store-room`; segregated ports; element persistence for every type; `EditorContext`; `drawview-session`; no global store | |
| 5. Controller and API | `Viewport`, `CollaboratorPresence`, `EditorHost`, internal managers; explicit API strict and KDoc on every module; ABI dumps | |
| 6. Sync and relay protocol | `drawview-sync-protocol`; sealed operations with their identity in clear | |
| 7. Feature modules | `drawview-lecture` / `drawview-lecture-ui`, `drawview-export` / `drawview-export-ui` (`ElementExporter`, export writing to a sink instead of `FileTarget`) | |
| 8. Release readiness | consumer checks; licenses per artifact; Dokka; CI workflow for the native matrix and the publication (written, run once a remote exists); README of the modules | |

Every phase ends with the tests of the JVM green, the desktop application of PencilNotes and of
the sample running, and the modules it touches published to `mavenLocal()`.

## Decisions taken by me (revertible)

* The names of the new modules (`drawview-platform`, `drawview-model`, `drawview-ink`,
  `drawview-canvas`, `drawview-store`, `drawview-store-room`, `drawview-session`,
  `drawview-sync-protocol`, `drawview-lecture-ui`, `drawview-export-ui`) and the rename of
  `drawview-core`: "core" depending on Compose is what made the modules look independent while
  they were not.
* Packages follow the modules (`io.github.valerioisufi.drawview.model...`): no package split
  across two artifacts.
* The engine modules implement the ports of the canvas (they depend on it): one module per
  engine, at the price of not being a PDF or TeX library usable without DrawView.
* PDF export stays in `drawview-export` with `drawview-pdf`: the image-only export is not worth
  a module of its own.
* The coordinates and the SCM address (`https://github.com/valerioisufi/DrawView-KMP`, to fix
  when the remote exists).
* The routing header of the protocol shows who writes a document and when to a relay.

## Left for later

* The built-in kinds drawn through `ElementRenderer` too: they need what a box cannot express
  (meshes, PDF pages, laid-out texts), so they keep their code in `TileRenderer` and the exporter.
* `ElementExporter` (an element type written in a PDF as vectors): its elements are images at
  300 dpi meanwhile.

* The relay server and `RelayTransport`.
* Running the CI workflow (the repository has no remote yet) and the first publication on Maven
  Central (namespace verification, signing key).
* Release checklist: real Room migrations, `PROTOCOL_VERSION` raised on every format change, the
  formats of `ElementType` payloads frozen (see `docs/collaboration.md` and the memory of the
  project: nothing is compatible before the first release).
