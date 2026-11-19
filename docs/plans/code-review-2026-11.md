# Review of the codebase, November 2026

A review of `main` on 2026-11-15 (`d9176cf5`), on the Windows machine of the maintainer: `jvmTest`,
`checkKotlinAbi`, and a reading of what another device sends, of the stores and of the paths.
What it found, what was fixed, and what is left.

## Starting point

* The extension of a resource received in a `ResourceChunk` went as it came into the path of the
  file written: with separators and `..` a device could write anywhere on a Windows desktop
  (Windows resolves `..` even past a folder that is not there; Android and iOS do not).
* The klib dump of `drawview-lecture` had `markdownOf` out of its place, so `checkKotlinAbi`
  failed everywhere; the dumps of the Compose modules held the lambdas Compose keeps in
  `ComposableSingletons`, named by hashes of the compiler.
* `feat/windows-paths` (done 2026-10-18) was not merged, and `4bcfa788` (2026-11-13) had made
  `dataDirectory()` the parent of the resources on Windows without moving what was kept inside
  them: the lectures, the speech and local models, the name of the device were no longer found.
* `PersistenceQueue` logged a write that failed and left it out: a database busy for a moment
  lost a change of a document for good.
* The opening and the saving of a document, and the initialization of the formulas, caught the
  cancellation of their coroutine with every other `Throwable`.
* A text markup whose rectangles cannot be read was left out of its page without a word.

## Requirements

1. What another device names never becomes part of a path.
2. `checkKotlinAbi` fails for changes of the API only.
3. Nothing kept on a Windows device is lost from sight (see `docs/plans/windows-paths.md`).
4. A write that fails for a moment is not lost.
5. Nothing that travels changes: `PROTOCOL_VERSION` stays 1, `ProtocolFormatTest` as it is.

## Design

* **Protected Variations: the extension received is checked where the download is made**
  (`ResourceDownloads.add`, `receivedExtension`): kept when it is up to 8 ASCII letters and
  digits, `bin` otherwise, before any `ResourceFiles` sees it.
* **The ABI validation leaves out `**ComposableSingletons**`** (`KmpLibraryConventionPlugin`).
* **`DesktopDataLayout`** (`docs/plans/windows-paths.md`, phase 4) moves `models`, `litert-cache` and
  `search` too.
* **Information Expert: the queue tries a failed write again** (`PersistenceQueue.execute`) after
  50 ms, 200 ms and 1 s, before the writes after it: the writes of the stores are upserts,
  inserts that ignore what is there, or transactions.
* `CancellationException` rethrown before the catch-all blocks of `DocumentEditorViewModel`,
  `EditorSessions` and `LatexEngine.initialize`.

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | The extension of a resource received (`fix/resource-extension`) | Done, in `main` |
| 2 | The ABI dumps (`fix/abi-dumps`) | Done |
| 3 | `feat/windows-paths` rebased, phase 4 there | Done |
| 4 | The persistence queue tries a failed write again (`fix/persistence-retry`) | Done |
| 5 | The cancellation let through, the text markups not read logged (`fix/cancellation`) | Done |

## Decisions taken by me (revertible)

* A received extension that is not safe becomes `bin` (not a refusal): a file of a device on
  Windows without an extension, in a folder with a dot, is still received.
* Three retries, 1.25 s at most per write: a write that always fails (a bug) delays the ones
  after it by that much, and is then left out as before.
* The cancellation scan looked for catch-all blocks whose `try` calls a `suspend` function; the
  others (decoding, native calls) are left as they are.

## Left for later

* **A save that fails is told to nobody.** After the retries, `PersistenceQueue` and
  `EditorSessions.close` only log it. The user should be told (a message of the editor, and the
  document kept open until it is written): it needs a hook of the UI, its own plan.
* The sync tests on the iOS simulator, `compileKotlinIosSimulatorArm64` and the klib dumps of the
  Compose modules: on the Mac (they cannot run on Windows).
* `fix/lecture-eval-windows-ids`, for `feat/lecture-eval`, to be picked there.
