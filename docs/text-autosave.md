# Text saved while written

The text of a box being written is kept only in the editor until the box is closed: when the
process dies before, the text is lost. Seen on the Android tablet on 2026-11-06: an
`adb install -r` over the running application dropped a whole line typed in a box still open, and
the box came back with the text it had before. The same happens when Android kills the application
in the background, or when the desktop application is closed with a box open. This document gives
the starting point, the requirements, the design and the plan. It starts from main at 9535abe7,
2026-11-06.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

Read in the code; nothing was measured yet.

1. **The text being written lives in `TextEditorManager` only** (`drawview-canvas`): `value`, the box
   (`boxX`, `boxY`, `boxWidth`), the style, the links of its chain. Nothing reaches the document
   until `finishEditing` submits one change (`AddElements` for a new text, `UpdateTexts` for a
   changed one, `emptiedChange` for one emptied, `flowChain` for a chain), or until the text
   overflows its page and `flowChain` submits the chain and opens the editor again.
2. **What is submitted is durable soon after.** `DocumentSession.submit` notifies the
   `ChangePersister`, which enqueues the effects and the operation on the `PersistenceQueue`
   (one transaction of the store per operation, `DocumentPersistence.transaction`). The queue runs on
   its own scope at once; nothing waits for the box to close.
3. **The others see the text as a draft only.** `reportDraft` sends it to the presence of the other
   devices at every change; it travels with the connection and is never stored.
4. **No signal of the platform ends the writing:**
   * desktop: `exit()` in `pencilnotes/desktopApp/.../main.kt` (and the sample) runs
     `DrawViewSessions.flushAll()`, which writes what was submitted: the box open was not;
   * `DocumentEditorViewModel.closeSession` (a window closed, an activity finished, another document
     opened) flushes and disposes the controller without `finishEditing`;
   * Android and iOS: nothing is done on `onStop` or on going to the background.
5. **A kill gives no signal at all.** `adb install -r`, a force stop, a crash, or Android killing a
   process already stopped run no callback: only what was already written survives.
6. **The history** (`HistoryManager`) records the inverse of every local change; `beginGroup` /
   `endGroup` make one entry of the changes of a block. It is not stored.
7. **The field is keyed on `sessionId`** (`TextBoxEditor`): a new session makes the field again, with
   its focus, its keyboard and its own undo.

## Requirements

1. **The text written survives the process**: killed, crashed, replaced by an install, stopped by
   the system, or closed, the document has what was written up to a couple of seconds before.
2. **Writing is not interrupted**: no new session of the editor, no keyboard hidden, no caret or
   selection moved, no flicker of the box.
3. **One step to undo per writing**, as today: the saves made while writing are undone with it, in
   one step.
4. **Chains stay whole**: a save while writing keeps the links of the box (`next`, `continuesLine`),
   and the flow of the chain on closing works as today.
5. **Nothing stored or sent changes format**: the saves are the changes the editor already submits
   (`AddElements`, `UpdateTexts`), so no migration and no change of the protocol. A text changed
   meanwhile on another device is kept as a copy, as today.
6. **Bounded cost**: no save per key; nothing saved when nothing changed; a blank text is not saved.
7. **The signals of the platforms save at once**: Android stopping the activity, iOS going to the
   background, a window or the desktop application closed.

## Design

The text is saved **in the document, as it is written**: a *checkpoint* submits the change that
`finishEditing` would submit for the box alone, and the editor stays open on it. The document stays
the one place where a note lives; nothing new is stored, nothing has to be restored.

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The change of the box as it is now, submitted without closing the editor; what the next change is made from (`editedFrom`, the element) taken from the document | `TextEditorManager.saveWriting` (`drawview-canvas`, public) | Information Expert, Controller |
| When to save: some time after the last change, at most some time after the first one not saved | `WritingSaver` (`drawview-canvas`, new, internal) | Pure Fabrication, High Cohesion |
| The saves of a writing undone in one step | `HistoryManager` entry kept open (`openEntry` / `closeEntry`, internal) | Information Expert, Protected Variations |
| Undo and redo of the canvas while writing: the writing ended first | `DrawViewController.undo` / `redo` | Controller |
| The writing of an open document saved now, and ended when the document is closed | `EditorSession.saveWriting`, `DocumentEditorViewModel.closeSession`, `DrawViewSessions.saveWriting` (`drawview-session`) | Controller, Indirection |
| The stop of the screen of the editor (Android, iOS) | `EditorSessionScreen`, a `LifecycleEventEffect(ON_STOP)` | Indirection, Low Coupling |
| The desktop applications closing | `exit()` of PencilNotes and of the sample | Controller |

### The checkpoint

`saveWriting()` does what `finishEditing` does for the box alone, then keeps the session:

* a blank text, a draft equal to what was last saved, or a document read only: nothing;
* a new text: `AddElements` of the draft with its identifier (`newTextId`, already the one the
  presence reports); the element is hidden from the tiles before it is added, and becomes
  `activeTextItem` (its page `itemPageIndex`), so that the next save and the closing update it;
* a changed text: `UpdateTexts` of the draft from `editedFrom`, when it differs; moved to another
  page, with `ReorderElements.onTop`, as `finishEditing` does, and `itemPageIndex` follows it;
* then `editedFrom` becomes the text as the document has it now (its versions with the new stamp),
  so that the next save is made from it, and a text changed meanwhile on another device is kept as a
  copy once, not at every save.

