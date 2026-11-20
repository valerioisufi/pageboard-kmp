# Modularization and publishing

The modules of Pageboard are to be published on Maven and reused by other developers: each one
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
Variations). Nothing is released yet, but the documents already written with PencilNotes are kept:
every version of the Room schema from 16 on comes with its migration, never a destructive one (a
database without its migration is not opened); the protocol is changed under the same version
until the release (see the release checklist at the end).

## Starting point

* **Native code built as one block.** `pageboard-native` is one Android library building the
  Google Ink bridge, MicroTeX, the PDFium bridge (with `libpdfium.so`) and the audio codecs
  (Opus, AAC); `pageboard-core` depends on it on Android, so an application using the core ships
  all of them for three ABIs.
* **Engines reached as global singletons, past their ports.** `pageboard-core` declares
  `PdfTileAdapter` and `LatexTileAdapter`, but `pageboard-ui` calls `LatexEngine` and
  `PdfiumEngine` directly (`FormulaImages`, `TextBoxEditor`, `DocumentScreen`,
  `DocumentEditorViewModel`), and so do `DocumentLibrary` and `DocumentThumbnails` in
  `pageboard-explorer`: neither module compiles without `:pageboard-latex` and `:pageboard-pdf`.
  `LatexTileAdapter` and `LatexEngine.asTileAdapter()` are not used. `LatexEngine` keeps a global
  configuration (`setTextFonts`, `initialize`).
* **`DocumentScreen` reaches into the rendering pipeline**: it sets the text adapter of the renderer
  and clears its caches through `controller.tileManager.tileRenderer.textAdapter` when the
  formula engine becomes ready.
* **The model is Compose state.** `Page` and `Document` hold `SnapshotStateList` and
  `mutableStateOf`, their readers use `readSnapshot` for consistent reads from the tile workers,
  `DocumentSession.isReadOnly` is a `mutableStateOf`, `ImageElement` keeps its decoded
  `ImageBitmap`, and the model, the changes and the geometry use Compose `Rect`, `Offset` and
  `Color` (`TextFieldValue` in `RichTextEditing`). The changes themselves are close to headless:
  `DocumentChange`, `Operation` and `DocumentSnapshot` import nothing of Compose, `ElementData`
  only `Rect` and `Color`.
* **The element kinds are closed.** `PageElement` and `ElementData` are sealed; the code
  specific to each kind is spread over `PageModels` (one list per kind), the changes, the
  persistence (one save method and one table per kind), `TileRenderer`, `CollaboratorsOverlay`,
  `SelectionManager` (one list per kind in a selection), `SnapManager`, `EraserManager`,
  `CanvasGestureHandler` and `PdfExporter`. `docs/plans/export.md` chose the exhaustive `when` on
  purpose; that choice is replaced here.
* **`pageboard-ui` depends on `pageboard-data`** (`api`) because `DocumentEditorViewModel` and
  `EditorSession` live there; `EditorSession` exposes `OpenDocument`. The extensions use only
  `session.documentId` and the controller.
* **Storage.** `pageboard-data` holds the store port (`DocumentStore`, `OpenDocuments`), the
  in-memory store of the tests and the Room store in one module, so Room and SQLite come with
  it; it also imports Compose (`toArgb`). `DocumentStore` mixes documents, folders, preferences,
  the operations log and the sharing secrets; `DrawPersistence` mixes the document with the user
  preferences. `PageboardStore.default` is a global store.
* **`pageboard-sync` is not headless**: sync → data → core brings Compose UI, Foundation,
  Material3 and Room. The protocol, the encryption and the engine are one module.
* **`PageboardController`** (812 lines) holds the camera, the layout of the pages, the tools, the
  ink, the presence of the collaborators, the persistence queue and the text formatting; its
  eight managers receive the controller itself (`SelectionManager(this)`...), and all of them are
  public properties.
