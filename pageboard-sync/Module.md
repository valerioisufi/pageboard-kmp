# Module pageboard-sync

The synchronization of the documents between devices: `SyncEngine` shares and joins documents,
exchanges their operations with the devices it is connected to (end to end encrypted, see
`pageboard-sync-protocol`), keeps the membership decided by the owner and the presence of the
other devices.

## Shared folders and shared access

A folder of the library, or the whole library, is shared as one thing (a space, see
`SpaceLibrary` of `pageboard-store`): `shareSpace`, `joinSpace`. A folder may be shared inside
another shared folder, and a document may be shared on its own and be in shared folders at the
same time. Each way to reach an item is an access, managed on its own, and what they give is
their union:

* a device has an item while any access names it, with the highest of its roles;
* a connection is about one access, and is authorized by the membership of that access only: a
  member removed from a folder never gets its new key through another access;
* an operation is taken when the device the memberships of every access agree on signed it, and
  some access lets it write; the devices not in an access learn its membership from the others,
  taken only when anchored in what they trust (the folder names the item's own sharing and its
  owner, the item's sharing names the folder);
* `accessOf` gives an application what to show: the sources of an item (`AccessSource`), their
  members, the grants of their owners, and what removing someone from one leaves them;
* `waitingOf` tells which changes of other devices wait here, and why (`WaitingChanges`): a
  device not known yet, accesses naming a replica for different devices, earlier changes of a
  folder missing. A change waits until it can be taken, and is never applied before.

The owner of a sharing decides who besides itself may do more than its role (`Grant` of
`pageboard-sync-protocol`, `setGrants`): share an item of its folder on its own
(`Capability.SHARE_INSIDE`), bring in an item shared on its own (`Capability.BRING_SHARED`), put
its item into a folder of their own (`Capability.PLACE_IN_SPACES`, `place`). A grant only allows:
a device that does not know a capability allows nothing by it. An application may grant
capabilities of its own: they are signed and carried, and read by the application.
