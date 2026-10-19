# Trade task execution

This specification defines how a node runs the operations of its Bisq Easy trades: the processing of received trade
messages, the actions of the user, and the other operations which read or change a trade. It does not apply to MuSig.

## Background

A trade receives messages from the network layer, which passes each received message to the services in a task of its
own, so several messages are processed at the same time. The user acts on a trade from the desktop or through the API,
in other threads. A node which processes two operations of one trade at the same time can lose an operation. For
example, a trade message which the maker processed at the same time as the take offer request could stay pending, so
the trade stopped. Locks in single places did not cover all such combinations.

## Rules

1. The node runs the operations of a trade as tasks of that trade. The tasks of one trade run one after the other, in
   the order in which they were submitted. Tasks of different trades can run at the same time.
2. A trade has a queue of tasks only while one of its tasks runs or waits.
3. A task which fails completes its result with the failure. The next task of the trade still runs.
4. A task does not wait for the result of another task of its trade.
5. At shutdown, the node refuses new tasks and waits for the queued tasks for a limited time.
6. A received trade message runs as a task of the trade whose ID it carries, also when the node has no trade with that
   ID. The take offer request runs as a task of the trade which it creates.
7. At startup, the node creates the protocol of each stored trade in a task of that trade, before it subscribes to the
   trade messages of the network. The initialization of the trade service completes when these tasks have run. A
   failed creation is logged, and the other trades still start.
8. The check of a trade chat channel against its trade runs as a task of the trade.
9. The actions of the user on a trade, from the desktop or the API, run as tasks of the trade. This includes taking an
   offer, rejecting or cancelling a trade, and confirming the Bitcoin payment. The taker creates its trade and the
   protocol in a task of the new trade. The call checks the trade restrictions and the data of the action, and returns
   a future of the task.

## Reasons

- Rule 1: an operation of a trade sees the result of every operation of that trade which was submitted before it, and
  no operation of that trade runs at the same time. So a combination of operations needs no lock of its own. Trades
  do not depend on each other, so a slow task of one trade does not delay the other trades.
- Rule 2: anybody can send trade messages with made-up trade IDs. A queue for each received trade ID would grow the
  memory of the node without limit.
- Rule 3: a failure of one operation must not stop the later operations of the trade, for example a later message of
  the peer.
- Rule 4: the task for which it waits runs only after it, so both would wait forever.
- Rule 5: a task which is cut off at shutdown can leave a trade change which is not persisted.
- Rule 6: a trade message can arrive before the take offer request which creates its trade, or at the same time. The
  node keeps such a message as pending message until the protocol of its trade exists (see
  [Pending trade messages](pending-trade-messages.md)). Before this rule, a message which the node processed at the
  same time as the request could look for the protocol before the request created it, and add itself to the pending
  messages after the request had processed them. The message then stayed pending. When the seller took a buy offer
  and sent the payment account data right away, the buyer waited for the account data and the seller for the payment,
  and the trade stopped. Two copies of a take offer request also run one after the other, so the second one finds the
  trade which the first one created.
- Rule 7: at the subscription, the network layer passes the messages which it received before to the trade service
  (see [Startup replay](../network/startup-replay.md)). As tasks of their trades, they run after the protocol of their
  trade was created. One stored trade whose protocol cannot be created must not stop the other trades.
- Rule 8: the check fails a trade whose channel does not match it. It reads and changes the state of the trade, so it
  must not run at the same time as another operation of the trade.
- Rule 9: an action sees the state which the messages before it produced, and a message which arrives during the action
  waits for it. The UI thread does not wait for the trade. Before, a click on the desktop could wait for a message
  handler of the same trade, for example while it sent a message to the peer. A restriction and invalid data are
  reported by the call, so the user gets the error before the action is submitted.