* **Headless code inside the UI modules**: about 40 of the 53 files of `pageboard-lecture` use no
  Compose (audio, transcription, Gemini, Ollama); in `pageboard-export` the exporters sit beside
  the dialog, and `DocumentExport` depends on `FileTarget` of `pageboard-ui`.
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
2. Headless modules (no Compose): `pageboard-platform`, `pageboard-model`, `pageboard-ink`,
   `pageboard-store`, `pageboard-store-room`, `pageboard-sync-protocol`, `pageboard-sync`,
   `pageboard-sync-lan`, `pageboard-sync-iroh`, `pageboard-ml`, `pageboard-ml-litert`,
   `pageboard-search`, `pageboard-handwriting`, `pageboard-agent`; `pageboard-lecture` keeps the Compose runtime only (no UI, no Skia), the
   observable state of its assistant (see Feature modules).
3. A native library ships with the module that uses it, and only with it.
4. No global mutable state in the APIs: the engines, the stores and the registries are instances
   passed in (Creator, Low Coupling); a module may keep a default instance for convenience only
   behind an explicit call.
5. The public API is declared (explicit API mode, strict), checked (ABI validation) and
   documented (KDoc, Dokka); the rest is `internal`.
6. A third-party element type uses the same API as the built-in ones.
7. The tests never use the data of the user: every `Test` task runs on `build/test-data/<task>`
   (`pageboard.test.data`, applied by `pageboard.kmp.library` and by the modules of the
   applications; see [test-data.md](test-data.md)).

## Target modules

| Module | Content | Depends on | Compose | Native |
|---|---|---|---|---|
| `pageboard-platform` | files, native library loading, device name and profile, secure store, Android context, time, locks | — | no | — |
| `pageboard-model` | document, pages, element envelope, element types registry, changes, session, clock, stamps, snapshots, attachments, geometry values, text parsing, stroke modelling port and its Kotlin modeller | platform | no | — |
| `pageboard-ink` | the Google Ink stroke modeller | model | no | Ink |
| `pageboard-canvas` (was `pageboard-core`) | controller, managers, canvas, gestures, tiles, camera, element renderers, image cache, ports of the engines | model, ink | yes | — |
| `pageboard-pdf` | PDFium: reading, rendering (implements the PDF port), writing | canvas | yes | PDFium |
| `pageboard-latex` | MicroTeX (implements the formula port) | canvas | yes | MicroTeX |
| `pageboard-ui` | editor screen, bars, dialogs, commands, theme, `EditorExtension` | canvas | yes | — |
| `pageboard-store` | store ports, `OpenDocuments`, key-value (and in-memory) store | model | no | — |
| `pageboard-store-room` | the Room store, the migrations of its schema | store | no | SQLite |
| `pageboard-html` | HTML turned into the Markdown of the texts, for what is pasted (see [html-paste.md](html-paste.md)) | model | no | Ksoup |
| `pageboard-store-testing` | the contract tests every store must pass | store | no | — |
| `pageboard-session` | opening a stored document in the editor, writing its changes (`DocumentEditorViewModel`, `EditorSessionScreen`) | ui, store | yes | — |
| `pageboard-explorer` | library of documents and folders, thumbnails | session | yes | — |
| `pageboard-sync-protocol` | frames, routing header, encryption, identity, invitations, transport ports | platform | no | — |
| `pageboard-sync` | sync engine, messages, presence, membership | sync-protocol, store | no | — |
| `pageboard-sync-lan` | LAN transport and discovery | sync | no | — |
| `pageboard-sync-iroh` | internet transport through iroh, direct or relayed (see [internet-sync.md](internet-sync.md)) | sync | no | iroh bridge (Rust) |
| `pageboard-sync-ui` | collaboration extension, dialogs | sync-lan, ui | yes | — |
| `pageboard-lecture` | recording, audio codecs, transcription, AI backends (Gemini, Ollama, the models of the device), the lectures of the documents and their sharing, the assistant (`LectureAssistant`, its state) | model, ml | runtime only | audio codecs, whisper.cpp (desktop) |
| `pageboard-lecture-ui` | lecture panel, settings, extension of the editor and of the library, the notebook of an editor (`ControllerNotebookSource`), the microphone permission, the file pickers | lecture, ui | yes | — |
| `pageboard-lecture-testing` | the test doubles of the assistant (microphone, notebook, secrets) | lecture | runtime only | — |
| `pageboard-ml` | local models port: catalog, downloads, `Embedder`, `Generator`, `LoadedModels` | platform | no | — |
| `pageboard-ml-litert` | LiteRT-LM runtime of the local models (desktop, Android) | ml | no | LiteRT-LM |
| `pageboard-handwriting` | the samples of the strokes, the ports of the readers, the lines of a block, the reading, InkML | model | no | — |
| `pageboard-handwriting-mlkit` | ML Kit Digital Ink as the reader of Android | handwriting | no | ML Kit (Android) |
| `pageboard-handwriting-windows` | the handwriting recognizers of Windows as the reader of the desktop | handwriting, platform | no | C++/WinRT (JNI, Windows) |
| `pageboard-handwriting-ui` | "Convert to text" on a selection of strokes | handwriting, ui | yes | — |
| `pageboard-search` | search index (FTS5 and vectors), sources, indexer, engine, `NotesAnswerer` | ml, store, handwriting | no | SQLite |
| `pageboard-search-ui` | `ContentSearch` of the library, PDF source, model settings, "Ask your notes" card | search, explorer, ui | yes | — |
| `pageboard-agent` | what an external model reads and proposes: tools, proposals, access, its messages (see [agent-tools.md](agent-tools.md)) | store | no | — |
| `pageboard-agent-ui` | the review of the proposals in the editor, the access of the clients | agent, session | yes | — |
| `pageboard-export` | export use case, PDF and image exporters | canvas, store, pdf | yes (graphics) | — |
| `pageboard-export-ui` | export dialog and extension | export, ui | yes | — |
| `pageboard-native-ink`, `-tex`, `-pdfium`, `-audio`, `-iroh` | Android shared libraries, one engine each | — | — | one each |

