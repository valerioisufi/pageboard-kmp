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

Read in the code; nothing measured yet (see phase 0).

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
  while it waits: a lecture waiting here can still be taken by another device, and then leaves the
  queue.
* `start` (a recording) calls `hold()`, and `release()` when its recorder stops listening (the review
  of the recording needs no speech model). A lecture followed live does not hold the queue: as today
  it runs beside it (decision 3).

### Pausing

`RecordingTranscriber.transcribe` gets `pause: suspend () -> Unit`, called before every piece is
submitted. The gate of the queue, when closed: waits for the pieces in flight, delivers them, closes
the transcriber (`Transcriber.close` frees the whisper model; it is loaded again at the next piece),
sets `importStep = WAITING`, and suspends until it opens. Nothing transcribed is thrown away, and the
piece in progress is finished first: with whisper, some tens of seconds at most.

### The window

Decided by phase 0, which measures on the Mac of the user, with the model the user uses, the frames of
a pan and of a stroke: alone, with a transcription running, and with each of these, one at a time:

| Candidate | Cost |
|---|---|
| Fewer decoder threads than performance cores (2 instead of 4) | A slower decoder |
| The decoder threads at a lower priority (`QOS_CLASS_UTILITY` in the bridge) | They move to the efficiency cores under load |
| Shorter pieces to the encoder, so that the GPU is given back more often | More pieces |
| The encoder on the CPU while the window is in front | Much slower |
| The record built off the thread of the interface, the transcript shown appended and not rebuilt | None |

Only what the measure shows to matter is done, and the speed of the transcription is measured with it.

## Phases

One commit per phase on `feat/transcription-queue`, with the checks of `CLAUDE.md`.

| # | Phase | Status |
|---|---|---|
| 0 | Measure: a bench that pans and draws in a real window while whisper.cpp transcribes ten minutes of audio; the table above filled in; the target of requirement 4 | Waiting for a model (see below) |
| 1 | `TranscriptionQueue`, `ImportStep.WAITING`, `importRecording` converting at once, `generate` and `retranscribe` enqueued, `canRecord`. Tests: two imports one after the other, an import during a retranscription, the order kept, a failed conversion leaving the queue as it was, a lecture taken out of the queue, the generation lock taken when the job starts | Not started |
| 2 | Pausing: the gate in `RecordingTranscriber`, held by a recording. Tests: a recording started in the middle of a transcription (the piece in flight delivered, the model closed, nothing lost, it goes on after), an import during a recording waiting for its end, a recording that fails to start releasing the gate | Not started |
| 3 | The panel: *New recording* and *Import* enabled by the new rules, the card of a lecture waiting with *Remove from the queue*, the notices. Tests: screenshots (`verifyRoborazziJvm`), the desktop application on a scratch directory | Not started |
| 4 | The window: what phase 0 chose. Tests: the bench of phase 0 run again | Not started |

### What phase 0 needs

A whisper model. The one of the user is in the data directory of PencilNotes, which is never touched
while verifying. Either the user allows reading that one file (read only, by its path, from a scratch
data directory), or a model is downloaded into the scratch directory (the user says which: about
570 MB for `ggml-large-v3-turbo-q5_0.bin`).

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

## Left for later

* Keeping the queue across the runs of the application.
* Choosing the order of what waits.
* The transcription on Android and iOS, where there is no whisper.cpp.
