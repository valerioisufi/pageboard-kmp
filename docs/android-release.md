# The release of PencilNotes on Google Play

PencilNotes 3.0 (the Kotlin Multiplatform application) published on Google Play as the update of the
original Android app, signed with its key, shrunk by R8, with rules for the backups of the system, a
privacy policy and what the Play Console asks. This document starts from the code as it is (main at
90d87a8d, 2026-11-15).

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* **Google Play has the original app**, `com.studiomath.pencilnotes`: version 2.1 (versionCode 3) in
  production since 2026-04-01, in one country, with no active installations (versions 1.0 and 2.0
  are no longer active). Its account has access to production. Its upload key is a PKCS12 keystore
  of the user (alias `pencilnotes`), outside the repository; its SHA-1 is the upload certificate of
  the Play Console (checked by the user on 2026-11-15).
* **The application of today** is `io.github.valerioisufi.pencilnotes` (namespace and
  applicationId), versionCode 4, versionName 3.0. The tablets of the user run its debug build, with
  their real notes.
* **The 2.1 keeps its notes** in the Room database `draw_database` (version 1, schema not exported);
  the 1.0 kept them in XML. The 3.0 opens `drawview.db` only: an update leaves `draw_database` on the
  device, untouched and unread.
* **No signing configuration**: `assembleRelease` gives an unsigned APK.
* **No shrinking**: `isMinifyEnabled = false`, and the `proguardFiles` name a
  `proguard-rules.pro` the application does not have. No library ships consumer rules.
* **Code reached by name from native code**: the classes of `io.nano.tex` (`Rect`, `TextLayout`,
  `Font`, `LaTeX`, `ActionRecorder`, `TeXRender`: fields `x y w h`, methods called back), the classes
  of `androidx.ink` the Ink engine creates (`ImmutableVec`, `MutableBox`, `StrokeInput`...), and every
  `external fun` (`InkNative`, `LaTeX`, `TeXRender`, `PdfiumNative`, `AudioCodecs`, `IrohJni`).
* **Backups**: `android:allowBackup="true"` without rules. Auto Backup copies the database, the
  preferences and the files, up to 25 MB per application (beyond it, nothing is backed up). The
  identity of the device (`DeviceIdentity.ofThisDevice`) and the API keys are in `SecureStore`,
  encrypted by a key of the Android Keystore that a restore does not bring: on a restored device
  `SecureStore.read` gives `null`, and a new identity is made. The memberships of the shared
  documents name the device of each replica, so the restored database holds replicas whose device
  key is gone.
* **Permissions** merged from the libraries: `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`
  (`LectureRecordingService`), `FOREGROUND_SERVICE_DATA_SYNC` (`SyncService`), `POST_NOTIFICATIONS`,
  `INTERNET`, `ACCESS_LOCAL_NETWORK`.
* **The sync over the internet** is off by default (`InternetSettings.enabled = false`); turned on
  without a relay of one's own, it goes through the public relays of n0.
* **No privacy policy** anywhere.

## Requirements

1. The release is `com.studiomath.pencilnotes`, versionCode above 3, signed with the upload key of
   the user; the key and its passwords never enter the repository.
2. The debug build stays `io.github.valerioisufi.pencilnotes`, so that the tablets keep updating the
   application with their notes (`adb install -r`).
3. A machine or a CI without the key still builds the release (unsigned), so the checks stay as
   they are.
4. The release is shrunk by R8, and every library ships the rules of the code it reaches from native
   code or by reflection: an application using the libraries gets them without writing any.
5. Nothing of the notes is lost: an update from 2.1 leaves `draw_database` as it is, and a restore
   from a backup never breaks the shared documents of the other devices.
6. A privacy policy at a public address, and the answers of the Play Console written down.
7. The App Bundle installs over 2.1 on a real device, and the release works on the tablet (the list
   of phase 5).

## Design

| Responsibility | Where | GRASP |
|---|---|---|
| The identity of the application on Play, the debug one apart | `pencilnotes/androidApp/build.gradle.kts` (`applicationId`, `androidComponents.onVariants`) | Protected Variations (the tablets keep their id) |
| Where the key comes from: `local.properties` or the environment, else unsigned | `ReleaseSigning` in `build-logic` (`drawview.android.signing`) | Pure Fabrication, Protected Variations (the source of the secrets) |
| The rules of the code reached from native code, per library | `consumer-rules.pro` of `drawview-ink`, `-latex`, `-pdf`, `-lecture`, `-sync-iroh`, given by `drawview.android.native` / the library plugins | Information Expert (the module knows its JNI), Low Coupling (no rule in the applications) |
| The rules of the application itself | `pencilnotes/androidApp/proguard-rules.pro` | Information Expert |
| What a backup or a transfer keeps | `res/xml/data_extraction_rules.xml` (API 31+), `res/xml/backup_rules.xml` (API 29-30) of the application | Information Expert |
| A device restored with a new identity | decided in phase 3 (see Decisions) | Controller, Protected Variations |
| The privacy policy and the answers of the Play Console | `docs/privacy.md`, `docs/play-console.md` | Information Expert |

