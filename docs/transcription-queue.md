# Transcriptions in a queue, and a window that stays smooth

Two reports from real use of the lecture assistant on the desktop:

1. while a lecture is transcribed, no other audio can be imported and nothing can be recorded;
2. while a lecture is transcribed, the window stutters.

This document checks both against the code and gives the requirements, the design and the plan. It
continues `docs/lecture-assistant.md` and `docs/local-models.md`, and starts from `main` at d04b4613,
2026-11-12.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Pure Fabrication, Protected Variations).

## Starting point

Read in the code. What phase 0 then measured is in "The window", under the design.

### One thing at a time

* `LectureAssistant.isBusy` is `activeRecorder != null || importing != null`, and `importing` is one
  lecture: the one being imported (`importRecording`), transcribed for the first time (`generate`, a
  lecture received without a transcript) or transcribed again (`retranscribe`).
* `record`, `start`, `importRecording`, `generate` (when it transcribes) and `retranscribe` all return
  at once while `isBusy`. `LecturePanel` disables *New recording* and *Import* with it, in every
  document, and `Notices` says that another lecture is being recorded or imported.
* An import converts the file (`decodeAudioFile`), transcribes it (`RecordingTranscriber`, pieces of
  `Transcriber.pieceSeconds`, `parallelism` at a time) and reviews it (`FinalReviewer`), in one job.
  A lecture of an hour and a half keeps the assistant busy for minutes.
* The lectures followed live from another device (`follow`) are outside `isBusy`: they already run
  beside everything else, each with its own transcriber.
* A lecture left `IMPORTING` when the application quits is loaded as finished, with its audio and the
  transcript it had (`LectureLibrary`, the status set here); *Generate* transcribes it when it has
  none.

### The window stutters

* The transcription is not on the thread of the interface: the jobs run on `Dispatchers.Default`,
  whisper.cpp inside `Dispatchers.IO` (`WhisperTranscriber.transcribe`), and the state of the lecture
  is set on `stateContext` a piece at a time.
* whisper.cpp is loaded with `useGpu = true` and decodes on 4 threads (`DEFAULT_THREADS`). The Mac of
  the user has 4 performance cores and 6 efficiency ones, and Compose draws the window with the same
  GPU (Metal). Two suspects, to be measured: the encoder holding the GPU while a frame waits, and the
  four decoder threads taking the four performance cores.
* On the thread of the interface, at every piece: `Lecture.persist` builds the whole record
  (`toRecord`, `LectureRecords.stamped`) and, transcribing again, the transcript is rebuilt
  (`segments + rest`). It grows with the lecture, but it happens once a piece: a hitch every few
  seconds at most, not a window that stutters all the time.

## Requirements

1. **An audio can be imported at any time**, also while a lecture is recorded, transcribed or
   reviewed: it is converted at once, and waits its turn to be transcribed.
2. **A recording can start while a transcription runs or waits.** The transcription pauses at the end
   of the piece it is on, frees its model, and goes on when the recording is over (decided by the
   user, 2026-11-12): one whisper model in memory at a time.
3. **What waits is shown as waiting**, in its lecture and in the notices, and can be taken out of the
   queue.
4. **The window stays smooth while a lecture is transcribed**: the frames of a pan and of a stroke as
   without it, within what phase 0 measures and sets as the target.
5. **Nothing recorded or imported is lost**: an audio converted is a lecture on disk before it waits;
   the application quitting leaves it as an interrupted import is today.
6. **No stored format changes**: `lecture.json` keeps its fields and its statuses, so that the other
   devices read it as before. A device on the old code sees a lecture waiting as one being imported.

## Design

### Responsibilities

| Responsibility | Who | GRASP |
|---|---|---|
| The transcriptions of whole recordings, one at a time, in the order asked; pausing and resuming | `TranscriptionQueue` (`drawview-lecture`, internal) | Pure Fabrication, High Cohesion |
| Knowing when a recording here needs the model | `LectureAssistant`, which starts and ends the recordings | Controller |
| Stopping between two pieces and freeing the model | `RecordingTranscriber`, given a gate by the queue | Information Expert |
| What a lecture waiting shows | `Lecture.importStep`, with `ImportStep.WAITING` | Information Expert |
| How whisper.cpp uses the machine while the window is in front | `WhisperTranscriber`, by what phase 0 measures | Protected Variations |

