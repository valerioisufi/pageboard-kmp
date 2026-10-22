# Synchronization over the internet

The devices sharing a document synchronize today on the same local network only
(`drawview-sync-lan`). This plan adds the internet: devices on different networks (home,
university, mobile data) connect to each other directly when they can and through a relay when
they cannot, with [iroh](https://github.com/n0-computer/iroh) (Rust) compiled for every platform
of the libraries. The devices are online at the same time: handing the operations to a device
that comes online later (store and forward) is left to a plan of its own.

The responsibilities are assigned following the GRASP principles (Information Expert, Creator,
Controller, Low Coupling, High Cohesion, Polymorphism, Pure Fabrication, Indirection, Protected
Variations).

## Starting point

* `SyncEngine` works on any `SyncChannel` (whole frames, in order, both ways): `accept` serves
  the channels the other devices open, `connect` the ones this device opens. `LanTransport` (TCP)
  and `InMemoryTransport` implement `SyncTransport`. `LanSync` is the Controller of the local
  network: it announces the tags of the documents shared (mDNS), connects to the devices sharing
  the same ones, makes the lost connections again every 5 s and remembers the devices found.
* A transport is not trusted: everything after the `OpenFrame` (protocol version and tag of the
  document, in clear) is encrypted with the key of the document (`SecureChannel`), each device
  proves its `DeviceIdentity` (ECDSA P-256, kept in the `SecureStore`) in its `Hello`, and the
  membership signed by the owner says who works on the document.
* `Invitation.address` is "in the terms of a `SyncTransport`", but the applications make their
  invitations without one: the owner is found on the local network by its tag.
* `SyncCodec` ignores the fields it does not know, but a message of a kind it does not know closes
  the connection (`SyncEngine.otherVersionMet`).
* Two devices on different networks never synchronize, and neither do two devices on a network
  isolating its clients (guest Wi-Fi, many university networks): see the risks of
  [collaboration.md](collaboration.md).
* The native code is C and C++ only: one Android library per engine (`drawview-native-*`, CMake
  through the NDK), JNI libraries in the desktop jars (`buildDesktopNativeLibs`, five platforms
  built by the CI for a release), static libraries and cinterop on iOS (`build_ios.sh`). There is
  no Rust in the build, and no Rust toolchain on the development Mac.
* The operations travel sealed (`SealedOperation`), ready for a store-and-forward relay that is
  not built.

## Requirements

1. Two devices sharing a document, online at the same time on different networks (behind NATs,
   on mobile data, behind a carrier-grade NAT), synchronize as they do on the local network:
   operations, presence, resources and attachments, with the protocol above the channel
   unchanged.
2. The connection is direct whenever the network allows it (hole punching), through a relay
   otherwise. The relay carries all of the traffic and reads none of it: it has neither the key
   of the document nor the keys of the connection.
3. Nothing is announced in public: no directory says who shares what. A device learns where the
   members of its documents are from the members only (the invitation, the connections).
4. The application gives the relay (its address and access token). The relay can be hosted on a
   small virtual machine. PencilNotes uses the relay of its user; the tests run a relay in the
   process; the public relays of n0 serve the development until the relay exists.
5. The local network keeps working without the internet. Both transports run at the same time,
   and the engine keeps one connection per device and document.
6. The rules of the libraries hold: a headless module; the native library ships with the module
   that uses it (Android library, JNI in the desktop jar for the five platforms, static library in
   the iOS klib); no global state in the API; explicit API and ABI dump; an application without
   the module works on the local network only; every artifact carries the licenses of the Rust
   crates it contains.
7. The mobile life cycle: the connections come back quickly after the background and after a
   change of network (Wi-Fi to mobile data). No new entitlement on iOS (no multicast), no new
   permission on Android (`INTERNET` only).
8. The data of the user: no change to the Room schema or to the stored formats. The only new
   storage (the addresses of the devices) is versioned from its first version. The protocol
   changes are declared (version 2, see [Protocol](#protocol-version-2)).

## Design

### Responsibilities

| Responsibility | Class (module) | GRASP |
|---|---|---|
| A connection to another device by its key, direct or relayed, with streams | the iroh `Endpoint`, behind the C API of `native/iroh-bridge` (Rust) | Indirection, Protected Variations |
| The bridge seen from Kotlin: endpoints, connections, streams as numbers, blocking calls with a timeout | `IrohBridge` (an internal interface whose members are public, since JNI is named after them), `IrohJni` (Android, desktop), `IrohCinterop` (iOS); `irohBridge` is `null` without the native library | Pure Fabrication, Polymorphism |
| Reaching a device and being reached | `IrohTransport : SyncTransport` (`drawview-sync-iroh`): one QUIC connection per device, one bidirectional stream per document | Polymorphism, Protected Variations |
| A stream as a channel | `IrohChannel : SyncChannel` (frames with their length, delimited by the bridge) | Polymorphism |
| The key of the connections of this device | `IrohSecret` (Ed25519, in the `SecureStore`), apart from the `DeviceIdentity` | Information Expert |
| Where a device can be reached, proved by the device | `DeviceAddress` (`drawview-sync-protocol`): device, transport, address (opaque), time, signature by the `DeviceIdentity` | Information Expert, Creator (each device makes and signs its own) |
| Carrying the addresses between the members | `SyncEngine`: its own in the `Hello`, those of the members of the document in `DeviceAddresses`, relayed; it never reads them | Protected Variations |
| Keeping the addresses | `AddressBook` port (`drawview-sync`), in memory by default; one versioned value of the `PreferenceStore` in the applications | Protected Variations |
| Working with the devices over the internet | `IrohSync` (`drawview-sync-iroh`), like `LanSync`: the endpoint, its address published, the members not connected, the retries, the changes of network, the foreground | Controller |
| The relay of an application | `RelayConfig` (addresses, access token) | Protected Variations |
| Showing how each participant is connected, the settings of the relay | `Collaboration` (`drawview-sync-ui`), the settings of PencilNotes | Controller |

### Connections

* The connections of the synchronization use the ALPN `drawview/sync/2`. `IrohTransport` keeps
  one connection per device: `connect(address)` opens a bidirectional stream on it (and the
  connection when it is missing), and the streams the other devices open are its `incoming`
  channels. The engine then works as over TCP: `OpenFrame`, `SecureChannel`, `Challenge`,
  `Hello`.
* Who connects: as with `LanSync`, the device with the smaller identity first, the other one when
  the connection is still missing a moment later. A device already connected about a document
  (through any transport, see `SyncEngine.peers`) is not connected again; when both transports
  connect at once, the engine keeps one of the two connections (its rule for two connections
  between the same devices). On the same network iroh finds the direct path itself, so neither
  transport needs to be preferred.
* The `Ping` of the engine (4 s, lost after 12 s) stays; iroh keeps the NAT mappings alive on its
  own.
* After the background and at every change of network, `IrohSync.refresh()` tells the endpoint
  (`network_change`) and makes the missing connections again.
* The `OpenFrame` now travels inside the TLS of QUIC: only the other device sees the tag.

### Addresses

* The address of a device on iroh is its endpoint identifier (an Ed25519 public key), its home
  relay and the direct addresses it knows, written as text by the bridge; the engine never reads
  it.
* `DeviceAddress(device, transport, address, issuedAt, signature)` is signed by the
  `DeviceIdentity` of the device. A device keeps an address only when its signature is right and
  the device is a member of a document they share.
* Exchange: a device gives its own addresses in its `Hello` (`addresses`). After the membership,
  each side sends the addresses of the members of the document it knows (`DeviceAddresses`). An
  address received is relayed to the other connections of the documents of that device. Of two
  addresses of the same device and transport, the more recent one wins.
* The invitation carries the addresses of the owner (`addresses`, by transport, instead of
  `address`): the device joining reaches the owner over the internet. On the local network the
  owner is still found by its tag.
* In the link, each address is `a.<transport>=<address>`. The links are stored too: the device
  joining keeps its link in `SharingState.joining`. So `a=<address>`, the form before, is read as
  the address on the local network (`lan`).
* `AddressBook` (a port of `drawview-sync`): the addresses by device, kept in the preferences of
  the store (`PreferenceStore`, one JSON value with its version). The Room schema does not change.
  An address is forgotten when its device is not a member of any document shared any more.
* The engine signs the addresses of this device (it holds the `DeviceIdentity`) when a transport
  gives them (`SyncEngine.publishAddress`), and gives the controllers the addresses of the
  members (`SyncEngine.addressesOf`).
* Nothing is published: no DHT, and the DNS address lookup of iroh is off. Only the members of a
  document know the endpoint identifiers of each other.

### Protocol (version 2)

* Changes: `Hello.addresses`, the message `DeviceAddresses`, the addresses of the invitation (the
  link repeats `a.<transport>=<address>`), the ALPN on iroh.
* `PROTOCOL_VERSION` goes from 1 to 2, and `ProtocolFormatTest` records the format of version 2.
  A device of version 1 is refused at the first frame and told (`otherVersionMet`). The devices of
  version 1 need not be compatible (the decision of the user, 2026-10-22), and the new kind of
  message would close their connections anyway, in the middle of a session: the version makes
  the refusal clean.
* The version is authenticated with every frame and every sealed operation. Nothing is stored
  sealed, so no stored data changes. The invitation links made before (without addresses) keep
  working.

### Native bridge

* `native/iroh-bridge`: a Rust crate (static and dynamic library) on iroh 1.4.1, with `Cargo.lock`
  committed and `rust-toolchain.toml` fixing the version of Rust (1.99.1) and its targets. Each
  endpoint owns its tokio runtime.
* Endpoints, connections and streams are numbers, not pointers. A number closed or never given is
  refused, so a call racing a close fails instead of touching freed memory.
* The functions:
  * bind an endpoint (its secret key, its relays and their token, the ALPN);
  * its address and its identifier;
  * connect and accept;
  * open and accept a stream;
  * send a frame, receive one with a timeout, whether the stream ended;
  * close;
  * what a connection goes through (direct or relay, round-trip time);
  * a change of network.

  The errors come back as a code and a message (on the same thread), and every function catches
  the panics.
* Each stream has a task reading its frames (4 bytes of length, then the bytes) into a queue of 8,
  and a task writing the frames of another queue. `send` and `receive` only wait on the queues,
  so a timeout never leaves half a frame read or written. The queues are bounded, which keeps the
  flow control of QUIC.
* Kotlin calls the functions blocking, off the main thread, with short timeouts and again (phase
  2): a coroutine cancelled lets go within one of them. Closing a stream or a connection ends the
  calls waiting on it.
* Desktop and Android: the JNI functions are exported by the same crate through `jni-sys` 0.4 (what
  `jni` 0.22 under iroh uses, so it is linked once), on numbers, strings and byte arrays only. iOS:
  the header (`include/iroh_bridge.h`, written by hand: twenty functions) is read by cinterop, and
  the static library goes into the klib.
* Android: iroh reads the DNS servers of the system and the network interfaces through JNI, so the
  bridge receives the Java VM and the application context before any endpoint
  (`install_android_jni_context`). The context comes from `drawview-platform`, which keeps it
  already.
* iOS: the klib links `-framework Security -framework SystemConfiguration -framework
  CoreFoundation -framework Network -lresolv`. The C code inside the crates (ring, blake3) is
  built for the minimum iOS of Kotlin/Native (`IPHONEOS_DEPLOYMENT_TARGET=15.0`).
* The build (decided in phase 0): Gradle tasks running cargo, one task type in `build-logic`.
  * `drawview-native-iroh` builds the three Android ABIs with the linkers of the NDK of the
    module, given by environment variables (no cargo-ndk).
  * The desktop library is built with the other JNI libraries (`buildDesktopNativeLibs`, the CI
    matrix).
  * `:drawview-sync-iroh:buildIrohBridgeIos` builds the two iOS targets, without the debug
    symbols of `std` (`strip -S`: 8.8 MB each in the klib instead of 30 MB).
  * Cargo is found in `local.properties` (`rust.cargo`), then in `~/.cargo/bin` and on the
    `PATH`: Gradle started by Android Studio does not see the `PATH` of the shell.

  Corrosion inside `native/CMakeLists.txt` was left: CMake would have to find cargo at every sync
  of Android Studio, for no gain over a task.
* A release is built for its size: `opt-level = "z"`, LTO, one codegen unit, stripped.
* `Cargo.lock` keeps `libc` at 0.2.189. From 0.2.190 `libc::in6_ifreq` exists on macOS only, and
  `netdev` (under iroh) does not build for iOS without it; the fix is open (shellrow/netdev#192).
* An iOS application can link one Rust static library only (two would have the symbols of `std`
  twice). If another module in Rust comes (the runtime of the local models), the crates go into
  one library.
* The TLS cryptography is ring, the default of iroh: it builds for every target tried in phase 0.
* The licenses: cargo-about writes the notices of the crates (`native/iroh-bridge/
  THIRD_PARTY_NOTICES.md`, again when `Cargo.lock` changes, from `about.toml` and `about.hbs`).
  The artifacts of `drawview-sync-iroh` and `drawview-native-iroh` carry the notices, and
  `THIRD_PARTY_LICENSES.md` names them. `about.toml` accepts permissive licenses only. The 316
  crates of the run time all have one: MIT, Apache-2.0, BSD, ISC, Zlib, Unicode-3.0, and
  CDLA-Permissive-2.0 for the root certificates.

### Relay

* The `iroh-relay` server of the same version as the crate:
  * HTTPS on port 443 with a Let's Encrypt certificate, which needs a domain name (a free dynamic
    DNS name will do);
  * port 80 for the certificate;
  * QUIC address discovery on UDP port 7842.

  Access is by a shared token (`access.shared_token`), which the applications receive with the
  address. The configuration and the service unit live in `tools/relay/`, with a guide.
* Where it runs (the user wants a free tier of AWS or Google Cloud):
  * the e2-micro of the Google Cloud free tier runs in the United States only (`us-west1`,
    `us-central1`, `us-east1`) with 1 GB of outbound traffic a month. That is enough for
    coordinating and keeping the connections alive, since most connections go direct, but tight
    for traffic through the relay: the audio of a lecture is about 11 MB an hour, plus the
    presence while drawing;
  * an AWS account opened after 15 July 2025 gets credits for a free plan of six months, then
    pays, and a public IPv4 address is billed by the hour.

  So the start is an e2-micro with a budget alert. Phase 7 measures the traffic through the relay
  (its metrics) and moves it if it does not fit. The price of the external address is to be
  checked when the machine is created.
* Until then the development uses the public relays of n0 (rate-limited). The tests use a relay on
  the loopback over plain HTTP, as `iroh-relay --dev` does: `dev_relay`, a binary of the crate
  (feature `dev-relay`, never in the libraries), built by `:drawview-sync-iroh:buildIrohDevRelay`.
  The JVM tests start it; the iOS tests connect directly.

### Modules

```
drawview-sync-protocol  + DeviceAddress
drawview-sync           + addresses in Hello, DeviceAddresses, AddressBook, protocol 2
drawview-sync-iroh      IrohTransport, IrohChannel, IrohSync, RelayConfig (headless, on sync)
drawview-native-iroh    Android: libiroh_bridge.so (three ABIs)
drawview-sync-ui        + how each participant is connected
native/iroh-bridge      the Rust crate (C API, JNI)
tools/relay             configuration of the relay, guide
```

## Plan

| Phase | Content | Status |
|---|---|---|
| 0. Spike | A throw-away module, not versioned. The Rust toolchain (rustup, targets). A crate with the C API on the desktop JVM, the iOS simulator and the Android emulator. Checks: two endpoints exchanging frames through a public relay; this Mac and a phone on mobile data (direct or relayed); the size of the library on each platform; the build (Corrosion or cargo-ndk); the TLS cryptography; the network interfaces on Android; iOS without entitlements | done |
| 1. Bridge and build | `native/iroh-bridge`, `drawview-native-iroh`, the desktop JNI in `buildDesktopNativeLibs` and in the CI matrix, the iOS static library and cinterop, `rust-toolchain.toml`, the licenses, the consumer check of the Android library | done |
| 2. Transport | `drawview-sync-iroh`: `IrohNative`, `IrohTransport`, `IrohChannel`, `RelayConfig`. Tests: frames (1.5 MB too); three devices synchronizing over iroh with a relay in the process, on the JVM and the iOS simulator | done |
| 3. Addresses, protocol 2 | `DeviceAddress`, `Hello.addresses`, `DeviceAddresses`, `AddressBook` (a versioned value, with its tests), the addresses of the invitation, `PROTOCOL_VERSION` 2 and `ProtocolFormatTest` | to do |
| 4. Controller | `IrohSync`: the endpoint and its life cycle, its address published, the members connected, the retries, the foreground, the changes of network; with `LanSync` at the same time. Tests: devices "on different networks", through the relay only, a connection lost and made again | to do |
| 5. Relay | `tools/relay/`: the configuration of `iroh-relay` (TLS, token, QUIC address discovery), the service unit, the guide for an e2-micro. The user creates the machine | to do |
| 6. Applications | PencilNotes on Android, iOS and the desktop: the relay in the settings (address, token), synchronization over the internet on or off, how each participant is connected in the sharing dialog; the sample; the strings in the 7 languages; the screenshots | to do |
| 7. Real networks | iPad or phone on mobile data and the Mac at home, the university Wi-Fi, two homes. Measures: the time to connect, how many connections go direct, the traffic through the relay, the battery | to do |

Every phase ends with the checks of `CLAUDE.md` (the JVM tests, the ABI, the Android, iOS and
application builds, the consumer checks when the published API or the dependencies change), and
the desktop applications run on a scratch data directory.

### Phase 0 results

The spike (a throw-away crate and three throw-away builds, not versioned) ran the same self test
everywhere. Two endpoints of the same process were tested: one reachable through the relay only,
the other one directly. Each echoed 50 frames and a frame of 1.5 MB. The relays were the public
ones of n0 (the nearest one, in Frankfurt).

| Check | macOS (command line; JVM through JNI) | iOS simulator (process; Kotlin/Native test through cinterop) | Android emulator (application, API 37, arm64) |
|---|---|---|---|
| Endpoint online (home relay reached) | 0.5 s | 0.5 s (0.9 s to bind the first time) | 0.6–0.9 s |
| Connected by endpoint identifier and relay | 90–120 ms | 90–110 ms | 110–170 ms |
| Direct path after (hole punching) | about 100 ms | 100–220 ms | about 200 ms |
| 1.5 MB through the relay / direct | 2.0–2.2 s / 40 ms | 1.9–2.1 s / 25–30 ms | 2.0 s / 8 ms |
| Size of the library (release, stripped) | 6.7 MB (dylib), 5.5 MB with `opt-level = "z"` | 8.0 MB added to a linked executable (6.2 MB of code) | 7.9 MB arm64, 5.0 MB armv7, 8.7 MB x86_64 |

* The Mac connected to the application in the Android emulator, which sits behind the NAT of
  the emulator. It went through the relay in 99 ms, then direct after 208 ms.
* iOS. iroh 1.4.1 does not build with `libc` 0.2.190 or later (see Native bridge), so `libc` is
  kept at 0.2.189. The static library links into a Kotlin/Native test with the frameworks listed
  in Native bridge. Built for iOS 27.1 (the default of the SDK), the C code of ring and blake3
  made the linker warn; built for iOS 15.0, it does not.
* iOS needs no new entitlement: iroh sends no multicast, since its local discovery is off. The
  direct paths to a device on the same network need the local network permission, which
  PencilNotes already asks for (Bonjour).
* Android. Without the Java VM and the application context, iroh panics in its tasks (seen
  running from the shell); with them it does not. In an application, SELinux refuses the
  privileged netlink reads (`nlmsg_readpriv`, logged). The addresses are found all the same, and
  the hole punching works.
* The relay of the tests: an `iroh-relay` server on the loopback over plain HTTP, run inside a
  Rust test. Every connection works as through n0. The endpoint takes 3 s to be online, because
  there is no QUIC address discovery without TLS.
* The build. cargo builds Android with the linkers of the NDK given by environment variables.
  The Apple targets build as they are. ring builds for every target tried.
* JDK 27: `System.load` prints a warning (a "restricted method"). A future release will refuse it
  without `--enable-native-access`. This concerns every JNI library of the desktop applications,
  not only iroh.
* Not verified: a phone on mobile data, since there is no such device in this session. It is
  left to phase 7.


### Phase 1 results

* `native/iroh-bridge`: the crate, with eight Rust tests. Between two endpoints of the process they
  check:
  * frames both ways, a frame of 1.5 MB too;
  * a receive timing out and made again;
  * a stream closed giving its last frames, then its end;
  * a connection closed ending its streams;
  * the numbers of a closed endpoint refused;
  * two endpoints meeting through a relay on the loopback by identifier only;
  * the text of the addresses.
* `drawview-sync-iroh`: the bridge seen from Kotlin. The same four tests run on the JVM and on the
  iOS simulator (directly), plus one on the JVM through the relay of the tests started as a
  process.
* `drawview-native-iroh` (plugin `drawview.android.rust`, task `CargoBuild` of `build-logic`): the
  three ABIs, built with the NDK of the module.
* The desktop library is built by `build_desktop.sh` with the other JNI libraries. The `Native
  libraries` workflow tests it, bundles it for the five desktop platforms, and also tests the
  bridge on the iOS simulator and checks the Android ABIs.
* Sizes with `opt-level = "z"`:

  | Platform | Size |
  |---|---|
  | macOS arm64 (dylib in the jar) | 3.75 MB |
  | Android arm64-v8a | 4.9 MB |
  | Android armeabi-v7a | 3.4 MB |
  | Android x86_64 | 5.7 MB |
  | iOS (static library) | 8.8 MB per target, 2.8 MB once compressed in the published klib |

* The checks of `CLAUDE.md`, the consumer checks included, pass. The ABI of `drawview-sync-iroh` is
  recorded empty: everything in it is internal until phase 2.


### Phase 2 results

* `IrohTransport` (a `SyncTransport`):
  * `start` binds the endpoint and gives its address;
  * `connect` opens a stream on the connection to the device, made first when missing;
  * `incoming` gives the streams the others open;
  * `networkChanged` and `currentAddress` serve phase 4;
  * `isAvailable` tells whether the bridge is on the platform.

  The channels to the same device share one connection. The side that opened the connection
  closes it with the last of its channels. A connection found closed (the other device went
  away) is opened again once.
* `IrohChannel` (a `SyncChannel`) blocks in the bridge for 250 ms at most, again and again, on
  threads of its own: `Dispatchers.IO.limitedParallelism(256)`, beyond the 64 of `Dispatchers.IO`.
  So a receive cancelled by the engine (a connection silent for too long) lets go at once, and a
  frame is never cut. Each channel waiting for a frame holds a thread (see Left for later).
* `IrohSecret`: the key of the connections, generated with `CryptographyRandom` and kept in the
  `SecureStore`. `RelayConfig`: the relays of the application with their token, which its
  `toString` hides.
* Tests on the JVM and the iOS simulator:
  * frames over iroh (an empty frame and 1.5 MB too), the end seen when the other side closes;
  * a receive cancelled letting go within 250 ms, the channel going on;
  * three channels on one connection, and a new connection once they are closed;
  * a device gone cannot be connected to, nor a transport not started;
  * three `SyncEngine`s keeping a document the same: Bob and Cy join through Ada, and the change
    of Bob reaches Cy relayed by her.

  On the JVM the three devices also run through the relay of the tests, reaching Ada by her
  identifier and relay only.
* The ABI of `drawview-sync-iroh` records `IrohTransport`, `IrohSecret` and `RelayConfig`.

## Decisions

### Taken by the user (2026-10-22)

* iroh rather than libp2p. When the hole punching fails, the relay of iroh carries all of the
  traffic, while the relay (v2) of rust-libp2p has limits of 128 KiB and 2 minutes per circuit by
  default. iroh has fewer pieces to put together and its API is stable since 1.0.
* The relay runs on a free tier of AWS or Google Cloud.
* No compatibility with the devices of protocol version 1.
* Store and forward comes later, in a plan of its own.

### Taken by me (revertible)

* Protocol version 2 (see [Protocol](#protocol-version-2)).
* A C API written by hand, called through JNI and cinterop, rather than UniFFI with Gobley. The
  other way would bring JNA, generated public code against the explicit API, and a third-party
  Gradle plugin, for an API of ten functions. The ink bridge is already made this way.
* The key of the connections apart from the `DeviceIdentity`, as `CLAUDE.md` asks: the signed
  `DeviceAddress` binds the two.
* The addresses are signed by each device and relayed between the members. Two other ways were
  left:
  * in the membership signed by the owner, a member could not be found while the owner is
    offline;
  * published in the DNS, they would be public.
* One QUIC connection per device and one stream per document.
* Blocking calls on `Dispatchers.IO` rather than callbacks from the threads of Rust.
* The modules are named after the engine (`drawview-sync-iroh`, `drawview-native-iroh`), like
  `drawview-store-room` and `drawview-ml-litert`.
* The `iroh-relay` server of n0 rather than one of ours: the store and forward will need a service
  of its own anyway.
* The e2-micro of Google Cloud rather than AWS, whose free plan is credits for six months.
* The local network stays on `drawview-sync-lan`: it works without the internet, and the local
  discovery of iroh would need the multicast entitlement on iOS.
* The build through Gradle tasks running cargo, without Corrosion or cargo-ndk (see Native bridge).
* `opt-level = "z"` (18 % smaller than `"s"`). The traffic is small, and the cryptography of ring
  is assembly anyway.
* The relay of the tests over plain HTTP on the loopback: neither the public relays nor TLS
  certificates are needed for the tests.
* Numbers instead of pointers for the endpoints, connections and streams of the bridge (see Native
  bridge).
* `jni-sys` used directly rather than `jni`: the functions take and give numbers, strings and byte
  arrays only.
* The C header written by hand rather than generated by cbindgen: one tool less for twenty
  functions.
* The calls of the bridge blocking for 250 ms at most and made again, on a dispatcher of their own
  (phase 2), rather than callbacks from Rust into Kotlin.
* The side that opened a connection closes it with its last channel, and the other side leaves it
  to the first one or to the idle timeout of QUIC.
* The Android library built by its own module (`drawview.android.rust`, with the NDK that AGP
  manages), the desktop one by `build_desktop.sh` (which clears the libraries of its platform
  before installing them), the iOS ones by `drawview-sync-iroh` (the module linking them).

## Left for later

* Store and forward (`drawview-sync-relay`): a service that keeps the sealed operations for the
  devices offline.
* Several relays (failover, Europe and United States), or managed relays.
* The local network through iroh, in place of `LanTransport`.
* Rotating the keys of the connections. The relay sees which endpoints talk to each other, when
  and how much.
* Port mapping (UPnP, PCP) on the desktop, if the measures show it helps.
* One thread for every channel waiting for a frame (phase 2): with many shared documents and
  devices, a call of the bridge giving the frames of every stream of an endpoint at once (one
  thread per endpoint) would replace it.

## Risks

* The size of the library on Android (three ABIs) and in the desktop jar (five platforms):
  measured in phase 0, split by ABI in the app bundle.
* The build on Windows (MSVC) and on Linux arm64 in the CI.
* The background on iOS suspends the connections, as on the local network (`BackgroundSync`).
* The outbound traffic of the free tier.
* A single project (n0) behind the engine. Mitigated: it is open source, the relay is our own,
  and the bridge sits behind `SyncTransport`, so another engine would be another module.
