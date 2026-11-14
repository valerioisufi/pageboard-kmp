# Writing suggestions in PencilNotes

The suggester of PencilNotes for the hook of the text box (`docs/text-box-hook.md`): while the
student writes in a text box, the end of the word being written, taken from the words of their
notes, of the slides of the document and of its lectures. It is the plan named "Completion and
suggestions while writing" in `CLAUDE.md`, and "The writing suggestions from the notes" left for
later in `docs/search.md`. This document starts from the code as it is (main at bbedf36c,
2026-11-14).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The hook is done** (`docs/text-box-hook.md`, phases 1-4): `EditorExtension.textSuggestions`
   takes a `TextSuggester` (`suspend fun suggest(context, writing): TextSuggestion?`). The editor
   asks 400 ms after the last change, with the caret at the end of the text, never in a table cell
   or a block of code; it drops an answer whose text changed meanwhile; Tab, the right arrow at the
   end or a tap accepts it, as one edit.
2. **The sample** suggests the end of a word from the words of the texts of the document open
   (`WordSuggestions.kt`): the most frequent word that starts with the two or more letters before
   the caret. It reads every text of the document at every request, and knows nothing of the
   slides, the handwriting or the other notes.
3. **PencilNotes gives no suggester**: `PencilNotesServices.editorExtension` combines the lectures,
   the sharing, the export and the external models; none suggests.
4. **What the notes say is already read** by the search (`drawview-search`, `docs/search.md`): its
   index (`SearchIndex`, SQLite) keeps, for every document, chunks of text by kind (`TITLE`,
   `TEXT`, `PDF` for the text layer of the slides, `HANDWRITING` read by the model, the lectures
   from `lectureAssistant.searchSource`), kept current by `SearchIndexer` while no lecture is
   recorded. The FTS5 table folds the words (`remove_diacritics 2`, lower case), so its vocabulary
   cannot be shown as written ("perche" for "perché"); the `chunks` table keeps the text as written.
5. **The editor preferences** are `DrawPreferences` (`drawview-canvas`), JSON in the `preferences`
   table of Room (`ignoreUnknownKeys`, every field with a default). The switches of the editor
   (snap, shapes held) are checked commands of `EditorCommands`.

## Requirements

1. In PencilNotes, while writing a word in a text box, the end of the word is suggested when the
   notes make it likely; nothing is shown otherwise (no noise on every word).
2. The words come, in this order of weight: the document being written (as it is now, the box
   being written included), the indexed content of the same document (its slides, its handwriting,
   its lectures), then the rest of the library.
3. A word is suggested as written in the notes (accents, capitals of a name); the letters already
   typed are never changed (a suggestion is only inserted).
4. The answer is fast: within the 400 ms pause, the first request of a document included; reading
   the index never blocks the main thread.
5. Nothing leaves the device; no model is needed (it works on every device, during a lecture too).
6. The student can turn the suggestions off; the choice is kept with the other preferences of the
   editor, and the preferences written before read as today (suggestions on).
7. Nothing stored by the notes nor sent between devices changes; the search index keeps its
   format (it is only read).
8. The libraries stay headless where they are (`drawview-search` has no Compose), explicit API,
   the ABI recorded.

## Design

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The words of a text with their counts, as written; the words of a prefix | `WordCounts` (search) | Information Expert, Pure Fabrication |
| The texts of a document, or of all, as the index keeps them | `SearchIndex.texts(documentId)`, `SearchIndex.allTexts()` (search) | Information Expert |
| The vocabulary of the library and of each document, built off the main thread, rebuilt when the index changes | `NoteVocabulary` (search) | Pure Fabrication, Low Coupling |
| The completion of a word for the text being written: the layers weighted, the rules of the caret | `NoteWordSuggester : TextSuggester` (search-ui) | Polymorphism, Protected Variations |
| The suggester given to the editors | `ContentSearch.editorExtension` (search-ui) | Creator |
| Whether the editor asks at all | `DrawPreferences.areTextSuggestionsEnabled` (canvas), the command "Writing Suggestions" (ui) | Information Expert, Controller |
| The suggester in every editor of PencilNotes | `PencilNotesServices.editorExtension` | Creator |

### The words (`WordCounts`)

* A word is a run of letters and digits with at least one letter and 3 characters at least
  (`dell'algoritmo` gives `dell` and `algoritmo`, `e-mail` gives `mail`).
* The words of the notation are not words: the formulas (`$…$`), the code, the addresses of the
  links, found by `RichTextParser` (`drawview-model`).
