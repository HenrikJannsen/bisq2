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

package bisq.persistence;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A client which wants it writes a state which it dropped because of the rate limit as soon as the rate allows it, and
 * a state which it dropped during a write when that write has completed. Without it, the state waited for the next
 * call of the client, which for a trade store can come hours later. The tests run the scheduled writes themselves and
 * complete the writes themselves, so they do not depend on timing.
 */
class RateLimitedPersistenceClientDeferredWriteTest {
    private static final long RATE_IN_MS = 60_000;

    private final RecordingScheduler scheduler = new RecordingScheduler();
    @SuppressWarnings("unchecked")
    private final Persistence<TimestampStore> persistence = mock(Persistence.class);

    @Test
    void stateDroppedBecauseOfTheRateIsWrittenWhenTheRateAllowsIt() {
        TestClient client = new TestClient(true);
        when(persistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        assertTrue(client.persist().join());

        assertFalse(client.persist().join());

        assertEquals(1, scheduler.tasks.size());
        long delayInMs = scheduler.delaysInMs.get(0);
        assertTrue(delayInMs > 0 && delayInMs <= RATE_IN_MS, "delay " + delayInMs);
        client.rateInMs = 0;
        scheduler.runTasks();
        verify(persistence, times(2)).persistAsync(any());
        assertFalse(client.isDropped());
    }

    @Test
    void severalDropsScheduleOneWrite() {
        TestClient client = new TestClient(true);
        when(persistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        client.persist().join();

        client.persist().join();
        client.persist().join();
        client.persist().join();

        assertEquals(1, scheduler.tasks.size());
        client.rateInMs = 0;
        scheduler.runTasks();
        verify(persistence, times(2)).persistAsync(any());
    }

    @Test
    void stateDroppedDuringAWriteIsScheduledWhenTheWriteCompletes() {
        TestClient client = new TestClient(true);
        CompletableFuture<Void> write = new CompletableFuture<>();
        when(persistence.persistAsync(any())).thenReturn(write);
        client.persist();

        assertFalse(client.persist().join());
        assertTrue(scheduler.tasks.isEmpty());

        write.complete(null);
        assertEquals(1, scheduler.tasks.size());
        when(persistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        client.rateInMs = 0;
        scheduler.runTasks();
        verify(persistence, times(2)).persistAsync(any());
        assertFalse(client.isDropped());
    }

    // As before: a failed write is written again by the next call or at shutdown, and not every second
    @Test
    void failedWriteSchedulesNoWrite() {
        TestClient client = new TestClient(true);
        CompletableFuture<Void> write = new CompletableFuture<>();
        when(persistence.persistAsync(any())).thenReturn(write);
        client.persist();
        client.persist();

        write.completeExceptionally(new IllegalStateException("disk full"));

        assertTrue(scheduler.tasks.isEmpty());
        assertTrue(client.isDropped());
    }

    @Test
    void noWriteRunsAfterTheClientStopped() {
        TestClient client = new TestClient(true);
        when(persistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        client.persist().join();
        client.persist().join();
        assertEquals(1, scheduler.tasks.size());

        client.stopDeferredWrites();
        client.rateInMs = 0;
        scheduler.runTasks();
        client.persist().join();

        verify(persistence, times(2)).persistAsync(any());
        assertEquals(0, scheduler.tasks.size());
    }

    @Test
    void clientWhichDoesNotAskForItSchedulesNoWrite() {
        TestClient client = new TestClient(false);
        when(persistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        client.persist().join();

        assertFalse(client.persist().join());

        assertTrue(scheduler.tasks.isEmpty());
        verify(persistence, times(1)).persistAsync(any());
        verify(persistence, never()).persist(any());
    }

    private static final class RecordingScheduler implements RateLimitedPersistenceClient.DeferredWriteScheduler {
        private final List<Runnable> tasks = new ArrayList<>();
        private final List<Long> delaysInMs = new ArrayList<>();

        @Override
        public void schedule(Runnable task, long delayInMs) {
            tasks.add(task);
            delaysInMs.add(delayInMs);
        }

        // A task can schedule a further task, which the next call runs
        private void runTasks() {
            List<Runnable> toRun = new ArrayList<>(tasks);
            tasks.clear();
            delaysInMs.clear();
            toRun.forEach(Runnable::run);
        }
    }

    private final class TestClient extends RateLimitedPersistenceClient<TimestampStore> {
        private final TimestampStore store = new TimestampStore();
        private final boolean writeDroppedStateLater;
        private volatile long rateInMs = RATE_IN_MS;

        private TestClient(boolean writeDroppedStateLater) {
            super(scheduler);
            this.writeDroppedStateLater = writeDroppedStateLater;
        }

        @Override
        protected long getMaxWriteRateInMs() {
            return rateInMs;
        }

        @Override
        protected boolean writeDroppedStateLater() {
            return writeDroppedStateLater;
        }

        @Override
        public Persistence<TimestampStore> getPersistence() {
            return persistence;
        }

        @Override
        public TimestampStore getPersistableStore() {
            return store;
        }
    }
}
