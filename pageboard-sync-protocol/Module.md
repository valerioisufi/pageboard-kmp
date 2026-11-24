# Module pageboard-sync-protocol

The protocol of the synchronization, without the documents (what a relay needs): the first
frame of a connection in clear (`OpenFrame`: the protocol version and the tag of the document),
the frames encrypted with the key of the document (`SecureChannel`, `DocumentKey`), the operations
sealed with it, their identity in clear (`SealedOperation`, `OperationSeal`), the identities of
the devices (`DeviceIdentity`), the invitations (`Invitation`), the membership signed by the owner
(`SignedMembership`) and the transports (`SyncTransport`, `SyncChannel`, `InMemoryNetwork`).

A membership carries what its owner decided beyond the roles: the grants of capabilities to some
members (`Grant`, `Capability`, `Membership.holds`), kept up to their last operation once taken
back, and the shared folders its item is in (`SpaceRef`), which the members take the memberships
of from their owners only. A member granted it may put the item into a folder of its own with a
`Placement`, signed by its device.
