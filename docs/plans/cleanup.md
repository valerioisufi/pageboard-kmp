# Cleanup

Two audits of the code (main at c9ce9cad, 2026-11-13) listed dead, redundant and badly placed code,
and the places where the GRASP principles are not kept. This plan checks each finding against the
code, drops the false ones, fixes the rest in small phases, and leaves the large redesigns to plans
of their own.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

The findings were checked by hand on the riskiest points and by a search of every symbol named
(`rg -w` over the `.kt`/`.kts` files, outside `build/`).

**Confirmed bugs**

1. **The dirty dot of a secondary desktop window never lights up** (`pencilnotes` and `sample`
   `main.kt`): `remember(documentId) { DrawViewSessions.find(documentId) }` runs before the window
   opens its session, and `DrawViewSessions` is a plain set, not state, so the window keeps `null`.
   In `sample` the call is also outside `DrawViewWindowDecor`.
2. **The agent tools count and read removed elements** (`DocumentTools`): the page list counts
   `page.texts/strokes/images/pdfs.size` with the removed ones; `pageContent` takes its strokes
   (count, list, ink area) from `page.elements()` unfiltered, and the text of the PDF pages from
   `page.pdfs` unfiltered, so a removed PDF still gives its text to the model.
3. **`AgentActivity` knows `NoChange` by its `toString()`**, the name of an internal object.
4. **A percent shown twice**: Compose Resources replaces `%1$d` but does not turn `%%` into `%`
   (`replaceWithArgs`, checked in the built `.cvr`), so `search_model_downloading` and
   `lecture_local_downloading` show "42%%". The audit had it the other way round:
   `lecture_text_notes_done`, with a single `%`, is right.
5. **The night theme of PencilNotes on Android** inherits `Theme.Material.Light.NoActionBar`.
6. **`SearchEngine`**: `if (chunk.kind == TITLE) chunk.text else chunk.text`.
7. **The title of a sample window** reads `document.name` without observing it: a rename does
   not reach the window.

**Confirmed dead or useless code** (no caller in the repository): the lists of the audit, part 1A
(private, `internal`, native and app code), 1C (15 drawables, 22 strings, the MicroTeX leftovers:
`native/tex/res` is byte for byte the copy in `drawview-latex`), part 4 (no-ops, ignored
parameters, placeholder tests, orphan KDoc) and part 5 (catalog entries, dependencies, plugins and
source sets not used).

**Confirmed duplication**: the `androidMain`/`jvmMain` twins (identical `InkNative`,
`StrokeEngineDelegate`, `TeXException`; near-identical `LiteRtEngine`, `AudioCodecs`,
`PdfiumNative`, `PlatformFiles`), `restrictToOwner`, `codePointAt`, `CIRCLE_KAPPA` (3 values),
`PT_TO_MM` (3 values), `SwitchRow`, `SocketChannel.writeLine`, the WAV header added by
`SpeechSource` and stripped by `WhisperTranscriber`, the page backgrounds drawn by hand in
`PageGridOverlay` and `PageTemplateConfigurator`, `ExportSheetState` repeating `ExportContents`.

**Found while fixing**: the release workflow publishes without `-Pdrawview.pdfium.desktop=all`,
so the `drawview-pdf` jar on Maven Central holds the PDFium of the runner only (Linux), while the
native workflow checks all of them.

**Also while fixing**: the screenshot `agent_marks` of `drawview-agent-ui` was older than the shapes
tool in the bar (main at c9ce9cad fails it): recorded again. `TranscriptionQueueTest` timed out
once under the load of the whole build, and passes alone.

**Findings dropped** (false, or right as they are):

* `StrokeEntity.hashCode` on fewer fields than `equals` is correct: equal entities still have equal
  hashes.
* A resource with an empty `hash` is not checked on arrival on purpose: `DrawResource.hash`
  defaults to `""` for the resources stored before the hashes; refusing them would leave their
  images missing on the other devices.
* The names of the database (`drawview.db` / `database.db`) and of the resource folders differ by
  platform, but they hold the user's notes: renaming them is a migration of stored data for no
  gain.
