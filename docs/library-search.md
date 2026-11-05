# The search inside the library

The search finds what it should ([docs/search.md](search.md)), but it is not part of the library:
it sits on top of the navigation, which keeps moving underneath without showing anything. This plan
makes a search a state of the library, entered and left in one way, and fixes what follows from it.
It starts from the code as it is (main at 7ef50513, 2026-11-05).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

`FileExplorerViewModel.shownFiles` shows the results whenever `searchQuery` is not empty, whatever
the section and the folder are. The section and the folder go on changing behind them, and each
command decides alone what to do with the query: `showFolder` clears it, `showSection` and
`backFolder` do not.

Each problem below says how it is known:

* **Seen**: reproduced on the library through a temporary test driving the interface, 1440 dp wide,
  with a fake `LibrarySearch` (not committed);
* **Read**: from the code, not reproduced.

### The navigation under a search

| # | Problem | Known |
|---|---|---|
| N1 | **The reported problem.** Recent, Shared and Trash with a query: the section is chosen in the pane and the content stays the results. | Seen |
| N2 | The up button inside a folder with a query: the folder changes (`Physics` to none) and the content stays the results. Its shortcut (⌘↑) is the same command. | Seen |
| N3 | The Trash with a query shows notes of the library under the title "Trash", with the commands of the library. | Seen |
| N4 | Without a pane (a phone, a tablet) only the folders whose name matches stay on screen, so N2 is the only way out besides clearing the query. | Read |
| N5 | A note whose details were opened puts the whole library in selection mode: clicking a folder then adds it to the selection (`[Motion, Physics]`) instead of opening it. Not only during a search. | Seen |
| N6 | The filter "Notes" or a tag hides the folders of the current one, so the notes in sub-folders cannot be reached while it is on. It also stays on across the folders and the sections. | Seen |
| N7 | A search inside a folder or a section covers the whole library: inside `Physics`, `limit` shows `Limits` of `Analysis` and `Limit cycles` of the root. | Seen |

A folder of the pane and the card of a folder found do work: both go through `showFolder`, which
clears the query (Seen). They clear it behind the port, though (see S1).

### The state of the search

| # | Problem | Known |
|---|---|---|
| S1 | `showFolder` and `navigateBack` write `searchQuery` without telling `LibrarySearch`: the port keeps the old query (`port='limit'` with `query=''`). An answer being generated goes on, and typing the same query again does not search again. | Seen |
| S2 | The selection survives the navigation and the query: an item selected in the root stays selected inside another folder and behind a query that hides it, and the actions run on what is no longer visible. | Seen |
| S3 | `selectedItems` holds copies: an item renamed from the pane of the details, or by another device, keeps its old name there. | Read |

### The rules that look at the current folder instead of the item

| # | Problem | Known |
|---|---|---|
| R1 | Renaming a result checks the name against the current folder: `Motion` (in Physics) cannot be called `Limit cycles`, free there but taken in the root, and it can be called `Waves`, taken in Physics (what the store does then was not checked). | Seen |
| R2 | `isValidMove` compares the target with the current folder: from the root, a search or Recent, a note in a folder cannot be moved to Home. | Seen |
| R3 | A folder made during a search is not shown (it does not match) and nothing says where it went. | Seen |
| R4 | "Select all" is enabled by `currentDirectoryFiles` but selects `shownFiles`: disabled in an empty folder with results on screen. | Read |
| R5 | "Export folder…" and the actions without a selection run on the folder behind the results, which is not on screen. | Read |

### What the results say

| # | Problem | Known |
|---|---|---|
| P1 | The title and the subtitle stay those of the folder ("Home", "7 notes · sorted by name") above the results, and the pane highlights that folder. Nothing says these are results, nor from where. | Seen |
| P2 | A result does not say where it is, against the requirement of docs/search.md: two notes with the same name look the same. A note can also be found by the name of its folder (the title chunk holds the path), with nothing explaining why. | Read |
| P3 | The order button does nothing on ranked results, and the subtitle still says "sorted by name". | Seen |
| P4 | "No results" says "The search looks at the names", false with the search of the contents. It says nothing when the index is still being built. | Read |
| P5 | The card of "Ask your notes" stays empty without a model and without Gemini; its documentation promises an indexing status it does not have. | Read |
| P6 | A snippet opens its note even in selection mode. | Read |

