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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeTaskExecutorTest {
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final TradeTaskExecutor executor = new TradeTaskExecutor(pool);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void tasksOfOneTradeRunOneAfterTheOtherInSubmissionOrder() throws Exception {
        List<Integer> runOrder = new CopyOnWriteArrayList<>();
        AtomicInteger runningTasks = new AtomicInteger();
        AtomicInteger maxRunningTasks = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        IntStream.range(0, 200).forEach(index -> futures.add(executor.submit("trade", () -> {
            maxRunningTasks.accumulateAndGet(runningTasks.incrementAndGet(), Math::max);
            runOrder.add(index);
            runningTasks.decrementAndGet();
        })));

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

        assertThat(runOrder).containsExactlyElementsOf(IntStream.range(0, 200).boxed().toList());
        assertThat(maxRunningTasks.get()).isEqualTo(1);
    }

    @Test
    void tasksOfDifferentTradesRunAtTheSameTime() throws Exception {
        CountDownLatch otherTradeRan = new CountDownLatch(1);
        CompletableFuture<Boolean> waitingTask = executor.supply("trade-1", () -> await(otherTradeRan));

        executor.submit("trade-2", otherTradeRan::countDown).get(10, TimeUnit.SECONDS);

        assertThat(waitingTask.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void taskOfATradeWaitsForTheRunningTaskOfThatTrade() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> runOrder = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> first = executor.submit("trade", () -> {
            await(release);
            runOrder.add("first");
        });
        CompletableFuture<Void> second = executor.submit("trade", () -> runOrder.add("second"));

        assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();

        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
        assertThat(runOrder).containsExactly("first", "second");
    }

    @Test
    void futureCompletesWithTheResultOfTheTask() throws Exception {
        assertThat(executor.supply("trade", () -> 42).get(10, TimeUnit.SECONDS)).isEqualTo(42);
    }

    @Test
    void failedTaskCompletesItsFutureExceptionallyAndTheNextTaskRuns() throws Exception {
        IllegalStateException failure = new IllegalStateException("failed");
        CompletableFuture<Void> failing = executor.submit("trade", () -> {
            throw failure;
        });
        CompletableFuture<Integer> next = executor.supply("trade", () -> 1);

        assertThatThrownBy(() -> failing.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCause(failure);
        assertThat(next.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test
    void taskSubmittedByATaskOfTheSameTradeRunsAfterIt() throws Exception {
        TradeTaskExecutor directExecutor = new TradeTaskExecutor(Runnable::run);
        List<String> runOrder = new ArrayList<>();
        CompletableFuture<CompletableFuture<Void>> outer = directExecutor.supply("trade", () -> {
            CompletableFuture<Void> inner = directExecutor.submit("trade", () -> runOrder.add("inner"));
            runOrder.add("outer");
            assertThat(inner).isNotDone();
            return inner;
        });

        outer.get().get();
        assertThat(runOrder).containsExactly("outer", "inner");
    }

    @Test
    void queueOfATradeIsRemovedWhenItsTasksHaveRun() throws Exception {
        executor.submit("made-up-trade", () -> {
        }).get(10, TimeUnit.SECONDS);

        assertThat(awaitNumQueues(0)).isTrue();
    }

    @Test
    void shutdownWaitsForTheQueuedTasksAndRefusesNewTasks() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> running = executor.submit("trade", () -> await(release));
        CompletableFuture<Integer> queued = executor.supply("trade", () -> 1);
        CompletableFuture<Boolean> shutdown = CompletableFuture.supplyAsync(() -> executor.shutdown(10, TimeUnit.SECONDS));

        release.countDown();

        assertThat(shutdown.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(running).isDone();
        assertThat(queued.get()).isEqualTo(1);
        assertThatThrownBy(() -> executor.supply("trade", () -> 2).get())
                .hasCauseInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void shutdownReturnsFalseWhenTheTasksDoNotEndInTime() {
        CountDownLatch release = new CountDownLatch(1);
        executor.submit("trade", () -> await(release));

        assertThat(executor.shutdown(100, TimeUnit.MILLISECONDS)).isFalse();
        release.countDown();
    }

    @Test
    void tasksWhichTheExecutorRejectsFail() {
        TradeTaskExecutor rejectingExecutor = new TradeTaskExecutor(runnable -> {
            throw new RejectedExecutionException("rejected");
        });

        CompletableFuture<Integer> task = rejectingExecutor.supply("trade", () -> 1);

        assertThatThrownBy(task::get).hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(rejectingExecutor.getNumQueues()).isZero();
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // The runner removes the queue after it completed the future of the last task
    private boolean awaitNumQueues(int numQueues) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (executor.getNumQueues() != numQueues) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }
}