```
platform ← model ← ink ← canvas ← ui ← session ← explorer
              ↑            ↑   ↖ pdf, latex      ↖ sync-ui, lecture-ui, export-ui
              ├ store ← store-room                    (+ sync-lan, lecture, export)
              │   ↖ sync ← sync-lan, sync-iroh
platform ← sync-protocol ↗          lecture → model, store;  export → canvas, pdf
platform ← ml ← ml-litert;  ml, store ← search ← search-ui → explorer;  lecture-ui → search
```

`pageboard-native-*` are dependencies of the Android source sets only (ink → native-ink, latex →
native-tex, pdf → native-pdfium, lecture → native-audio, sync-iroh → native-iroh); the
applications never declare them.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The data of a kind of element: identifier, format version, serializer, migration | `ElementType<T>` (`pageboard-model`) | Polymorphism, Protected Variations |
| Reading the value of an element of a type | `ElementType.decode` (`pageboard-model`): the model and the stores never read the payloads | Information Expert, Protected Variations |
| The element types an application draws | `ElementRenderers` (`pageboard-canvas`), given by `PageboardEngines.elements` to the editors, the thumbnails and the export | Pure Fabrication, Indirection |
| Adding an element of a type, replacing its value | `CustomElementManager` of the controller (`AddElements`, `UpdateElementContent`) | Creator, Controller |
| An element whose type is not installed: kept, synced, undone, drawn as a placeholder | `CustomElementData` with an opaque payload, decoded when its type is registered | Protected Variations |
| What every element has (identifier, page, order, stamps, presence, rotated box) | the element envelope (`PageElement`, `RotatableItem`) | Information Expert |
| Placing, selecting, snapping, erasing, moving between pages any element | the editor through the envelope (`PlaceElements`, `BoxPlacement`, selection of elements) | Information Expert, Polymorphism |
| Drawing an element, its bounds, whether a point hits it | `ElementRenderer<T>` (`pageboard-canvas`), one per type, built-in ones included | Polymorphism, Information Expert |
| Writing an element in a PDF | `ElementExporter<T>` (`pageboard-export`); without one, the element is drawn by its renderer at 300 dpi | Polymorphism, Protected Variations |
| Modelling a stroke from its inputs | `StrokeModeller` port (`pageboard-model`), Kotlin modeller there, Google Ink in `pageboard-ink` | Protected Variations, Indirection |
| Consistent reads of a page from the tile workers | copy-on-write content of the pages (immutable lists replaced by each change) | Information Expert |
| Telling the UI what changed | `StateFlow`s of the model, observed by the Compose layer | Low Coupling, Indirection |
| Decoded images | `ImageCache` (`pageboard-canvas`), keyed by resource | Pure Fabrication |
| The optional engines of an editor (PDF, formulas) | `PageboardEngines` (`pageboard-canvas`), given to the controller, the thumbnails and the library; `LocalPageboardEngines` for the composables | Pure Fabrication, Indirection, Protected Variations |
| Rendering a formula, telling when the engine is ready | `FormulaRenderer` port (`pageboard-canvas`), implemented by `pageboard-latex` | Indirection, Protected Variations |
| Clearing the text caches when the formula engine becomes ready | the text adapter, subscribed to its `FormulaRenderer` | Information Expert |
| Storage of documents, folders, operations, sharing secrets, preferences | segregated ports in `pageboard-store` (`DocumentCatalog`, `OperationLog`, `SharingStore`, `PreferenceStore`; `DocumentStore` adds the content and the persistence), Room behind them in `pageboard-store-room` | Protected Variations, High Cohesion |
| The documents of a store being changed, one session each | `OpenDocuments`, created once by the application and given to the library, the editor, the synchronization and the export (it knows its store: Information Expert); an open document reads only the operation log | Creator, Low Coupling |
| Keeping the stored documents across the versions of the schema | the migrations of `pageboard-store-room` (`PAGEBOARD_DATABASE_MIGRATIONS`), each one tested on a database of its version | Protected Variations |
| Saving the changes of an open document | `DrawPersistence` with element saves for every type; preferences in a port of their own | High Cohesion, Polymorphism |
| What an extension of the editor receives | `EditorContext` (document identifier, controller) in `pageboard-ui` | Low Coupling |
| Opening a stored document in the editor and writing its changes | `DocumentEditorViewModel` (`pageboard-session`) | Controller, High Cohesion |
| Viewport, page layout, coordinate conversions | `Viewport` (`pageboard-canvas`) | High Cohesion |
| Presence of the collaborators in the editor | `CollaboratorPresence` (`pageboard-canvas`) | High Cohesion |
| What a manager needs of the editor | narrow interfaces (`EditorHost`), not the controller | Low Coupling |
| Frames that a relay can route and store without reading them | routing header and sealed operations (`pageboard-sync-protocol`) | Protected Variations, Indirection |
| Building, publishing and checking every module the same way | convention plugins (`build-logic`) | Pure Fabrication, High Cohesion |

