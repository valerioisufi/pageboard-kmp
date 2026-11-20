# The workflows after the rename

After the rename DrawView → Pageboard (`docs/plans/pageboard.md`) and the repository made public
(2026-11-20), no workflow of `valerioisufi/pageboard-kmp` ends green on `main`. This plan finds
the cause of each failure and fixes what the repository can fix.

The responsibilities stay where `docs/plans/ci-time.md` put them (High Cohesion, Protected
Variations): `check.yml` checks every change, `native.yml` the native engines, `screenshots.yml`
the screenshots in their Docker image, `release.yml` the releases.

## Starting point

The runs of `main` (0480bf58) and of the first pull requests of Dependabot, on 2026-11-20:

| Workflow | Result | Cause |
|---|---|---|
| All, up to 2026-11-19 | failed in seconds | "The job was not started because recent account payments have failed or your spending limit needs to be increased": the minutes of the private repository. Gone since it is public |
| `check.yml`, job `check` | failed ([run](https://github.com/valerioisufi/pageboard-kmp/actions/runs/44764476821)) | `LanSyncTest.aDeviceStartedAgainIsConnectedAgainWithoutBeingFoundAgain`, its 30 s timeout, on `macos-26` while cargo builds iroh beside it. It passed in the run of the pull request of Dependabot on the same code, and here three times out of three: a test that fails now and then, not the rename |
| `check.yml`, job `check` | failed ([run](https://github.com/valerioisufi/pageboard-kmp/actions/runs/44761228576)) | `:pageboard-canvas:checkKotlinAbi`: in the klib dump, edited by hand on Windows in the rename, `FormulaOp.TextElement` (once `DrawText`) stayed where the old name sorted. Hidden on `main` by the failure above, Gradle stopping at the first one |
| `check.yml`, job `check` | cancelled ([run](https://github.com/valerioisufi/pageboard-kmp/actions/runs/44760936664)) | a newer push on `main` (`cancel-in-progress`): as meant |
| `check.yml`, job `Android applications` | green | |
| `screenshots.yml`, `verify` | failed ([run](https://github.com/valerioisufi/pageboard-kmp/actions/runs/44760936529)) | 10 screenshots of `pageboard-explorer` and `pageboard-sync-ui` show the old names: "DrawView" in the bar of the library, a `drawview://join` invitation. The rename changed the strings and not the references, which are recorded in Docker only |
| `native.yml`, Desktop windows-x86_64 | failed ([run](https://github.com/valerioisufi/pageboard-kmp/actions/runs/44761228341)) | `TranscriptionQueueTest.theOtherDevicesAreToldWhenATranscriptionStartsNotWhileItWaits`: the test takes an import out of the queue as soon as it is `WAITING`, but the import is `WAITING` a moment before it is put in the queue (`LectureImporter` sets it, then `AssistantWork.enqueueTranscription`); on a slow runner `cancelTranscription` finds nothing to cancel. `aLectureIsTakenOutOfTheQueueWaitingOrRunning` has the same race. The other five desktop jobs, Android and iOS are green |
| `release.yml` | never run (no tag `pageboard-v*`) | the environment `maven-central` has no secrets (`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_KEY_PASSWORD`): a release would fail at publishing. A setting for the maintainer, not the repository |
| CodeQL (default setup) | green | |

Nothing in `.github/` keeps a `drawview` name, a `-Pdrawview.*` property or the name `DrawView-KMP`.

Not caused here: the Dependabot pull request of the Gradle dependencies (20 updates) fails its
`Android applications` job; it is that update's own to fix. The Node.js 20 warnings of the `@v4`
actions of `check.yml` go with the Dependabot pull request of the actions.

## Requirements

* Every workflow that can pass on a branch passes: `check.yml`, `screenshots.yml`, `native.yml`.
* No change to the sync protocol, to a stored format or to the public API (the dump is reordered,
  not changed).
* The references of the screenshots are recorded in their Docker image only (label
  `record-screenshots`), never on Windows.
* A failure that comes back says why in one run.

## Design

* **The klib dump in its order** (Information Expert: the dump is what `updateKotlinAbi` writes on
  macOS): `TextElement` moved between `SetStrokeWidth` and `Translate`, the diff of the run applied
  as it is.
* **The tests wait for what they act on** (Protected Variations against the speed of the runner):
  `TranscriptionQueueTest` waits until an import is in the queue (`waitsInTheQueue`: `WAITING`
  and its `generating` job set, both on the thread of the state), not only converted.
* **`check.yml` says every failure in one run**: `--continue` on its Gradle steps, and the XML
  results of the tests kept as an artifact on a failure, as `native.yml` does. The next time
  `LanSyncTest` fails, the message and the line are there.
* **The references recorded on the runner**, by the label on the pull request.

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | This plan | Done |
| 2 | The klib dump of `pageboard-canvas` in order; `TranscriptionQueueTest` waits for the queue | Done, the test green here (Windows) |
| 3 | `check.yml`: `--continue`, the results of failed tests kept | Done |
| 4 | The references of the screenshots recorded on the runner (label `record-screenshots`) | To do |
| 5 | The runs of the pull request green, linked here | To do |

## Decisions

* `LanSyncTest` is not changed: one failure in two runs on the runner, none here, and no message
  to tell which wait timed out (the line is the shared `awaitSame`). The results kept by phase 3
  will say it if it comes back. Revertible.
* `--continue` in `check.yml` makes a failing run go on to the end (a few more minutes of macOS)
  in exchange for every failure at once. Revertible.

## Left for later

* The secrets of `maven-central` before the first tag `pageboard-v*` (the maintainer).
* `LanSyncTest` if it fails again, with the results of the run.