### The field and the scroll

| # | Problem | Known |
|---|---|---|
| F1 | The field is in the first item of the grid on a phone and in the widest layout: scrolled away, the query is out of sight and the field is not composed, so ⌘K cannot reach it (`runCatching` hides the failure). The temporary test could not scroll far enough to confirm it. | Read |
| F2 | One scroll position for everything: new results or a folder open at the position of what was shown before. | Read |
| F3 | Back from the editor the position is lost: `gridState` lives inside `if (session == null)`, and so is the place in the results. | Read |
| F4 | The focus stays in the field after opening a folder (the keyboard stays up on a tablet). The Search key of the keyboard does nothing (`KeyboardActions()`). | Read |

### The search of the contents

| # | Problem | Known |
|---|---|---|
| C1 | The results of the previous query stay until the next ones arrive: with the ids of "limit", `waves` shows `Limit cycles` too. No sign that a search is running. | Seen |
| C2 | `searchNow` runs in the scope of a `LaunchedEffect` (the main thread) and `SearchIndex.searchWords` and `searchVectors` are synchronous under the lock the indexer writes with: the typing can stall, up to 50,000 vectors compared at every key. Not measured. | Read |
| C3 | No wait between the keys: every key is a search and an embedding. | Read |
| C4 | Every change of the index runs the search again: while indexing, the results move under the pointer, and a slow search can be cancelled again and again. | Read |
| C5 | The vectors have no threshold: with the embedding model every query returns 50 chunks, so "no results" practically never appears and the last results are unrelated. | Read |
| C6 | An error of `engine.search` (not a cancellation) is not caught in the collector, and it takes down the scope of the indexer with it, or the application. | Read |
| C7 | Without the content search a name matches only as one contiguous string with its accents: `universita` does not find `Università`, `analisi limiti` does not find `Limiti di analisi`. | Read |

## Requirements

1. **A search is a state of the library**, with one way in and one way out. While it lasts the
   library says it is showing results, and from where.
2. **Going somewhere always goes there.** A section, a folder or a card leaves the search and shows
   what was asked; nothing changes out of sight.
3. **Back leaves the search and returns where it was**, the folder and the section kept.
4. **What is done on a result follows the result**: its folder for the names and the moves, not the
   folder behind it.
5. **The selection only holds what is on screen.**
6. **The query stays in sight and within reach of its shortcut**, wherever the list is scrolled.
7. **Typing never stalls**, and the results shown are always those of the query written.
8. Nothing stored changes, and nothing that travels: no migration, `PROTOCOL_VERSION` stays.
9. **A search looks where the library is**: the folder shown with its sub-folders, or the section
   shown, and can be widened to the whole library with one click. In the root the two are the same.

## Design

| Responsibility | Class | GRASP |
|---|---|---|
| The query: the only writer, which tells the port; whether a search is on; leaving it | `FileExplorerViewModel.setQuery`, `isSearching`, `leaveSearch` | Controller, Information Expert |
| Going somewhere: the section and the path changed together, the search left, the selection cleared | `FileExplorerViewModel.navigate` (`showFolder`, `showSection`, `enterFolder`, `backFolder` through it) | Controller, High Cohesion |
| The details of an item, apart from the selection | `FileExplorerViewModel.detailsItem` | Information Expert, High Cohesion |
| The folder a rule looks at: the one of the item | `validateFileName`, `isValidMove` | Information Expert |
| The selection as keys, the items read from the library | `selectedKeys`, `selectedItems` derived | Information Expert |
| What the header says: results, a folder, a section | `LibraryTitle`, `librarySubtitle`, `LibraryNavigationPane` | Polymorphism on the state |
| The query always in sight, the position kept | `ExplorerScreen`, `DocumentLibrary` | Low Coupling |
| Not stalling, not showing old results, surviving an error | `ContentSearch`, `SearchEngine` | Controller, Protected Variations |

