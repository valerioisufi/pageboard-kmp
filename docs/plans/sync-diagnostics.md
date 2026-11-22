# Sync diagnostics

Setting up the sharing between two devices (a library shared between a MacBook and an iPhone)
often fails at first, and the application says nothing about why: the devices "look for each
other" forever. This plan fixes the cause found on the maintainer's devices and makes every
failure of the local network visible, with what to do about it.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

Asked by the maintainer on 2026-11-22, after the library shared between the MacBook and the
iPhone did not sync.

What was found on the MacBook (PencilNotes desktop running since 13:46, checked at 17:30):

* `dns-sd -B _pageboard._tcp` finds the MacBook (`n=Valerio MacBook`), and `dns-sd -L` resolves
  it to `192-168-0-248.local.:28461`. The MacBook is on **192.168.0.76** now: 192.168.0.248
  answers no ping, its port is closed, while 192.168.0.76:28461 accepts connections. The iPhone
  finds the MacBook and connects to an address that is gone.
* The cause: `JmdnsLanDiscovery` creates its `JmDNS` once (`by lazy`), bound to the address of the
  interface at that moment. The address changed meanwhile (the Wi-Fi uses a private address,
  `ae:7a:…`; a rotating private address or a new lease gives a new IP: not verified). Nothing
  creates `JmDNS` again: `LanSync.refresh(networkChanged = true)` announces again on the same
  instance, with the old address. Bound to an address that is gone, the MacBook most likely does
  not hear the iPhone either.
* The firewall of macOS is off. The internet sync is off (`InternetSettings.enabled = false` by
  default; the process has no socket of iroh), so the local network is the only way.
* PencilNotes was not running on the iPhone at the time (`devicectl device info processes`): iOS
  stops the Bonjour of an application in the background.

Why nothing is said:

* `LanSync.open` turns a failed connection (`SyncException`) or a timeout into `null`: no trace.
* iOS: `rememberLocalNetworkAccess()` returns `true`. `BonjourLanDiscovery` implements neither
  `netServiceBrowser(_:didNotSearch:)` nor `netService(_:didNotPublish:)`, where a local network
  permission refused or a missing `NSBonjourServices` arrive: a "Don't Allow" given once stops the
  sharing for good, silently.
* Android: `NsdLanDiscovery` leaves `onStartDiscoveryFailed` and `onRegistrationFailed` empty.
* The `Info.plist` of the macOS application (`pencilnotes/desktopApp/build.gradle.kts`) has the
  microphone only: no `NSLocalNetworkUsageDescription`, no `NSBonjourServices`. The application is
  signed ad hoc (`Signature=adhoc`), a new signature at each build: macOS may ask again for the
  local network, or refuse it, after a rebuild (not verified).
* The interface knows `isOnline` (started or not), `otherVersionMet` and the `JoinStatus`
  (`LOOKING_FOR_OWNER` while nothing happens): nothing of what the network does.

## Requirements

1. **The announcement follows the network.** When the address of this device changes (a new
   lease, another Wi-Fi, a cable), the desktop announces itself and listens on the new address
   within one round of `LanSync` (5 s), without restarting the application.
2. **Every failure of the discovery is known** on the three platforms, with its cause: the local
   network permission refused (iOS, macOS when it can be known), the configuration missing from
   the `Info.plist` of the application, no address on a local network, announcing or browsing
   failed (with the code of the system).
3. **Every device that cannot be reached is known**: its name, the address tried, the cause (timeout,
   refused, unreachable), since when; cleared once connected.
4. **The interface says it, with what to do**: in the dialog of sharing and of joining, one line per
   problem in the words of the user ("The local network is not allowed: Settings › Privacy &
   Security › Local Network › PencilNotes"; "The MacBook was found at 192.168.0.248 but does not
   answer"; "Keep PencilNotes open on the iPhone while the devices meet"), the details (addresses,
   codes) folded under it.
5. **A check on request**: a "Check" button running the checks at once (listening, the
   announcement of this device resolved back to one of its addresses, the devices found and a
   connection to each) and showing what passed and what did not.
6. **Nothing travels.** The diagnostics stay on the device, in memory: no protocol change
   (`PROTOCOL_VERSION` stays 1, `ProtocolFormatTest` unchanged), no stored format, no address in a
   log sent anywhere.
