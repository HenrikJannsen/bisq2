# Publisher of the authorized data of oracle and seed nodes

## Scope

This specification defines which key pair oracle and seed nodes use to publish and remove the authorized data which
they authorize. It also defines how they replace the entries which earlier versions published with another key pair.

It applies to storage key V1: the storage key of authenticated data is the hash of the data, without the publisher.

## Terms

- **Authorized key pair**: the key pair of an oracle or seed node whose public key is in `authorizedPublicKeyBytes` of
  the `AuthorizedData`. Its private key creates the authorization signature over the data.
- **Network identity key pair**: the key pair of the default identity of the node. Its public key is part of the
  network ID of the node.
- **Publisher**: the key pair which signs an add or remove request. The add request contains the hash of its public key
  (`pubKeyHash` of `AuthenticatedSequentialData`).
- **Entry**: the request which a node stores for a storage key: an add request, or a remove request after a removal.

## Problem

Earlier versions of oracle and seed nodes published their authorized data with their network identity key pair. The
authorized key owns authorized data: only the authority decides who publishes and removes its data. A later version
accepts authorized data only from its owner. Entries which another key published would then be lost, and some of them
are not published again, for example timestamps, account age and signed witness data.

So oracle and seed nodes must publish with their authorized key pair, and replace the entries of earlier versions,
before the later version is released.

## Rules

1. Oracle and seed nodes publish the authorized data which they authorize with their authorized key pair.

   Exception: an oracle node publishes its `AuthorizedOracleNode` with its network identity key pair. The data names
   the key of the network ID as its owner, and nodes reject it from another publisher.

2. An oracle node removes a bonded role when its registration is cancelled or fails the DAO revalidation, and when the
   role disappears from the list of bonded roles of the node. In each case it sends a removal with its authorized key
   pair first, and then a removal with its network identity key pair.

   Reason: a node accepts a removal only from the publisher of its stored entry. Until all entries of earlier versions
   are replaced, a node can hold the entry of either key pair. A node broadcasts a removal only if its local store
   accepts it.

   - If the local entry is the one of the network identity key pair, the local store rejects the first removal, and
     the node does not broadcast it. It accepts and broadcasts the second removal.
   - If the local entry is the one of the authorized key pair, the local store accepts the first removal. It then
     stores the second removal as the newer removal, because its sequence number is higher, and the node broadcasts
     it as well. Nodes which missed the replacement still hold the entry of the network identity key pair, and they
     accept the second removal.

   The order makes sure that the removal with the network identity key pair is also broadcast after the replacement.
   In the opposite order, the local store would reject it after the replacement.

3. Once per start, an oracle node publishes again with its authorized key pair each entry of its authenticated data
   stores which meets all of these conditions:

   - It is an add request of authorized data whose authorized public key is the one of the node.
   - Another key than the authorized key published it.
   - It has not expired.
   - Its data is neither `AuthorizedOracleNode` (rule 1) nor `AuthorizedBondedRole`.

   The node selects the entries when it starts to publish its data again: after it has the configured number of
   connections and after the initial delay. Then its store also contains the data of its first inventory responses.
   It publishes the entries one at a time, with lower priority than all other data which it publishes again. Before
   each publication, it checks again that the entry has not expired.

   Reasons:

   - Oracle nodes do not publish timestamps, account age, signed witness and account timestamp data again on their
     own; users request them again before they expire. Without this rule, an entry of the network identity key pair
     would be replaced only when its user requests the data again, and it would otherwise stay until it expires.
   - The condition "another key than the authorized key" also covers entries of an earlier network identity of the
     node, and copies which another key published. The publication takes the sequence number of the local entry plus
     one, so it replaces such an entry as well.
   - Bonded roles are excluded because the node publishes its bonded roles again at each start, except banned roles.
     A banned role must not get a new time to live.
   - Expired entries are excluded because a publication would give them a new time to live.
   - The rule runs at each start, but it does not select an entry again after its replacement: the local entry is then
     the one of the authorized key pair.

   Seed nodes need no selection. Their only authorized data is their bonded role, which they publish again at each
   start (rule 1).

## How a publication replaces the entry of an earlier version

Rules 1 and 3 rely on the following behavior of all current nodes:

- The storage key is the hash of the authorized data without its authorization signature. It contains neither the
  signature nor the publisher. So the entry of the network identity key pair and the entry of the authorized key pair
  have the same storage key.
- The publishing node takes the sequence number of the stored entry plus one, whatever the publisher of that entry is.
- A node accepts an add request whose sequence number is higher than the one of the stored entry. It does not compare
  the publisher with the publisher of the stored entry. The new entry replaces the old one.
- A replacement notifies applications only that the data was added. Applications see the same data again. They are not
  notified of a removal.
- The inventory filter contains the storage key and the sequence number of each entry. A node which missed the
  broadcast gets the replacing entry with its next inventory request.

A separate removal of the old entry is therefore not needed, and it must not be sent. The removal and the new entry
have the same storage key. If the removal arrives first, a node removes the data and notifies applications of the
removal, until the new entry arrives. With a higher sequence number, the removal can also block the new entry. On oracle
nodes, a bonded role which disappears from the list of bonded roles is also removed with the key pairs of the node
(rule 2).

## Rollout

1. All oracle and seed nodes run this version.
2. Each oracle node logs when it took the last entry of rule 3. After that, the replacements spread through the
   network. Nodes which were offline get the replacing entries with their next inventory request.
3. Before the next release, the developers check the network database of a seed node, which holds all network data.
   Apart from `AuthorizedOracleNode` and banned bonded roles, no add request of authorized data of an oracle or seed
   node may have another publisher than its authorized key.
4. Then the version which accepts authorized data only from its owner can be released.

Reason for this order: the later version does not accept entries of the network identity key pair. An oracle node
which does not run this version before the release loses these entries on updated nodes, and on its own node when it
updates. Some of this data is not published again. Bonded roles of network data version 0 cannot be rebuilt from the
persisted registrations of the oracle node.

## Limitations

- **One-time longer time to live.** A publication gets a new creation date. Data which an oracle node publishes again
  by rule 3 lives up to one time to live longer, once. This includes data of users who do not request it again, and
  data of inactive user profiles. Keeping the original creation date would need a new way to build the add request in
  the network layer.
- **Double publication.** At each start, an oracle node publishes the proof of burn and bonded reputation data of
  active user profiles again. Rule 3 can publish the same entries a second time. This costs proof of work and bandwidth
  once, and changes nothing else.
- **Copies with a higher sequence number.** If a node holds a copy of the data which another key published with a
  higher sequence number than the one in the store of the oracle node, it rejects the publication. It keeps the copy
  until the copy expires. The later version removes such entries when it loads its store.

## Verification

- Oracle nodes publish account age, signed witness, account timestamp, timestamp, market price, proof of burn, bonded
  reputation and bonded role data with their authorized key pair. Seed nodes publish their bonded role with their
  authorized key pair.
- Oracle nodes publish `AuthorizedOracleNode` with their network identity key pair.
- Oracle nodes remove a bonded role first with their authorized key pair and then with their network identity key pair.
- Rule 3 selects only the entries which meet its conditions, and publishes each of them once with the authorized key
  pair.
- In the store, the publication with the authorized key pair replaces the entry of the network identity key pair, and
  notifies only that the data was added. The store accepts a removal only from the publisher of its entry. After the
  replacement, it accepts both removals of rule 2 in their order, and a store which missed the replacement accepts the
  removal with the network identity key pair.

## Related documents

- [`../bonded-roles/registration.md`](../bonded-roles/registration.md): the registration and removal of bonded roles.