### The queue

* `TranscriptionQueue(scope)`: `enqueue(lecture, job)` adds a job and returns it; one job runs at a
  time, the others wait in order; `cancel(lecture)` takes one out; `hold()` and `release()` close and
  open its gate; `waiting` and `running` are states read by the interface.
* `LectureAssistant.importing` stays (the lecture whose job runs, for the code that reads it), and
  gains `transcribing: List<Lecture>` (running and waiting) and `isTranscribing(lecture)`.
* `isBusy` keeps its meaning for the exit of the application (`LectureRecordings.isRecording`:
  something would be lost). What the buttons ask becomes:
  * `canRecord`: no recording here not yet over (`activeRecorder == null`);
  * an import: always.
* `importRecording` converts the file at once, outside the queue (it reads the file the user chose,
  which may be temporary), saves the lecture, then enqueues its transcription and review. Two imports
  convert one after the other.
* `generate` (when it transcribes) and `retranscribe` enqueue too. The generation lock that tells the
  other devices to leave the lecture alone (`holdingGeneration`) is taken when the job starts, not
  while it waits: it is refreshed while held, and a lecture waiting for an hour would look abandoned.
  A lecture waiting is `IMPORTING` here, and the other devices see it so once it is saved or merged,
  as an import today; when another device takes the lock, the job here is cancelled (as today,
  `Lecture.showMerged`) and leaves the queue.
* `cancelTranscription(lecture)` takes a lecture out, waiting or running: it becomes a finished
  lecture with its audio and the transcript made so far, and is saved so.
* An imported lecture is told that its audio is here when it is converted (`Lecture.audioReceived`):
  before, a lecture just imported had `hasAudio` false until it was loaded again, so it could not be
  exported or transcribed again from the panel it was imported in.
* `start` (a recording) calls `hold()`, and `release()` when the recording is finished, its review
  written (decision 6). A lecture followed live does not hold the queue: as today it runs beside it
  (decision 3).

### Pausing

`RecordingTranscriber.transcribe` gets `pause: suspend () -> Unit`, called before every piece is
submitted. The gate of the queue, when closed: waits for the pieces in flight, delivers them, closes
the transcriber (`Transcriber.close` frees the whisper model; it is loaded again at the next piece),
sets `importStep = WAITING`, and suspends until it opens. Nothing transcribed is thrown away, and the
piece in progress is finished first: with whisper, some tens of seconds at most.

### The window

Measured by phase 0 (`TranscriptionWindowBenchmark`, on a Mac with 4 performance and 6 efficiency
cores, 2026-11-13): a real window of the editor of 1200 × 900 on a page of 30 texts and
250 strokes, zoomed and panned at every frame, while `ggml-large-v3-turbo-q5_0` transcribes four
minutes of speech again and again in pieces of a minute. The time between two frames over 15 s, and
how many seconds of audio are transcribed in a second:

| What transcribes | Frames a second | Median | 99% | Longest | Frames over 50 ms | Speed |
|---|---|---|---|---|---|---|
| Nothing | 60 | 16.7 ms | 17.9 ms | 23 ms | 0 | – |
| The GPU, 4 threads (as it was) | 34 | 16.7 ms | 383 ms | 402 ms | 20 | 26.4× |
| The GPU, 2 threads | 35 | 16.7 ms | 368 ms | 401 ms | 20 | 26.4× |
| The GPU, 1 thread | 36 | 16.7 ms | 334 ms | 384 ms | 21 | 26.4× |
| The processor, 4 threads | 60 | 16.7 ms | 17.7 ms | 25 ms | 0 | 5.7× |
| The processor, 6 threads | 60 | 16.7 ms | 18.5 ms | 19 ms | 0 | 5.9× |
| The processor, 8 threads | 60 | 16.7 ms | 19.2 ms | 26 ms | 0 | 5.6× |
| The processor, 10 threads | 60 | 16.7 ms | 25.9 ms | 55 ms | 1 | 4.3× |

* **It is the GPU, not the threads.** On the GPU the window stops for about 0.4 s more than once a
  second, half of the time in all, whatever the threads of the decoder: the encoder of whisper.cpp
  runs as one long piece of work of the GPU, and the frame of the window waits for it.
