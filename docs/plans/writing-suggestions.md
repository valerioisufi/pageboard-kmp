# Writing suggestions in PencilNotes

The suggester of PencilNotes for the hook of the text box (`docs/plans/text-box-hook.md`): while the
student writes in a text box, the end of the word being written, taken from the words of their
notes, of the slides of the document and of its lectures. It is the plan named "Completion and
suggestions while writing" in `CLAUDE.md`, and "The writing suggestions from the notes" left for
later in `docs/plans/search.md`. This document starts from the code as it is (main at 5cc1b54a,
2026-11-14).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **The hook is done** (`docs/plans/text-box-hook.md`, phases 1-4): `EditorExtension.textSuggestions`
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
4. **What the notes say is already read** by the search (`drawview-search`, `docs/plans/search.md`): its
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
| The texts of a document, or of all, as the index keeps them | `SearchIndex.texts(documentId, kinds)` (search) | Information Expert |
| The vocabulary of the library and of each document, built off the main thread, rebuilt when the index changes | `NoteVocabulary` (search) | Pure Fabrication, Low Coupling |
| The completion of a word for the text being written: the layers weighted, the rules of the caret | `NoteWordSuggester : TextSuggester` (search-ui, internal) | Polymorphism, Protected Variations |
| The suggester given to the editors, the vocabulary created when the search starts | `ContentSearch.editorExtension`, `ContentSearch.start` (search-ui) | Creator |
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

* Built from the index (`SearchIndex.texts`, the `chunks` table as written), on
  `Dispatchers.Default` in a scope of the application, never by the caller: asked for words not
  built yet, it answers what it has (`null` the first time) and builds them (requirement 4). While
  they are built the suggester answers from the document open only.
* **The library**: every chunk but the names of the documents (`TITLE`), built again when the index
  changed (`SearchIndex.revision`) and at least `LIBRARY_REFRESH` (2 minutes) went by: the
  indexer writes the document being written as it changes, and the library need not follow it.
* **A document**: what it has besides its typed texts (`PDF`, `HANDWRITING`, `LECTURE_TRANSCRIPT`,
  `LECTURE_NOTES`), built again whenever the index changed; its typed texts are read by the
  suggester from the editor, as they are now.
* Memory: one map of words for the library (some tens of thousands of words, a few MB) and those of
  the 8 documents asked for last (`MAX_DOCUMENTS`).

### The suggestion (`NoteWordSuggester`)

* The prefix is the word before the caret, at least 3 characters starting with a letter
  (`MIN_PREFIX`), not followed by a letter, not a command of LaTeX (`\alp`), not in a formula, in
  code or in the address of a link left open on its line (the `$` and the backticks counted).
* Score of a word: `3 × count in the document open + 2 × count in its indexed content + 1 × count
  in the library`. The document open is counted from `context.document` (its texts read on the
  main thread, counted on `Dispatchers.Default`), the box being written without the word being
  written. The form suggested is that of the first layer that has the word.
* A word is suggested only when it adds at least 2 letters, and when it is clearly the best: its
  score at least `MIN_SHARE` (0.5) of the scores of all the words with that prefix, and seen at
  least twice (`MIN_COUNT`), and no other word has the same score. One word of the notes is no
  suggestion.
* The letters typed keep their case; typed all in capitals, the rest of the word is too.
* The answer is the rest of the word, with no space after it: the student goes on as they like.

### The switch

* `DrawPreferences.areTextSuggestionsEnabled: Boolean = true`: a new key of the JSON, absent from
  the preferences written before, read as `true` (a test reads the JSON of the current version).
* `DrawViewController.areTextSuggestionsEnabled` holds it (as the stylus only mode) and tells it
  in its preferences; `TextBoxEditor` asks only when it is on.
* The command "Writing Suggestions", checked, after "Hold to Draw Shapes" in the menu of the editor
  and in the View menu of the menu bar, in the seven languages of `drawview-ui`; it is there only
  when an extension suggests (`LocalTextSuggestions`), so the sample without the search and an
  application without suggester show nothing new.

## Phases

| Phase | Content | Status |
|-------|---------|--------|
| 1 | `WordCounts` in `drawview-search`: the words of a text without the notation (formulas, code, addresses), the form written most often by lower case, `withPrefix`; tests (Italian accents, capitals, apostrophes, formulas); ABI of `drawview-search` | Done |
| 2 | `SearchIndex.texts` and `NoteVocabulary`: built off the main thread, a document again when the index changed, the library at most every 2 minutes, the 8 documents asked for last kept; tests on an in-memory index; ABI of `drawview-search` | Done |
| 3 | `NoteWordSuggester` (internal) and `ContentSearch.editorExtension` in `drawview-search-ui`: the prefix, the layers, the thresholds, the vocabulary created by `start`; tests of the prefix and of the completion; ABI of `drawview-search-ui` | Done |
| 4 | The switch: `DrawPreferences.areTextSuggestionsEnabled`, read from the JSON of today as on (test), `DrawViewController.areTextSuggestionsEnabled`, the text box not asking when off, the checked command "Writing Suggestions" (only with an extension that suggests) in the menu of the editor and the menu bar, in the seven languages; ABI of `drawview-canvas` and `drawview-ui` | Done |
| 5 | PencilNotes: `search.editorExtension` first in `PencilNotesServices.editorExtension`; `WritingSuggestionsTest` (search-ui) from the slides indexed to Tab in the text box, and nothing when turned off; the desktop app started on a scratch data directory. Writing in it by hand, and tuning the thresholds on real notes, left to the maintainer | Done |

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
