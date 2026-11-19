# Screenshots that anyone can verify and record

The reference images of the screenshot tests recorded and verified in one environment of the
continuous integration, a Linux runner of GitHub Actions, so that a contributor on any system can
check a change of the interface and get its references, without the MacBook of the maintainer.
This document starts from the code as it is (main at 514f415f, 2026-11-19).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* **Seven modules have screenshot tests** with Roborazzi 1.76 (`recordRoborazziJvm`,
  `verifyRoborazziJvm`): `drawview-explorer`, `-ui`, `-sync-ui`, `-lecture-ui`, `-export-ui`,
  `-search-ui`, `-agent-ui`. Each applies the plugin and sets `outputDir` to its own `screenshots/`,
  175 reference images in all. The tests draw with `runComposeUiTest` and `captureToImage()`, on the
  raster of Skia (CPU), with no window and no GPU.
* **The references are recorded on macOS**, on the MacBook of the maintainer. On Windows nearly every
  screenshot of `drawview-explorer` and eight of `drawview-ui` differ in the antialiasing of the text,
  and `verifyRoborazziJvm` fails there. Recent branches (`fix/search-snippet-latex`,
  `fix/phone-toolbar`, the last commit of main) left their references "to record on the Mac".
* **Why the systems differ.** Skia rasterizes the glyphs with the scaler of each system (CoreText on
  macOS, DirectWrite on Windows, FreeType on Linux), so even the fonts bundled by the libraries (Plus
  Jakarta Sans, Noto Sans) are drawn differently. A few places ask for the fonts of the system
  (`FontFamily.Default`, `Monospace`, `Serif`: `NotesFonts.System`, the numbering controls, the
  formulas of `MarkdownTextUtil`), which differ too.
* **`jvmTest` does not compare**: without `roborazzi.test.verify` the captures are not compared, so
  `jvmTest` passes on every system; only `verifyRoborazziJvm` fails.
* **The workflows**: `check.yml` runs `jvmTest checkKotlinAbi` and the compilation on `macos-14`,
  never `verifyRoborazziJvm`. `native.yml` builds and tests the JNI libraries on every platform,
  `ubuntu-latest` included. `feat/ci-time` (not merged) splits `check.yml`, adds caches of cargo and
  ccache in `.github/actions/native-caches` and measured the JNI libraries of Linux x86_64 at 19
  minutes cold, 7 warm.
* **The screenshots need the JNI libraries**: `drawview-explorer` draws PDF pages and formulas with
  the engines of the applications (PDFium, MicroTeX), built by `:buildDesktopNativeLibs`.
* **Actions has a spending limit** while the repository is private; Linux minutes count once, macOS
  minutes ten times. Once the repository is public (`docs/plans/open-source.md`) they are free.

## Requirements

1. The references are recorded in one environment, the same for everyone, and described in the
   repository: system, JDK, fonts, the settings of the test JVM.
2. Every pull request verifies the screenshots there, and when they differ the author sees the
   differences (the `_compare.png` of Roborazzi) without running anything.
3. Anyone can get the references of a change recorded there: the maintainer on a branch of the
   repository, a contributor on the branch of a fork.
4. Off that environment the tests still run, and nobody records references by mistake.
5. No tolerance that hides a real regression: a moved icon, a changed color, a line one pixel off.
6. The references are migrated once, all together, in one commit that says so.
7. Few minutes while the repository is private.

## Design

| Responsibility | Where | GRASP |
|---|---|---|
| The screenshot settings of a module (the plugin, `outputDir`, the test JVM, the guard of recording) | a convention plugin `drawview.screenshots` in `build-logic`, applied by the seven modules in place of their own `roborazzi {}` | Information Expert, High Cohesion |
| Telling the reference environment apart | the variable `DRAWVIEW_SCREENSHOTS=reference`, set only by the workflow (and the Docker image, phase 5) | Protected Variations, Indirection |
| Verifying every pull request | `.github/workflows/screenshots.yml`, job `verify`, on `ubuntu-24.04` | Controller |
| Recording on demand | the same workflow, job `record`, on `workflow_dispatch` | Controller |
| The differences and the new images of a failing verify | artifacts of the `verify` job | Pure Fabrication |
| How to verify and record, for a contributor | `CONTRIBUTING.md`, `docs/reference.md` | Information Expert |

