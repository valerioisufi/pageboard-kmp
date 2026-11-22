# Changelog

The changes of the Pageboard libraries (`io.github.valerioisufi:pageboard-*`), released together under
one version. A 0.x version may break the public API in a minor version: every break is written
here. PencilNotes has its own release notes in the stores.

## Unreleased

The first release on Maven Central, 0.1.0: this section takes its number and its date in the commit
that sets `VERSION_NAME` for the tag `pageboard-v0.1.0`.

* Android 10 (API 29) and later; the desktop on Java 17 and later (macOS 13, Linux with glibc
  2.28, Windows 10); iOS 15. Built with Kotlin 2.4: an application needs Kotlin 2.4 or later.
* `pageboard-bom`: an application imports it and declares the libraries without a version; every
  library depends on it, so that Gradle takes all of them at one version.
* On the desktop the application names its data directory once, `PageboardDesktop.init`, as it
  gives its context on Android with `PageboardAndroid.init`: there is no directory shared by
  default.
* The sync (`pageboard-sync*`) is experimental: an application that starts it opts in to
  `@ExperimentalSyncApi` (`SyncEngine`, `LanSync`, `IrohSync`, `IrohInternet`, `Collaboration`).
* What is public for the other modules only is `@InternalPageboardApi`: an error outside them.
* An iOS application with `pageboard-pdf` embeds the PDFium of the release of GitHub of the same
  version (see the page of `pageboard-pdf`).
* Material 3 is an alpha (`1.12.0-alpha03`): no stable one goes with Compose Multiplatform 1.12.