### The state

* `searchQuery` gets one writer, `setQuery`, called by `search`, `navigate` and `navigateBack`, which
  always calls `contentSearch?.onQueryChange` (S1). `isSearching` replaces the checks spread around
  (`isNotBlank`, `isNotEmpty`, `trim`).
* `navigate(section, path)` clears the query and the selection and moves together (N1, N2, S2).
  Back is not a navigation: it leaves the search and keeps the place (requirement 3).
* In the Trash the field filters the trash by name, with its own placeholder (N3).
* `SearchScope` (internal: `Here`, `Library`) says where a search looks (N7). A search starts at
  `Here`: the folder shown with its sub-folders at any depth, or the notes of Recent or Shared. A
  chip above the results ("In Physics" / "Whole library") changes it, and is not shown in the root.
  Leaving the search brings it back to `Here`.
* `folderIdsInScope`, derived from the folders, gives what `shownFiles` keeps of the names found and
  of the ranked documents (Information Expert).
* A change of the query leaves out of the selection what is no longer shown (S2).
* A plain click during a search or with the details open opens the item. Only a long press, ⌘ or
  Ctrl, Shift enter selection mode; the pane of the details shows `detailsItem`, kept apart (N5).
* A filter keeps the folders of the folder shown, and goes back to All when the section changes
  (N6). During a search it narrows the results as it does now.

### Inside the view model, the rules

* `validateFileName` looks among the siblings of the item renamed, the current folder for a new
  item (R1). `isValidMove` is true when one of the items is not already in the target and no folder
  goes into itself (R2).
* A folder made during a search leaves the search and shows the folder it went to (R3).
* "Select all" follows `shownFiles`; the actions on the folder shown are disabled during a search
  (R4, R5).

### What is shown

* During a search the title is "Results for “…”", the subtitle counts the results and says where
  they were looked for; no up button (P1, N2). The folder stays highlighted in the pane only while
  the search looks inside it.
* Under each result, the path of its folder from `folderPaths` (P2).
* Ranked results have no order button (P3).
* "No results" says only what is true of every search; `ContentSearch.headerContent`, which knows,
  says how many notes are still to be read (P4). It draws nothing when it has nothing to offer (P5).

### The field and the scroll

* The search leaves the grid: fixed above the list in all layouts (F1). The title and the filters
  scroll with the content.
* The scroll goes back to the top when the query, the section or the folder change, with
  `requestScrollToItem` (F2). `scrollToItem` remeasures at once: the temporary test, scrolling that
  way, failed with "Unsupported concurrent change during composition" in most runs while the
  thumbnails were loading. Whether it can happen in the application is not known; this avoids it.
* `gridState` moves up to `DocumentLibrary`, outside the editor branch (F3).
* `navigate` clears the focus through the screen, and the Search key hides the keyboard, inside
  `SearchField` with no change to its signature (F4).

### The search of the contents

* `onQueryChange` drops the old results at once when the query changes: the names found come first,
  the ranked ones replace them. `isSearching` of the port's header shows a small progress (C1).
* `SearchEngine.rankChunks` runs off the main thread (C2); a wait of 150 ms between the keys (C3).
* A change of the index searches again at most every 5 seconds and never cancels a running search
  (C4).
* Errors of a search are caught in the collector and shown as no ranked results, the names still
  found (C6).
* The names match without accents and word by word, with `searchWordsOf` where the module can reach
  it, or its equivalent in the explorer, which does not depend on the search (C7).
* The scope reaches the index (N7). `SearchEngine.search` takes the 50 best chunks of the whole
  library and groups them, so filtering afterwards loses what is in the folder when the best chunks
  are elsewhere. `SearchIndex` and `SearchEngine` take the documents to look in, and `LibrarySearch`
  receives them with the query (a default member, so an implementation keeps building). "Ask your
  notes" looks in the same documents, and says so.
* A threshold on the vectors, measured (C5): the bench `SearchBench` on the 30 searches of phase 0,
  plus searches with no answer in the notes. A word match always passes. The value goes in this
  plan.

