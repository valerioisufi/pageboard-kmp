# Local models: one runtime on every device

The models that run on the devices of the student (the handwriting read, the notes suggested
during a lecture, the review, the speech recognized, the embeddings of the search) run on one
runtime, [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM), on Android, on the desktop
(Windows, macOS, Linux) and on iOS, with the models of the Gemma family: Gemma 4 for the
generation (text, images, audio) and EmbeddingGemma 2 for the embeddings (see
[docs/search.md](search.md)). Today the local models are reached through Ollama, on the computer
only, and the speech through whisper.cpp, on the desktop only; a tablet has no local model.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* **Everything in `drawview-lecture`.** The models are reached by the assistant only:
  `GeminiClient` and `GeminiBackend` (the Gemini API), `OllamaClient` and `OllamaBackend` (HTTP to
  the Ollama of the settings, `localUrl`, also found on the local network by `findOnNetwork`),
  both behind `ModelBackend` and `ModelPrompt` (system, parts of text, images, audio and files, the
  JSON schema of the answer), which are internal. `ModelBackends` picks one by `ModelProvider`
  (`GEMINI`, `LOCAL`).
* **The defaults of the local models are Gemma 4 already**, through Ollama: `gemma4:e4b` for the
  live notes and the catch-up, `gemma4:12b` for the review. Ollama answers in the JSON of the
  schema (`format`); it takes no audio.
* **whisper.cpp** is built from its source by Gradle (`native/whisper/whisper.properties`, CMake)
  into the desktop jar; `whisperEngine()` is `null` on Android and iOS, so a phone or a tablet
  cannot transcribe (`AssistantSettings.consistent` gives the transcription to Gemini there).
* **`LocalModels`** (Compose state) lists the models of Ollama, pulls them, and downloads the
  models of whisper into `whisper/` of the data directory (a `.part` file renamed when complete,
  no resume, no checksum).
* **The handwriting** is read by `HandwritingReader` with the model of the live notes, a block of
  strokes rendered as an image (`NotebookSource.renderInk`), and only in the notebooks with a
  lecture.
* **Stored formats that the change meets**:
  * `assistant-settings.json`, per device, without a version: `TaskModel(provider, gemini, local)`
    for every `LectureTask`;
  * `LectureRecord.finalReviewProvider: ModelProvider?` in `lecture.json`, which travels to the
    other devices of the notebook (`LectureAttachments`, `lecture/<id>`): a new value of
    `ModelProvider` written there cannot be read by a device of the previous version.

## LiteRT-LM and the models (October 2026)

* **Releases**: v0.18.0 (6 October) adds EmbeddingGemma 2 (text, vision and audio embeddings,
  Matryoshka truncation) to Kotlin, Swift, C++, Python and the web; v0.17.0 (9 September) the
  vision and audio of Gemma 4 12B, multi-token prediction, the Metal residency on Apple silicon;
  v0.16.1 (18 August) fixes a crash of the JVM on Windows; v0.16.0 (11 August) publishes a
  versioned C API as prebuilt shared libraries for every platform; v0.14.0 the audio on GPU and
  NPU.
* **Kotlin**: `com.google.ai.edge.litertlm:litertlm-android` and `litertlm-jvm` (Linux, macOS,
  Windows). `Engine(EngineConfig(modelPath, backend, visionBackend, audioBackend, cacheDir))`,
  `initialize()` (up to about 10 s), `createConversation(ConversationConfig(systemInstruction,
  samplerConfig, tools, thinkingConfig, maxOutputToken))`, `sendMessageAsync(contents):
  Flow<Message>`; the contents are `Content.Text`, `ImageBytes`, `ImageFile`, `AudioBytes`,
  `AudioFile`; `Backend.CPU()`, `GPU()`, `NPU(nativeLibraryDir)`; tool calling (`ToolSet`,
  `OpenApiTool`); the native errors are `LiteRtLmJniException`. The GPU on Android needs
  `uses-native-library` for `libOpenCL.so` and `libvndksupport.so` (not required). The embeddings:
  `EmbeddingEngine(EmbeddingEngineConfig(modelPath, backend, visionBackend, audioBackend))`,
  `computeEmbedding(listOf(InputData.Text(...)))`.
* **No constrained decoding is documented**: the answer in the JSON of a schema is not guaranteed
  (Ollama guarantees it today).
* **Swift** is an official SDK, of no use from Kotlin; the **C API** (`c/engine.h`,
  `conversation.h`, `embedding_engine.h`) is the way of Kotlin/Native. No example of it from
  Kotlin/Native exists.
