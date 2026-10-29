# The cost of the lecture assistant

A lecture of an hour and a half followed by the assistant costs about $0.55 of Gemini today, for
its live notes alone. This plan brings it to about $0.12 with the same model and notes of the same
quality: a request every five minutes instead of every minute and a half, the slides of the whole
notebook at the start of every request, and that start kept in an explicit cache of Gemini. This
document starts from the code as it is (main at 788d14a6, 2026-10-29) and from what a bench
measured on a recorded lecture, then the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **A request every chunk.** `LectureRecorder` cuts the audio into chunks of
   `AssistantSettings.chunkSeconds` (60 s by default, 30–120) and sends a request of live notes
   for every chunk (`analyzeChunks`, built by `LecturePrompts.chunkPrompt`). The lecture of
   2026-10-15 (1:12:46) made 49 requests: 326,830 prompt tokens, 55,316 output tokens, about $0.45
   with Gemini 3.8 Flash ($0.75 / $3.75 a million tokens until 2026-12-31, then twice as much).
2. **No cache.** The same lecture read 0 tokens from the cache. The parts of a request go from
   the most stable to the most variable (instructions, course, memory, notebook, speech), but the
   memory (outline, notes already suggested) changes at every request, right after a start of
   about 1,300 tokens: Gemini 3.8 Flash caches a start of 4,096 tokens at least.
3. **Every request repeats the slides around the visible page** (`pagesText`, 3,500 characters
   at most), and knows nothing of the others: the notes often restate a slide.
4. **The outline** is asked every 210 s (`OUTLINE_INTERVAL_MS`), about 300 words of output each
   time.
5. **The transcription** is a separate cost: whisper.cpp on the computers (free), Gemini 3.5
   Transcribe on the phones ($0.005 a minute live, $0.45 a lecture; `consistent()` forces it where
   whisper does not run).
6. **What the bench measured.** `NotesBench` (in the jvmTest of `drawview-lecture`, run with
   `-Pdrawview.bench`) replays the lecture of 2026-10-15 with its transcript, a request every
   interval, built as the recorder builds them, on the notebook as the student had it without the
   assistant (the notes inserted from its suggestions removed, no images of the pages). A grader
   (Gemini 3.8 Flash) scores every set of notes on the 23 questions of the eval set that the
   slides do not answer, and judges every note: it adds to the slides, it restates them, trivial,
   wrong.

   | Configuration | Questions | Notes | Add | Restate | Wrong | $ for 72 min |
   |---|---|---|---|---|---|---|
   | Today (3.8 Flash, every 90 s, medium thinking) | 74% | 13 | 10 | 3 | 0 | ~0.45 |
   | 3.8 Flash, every 5 min, slides first | 72% | 16 | 13 | 3 | 0 | 0.143 (no cache) |
   | 3.8 Flash, every 5 min, prompt of today | 67% | 23 | 11 | 11 | 1 | 0.090 |
   | 3.5 Flash-Lite, every 5 min, prompt of today | 46% | 19 | 5 | 13 | 1 | 0.038 |
   | Gemma 4 E4B on the Mac (LiteRT-LM), every 5 min | 61% | 53 | 6 | 35 | 10 | 0 |

   Earlier runs, on the notebook with the notes of the assistant in it, showed too that the audio
   sent to Gemini instead of the transcript costs twice as much for worse notes (3.8 Flash: 39%
   of the questions against 67%), that Gemma 4 E4B on the audio writes a wrong note out of three,
   and that the medium thinking costs five times the output for one note more. Implicit caching
   did not take a start of about 6,000 identical tokens, sent seconds apart: 3,906 cached tokens
   out of 148,000 in one run, none in the others.

   **The grader and the model are noisy.** The same set of notes, graded twice, got 72% and 63%
   of the questions, and 4 notes of 16 changed verdict. The same configuration, run twice, wrote
   10 and 13 notes (54% and 67%). A difference of ten points or of a few notes between two runs
   is not a result.

## Requirements

1. A lecture of 90 minutes costs about $0.12 of live notes with Gemini 3.8 Flash until
   2026-12-31, about $0.24 after, with the transcription on the device.