### The reference environment

* **The runner** is pinned: `ubuntu-24.04`, not `ubuntu-latest` (which moves to Ubuntu 26), on
  x86_64. The raster of Skia can take other paths on another architecture (NEON on arm64), so the
  architecture is part of the environment.
* **The JDK** is Zulu 21, as in the other workflows; the version of Skiko comes from Compose, in the
  version catalog.
* **The fonts of the system** are those of Ubuntu 24.04 plus `fonts-dejavu-core`, installed by the
  workflow, so that `Default`, `Monospace` and `Serif` resolve to fixed families. The bundled fonts
  need nothing.
* **The test JVM** of the screenshot modules gets `-Duser.language=en -Duser.country=US
  -Duser.timezone=UTC -Dfile.encoding=UTF-8 -Djava.awt.headless=true` from the convention plugin, on
  every system, so that dates, numbers and text do not depend on the host.
* **The comparison** is the default of Roborazzi 1.76 (checked in phase 1): a small distance per
  pixel and no share of differing pixels allowed. The same on every system.

### Verifying

`screenshots.yml` runs on every pull request and on the pushes to `main`, except those that change
only `docs/**` or Markdown files: a change of the model or of the canvas can move a screenshot as
much as a change of a UI module, so there is no filter on the modules.

1. Installs the fonts, sets up the JDK and Gradle (`gradle/actions/setup-gradle`, whose cache keeps
   the JNI libraries when their sources are the same; the caches of `feat/ci-time` when it is
   merged).
2. Runs `./gradlew verifyRoborazziJvm --continue` on the seven modules.
3. On a failure uploads two artifacts:
   * `screenshots-diff`: the `_compare.png` of every differing screenshot, to look at;
   * `screenshots-new`: the images rendered by the run, laid out at their paths in the repository
     (`drawview-ui/screenshots/…`), to unzip at the root of a checkout and commit when the change
     is meant.

It is a workflow of its own, not a step of `check.yml`: it runs on Linux while `check.yml` stays on
macOS, and it does not touch the files `feat/ci-time` changes (Low Coupling).

### Recording

* **A branch of the repository**: `workflow_dispatch` of `screenshots.yml` with the branch as input
  (`gh workflow run screenshots.yml -f branch=feat/x`). The `record` job runs `recordRoborazziJvm`,
  then `verifyRoborazziJvm` on what it recorded, and commits the changed images on that branch,
  `build(screenshots): the references recorded on the ci runner`, as `github-actions[bot]`, with
  `contents: write` for that job only. A push made with `GITHUB_TOKEN` starts no workflow, so the
  checks of the pull request are run again by hand, or by the next push.
* **A branch of a fork**: `GITHUB_TOKEN` cannot push there, and a workflow that ran the code of a
  fork with a token that writes (`pull_request_target`) would let any fork write in the repository.
  So the author takes `screenshots-new` from the failing run of the pull request, unzips it, looks at
  the images and commits them. No run more than the verify that already failed.

### Off the reference environment

* **`jvmTest`** runs the screenshot tests as today, without comparing.
* **`verifyRoborazziJvm`** runs and compares as on the runner, with no tolerance; when
  `DRAWVIEW_SCREENSHOTS` is not `reference` the plugin first prints that the references come from
  the Linux runner and that differences of antialiasing are expected here, and points to
  `compareRoborazziJvm` (the differences in `build/outputs/roborazzi`, without failing) and to the
  check of the pull request.
* **`recordRoborazziJvm`** fails off the reference environment, saying how to record (the workflow,
  or `screenshots-new`). `-Pdrawview.screenshots.recordHere=true` records anyway, for a look at the
  images that is not committed.

