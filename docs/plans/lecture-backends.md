# The lecture models behind one backend per provider

Phase 11 of `docs/plans/architecture.md` (design D, Polymorphism, and the rest of design C for
`LectureAssistant`): the requests of the lecture assistant reach their model through one
`ModelBackend` per provider, chosen once by the settings; the search asks its online answers through
the same backends; the models of the device are `drawview-ml`'s; and the state of `LectureAssistant`
is split along the seams this draws (step 2 of `docs/plans/lecture-ui-split.md`). Phase 4 of
`docs/plans/local-models.md` stays the reference for the models on the device, and the live notes stay on
Gemini (`docs/plans/local-notes.md`).

It starts from `main` at 8bbe86dc (2026-11-14).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* **`ModelBackend` exists, but is passed over.** `model/ModelPrompt.kt` of `drawview-lecture` has
  `ModelPrompt`, `ModelAnswer`, the interface `ModelBackend` and `ModelBackends(gemini, local)`
  with `of(provider)`; `GeminiBackend` and `OllamaBackend` implement it. Only the review
  (`Lecture.runFinalReview`) and the text notes (`LectureAssistant.textNotes`) call `of(provider)`.
  The others call the two backends by name, each in its own `when (task.provider)`:
  * `LectureRecorder.request` (the live notes and the catch-up): Gemini with the cache of the start
    of the lecture (`GeminiPromptCache`), Ollama kept loaded for ten minutes;
  * `HandwritingReader.read`: the same, without the cache;
  * `LectureAssistant.composeContext` (the course context): the same.
* **What differs by provider is spread as `if (provider == ...)`** next to those calls:
  * whether the key of Gemini is missing (`LectureRecorder`, `HandwritingReader` callers,
    `FinalReviewer`, `Lecture.runFinalReview`, `composeContext`, `estimateTextNotes`, `textNotes`);
  * the pages sent as images (1 or 2 a request, 8 or 16 for the review);
  * the blocks of handwriting a request (3 or 6) and an update (3 or 6);
  * whether the start of the lecture goes first, cached (`LectureRecorder.startOf`);
  * which errors are repeated (`GeminiException.isRetryable`, `OllamaException` 5xx) and which
    stop (`Lecture.problemOf`);
  * which models are freed at the end of the lecture (`unloadLiveModels`, Ollama only).
* **The search asks Gemini directly.** `LectureAssistant.answerWithGemini` builds its own
  `GenerateContentRequest` on `GeminiClient`, with the Gemini model of the live notes; it is public,
  used by `onlineAnswerBackend` of `drawview-lecture-ui` with `canAnswerWithGemini`.
* **The models of the device are measured, not used.** `drawview-ml` (`ModelCatalog`,
  `InstalledModels`, `ModelDownloads`, `LoadedModels`, the port `ModelRuntime`) and
  `drawview-ml-litert` serve the search of PencilNotes; `drawview-lecture` reaches them only in its
  bench (jvmTest), where `BenchModels.kt` turns a `ModelPrompt` into a `GenerationRequest`
  (`toRequest`) and takes the JSON out of an answer (`jsonOf`) "as `DeviceBackend` will".
* **`LectureAssistant` is about 1070 lines** holding three kinds of state: the lectures of the
  documents shown (lists, the one shown, course contexts, sharing, documents with lectures); the
  work it schedules (the queue of transcriptions, the conversions of the imports, the reviews, the
  lectures followed live, the offers of the lectures received); and the models (the backends, the
  speech recognition, the readers of the handwriting, the requests of the course context, of the
  text notes and of the search).
* **Stored formats it meets**: `assistant-settings.json` (`TaskModel(provider, gemini, local)` per
  task, no version) and `LectureRecord.finalReviewProvider` in `lecture.json`, which travels.

## Requirements

1. One place decides, for a task, which backend and which model answer it: the settings, read once
   per request. No `when (provider)` outside the backends.
2. What differs by provider (the key, the images, the batches, the cache of the start, the errors
   repeated, the models freed) is a member of its backend.
3. The online answers of the search go through the Gemini backend; `answerWithGemini` goes.
4. The models of the device are reached through `drawview-ml` (`LoadedModels`, the catalog of
   `ModelCatalog`), never through a second list of models in `drawview-lecture`.
5. `LectureAssistant` keeps its role and public API (but for the answers of the search), its state
   split into internal classes by responsibility, the behaviour unchanged (the tests and the
   screenshots as they are).
