# Relative paths for stored document resources

Imported PDFs and images kept inside `PlatformFiles.resourcesDirectory()` must be persisted as paths relative to that directory rather than absolute filesystem paths. On iOS, each app update or reinstall moves the app's Data Container to a new UUID, turning stored absolute paths into dangling references: existing PDFs stop rendering while newly imported PDFs work. Storing relative paths at the persistence boundary fixes the root cause across iOS, Android, and desktop.

The responsibilities are assigned following the GRASP principles (Information Expert, Protected Variations, Low Coupling, High Cohesion, Pure Fabrication).

## Starting point

* `newResourcePath(prefix, extension)` in `pageboard-platform/src/commonMain/kotlin/io/github/valerioisufi/pageboard/platform/PlatformFiles.kt` builds an absolute path inside `PlatformFiles.resourcesDirectory()`:
  * iOS: `<NSDocumentDirectory>/pageboard_resources`
  * Android: `<filesDir>/pageboard_resources`
  * desktop JVM: `<dataDir>/resources`
* `DocumentRepository.saveResource` (`pageboard-store-room`) writes `resource.path` verbatim into `resources.uri`. `DocumentRepository.mapResource`, `DocumentRepository.fileWithContent`, and `FileRepository.deleteDocument` read and delete `entity.uri` verbatim. `KeyValueDocumentStore` and `KeyValueFormat` (`pageboard-store`) do the same with `StoredResource.uri` and `resourceKey(uri)`.
* On iOS, `NSDocumentDirectory` includes a per-install/per-update Data Container UUID (`/var/mobile/Containers/Data/Application/<UUID>/Documents`). While `DatabaseBuilder.ios.kt` resolves `pageboard.db` dynamically from `NSDocumentDirectory` at every launch, `resources.uri` still holds `<OLD-UUID>`. After an app update:
  * `TileRenderer` passes the stale path to `CachingPdfTileAdapter` (`FPDF_LoadDocument` fails silently and caches the path in `failed`) and `ImageCache` (`decodeImageFile` returns `null`), leaving PDF pages blank and imported images missing.
  * Text markup (`TextMarkupManager`), PDF search indexing, PDF export, sync file serving (`PlatformResourceFiles`), and file cleanup in `FileRepository.deleteDocument` all fail on the stale path.
  * Newly imported PDFs and images work because `MediaManager.storedFile` sees `PlatformFiles.exists(oldPath) == false` and allocates a new path under the current container UUID.
* On desktop, absolute paths in `resources.uri` also break if the user moves their data directory (e.g. `~/.pencilnotes`).
* All other persisted data (`pageboard.db`, `lectures`, `thumbnails`, `search/index.db`, `models`) already derives paths dynamically from `NSDocumentDirectory` or `dataDirectory()`.

## Requirements

1. Resources stored inside `PlatformFiles.resourcesDirectory()` are persisted in `resources.uri` (Room) and `StoredResource.uri` (KeyValue) as paths relative to `resourcesDirectory()` (e.g. `pdf_<id>.pdf`), using `/` separators.
2. In-memory `DocumentResource.path` remains an absolute, directly openable path: no changes to `pageboard-model`, `pageboard-canvas`, `pageboard-pdf`, `pageboard-sync`, `pageboard-export`, `pageboard-search`, or `pageboard-agent`, and no sync protocol change (`PROTOCOL_VERSION` stays unchanged).
3. External paths outside `PlatformFiles.resourcesDirectory()` (e.g. tests using `/tmp/...` or uncopied agent references), empty URIs (unsynced resources), and `COLOR` resources are preserved untouched.
4. Shared-file reference counting on document deletion (`ResourceDao.countByUri` in Room; `pathsOfOtherDocumentsLocked` in `KeyValueDocumentStore`) remains exact across all existing and newly created documents.
5. Existing Room databases (desktop, Android, iOS) are migrated via a tested `Migration27To28` (`PageboardDatabase` version 27 to 28) so old absolute paths inside `resourcesDirectory()` or a relocated `pageboard_resources/` folder become uniform relative paths — preventing `countByUri` mismatches when a new document deduplicates against a pre-existing resource on desktop or Android, and recovering relocated iOS resources as a side effect.
6. `pageboard-platform` ABI dumps (`api/jvm`, `api/android`, `api/pageboard-platform.klib.api`) are updated for the new public helper and verified by `checkKotlinAbi`.

