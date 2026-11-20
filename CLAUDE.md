# Pageboard

Kotlin Multiplatform libraries for drawing and note-taking (Android, desktop JVM, iOS), published
as `io.github.valerioisufi:pageboard-*`, plus two applications built on them: PencilNotes and the
sample. The libraries were named DrawView until November 2026 (`docs/plans/pageboard.md`): the
plans written before keep the old names. Start from:

- `docs/reference.md`: the modules, the features, how to build, run and test (`README.md` is the
  short version for visitors).
- `docs/plans/modularization.md`: the module table, the dependency graph and the rules the libraries
  follow (the "Rules" and "Design" sections).
- The plan of the area you work on, in `docs/plans/`: `collaboration.md` (sync),
  `internet-sync.md` (sync over the internet), `lecture-assistant.md`, `export.md`,
  `text-editing.md`, `adaptive-ui.md`, `ui-design.md`.

## How work is done

- **Every feature, refactor or UI change starts with a plan.** Design it with the GRASP patterns
  in `docs/<feature>.md` before writing code. The plan holds:
  - the starting point;
  - the requirements;
  - the design, with the patterns named;
  - phases with a status table;
  - the decisions, and what is left for later.

  The user approves the plan. Decisions you take alone go in it as revertible.
- **One branch per piece of work** (`feat/<name>` from `main`), with one commit per phase. Update
  the status of the phase in the plan in the same commit. `main` takes a branch by fast-forward,
  when the user says so.
- **Commits** are `type(scope): description`, in lowercase, of type `feat`, `fix`, `refactor`,
  `build` or `docs`. Never add `Co-Authored-By` or "Generated with Claude Code", and never add
  attribution to PRs or issues.
- **Language:** code, comments, docs and commits are in English.
  Match the comment style around you: KDoc that says what a thing is, in full sentences.

## Non-negotiable

- **People keep real notes in PencilNotes. Never lose them.**
  - Every change to the Room schema (`pageboard-store-room/schemas`, `Migrations.kt`) needs a
    migration tested with `MigrationTestHelper`.
  - Every change to a stored format (documents, preferences, lecture records, attachments)
    needs a tested migration too.
  - Never use a destructive fallback, and never delete or rewrite stored data to "start clean".
- **Never touch `~/.pencilnotes`, `~/.pageboard` or `~/.drawview` (the sample before the rename)
  while verifying.** Run the desktop apps on a scratch directory:
  `JAVA_TOOL_OPTIONS="-Dpageboard.dataDir=<scratch dir>" ./gradlew :pencilnotes-desktop:run`
  (the old `-Ddrawview.dataDir` alone stops the run).
- **The sync protocol is live between devices** (`PROTOCOL_VERSION` in `OpenFrame.kt`: 1).
  - Any change to what travels is a protocol change: frames, messages, sealed operations,
    invitations, membership. Say so, re-record `ProtocolFormatTest`, and agree on the version
    with the maintainer.
  - Devices on different formats cannot sync.
- **What another device sends is untrusted input**: anyone may reach a device over the internet
  or on the same Wi-Fi.
  - Bound what is read before the other device is authorized (`SyncChannel.receive(maxSize)`),
    and never allocate a length announced before its bytes come.
  - A frame, message or operation not understood fails the connection (a `SyncException`), never
    the application; its CBOR nesting is checked before it is decoded (`cborNestsWithin`).
  - Members are not trusted either (`docs/plans/untrusted-members.md`): an operation is taken only signed
    by the device of its replica, a key only as the owner named it, a document from its owner
    only, what a member has from that member only.
- **No web targets.** No JS or Wasm, ever: the targets are Android, JVM, `iosArm64` and
  `iosSimulatorArm64`.
- **Ask before granting** microphone, notification or system permissions in simulators and
  emulators.

## The rules of the libraries

These are in brief; the full version is in `docs/plans/modularization.md`.

- A module builds and runs with what it declares. An optional engine (PDF, formulas, native ink)
  is given by the application, and when it is missing the feature degrades, never the build.
- **Headless modules have no Compose:**
  - `pageboard-platform`, `-model`, `-html`, `-ink`, `-store`, `-store-room`;
  - `-sync-protocol`, `-sync`, `-sync-lan`, `-sync-iroh`;
  - `-ml`, `-ml-litert`, `-search` (the local models and the search in the notes, see
    docs/plans/local-models.md and docs/plans/search.md);
  - `-handwriting`, `-handwriting-mlkit`, `-handwriting-windows` (the handwriting read, see
    docs/plans/handwriting.md);
  - `-agent` (what an external model reads and proposes, see docs/plans/agent-tools.md).

  `pageboard-lecture` keeps the Compose runtime only.
