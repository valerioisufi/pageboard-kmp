# Module pageboard-sync-ui

Collaboration in an application: `createCollaboration` (one per process) and `CollaborationAccess`
(binds it to each window), the extension of the
editor (`Collaboration.editorExtension`: sharing, the other devices drawn over the document),
`CollaborationDialogs` and `InvitationLinks` (the invitations opened from outside).

Over the internet too when the application gives an `InternetSync` (`IrohInternet` of
`pageboard-sync-iroh`): the user turns it on and chooses the relay in the sharing, which shows
how each participant is reached (the local network, the internet directly or through the relay).
