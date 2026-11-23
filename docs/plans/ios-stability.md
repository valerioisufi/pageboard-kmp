# The crashes and the lag of the iPhone

PencilNotes crashes on the iPhone in landscape when a menu or the settings of a tool open, crashes
now and then while texts are laid out, and lags on a document of a library shared with other
devices. The causes come from the crash logs of the iPhone (7 between November 14 and 23) and from
the simulator. Nothing stored and nothing that travels between the devices changes.

The responsibilities are assigned following the GRASP principles.

## Starting point

* **Landscape.** The menus of the design (`DesignMenu`, the "more" menu of the editor) and the
  settings of the tools (`ToolPopover`) are `DropdownMenu`s of Material: a `Column` with
  `width(IntrinsicSize.Max).verticalScroll(...)` in a popup. On the iPhone in landscape, a menu
  taller than the window is measured with an infinite height, and `verticalScroll` stops the
  application ("Vertically scrollable component was measured with an infinity maximum height").
  Reproduced on the simulator with the "more" menu of the editor; a short menu (the one of the
  library) opens.
* **Texts.** `ComposeTextTileAdapter` lays the texts out with a `TextMeasurer` under its own lock,
  off the main thread (the text boxes of `TextBoxes.adapterChanged` on `Dispatchers.Default`, the
  tiles on their workers). On iOS the text engine of Compose keeps the styles of Skia in one global
  `WeakKeysCache`, a `HashMap` that is not safe between threads and that every lookup sweeps: the
  main thread laying out the text of the interface at the same time throws a
  `ConcurrentModificationException` (`ParagraphBuilder.makeSkTextStyle`), which ends the application.
* **The heads of a shared library.** With a folder or the library shared (a space), every change
  of a document (`store.documents`, after 2 s of quiet) makes `SyncEngine.tellHeads` hash the
  version vector of **every** document of the space, two queries each, and `recomputeWanted` does it
  again right after. A library of N documents costs about 4N queries after every pause of the pen,
  on the database the editor saves into.
* **The presence.** In a shared document `CollaboratorsLayer` sends the presence up to 30 times a
  second, on the main thread: `toPresence` copies the whole stroke being drawn into a list of boxed
  floats every time (the engine sends only the inputs not sent yet). Each one also makes
  `markWorkedOn` start a coroutine that waits 30 s: about a thousand of them while writing.

## Requirements

1. No menu, popover or sheet stops the application in landscape: it is as tall as the window lets
   it, and scrolls.
2. The texts are laid out on iOS without racing the text of the interface: no crash, and the main
   thread never waits for a worker.
3. The heads of a space cost a few queries, whatever the number of its documents, and are computed
   once per change.
4. The presence costs on the main thread what the stroke gained since the last one, and one timer
   per document worked on.
5. Nothing changes in what is stored or what travels: no migration, no protocol change.

## Design

* **Protected Variations (the menus).** One modifier of `pageboard-ui`, `menuHeightLimit()`, bounds
  the height of a popup menu to the window (`LocalWindowInfo.containerSize`) less its margins;
  `DesignMenu` and `ToolPopover` give it to `DropdownMenu`, so the `verticalScroll` of Material is
  always measured with a bounded height, whatever the popup of the platform gives it.
* **Pure Fabrication (the thread of the texts).** `onTextLayoutThread { }`, `expect` in
  `pageboard-ui`: on iOS it runs its block on the main thread (directly there, else with
  `dispatch_sync` on the main queue), elsewhere in place. `ComposeTextTileAdapter` runs each of its
  public calls in it, **before** taking its lock: a worker waiting for the main thread holds no lock,
  and the main thread never waits for a worker, so it cannot deadlock. Each call is one text: the
  main thread draws frames between them.
* **Information Expert (the version vectors).** `OperationLog.versionVectors(documentIds)` gives the
  vectors of many documents at once, by default one by one; the Room store reads them in two queries
  (by chunks of the limit of SQLite), without a change of the schema.
* **Information Expert (the heads).** `SyncEngine.headsOf` reads the documents of the store and the
  trash once, then the vectors of the space at once; `tellHeads` gives the heads it computed to
  `recomputeWanted` instead of computing them again.
* **Controller (worked on).** `markWorkedOn` keeps when each document was last worked on; one job per
  document waits until it is left alone for 30 s, instead of one job per presence.
* **Information Expert (the presence).** `CollaboratorsLayer` keeps the values of the stroke being
  drawn and adds only the inputs that came since the last presence.

## Phases

| Step | Content | Status |
|------|---------|--------|
| 1 | The menus and the popovers bounded to the window (no crash in landscape) | planned |
| 2 | The texts laid out on the main thread on iOS | planned |
| 3 | The heads of a space: the vectors at once, computed once per change; one timer per document worked on | planned |
| 4 | The presence built from what the stroke gained | planned |

## Decisions

Taken alone, revertible:

* **The main thread lays the texts out on iOS**, rather than a copy of the text engine apart: the
  cache of Compose is internal and global. The tiles of texts cost the main thread their layout and
  drawing, one text at a time.
* **The desktop keeps its workers.** Its cache is a `WeakHashMap`, not safe between threads either,
  but no crash of it is known; see "Left for later".
* **`versionVectors` is a new member with a default**, so the stores of others keep building
  (`checkKotlinAbi` records it).

## Left for later

* The desktop (JVM) races the same cache of Compose (`WeakHashMap`): `onTextLayoutThread` on the
  thread of Swing, once a crash or a corruption shows it.
* The tiles of texts drawn off the main thread on iOS again, when Compose makes its cache safe
  between threads (to report to JetBrains).
* Why the popup of Compose gives an infinite height in landscape on iOS (to report to JetBrains with
  a reproduction).
* The dialog "New note" does not scroll in landscape on the iPhone, and its button "Create note"
  cannot be reached.
* A measure of the lag on the iPhone with Instruments, before and after phases 3 and 4.