* **Models** (`litert-community` on Hugging Face, Apache 2.0):

  | Model | File | Input | Use |
  |---|---|---|---|
  | Gemma 4 E2B | 2,583 MB | text, image, audio | phones and tablets |
  | Gemma 4 E4B | 3,654 MB | text, image, audio | tablets and computers |
  | Gemma 4 12B | — (to check) | text, image, audio | the review on a computer |
  | EmbeddingGemma 2, text 270M | 165 MB (int4) | text | the search |

  The audio of Gemma 4 is at most 30 seconds a clip, with no times said; the images take a budget
  of 70 to 1,120 tokens (more for small text); on documents E4B is far better than E2B
  (OmniDocBench 1.5, edit distance: 0.181 against 0.290).
* **Speed** (GPU, 1,024 tokens read, 256 written; prefill / decode in tokens a second): E2B on a
  Galaxy S26 Ultra 3,808 / 52, on an iPhone 17 Pro 2,878 / 56; E4B on the S26 Ultra 1,293 / 22, on
  an Intel Lunar Lake laptop (Windows) 1,202 / 25, on a MacBook Pro M4 Max 2,560 / 101.

## Requirements

* One runtime for the generation (text, images, audio) and the embeddings on Android, on the
  desktop JVM (Windows, macOS, Linux) and on iOS (`iosArm64`, `iosSimulatorArm64`).
* Gemma 4 reads the handwriting, formulas included (LaTeX), writes the live notes, the catch-up
  and the review, and may recognize the speech; EmbeddingGemma 2 gives the embeddings of the
  search.
* Nothing leaves the device once the model is downloaded.
* The runtime is an optional engine (rule 1): an application without it builds, and the assistant
  offers Gemini and Ollama as today.
* The libraries stay headless and without global state; explicit API; the ABI recorded.
* The models are downloaded on request, from the settings, resumed when interrupted, verified,
  kept in `models/` of the data directory; never inside the application. The settings say their
  size and the memory they need.
* The settings of today keep doing what they do (a tested migration); the lecture records and what
  travels do not change: `PROTOCOL_VERSION` stays 1.
* A model is loaded when it is needed and freed when it is no longer: on a phone or a tablet one
  large model at a time.

## Design

### Modules

```
drawview-ml          platform      the ports, the catalog, the downloads, the models in memory
drawview-ml-litert   ml            LiteRT-LM: litertlm-android, litertlm-jvm, the C API on iOS
drawview-lecture     model, ml     the assistant, on the ports of drawview-ml
drawview-search      store, ml     see docs/search.md
```

`drawview-ml` and `drawview-ml-litert` are headless (no Compose): their state is `StateFlow`,
which `LocalModels` of `drawview-lecture` turns into the Compose state of the settings.
`drawview-lecture` depends on the ports only; PencilNotes and the sample give the runtime.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The models that can be used: file, size, SHA-256, inputs, memory needed, the devices they suit | `ModelCatalog`, `ModelSpec` (ml) | Information Expert |
| The models on this device, in `models/` | `InstalledModels` (ml) | Information Expert |
| Download a model: resumed (HTTP range), verified, its progress; a file that does not match is not kept | `ModelDownloads` (ml) | Pure Fabrication |
| Run a model: load it, answer a request of text, images and audio, compute an embedding | `ModelRuntime`, `Generator`, `Embedder` (ml) | Protected Variations, Polymorphism |
| LiteRT-LM on every platform (GPU tried first, then CPU) | `LiteRtRuntime` (ml-litert, one `actual` per platform) | Polymorphism, Indirection |
| Keep in memory what is used, free what is not, one large model at a time on a phone | `LoadedModels` (ml) | Pure Fabrication, Low Coupling |
| An answer in the JSON of its schema, without constrained decoding: checked, asked again once with the error | `JsonAnswers` (ml) | Pure Fabrication |
| The requests of the assistant answered on the device | `DeviceBackend : ModelBackend` (lecture) | Polymorphism |
| Which engine answers the local requests, and its models | `AssistantSettings.localEngine`, `TaskModel.device` (lecture) | Information Expert |
| The settings of the previous version read as they were | `AssistantSettingsStore` (lecture), version 2 | Information Expert |
| The speech recognized by Gemma 4, 30 seconds at a time, cut at a pause | `GemmaTranscriber : Transcriber` (lecture) | Polymorphism |
| The models of the settings: on the device, Ollama, whisper | `LocalModels` (lecture) | Controller |
| The runtime of the application | `LectureAssistant(runtime: ModelRuntime? = null)`, `rememberLectureAssistant` | Creator |

