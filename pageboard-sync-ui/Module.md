# Module pageboard-sync-ui

Collaboration in an application: `createCollaboration` (one per process) and `CollaborationAccess`
(binds it to each window), the extension of the
editor (`Collaboration.editorExtension`: sharing, the other devices drawn over the document),
`CollaborationDialogs` and `InvitationLinks` (the invitations opened from outside).

Over the internet too when the application gives an `InternetSync` (`IrohInternet` of
`pageboard-sync-iroh`): the user turns it on and chooses the relay in the sharing, which shows
how each participant is reached (the local network, the internet directly or through the relay).

## On Android

The library adds to the manifest of the application the permissions `FOREGROUND_SERVICE` and
`FOREGROUND_SERVICE_DATA_SYNC`, and `SyncService`, a foreground service of type `dataSync` that keeps
the synchronization going in the background (`pageboard-sync-lan` adds the network). The
application declares the foreground service of type data sync in the Play Console.
