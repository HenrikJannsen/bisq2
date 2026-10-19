# Bisq Easy message progress

Bisq Easy trade messages can arrive in another order than the peer sent them. A received message can therefore wait
for a prerequisite message or local user action before the trade protocol can apply it.

## Deferred messages of an existing trade

1. A trade keeps an accepted peer message which its protocol cannot apply yet when that message can advance a later
   step. Later state transitions retry the deferred messages.
2. The node persists these deferred network messages with the trade in its private store. A completed persistence
   write must preserve both the trade state and its deferred messages. Restarting with that stored trade must not
   discard a message merely because its prerequisite had not arrived before shutdown.
3. Consuming a deferred message removes it from the pending messages. Reaching a final trade state clears the pending
   messages. The node snapshots the state and pending messages together so that persistence cannot record removal of
   a message with the state from before that message was applied.
4. Restoring a deferred message requires its trade ID, sender and receiver to match the stored trade, as defined in
   [Sender of a trade message](trade-message-sender.md). An invalid pending entry must not prevent restoration of the
   trade or its other pending entries. Local user action events are not restored as received network messages.

## Reason

A buyer may receive the seller's Bitcoin transfer confirmation before the seller's fiat receipt confirmation. The
buyer must wait for the fiat receipt confirmation before applying the Bitcoin transfer confirmation. If the buyer
restarts while waiting, both the waiting state and the Bitcoin confirmation are needed to continue.

The network acknowledges a confidential message after decryption and removes received mailbox messages independently
of the trade protocol's progress. An acknowledgement therefore does not establish that the trade advanced. The sender
stops automatic resends after acknowledgement. Keeping a deferred message only in memory would lose the message on
restart, even after the trade's state was saved successfully.

## Compatibility and scope

The persisted Bisq Easy trade adds a repeated field for pending trade messages after the fields used by version
2.1.14. Trades written by that version have an empty pending collection and remain readable. Network message formats
do not change. An older application can read the new trade but drops its pending messages when it writes the trade
again, so restarting with an older application does not preserve this recovery behavior.

Messages which arrive before their trade exists follow [Pending trade messages](pending-trade-messages.md).

This persistence rule protects messages included in a successful store write. It does not make network acknowledgement
atomic with that write. Preventing loss when the process crashes after acknowledgement but before delivery to the
trade service or completion of persistence requires a durable incoming-message store coordinated with acknowledgement.
That change affects the delivery lifecycle of all confidential message consumers and requires a separate design.