### The signing

`drawview.android.signing` reads, in order, `pencilnotes.signing.storeFile`, `.storePassword`,
`.keyAlias`, `.keyPassword` from `local.properties`, else `PENCILNOTES_SIGNING_*` from the
environment. With all four it sets the `release` signing configuration; without them the release
stays unsigned and the build says so once. The passwords are never printed.

### The rules

Each library with native code keeps, in its `consumer-rules.pro`:

* the classes with `external` members and those members (`-keepclasseswithmembernames`);
* the classes, constructors, fields and methods the native code finds by name (`io.nano.tex.**` in
  `drawview-latex`; the classes of `androidx.ink` in `drawview-ink`);
* nothing more: kotlinx.serialization, Room, Ktor and Compose ship their own rules.

A test of phase 2 builds the release of the sample with R8 and opens the canvas, a formula and a
PDF, so that a missing rule fails before the application does.

## Plan

| Phase | Content | Status |
|---|---|---|
| 1 | `applicationId` `com.studiomath.pencilnotes` on the release, the debug kept on `io.github.valerioisufi.pencilnotes`; `drawview.android.signing`; the release built signed on the machine of the user, unsigned in the CI | Done |
| 2 | R8 and resource shrinking on the release; the `consumer-rules.pro` of the five libraries; the rules of the application; `-keepattributes SourceFile,LineNumberTable` and the mapping file kept with the bundle; the release of the sample run with R8 | Done |
| 3 | The backup rules; the case of a restored device (test and decision, see Decisions); `allowBackup` as decided | Done |
| 4 | `docs/privacy.md` (English and Italian), `docs/play-console.md` (Data safety, the foreground services and their video, content rating, audience, non-trader status, countries); where the policy is published | Done |
| 5 | `bundleRelease`; its size per ABI; installed over 2.1 on the tablet (from the Play Store, then the bundle with `bundletool`); the list of the release: library, pen and eraser, text, formula, PDF import and marks, export PDF and archive, lecture recorded with the screen off, LAN sync with the desktop, search with the local models | To do |

The upload to the Play Console (internal testing, then production) is the user's.

### Phase 1 results

* `AndroidSigningConventionPlugin` (`drawview.android.signing`) and `ReleaseSigning` in
  `build-logic`; PencilNotes for Android applies it. The application `<name>-android` takes its key
  from `<name>.signing.*` in `local.properties`, else `<NAME>_SIGNING_*` in the environment.
* The release APK is `com.studiomath.pencilnotes` (versionCode 4, versionName 3.0), the debug APK
  `io.github.valerioisufi.pencilnotes` (checked with `aapt dump badging`).
* Signed with a throwaway key given in the environment, the release carries its certificate
  (`apksigner verify`); without a key it is `pencilnotes-android-release-unsigned.apk` and the build
  says so. The variables of the environment are inputs of the configuration cache: changing them
  configures again.
* `:pencilnotes-android:assembleDebug`, `:sample-android:assembleDebug` and
  `:pencilnotes-android:testDebugUnitTest` pass. No library changed, so the JVM tests and the ABI
  were not run again.
* For the user: four lines in `local.properties`, the passwords never in the repository:

  ```properties
  pencilnotes.signing.storeFile=D:/path/to/keyStore.jks
  pencilnotes.signing.storePassword=...
  pencilnotes.signing.keyAlias=pencilnotes
  pencilnotes.signing.keyPassword=...
  ```

### Phase 2 results

* `KmpLibraryConventionPlugin` publishes the `consumer-rules.pro` of a module when it has one
  (`optimization.consumerKeepRules`); the AARs of the five libraries carry it as `proguard.txt`.
* The rules keep the classes with native methods and those methods (`InkNative`, `PdfiumNative`,
  `AudioNative`, `IrohJni`, `io.nano.tex`), and the classes MicroTeX reaches by name. Since MicroTeX
  registers its native methods with `RegisterNatives`, `LaTeX` and `TeXRender` keep **all** of them:
  with names only, R8 removed `LaTeX.nFree()` (never called from Kotlin) and the release aborted at
  start (`NoSuchMethodError`), found on the emulator.
* PencilNotes for Android: R8 and resource shrinking on the release, `proguard-rules.pro` with the
  lines of the stack traces kept (`SourceFile`, `LineNumberTable`); the mapping file is
  `build/outputs/mapping/release/mapping.txt`, to upload with the bundle. kotlinx.serialization, Room,
  Ktor and LiteRT-LM needed no rule of ours: R8 reported no missing class.
* Run on a new emulator made for it (`PencilNotes_Release_Test`, Pixel Tablet, API 36.1, x86_64; the
  other emulators hold builds of the original app signed with another key): the release starts,
  draws strokes (Ink), exports the note as PDF and shares it (PDFium), and draws a formula in a text
  (MicroTeX), with no error in the log. The sample was not run: the release of PencilNotes uses
  every library with native code, which tests the rules better. Recording a lecture (the microphone
  is a permission to ask the user) and the sync over the internet are left to the list of phase 5.
