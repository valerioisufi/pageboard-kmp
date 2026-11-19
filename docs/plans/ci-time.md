# The time of the GitHub workflows

`native.yml` takes up to 37 minutes for each push on `main`, whatever the push changes. This plan
makes a workflow run only when it has something to check, makes a run reuse what was already
compiled, and removes what two workflows do twice. What a release publishes stays built from
nothing.

The work was first done on `feat/ci-time` (October and November 2026), measured on its runs until the
spending limit of the account stopped them, and left 222 commits behind `main`. This plan brings it
onto `main` of 2026-11-19 (66517658), redone on what `main` has now rather than replayed.

The responsibilities are assigned following the GRASP principles, where they apply to a build:
High Cohesion, Low Coupling, Information Expert and Protected Variations.

## Starting point

On `main` of 2026-11-19:

* `check.yml` runs on `macos-14` and builds the two debug APKs there. On the runs of November it
  failed in about 2 minutes: `brush_paint.cc:465: call to 'CalculateMinimumRequiredVersion' is
  ambiguous`, the clang of Xcode 15.4 building the iOS libraries of Ink. On `feat/ci-time` it was
  green on `macos-26`. `release.yml` uses `macos-14` too, in its three macOS jobs.
* `native.yml` runs on every push on `main` and on every pull request, with no filter on paths, and
  without any cache of cargo or of the C++ compilers (`ccache not found` in the logs).
* The desktop build of the runners is already fixed on `main` (`ed5c3f83`: `build_desktop.sh`
  without a `local.properties`, `std::modf` for the libstdc++ of GCC 13).
* The other failures of the first complete run of `native.yml` are not fixed on `main`:
  * `TrackpadGesturesTest` on Linux x86_64 and Windows: two tests scroll by units of the platform
    and expect pixels of macOS (it also fails on the Windows machine of the maintainer);
  * `LectureRecorderTest.withoutAKeyTheRecordingGoesOn`, a timeout on Apple silicon: it waits for
    the notes of the default interval instead of 60 s, as the other tests do;
  * a test of `LectureSharingTest` waiting for a state that the file already had before the request;
  * `downloadOpusSource` on Linux aarch64, a failed TLS handshake with `downloads.xiph.org`;
  * `No space left on device` in the Android job (1 and 5 November).
* The ABIs of a debug build: `main` has `debugAbis()` (`AndroidAbis.kt`), read from
  `android.debug.abis` in `local.properties`. The runners have no `local.properties`, so a workflow
  cannot ask for one ABI.
* `screenshots.yml` is new since `feat/ci-time` and runs in a Docker image: nothing here touches it.
* The GitHub Actions minutes of the account are exhausted while the repository is private. Once it is
  public, the standard runners cost nothing.

## Requirements

* A push that cannot change the result of `native.yml` does not start it.
* Every change is still built and tested somewhere: `check.yml` always runs, and it keeps assembling
  the APKs when `native.yml` is filtered out.
* A change of the runners (a new compiler, a new Xcode) is found within a week even when nobody
  pushes native code.
* `main` keeps checking all the platforms and all the ABIs. Reducing them is for `check.yml` only.
* A release is built from nothing, without any of these caches.
* No default of the build changes for those who use the libraries or build them locally.
* Nothing here touches stored formats, the sync protocol or the published APIs.

## Design

| Responsibility | Where | GRASP |
|---|---|---|
| Decide if a change concerns the native engines | `paths` of `native.yml` | Information Expert: the workflow knows what it checks |
| Check every change: tests, public API, compilation, APKs | `check.yml` | High Cohesion |
| Check the native engines: every platform, every ABI | `native.yml` | High Cohesion |
| Publish | `release.yml`, unchanged in what it builds | Low Coupling: it depends on no cache |
| Reuse Rust and C++ compilations | the composite action `.github/actions/native-caches`, the build scripts unaware of it | Low Coupling, Protected Variations |
| The ABIs of a debug build | `debugAbis()`: the Gradle property `drawview.android.debug.abis`, else `local.properties`, else all | Protected Variations, Information Expert |
| Downloads that survive a failing server | `DownloadVerifiedFile` in `build.gradle.kts` | Information Expert |

### When each workflow runs

`native.yml` gets a filter on `push` and `pull_request`, with these paths:

* `native/**`, `drawview-native-*/**`;
* the modules that load native libraries: `drawview-ink`, `drawview-canvas`, `drawview-latex`,
  `drawview-pdf`, `drawview-lecture`, `drawview-lecture-testing`, `drawview-sync-iroh`;
