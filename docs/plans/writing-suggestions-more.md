# Writing suggestions: the lecture, a local model, the lines of a text

Stage G of the roadmap of `docs/plans/text-editor-enhancements.md`: what
`docs/plans/writing-suggestions.md` left for later. It starts from `main` after stage F
(`docs/plans/import-and-clipboard.md`).

The responsibilities are assigned following the GRASP principles.

## Starting point

1. **The hook** (`EditorExtension.textSuggestions`, `TextSuggester`): asked 400 ms after the last
   change, with the caret at the end of the text, never in a table or a block of code; the answer
   is shown after the end, lighter (`GhostText`), taken by Tab, → or a tap.
2. **The suggester of the notes** (`NoteWordSuggester`, `pageboard-search-ui`): the end of the word
   being written, from the words of the document, of its index (the slides, the handwriting, the
   lectures indexed) and of the library; three letters typed, two added, a clear winner seen twice.
3. **The lecture being recorded** (`LectureAssistant.activeRecorder`): its transcript grows every
   chunk (`Lecture.transcript`), but its words reach the suggestions only once the lecture is
   indexed, after it ends.
4. **The local models** (`DeviceModels`, `LoadedModels`, `docs/plans/local-models.md`): Gemma on the
   device answers the tasks of the assistant; nothing asks it while a text is written.
5. **A suggestion in the middle of a text**: never asked; the ghost text is added after the end.

## Requirements

1. **The words of the lecture being recorded**: while a lecture of the document is recorded, the
   word being written is completed from its transcript so far, as from the notes (three letters
   typed, two added), a word heard once enough: it was just said.
2. **The next words from a local model**: with a switch of the settings of the assistant (off by
   default), after a word ended, the model of the device suggests the next few words of the
   sentence, in the language and the style of the text; never while a lecture is recorded (the
   model and the transcription would compete), never when no generation model is installed.
3. **At the end of a line in the middle of a text**: a suggestion is asked and shown where the
   caret ends a line, not only the text.
4. **No note changes**: a setting added reads as off from the settings of before (tested); nothing
   that travels changes; every platform, the 7 languages, light and dark.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The word being written before the caret | `WritingWords.prefixAt` (`pageboard-search`, moved from `NoteWordSuggester`) | Information Expert |
| The words of the lecture being recorded | `LectureWordSuggester` (`pageboard-lecture-ui`), `WordCounts` | Information Expert, Low Coupling |
| The next words from the model | `ModelWordsSuggester` (`pageboard-lecture-ui`), `AssistantSettings.writingFromModel` | Pure Fabrication, Protected Variations |
| A suggestion at the end of a line | `GhostText.insert` (`pageboard-ui`), `TextBoxEditor` | Information Expert |

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | The words of the lecture being recorded; tests | Done |
| 2 | The next words from a local model, its switch; the setting read from before; tests | Pending |
| 3 | A suggestion at the end of a line in the middle of a text; tests, screenshots | Pending |

## Decisions

Taken alone, revertible:

1. **The suggesters of the lecture come after the one of the notes** (`+` of the extensions): the
   notes win when both know the word; the model last, the slowest.
2. **The model writes at most a few words** (16 tokens, temperature 0, the paragraph before the
   caret only), kept loaded two minutes after the last request: a suggestion is a hint, not a text.
3. **A word of the lecture wins when it was heard more often than any other** with the same start, a
   tie suggesting nothing; it is read from the transcript of the lecture of the document being
   recorded only (`LectureAssistant.activeRecorder`), so a lecture of another notebook says nothing.

## Left for later

- Suggestions in handwriting: completing a word written with the pen needs the strokes read as
  they are written and a way to show the end of a word in ink, a plan of its own.
- Learning from the suggestions refused.
- Tuning the model's suggestions on real notes, left to the maintainer.