* **On the processor the window is as without a transcription**, and the transcription is 4.6 times
  slower: an hour and a half of lecture in 16 minutes instead of three and a half. More threads than
  the performance cores do not make it faster.
* The work on the thread of the interface at every piece (the record built, the transcript rebuilt)
  does not show: no frame is late on the processor.
* The Metal backend of whisper.cpp 1.9.4 splits a graph into 9 command buffers at most, and does not
  let a caller choose how many: making the work of the GPU finer would mean patching it.

Proposed from this, and turned down by the user (2026-11-13): the speech model on the processor
while the window of the application is in front, on the GPU otherwise. It was written and taken out
again; nothing of it is in the code.

### The encoder on the Neural Engine

whisper.cpp 1.9.4 can run its encoder through Core ML (`WHISPER_COREML`), from a second file next to
the model: `ggml-large-v3-turbo-encoder.mlmodelc` beside `ggml-large-v3-turbo-q5_0.bin` (the `-q5_0`
is dropped from the name, so one encoder serves both turbo models). The authors of whisper.cpp
publish it compiled, zipped, in the same repository as the models: 1,173,393,014 bytes for
large-v3-turbo, 163,083,239 for small, 37,922,638 for base. The decoder stays where it is (the GPU,
or the processor).

Measured the same way (2026-11-13), with the bridge built with Core ML, the encoder downloaded into a
scratch directory, and the model of the user read through a link:

| What transcribes | Frames a second | Median | 99% | Longest | Frames over 50 ms | Speed |
|---|---|---|---|---|---|---|
| Nothing | 60 | 16.7 ms | 17.9 ms | 18 ms | 0 | – |
| The GPU, 4 threads, without the encoder file (as it is) | 32 | 16.7 ms | 384 ms | 416 ms | 21 | 27.4× |
| The Neural Engine, the decoder on the GPU, 4 threads | 60 | 16.7 ms | 17.6 ms | 28 ms | 0 | 29.3× |
| The Neural Engine, the decoder on the processor, 4 threads | 60 | 16.7 ms | 17.6 ms | 18 ms | 0 | 25.1× |

* **The window is as without a transcription, and the transcription is faster than today.** The
  short pieces of work of the decoder on the GPU do not hold a frame back; the long one was the
  encoder.
* The first minute heard is the same word for word in the three ways.
* Core ML left free to choose (`MLComputeUnitsAll`, as whisper.cpp asks) put the encoder on the
  Neural Engine: the same numbers as with the Neural Engine forced. No patch of whisper.cpp is needed.
* The model loads in 0.3 s once Core ML has compiled the encoder for the Neural Engine. The first
  load, which compiles it, was not timed: phase 5 measures it.

### Design of phase 4 onwards

Approved by the user, 2026-11-13.

* **The build** (`native/CMakeLists.txt`): on macOS, whisper.cpp with `WHISPER_COREML` and
  `WHISPER_COREML_ALLOW_FALLBACK`. Without the encoder file, or with one Core ML cannot load,
  whisper.cpp encodes on the GPU as today (Protected Variations: nothing else of the transcriber
  knows about Core ML).
* **`WhisperModel` knows its encoder** (Information Expert): the name of the file (`encoderFileName`),
  its size and its SHA-256, the ones above.
* **`LocalModels` downloads it** (Controller, as it does the models): `encoders`, the models whose
  encoder is on the computer; `downloadEncoder(model)`, the zip as a `.part`, its size and SHA-256
  checked, extracted, the zip deleted; `usesNeuralEngine`, true on a Mac with Apple Silicon only (on an
  Intel Mac Core ML has no Neural Engine to give).
* **`EncoderArchive` extracts it** (Pure Fabrication, `jvmMain`): into a `.part` directory renamed when
  complete, the entries of `__MACOSX` skipped, an entry going out of the directory refused, the bytes
  extracted bounded. A native library loads what is in it, so only a zip whose SHA-256 is the known
  one is opened.
* **The settings show it** under the model chosen, where `usesNeuralEngine`: *Neural Engine: keeps the
  window smooth while transcribing* with *Download (1.2 GB)*, or ready ✓. Downloading a model on
  such a Mac downloads its encoder after it.
