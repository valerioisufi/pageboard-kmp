# Opening the repository and the first release of the libraries

The repository made public on GitHub, with the files a public project needs, PencilNotes kept in it
under a name and an icon of its own, and the libraries released on Maven Central as 0.1.0 once the
repository is public. This document starts from the code as it is (main at 90d87a8d, 2026-11-15).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* **The repository is private**, 516 commits by one author. A search of the history for keys of
  Google, GitHub and OpenAI and for private keys (PEM, PGP) found none; no file of the history is
  larger than 3 MB. `local.properties` and the `Local.xcconfig` of the iOS apps are ignored;
  `.claude/` is not versioned.
* **On GitHub** today: `main` and four branches (`feat/ci-time`, `feat/lecture-eval`,
  `feat/local-models`, `feat/native-ci-desktop-build`), no tag.
* **The license** is MIT, with `THIRD_PARTY_LICENSES.md` in every jar and Android library. Nothing
  says what the name "PencilNotes" and its icon are.
* **The README** is a reference manual of the modules and of the features; it has no screenshot, no
  link to the applications and no Gradle snippet.
* **`docs/`** holds about ninety plans of features beside the few pages a user of the libraries
  reads.
* **CLAUDE.md** speaks of the devices of the user and of the relay on Oracle Cloud.
* **No** `SECURITY.md`, `CONTRIBUTING.md`, `CHANGELOG.md`, issue templates or `FUNDING.yml`.
* **The release of the libraries** (`.github/workflows/release.yml`) runs on every tag `v*`; it has
  never run. `VERSION_NAME` is `0.1.0-SNAPSHOT`, one version for every library.
* **Actions** has a limit of minutes on the private repository; a public one has none.

## Requirements

1. Nothing private becomes public: no secret in the history or on the branches pushed, nothing about
   the devices or the infrastructure of the user in the files.
2. Whoever arrives understands in a minute what the project is: the libraries for developers,
   PencilNotes for the users, each with its links.
3. The code is MIT; the name "PencilNotes" and its icon are not, so that nobody publishes the
   application under that name.
4. Security problems are reported privately; contributions follow the rules of the project.
5. The libraries and the application are released apart: a tag of one never releases the other.
6. The first release on Maven Central (0.1.0) is tried before it is published, and says what a 0.x
   promises.

## Design

| Responsibility | Where | GRASP |
|---|---|---|
| What the project is, for a visitor | `README.md` (short: what, screenshots, links, a Gradle snippet), the reference moved to `docs/` | High Cohesion |
| The plans of the features, apart from the documentation | `docs/plans/` (the links updated) | High Cohesion |
| The name and the icon of PencilNotes | `pencilnotes/TRADEMARK.md`, a line in the README and in `LICENSE`'s neighbourhood | Information Expert |
| How to report a vulnerability | `SECURITY.md` (private reporting of GitHub) | Information Expert |
| How to contribute | `CONTRIBUTING.md` (the plan first, the commits, never lose notes, the checks), `.github/ISSUE_TEMPLATE/` | Information Expert |
| What changed between releases | `CHANGELOG.md` (libraries) | Information Expert |
| Which tag releases what | `release.yml` on `drawview-v*`; the application on `pencilnotes-v*` (its own workflow later) | Protected Variations |
| The modules still changing | an opt-in annotation of the sync modules (`@ExperimentalSyncApi`) | Protected Variations |

### What is published on Maven

Every library, the `-testing` modules included (a store of one's own runs the tests of
`drawview-store-testing`) and the `drawview-native-*` modules the others depend on; not the
applications, the sample and `consumer-checks`. One version for all, released together.

A 0.x is experimental: the ABI stays recorded and every break is written in `CHANGELOG.md`, but a
minor version may break.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | Checks of the history and of the branches: gitleaks on every commit; the four branches on GitHub merged or deleted there (with the user) | Done here (the branches on GitHub left to the user) |
| 2 | CLAUDE.md without the devices and the infrastructure of the user; `docs/plans/` with the links updated | To do |
| 3 | `README.md` rewritten (screenshots from the Roborazzi images, the reference in `docs/`); `pencilnotes/TRADEMARK.md`; `SECURITY.md`, `CONTRIBUTING.md`, the issue templates, `CHANGELOG.md` | To do |
| 4 | `release.yml` on the tags `drawview-v*`; `@ExperimentalSyncApi` on the sync modules (ABI recorded on purpose); Dokka on GitHub Pages | To do |
| 5 | The repository made public and its settings: protection of `main`, Discussions, private vulnerability reporting, Dependabot, secret scanning (the user's) | To do |
| 6 | Maven Central: the namespace `io.github.valerioisufi` verified, the PGP key and the four secrets (the user's); a release candidate uploaded without publishing, checked and dropped; then `drawview-v0.1.0` | To do |

Open Collective and `FUNDING.yml` come after phase 5, on their own.

### Phase 1 results

* Every commit of every branch (`git log -p --all`) searched for the keys of Google (`AIza`),
  GitHub (`ghp_`, `github_pat_`), OpenAI and Anthropic (`sk-`), Slack, AWS, Hugging Face, private
  keys (PEM, PGP) and signing passwords: none. The only matches are the code of
  `drawview.android.signing` and the placeholders of its documentation (`signing.keyPassword=...`).
  gitleaks was not run (a binary to download): the secret scanning of GitHub runs on the whole
  history when the repository becomes public (phase 5).
* No key store, `local.properties`, `Local.xcconfig`, `.env` or `google-services.json` was ever
  committed.
* No address of the relay or of a machine of the user: the addresses in the code are of the
  documentation ranges (`192.0.2.1`, `relay.example.com`) and of the tests.
* Left to the user: the four branches on GitHub (`feat/ci-time`, `feat/lecture-eval`,
  `feat/local-models`, `feat/native-ci-desktop-build`), merged or deleted there.

## Decisions

* **PencilNotes stays in this repository** (with the user, 2026-11-15): it is the first user of the
  libraries, and a change of both lands in one commit. It moves to a repository of its own (with its
  history, `git filter-repo`) when the libraries reach 1.0 and change slowly, or if it needs another
  license.
* **The history is published whole**, the email of the author included (with the user, 2026-11-15:
  it is already public through other repositories). Rewriting it would change every hash.
* **The tags are `drawview-vX.Y.Z` and `pencilnotes-vX.Y.Z`** (alone, revertible).
* **The repository becomes public before the first release on Maven**: the POM points to it.

## Left for later

* A BOM of the libraries.
* The release of the desktop and iOS applications from a tag `pencilnotes-v*`.
* The repository of PencilNotes apart, at 1.0 of the libraries.
