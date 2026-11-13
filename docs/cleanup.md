# Cleanup

Two audits of the code (main at 58029db4, 2026-11-13) listed dead, redundant and badly placed code,
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
  `PlatformFiles` owns the data directory (`DeviceProfile.dataDirectory`, `modelsDirectory`). One
  `PT_TO_MM = 25.4f / 72f` in `drawview-model`, one `CIRCLE_KAPPA` beside the path code.
* **Creator.** The desktop window that opens a session hands it to its frame: the document window
  gives its `EditorSession` back (a callback or a `State`), and the frame reads the dirty state
  from it, instead of looking it up once in a registry.
* **Pure Fabrication.** A shared `jvmAndAndroid` source set (an intermediate `jvmCommon` group in
  `drawview.kmp.library`) holds the code of the JNI twins once; `restrictToOwner` moves to
  `drawview-platform`; one `SwitchRow` in `drawview-ui`; one `writeLine` helper beside the socket
  code of the agent.
* **Low Coupling.** `drawview-store-room` stops depending on `drawview-ink`: the stroke modeller
  is given by the application, as `KeyValueDocumentStore` already does. `drawview-sync-ui` takes
  `drawview-sync-lan` as `implementation`. The dependencies, plugins and source sets nobody uses go.
  `FileExplorerViewModel` stops importing Compose resources and UI tags (the UI maps them).
* **Polymorphism / Protected Variations.** `DrawViewMenuBar` reads the conventions of the window
  (`currentAdaptiveInfo().conventions`), not the global. `TeXRender` on the JVM gets the explicit
  `release()` it has on Android. The duplicated page backgrounds draw through the renderer of
  `drawview-canvas` (an internal function made reachable to `drawview-ui`, or a small public one).
* **Indirection.** `DocumentStore` users that need preferences take a `PreferenceStore` given by
  the application instead of downcasting `store as? PreferenceStore`.

## Phases

| Phase | Content | Status |
|-------|---------|--------|
| 1 | Bugs 1–7 of the starting point, with tests (agent counts, `NoChange`) | done |
| 2 | Dead code: private/`internal`/native/app symbols, orphan KDoc, no-ops and wasted allocations, placeholder tests | to do |
| 3 | Dead resources and build: drawables, strings, MicroTeX leftovers, catalog, unused dependencies/plugins/source sets, the pdfium linker flags limited to `drawview-pdf`, `windows-aarch64` aligned in CI | to do |
| 4 | Duplicates: `jvmAndAndroid` source set and its twins, constants, `restrictToOwner`, `codePointAt`, `SwitchRow`, `writeLine`, WAV round trip, `ExportSheetState` on `ExportContents`, page backgrounds | to do |
| 5 | Couplings: `store-room` without `ink`, `sync-ui` dependencies, `PreferenceStore` given, `dataDirectory` in `PlatformFiles`, explorer view model without UI types, menu bar conventions, `TeXRender.release` on the JVM | to do |
| 6 | Public API with no caller (part 1B of the audit), removed by the user's choice, with `updateKotlinAbi` | waits for the user |

Each phase is one commit on `feat/cleanup`, after the checks of `CLAUDE.md` (`jvmTest
checkKotlinAbi`, the Android/iOS compilations and the two apps, `verifyRoborazziJvm` when the UI
moves, `consumer-checks` when dependencies or published APIs change). A finding that turns out to
be used while removing it is kept and noted here.

## Decisions

Taken alone, revertible:

* `PT_TO_MM` becomes `25.4f / 72f` everywhere: texts sized with `0.3527f` may move by less than a
  hundredth of a millimetre per point. Text heights are laid out, not stored, so nothing stored
  changes.
* `DocumentTools` keeps the field `pdf_pages` of its output: the external models read it.
* The schemas `8.json`–`15.json` stay.
* The sample on iOS does not declare `drawview://`, though `ContentView.swift` handles it:
  PencilNotes declares it for its invitations, and two applications claiming the same scheme leave
  iOS choosing one of them.
* `DocumentChange.changesNothing` (true for the change undoing an `AddResource` only) is new public
  API of `drawview-model`, recorded in its dump.

## Left for later

Each needs a plan of its own:

* **The monoliths**: `SyncEngine` (connections, operations, resources and attachments, presence),
  `DocumentTools` (DTOs, schemas, handlers by domain), `LecturePanel`, `EditorToolbar`,
  `KeyValueDocumentStore`, `FileExplorerViewModel`, `LectureAssistant`.
* **The editor ports**: a narrow facade in place of the raw `DrawViewController` in
  `EditorContext`, the manager constructors made internal, and the shape of `EditorExtension`
  before the first release (it is a class of lambdas: each new hook breaks the ABI).
* **The lecture assistant on `drawview-ml`** (phase 4 of `docs/local-models.md`): one
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
