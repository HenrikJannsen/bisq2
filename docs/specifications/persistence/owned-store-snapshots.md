# Stores with one state owner

These rules apply to clients that serialize all store mutations through one state owner, including
Bisq Easy trades. Other persistence clients retain their existing write scheduling.

## Complete immutable snapshots

The state owner captures a complete immutable snapshot after a command finishes its mutations.
The disk writer receives only that snapshot. Later mutations cannot change a queued snapshot or
make one file contain fields captured at different points in a command.

Every entry belongs to the same capture. If any entry cannot be serialized, the whole capture
fails. A failed trade must not be omitted from an otherwise successful store write. A failed capture
keeps the store dirty and must prevent a successful flush until a later complete capture includes
the current state. It must not replace the last complete file with a partial store.

Reason: removing a trade from the saved store can lose both its state and its deferred messages,
even when the write itself reports success.

## Revisions and pending writes

Each accepted complete snapshot receives an increasing requested revision. The written revision
advances only after a snapshot successfully replaces the current store file. Revisions describe
requests within the running process; they do not change the persisted format.

At most one snapshot write is active for a store. While it is active, newer complete snapshots may
replace older pending snapshots. Because every snapshot contains the whole store, writing a newer
revision also satisfies a request to persist an older revision.

After a successful write, a newer pending snapshot must be written without requiring another
command, timer, or shutdown. Ordinary snapshot requests must not create retained completion
waiters; only callers that explicitly await a flush need such waiters.

A failed write does not advance the written revision. It retains the latest requested snapshot as
dirty and fails outstanding flush requests. A subsequent snapshot request, flush, or explicit retry
attempts a write again. Failure alone does not start an unbounded automatic retry loop.

## Flush and shutdown

A flush names a requested revision and succeeds only after that revision, or a later complete
snapshot, replaces the store file. A flush of the latest state includes any failed capture known
to the state owner. Draining an older write cannot turn that failed capture into success.

Write completions return to the state owner. Closing ordinary command admission must leave
completion admission available until accepted commands and their persistence results settle.
Shutdown must drain an existing write even when it will report a later capture failure. Otherwise
the writer could finish after the state owner has stopped accepting its completion.

The owner must not wait synchronously for its own completion callbacks. Waiting for a flush must
allow the owner to process write results and schedule the pending final snapshot.

## File and delivery guarantees

Snapshot writes use the existing store format, backup policy, and file replacement behavior in
[Store write results](store-write-results.md). Existing store files remain readable without a
migration.

A successful flush confirms file replacement. It does not add an fsync guarantee against power
loss or storage hardware failure. It also does not change transport acknowledgements into durable
trade receipts. A separate delivery protocol would have to retain and retry messages until the
receiver confirms persistence to provide that guarantee.
