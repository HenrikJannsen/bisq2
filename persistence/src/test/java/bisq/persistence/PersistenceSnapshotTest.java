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
import com.google.protobuf.Any;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersistenceSnapshotTest {
    @TempDir
    Path tempDir;

    @Test
    void writesCapturedValuesInTheExistingDelimitedAnyFormat() throws Exception {
        Persistence<TimestampStore> persistence = persistence(MaxBackupSize.ZERO);
        TimestampStore store = store(1);
        Any snapshot = store.toAny();
        store.getTimestampsByProfileId().put("profile", 2L);

        persistence.persistSnapshotAsync(snapshot).join();

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        snapshot.writeDelimitedTo(expected);
        assertArrayEquals(expected.toByteArray(), Files.readAllBytes(persistence.getStorePath()));
        PersistableStoreResolver.addResolver(store);
        assertEquals(1L, persistence.read().orElseThrow().getTimestampsByProfileId().get("profile"));
    }

    @Test
    void snapshotsAndOrdinaryWritesUseTheSameSubmissionOrder() {
        Persistence<TimestampStore> persistence = persistence(MaxBackupSize.ZERO);
        TimestampStore first = store(1);
        TimestampStore last = store(3);
        PersistableStoreResolver.addResolver(first);

        CompletableFuture<Void> firstWrite = persistence.persistAsync(first);
        CompletableFuture<Void> snapshotWrite = persistence.persistSnapshotAsync(store(2).toAny());
        CompletableFuture<Void> lastWrite = persistence.persistAsync(last);
        CompletableFuture.allOf(firstWrite, snapshotWrite, lastWrite).join();

        assertEquals(3L, persistence.read().orElseThrow().getTimestampsByProfileId().get("profile"));
    }

    @Test
    void snapshotWriteUsesTheExistingBackupPath() throws Exception {
        Persistence<TimestampStore> persistence = persistence(MaxBackupSize.TEN_MB);
        Any previous = store(1).toAny();
        persistence.persistSnapshotAsync(previous).join();

        persistence.persistSnapshotAsync(store(2).toAny()).join();

        assertEquals(1, persistence.getBackups().size());
        try (var input = Files.newInputStream(persistence.getBackups().getFirst().getPath())) {
            assertEquals(previous, Any.parseDelimitedFrom(input));
        }
    }

    @Test
    void failedSnapshotWriteReportsFailureAndKeepsThePreviousStore() throws Exception {
        Persistence<TimestampStore> persistence = persistence(MaxBackupSize.ZERO);
        persistence.persistSnapshotAsync(store(1).toAny()).join();
        byte[] previous = Files.readAllBytes(persistence.getStorePath());
        Files.createDirectory(persistence.getStorePath().resolveSibling(
                PersistableStoreFileManager.TEMP_FILE_PREFIX + persistence.getStorePath().getFileName()));

        assertThrows(CompletionException.class,
                () -> persistence.persistSnapshotAsync(store(2).toAny()).join());

        assertArrayEquals(previous, Files.readAllBytes(persistence.getStorePath()));
    }

    private Persistence<TimestampStore> persistence(MaxBackupSize maxBackupSize) {
        return new Persistence<>(tempDir.resolve("db/private"), "SnapshotStore", maxBackupSize, new RestoreService());
    }

    private static TimestampStore store(long timestamp) {
        TimestampStore store = new TimestampStore();
        store.getTimestampsByProfileId().put("profile", timestamp);
        return store;
    }
}
