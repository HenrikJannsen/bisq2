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