2. The notes are as good as today on the bench: the questions within a few points of 74%, as many
   notes that add to the slides, none wrong.
3. The student chooses how often the notes come (from one to ten minutes); "I missed something"
   still answers at once.
4. Nothing more leaves the device: the cache holds what the requests already send (the slides,
   the course), for the time of the lecture.
5. Without a cache (a start too short, an error of the API, a local model) the requests work as
   now.
6. The settings stored keep working: a file without the new field reads with its default.
7. **No audio leaves the device for Google** (asked 2026-10-29): the lecture is transcribed by
   whisper.cpp on a computer only, Gemini receives text and images. The phones and the tablets,
   where whisper does not run, record and share the audio with the devices of the user; a
   computer that follows the lecture transcribes it and writes the notes live, otherwise it
   transcribes and reviews it afterwards.
8. What was stored with the online transcription stays readable: the lectures transcribed by
   Gemini keep their transcript; settings saved with a Gemini transcription read as whisper.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The start of a request that does not change during a lecture: instructions, course, the text of every slide of the notebook as it was when the lecture began; the rule that restating a slide is redundant, explaining it is not | `LecturePrompts.chunkPrompt(start = …)`, `slidesText` (`drawview-lecture`) | Information Expert (it knows the prompts), High Cohesion |
| The title and the pages of the notebook at the first request of Gemini with the notebook open | `LectureStart`, taken by `LectureRecorder.startOf` | Information Expert, Creator (the recorder has the notebook) |
| The pages around the visible one without the text of their slide when the start holds it ("slide above"), with the notes of the student | `LecturePrompts.pagesText` | Information Expert |
| Which leading parts of a prompt may be cached | `ModelPrompt.cachedParts` (the number of leading parts) | Protected Variations (the backends that cannot cache ignore it) |
| The cachedContents of the API: create, extend, delete | `GeminiClient.createCache`, `extendCache`, `deleteCache` | Information Expert (the REST of Gemini) |
| The cache of a lecture: created at the first request whose start is long enough, reused while the start is the same, extended at every request, deleted when the lecture stops; none after a refusal | `GeminiPromptCache` (`drawview-lecture`), one for each recording, given to `GeminiBackend.generate` | Pure Fabrication, Indirection (the recorder does not know the API) |
| How often the notes are asked | `AssistantSettings.notesSeconds` (300 by default, 60–600) | Information Expert |
| Transcribing every chunk as it comes, gathering the transcripts until the interval, asking the notes with them as one; the outline at every request when the interval is longer than `OUTLINE_INTERVAL_MS` | `LectureRecorder` (`analyzeChunks`, `askNotes`) | Controller |
| The transcript said before a request, without the one it carries | `Lecture.memory(elapsed, transcriptBeforeMs)` | Information Expert |
| The setting in the panel of the assistant | `drawview-lecture-ui` | Controller |
| Reading the settings stored before the new field | `AssistantSettingsStore`, a test on a stored file | Protected Variations |

### The request

```
system      instructions + rule on the slides             ┐
part 1      course                                        │ cached, the same for the whole lecture
part 2      slides of the notebook (page number, text)    ┘
part 3      memory: outline, notes already suggested, end of the previous transcript
part 4      notebook: the visible page and those around, notes of the student, 2 images
part 5      what to do, transcript of the interval
```

The slides of the start are those of the notebook when the lecture begins
(`NotebookSource.allPages`). A page added later is in part 4 when it is near the visible one, as
today; the start does not change during a lecture, so the cache is never rebuilt.

### The cache

`GeminiPromptCache.prefixFor(prompt, model)` hashes the system and the cached parts. On the first
request it creates a `cachedContents` with them (`ttl` 10 minutes) and remembers its name; a
request with the same hash sends the other parts with `cachedContent` and extends the `ttl`; a
different hash (another model, another notebook) creates a new one and deletes the old. A refusal
(too short, a model without cache) is remembered: the requests of that lecture go whole. When the
recording stops, the cache is deleted; one left behind by a crash expires by itself.