* A word is counted under its lower case, with the form written most often, so "Perché" at the
  start of a line and "perché" count together and "perché" is suggested; "Dijkstra" keeps its
  capital. The accents are not folded: "perche" is another word.
* `withPrefix(prefix)`: the words that start with the prefix, ignoring the case, found by a binary
  search on the sorted words. The completion (the rest of the word, the case of the letters typed
  kept) is the suggester's.

### The vocabulary (`NoteVocabulary`)

* Built from the index (`SearchIndex.texts`), on `Dispatchers.Default`, at the first request and
  again when the indexer has written (`SearchIndexer.progress` going idle with new documents): the
  library once (the counts of all the documents together), and each document asked for its own.
* While it is built the suggester answers from the document open only (requirement 4): the first
  words of a session are completed without the slides, never late.
* Memory: one map of words for the library (some tens of thousands of words, a few MB) and the
  maps of the documents opened in this run.

### The suggestion (`NoteWordSuggester`)

* The prefix is the word before the caret, at least 3 letters (`MIN_PREFIX`), not in a formula or
  a link (the parse of the source says where the caret is).
* Score of a word: `3 × count in the document open + 2 × count in its indexed content + 1 × count
  in the library` (the document open counted from `context.document`, the box being written
  without its last word, as the sample does).
* A word is suggested only when it adds at least 2 letters, and when it is clearly the best: its
  score at least `MIN_SHARE` (0.5) of the scores of all the words with that prefix, and seen at
  least twice (`MIN_COUNT`). One word of the notes is no suggestion.
* The answer is the rest of the word, with no space after it: the student goes on as they like.

### The switch

* `DrawPreferences.areTextSuggestionsEnabled: Boolean = true`: a new key of the JSON, absent from
  the preferences written before, read as `true` (a test reads the JSON of the current version).
* `TextBoxEditor` asks only when it is on (through the controller, as the snap). The command
  "Writing Suggestions", checked, next to "Snap" in the menu of the editor, in English, Italian and
  the other languages of `drawview-ui`.

## Phases

| Phase | Content | Status |
|-------|---------|--------|
| 1 | `WordCounts` in `drawview-search`: the words of a text without the notation (formulas, code, addresses), the form written most often by lower case, `withPrefix`; tests (Italian accents, capitals, apostrophes, formulas); ABI of `drawview-search` | Done |
| 2 | `SearchIndex.texts` / `allTexts` and `NoteVocabulary`: built off the main thread, rebuilt after the indexer writes, the document open meanwhile; tests on an in-memory index; ABI of `drawview-search` | Proposed |
| 3 | `NoteWordSuggester` and `ContentSearch.editorExtension` in `drawview-search-ui`: the prefix, the layers, the thresholds; tests on documents and on an index; ABI of `drawview-search-ui` | Proposed |
| 4 | The switch: `DrawPreferences.areTextSuggestionsEnabled`, read from the JSON of today as on (test), the editor not asking when off, the checked command with its strings; ABI of `drawview-canvas` and `drawview-ui` | Proposed |
| 5 | PencilNotes: `search.editorExtension` in `PencilNotesServices.editorExtension`; the desktop app run on a scratch data directory with a PDF and a text, the suggestions checked by hand | Proposed |

Each phase is one commit on `feat/writing-suggestions`, after the checks of `CLAUDE.md`
(`consumer-checks` from phase 2, the screenshots of `drawview-ui` in phase 4).

## Decisions

Taken alone, revertible:

* **Words of the notes, no model**, in this plan: instant, on every device, during a lecture too,
  nothing loaded in memory. A model is the next step (see "Left for later").
* **The suggester lives with the search** (`drawview-search-ui`), since the index is the one place
  that knows the slides, the handwriting and the lectures; an application without the search has
  no suggestions from the notes (the sample keeps its own).
* **Three letters at least, two letters added at least, a clear winner seen twice**: few
  suggestions, mostly right. The thresholds are constants, tuned by hand in phase 5.
* **No space after the word accepted.**
* **On by default**, the switch in the menu of the editor (not in a settings screen).
* **The order of `+`**: the suggester of the search goes first in `PencilNotesServices`; no other
  extension suggests today.

## Left for later

* **The next words from a local model** (Gemma 4 E2B/E4B through `LoadedModels`): the end of the
  sentence in the style of the notes. It needs the model loaded while writing (2.5-3.6 GB, one
  large model at a time on a tablet, never during a lecture), a budget of latency, and a switch of
  its own: a plan of its own once this one is used.
* The words of the lecture being recorded (its transcript before it is indexed).
* Suggestions in the middle of a text, in a table cell, of several words.
* Learning from the suggestions refused.