### The ports

The shape is fixed in phase 1; in short:

```kotlin
public interface ModelRuntime {
    public suspend fun generator(model: InstalledModel, options: GeneratorOptions): Generator
    public suspend fun embedder(model: InstalledModel, options: EmbedderOptions): Embedder
}

public interface Generator : AutoCloseable {
    /** The answer to [request] (text, JPEG images, 16 kHz mono PCM), the tokens written so far in [onProgress]. */
    public suspend fun generate(request: GenerationRequest, onProgress: (Int) -> Unit = {}): String
}

public interface Embedder : AutoCloseable {
    public val dimensions: Int
    public suspend fun embed(text: String): FloatArray
}
```

`ModelRuntime` is an instance given by the application (rule 4); `LoadedModels` wraps it and is
the only one that loads and closes.

### The engine is a setting, not a provider

`ModelProvider` stays `GEMINI` and `LOCAL`: `LOCAL` is a model of the devices of the student
(free, private), whoever runs it. The engine (`LocalEngine.DEVICE`, LiteRT-LM; `LocalEngine.OLLAMA`)
is in the settings of the device only, and `TaskModel` keeps a model for each (`local` for Ollama,
`device` for an id of the catalog). So `LectureRecord.finalReviewProvider` and the lectures that
travel are written as today (`finalReviewBy` names the model, a string).

### The settings, version 2

* `assistant-settings.json` takes `version = 2`, `localEngine` and `TaskModel.device`.
* A file without a version is version 1: its local requests stay with Ollama (`localEngine =
  OLLAMA`); a new installation starts with `DEVICE`.
* Tested with the file written by the current version (a fixture): the same tasks, the same
  models, Ollama.
* The defaults of the device models follow the profile of the device (`drawview-platform`):
  * a computer: E4B for the live notes, the catch-up and the handwriting, 12B for the review;
  * a tablet or a phone: E2B for the live notes, E4B for the review.

### Memory and loading

