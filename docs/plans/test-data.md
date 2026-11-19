# Test data

The tests of the JVM never read or write the data of the user. This document starts from the
checks of 2026-10-18 on the Windows machine of the maintainer, where the tests of the JVM wrote into
`~/.drawview`.

The responsibilities are assigned following the GRASP principles (Information Expert, Pure
Fabrication, Protected Variations).

## Starting point

* On the desktop the data directory is `drawViewDesktopDataDirectory`: the
  `drawview.dataDir` system property (`DRAWVIEW_DATA_DIRECTORY_PROPERTY`), else `~/.drawview`.
  PencilNotes sets `~/.pencilnotes` when the property is missing.
* No `Test` task of the build sets the property, so every test that reaches the data directory
  (the secure store, the Room database, the formulas, the window states) uses `~/.drawview`.
  The only safe run is one with `JAVA_TOOL_OPTIONS="-Ddrawview.dataDir=<scratch dir>"`, given by
  hand.
* `SecureStoreTest` writes `~/.drawview/secure/test-secret-<nanos>`, then reads its POSIX
  permissions: on Windows `Files.getPosixFilePermissions` throws
  `UnsupportedOperationException`, the test fails and its secret is never deleted.

## Requirements

1. Every `Test` task of the build (the JVM and the Android host tests of the libraries and of
   the applications) runs with `drawview.dataDir` set to a directory under the `build/` of its
   module, created before the tests start. No hand-made option is needed.
2. Compatible with the configuration cache and the build cache: the absolute path of the
   directory is not an input of the tests.
3. `SecureStoreTest` passes on Windows: it checks the POSIX permissions only where the file
   system has them, and it deletes its secret even when an assertion fails.
4. Nothing in `~/.drawview` or `~/.pencilnotes` is read, listed, written or deleted, the
   `test-secret-*` left there by the old test included: the maintainer decides about it.

## Design

* **Information Expert.** The build of a module knows its `build/` directory and its test
  tasks, so it gives each `Test` task its data directory: `build/test-data/<task>` (for example
  `drawview-platform/build/test-data/jvmTest`), one per task, so that the JVM and the Android
  host tests of a module do not share it.
* **Pure Fabrication.** The convention plugin `drawview.test.data` (`TestDataConventionPlugin`)
  holds the rule:
  * `TestDataDirectory`, a `CommandLineArgumentProvider`, adds `-Ddrawview.dataDir=<directory>`
    to the JVM of the tests; its directory is `@Internal`, so the path does not enter the cache
    key and the checkouts share the cached results;
  * a `doFirst` action empties and creates the directory before the tests start, so every run
    starts from no data and the data of a failed run stays to be looked at until the next one.
* **Protected Variations.** `drawview.kmp.library` applies it, so every library (and every new
  one) has it; the modules of the applications, which use no convention plugin, apply it by id.
  A new place for the test data changes the plugin only.
* A command-line `-D` comes after `JAVA_TOOL_OPTIONS`, so the directory of the build wins over
  one given by hand.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `drawview.test.data` applied by `drawview.kmp.library` and by the six modules of the applications; `SecureStoreTest` on every file system | Done |

### Phase 1 results

* `./gradlew :drawview-platform:jvmTest` passes on Windows without `JAVA_TOOL_OPTIONS`,
  `SecureStoreTest` included; its secure store is
  `drawview-platform/build/test-data/jvmTest/secure`.
* With `JAVA_TOOL_OPTIONS` pointing at an empty canary directory, `./gradlew jvmTest
  checkKotlinAbi --continue` leaves the canary empty: every test JVM takes the directory of the
  build. The 19 modules with JVM tests (the sample included) each have `build/test-data/jvmTest`.
* The configuration cache stores and reuses the entry, with no problem reported for the plugin.
* The failures left are those of this Windows host before the change: `checkKotlinAbi` of the
  modules with Compose or Room (their iOS klib dump comes out empty), `TrackpadGesturesTest`,
  `LectureLibraryTest`, `LectureRecorderTest`, `LectureSharingTest`.

## Decisions taken by me (revertible)

* The place, `build/test-data/<task>`, and one directory per task.
* The directory is emptied before each run (not after it).
* The Android applications apply the plugin too, though their unit tests do not reach the
  desktop data directory: the rule is "every module with tests on a JVM", without exceptions.

## Left for later

* The `test-secret-*` file the old `SecureStoreTest` left in `~/.drawview/secure` on Windows:
  the maintainer deletes it, if they want.