* The schemas `8.json`–`15.json` are the history of the database; they stay.
* `AgentEngines.handwriting` not passed by PencilNotes, `LicensesTask` copying the whole notice,
  `CommandRoot` ignoring a nested host, the Compose runtime in `drawview-lecture`: chosen and
  written down.
* `SecureStore` in clear on the JVM, the tie-breakers of `LanSync` / `IrohSync`: real, but the
  first is a stored format and the second decides which connection two devices keep (both must
  agree, so it is protocol behaviour). Left for later, see below.

## Requirements

1. Fix the confirmed bugs, each with a test where one can be written.
2. Remove the dead code, resources, strings and dependencies; the build, the tests, the ABI dumps
   and the screenshots stay green, and no stored format or protocol changes.
3. Merge the duplicates into one owner.
4. Remove the couplings that need no redesign.
5. The public API with no caller is removed only with the user's yes, list by list, recorded with
   `updateKotlinAbi`.
6. Nothing stored by the user changes: no Room schema, no document, preference or lecture format,
   nothing that travels.

## Design

* **Information Expert.** `DocumentTools` asks the page for what is shown: one helper
  `DrawPage.shownElements()` (or the existing filter, applied once in `pageContent`) is the only
  source of counts, strokes and PDFs. `DocumentChange` says itself whether it changes nothing (a
  public `isNoChange`, or `NoChange` made public), instead of `AgentActivity` reading a name.
  The directories of the data are found as the parents of their paths (`kotlinx.io.files.Path`),
  not by cutting the text at the last `/`, which fails with the separators of Windows
  (`dataDirectory`, `WhisperTranscriber.modelsDirectory`). The PDF circles take one exact
  `CIRCLE_KAPPA`.
* **Creator.** The desktop window that opens a session hands it to its frame: the document window
  gives its `EditorSession` back (a callback or a `State`), and the frame reads the dirty state
  from it, instead of looking it up once in a registry.
* **Pure Fabrication.** Code identical on every target goes to `commonMain` (`TeXException`,
  `Rect` of MicroTeX); code identical on the JVM and Android goes to a `jvmAndAndroid` group of the
  module (`InkNative`, the stroke engine delegate of `drawview-ink`); one `writeLine` beside the
  socket code of the agent; the rule of the formats in `ExportScope`, read by `ExportContents`
  and `ExportSheetState`.
* **Low Coupling.** The dependencies, plugins and source sets nobody uses go.

## Phases

| Phase | Content | Status |
|-------|---------|--------|
| 1 | Bugs 1–7 of the starting point, with tests (agent counts, `NoChange`) | done |
| 2 | Dead code: private/`internal`/native/app symbols, orphan KDoc, no-ops and wasted allocations, placeholder tests | done |
| 3 | Dead resources and build: drawables, strings, MicroTeX leftovers, the unused table of the search index, catalog, unused dependencies/plugins/source sets, PDFium of every desktop in the released jar | done |
| 4 | Duplicates: common and `jvmAndAndroid` twins, constants, `writeLine`, the formats of an export scope | done |
| 5 | The directories of the data found by their paths | done |
| 6 | Public API judged on its merit (the user asked for it whatever its callers): the leftovers removed, the rest kept | done |

Each phase is one commit on `feat/cleanup`, after the checks of `CLAUDE.md` (`jvmTest
checkKotlinAbi`, the Android/iOS compilations and the two apps, `verifyRoborazziJvm` when the UI
moves, `consumer-checks` when dependencies or published APIs change). A finding that turns out to
be used while removing it is kept and noted here.

## Decisions

Taken alone, revertible:

* The three values of `PT_TO_MM` stay. `TextSizes.PT_TO_MM` (`0.3527f`) sizes every text written so
  far: `25.4f / 72f` would make them a little larger, and a line could wrap at another word. The
  fill of a text (`TextBackground`, exact) moves visibly with the other one (its test and
  `text-backgrounds` screenshot see it). The page measures (`Dimension`) keep their `1 / 2.83465f`.