**Why no tolerance off Linux.** The differences of antialiasing touch the edge of every glyph: on a
screen of the library they are a few percent of the pixels. A `changeThreshold` above that lets an
icon move, a small element change color or a line shift by a pixel, and a tolerance per pixel high
enough for the edges of the glyphs hides a change of a text color. Verifying in one place with no
tolerance keeps every regression visible; locally the comparison images serve to look, not to judge.

### Migrating

Once the workflow works, the `record` job records every reference on `feat/ci-screenshots` in one
commit, `build(screenshots): every reference recorded again on the linux runner`, which says that
the images change for the rendering of Linux and not for a change of the interface. Before it, the
references of the Mac and the new ones are compared one by one by eye, on a sample of each module
(the `_compare.png` of a verify on the runner), to tell the rendering apart from a real difference.

The branches open at that moment record their screenshots again after rebasing on `main` (the
workflow, one run each).

## Phases

| Phase | Content | Status |
|---|---|---|
| 1 | The convention plugin `drawview.screenshots`: the plugin, `outputDir`, the test JVM, the notice of verify and the guard of record; the seven modules use it. The defaults of the comparison of Roborazzi 1.76 checked | Planned |
| 2 | `screenshots.yml`: `verify` on pull requests with its two artifacts, `record` on `workflow_dispatch`. Tried on the branch: one verify that fails (the references of the Mac) and one record on a scratch branch | Planned |
| 3 | The migration: every reference recorded on the runner, in one commit; a verify of the next run passes | Planned |
| 4 | The documentation: `CONTRIBUTING.md` (verify, record, `screenshots-new`), `docs/reference.md`, the line of `CLAUDE.md` on UI changes | Planned |
| 5 | Optional: a Docker image of the environment (`tools/screenshots/Dockerfile`, `ubuntu:24.04` by digest, the same JDK and fonts, cmake and cargo for the JNI libraries) and a script that records and verifies in it | To decide |

**Minutes.** A run of `verify` is about 25 minutes of Linux with a cold cache (the JNI libraries,
19 minutes in the measures of `feat/ci-time`) and about 10 with a warm one. Phases 2 and 3 need
about four runs, 60-80 minutes of Linux. Each run is asked to the maintainer first while the
repository is private.

## Decisions

* **Linux, not macOS, as the reference** (revertible): a runner that anyone can use, free for a
  public repository and a tenth of the cost of macOS while it is private, and the same system as a
  Docker image. The maintainer can no longer record on the Mac.
* **`ubuntu-24.04` on x86_64, pinned** (revertible): a change of the image or of the architecture is
  a migration of its own (phase 3 again).
* **No tolerance anywhere** (revertible): see "Why no tolerance off Linux".
* **A workflow of its own** rather than a job of `check.yml` (revertible): so that it runs on Linux
  and does not collide with `feat/ci-time`. It can become a job of `check.yml` once that branch is
  merged.
* **Forks get an artifact, not a commit**: no `pull_request_target`, no token that writes for the
  code of a fork.
* **The variable `DRAWVIEW_SCREENSHOTS`** rather than detecting `CI` or Linux: a Linux laptop is not
  the reference environment, and the Docker image of phase 5 is.

## Left for later

* **The fonts of the system out of the screenshots**: giving the tests bundled families for
  `Default`, `Monospace` and `Serif` would make the screenshots independent of the fonts of the
  runner, but it changes what the tests draw compared with the applications.
* **The arm64 runner** (`ubuntu-24.04-arm`) as the reference, if Docker on Apple silicon matters
  more than the x86_64 laptops: it would make phase 5 native on the Macs.
* **A label or a comment that records** on a pull request of the repository, once the workflow
  is used often.
* **Measuring whether two runs on different machines of the runner give the same images** (the
  raster of Skia picks its code by the features of the CPU): phase 2 records twice and compares; if
  they differ, a per-pixel distance just above those differences, decided then.
