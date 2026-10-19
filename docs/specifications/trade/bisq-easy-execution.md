# Bisq Easy execution and publication

## One owner for trade state

The Bisq Easy trade service serializes changes to all its trades through one execution owner.
The same owner controls trade creation, duplicate detection, protocol registration, pending
messages, FSM transitions, closure, deletion, restrictions, redaction and restoration.

A Bisq Easy trade is identified by its immutable trade ID. Its equality and hash code depend
only on that ID. State transitions, payment data changes and redaction must preserve active
and closed store membership so the owner can remove or move a trade reliably.

A command validates the current authoritative trade when it executes. A handle to a closed,
deleted or replaced trade cannot authorize changes to another trade. External callers receive
read handles, not mutable protocol models or protocol instances.

Public commands always queue, including commands submitted from observers during another
command. They must not reenter a partially completed transition. The owner never waits for a
command or persistence completion that requires the same owner to run.

Serial execution establishes local processing order. It does not establish the causal order of
network messages. The retained inputs and replay rules in
[Bisq Easy message progress](bisq-easy-message-progress.md) and
[Pending trade messages](pending-trade-messages.md) still apply.

## Admission

External user commands have bounded admission. A full queue rejects a new command explicitly;
the caller must not treat submission as successful processing. Incoming delivery applies
backpressure while waiting for admission and never executes a transition on a network thread.

Completion of accepted work retains admission while ordinary commands drain. Restriction,
channel and redaction refreshes have bounded coalesced admission independent of user-command
capacity. Each refresh reads the current source state, so combining repeated notifications
cannot discard a new restriction or the need to validate a channel.

The transport still owns its upstream delivery and overload policies. Admission to the trade
executor is not a durable acknowledgment; these rules do not close the transport-to-storage
crash window described in the message-progress specification.

## Results and external effects

User action futures complete after processing and publication, or fail with the rejection.
Processing success is distinct from durable persistence and network delivery. An action deferred
by the FSM retains the behavior specified in
[Trade log messages of user actions](trade-log-of-user-actions.md).

Application coordination sends a private trade log only after the domain action succeeds. It
captures the trade, channel, identity and input data belonging to the requested operation.
Changing the selected trade or user profile cannot redirect a queued action or its chat message.
Accepted multi-step workflows finish their required command and send-initiation steps before
application shutdown flushes outgoing messages.

Desktop callers remain asynchronous and prevent duplicate submissions while an operation is
pending. A detached or replaced view must not render a late result. Detaching presentation does
not cancel an accepted domain operation. REST success and rejection reflect the processed
operation; a request timeout alone does not cancel an admitted command.

## Read publication

A stable read handle publishes complete immutable snapshots of mutable presentation fields.
The public model does not contain private signing keys, mutable parties or writable protocol
observables. A consumer needing several fields from one version captures one snapshot.

Observers receive an initial value ordered with subsequent changes. Membership collections
also publish immutable snapshots and reject external mutation. Property notifications occur
after the complete snapshot becomes visible.

Publication queues captured notification values while holding its internal lock, then invokes
observers outside that lock. Reentrant subscriptions join the same ordered notification queue.
A blocked callback does not prevent another thread from publishing a newer snapshot. Command
completion guarantees that the snapshot is visible; callbacks already running on another thread
may finish later. A delayed callback can describe an older version than `getSnapshot()`. Consumers
use the callback's captured snapshot when they need that version, or capture the current snapshot
once when they need the latest version. Unbinding discards queued notifications for that subscription.

Consumers invalidate their subscription registrations on removal or shutdown. A registration
that finishes later must immediately unbind its returned pin. Callbacks from invalidated
registrations must not initiate new delivery. External sends already initiated may still settle.

Observers must hand off slow work. The bounded command queue does not bound notifications retained
behind a blocked observer; ordered transition notifications remain queued until that observer returns.

Publication follows completion of the command's mutations. Where a command applies several
valid transitions, observers still receive the ordered transition snapshots. This preserves
notifications that depend on each transition rather than only its final state. View lifecycle
and state freshness rules in [Bisq Easy state display](bisq-easy-state-display.md) remain in force.

## Persistence and lifecycle

The owner captures the complete store after a command requests persistence. The disk writer
receives immutable data and does not inspect live trade objects. Capture and write failures,
trailing writes and revision-based flush results follow
[Stores with one state owner](../persistence/owned-store-snapshots.md).

Restoration finishes before protocol initialization. Restrictions are initialized before
retained messages and network replay are processed. Shutdown cannot attach a new message
subscription after it has detached the service.

Shutdown first stops new application workflows and drains their accepted command chains.
The trade service then detaches producers and drains admitted commands and refreshes. Outgoing
message flushing follows that drain. The trade owner remains available for persistence receipts
until the final store flush completes or reports failure. Concurrent final flush requests share
that terminal result and cannot start a new capture after the owner stops.

## Compatibility and scope

These execution and publication rules do not change the Bisq Easy wire messages or store format.
The retained-message fields and their compatibility rules remain unchanged. The ownership rule
applies to Bisq Easy; MuSig retains its separate execution lifecycle.