* `DocumentTools` keeps the field `pdf_pages` of its output: the external models read it.
* The schemas `8.json`–`15.json` stay.
* The sample on iOS does not declare `drawview://`, though `ContentView.swift` handles it:
  PencilNotes declares it for its invitations, and two applications claiming the same scheme leave
  iOS choosing one of them.
* The accessors of the strings and drawables removed from `drawview-ui` and `drawview-explorer`
  are public (`Res`, used by the other modules): they leave the ABI dumps, recorded with
  `updateKotlinAbi`. `DrawableResourcesTest` goes with the last vector drawable it checked.
* `ExportScope.isSingleDocument` and `ExportScope.formats` are new public API of `drawview-export`;
  `io.nano.tex.Rect` of Android gets the `toString` of the other platforms.
* `DocumentChange.changesNothing` (true for the change undoing an `AddResource` only) is new public
  API of `drawview-model`, recorded in its dump.

## Findings kept while fixing

Checked again when removing them, they stay:

* `Font.styledFace` (`io.nano.tex`, Android), `AssistantSettings.localModel`,
  `SharingState.closed` and `InvitationGrant.createdAt`: public API (phase 6). The sharing state is
  also stored: its fields are not removed without a migration.
* `PageOnScreen.index`: read by the tests of the export sheet, which build it.
* The empty `onDispose` of `DrawScreen`: the text adapter is set when the effect is applied, not
  later in a coroutine as a `LaunchedEffect` would.
* `PdfSearchSource.version` opening an adapter: it asks whether the PDF engine works now (its
  native library may be missing), not whether it is given.
* `windows-aarch64`: the native scripts can build it by hand; packaging it needs a runner of its
  own in CI, a choice of the user.
* The PDFium linker flags of every library's iOS tests: every library whose tests link
  `drawview-pdf`, even through another module, needs them.
* `api(project(":drawview-sync-lan"))` in `drawview-sync-ui`: `Collaboration` takes a
  `LanDiscovery` in its public constructor.
* `drawview-store-room` depending on `drawview-ink`: `DrawViewStore.default` loads the strokes of
  the user with `InkStrokeModeller`, and only the applications could choose it instead; that comes
  with the global objects, below.
* `documents.store as? PreferenceStore`: a default the callers can replace, not a downcast they
  must live with; making every `DocumentStore` a `PreferenceStore` would bind the third-party stores.
* `platformConventions` in `DrawViewMenuBar`: the menu bar is composed outside `AdaptiveRoot`,
  where the conventions of the window are not known yet.
* `FileExplorerViewModel` with Compose resources and tags: `drawview-explorer` is a Compose module,
  and the tags are given by the applications as they are shown.
* `TeXRender.release` on the JVM: nothing would call it (the one of Android has no caller either,
  phase 6).
* The two page backgrounds drawn by hand (`PageGridOverlay`, `PageTemplateConfigurator`): drawing
  them with the renderer of `drawview-canvas` needs a public drawing function and changes how the
  thumbnails look (later, below). Their `PageBackground.Solid` branches are there for the `when`
  to be exhaustive.
* The WAV made by `SpeechSource` and read back by `WhisperTranscriber`: it is the format of the
  public `Transcriber`, which any engine can take; 44 bytes a piece.
* The two switch rows of `ShareSheet`: one has an icon and a hint, the other neither.
* `cryptography.provider.optimal` in `drawview-sync`: no code names it, but it registers the
  provider of the cryptography at run time.
* `compose.materialIconsExtended` in `drawview-ui` and `drawview-lecture-ui`: they draw `Undo`,
  `Redo`, `Code`, `Mic`.

## The public API (phase 6)

The user asked to judge each candidate on whether it makes sense as API, called or not.

**Removed** (recorded with `updateKotlinAbi`):

* `DrawDocument.name`: an alias of `title` kept from before; its callers read `title`.
* `StrokeMesh.id`: a random id that no copy of the mesh kept (`withPoints`, `transformed`), and made
  `equals` an identity anyway while `hashCode` hashed every vertex. A mesh is now equal to itself
  only, as before, without the cost.