* Nothing stored changes: the encoder is a new file in the directory of the models, not a format.

## Phases

One commit per phase on `feat/transcription-queue`, with the checks of `CLAUDE.md`.

| # | Phase | Status |
|---|---|---|
| 0 | Measure: `TranscriptionWindowBenchmark`, a real window of the editor panned at every frame while whisper.cpp transcribes four minutes of speech, on the GPU and on the processor with 1 to 10 threads; the table above. The target of requirement 4: no frame over 50 ms, as without a transcription | Done |
| 1 | `TranscriptionQueue`, `ImportStep.WAITING`, `importRecording` converting at once, `generate` and `retranscribe` enqueued, `transcribing`, `isTranscribing`, `cancelTranscription`, with the ABI of `drawview-lecture` re-recorded; the card of a lecture waiting says so. A recording here still refuses and is refused, until phase 2 pauses the queue. Tests (`TranscriptionQueueTest`): two imports one after the other, imports during a new transcription with the order kept, a file that is not audio leaving the queue as it was, a lecture taken out of the queue waiting and running, the generation lock taken when the job starts | Done |
| 2 | Pausing: `TranscriptionGate`, checked by `RecordingTranscriber` between two pieces and held by a recording here; `canRecord`, with the ABI of `drawview-lecture` re-recorded; an import, a first transcription and a new one are taken during a recording too. Tests (`TranscriptionQueueTest`): a recording started in the middle of a transcription (the piece in flight delivered, the model closed, a segment for every piece, it goes on after), an import during a recording waiting for its end, a recording that cannot start leaving the transcriptions going | Done |
| 3 | The panel: *Record* enabled by `canRecord` and *Import* always; the card of a lecture waiting (for its turn, or for the recording to end) with *Remove from the queue*, of one being transcribed with *Stop*; the notice of a recording elsewhere, and of the other recordings in the queue. Tests: `LecturePanelQueueTest` (the buttons while a lecture is transcribed and while one is recorded, a lecture taken out waiting and stopped running, from its card), the screenshot `lecture-recording-notices` re-recorded (`verifyRoborazziJvm`). The desktop application on a scratch directory was not run: it needs a speech model (phase 0) | Done |
| 4 | The Neural Engine measured: the bench prints the load time and the first words heard; the table above | Done |
| 5 | The bridge with Core ML on macOS, falling back without the encoder; `WhisperTranscriberTest` and the transcription of the bench with and without the encoder file; the first load (Core ML compiling) timed with the cache of Core ML empty | Not done |
| 6 | `WhisperModel` encoder fields, `LocalModels.encoders`, `downloadEncoder`, `usesNeuralEngine`, `EncoderArchive`, with the ABI of `drawview-lecture` re-recorded. Tests: an archive extracted, one with an entry outside the directory refused, one with the wrong SHA-256 refused and deleted, a download stopped leaving nothing | Not done |
| 7 | The settings: the encoder under the model, downloaded after a model on Apple Silicon; screenshot; the desktop application on a scratch directory, the encoder downloaded there and a lecture imported while panning | Not done |

## Decisions

Taken alone, revertible, unless they say otherwise.

1. **The queue is in memory.** After the application quits, a lecture that was waiting is a finished
   lecture with its audio and no transcript, transcribed on request, as an interrupted import today.
   Keeping the queue would add a stored format.
2. **A recording pauses the transcription and does not run beside it** (decided by the user).
3. **A lecture followed live from another device does not pause the queue**: as today. It is
   uncommon, and pausing would leave an import waiting for a lecture that lasts hours.
4. **One recording here at a time**, as today: `recorder` is one.
5. **The conversion of an import is outside the queue**: it is fast, and it is what makes the audio
   safe.
6. **The queue goes on when the recording is finished, its review included**, not when its
   microphone closes: one hook (`onFinished`, called by every way a recording ends), and a review
   written by a local model needs the memory too. The plan first said "when its recorder stops
   listening".

## Left for later

* The window on a computer without a Neural Engine (Windows, Linux, an Intel Mac): the work of the
  GPU made finer, by patching the Metal backend of whisper.cpp, or the processor chosen by a setting.
* Keeping the queue across the runs of the application.
* Choosing the order of what waits.
* The transcription on Android and iOS, where there is no whisper.cpp.
