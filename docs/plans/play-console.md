# PencilNotes on Google Play: the answers of the Play Console

What the Play Console asks for the release of PencilNotes 3.0 (`com.studiomath.pencilnotes`), with
the answers drawn from the code as it is (2026-11-15, `docs/privacy.md`). The forms are the maintainer's;
their wording changes, so each answer says what it rests on. Check the help of each form before
sending it.

## Store listing

* **The listing is in the repository**, in the layout of fastlane (which F-Droid reads too):
  `pencilnotes/androidApp/fastlane/metadata/android/<en-US|it-IT>/` holds `title.txt`,
  `short_description.txt`, `full_description.txt` and `images/` (`icon.png` 512 px,
  `featureGraphic.png` 1024 x 500, `phoneScreenshots`, `sevenInchScreenshots`,
  `tenInchScreenshots`). Copy them into the Play Console, one language at a time. How they were
  made: `docs/plans/store-listing.md`.
* **Category**: Education (students and lectures); Productivity is the other fit.
* **Countries**: today one country of 178; at the release, at least Italy, the European Union, the
  United Kingdom and the United States.

## App content

| Form | Answer | Why |
|---|---|---|
| Privacy policy | <https://valerioisufi.github.io/pencilnotes/privacy/> (a copy of `docs/privacy.md`) | the microphone and the network require one |
| App access | All functionality available without special access | no account, no login |
| Ads | No ads | none |
| Content rating | Reference: Utility, productivity, communication or other; no violence, sexuality, language, drugs, gambling; users can share content with each other: **yes** (shared documents, between devices invited by their owner) | the sync of shared documents |
| Target audience | 13-15, 16-17, 18 and over (not under 13) | out of the Families policy; the privacy policy says so |
| News app | No | |
| Government app, financial features, health | No | |
| Data deletion | No account is created: nothing to delete on the developer's side | `docs/privacy.md` |

## Data safety

Data **collected** means sent off the device by the app; data **shared** means given to a third
party. The exemptions of Google that apply, as worded on 2026-11-15:

* data processed on the device only is not collected;
* data sent end-to-end encrypted, unreadable by anyone but the sender and the recipient, need not be
  declared: the sync, LAN and internet;
* a transfer the user starts and expects is not *sharing*: the requests to Gemini with the user's own
  key, to Ollama on the user's computer.

The requests to Gemini still send data off the device, so they are declared as collected, optional:

| Data type | Collected | Shared | Optional | Purpose | Encrypted in transit |
|---|---|---|---|---|---|
| Audio: voice or sound recordings (the lecture) | Yes, when the user turns on Gemini | No | Yes | App functionality | Yes (HTTPS) |
| Photos and videos: photos (images of the pages seen) | Yes, idem | No | Yes | App functionality | Yes |
| Files and docs (the text of the slides and notes) | Yes, idem | No | Yes | App functionality | Yes |

Everything else (location, personal info, contacts, app activity, device IDs, crash logs,
diagnostics): **not collected**. "Is all of the user data collected by your app encrypted in
transit?": yes. "Do you provide a way for users to request that their data is deleted?": nothing is
kept by the developer; the data on the device is deleted with the notes or the app.

## Permissions and foreground services

* **Foreground service, microphone** (`LectureRecordingService`): the lecture recording the user
  starts goes on with the screen off or the app in the background, with its notification, until the
  user stops it. Video (30 s): open a note, the microphone button, start, lock the screen, the
  notification, unlock, stop.
* **Foreground service, data sync** (`SyncService`, `BackgroundSync` in `drawview-sync-ui`): the
  transfer of the changes of the shared documents to and from the other devices of the user, with
  its notification. It starts while the app is shown and some device is connected, and stops when
  none is, or when Android ends the time it gives to data sync (`onTimeout`, Android 15: 6 hours a
  day). Video: share a note with a second device, leave the app, write on the second device, the
  notification and the change arriving.

  **Risk**: the policy of Google describes data sync as a transfer that ends (upload, download,
  backup, import); a service that lasts as long as devices are connected may be refused. If it is,
  the answer is to keep it only while changes are being sent or received, and to let the
  connections go when the app is left (as on iOS): a change of `BackgroundSync`, in phase 5 or in a
  plan of its own.
* **RECORD_AUDIO, POST_NOTIFICATIONS**: asked the first time the user records a lecture.
* **INTERNET, ACCESS_LOCAL_NETWORK**: the sync, Gemini, the download of the models.

## Other declarations

* **Trader status (Digital Services Act)**: not a trader, while the app is free and earns nothing.
* **Export compliance**: the app uses encryption (HTTPS; end-to-end encryption of the shared
  documents with standard algorithms).
* **Advertising ID**: not used. No library of the app declares `AD_ID` (checked in the merged
  manifest at each release).

## The release

1. `./gradlew :pencilnotes-android:bundleRelease` on the machine with the upload key
   (`pencilnotes.signing.*` in `local.properties`).
2. Internal testing: upload `pencilnotes-android-release.aab` and
   `build/outputs/mapping/release/mapping.txt` (deobfuscation file).
3. Install it from the Play Store on the tablet, over 2.1 where it is, and go through the list of
   phase 5 of `docs/plans/android-release.md`.
4. Production, staged (20 %, then 100 %).
