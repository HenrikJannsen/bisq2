/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.trade.bisq_easy;

import bisq.common.threading.ExecutorFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Serial ownership with bounded external admission and a reserved path for accepted work's completions. */
final class BisqEasyTradeExecutor {
    private final Object admission = new Object();
    private final Semaphore capacity;
    private final ExecutorService executor;
    private volatile Thread owner;
    private boolean accepting = true;
    private CompletableFuture<Void> quiescence;

    BisqEasyTradeExecutor(int maxPendingCommands) {
        if (maxPendingCommands < 1) {
            throw new IllegalArgumentException("Command capacity must be positive");
        }
        capacity = new Semaphore(maxPendingCommands, true);
        executor = Executors.newSingleThreadExecutor(task ->
                ExecutorFactory.getThreadFactory("BisqEasyTrade").newThread(() -> {
                    owner = Thread.currentThread();
                    task.run();
                }));
    }

    <T> CompletableFuture<T> submit(Supplier<T> command) {
        try {
            // The untimed overload ignores semaphore fairness and can starve waiting network deliveries.
            if (!capacity.tryAcquire(0, TimeUnit.NANOSECONDS)) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Trade command queue is full"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
        return enqueue(command);
    }

    // Network delivery may wait for capacity; it must neither discard messages nor run a transition on its caller.
    CompletableFuture<Void> receive(Runnable command) {
        if (isOwnerThread()) {
            return submit(() -> {
                command.run();
                return null;
            });
        }
        try {
            capacity.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(e);
        }
        return enqueue(() -> {
            command.run();
            return null;
        });
    }

    private <T> CompletableFuture<T> enqueue(Supplier<T> command) {
        CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (admission) {
            if (!accepting) {
                capacity.release();
                return CompletableFuture.failedFuture(new RejectedExecutionException("Trade service is stopping"));
            }
            executor.execute(() -> {
                // Capacity bounds queued work. A running command may enqueue another command without running it inline.
                capacity.release();
                try {
                    result.complete(command.get());
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                }
            });
        }
        return result;
    }

    /** Only the one outstanding persistence write and lifecycle barriers use this path. */
    Executor completionExecutor() {
        return executor;
    }

    <T> CompletableFuture<T> complete(Supplier<T> command) {
        return CompletableFuture.supplyAsync(command, executor);
    }

    /** Each fixed producer owns one marker; repeated refreshes coalesce while its marker is queued. */
    void refresh(AtomicBoolean scheduled, Runnable command) {
        synchronized (admission) {
            if (!accepting || !scheduled.compareAndSet(false, true)) {
                return;
            }
            executor.execute(() -> {
                // A change arriving during the refresh must schedule another pass.
                scheduled.set(false);
                command.run();
            });
        }
    }

    CompletableFuture<Void> quiesce() {
        synchronized (admission) {
            if (quiescence == null) {
                accepting = false;
                quiescence = CompletableFuture.runAsync(() -> {}, executor);
            }
            return quiescence;
        }
    }

    boolean isOwnerThread() {
        return Thread.currentThread() == owner;
    }

    void assertOwnerThread() {
        if (!isOwnerThread()) {
            throw new IllegalStateException("Trade state must be accessed on its owner");
        }
    }

    // Called only after admitted commands and persistence receipts have settled.
    void shutdown() {
        executor.shutdown();
    }
}