What it does not touch: `sessionId`, `value`, the caret, `caretRequest`, the hidden element, the
defaults of the texts (updated on closing), the chain. **The chain is not flowed by a save**: the box
is saved with its links as they are, a state the chain already has between two edits; the closing
flows it as today (`flowChain` compares with `editedFrom`, now the last save). An overflow while
writing flows at once, as today.

The editor ignores its own change in `onChange` (a text moved to another page is placed again,
which must not close the editor as a text removed elsewhere would).

### When to save

`WritingSaver` is told of every change of the draft (where `reportDraft` is called: text, style,
box) and saves **2 s after the last change, and at most 10 s after the first change not saved**, so
that writing without pauses is saved too. It runs on the scope of the controller (the main thread of
the editor), with `delay`: tests drive it with virtual time. It stops when the writing ends.

Cost: one operation per pause in the writing, of the size of the box's text (an `UpdateTexts`
carries the whole text), and the same operation sent to the devices the note is shared with, who see
the text saved as they already see it written. The log grows with it until it is compacted, as for
any other change.

### One step to undo

The first save opens an entry of the history; the inverses of the saves and of the closing
(`finishEditing`, `emptiedChange`, `flowChain`) go into it, and it is closed when the writing ends
(closed, cancelled, the session reset). A flow while writing opens the editor again on the next box
of the same writing: the entry stays open across it, so that a writing that flowed is undone in one
step too (today it takes two). A new text saved and then emptied leaves an entry that undoes to
nothing, which is dropped.

The entry is a mechanism of its own, not `beginGroup` across the user's time: a group left open by a
bug would swallow every later change. Undo or redo on the canvas while writing ends the writing
first, so that what is undone is the writing (today it undoes the change before and leaves the box
open over it).

### The signals of the platforms

* **Closing a document** (`DocumentEditorViewModel.closeSession`: a window closed, an activity
  finished, another document opened): `finishEditing` before the session is flushed and disposed, on
  the main thread.
* **Android and iOS**: `EditorSessionScreen` saves the writing on `Lifecycle.Event.ON_STOP`
  (`lifecycle-runtime-compose`, already in the catalog). On Android it comes when the activity stops,
  which is before the system may kill it in the background. On iOS, Compose Multiplatform should send
  it when the application goes to the background: to be verified in phase 3; if it does not, the
  SwiftUI apps call `DrawViewSessions.saveWriting()` on `scenePhase == .background`.
* **Desktop exit**: `exit()` calls `DrawViewSessions.saveWriting()` (main thread) before
  `flushAll()`, in PencilNotes and in the sample.

A kill gives no signal: the last save of `WritingSaver` is what survives, at most 2 s (10 s of
writing without pauses) behind.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | `saveWriting` and the history entry: tests in `drawview-canvas` of a new text and a changed one saved with the editor open (same `sessionId`, value and caret), one undo for the writing (with saves, with a flow), a blank text not saved, a new text saved then emptied, a chain saved with its links and flowed on closing, a text changed on another device kept as one copy, a box moved to another page, undo while writing | to do |
| 2 | `WritingSaver`: tests with virtual time (saved 2 s after the last change, at most 10 s while typing, nothing when unchanged, stopped at the end); **the kill test**: in `drawview-session`, a document of a `KeyValueDocumentStore` written in a box, the time past the save, the process dropped without closing anything, a new store on the same storage opens the document and finds the text; the same on the Room store on the JVM | to do |
| 3 | The signals: `closeSession` ends the writing, `ON_STOP` in `EditorSessionScreen`, `DrawViewSessions.saveWriting` in the exits of the desktop apps; iOS verified (or `scenePhase`); tried on the tablet (`adb install -r` over a box open) and on the desktop app on a scratch directory | to do |

Each phase: the checks of `CLAUDE.md`, `updateKotlinAbi` for the new public functions
(`TextEditorManager.saveWriting`, `EditorSession.saveWriting`, `DrawViewSessions.saveWriting`), said
in the commit. Nothing changes in the stored formats or in the protocol: no migration, no
`ProtocolFormatTest` recorded again.

## Decisions

To be taken by the user:

1. **Where the text is saved.** Proposed: **in the document**, as the changes the editor already
   makes (this design). The alternative is a **draft kept apart**, on this device only (a preference
   or a table of the store, written at every change), applied when the document opens again: nothing
   more travels and the log does not grow, but it is a new stored format with its own migration, a
   restore path, and the clearing of the draft cannot be in the same transaction as the change it
   becomes, so a kill between the two applies the draft again over the text saved (a copy, or the
   text of a flowed chain doubled). The other devices would not have the text until this one opens
   the note again.
2. **The times**: 2 s after the last change, at most 10 s. Shorter loses less on a kill and writes
   more operations.

Taken alone, revertible:

3. **A writing that flowed is one step to undo**, where today it is two.
4. **Undo and redo of the canvas while writing end the writing first.**
5. **Saved on `ON_STOP`, not on `onSaveInstanceState`**: the text is in the document, not in a state
   of the activity; `ON_STOP` comes on every way an activity leaves the screen.
6. **No save on `ON_PAUSE`**: a dialog or the split screen pauses the activity while the user still
   writes in it.

## Left for later

- A text removed on another device while it is written here closes the editor and drops what was
  written (`onChange`, `cancelEditing`); with the saves, what was saved stays in the removed text,
  in the trash. Keeping it as a new text is a decision of its own.
- The undo of the field itself (Ctrl+Z inside the box) is not touched.
