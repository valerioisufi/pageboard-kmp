# Contributing

Thank you for helping. Bugs, ideas and questions are welcome as issues; for a change of the code,
open an issue first, so that its design can be agreed before the work. Everyone who takes part
follows the [code of conduct](CODE_OF_CONDUCT.md).

## How a change is made

* **A plan first.** A feature, a refactor or a change of the interface starts with a plan in
  `docs/plans/<feature>.md`: the starting point, the requirements, the design (with the GRASP
  principles named), phases with a status table, the decisions and what is left for later. The
  plans already there show the form.
* **One branch per piece of work** (`feat/<name>`, `fix/<name>`), one commit per phase, the status
  of the phase updated in the plan in the same commit.
* **Commits**: `type(scope): description`, in lowercase, of type `feat`, `fix`, `refactor`, `build`
  or `docs`.
* **English** for the code, the comments, the docs and the commits. KDoc says what a thing is, in
  full sentences; match the style around you.

## What must never break

* **The notes of the users.** Every change to the Room schema needs a migration tested with
  `MigrationTestHelper`; every change to a stored format (documents, preferences, lecture records,
  attachments) a tested migration too. No destructive fallback.
* **The sync protocol.** A change to what travels between devices is a protocol change: say so in
  the pull request, and record `ProtocolFormatTest` again.
* **What another device sends is untrusted input** (see [SECURITY.md](SECURITY.md)).
* **The rules of the libraries** in `docs/plans/modularization.md`: a module builds and runs with
  what it declares, the headless modules have no Compose, no global mutable state in the APIs, and
  the public API is explicit and recorded in `api/`.
* **No web targets**: Android, JVM, `iosArm64` and `iosSimulatorArm64`.

## Checks

Your own notes stay safe while you check: run the desktop applications on a scratch directory,
`JAVA_TOOL_OPTIONS="-Dpageboard.dataDir=<scratch dir>" ./gradlew :pencilnotes-desktop:run`.

```bash
./gradlew jvmTest checkKotlinAbi
./gradlew compileAndroidMain :pencilnotes-android:assembleDebug :sample-android:assembleDebug
```

* On macOS: `./gradlew compileKotlinIosSimulatorArm64`, and the tests of the sync modules on the iOS
  simulator.
* A change of the public API: `./gradlew updateKotlinAbi`, and say so in the commit.
* A change of the interface: the screenshots, below.
* A change of the published APIs or of the dependencies: `./consumer-checks/check.sh`.

## Screenshots

The modules with an interface compare it with reference images, in their `screenshots/`
directories. The references are recorded in one environment, a Docker image of Ubuntu on x86_64
(`tools/screenshots/Dockerfile`): each system draws the text with its own antialiasing, so an image
recorded on macOS or Windows differs from one recorded there.

* **The check of your pull request** (the workflow Screenshots) verifies them. When they differ it
  uploads two artifacts: `screenshots-diff`, the differences (`_compare.png`), and
  `screenshots-new`, the images of the run at their paths in the repository.
* **When the change is meant**, get the new references:
  * on a fork, download `screenshots-new`, unzip it at the root of your checkout, look at the
    images and commit them;
  * on a branch of the repository, add the label `record-screenshots` to the pull request (or run
    `gh workflow run screenshots.yml -f branch=<branch>`): the workflow records the references
    and commits them on the branch. Push, or run the checks again, to verify them.
* **On your computer with Docker**: `tools/screenshots/run.sh verify` verifies and
  `tools/screenshots/run.sh record` records, in the same image. On Apple silicon the image is
  emulated, and the first run takes long.
* **On your computer without Docker**: `jvmTest` runs the screenshot tests without comparing.
  `compareRoborazziJvm` writes the differences in `build/outputs/roborazzi` of each module without
  failing, to look at; `verifyRoborazziJvm` may fail on text antialiasing alone, and
  `recordRoborazziJvm` refuses to record there.

There is no tolerance in the comparison: a threshold wide enough for the antialiasing of another
system would hide a moved icon or a line one pixel off. See
[docs/plans/ci-screenshots.md](docs/plans/ci-screenshots.md).

## License

By contributing you agree that your contribution is released under the [MIT License](LICENSE).