* The APK with the four ABIs is 114 MB; its dex 7.5 MB. Phase 5 measures the bundle per ABI.
* `compileAndroidMain`, `:pencilnotes-android:assembleDebug` and `:sample-android:assembleDebug`
  pass. `checkKotlinAbi` fails on this Windows host for the Compose and Room modules only, whose iOS
  klib dumps come out empty here (as on main); no Kotlin source changed, so the ABI is the same.

## Decisions

* **An update of the original app, not a new one** (with the user, 2026-11-15): the account keeps
  its access to production, without a new closed test of 12 testers for 14 days, and the listing
  keeps its history. An applicationId is a name, not a domain: owning `studiomath.com` is not needed.
* **No import of the notes of 2.1 and 1.0** (with the user, 2026-11-15; revertible): no active
  installation, and the user has no notes there. The update does not delete `draw_database`, so an
  import added later finds the notes as they were.
* **The key outside the repository** and its passwords in `local.properties` (not versioned) or in the
  environment; Play App Signing keeps the key of the application, so a lost upload key can be reset.
  (Alone, revertible: a keystore of the system or a password manager in its place.)
* **The restored device** (to decide in phase 3, with the user). The options seen:
  1. back up only what has no identity: exclude the database too (`allowBackup` kept for the
     preferences only), and leave the backups to the archive;
  2. back up the notes and, at the start, recognize a database written by another identity: its
     shared documents become local copies (the notes kept, the sharing to join again);
  3. no backup at all (`allowBackup="false"`).

  The second one keeps the most for the user and needs a test of the sync; the first is the
  simplest. Whatever is chosen, the 25 MB of Auto Backup leave out a library with PDFs or lectures,
  so the archive stays the backup to suggest.

  **Decided** (with the user, 2026-11-15): nothing of the notes in the backups of the system. The
  25 MB of the cloud backup would keep the database without its PDFs, images and lectures (a
  restore that looks complete and is not), and the tablets without Google services have no such
  backup at all. The preferences are in the same database (the store is the `PreferenceStore`), so
  "the preferences only" leaves nothing: the third option, `allowBackup="false"`, with
  `data_extraction_rules.xml` excluding every domain from the device transfer too (Android 12 and
  later transfer an application despite `allowBackup="false"`). The backup is the archive and the
  sync; a restored device never holds the shared documents of another identity.
* **The sync over the internet stays off by default**, and is labeled beta in its settings.

### Phase 3 results

* `allowBackup="false"` and `res/xml/data_extraction_rules.xml` (every domain excluded from the
  cloud backup and from the device transfer) in the manifest of PencilNotes for Android, read back
  from the debug and the release APK (`aapt dump xmltree`).
* On the emulator, with the local transport of the backups, `bmgr backupnow
  com.studiomath.pencilnotes` answers "Backup is not allowed" (the transport of Google given back
  afterwards).

### Phase 4 results

* `docs/privacy.md`, in English and Italian, from what the code sends: Gemini with the user's key
  (`generativelanguage.googleapis.com`), Ollama on the local network, the models from Hugging Face,
  the sync (mDNS and direct connections on the local network; over the internet, off by default,
  direct or through the relays of n0 or the user's, end-to-end encrypted; the iroh endpoint is the
  `Minimal` preset, with no discovery service). No analytics, no crash reporting, no server of the
  developer. The contact address is pencilnotes@proton.me (the user's, 2026-11-15).
* `docs/play-console.md`: the listing, the forms of App content, Data safety (the requests to
  Gemini declared as collected and optional; the sync exempt as end-to-end encrypted), the two
  foreground services with their videos, the other declarations and the steps of the upload. The
  merged manifest of the release has no `AD_ID`.
* Found: `SyncService` lasts as long as devices are connected, while the policy of Google describes
  data sync as a transfer that ends. Written as a risk in `docs/play-console.md`, with the change to
  make if the review refuses it.
* The policy is published at <https://valerioisufi.github.io/pencilnotes/privacy/> (the repository
  `valerioisufi/valerioisufi.github.io`, public, GitHub Pages; a copy of `docs/privacy.md` to keep
  the same; `DrawView-KMP` stays private until `docs/open-source.md`).

## Left for later

* An automatic archive: the notes exported every so often into a folder the user chooses (Drive,
  Files, a USB key), on Android, iOS and the desktop, in a plan of its own: the backup a user
  expects, in place of the backups of the system.

* The import of `draw_database` (and of the XML of 1.0), if a user of 2.1 ever asks.
* The donation link: kept out of the application on Play (the Payments policy has been applied to
  links to Open Collective), on GitHub and on the site only.
* The same questions on iOS: the Keychain of `SecureStore` is "this device only", so an iCloud
  restore makes a new identity too.
* F-Droid: it builds from the sources, and PDFium comes prebuilt today.
* A CI job that builds and signs the bundle from a tag, with the key in the secrets.