### Open element types

* **Envelope and payload.** The model keeps what every element has (identifier, page, order,
  stamps, presence, a rotated box in page millimeters); a type adds its payload, an immutable
  serializable value. So a third-party element is moved, rotated, resized, snapped, selected,
  copied, undone, stored and synced without code of its type; its type draws it inside its box.
* **Types.**

  ```kotlin
  interface ElementType<T : Any> {
      /** The same on every device: a reverse domain name ("com.example.table"); "pageboard.*" is reserved. */
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
  in `ElementRenderers` (`PageboardEngines.elements`); the tool creating an element and its editor
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
  `pageboard-model` (colors as ARGB `Int`, as the texts already store them); the canvas converts
  at its border.
* **State.** The content of a page is one immutable value replaced by each change: a tile worker
  reads a page whole without a snapshot. The pages and the files of a document are copy-on-write
  lists, changed by the document only (`appendPage`, `movePage`, `addResource`...). The document
  tells its changes by its `revision` (`StateFlow`, advanced by every change the session applies),
  the session its read only: a composable showing the document calls `observeChanges()`. The
  decoded images move to `ImageCache`, the flags of the editor (`isDragging`,
  `isShownByCollaborator`) to `HiddenElements` of the canvas.
* **Strokes.** `StrokeData.toElement` models the stroke through the `StrokeModeller` of the
  document (`Document.strokeModeller`), not through a global engine: `KotlinStrokeModeller` in
  the model, `InkStrokeModeller` of `pageboard-ink` given by the stores (they depend on it). The
  meshes are local (the inputs are what is synced), so two modellers never break the
  convergence.
* **Order of the work.** `pageboard-model` is created empty; each package moves into it once it
  compiles without Compose, so that the compiler enforces the rule.

### Engines as instances

* A native engine is global to its process (one MicroTeX, one PDFium): `PdfiumEngine` stays the
  facade of its engine, `LatexEngine` is an instance the application creates over the one native
  engine (`docs/plans/engine-instances.md`); no other library reaches them. They give the
  implementations of the ports of the canvas (`PdfiumEngine.createTileAdapter`, a `LatexEngine`
  is a `FormulaRenderer`); the application builds a `PageboardEngines` with them and gives
  it to the editor and the library; `pageboard-ui`, `pageboard-explorer` and `pageboard-lecture` no
  longer depend on `pageboard-pdf` and `pageboard-latex`. Only `pageboard-export` uses PDFium
  directly (it writes the PDFs); it takes the vectors of the formulas from the formula renderer
  (`FormulaRenderer.drawing`, `docs/plans/vector-formulas.md`).
* The controller receives the engines: it creates the PDF adapter of its renderer (Creator) and
  draws the texts again when the formula renderer becomes ready (Information Expert), so that
  `DocumentScreen` no longer reaches into the renderer.
* The formula renderer is prepared with the fonts of the text of the formulas (`\text{}` in the
  font of the notes) by whoever shows the editor (`DocumentLibrary`), through the port.
* Without a formula renderer the formulas stay as their LaTeX source; without a PDF renderer a
  PDF page is a blank page with its frame.

### Storage and editor session

* `pageboard-store` keeps the ports and `OpenDocuments`: the library of documents and folders
  (`DocumentCatalog`), the operations log (`OperationLog`), the sharing secrets and the read only
  (`SharingStore`), the preferences as text by key (`PreferenceStore`), the content and the
  persistence of the open documents (`DocumentStore`, which extends the first three); the
  key-value store (in memory for the tests and the tools). `pageboard-store-room` implements them
  with Room; custom elements go into a table of their own (type, version, payload, box, order,
  stamps). The tests every store must pass are in `pageboard-store-testing`, for the stores of the
  applications too.
* No library reaches a global store: the library, the editor, the collaboration and the export
  receive the `OpenDocuments` of the application (`PageboardStore.documents` of
  `pageboard-store-room` is the one of the data directory, called by the applications only); the
  thumbnails are kept on disk only when the application says where
  (`DocumentThumbnails.defaultDirectory`).
* A new version of the Room schema comes with its migration (`Migration`), tested on a database of
  the version before (`MigrationTestHelper`) and checked on a copy of a real database: the
  documents are never dropped.
* Every contribution of an `EditorExtension` receives an `EditorContext`: the editor of the
  document shown (its controller) and the identifier of the document, the same instance as long
  as the document is shown; what the editor gives its extensions later is added there without
  changing their signature (Protected Variations). The extension points (`EditorExtension`,
  `EditorPanel`, `DocumentAction`, `LibraryAction`, `DocumentTag`) are in the package
  `ui.extension`.
* `EditorScreen` (`pageboard-ui`) is the editor of a controller with its extensions, whoever keeps
  the document. The editor session bound to a store (`EditorSession`, `DocumentEditorViewModel`,
  `EditorSessionScreen`, `PageboardSessions`) is in `pageboard-session`, with its package; the
  library depends on it, `pageboard-ui` depends on no store (nor on the lifecycle of the view
  models).

### Controller

* `Viewport` (`canvas.camera`: the size of the canvas, the layout of the pages, the camera, the
  conversions between screen, world and page millimeters, the zoom, the moves between pages) and
  `CollaboratorPresence` (the activity of the editor for the other devices, the collaborators
  drawn over the document, the edits refused) leave the controller (892 lines to 530).
* No `EditorHost`: the managers collaborate with each other (the selection with the text editor,
  the marks, the snapping, the pages) and with most of the editor (the document, the session,
  the viewport, the presence, the tiles, the history, the messages); an interface listing all of
  it would be the controller under another name, without lower coupling. They keep the
  controller (GRASP Controller of the editor, inside the canvas); what matters to the
  applications is their public surface: their constructors are `internal`, and so is every
  member the other modules do not use.
* The public API is declared: explicit API mode strict in every library (convention plugin),
  each declaration `public` or `internal` by its use outside of its module, the types reached by
  a public signature public with what of them is used (e.g. `ChangeContext`, opaque: its
  constructor and its values internal); the members called by name by the native code (JNI)
  keep their JVM names. The ABI of every library is recorded in its `api/` directory (KGP ABI
  validation): `check` fails when it changes, `updateKotlinAbi` records a change wanted.

### Sync and relay

* `pageboard-sync-protocol` holds what a relay needs, without the model or a store (it depends on
  `pageboard-platform` only): the clear first frame (`OpenFrame`: protocol version, document tag),
  the encryption of the frames (`SecureChannel`, `DocumentKey`), the sealed operations, the
  identities of the devices (`DeviceIdentity`), the invitations, the membership signed by the
  owner (`Role`, `Member`, `Membership`, `SignedMembership`: what a relay would check), the
  transports (`SyncChannel`, `SyncTransport`, the in-memory one of the tests). `pageboard-sync`
  keeps the engine, its messages and what a device keeps of its shared documents
  (`SharingState`).
* **Store and forward.** A relay should also hand the operations to a device that comes online
  later (editing at home, opening the notebook at the university): every operation travels as a
  sealed operation, its identity in clear (replica, sequence number) and its content encrypted, so
  that a relay can store the operations of a document and send a device the ones after its version
  vector without reading them. The cost: a relay sees how many devices write a document and when.
  `SealedOperation` (replica, sequence, content) and `OperationSeal` (AES-256-GCM with the key of
  the document, a random nonce each; the protocol version, the tag of the document and the
  identity authenticated with the content, so that a relay can neither read nor relabel them);
  the engine seals the operations as it sends them (not on the thread of the document) and checks
  that the identity inside is the one in clear. The format record of the protocol describes the
  operations as well (their content is part of it).
* The relay server (`pageboard-sync-relay`, a self-hostable Ktor server, and `RelayTransport`) is
  left for later; whether a public relay is offered for PencilNotes is a product decision.

### Feature modules

* **Export.** `pageboard-export` holds the exporters and the use case (`DocumentExport`): it writes
  the file at a path; `pageboard-export-ui` (the dialog, "Export" in the library and in the editor)
  delivers it where the user chose (`FileTarget.deliver`). The texts are drawn by the
  `TextTileAdapter` the dialog gives (the one of the editor).
* **Lecture.** `pageboard-lecture` holds everything but the screens: the recording and the codecs,
  the transcription, the models (Gemini, Ollama), the lectures of the documents and their sharing,
  and the assistant (`LectureAssistant`, the Controller of the feature). It no longer knows the
  editor: a recording sees the notebook through `NotebookSource` (`ControllerNotebookSource` of
  `pageboard-lecture-ui` reads an editor), the audio is exported to a path and delivered by
  whoever asked, the notifications of what arrives from the other devices are described by the
  UI (`describeArrival`, in the language of the application). `pageboard-lecture-ui` holds the
  panel, the settings, the extensions of the editor and of the library, the microphone
  permission, the file pickers, the strings and the reference images.
* The state of the assistant (`Lecture`, `LectureAssistant`, `LocalModels`, `AssistantSettings`,
  `RecordingPlayer`: about 80 values the panel shows) stays Compose snapshot state: the core
  depends on the Compose runtime, not on its UI or Skia (a tool on the JVM can still record and
  transcribe). Turning it into `StateFlow`s would rewrite every read of the panel, the risk of
  a regression of a feature in daily use for no consumer that needs it; it is left for when one
  does.
* The test doubles of the assistant (`FakeMicrophone`, `FakeNotebook`, `MemorySecrets`) are in
  `pageboard-lecture-testing`, for its tests, the ones of its UI and those of the applications.

### Publishing

* Coordinates `io.github.valerioisufi:<module>`, version `0.1.0-SNAPSHOT` until the first release
  (`gradle.properties`); the Vanniktech Maven Publish plugin (Central Portal, signing, sources,
  Dokka javadoc jars); POM with MIT license, developer, SCM.
* Convention plugins in `build-logic`: KMP library targets (Android, desktop JVM, iOS arm64 and
  simulator), Compose library, publishing, explicit API, ABI validation, Android native library.
* Native binaries: the desktop jars of a release hold the JNI libraries of the six desktop
  platforms (built by a CI matrix, `-Ppageboard.native.prebuilt=true`; publishing a release
  without them fails); ink, MicroTeX and the audio codecs are static libraries inside the iOS
  klibs; PDFium is a dynamic framework on iOS: the klib links `-framework PDFium` without a path
  and the XCFramework is published with the release, with the steps to embed it.
* Every artifact carries the licenses of the code it contains (PDFium and its dependencies,
  MicroTeX, Google Ink, Opus, vo-aacenc, whisper.cpp).
* **Consumer checks**: a separate Gradle build resolving the modules from `mavenLocal()`: a JVM
  program using `pageboard-model` alone (no Compose on its runtime classpath), a desktop
  application using `pageboard-ui` without `pageboard-pdf` and `pageboard-latex`, an Android
  application using `pageboard-canvas` (only `libink_bridge.so` in its APK).

## Plan

| Phase | Content | Status |
|---|---|---|
| 0. Build foundation | `build-logic` convention plugins applied to every library; publishing skeleton (`publishToMavenLocal` of every library); explicit API measurable (`-Ppageboard.explicitApi=warning`, strict module by module in phase 5); `currentOs` out of the libraries; no iOS framework in the libraries; `material3` out of the core | Done |
| 1. Native per engine | `pageboard-native` split into `pageboard-native-ink`, `-tex`, `-pdfium`, `-audio`; `pdfium.def` without absolute paths in the published klib; check of the desktop platforms when publishing a release | Done |
| 2. Engines as ports | ports `FormulaRenderer` and `PdfTileAdapter`, `PageboardEngines` (given to the controller, the library, the thumbnails; `LocalPageboardEngines`); `pageboard-ui`, `pageboard-explorer` and `pageboard-lecture` without `pageboard-pdf` / `pageboard-latex`; the text adapter clears its own caches; dead `LatexTileAdapter` removed | Done |
| 3a. Model values | `pageboard-platform` (files, native libraries, secure store, device, locks, time, SHA-256) and `pageboard-model` created; `Point`, `Rect` and ARGB colors in the model, converted at the border of the canvas (`ComposeGeometry`); moved: stamps, versions, element order, fractional index, `Matrix2D`, `AffineTransform`, measures, hit testing, PDF page fit, stroke points, tools, meshes, outlines, brushes, PDF text layout, rich text parsing, text sizes, backgrounds, alignment, attachments | Done |
| 3b. Headless state | `pageboard-ink` (Google Ink, its bridges) and the `StrokeModeller` of the document (Kotlin in the model, Ink in the stores); `HiddenElements` and `ImageCache` in the canvas instead of flags and pixels in the elements; the content of a page one immutable value, copy-on-write pages and files, `Document.revision` and the read only of the session as `StateFlow`s (`observeChanges` in the composables); document, changes and session moved; `pageboard-core` renamed `pageboard-canvas` | Done |
| 3c. Open element types | `ElementType` (CBOR payloads, versions, migration), `CustomElement`, `CustomElementData`, `UpdateElementContent`, unknown types kept as bytes; `ElementRenderer` / `ElementRenderers` (placeholders for the unknown types) in the tiles, the overlays, the selection, the snapping, the thumbnails, the image and PDF export; `CustomElementManager`; Room table `custom_elements`; the shapes of `sample` | Done |
| 4a. Persistence port | `DocumentPersistence`, `ChangePersister`, `PersistenceQueue` in the model; the preferences of the editor as text by key (`PreferenceStore`, `EditorPreferences.encode`) | Done |
| 4b. Stores | `pageboard-store` / `pageboard-store-room` / `pageboard-store-testing`; segregated ports; no global store in the libraries; the Room migrations from version 16 (no destructive fallback); `SelectionActivity` in the model (the synchronization without the canvas) | Done |
| 4c. Editor session | `EditorContext` given to the extensions; `EditorScreen` in `pageboard-ui`; `pageboard-session` (`EditorSession`, `DocumentEditorViewModel`, `EditorSessionScreen`, `PageboardSessions`); `pageboard-ui` without any store; the extension points in `ui.extension` | Done |
| 5. Controller and API | `Viewport`, `CollaboratorPresence`; no `EditorHost` (see Controller); explicit API strict in every library, the internals by their use; KDoc on every top-level public declaration; ABI dumps (`api/`, checked by `check`) | Done |
| 6. Sync and relay protocol | `pageboard-sync-protocol` (frames, encryption, identities, invitations, membership, transports; on `pageboard-platform` only); sealed operations with their identity in clear | Done |
| 7. Feature modules | `pageboard-lecture` / `pageboard-lecture-ui` / `pageboard-lecture-testing`; `pageboard-export` / `pageboard-export-ui` (the use case writing a file at a path, the dialog delivering it: no `FileTarget` in the export) | Done |
| 8. Release readiness | consumer checks (`consumer-checks/`: the model alone on the JVM, the editor without engines on the desktop, the canvas on Android); the license and the third-party notices in every jar and Android library; Dokka (the javadoc jars, `:dokkaGenerate` for all); CI workflows `check.yml` and `release.yml` (the native matrix and the publication: written, run once a remote exists); a `Module.md` page per module | Done |

Every phase ends with the tests of the JVM green, the desktop application of PencilNotes and of
the sample running, and the modules it touches published to `mavenLocal()`.

## Decisions taken by me (revertible)

* The names of the new modules (`pageboard-platform`, `pageboard-model`, `pageboard-ink`,
  `pageboard-canvas`, `pageboard-store`, `pageboard-store-room`, `pageboard-session`,
  `pageboard-sync-protocol`, `pageboard-lecture-ui`, `pageboard-export-ui`) and the rename of
  `pageboard-core`: "core" depending on Compose is what made the modules look independent while
  they were not.
* Packages follow the modules (`io.github.valerioisufi.pageboard.model...`): no package split
  across two artifacts.
* The engine modules implement the ports of the canvas (they depend on it): one module per
  engine, at the price of not being a PDF or TeX library usable without Pageboard.
* PDF export stays in `pageboard-export` with `pageboard-pdf`: the image-only export is not worth
  a module of its own.
* The coordinates and the SCM address (`https://github.com/valerioisufi/DrawView-KMP`, to fix
  when the remote exists).
* The routing header of the protocol shows who writes a document and when to a relay.

## Left for later

* The KDoc of the public members (about 670 without one, many self-explanatory: `id`, `undo()`,
  `zoomIn()`): Dokka lists them (`reportUndocumented`), to write before the first release.
* The iOS consumer check (an Xcode project using the frameworks of the libraries), and the
  licenses inside the klibs (a klib has no place for them: the applications show
  `THIRD_PARTY_LICENSES.md`, as PencilNotes does in its About & Licenses).
* The built-in kinds drawn through `ElementRenderer` too: they need what a box cannot express
  (meshes, PDF pages, laid-out texts), so they keep their code in `TileRenderer` and the exporter.
* `ElementExporter` (an element type written in a PDF as vectors): its elements are images at
  300 dpi meanwhile.

* The relay server and `RelayTransport`.
* Running the CI workflow (the repository has no remote yet) and the first publication on Maven
  Central (namespace verification, signing key).
* Release checklist: `PROTOCOL_VERSION` raised on every format change, the formats of
  `ElementType` payloads frozen (see `docs/plans/collaboration.md`); the Room migrations are written
  already (from version 16).
