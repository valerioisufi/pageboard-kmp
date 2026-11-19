# Module pageboard-platform

The services of the platforms DrawView runs on, without the documents: the files of the
application (`PlatformFiles`, `dataDirectory`, on the desktop the directory of
`PAGEBOARD_DATA_DIRECTORY_PROPERTY`), the secrets kept by the system (`SecureStore`: the Keychain,
the Android Keystore), the name of the device (`DeviceProfile`), the native libraries of the
desktop jars (`JvmNativeLibraries`), locks (`SyncLock`), time and SHA-256.