- A native library ships with the module that uses it, and only with it: `pageboard-native-*` on
  Android, JNI in the jar on desktop, cinterop on iOS. The iroh bridge (`native/iroh-bridge`) is
  Rust, built by Gradle with the cargo of `rust.cargo` in `local.properties` (or `~/.cargo/bin`,
  or the `PATH`) into `native/build/rust`.
- **No global mutable state in the APIs.** Engines, stores and registries are instances passed
  in.
- **The public API is explicit and recorded:**
  - Explicit API mode is strict.
  - Each library records its ABI in `api/`, and `checkKotlinAbi` fails when it changes. Run
    `./gradlew updateKotlinAbi` only for a change you mean, and say so in the commit.
  - Internals used by public inline functions are `@PublishedApi internal`.
  - Members called from JNI stay public, because `internal` names are mangled on the JVM.
- A third-party element type uses the same API as the built-in ones.
- **A new library:**
  - Apply `pageboard.kmp.library` or `pageboard.compose.library` (plus `pageboard.android.native` /
    `pageboard.jvm.natives` for native code).
  - Add it to `settings.gradle.kts` and to the Dokka aggregate in `build.gradle.kts`.
  - Write its `Module.md`, and record its `api/` dump.

## Checks before committing a phase

```bash
./gradlew jvmTest checkKotlinAbi
./gradlew compileAndroidMain compileKotlinIosSimulatorArm64 :pencilnotes-android:assembleDebug :sample-android:assembleDebug
./consumer-checks/check.sh   # when published APIs or dependencies change
```

- **The iroh bridge changed:** its Rust tests, with the target directory of the Gradle builds
  (`native/iroh-bridge/target` is not one to commit):
  `CARGO_TARGET_DIR=native/build/rust cargo test --manifest-path native/iroh-bridge/Cargo.toml --features dev-relay`
- **The sync modules changed:** their tests on the iOS simulator too,
  `./gradlew :pageboard-sync-protocol:iosSimulatorArm64Test :pageboard-sync:iosSimulatorArm64Test :pageboard-sync-lan:iosSimulatorArm64Test :pageboard-sync-iroh:iosSimulatorArm64Test`.

- **UI changes:** check the screenshots, and run the desktop app on a scratch data directory.
  - The references are recorded only in the Docker image of `tools/screenshots/`
    (`docs/plans/ci-screenshots.md`): never record them on the Mac or on Windows, where
    `recordRoborazziJvm` refuses.
  - Verify with `tools/screenshots/run.sh verify` where Docker runs, else look at the differences
    of `compareRoborazziJvm` and let the workflow Screenshots of the pull request verify.
  - Record with the label `record-screenshots` on the pull request (it commits on the branch), or
    `tools/screenshots/run.sh record`.
- **Stale results:** if a result looks stale, run again with `--rerun-tasks` on the module.

## Worktrees

`.worktreeinclude` copies `local.properties` (the Android SDK) and the `Local.xcconfig` files of
the iOS apps (the Apple team) into a new worktree. The native libraries of the desktop, of iOS and
of cargo come from the build cache when their sources are the same (`docs/plans/build-speed.md`); the
CMake of Android runs in each checkout, which takes a while. In `local.properties`,
`android.debug.abis=arm64-v8a` leaves it one ABI, and `rust.targetDir` shares the directory of cargo.

## Planned work

Each one gets its own plan.

- **Sync over the internet with iroh** (in progress, `feat/internet-sync`): the plan is
  `docs/plans/internet-sync.md`. It replaces the idea of libp2p, whose relay limits every circuit to
  128 KiB and 2 minutes by default.
  - Left: a relay of its own (`tools/relay/README.md`) and the tests on real networks (phase 7).
  - Offline devices: the relay of iroh does not store anything, so they still need the
    store-and-forward relay (`SealedOperation` was made for it), in a plan of its own.
  - Publishing the applications to other users needs more: the access to the relay (a token in
    an application is no secret), privacy and the forms of the stores, the compatibility between
    versions of the protocol (see "Left for later" in the plan).
- **Lecture assistant on small, specialized local models.**
  - Today Gemini and Ollama are wired inside `pageboard-lecture`.
  - The model runtime (whisper.cpp today) should become a module of its own, shared with
    writing suggestions.
  - The lectures already recorded are user data: their format needs a migration.
- **Completion and suggestions while writing.**
  - This needs a new extension point in the editor: `EditorExtension` has no hook into the text
    box (`TextBoxEditor`).
  - `EditorExtension` is a class built from lambdas, so every new hook breaks binary
    compatibility once it is published. Decide its shape before the first release.