* `io.nano.tex.Alignment` (JVM and iOS only): constants of MicroTeX nothing passes.
* `AssistantSettings.localModel`: a getter named for every local model, giving the one of the final
  review only; `task(LectureTask.FINAL_REVIEW).local` says it.
* `SearchIndex.allChunks()`, `RoomDocumentStore.database` (now internal: the store is the API, not
  its database).
* `FileExplorerViewModel.isNarrowed`, `recentFiles` and the single-item doubles `deleteFile`,
  `moveFile`, `deleteSelected`, `moveSelected` of `deleteFiles` and `moveFiles`.
* Six public icons no screen draws (`File`, `Flag`, `History`, `Move`, `Outline`, `Settings`).
* `PdfiumNative.nInit`/`nDestroy` and their JNI functions: the bridge initializes PDFium itself.
* `Font.styledFace` (private, never read).

**Kept**, though nothing calls them today:

* The units and shapes of a general type: `Dimension.A3`/`A5`, `Matrix2D.preTranslate`/`preScale`/
  `mapRadius`, `PdfSize.aspectRatio`, the design `seed` color.
* The counterparts and lifecycles: `DesktopWindowManager.openLibrary` (of `closeLibrary`),
  `LaTeX.release`, `LaTeX.setDebug`, the setters of `TeXRender`, `Graphics2D.lockColor`/
  `unlockColor` (a formula drawn in one color). The JNI functions of MicroTeX on Android are
  registered by a table: a Kotlin method removed would fail it.
* The extension points: `ElementRenderers.get`, `KeyValueStorage`, `InMemorySharedDocuments`,
  `RelayConfig.NONE`, `ReferenceWindow` (the window sizes of the screenshot tests of every module).
* What the tests use to test (`TileCache.contains`, `SyncCodec.decodeOrThrow`,
  `InkStrokeEngine.addPoint`…), and the C functions of the native bridges.
* `SharingState.closed` and `InvitationGrant.createdAt`: stored.

## Left for later

Each needs a plan of its own:

* **One drawing of the page backgrounds** for the editor, the page grid and the template picker.
* **The duplicates across modules** (`codePointAt` in `drawview-pdf` and `drawview-export`, the
  `SwitchRow` of four UI modules): sharing them needs public API in a lower module.

* **The monoliths**: `SyncEngine` (connections, operations, resources and attachments, presence),
  `DocumentTools` (DTOs, schemas, handlers by domain), `LecturePanel`, `EditorToolbar`,
  `KeyValueDocumentStore`, `FileExplorerViewModel`, `LectureAssistant`.
* **The editor ports**: a narrow facade in place of the raw `DrawViewController` in
  `EditorContext`, the manager constructors made internal, and the shape of `EditorExtension`
  before the first release (it is a class of lambdas: each new hook breaks the ABI).
* **The lecture assistant on `drawview-ml`** (phase 4 of `docs/plans/local-models.md`): one
  `ModelBackend` without `when (provider)` nor `answerWithGemini`, no second model catalog.
* **`drawview-export` without `drawview-latex` and `drawview-pdf`**: vector formulas through
  `FormulaRenderer`.
* **`SyncNetwork` made symmetric** (`LanSync` / `IrohSync` stop and tie-breaker): protocol
  behaviour, to agree with the user.
* **`SecureStore` encrypted on the JVM**: a stored format, with its migration.
* **The resource downloads streamed to disk** like the attachments, instead of `Download.bytes`.
* **The secondary PencilNotes window** without the lecture assistant and the collaboration.
* **The missing translations** of `drawview-agent-ui`, `drawview-search-ui`, `drawview-lecture-ui`.
* **The global objects** (`DrawViewStore`, `DrawViewSessions`, `LatexEngine`, `LectureRecordings`,
  `RecordingPlayer.current`, `ImageCache.Shared`, `DrawViewAndroid`) turned into instances passed
  in, as the rules of the libraries ask.
