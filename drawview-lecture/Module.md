# Module drawview-lecture

The lecture assistant without its screens: it records a lecture (`LectureRecorder`, the audio
codecs), transcribes it (Gemini, whisper.cpp on the desktop), follows it with the models (Gemini,
Ollama) and suggests what is missing from the notes, then reviews it. `LectureAssistant` is its
Controller; a recording sees the notebook through a `NotebookSource`. Its state is Compose
snapshot state (the runtime only: no UI).