7. **Untrusted input stays so.** What another device announces (its name, its address) is shown as
   text, bounded as today (`MAX_NAME_LENGTH`); a device announcing many addresses or names does
   not grow the diagnostics without bound (the last ones per device, a few devices).
8. The rules of the libraries: headless modules without Compose (`-sync`, `-sync-lan`), explicit
   API recorded (`updateKotlinAbi` for the changes meant), no global mutable state; the interface
   in `pageboard-sync-ui`, strings in the 7 languages.

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| The address to be on, looked at again; `JmDNS` created again when it changes | `JmdnsLanDiscovery` (`-sync-lan`, jvm), with the addresses read through a `LocalAddresses` function given in (tests) | Information Expert, Protected Variations |
| Taking the network as it is now, saying whether the announcement must be made again | `LanDiscovery.refresh(): Boolean` (default `false`) | Polymorphism |
| A problem of the discovery: what, the code of the system, since when | `DiscoveryProblem` (sealed: `PermissionDenied`, `MissingConfiguration`, `NoLocalAddress`, `AdvertiseFailed`, `BrowseFailed`) | Polymorphism |
| The problems of the discovery of each platform | `LanDiscovery.problems: StateFlow<List<DiscoveryProblem>>` in `JmdnsLanDiscovery`, `BonjourLanDiscovery`, `NsdLanDiscovery` | Information Expert |
| The configuration of the application checked (iOS: `NSBonjourServices` with `_pageboard._tcp`, `NSLocalNetworkUsageDescription` in the main bundle) | `BonjourLanDiscovery` | Information Expert |
| The devices found, the connections tried, their last failure | `LanSync.reachability: StateFlow<List<PeerReachability>>` (name, address, last failure, since, connected) | Information Expert (it makes the connections) |
| What the user is told: the problems of the local network, of the internet, the other version met | `SharingStatus` (`-sync-ui`), built by `Collaboration.status` from the flows above | Controller, Indirection |
| The check on request | `Collaboration.check(): CheckReport`, with `LanSync.check()` doing the network part | Controller, Pure Fabrication |
| Showing the problems and the check | `SharingProblems` composable in `ShareSheet` and `JoinDocument` | High Cohesion |

### The address that follows the network (desktop)

`JmdnsLanDiscovery` keeps the address it is bound to and the `JmDNS` on it in a `MutableStateFlow`.
`refresh()` reads the addresses again (`chooseLocalAddress`, as today): when the chosen one
differs, it closes the `JmDNS`, creates one on the new address and returns `true`; `LanSync`
then announces again (as for `networkChanged`). `services` is a `flatMapLatest` over the current
`JmDNS`: the listener follows the new instance, the devices found are found again. `LanSync`
calls `discovery.refresh()` at each round of `refresh()` (every `retryMs`), so it is at most 5 s
late; a read of the interfaces costs microseconds.

No address at all (Wi-Fi off): `NoLocalAddress`, and no `JmDNS` on `InetAddress.getLocalHost()` (the
loopback today, which announces an address nobody can reach).

### The problems of the discovery

* **iOS** (`BonjourLanDiscovery`): `netServiceBrowser(_:didNotSearch:)` and
  `netService(_:didNotPublish:)` give the error dictionary (`NSNetServicesErrorCode`,
  `NSNetServicesErrorDomain`). The local network refused comes as the DNS-SD code
  `kDNSServiceErr_PolicyDenied` (-65570) → `PermissionDenied`; a service missing from
  `NSBonjourServices` as `NSNetServicesMissingRequiredConfigurationError` (-72008) →
  `MissingConfiguration` (to be verified on the device in phase 2, the codes noted in the plan). The
  `Info.plist` is checked at start too, so a third-party application without the keys learns it
  before trying. `rememberLocalNetworkAccess()` stays `true` (iOS has no API to ask the state): the
  refusal is known from the failure.
* **Android** (`NsdLanDiscovery`): `onStartDiscoveryFailed`, `onRegistrationFailed` with their code
  (`FAILURE_INTERNAL_ERROR`, `FAILURE_MAX_LIMIT`...) → `BrowseFailed`, `AdvertiseFailed`.
