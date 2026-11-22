# Module pageboard-sync-lan

The synchronization on the local network: `LanTransport` (TCP), `LanDiscovery` (mDNS: JmDNS on
the desktop, `NsdManager` on Android, Bonjour on iOS) and `LanSync`, which connects the devices
sharing a document.

## On Android

The library adds to the manifest of the application the permissions `INTERNET` and
`ACCESS_LOCAL_NETWORK` (the devices of the local network).
