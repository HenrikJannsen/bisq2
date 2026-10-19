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

import bisq.persistence.backup.MaxBackupSize;
import bisq.persistence.backup.RestoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class CoalescingPersistenceClientTest {
    @TempDir
    Path tempDir;

    @Test
    void requestWithoutRunningWriteIsWrittenAtOnce() {
        TestClient client = new TestClient(new TimestampStore(), tempDir);

        CompletableFuture<Boolean> result = client.persist();

        assertEquals(1, client.persistence.copies.size());
        client.persistence.completeWrite(0, true);
        assertTrue(result.join());
        assertFalse(client.isUnsaved());
    }

    @Test
    void requestsDuringAWriteAreWrittenTogetherAfterIt() {
        TestClient client = new TestClient(new TimestampStore(), tempDir);
        CompletableFuture<Boolean> first = client.persist();
        client.store.getTimestampsByProfileId().put("A", 1L);
        CompletableFuture<Boolean> second = client.persist();
        client.store.getTimestampsByProfileId().put("A", 2L);
        CompletableFuture<Boolean> third = client.persist();

        assertEquals(1, client.persistence.copies.size());
        client.persistence.completeWrite(0, true);

        assertTrue(first.join());
        assertEquals(2, client.persistence.copies.size());
        assertEquals(2L, client.persistence.copies.get(1).getTimestampsByProfileId().get("A"));
        assertFalse(second.isDone());
        assertTrue(client.isUnsaved());

        client.persistence.completeWrite(1, true);

        assertTrue(second.join());
        assertTrue(third.join());
        assertFalse(client.isUnsaved());
        assertEquals(2, client.persistence.copies.size());
    }

    @Test
    void failedWriteLeavesTheStateUnsavedForTheWriteAtShutdown() {
        TestClient client = new TestClient(new TimestampStore(), tempDir);
        client.store.getTimestampsByProfileId().put("A", 1L);

        CompletableFuture<Boolean> result = client.persist();
        client.persistence.completeWrite(0, false);

        assertFalse(result.join());
        assertTrue(client.isUnsaved());
        client.persistOnShutdown();
        assertEquals(1, client.persistence.shutdownCopies.size());
        assertEquals(1L, client.persistence.shutdownCopies.get(0).getTimestampsByProfileId().get("A"));
    }

    @Test
    void failedWriteIsWrittenAgainByTheNextRequest() {
        TestClient client = new TestClient(new TimestampStore(), tempDir);
        client.persist();
        client.persistence.completeWrite(0, false);

        CompletableFuture<Boolean> next = client.persist();
        client.persistence.completeWrite(1, true);

        assertTrue(next.join());
        assertFalse(client.isUnsaved());
    }

    @Test
    void failedCopyOfTheStoreDoesNotBlockLaterWrites() {
        TimestampStore store = spy(new TimestampStore());
        doThrow(new IllegalStateException("copy failed")).doCallRealMethod().when(store).getClone();
        TestClient client = new TestClient(store, tempDir);

        assertFalse(client.persist().join());
        CompletableFuture<Boolean> next = client.persist();

        assertEquals(1, client.persistence.copies.size());
        client.persistence.completeWrite(0, true);
        assertTrue(next.join());
    }

    @Test
    void writeAtShutdownIsSkippedWhenTheStateIsOnDisk() {
        TestClient client = new TestClient(new TimestampStore(), tempDir);
        client.persist();
        client.persistence.completeWrite(0, true);

        client.persistOnShutdown();

        assertTrue(client.persistence.shutdownCopies.isEmpty());
    }

    private static class ControlledPersistence extends Persistence<TimestampStore> {
        private final List<TimestampStore> copies = new ArrayList<>();
        private final List<CompletableFuture<Void>> writes = new ArrayList<>();
        private final List<TimestampStore> shutdownCopies = new ArrayList<>();

        ControlledPersistence(Path directory) {
            super(directory, "TestStore", MaxBackupSize.ZERO, new RestoreService());
        }

        @Override
        public CompletableFuture<Void> persistAsync(TimestampStore store) {
            copies.add(store);
            CompletableFuture<Void> write = new CompletableFuture<>();
            writes.add(write);
            return write;
        }

        @Override
        protected void persist(TimestampStore store) {
            shutdownCopies.add(store);
        }

        void completeWrite(int index, boolean success) {
            if (success) {
                writes.get(index).complete(null);
            } else {
                writes.get(index).completeExceptionally(new IllegalStateException("write failed"));
            }
        }
    }

    private static class TestClient extends CoalescingPersistenceClient<TimestampStore> {
        private final TimestampStore store;
        private final ControlledPersistence persistence;

        TestClient(TimestampStore store, Path directory) {
            this.store = store;
            persistence = new ControlledPersistence(directory);
        }

        @Override
        public Persistence<TimestampStore> getPersistence() {
            return persistence;
        }

        @Override
        public PersistableStore<TimestampStore> getPersistableStore() {
            return store;
        }
    }
}