* the build: `build-logic/**`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/**`, `gradlew*`,
  `gradle.properties`;
* what its checks use: `sample/**`, `pencilnotes/androidApp/**`, `.github/workflows/native.yml` and
  `.github/actions/**`.

A wrong filter fails silently, by not checking, so a `schedule` once a week and `workflow_dispatch`,
which ignore the filter, run everything. If a required status check is ever set on these jobs, a
filtered workflow would leave it pending: phase 5 of `docs/plans/open-source.md` (the protection of
`main`) must require the jobs of `check.yml` only.

### What moves between the two workflows

`check.yml` splits in two jobs:

* `check`, on `macos-26`: `jvmTest checkKotlinAbi`, the compilation of every platform without the
  APKs, and `consumer-checks`.
* `android`, on Linux: the two `assembleDebug`, for `arm64-v8a` only.

So the APKs of every change are built where the minutes cost less, and `native.yml` remains the only
one to check all the ABIs.

### Caches

* **Rust:** `Swatinem/rust-cache` on `native/iroh-bridge`, with the target directory of the Gradle
  builds (`native/build/rust`).
* **C++:** `hendrikmuhs/ccache-action` on Linux, macOS and Android, one cache per job, reaching CMake
  through `CMAKE_C_COMPILER_LAUNCHER` and `CMAKE_CXX_COMPILER_LAUNCHER`, with
  `CCACHE_COMPILERCHECK=content` since the NDK may be installed anew by each run. ccache does not
  work with the Visual Studio generator, so Windows relies on the reuse of the libraries below.
* **The archives of the engines:** `build/*/downloads`, verified by their digest when they are used.
  `DownloadVerifiedFile` keeps a file already there with the right digest.
* **The desktop libraries:** `build/nativeLibs/jvm` kept with `actions/cache` under a key of the
  platform and of `hashFiles` of `native/**`, `gradle/libs.versions.toml` and `build.gradle.kts`. On
  a hit the job skips the JNI libraries and passes `-Pdrawview.native.prebuilt=true` to its tests.
  The save is a separate step that follows a successful build only.
* **Gradle:** nothing to add. `setup-gradle` writes only on the default branch.

The third-party actions are pinned to a commit.

## Phases

The workflows cannot run on a local machine: each phase is checked here by what can be (the build
logic, the tests, the syntax of the workflows), and on Actions once the minutes are there. One
commit per phase.

| Phase | Content | Status |
|---|---|---|
| 1 | This plan | Done |
| 2 | The failures of the runners: `TrackpadGesturesTest` in pixels, the two lecture tests, the downloads tried again and kept | To do |
| 3 | `check.yml` and `release.yml` on `macos-26` | To do |
| 4 | `check.yml` in two jobs, the APKs on Linux for one ABI (`drawview.android.debug.abis`); the `paths` of `native.yml` and the weekly run | To do |
| 5 | The caches: Rust, ccache, the archives, the desktop libraries; the disk of the Android jobs freed; the results of failed tests kept | To do |
| 6 | The times measured on Actions and written here; what did not pay removed | To do, once the repository is public or the minutes are there |

## Measures of `feat/ci-time`

| Job | Empty caches | Rust and ccache restored |
|---|---|---|
| `check.yml` | failed at 25 min | 14 min, green |
| Desktop Linux x86_64 | 19 min | 7 min |
| iOS | 10 min | 8 min |
| Android applications, one ABI | 14 min | 11 min |
| Android, three ABIs | 34 min | 27 min, ccache finding nothing |
| Desktop Windows | 36 min | 25 min |

## Decisions

Taken alone, all revertible:

* **Redone on `main`, not rebased:** the workflows and the build logic of `main` changed under the
  branch (`debugAbis()`, the tags of `release.yml`, `screenshots.yml`), so each idea is written again
  on what is there. `feat/ci-time` and `feat/native-ci-desktop-build` are left as they are.
* **One property for the ABIs of a debug build**, `drawview.android.debug.abis`, over
  `android.debug.abis` of `local.properties`, instead of the separate `drawview.android.abis` of
  `feat/ci-time`: `main` already filters the debug builds only, and a release keeps every ABI.
* **The filter is on the workflow, not on the single jobs:** the native jobs depend on the same
  files, and `desktop-jars` depends on all of them.
* **A weekly run**, on Monday at 4:00 UTC, rather than one every night.
* **`release.yml` uses none of the caches.**
* **`arm64-v8a` for the `android` job of `check.yml`:** the ABI of the real devices.

## Left for later

* Phase 6.
* Pinning `macos-latest` and `ubuntu-latest` to a version.
* Larger runners or self-hosted ones: not before the savings of this plan are measured.
