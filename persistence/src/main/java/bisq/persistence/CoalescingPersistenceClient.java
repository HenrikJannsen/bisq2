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

import bisq.common.util.ExceptionUtil;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * A persistence client which writes its store for every persist request, but never runs two writes of the store at the
 * same time. The requests which arrive while a write runs are written together by one write after it. Unlike
 * {@link RateLimitedPersistenceClient}, it drops no request, so it fits stores whose changes must not get lost, for
 * example trades.
 * <p>
 * The store copy for a write is taken when the write starts, so a write contains every change which was persisted
 * before it started. A failed write leaves the state unsaved, as a request which is not written yet. The next write,
 * or the write at shutdown, writes it again (see docs/specifications/persistence/store-write-results.md).
 */
@Slf4j
public abstract class CoalescingPersistenceClient<T extends PersistableStore<T>> implements PersistenceClient<T> {
    // All fields below are guarded by this lock
    private final Object lock = new Object();
    private boolean writeInProgress;
    // Completes when the requests which wait for the next write are written
    @Nullable
    private CompletableFuture<Boolean> nextWrite;
    // The persisted state might not be on disk: a request waits, a write runs, or the last write failed
    private boolean unsaved;

    public CoalescingPersistenceClient() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::persistOnShutdown,
                "CoalescingPersistenceClient.shutdownHook"));
    }

    /**
     * @return a future which completes with true when a write which contains the current state succeeded, and with
     * false when that write failed
     */
    @Override
    public CompletableFuture<Boolean> persist() {
        CompletableFuture<Boolean> result;
        synchronized (lock) {
            unsaved = true;
            if (nextWrite == null) {
                nextWrite = new CompletableFuture<>();
            }
            result = nextWrite;
            if (writeInProgress) {
                // The running write starts the next write when it completes
                return result;
            }
            writeInProgress = true;
        }
        startWrite();
        return result;
    }

    @VisibleForTesting
    boolean isUnsaved() {
        synchronized (lock) {
            return unsaved;
        }
    }

    // The shutdown hook writes the state synchronously if it might not be on disk. Writes of a store are serialized by
    // PersistableStoreReaderWriter, so this write does not interfere with a write which still runs.
    @VisibleForTesting
    void persistOnShutdown() {
        synchronized (lock) {
            if (!unsaved) {
                return;
            }
            unsaved = false;
        }
        try {
            getPersistence().persist(getPersistableStore().getClone());
        } catch (RuntimeException e) {
            // Persistence.persist has logged the failure without store content
        }
    }

    private void startWrite() {
        CompletableFuture<Boolean> write;
        synchronized (lock) {
            write = nextWrite;
            nextWrite = null;
        }
        T clone;
        try {
            clone = getPersistableStore().getClone();
        } catch (RuntimeException e) {
            // The message of the exception can contain values of the store, so we log only its type and stack trace
            Path storePath = getPersistence().getStorePath();
            log.error("Copying the store {} for writing failed. Cause: {}", storePath,
                    ExceptionUtil.getSafeStackTraceAsString(e));
            onWriteCompleted(write, false);
            return;
        }
        try {
            getPersistence().persistAsync(clone)
                    .whenComplete((nil, throwable) -> onWriteCompleted(write, throwable == null));
        } catch (RuntimeException e) {
            log.error("Starting the write of the store {} failed", getPersistence().getStorePath(), e);
            onWriteCompleted(write, false);
        }
    }

    private void onWriteCompleted(CompletableFuture<Boolean> write, boolean success) {
        boolean writeAgain;
        synchronized (lock) {
            writeAgain = nextWrite != null;
            if (success && !writeAgain) {
                unsaved = false;
            }
            writeInProgress = writeAgain;
        }
        write.complete(success);
        if (writeAgain) {
            startWrite();
        }
    }
}