* **Desktop** (`JmdnsLanDiscovery`): `JmDNS.create` or `registerService` failing (an `IOException`,
  "No route to host" when macOS refuses the multicast) → `AdvertiseFailed` / `BrowseFailed` with
  the message; on macOS such a failure is also shown as "the local network may be refused" (the JVM
  cannot ask the state).

A problem is cleared when what failed works again (a search started, an announcement published).

### The devices that cannot be reached

`LanSync.open` keeps, per device, the address tried and the outcome instead of dropping it: the
cause of the `SyncException` (its message and its cause: `ConnectException`, `NoRouteToHost`,
timeout), the time of the first failure in a row; cleared once `engine.connect` runs. At most the
devices of `known` (forgotten after `FORGET_AFTER` as today) and one entry each: bounded by what is
already bounded.

A device found whose address is not on a local network of this device (another subnet, a
self-assigned 169.254 address) is noted as such: "found on another network".

### What the user is told

`SharingStatus` is a list of problems in priority order, each with a kind for the interface (the
text is chosen there, from resources):

1. The local network not allowed / the configuration missing (nothing else can work).
2. No local network.
3. A device found but not answering (its name and address), several grouped.
4. Nobody found after a while (30 s) with documents shared: "Open PencilNotes on the other device,
   on the same Wi-Fi"; with the internet off, "or turn on the sync over the internet".