* A model is loaded at its first request (`initialize()` takes seconds: the panel says "loading
  the model").
* The model of the live notes stays loaded during the lecture (as `LIVE_KEEP_LOADED_SECONDS` of
  Ollama) and is freed at its end, as today.
* On a phone or a tablet `LoadedModels` keeps one generator at a time; the embedder of the search
  (165 MB) waits while a lecture is recorded.
* GPU first, CPU when the GPU cannot be used; the backend chosen is said in the settings.

### Native libraries

* **Android**: `litertlm-android` brings its libraries (an AAR), with `drawview-ml-litert` only
  (rule 3); the manifest of the module declares the GPU libraries as optional.
* **Desktop**: `litertlm-jvm`; phase 1 checks which libraries it brings for every system, and that
  they reach the jar of PencilNotes on Windows, macOS and Linux.
* **iOS**: the prebuilt C API of the release, downloaded and verified by Gradle
  (`native/litertlm/litertlm.properties`, as whisper.cpp), bound with cinterop and embedded in the
  applications as `PDFium.framework` is.

### Speech

Gemma 4 may replace whisper.cpp: it runs on the tablets too (none transcribes today), and the
course vocabulary goes in its prompt. Against it: 30 seconds a clip, no times (a segment per
piece, as the speech models of Gemini without times), a quality not measured on Italian lectures.
whisper.cpp stays until phase 2 measures both on the same ten minutes of
[docs/lecture-assistant.md](lecture-assistant.md); removing it is a decision of its own.

### Ollama

Ollama stays as the engine "advanced": the large models (Gemma 4 26B and 31B) and the computer of
the student reached from a tablet on the local network. It is no longer the default.

## Phases

1. **The runtime on the desktop**: `drawview-ml` (ports, `ModelCatalog`, `InstalledModels`,
   `ModelDownloads` with resume and SHA-256, `LoadedModels`, `JsonAnswers`) and
   `drawview-ml-litert` on the JVM; the bench (a JVM test of `drawview-ml-litert` run only with
   `-Ddrawview.bench=<scratch dir>`, never by `jvmTest`); the natives of `litertlm-jvm` checked on
   Windows. Both modules as every new library: `drawview.kmp.library`, `settings.gradle.kts`, the
   Dokka aggregate, `Module.md`, the `api/` dump.
2. **Measure on the desktop**, on a copy of some notebooks and of a lecture in a scratch directory
   (the student chooses them): Gemma 4 E2B and E4B on the handwriting and the formulas (image
   budgets 280, 560, 1,120), how often the answers follow their JSON schema, Gemma 4 against
   whisper.cpp on ten minutes of lecture, EmbeddingGemma 2 for the search (with phase 0 of
   [docs/search.md](search.md)). The results go here; the defaults follow.
3. **Android**: `litertlm-android` in `drawview-ml-litert`, GPU and CPU, measured on the tablet of
   the student.
4. **The assistant on the device**: the settings version 2 and their migration, `DeviceBackend`,
   the handwriting, the live notes, the catch-up and the review on Gemma 4, `LocalModels` on
   `InstalledModels` and `ModelDownloads`; the lecture records unchanged (tested).
5. **The settings** (`drawview-lecture-ui`): the engine, the models of the catalog with their size
   and memory, the downloads; the screenshots.
6. **The speech on the device**, if phase 2 says so: `GemmaTranscriber`, the tablets transcribe
   (`consistent`).
7. **iOS**: the C API of LiteRT-LM, cinterop, the framework in the applications.
8. **Documentation**: README, `docs/modularization.md` (modules, graph, headless list),
   CLAUDE.md, the `Module.md` of the new modules, the licenses (LiteRT-LM; Gemma 4 and
   EmbeddingGemma 2 in About, downloaded and not shipped).

## Progress

| Phase | State |
|---|---|
| 1. The runtime on the desktop | not started |
| 2. Measure on the desktop | not started |
| 3. Android | not started |
| 4. The assistant on the device | not started |
| 5. The settings | not started |
| 6. The speech on the device | not started |
| 7. iOS | not started |
| 8. Documentation | not started |

## Checks

* `./gradlew jvmTest checkKotlinAbi` and the builds of CLAUDE.md at every phase;
  `./gradlew updateKotlinAbi` for the new modules (phase 1) and for `drawview-lecture` (phase 4),
  said in the commits; `./consumer-checks/check.sh` from phase 1 (new published modules and
  dependencies).
* `verifyRoborazziJvm` of `drawview-lecture-ui` in phase 5.
* The desktop application on a scratch directory only:
  `JAVA_TOOL_OPTIONS="-Ddrawview.dataDir=<scratch dir>" ./gradlew :pencilnotes-desktop:run`. The
  models are downloaded into the scratch directory, never into `~/.pencilnotes`.

## Decisions

* LiteRT-LM is the one runtime on the devices; Gemma 4 reads the handwriting, formulas included
  (the student, 2026-10-17).
* `ModelProvider` does not change; the engine is a setting of the device. A new provider would
  change the lecture records that travel.
* The settings of today stay with Ollama; the new installations start on the device (revertible).
* The defaults by profile of the device: E4B and 12B on a computer, E2B and E4B on a tablet
  (revertible, after phase 2).
* The module names `drawview-ml` and `drawview-ml-litert` (revertible).
* The models are downloaded from `litert-community` on Hugging Face, verified by SHA-256, and kept
  in `models/` of the data directory; a model file is deleted only when the student asks it in the
  settings.
* whisper.cpp and Ollama stay until they are replaced by a measurement and a decision (revertible).
* The JSON answers are checked and asked again once (no constrained decoding); tool calling is the
  alternative if phase 2 finds too many answers out of their schema (revertible).

## Left for later

* Removing whisper.cpp (and its native build) and Ollama.
* The NPU of Android (vendor libraries, a larger application) and the adapter of LiteRT-LM for the
  Apple Foundation framework.
* The writing suggestions on the same runtime (a hook of `EditorExtension` in `TextBoxEditor`, see
  CLAUDE.md).
* The new versions of a model in the catalog, and the old files offered for deletion.
* A model adapted to the handwriting of the student (LoRA), if LiteRT-LM loads adapters.

## Risks

* LiteRT-LM is at 0.x: its API may change; the versions are pinned.
* The natives of `litertlm-jvm` on Windows (a crash was fixed in August) and the C API on iOS
  without examples from Kotlin/Native.
* Memory and space on a tablet: E4B is a file of 3.6 GB; the downloads go on Wi-Fi.
* The quality of Gemma 4 on Italian handwriting and on formulas is not measured yet: the models
  write plausible text that is not on the page (most of their errors in WildHandBench, August
  2026).
* The answers out of their schema, without constrained decoding.
