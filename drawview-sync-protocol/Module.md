# Module drawview-sync-protocol

The protocol of the synchronization, without the documents (what a relay needs): the first
frame of a connection in clear (`OpenFrame`: the protocol version and the tag of the document),
the frames encrypted with the key of the document (`SecureChannel`, `DocumentKey`), the operations
sealed with it, their identity in clear (`SealedOperation`, `OperationSeal`), the identities of
the devices (`DeviceIdentity`), the invitations (`Invitation`), the membership signed by the owner
(`SignedMembership`) and the transports (`SyncTransport`, `SyncChannel`, `InMemoryNetwork`).