5. Another version met (today's `otherVersion`).

`SharingState` (the state of the share sheet) gets `problems: List<SharingProblem>` in place of the
pair `offline`/`otherVersion`, which become two of its kinds.

### The check

`Collaboration.check()` starts the network if it is not, then within 10 s: the listener is on; the
announcement of this device, resolved by its own discovery, has one of the addresses of this device
(the case found on the MacBook); the devices found, and a connection to each (the handshake only, as
`LanTransport.connect`). The report lists each step passed or failed with its cause, shown in the
dialog and copyable as text (the addresses are the user's own; nothing is sent).

### Protocol and storage

Nothing changes in what travels or is stored: the diagnostics are flows in memory, the check opens
the connections that `LanSync` would open anyway.

### Configuration of the applications

The macOS application declares the local network as the iOS one does:
`NSLocalNetworkUsageDescription` and `NSBonjourServices` (`_pageboard._tcp`) in the `infoPlist` of
`pencilnotes/desktopApp` (the sample does not share documents: nothing to declare there).

## Plan

| Phase | Content | Status |
|---|---|---|
| 1. The address follows the network | `LanDiscovery.refresh()`, `JmdnsLanDiscovery` creating `JmDNS` again on a new address, `LanSync` announcing again; no announcement on the loopback; the `Info.plist` keys of the macOS applications. Tests: a discovery given a fake list of addresses that changes (announced again, found again), no address | done |
| 2. The problems of the discovery | `DiscoveryProblem`, `LanDiscovery.problems` on the three platforms, the `Info.plist` checked on iOS; the codes of iOS verified on the iPhone (permission refused, key removed in a scratch build). Tests: JVM (create failing), the mapping of the codes of iOS and Android | done (the codes of iOS on the iPhone: left to the maintainer, see phase 5) |
| 3. The devices that cannot be reached | `LanSync.reachability`, the failures of `open` kept and cleared, bounded; a device on another network. Tests with a `LanTransport` failing (timeout, refused), a device back | to do |
| 4. Interface | `Collaboration.status` and `check()`, `SharingProblems` in the share and join dialogs, the check and its report; strings in the 7 languages; Roborazzi of each kind (recorded in the image of `tools/screenshots`), the desktop on a scratch directory | to do |
| 5. Real devices | The MacBook and the iPhone: the IP of the MacBook changed while sharing (the lease renewed), the permission refused on the iPhone and given back, the application in the background on the iPhone | to do |

### Phase 1 results

* `JmdnsLanDiscovery` keeps an `MdnsResponder` (JmDNS, or a fake in the tests) on the current
  address in a `StateFlow`; `services` follows the current one (`flatMapLatest`). `refresh()`
  reads the addresses again and, when the chosen one differs, closes the responder, creates one on
  the new address, announces there what was announced and returns `true`; `LanSync.refresh` then
  announces again. An address given to the constructor stays.
* Without an address on a local network nothing is created (the loopback of
  `InetAddress.getLocalHost()` before); the announcement waits for an address.
* The public constructors are the same (`@JvmOverloads` keeps `JmdnsLanDiscovery()` in the
  bytecode; the dump of the ABI no longer lists it, the class files do). `LanDiscovery.refresh()`
  has a default (`false`): the ABI dump records it, a change meant.
* Tests: `JmdnsAddressTest` (a new address: a new responder announcing the same, the devices found
  through it; no address until one comes; an address given stays; an unused discovery stays idle),
  `LanSyncTest.aDiscoveryOnANewAddressIsAnnouncedAgain`.

### Phase 2 results

* `DiscoveryProblem` (`-sync-lan`, common): `PermissionDenied`, `MissingConfiguration(missing)`,
  `NoLocalNetwork`, `AdvertiseFailed(code, message)`, `BrowseFailed(code, message)`;
  `LanDiscovery.problems` (a `StateFlow`, empty by default). `DiscoveryProblems` keeps one of each
  kind, cleared once what failed works again (a search started, an announcement published).
* Desktop: no address → `NoLocalNetwork`; JmDNS not created → `BrowseFailed` with the message of
  the exception; an announcement failing → `AdvertiseFailed`.
* iOS: `netServiceBrowser(_:didNotSearch:)` and `netService(_:didNotPublish:)` mapped by
  `bonjourProblem` (`-65570` `kDNSServiceErr_PolicyDenied` → `PermissionDenied`, `-72008` →
  `MissingConfiguration`, the other `NSNetServices…Error` by name); the `Info.plist` of the main
  bundle checked at each announcement and search (`NSBonjourServices` with `_pageboard._tcp`,
  `NSLocalNetworkUsageDescription`).
* Android: `onStartDiscoveryFailed`, `onRegistrationFailed` with the name of the code; a
  `SecurityException` of `NsdManager` (the local network not allowed, Android 17) →
  `PermissionDenied`.
* iOS may also stay silent when the permission is refused (nothing found, no failure): the
  interface says it among the hints of "nobody found" (phase 4).
* Tests: `DiscoveryProblemTest` (one of each kind, cleared; the codes of Bonjour),
  `JmdnsAddressTest.withoutANetworkOrAResponderTheProblemIsSaidUntilItWorks`.

## Decisions

### To be taken by the maintainer

* The plan as it is, with its choices.
* Whether the addresses (192.168.0.248) are shown to the user, folded under the problem (proposed:
  yes, they say the most to whoever debugs it), or only in the report of the check.
* Whether a hint to turn on the sync over the internet is shown when the local network fails
  (proposed: yes, only when it is off and some documents are shared).

### Taken by me (revertible)

* `JmDNS` looked at again at each round of `LanSync` rather than a listener of the network of the
  system: the JVM has none, and 5 s is enough for a change of address.
* `refresh()` and `problems` on `LanDiscovery` with defaults (`false`, an empty flow), so a
  discovery of a third party keeps compiling; the ABI dump of `-sync-lan` changes for it.
* The problems as data and the words in the interface, rather than messages in the libraries: the
  strings are translated in `-sync-ui` only.
* The permission of iOS known from the failure of Bonjour, not asked beforehand: there is no API
  for its state, and the usual workaround (a probe connection) shows the prompt at a moment the
  user did not choose.
* `NSNetService` kept (deprecated, still working) rather than moving to `NWBrowser` /
  `NWListener` now: `NWBrowser` gives the state of the permission better (`.waiting` with
  `PolicyDenied`), but the move is a change of its own.

## Left for later

* `NWBrowser` / `NWListener` on iOS in place of `NSNetService` (and on macOS through cinterop, if
  the desktop ever gets a native discovery).
* The local network permission of macOS for the JVM: no API to read it; signing the development
  builds with a stable identity (an Apple Development certificate) so macOS keeps the permission
  across rebuilds, if phase 5 shows that the ad hoc signature loses it.
* The firewall of Windows (the inbound rule of the port) and of macOS when it is on: detected by the
  check as "found but not answering", not by name.
* A log of the sync on the device that the user can export for a bug report.