6. **No stored format changes in this plan without the user**: `assistant-settings.json`, the
   lecture records and what travels stay as they are; `PROTOCOL_VERSION` stays 1. Choosing the
   models of the device needs a new setting, so it waits for the user (see "Left to the user").
7. No paid request: the tests answer with mock engines.

## Design

### The backends (Polymorphism, Protected Variations)

| Responsibility | Class | GRASP |
|---|---|---|
| Answer a prompt with a model, and what that provider needs: whether it can answer now (the key), the images and batches it takes, whether it caches the start of a lecture, which of its errors are repeated and which stop, the models it frees at the end of a lecture | `ModelBackend` (interface), `GeminiBackend`, `OllamaBackend` | Polymorphism, Information Expert |
| The limits of a provider (pages as images, for the review too, handwriting blocks a request and an update) | `ModelLimits` | Information Expert |
| The backend and the model of a task, chosen once from the settings | `ModelBackends.forTask(settings, task): TaskBackend` | Information Expert, Indirection |
| The cache of the start of a lecture, opaque to the callers (`GeminiPromptCache` behind it) | `PromptCache` (interface), `ModelBackend.newPromptCache()` | Protected Variations |
| How long a local model stays in memory after an answer | `ModelUse.LIVE` / `ONCE` | Information Expert |
| The answers of the search (text, no schema), by the Gemini backend | `LectureAssistant.answerOnline`, `canAnswerOnline` | Polymorphism, Low Coupling |
| A prompt answered by a model of the device, the JSON taken out of its answer | `DeviceBackend : ModelBackend` over `LoadedModels` | Polymorphism, Indirection |

```kotlin
internal interface ModelBackend {
    val provider: ModelProvider
    val limits: ModelLimits
    /** Why it cannot answer now (the key of Gemini missing), `null` when it can. */
    fun unavailable(): LectureProblem?
    fun newPromptCache(): PromptCache?
    suspend fun generate(prompt: ModelPrompt, model: String, use: ModelUse = ModelUse.ONCE, cache: PromptCache? = null, onProgress: (Int) -> Unit = {}): ModelAnswer
    /** Whether [error] (one of this backend) may pass if the request is repeated. */
    fun isRetryable(error: Throwable): Boolean
    /** The problem shown for [error], `null` when it is not one of this backend. */
    fun problemOf(error: Throwable): LectureProblem?
    /** Frees the [models] kept in memory for a lecture. */
    suspend fun release(models: Set<String>)
}

internal class TaskBackend(val backend: ModelBackend, val model: String) // plus generate(prompt, use, cache, onProgress)
```

`ModelPrompt.schema` becomes optional: without it the answer is text (the search).
`LectureProblem` of a failed request comes from the backend that failed; `Lecture.problemOf` asks
every backend, then falls back to `Failed`.

### The state of the assistant (High Cohesion, Pure Fabrication, Controller)

| Responsibility | Class (internal) | GRASP |
|---|---|---|
| The lectures of the documents as shown: their lists, the one shown, the course contexts, the sharing, the documents with lectures; a lecture built for a directory | `AssistantLectures` | Information Expert |
| The work of the assistant over time: the queue of transcriptions, the conversions of the imports, the reviews, the lectures followed live, the offers of the lectures received, the lock of the generation | `AssistantWork` | Pure Fabrication |
| The models: the backends, the speech recognition, the readers of the handwriting, the requests of the course context, of the text notes and of the search | `AssistantModels` | Information Expert, Creator |
| The public face, as today: delegates to the three | `LectureAssistant` | Controller |

The Compose state moves with its owner; `LectureAssistant` reads it through getters, so the UI
observes it as before.

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | The backends: `ModelBackend` with its limits, cache, errors and release; `ModelBackends.forTask`; the three `when (task.provider)` and the provider checks go; `answerOnline` through `GeminiBackend` (ABI of `drawview-lecture` and `drawview-lecture-ui`) | done |
| 2 | The state of `LectureAssistant` split: `AssistantLectures`, `AssistantWork`, `AssistantModels` | done |
| 3 | `DeviceBackend` over `drawview-ml` (`LoadedModels`, `InstalledModels` of `ModelCatalog`), the request and the JSON of the bench moved into it, the bench on it; not chosen by the settings yet | done |
| 4 | The models of the device chosen: the settings version 2 (`localEngine`, `TaskModel.device`) with their migration, tested on a file of version 1; `ModelBackends` sending the local requests to the engine of the settings; `LectureAssistant(deviceModels)` | done |
| 5 | The applications give their models: PencilNotes the ones of its search (`DeviceModels`), the sample none; the engine and the models of the device in the settings of the assistant, with their downloads | done |
| 6 | The hints of the settings for the models of the device (the measure of `docs/plans/local-notes.md` said beside the live notes), a model of the device deleted or its download cancelled from the settings of the assistant | done |

