# Module drawview-sync-iroh

The synchronization over the internet: devices reach each other by their key with
[iroh](https://github.com/n0-computer/iroh), directly when the network allows it, through a relay
otherwise. The iroh bridge is a Rust library (`native/iroh-bridge`): JNI on Android
(`drawview-native-iroh`) and in the desktop jar, a static library on iOS. Without it the module
does nothing, and the devices synchronize on the local network only.

An application uses it through `IrohInternet` (given to `rememberCollaboration` of
`drawview-sync-ui`), or directly: `IrohTransport` (a `SyncTransport`) and `IrohSync`, which
connects the members of the shared documents at the addresses they give each other.