Estimate for 90 minutes, 18 requests, 3.8 Flash: start of ~6,300 tokens (creation $0.005,
storage $0.005, read 18 times $0.009), variable parts ~4,400 tokens with the 2 images ($0.06),
output ~620 tokens a request ($0.04): about $0.12.

### Without online transcription

What goes, all of it in `drawview-lecture` unless said:

| What | Where |
|---|---|
| The speech models of Gemini, live and by chunks, and the transcription by a general model | `GeminiLiveTranscriber`, `GeminiLive`, `GeminiSpeechTranscriber`, `GeminiPromptTranscriber`, `StreamingTranscriber`, `StreamSpeech`, `LecturePrompts.transcriptionOnlyPrompt`, the `audioTranscriptionConfig` of the client, `speechLocales` |
| The audio in the request of the notes and of "I missed something" | `RequestAudio`, `AudioSpeech`, `TranscriptionMode.WITH_NOTES`, the transcript field of the answer, `PromptPart.Audio` |
| The recording uploaded for the final review | the upload in `Lecture.writeReview`, `PromptPart.File` in `finalReviewPrompt`, `GeminiClient.uploadFile` |
| The choice of the transcription | `TranscriptionMode`, `onlineTranscription`, `sendsAudio`, `SharedData.AUDIO`; in `drawview-lecture-ui` the choices of the transcription and of its model, their strings and screenshots |

What stays, and who does what:

| Responsibility | Class (module) | GRASP |
|---|---|---|
| Transcribing a chunk, a recording, a lecture again: whisper.cpp only | `Transcribers` (`forUpdates`, `forRecording`), `WhisperTranscriber`, `LectureRetranscriber` | Information Expert, Polymorphism stays in `Transcriber` (a fake in the tests) |
| Whether this device follows a lecture with the models | `AssistantSettings.consistent(canTranscribe)`: where whisper does not run, `generateHere` is false | Protected Variations |
| Reading a task of transcription saved with Gemini | `LectureTask.TRANSCRIPTION` kept, its provider read as `LOCAL` | Protected Variations |
| What the panel says is sent to Google | `sentToGoogle`: the transcript and the notebook, never the audio | Information Expert |

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | The bench: `NotesBench` (the notes of a whole lecture, a configuration each: Gemini on the transcript or the audio, Gemma 4 E4B on LiteRT-LM with the search of the notebook; `-cached`, `-medium`, `@<seconds>`; the notebook without the assistant, `BenchLecture.untouched`), the price with the cached tokens, a cap on the dollars; the grader `tools/notes-bench/grade_notes.py`; the results of the Starting point | Done |
| 2 | The prompt: `LectureStart` and `slidesText` (80,000 characters at most), the rule on the slides, `pagesText` without the slides of the start, `ModelPrompt.cachedParts`, the recorder taking the start for Gemini only; tests in `LecturePromptsTest` (the start the same across requests, with the notebook closed and in a catch-up; the slides not repeated, a page added keeping its text; nothing cached without slides; the bound); the bench on 3.8 Flash every 5 minutes: 16 notes, none wrong, 143,824 prompt tokens, 0 cached, $0.142 | Done |
| 3 | The cache: `GeminiClient` (`createCache`, `extendCache`, `deleteCache`), `GeminiPromptCache` (one for each recording, 10 minutes past every request, a start refused sent whole, a start changed cached again and the old cache deleted, a cache gone sent whole then created again), `GeminiBackend.generate(prompt, model, cache)` counting the tokens of the creation, the recorder deleting it at the end (stop, end of a lecture followed, abandon); tests with the `MockEngine` (`GeminiPromptCacheTest`); ABI recorded on purpose (synthetic properties of the serializers only). The bench twice, 3.8 Flash every 5 minutes: 106,890 cached tokens of about 150,000, $0.068 and $0.076 for 72 minutes (about $0.09 for 90 minutes with the storage); 10 and 13 notes, 7 and 12 that add, none wrong, 54% and 67% of the questions | Done |
| 4 | How often: `notesSeconds` in the settings (300 by default, 60–600); the recorder transcribing every chunk and gathering the transcripts until then, the last ones asked when the recording stops; the transcript said before a request without the one it carries; "I missed something" unchanged; the setting in the Lecture section of the settings, hidden when the model hears the audio, its strings in English and Italian; tests: a settings file of today read with 5 minutes, the bounds, the gathering in `LectureRecorderTest` (three tests of the transcription ask the notes every minute); the screenshot `lecture-settings-lecture`; the ABI of `AssistantSettings` recorded on purpose | Done |
| 5 | Without online transcription: the transcribers of Gemini (live, by chunks, by a general model) and the Live API removed, `Transcribers` making whisper.cpp only; the notes, "I missed something" and the review on the transcript only (no `RequestAudio` in a request, no upload of the recording, no transcript in the answer of the notes); `PromptPart.Audio` and `File`, the Files API and the speech config of the client removed; `TranscriptionMode`, `sendsAudio`, `SharedData.AUDIO` removed, `LectureTask.TRANSCRIPTION` always whisper; `consistent` making the phones record only; the offers estimated on the transcript (`GenerationOffer.estimatedTokens`); the settings without the choice of the transcription, the retranscription on this computer only, the strings; tests: settings saved with Gemini Transcribe live, by chunks and with the notes read as whisper, a phone recording only, a lecture of 2026-10-15 read as it was, the recorder and the review on the transcript; the benches without the audio; screenshots `lecture-settings`, `lecture-idle`; the ABI recorded on purpose. The WebSockets of Ktor are part of its core: no dependency to drop | Done |
| 6 | Checks: `RecorderBench` (`-Pdrawview.bench.parts=recorder`), the lecture of 2026-10-15 through `LectureRecorder` itself: its recording heard as from a microphone (faster than live, so the chunks were merged as when the recorder falls behind), whisper.cpp large-v3-turbo q5 on this computer (25 segments), the notes of Gemini 3.8 Flash every 5 minutes with the explicit cache: 12 requests, 126,765 prompt tokens of which 85,512 cached, 7,388 output tokens, $0.065 for 72 minutes; 17 notes, 9 that add to the slides, none wrong, 72% of the questions. The desktop application started on a scratch data directory, without errors. The checks of CLAUDE.md | Done |

