# Lecture assistant: the transcription model chosen, the review on request

The lecture assistant (`drawview-lecture`) records a lecture, transcribes it, suggests the notes
missing from the notebook and reviews the whole lecture at its end. This plan lets the student
choose how the lecture is transcribed — whisper.cpp on the computer, a speech model of Gemini
(Gemini 3.5 Transcribe, live or a chunk at a time), a general Gemini model — and whether the final
review is written at the end of every lecture or only when asked; a lecture already recorded can
be transcribed again with another model.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* The final review always starts by itself: when the recording stops (`LectureRecorder.stop`)
  and at the end of an import (`LectureImporter.run`), after the handwriting of the pages seen not
  transcribed yet (read by the recorder).
* Two transcriptions only, told apart by `if (settings.sendsAudio)` in the recorder, the importer
  and the assistant: "online", the audio in the request of the live suggestions (Gemini transcribes
  and suggests at once; in an import a general model transcribes every chunk with a prompt), or
  "local", whisper.cpp. Local suggestions force the local transcription
  (`AssistantSettings.consistent()`).
* Gemini 3.5 Transcribe (August 2026, preview) has two models: `gemini-3.5-transcribe-live`
  (Live API over a WebSocket: PCM at 16 kHz in 100 ms pieces, interim and final transcripts,
  sessions of 10 minutes at most, custom vocabulary, `SMART` mode) and `gemini-3.5-transcribe`
  (`generateContent` with `audioTranscriptionConfig`, up to an hour per request).
* Ktor's `WebSockets` plugin is part of `ktor-client-core`, supported by the OkHttp and Darwin
  engines.

## Design

| Responsibility | Class (module `drawview-lecture`) | GRASP |
|---|---|---|
| When the review is written (end of the lecture or on request), which transcription follows from the models chosen | `AssistantSettings.reviewAtTheEnd`, `AssistantSettings.transcription` (`TranscriptionMode`) | Information Expert |
| Write the final review: the handwriting not transcribed yet (notebook open), the local models of the lecture freed, the review | `FinalReviewer` | Pure Fabrication, High Cohesion |
| The review itself, its state, a second request refused | `Lecture.runFinalReview(provider, prepare)`, `Lecture.claimReview` | Information Expert |
| The handwriting of a notebook, read once, shared by its recording and its reviews | `HandwritingReader`, one per document created by `LectureAssistant` | Creator, Information Expert |
| Use cases "review on request", "transcribe again" | `LectureAssistant.writeReview`, `LectureAssistant.retranscribe` | Controller |
| Transcribe a chunk | `Transcriber`: `WhisperTranscriber`, `GeminiSpeechTranscriber`, `GeminiPromptTranscriber` | Polymorphism, Protected Variations |
| Transcribe while the lecturer speaks | `StreamingTranscriber`: `GeminiLiveTranscriber` over a `LiveConnection` (`GeminiLiveClient`, a WebSocket) | Polymorphism, Indirection |
| The transcriber of the settings, for a recording and for a whole recording | `Transcribers` | Creator, Low Coupling |
| What was said in an update: its audio, its transcript chunk by chunk, the live transcript | `SpeechSource`: `AudioSpeech`, `ChunkSpeech`, `StreamSpeech` | Polymorphism |
| Transcribe a whole recording (import, transcribe again), chunks in parallel, in order | `RecordingTranscriber` | Pure Fabrication |
| The words to recognize (course context, slides on screen) | `LecturePrompts.vocabulary` | Information Expert |

* `FinalReviewer` gathers what was spread between the recorder (the handwriting at the end) and
  the lecture (the review): the recorder calls it when the review is written at the end, the
  assistant when the student asks for it, with the notebook open in the editor (the panel lives
  there). The material is completed inside `runFinalReview` (`prepare`), while the review is shown
  as running: the button cannot be pressed twice.
* The mode of the transcription is computed in one place from the models chosen (the model id of
  the transcription, the one of the live suggestions): the recorder, the importer and the
  settings ask it, instead of testing `sendsAudio`.

## Phases

1. **The review on request**: `reviewAtTheEnd`, `FinalReviewer`, one `HandwritingReader` per
   notebook, the Review tab and the switch in the settings.
2. **The transcription models a chunk at a time**: `TranscriptionMode`, `GeminiSpeechTranscriber`,
   `GeminiPromptTranscriber`, `Transcribers`, `SpeechSource`, `RecordingTranscriber` in parallel,
   the language spoken for every model, the settings.
3. **Gemini 3.5 Transcribe Live**: `GeminiLiveClient`, `GeminiLiveTranscriber` (sessions changed
   at a pause, reconnection with the audio sent again), `StreamSpeech`, the interim transcript.
4. **Transcribe again** a recorded lecture, from the menu of the lecture.
5. README.

## Progress

| Phase | State |
|---|---|
| 1. The review on request | done |
| 2. The transcription models a chunk at a time | done |
| 3. Gemini 3.5 Transcribe Live | done (to verify against the real API) |
| 4. Transcribe again | to do |
| 5. README | to do |