## Phases

Each phase is a commit on `feat/library-search`, with its tests. Phases 1 to 4 stay inside internal
classes, so no ABI changes. Phase 5 changes the public API of `drawview-search`, `drawview-explorer`
and `drawview-search-ui`, recorded with `updateKotlinAbi` and said in the commit: the dumps of the
modules with Compose come out empty on Windows, so they are recorded on the Mac.

1. **One way in and out** (N1 to N7, S1 to S3): the state, `navigate`, the scope with its chip, the
   details apart from the selection, the filter. The ranked results are filtered in the view model
   until phase 5. The temporary test becomes `LibrarySearchNavigationTest`, and `LibraryViewTest`
   gets the rules.
2. **The rules follow the item** (R1 to R5): tests on `FileExplorerViewModel`.
3. **What the results say** (P1 to P6): strings in every language of the resources; screenshots.
4. **The field and the scroll** (F1 to F4): screenshots of the three layouts recorded again, the
   application on a scratch directory, the tablet.
5. **Typing and fresh results** (C1 to C4, C6, C7, the scope in the index): `ContentSearchTest` and
   `ContentSearchLifecycleTest`; a measure of the typing on a large index, before and after.
6. **The threshold of the vectors** (C5): the measure, then the value.

## Progress

| Phase | State |
|---|---|
| 1. One way in and out | done |
| 2. The rules follow the item | done |
| 3. What the results say | done |
| 4. The field and the scroll | to do |
| 5. Typing and fresh results | to do |
| 6. The threshold of the vectors | to do |

## Checks

* `./gradlew jvmTest checkKotlinAbi` and the builds of CLAUDE.md at every phase; on this Windows
  computer `checkKotlinAbi` of the modules with Compose already fails on main, so compare it with
  main.
* `verifyRoborazziJvm` of explorer and search-ui from phase 3. The references come from the Mac:
  they are recorded there, or their differences on Windows are told apart from the real ones.
* The application on a scratch directory only:
  `JAVA_TOOL_OPTIONS="-Ddrawview.dataDir=<scratch dir>" ./gradlew :pencilnotes-desktop:run`.

## Decisions

All revertible, taken alone and waiting for the approval of this plan:

* **Any navigation leaves the search**: the simplest rule, already that of the folders of the pane.
* **A search starts from where the library is**, sub-folders included, and widens with a chip (asked
  by the student, 2026-11-05; the sub-folders and the chip are revertible). Without the sub-folders
  a search in a folder of folders would find nothing.
* **In the Trash the field filters the Trash by name.** The other choice, going back to the library
  when typing, would leave the Trash without a word.
* **The field fixed above the list on a phone too**: it moves under the bar, above the title, which
  changes the order of the design (docs/ui-design.md). The cost is about 60 dp of the list.
* **A filter keeps the folders of the folder shown.** The design said "no folder"; it is what hides
  the notes in the sub-folders.
* **No order on ranked results**: their order is the relevance.

## Left for later

* A "Relevance" entry in the order menu, and the order of the results chosen by the student.
* The words found highlighted in the names and the snippets: it needs their ranges in
  `LibrarySearchMatch`, a public class, so a recorded ABI change.
* The filter applied to the whole library, not only to the folder shown.
* Texts still hard-coded in `ContentSearch` ("p. 3", "Unknown error").
* The search inside the open document ([docs/search.md](search.md)).

## Risks

* Phase 1 changes what a click does while the details are open, on every wide library: a habit
  changes, and the screenshots with the pane open must be looked at one by one.
* A fixed field takes room on a phone in landscape.
* A note outside the folder is not found until the search is widened: the empty state offers the
  whole library, so that "no results" is not read as "it is not in my notes".
* The threshold depends on the corpus: too high, it hides the paraphrases that are the reason for
  the vectors. The measure of phase 0 was made on one student's notes, on the Mac.
* The temporary test used a fake port and only the wide layout for the navigation: the problems
  marked Read may turn out larger or smaller on the tablet.