## Checks

At every phase: `./gradlew jvmTest checkKotlinAbi`, the compiles of Android and iOS, the two
Android applications, `verifyRoborazziJvm` of `drawview-lecture-ui` (and of `drawview-explorer`,
whose `text-chain-written` is flaky: run again). `updateKotlinAbi` in phase 1 (the answers of the
search), said in the commit. `consumer-checks` in phase 3 (a new dependency of `drawview-lecture`).

## Decisions

Taken alone, revertible:

* **`ModelProvider` stays `GEMINI` and `LOCAL`**, as `docs/plans/local-models.md` decided: the provider
  is written in the lecture records that travel. The device is an engine of `LOCAL`.
* **`answerWithGemini` becomes `answerOnline`, `canAnswerWithGemini` `canAnswerOnline`**: the
  search asks for an answer online, and which backend gives it is the assistant's business. The
  model stays the Gemini model of the live notes, the answer text without a schema, at most 4,096
  tokens, as today.
* **The answers of the search take the thinking level of the settings** (`low` by default), as the
  other requests of Gemini, where `answerWithGemini` left the default of the model.
* **The review repeats the temporary errors of any backend** (`isRetryable`), so a review on Ollama
  is repeated after an error of its server as the live requests are; before, only Gemini's were.
* **The search keeps only the online backend**: the answers on the device are
  `drawview-search`'s own (`LoadedModels` of the application).
* **The whisper models stay in `LocalModels`**: they are a speech engine (whisper.cpp), not a model
  of `ModelRuntime`; "without a second catalog" is about the models that answer the prompts.
* **`DeviceBackend` before its setting** (phase 3): the bench measures the code the application
  will run, not a copy of it.
* **`documentsWithLectures` and `importError` become read-only properties in the klib dump**: they
  were `var` with a private setter, read through a getter now; the JVM ABI does not change.
* **A model of the device is deleted without a confirmation**, as in the settings of the search: it
  is downloaded again on request, and it is freed from memory first. The search may use it too.
* **The names** `AssistantLectures`, `AssistantWork`, `AssistantModels`, `TaskBackend`,
  `ModelLimits`, `ModelUse`, `PromptCache`.

## Taken by the user

* **Phase 4, a change of `assistant-settings.json`** (2026-11-14): the settings take `version` (2),
  `localEngine` (`DEVICE` or `OLLAMA`) and `TaskModel.device`, an id of the catalog of
  `drawview-ml`. A file without a version is version 1: it reads as it was written, its local
  requests with Ollama, and it becomes version 2 when the settings are saved. A new installation
  starts with `DEVICE`. Tested on `assistant-settings-v1.json`, the file the previous version writes
  (`AssistantSettingsMigrationTest`). The previous version reads a file of version 2 too: it ignores
  the keys it does not know.

### The design of phases 4 and 5

| Responsibility | Class | GRASP |
|---|---|---|
| Which engine answers the local requests, and the model of each engine | `AssistantSettings.localEngine`, `TaskModel.device`, `AssistantSettings.modelOf` | Information Expert |
| The settings of version 1 read as they were | `AssistantSettingsStore.read` | Information Expert, Protected Variations |
| The local backend of a request: the device when chosen and given, Ollama otherwise | `ModelBackends.of(provider, settings)` | Polymorphism |
| The models of the device of the application, shared with the search | `DeviceModels` (`InstalledModels`, `LoadedModels`, `ModelDownloads`) given to `LectureAssistant` | Creator, Low Coupling |
| The engine and the models of the device in the settings | `AssistantSettingsDialog` | Controller |

* The defaults of the device follow the profile (`isComputer`): E4B for the live notes and the
  catch-up and 12B for the review on a computer, E2B and E4B elsewhere.
* An engine `DEVICE` without the models of the device (the sample, iOS for now) sends the local
  requests to Ollama, as today: the setting is kept.

## Left for later

* The speech on the device (phase 6 of `docs/plans/local-models.md`) and iOS (7): on iOS the runtime
  supports no model yet, so the local requests stay with Ollama there.
* `LocalModels` downloading through `ModelDownloads` (resume, SHA-256) also for whisper.
