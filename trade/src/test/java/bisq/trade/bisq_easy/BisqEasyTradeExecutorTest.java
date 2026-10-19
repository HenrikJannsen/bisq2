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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BisqEasyTradeExecutorTest {
    @Test
    void observerSubmissionRunsAfterTheCurrentCommand() throws Exception {
        BisqEasyTradeExecutor executor = new BisqEasyTradeExecutor(4);
        try {
            List<String> events = new ArrayList<>();
            AtomicReference<CompletableFuture<Void>> nested = new AtomicReference<>();
            executor.submit(() -> {
                executor.assertOwnerThread();
                events.add("start");
                nested.set(executor.submit(() -> {
                    executor.assertOwnerThread();
                    events.add("nested");
                    return null;
                }));
                events.add("end");
                return null;
            }).get(5, SECONDS);
            nested.get().get(5, SECONDS);
            assertThat(events).containsExactly("start", "end", "nested");
            assertThatThrownBy(executor::assertOwnerThread).isInstanceOf(IllegalStateException.class);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void saturationStillAcceptsReceiptsAndQuiescenceDrainsCommands() throws Exception {
        BisqEasyTradeExecutor executor = new BisqEasyTradeExecutor(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            List<String> events = new ArrayList<>();
            CompletableFuture<Void> first = executor.submit(() -> {
                entered.countDown();
                await(release);
                events.add("first");
                return null;
            });
            assertThat(entered.await(5, SECONDS)).isTrue();
            CompletableFuture<Void> second = executor.submit(() -> {
                events.add("second");
                return null;
            });
            assertThatThrownBy(() -> executor.submit(() -> null).join())
                    .hasCauseInstanceOf(RejectedExecutionException.class);
            CompletableFuture<Void> receipt = new CompletableFuture<>();
            executor.completionExecutor().execute(() -> {
                executor.assertOwnerThread();
                events.add("receipt");
                receipt.complete(null);
            });
            CompletableFuture<Void> drained = executor.quiesce();
            assertThat(drained).isNotDone();
            release.countDown();
            CompletableFuture.allOf(first, second, receipt, drained).get(5, SECONDS);
            assertThat(events).containsExactly("first", "second", "receipt");
            assertThatThrownBy(() -> executor.submit(() -> null).join())
                    .hasCauseInstanceOf(RejectedExecutionException.class);
            // A write admitted before quiescence can complete after its barrier.
            executor.complete(() -> {
                executor.assertOwnerThread();
                return null;
            }).get(5, SECONDS);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void failedCommandDoesNotStopLaterCommands() throws Exception {
        BisqEasyTradeExecutor executor = new BisqEasyTradeExecutor(2);
        try {
            assertThatThrownBy(() -> executor.submit(() -> {
                throw new IllegalArgumentException("rejected action");
            }).join()).hasCauseInstanceOf(IllegalArgumentException.class);
            assertThat(executor.submit(() -> 42).get(5, SECONDS)).isEqualTo(42);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void saturatedNetworkDeliveryWaitsThenRunsOnceOnTheOwner() throws Exception {
        BisqEasyTradeExecutor executor = new BisqEasyTradeExecutor(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch receiverStarted = new CountDownLatch(1);
        List<String> events = new ArrayList<>();
        AtomicReference<Thread> processingThread = new AtomicReference<>();
        CompletableFuture<Void> received = new CompletableFuture<>();
        Thread receiver = Thread.ofPlatform().unstarted(() -> {
            receiverStarted.countDown();
            executor.receive(() -> {
                executor.assertOwnerThread();
                processingThread.set(Thread.currentThread());
                events.add("network");
            }).whenComplete((unused, failure) -> {
                if (failure == null) {
                    received.complete(null);
                } else {
                    received.completeExceptionally(failure);
                }
            });
        });
        try {
            CompletableFuture<Void> first = executor.submit(() -> {
                entered.countDown();
                await(release);
                events.add("first");
                return null;
            });
            assertThat(entered.await(5, SECONDS)).isTrue();
            CompletableFuture<Void> second = executor.submit(() -> {
                events.add("second");
                return null;
            });
            receiver.start();
            assertThat(receiverStarted.await(5, SECONDS)).isTrue();
            awaitWaiting(receiver);
            assertThat(received).isNotDone();
            assertThatThrownBy(() -> executor.submit(() -> null).join())
                    .hasCauseInstanceOf(RejectedExecutionException.class);

            release.countDown();
            CompletableFuture.allOf(first, second, received).get(5, SECONDS);

            assertThat(events).containsExactly("first", "second", "network");
            assertThat(processingThread.get()).isNotSameAs(receiver);
        } finally {
            release.countDown();
            receiver.join(5000);
            executor.shutdown();
        }
    }

    @Test
    void interruptedSubmissionReportsFailureAndPreservesTheInterrupt() throws Exception {
        BisqEasyTradeExecutor executor = new BisqEasyTradeExecutor(1);
        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> executor.submit(() -> 42).join())
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
            executor.shutdown();
        }
    }

    @Test
    void saturatedCommandQueueRetainsOneRefreshPerProducerBeforeQuiescence() throws Exception {
        BisqEasyTradeExecutor executor = new BisqEasyTradeExecutor(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean scheduled = new AtomicBoolean();
        List<String> events = new ArrayList<>();
        try {
            executor.submit(() -> {
                entered.countDown();
                await(release);
                events.add("first");
                return null;
            });
            assertThat(entered.await(5, SECONDS)).isTrue();
            executor.submit(() -> {
                events.add("second");
                return null;
            });

            executor.refresh(scheduled, () -> {
                executor.assertOwnerThread();
                events.add("refresh");
            });
            executor.refresh(scheduled, () -> events.add("duplicate"));
            CompletableFuture<Void> quiescence = executor.quiesce();
            release.countDown();
            quiescence.get(5, SECONDS);

            assertThat(events).containsExactly("first", "second", "refresh");
            assertThat(scheduled).isFalse();
            executor.refresh(scheduled, () -> events.add("after-quiescence"));
            executor.complete(() -> null).get(5, SECONDS);
            assertThat(events).containsExactly("first", "second", "refresh");
            assertThat(scheduled).isFalse();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void changeDuringRefreshSchedulesAnotherPass() throws Exception {
        BisqEasyTradeExecutor executor = new BisqEasyTradeExecutor(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean scheduled = new AtomicBoolean();
        List<String> events = new ArrayList<>();
        try {
            executor.refresh(scheduled, () -> {
                entered.countDown();
                await(release);
                events.add("first-refresh");
            });
            assertThat(entered.await(5, SECONDS)).isTrue();
            executor.refresh(scheduled, () -> events.add("second-refresh"));
            CompletableFuture<Void> quiescence = executor.quiesce();

            release.countDown();
            quiescence.get(5, SECONDS);

            assertThat(events).containsExactly("first-refresh", "second-refresh");
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private static void awaitWaiting(Thread thread) {
        long deadline = System.nanoTime() + SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, SECONDS)) {
                throw new AssertionError("Timed out waiting for test barrier");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
