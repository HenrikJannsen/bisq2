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

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotPersistenceTest {
    @Test
    void writesLatestPendingSnapshotWithoutAnotherRequest() {
        Fixture fixture = new Fixture();
        long first = fixture.persistence.request(snapshot("first"));
        long intermediate = fixture.persistence.request(snapshot("intermediate"));
        long latest = fixture.persistence.request(snapshot("latest"));
        CompletableFuture<Void> firstFlush = fixture.persistence.flush(first);
        CompletableFuture<Void> intermediateFlush = fixture.persistence.flush(intermediate);
        CompletableFuture<Void> latestFlush = fixture.persistence.flush(latest);
        assertEquals(List.of(snapshot("first")), fixture.snapshots);

        fixture.writes.getFirst().complete(null);
        assertEquals(0, fixture.persistence.getWrittenRevision());
        assertFalse(firstFlush.isDone());
        fixture.owner.runNext();

        assertTrue(firstFlush.isDone());
        assertFalse(latestFlush.isDone());
        assertEquals(List.of(snapshot("first"), snapshot("latest")), fixture.snapshots);
        assertTrue(fixture.persistence.isDirty());
        fixture.writes.getLast().complete(null);
        fixture.owner.runNext();

        intermediateFlush.join();
        latestFlush.join();
        assertEquals(latest, fixture.persistence.getWrittenRevision());
        assertFalse(fixture.persistence.isDirty());
        assertFalse(fixture.persistence.isWriteInProgress());
    }

    @Test
    void failedWriteRemainsDirtyAndFlushRetriesIt() {
        Fixture fixture = new Fixture();
        long revision = fixture.persistence.request(snapshot("latest"));
        CompletableFuture<Void> failedFlush = fixture.persistence.flushLatest();
        IllegalStateException failure = new IllegalStateException("write failed");

        fixture.writes.getFirst().completeExceptionally(failure);
        fixture.owner.runNext();

        assertSame(failure, assertThrows(CompletionException.class, failedFlush::join).getCause());
        assertEquals(0, fixture.persistence.getWrittenRevision());
        assertTrue(fixture.persistence.isDirty());
        assertFalse(fixture.persistence.isWriteInProgress());
        assertEquals(1, fixture.writes.size());
        assertTrue(fixture.owner.tasks.isEmpty());

        CompletableFuture<Void> retry = fixture.persistence.flush(revision);
        assertEquals(2, fixture.writes.size());
        assertFalse(retry.isDone());
        fixture.writes.getLast().complete(null);
        fixture.owner.runNext();

        retry.join();
        assertFalse(fixture.persistence.isDirty());
    }

    @Test
    void failedOlderWriteKeepsTheLatestSnapshotForExplicitRetry() {
        Fixture fixture = new Fixture();
        fixture.persistence.request(snapshot("first"));
        long latest = fixture.persistence.request(snapshot("latest"));
        CompletableFuture<Void> flush = fixture.persistence.flushLatest();

        fixture.writes.getFirst().completeExceptionally(new IllegalStateException("write failed"));
        fixture.owner.runNext();
        assertTrue(flush.isCompletedExceptionally());
        assertEquals(1, fixture.writes.size());

        fixture.persistence.retry();
        assertEquals(List.of(snapshot("first"), snapshot("latest")), fixture.snapshots);
        fixture.writes.getLast().complete(null);
        fixture.owner.runNext();

        assertEquals(latest, fixture.persistence.getWrittenRevision());
        assertFalse(fixture.persistence.isDirty());
    }

    @Test
    void newRequestRetriesAfterFailureWithoutWritingTheSupersededSnapshot() {
        Fixture fixture = new Fixture();
        fixture.persistence.request(snapshot("first"));
        fixture.writes.getFirst().completeExceptionally(new IllegalStateException("write failed"));
        fixture.owner.runNext();

        fixture.persistence.request(snapshot("replacement"));

        assertEquals(List.of(snapshot("first"), snapshot("replacement")), fixture.snapshots);
    }

    @Test
    void synchronousSubmissionFailureCompletesOnTheOwnerAndRemainsRetryable() {
        ManualExecutor owner = new ManualExecutor();
        IllegalStateException failure = new IllegalStateException("submission failed");
        SnapshotPersistence persistence = new SnapshotPersistence(snapshot -> {
            throw failure;
        }, owner);
        persistence.request(snapshot("latest"));
        CompletableFuture<Void> flush = persistence.flushLatest();
        assertFalse(flush.isDone());
        assertTrue(persistence.isWriteInProgress());

        owner.runNext();

        assertSame(failure, assertThrows(CompletionException.class, flush::join).getCause());
        assertTrue(persistence.isDirty());
        assertFalse(persistence.isWriteInProgress());
        assertTrue(owner.tasks.isEmpty());
    }

    @Test
    void alreadyCompletedWriteStillDispatchesItsCompletionToTheOwner() {
        ManualExecutor owner = new ManualExecutor();
        SnapshotPersistence persistence = new SnapshotPersistence(
                snapshot -> CompletableFuture.completedFuture(null), owner);
        long revision = persistence.request(snapshot("latest"));
        assertEquals(0, persistence.getWrittenRevision());
        assertTrue(persistence.isWriteInProgress());

        owner.runNext();

        assertEquals(revision, persistence.getWrittenRevision());
        persistence.flushLatest().join();
        assertFalse(persistence.isDirty());
    }

    @Test
    void flushCompletionCanRequestAnotherSnapshotWithoutLosingIt() {
        Fixture fixture = new Fixture();
        long first = fixture.persistence.request(snapshot("first"));
        fixture.persistence.request(snapshot("second"));
        fixture.persistence.flush(first).thenRun(() -> fixture.persistence.request(snapshot("third")));

        fixture.writes.getFirst().complete(null);
        fixture.owner.runNext();
        assertEquals(List.of(snapshot("first"), snapshot("second")), fixture.snapshots);
        fixture.writes.getLast().complete(null);
        fixture.owner.runNext();
        assertEquals(List.of(snapshot("first"), snapshot("second"), snapshot("third")), fixture.snapshots);
        fixture.writes.getLast().complete(null);
        fixture.owner.runNext();

        assertFalse(fixture.persistence.isDirty());
        assertEquals(3, fixture.persistence.getWrittenRevision());
    }

    @Test
    void flushRejectsUnknownRevisionsAndEmptyFlushNeedsNoWrite() {
        Fixture fixture = new Fixture();

        fixture.persistence.flushLatest().join();
        assertThrows(IllegalArgumentException.class, () -> fixture.persistence.flush(-1));
        assertThrows(IllegalArgumentException.class, () -> fixture.persistence.flush(1));
        fixture.persistence.retry();
        assertTrue(fixture.writes.isEmpty());
    }

    private static Any snapshot(String value) {
        return Any.newBuilder().setValue(ByteString.copyFromUtf8(value)).build();
    }

    private static class Fixture {
        private final ManualExecutor owner = new ManualExecutor();
        private final List<Any> snapshots = new ArrayList<>();
        private final List<CompletableFuture<Void>> writes = new ArrayList<>();
        private final SnapshotPersistence persistence = new SnapshotPersistence(snapshot -> {
            snapshots.add(snapshot);
            CompletableFuture<Void> write = new CompletableFuture<>();
            writes.add(write);
            return write;
        }, owner);
    }

    private static class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runNext() {
            tasks.remove().run();
        }
    }
}