## Design

| Responsibility | Where | GRASP |
|---|---|---|
| Convert between absolute resource paths and stored relative paths, handling `/` and `\` separators and Windows drive letters | `ResourcePaths` (`pageboard-platform`) | Information Expert, Pure Fabrication, Protected Variations |
| Translate `DocumentResource.path` ↔ `ResourceEntity.uri` at persistence boundaries; keep `countByUri` on stored URI and resolve before `PlatformFiles.delete` | `DocumentRepository` & `FileRepository` (`pageboard-store-room`) | Information Expert, Low Coupling, High Cohesion |
| Same store-boundary translation for `StoredResource.uri` and `resourceKey`, with read fallback to legacy absolute keys in `restoreResourceFile` | `KeyValueDocumentStore` & `KeyValueFormat` (`pageboard-store`) | Protected Variations, High Cohesion |
| Normalize existing `resources.uri` rows (non-`COLOR`, non-empty) from v27 to v28 using `ResourcePaths.toStored` plus closed historical `pageboard_resources/` suffix stripping for relocated mobile containers | `Migration27To28` (`pageboard-store-room`) | Information Expert, High Cohesion |

### `ResourcePaths` in `pageboard-platform`

Added in `pageboard-platform/src/commonMain/kotlin/io/github/valerioisufi/pageboard/platform/ResourcePaths.kt`:

* `public object ResourcePaths`
* `public fun toStored(path: String, resourcesDirectory: String = PlatformFiles.resourcesDirectory()): String`:
  * returns `""` when `path` is empty;
  * normalizes `\` to `/` and strips trailing slashes from `resourcesDirectory`;
  * if `path` is inside `resourcesDirectory` (with a non-empty relative tail containing no `..` traversal segment), returns the relative tail with `/` separators;
  * otherwise returns `path` unchanged.
* `public fun resolve(stored: String, resourcesDirectory: String = PlatformFiles.resourcesDirectory()): String`:
  * returns `stored` unchanged if empty or absolute (starts with `/`, `\`, or Windows drive `[A-Za-z]:[/\\]`);
  * otherwise joins `resourcesDirectory.trimEnd('/', '\\') + "/" + stored.trimStart('/', '\\')`.
* `newResourcePath` in `PlatformFiles.kt` also uses `trimEnd('/', '\\')` for consistency on Windows.

### Store boundary in `pageboard-store-room`

* `DocumentRepository.saveResource`: `val uri = if (resource.type == DocumentResource.ResourceType.COLOR) resource.path else ResourcePaths.toStored(resource.path)`.
* `DocumentRepository.mapResource`: `val path = if (type == DocumentResource.ResourceType.COLOR) entity.uri else ResourcePaths.resolve(entity.uri)`.
* `DocumentRepository.fileWithContent`: `resourceDao.findUriByHash(hash)?.let(ResourcePaths::resolve)`.
* `DocumentRepository.pageFingerprint`: keeps reading `resource?.uri`, which is now stable across container UUID changes and moved data directories.
* `FileRepository.deleteDocument`: filters out `COLOR` and empty URIs, checks `resourceDao.countByUri(uri) == 0` against the stored `uri`, and calls `PlatformFiles.delete(ResourcePaths.resolve(uri))`.

### Store boundary in `pageboard-store`

* `Document.toStored()` (`KeyValueFormat.kt`): stores `ResourcePaths.toStored(it.path)` for non-`COLOR` resources.
* `KeyValueDocumentStore.saveResource`: stores Base64 bytes under `resourceKey(storedUri)` while reading bytes from `resource.path`.
* `KeyValueDocumentStore.readDocumentLocked`: resolves `stored.uri` via `ResourcePaths.resolve` for non-`COLOR`, passing both `stored.uri` and the resolved `path` to `restoreResourceFile` (checking `resourceKey(storedUri)` first, then falling back to `resourceKey(path)` for legacy KV data).
* `KeyValueDocumentStore.fileWithContent`: resolves the stored `uri` from `StoredDocument` via `ResourcePaths.resolve`.
* `KeyValueDocumentStore.pathsOfOtherDocumentsLocked` and `deleteDocumentLocked`: normalize URIs with `ResourcePaths.toStored` for uniform deduplication and resolve via `ResourcePaths.resolve` before `PlatformFiles.delete`.

### Room `Migration27To28`

* `PageboardDatabase` version bumped `27` → `28`; exported schema `28.json` generated by KSP; `Migration27To28` registered in `PAGEBOARD_DATABASE_MIGRATIONS`.
* Selects `id, uri` from `resources WHERE LOWER(type) != 'color' AND uri != ''`.
* For each row:
  1. tries `ResourcePaths.toStored(uri)` against the active `resourcesDirectory()`;
  2. if still absolute and the `/`-normalized path contains `"/pageboard_resources/"`, extracts the non-empty relative tail after the last `"/pageboard_resources/"` (provided it has no `..` segment);
  3. updates `uri` when changed.
* External paths, `COLOR` rows, and empty URIs remain untouched.

## Phases

One branch (`fix/relative-resource-paths`), one commit per phase.

| Phase | Content | Status |
|---|---|---|
| 1 | `pageboard-platform`: add `ResourcePaths` (`toStored`, `resolve`), harden `newResourcePath` separator trimming, add `ResourcePathsTest` in `commonTest` (Unix/Windows/nested/external/empty/already-relative/traversal edge cases), update `pageboard-platform` ABI dumps (`./gradlew :pageboard-platform:updateKotlinAbi`) | Pending |
| 2 | `pageboard-store-room`: wire `ResourcePaths` in `DocumentRepository` and `FileRepository`, bump `PageboardDatabase` to v28 with `Migration27To28` and exported `28.json`, add tests in `PageboardDatabaseTest` and `PageboardDatabaseMigrationTest` | Pending |
| 3 | `pageboard-store`: align `KeyValueDocumentStore` and `KeyValueFormat` with `ResourcePaths` and add unit tests in `KeyValueDocumentStoreTest` / `SharedResourceFilesTest` | Pending |
| 4 | Verification: run `./gradlew jvmTest checkKotlinAbi`, `./gradlew compileAndroidMain compileKotlinIosSimulatorArm64 :pencilnotes-android:assembleDebug :sample-android:assembleDebug`, `./consumer-checks/check.sh`, and update plan statuses | Pending |

## Decisions

Agreed with the maintainer:

* Store relative paths at the store boundary while keeping `DocumentResource.path` absolute in memory.
* Place the path conversion helper (`ResourcePaths`) in `pageboard-platform` (updating its ABI dumps).

Taken alone (revertible):

* Name the helper object `ResourcePaths` with `toStored` and `resolve` (defaulting `resourcesDirectory = PlatformFiles.resourcesDirectory()` so callers stay concise and tests can pass arbitrary directories without mutating global state).
* Branch name: `fix/relative-resource-paths`.
* Include `Migration27To28` even though the maintainer does not need to recover current iPhone test data, because uniform `resources.uri` values are required for `ResourceDao.countByUri` safety on existing desktop and Android databases.
* In `Migration27To28`, only strip a historical directory marker for `"pageboard_resources"` (not generic `"resources"` outside the active `resourcesDirectory()`) to avoid false positives on external user paths containing a directory named `resources`.

## Left for later

* Any future preference storing a file inside `dataDirectory()` on iOS (for example if `AssistantSettings.whisperModel` is ever enabled on iOS) should follow the same relative-path pattern.
