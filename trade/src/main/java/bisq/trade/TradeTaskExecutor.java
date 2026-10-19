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

package bisq.trade;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs the tasks of each trade one after the other, in the order in which they were submitted. Tasks of different
 * trades can run at the same time on the threads of the given executor. A trade has a queue only while it has tasks,
 * so a trade ID which a peer made up leaves no state behind once its tasks have run.
 * See docs/specifications/trade/trade-task-execution.md.
 */
public final class TradeTaskExecutor {
    private final Executor executor;
    // A trade ID is in the map while one of its tasks runs or waits. All access is guarded by the monitor of this object.
    private final Map<String, Queue<Task<?>>> queueByTradeId = new HashMap<>();
    private boolean isShutdown;

    public TradeTaskExecutor(Executor executor) {
        this.executor = executor;
    }

    public CompletableFuture<Void> submit(String tradeId, Runnable task) {
        return supply(tradeId, () -> {
            task.run();
            return null;
        });
    }

    /**
     * Adds the task to the queue of the trade. The future completes with the result of the task, or exceptionally with
     * its exception. A task must not wait for the future of another task of its trade: that task runs only after it.
     */
    public <T> CompletableFuture<T> supply(String tradeId, Supplier<T> supplier) {
        Task<T> task = new Task<>(supplier);
        boolean isFirstTask;
        synchronized (this) {
            if (isShutdown) {
                task.fail(new RejectedExecutionException("The trade task executor is shut down"));
                return task.result;
            }
            Queue<Task<?>> queue = queueByTradeId.get(tradeId);
            isFirstTask = queue == null;
            if (isFirstTask) {
                queue = new ArrayDeque<>();
                queueByTradeId.put(tradeId, queue);
            }
            queue.add(task);
        }
        if (isFirstTask) {
            executeNextTask(tradeId);
        }
        return task.result;
    }

    /**
     * Refuses new tasks and waits until the tasks which are queued have run, at most for the given time. Must not be
     * called from a task.
     *
     * @return whether all queued tasks have run
     */
    public boolean shutdown(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        synchronized (this) {
            isShutdown = true;
            while (!queueByTradeId.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    @VisibleForTesting
    synchronized int getNumQueues() {
        return queueByTradeId.size();
    }

    private void executeNextTask(String tradeId) {
        try {
            executor.execute(() -> runNextTask(tradeId));
        } catch (RejectedExecutionException e) {
            Queue<Task<?>> queue;
            synchronized (this) {
                queue = queueByTradeId.remove(tradeId);
                notifyAll();
            }
            queue.forEach(task -> task.fail(e));
        }
    }

    private void runNextTask(String tradeId) {
        Task<?> task;
        synchronized (this) {
            task = queueByTradeId.get(tradeId).remove();
        }
        task.run();
        boolean hasNextTask;
        synchronized (this) {
            Queue<Task<?>> queue = queueByTradeId.get(tradeId);
            hasNextTask = !queue.isEmpty();
            if (!hasNextTask) {
                queueByTradeId.remove(tradeId);
                notifyAll();
            }
        }
        if (hasNextTask) {
            executeNextTask(tradeId);
        }
    }

    private static final class Task<T> {
        private final Supplier<T> supplier;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private Task(Supplier<T> supplier) {
            this.supplier = supplier;
        }

        private void run() {
            try {
                result.complete(supplier.get());
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
            }
        }

        private void fail(Throwable throwable) {
            result.completeExceptionally(throwable);
        }
    }
}
