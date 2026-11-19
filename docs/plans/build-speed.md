# The speed of the build

A new worktree takes 10 minutes to pass the checks, and each piece of work has a worktree of its
own. This document starts from what was measured (main at d79f6482, 2026-11-05, Mac M5 with 16 GB),
then gives the design and the plan.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

1. **A checkout already built is fast**, and nothing is to be gained there:

   | What | Time |
   |---|---|
   | Nothing to do, with the configuration cache | 0.3 s |
   | Configuration without the cache | about 1 s |
   | Compiling all the modules again: JVM, Android, iOS simulator | 22 s, 16 s, 21 s |
   | All the `jvmTest` | 52 s |

2. **A new worktree is slow:** `jvmTest checkKotlinAbi` takes 5 min 1 s, then Android and iOS take
   5 min 6 s. The Kotlin comes from the build cache (`compileKotlinJvm` and `compileAndroidMain`
   all `FROM-CACHE`). The time goes to the native code, as time of the tasks, which run in parallel:

   | Task | Time |
   |---|---|
   | CMake of Android, 3 ABIs (ink, tex, pdfium, audio) | about 18 min |
   | `buildRustLibraries` (Android) | 4 min 58 s |
   | `buildIrohBridgeIos` | 4 min 53 s |
   | `buildDesktopNativeLibs` | 3 min 44 s |
   | `buildIosNativeLibs-*` (2 SDKs) | 3 min 22 s |
   | `buildIrohDevRelay` | 2 min 29 s |

3. **None of these tasks is cacheable:** they are `Exec` tasks or a `CargoBuild` without
   `@CacheableTask`, so each checkout builds them again. The tasks after them follow: 17 modules of
   the 28 compile each iOS target again, and 18 `jvmTest` run again.
4. **Android builds 3 ABIs in debug** (`arm64-v8a`, `armeabi-v7a`, `x86_64`), while the tablet and
   the emulators of this Mac need the first one only. The CI checks that a debug APK holds the 3 of
   them (`native.yml`).
5. **Cargo builds in `native/build/rust`**, one per checkout, up to 6 GB. A change of the bridge
   builds all its dependencies again in a new worktree, since the build cache misses then.
6. **The classic settings would not help:** the configuration cache already runs the tasks in
   parallel (56 s of work in 22 s), so `org.gradle.parallel` changes nothing.

## Requirements

1. A new worktree with the sources of a checkout already built reuses its native libraries.
2. A library from the cache is the one a build would make: everything that changes it is in its
   key, the compiler too.
3. What is published does not change: a release and what the CI checks keep every ABI.
4. Nothing is required of a machine: without a setting the build does what it does today.

## Design

- **The tasks declare what makes their output (Information Expert):** each native task already
  lists its sources, and it adds what is missing (the package and version of PDFium, the version of
  Xcode). `CargoBuild` is `@CacheableTask`; the `Exec` tasks say `outputs.cacheIf { true }`.
- **The version of Xcode is read by a `ValueSource` (Pure Fabrication, Protected Variations):**
  `AppleToolchainVersion`, read when a native task is in the graph, not at each configuration: a
  build with nothing to do stays at 0.3 s. On another system it is empty. The Rust toolchain needs
  nothing, since `rust-toolchain.toml` is among the sources of the crate, and the NDK is in the
  environment of the task, by its path.
- **The machine says which ABIs it debugs (Indirection, Low Coupling):** `android.debug.abis` in
  `local.properties` (the place of `rust.cargo`, copied into the worktrees by `.worktreeinclude`),
  read by one function (`debugAbis`), the same for the CMake and for cargo. Without it, debug has
  the 3 ABIs, as the CI wants. The release variant always has them.
- **One task of cargo per variant (High Cohesion):** `buildRustLibrariesDebug` and
  `buildRustLibrariesRelease`, since the variants may have different ABIs. Same ABIs, same key: one
  build reuses the other.
- **The directory of cargo can be shared (Protected Variations):** `rust.targetDir` in
  `local.properties`, read by `rustTargetDirectory` and passed to `build_desktop.sh`. Without it,
  `native/build/rust`.

## Plan

| Phase | What | Status |
|---|---|---|
| 1 | The native tasks in the build cache, with the inputs they lacked | done |
| 2 | `android.debug.abis`, one cargo task per variant | done |
| 3 | `rust.targetDir` | done |

## Results

A new worktree, with the build cache filled by another checkout of the same sources: the two
commands of the checks, measured as in the starting point.

| Setting | `jvmTest checkKotlinAbi` | Android and iOS | Total |
|---|---|---|---|
| Before | 5 min 1 s | 5 min 6 s | 10 min 7 s |
| Phase 1, native tasks in the cache | 55 s | 2 min 44 s | 3 min 39 s |
| Phases 1 and 2, `android.debug.abis=arm64-v8a` | 59 s | 47 s | 1 min 46 s |

With phase 1 all the iOS targets come from the cache, and 25 `jvmTest` of the 26 that have tests.
What runs still is the CMake of Android, 5 tasks with one ABI.

`rust.targetDir`, when the build cache misses (`buildIrohBridgeIos` run again, 2 targets): 3 min 3 s
in an empty directory, 46 s from another worktree sharing it. The dependencies are reused.

A debug APK holds the 3 ABIs without the setting and only `arm64-v8a` with it; the release AARs
checked (iroh, pdfium) hold the 3 of them with the setting too.

## Decisions

Revertible, taken alone:

- **Debug keeps the 3 ABIs by default**, and the machine asks for less. The other way would have
  changed the check of the CI and surprised an emulator on an Intel Mac.
- **The Android CMake stays out of the cache:** its tasks belong to the Android plugin, and caching
  them needs a wrapper of the CMake. A single ABI gives most of the gain.
- **No `ccache` or `sccache`:** a tool to install, for less than what the build cache gives here.

## Left for later

- The slowest tests (`drawview-explorer` 49 s, `drawview-sync-iroh` 38 s).
- A remote build cache, should the CI become the bottleneck.