## Decisions

Taken alone, revertible:

* **Gemini 3.8 Flash stays the model of the live notes.** 3.5 Flash-Lite would cost about $0.05
  a lecture, but it answered 46% of the questions and restated the slides in most of its notes.
* **Five minutes by default.** The notes arrive later; "I missed something" covers the moment.
  The lectures recorded before keep their notes; the settings saved before read five minutes.
* **With the audio in the request** (Gemini transcribes and suggests at once), the notes still
  come at every update: they bring the transcript.
* **The transcript, not the audio**, goes to the notes: the audio costs twice as much for worse
  notes.
* **Low thinking**, as today for the live notes.
* **The start holds the slides when the lecture begins**, never rebuilt during it: those of the
  notebook at the first request of Gemini with the notebook open, in its order, up to 80,000
  characters (about 20,000 tokens).
* **A ttl of 10 minutes extended at every request**, rather than one for the whole lecture,
  whose length is unknown.
* **The review needs a transcript**: a lecture without one (recorded where whisper does not run,
  not transcribed yet) is transcribed on a computer first.
* **The benches lost their audio configurations** (`-audio`, Gemma 4 E4B hearing the audio, the
  transcription by the generation models): the library sends no audio any more.
* **No start for the local models**: they cache nothing and read a small context; their prompts
  stay as today.
* **The grader is a script** (`tools/notes-bench`), with the questions of the eval set of
  `feat/lecture-eval`.

## Left for later

* Whisper on the phones and the tablets (whisper.cpp with Metal on iOS, on Android), so that
  they write the notes without a computer.
* The outline less often than every request, measured on the bench.
* A bench of more lectures, and with the images of the slides rendered from the PDF (the images
  of the pages recorded hold the notes of the assistant).
* Flash-Lite with a prompt of its own.
* The Batch API (half the price) for the lectures imported.
* The prices of 2027.
