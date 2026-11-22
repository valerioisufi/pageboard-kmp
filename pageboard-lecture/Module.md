# Module pageboard-lecture

The lecture assistant without its screens: it records a lecture (`LectureRecorder`, the audio
codecs), transcribes it (Gemini, whisper.cpp on the desktop), follows it with the models (Gemini,
Ollama) and suggests what is missing from the notes, then reviews it. `LectureAssistant` is its
Controller; a recording sees the notebook through a `NotebookSource`. Its state is Compose
snapshot state (the runtime only: no UI).

## On Android

The library adds to the manifest of the application the permissions `RECORD_AUDIO`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` and `POST_NOTIFICATIONS`, and
`LectureRecordingService`, a foreground service of type `microphone` that keeps the recording going
with the screen locked. The application asks for the microphone (and the notifications) at run
time, and declares the foreground service of type microphone in the Play Console.
