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

import bisq.common.threading.ExecutorFactory;
import bisq.common.util.ExceptionUtil;
import bisq.common.util.StringUtils;
import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This implementation of PersistenceClient drops persist requests if they happen too frequently.
 * It registers a shutdown hook and persists at shutdown. If the JVM got terminated non-gracefully
 * (e.g. kill signal or JVM crash) the shutdown hook is not executed (but any other approach to write in such cases
 * would fail as well).
 * As there is no guarantee that the last data are persisted in case of such unexpected terminations, it should be only
 * used if data loss is not critical (e.g. network data) and when write frequency is rather high.
 * A client which overrides {@link #writeDroppedStateLater()} gets a dropped state written as soon as the rate allows
 * it (see docs/specifications/persistence/store-write-results.md).
 */
@Slf4j
public abstract class RateLimitedPersistenceClient<T extends PersistableStore<T>> implements PersistenceClient<T> {
    // One daemon thread for the deferred writes of all clients which want them
    private static final ScheduledExecutorService DEFERRED_WRITES =
            ExecutorFactory.newSingleThreadScheduledExecutor("RateLimitedPersistenceClient.deferredWrites");

    @VisibleForTesting
    interface DeferredWriteScheduler {
        void schedule(Runnable task, long delayInMs);
    }

    private volatile long lastWrite;
    @Getter
    private volatile boolean dropped;
    private volatile boolean writeInProgress;
    private final DeferredWriteScheduler deferredWriteScheduler;
    private final AtomicBoolean deferredWriteScheduled = new AtomicBoolean();
    private volatile boolean deferredWritesStopped;

    public RateLimitedPersistenceClient() {
        this((task, delayInMs) -> DEFERRED_WRITES.schedule(task, delayInMs, TimeUnit.MILLISECONDS));
    }

    @VisibleForTesting
    RateLimitedPersistenceClient(DeferredWriteScheduler deferredWriteScheduler) {
        this.deferredWriteScheduler = deferredWriteScheduler;
        //todo (Critical) check if we want to use ShutdownHook here
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Path storePath = getPersistence().getStorePath();
            if (storePath != null) {
                Thread.currentThread().setName("RateLimitedPersistenceClient.shutdownHook-" + StringUtils.truncate(storePath, 8));
            }
            persistOnShutdown();
        }));
    }

    @Override
    public CompletableFuture<Boolean> persist() {
        boolean tooFast = System.currentTimeMillis() - lastWrite < getMaxWriteRateInMs();
        dropped = tooFast || writeInProgress;
        if (dropped) {
            // A write in progress schedules the deferred write itself when it completes. This call reads
            // writeInProgress after it set dropped, and the completion reads dropped after it reset writeInProgress,
            // so at least one of them schedules.
            if (writeDroppedStateLater() && !writeInProgress) {
                scheduleDeferredWrite();
            }
            return CompletableFuture.completedFuture(false);
        } else {
            lastWrite = System.currentTimeMillis();
            writeInProgress = true;
            dropped = false;
            T clone;
            try {
                clone = getPersistableStore().getClone();
            } catch (RuntimeException e) {
                // Without the reset, every later call would see a write in progress and drop its state until
                // shutdown. The state is not on disk, so the next call or the shutdown hook writes it again (see
                // docs/specifications/persistence/store-write-results.md). The message of the cause can contain values
                // of the store, so the log contains only its type and stack trace.
                writeInProgress = false;
                dropped = true;
                log.error("Copying the store {} for a write failed. Cause: {}", getPersistence().getStorePath(),
                        ExceptionUtil.getSafeStackTraceAsString(e));
                return CompletableFuture.completedFuture(false);
            }
            return getPersistence()
                    .persistAsync(clone)
                    .handle((nil, throwable) -> {
                        writeInProgress = false;
                        if (throwable != null) {
                            // The state is not on disk. The next call or the shutdown hook writes it again
                            // (see docs/specifications/persistence/store-write-results.md).
                            dropped = true;
                        } else if (dropped && writeDroppedStateLater()) {
                            // A call during this write dropped its state
                            scheduleDeferredWrite();
                        }
                        return throwable == null;
                    });
        }
    }

    protected long getMaxWriteRateInMs() {
        return 1000;
    }

    // A client returns true if a dropped state must not wait for its next write, for example because that write can
    // come hours later. Then the client writes a dropped state as soon as the rate allows it. A failed copy or write is
    // not repeated, so that a full disk does not log an error every second.
    protected boolean writeDroppedStateLater() {
        return false;
    }

    // The owner calls this when it shuts down, so that no deferred write runs after it. The shutdown hook still writes a
    // dropped state.
    protected void stopDeferredWrites() {
        deferredWritesStopped = true;
    }

    // At most one deferred write is scheduled. The delay is the rest of the interval since the start of the last write.
    private void scheduleDeferredWrite() {
        if (deferredWritesStopped || !deferredWriteScheduled.compareAndSet(false, true)) {
            return;
        }
        long delayInMs = Math.max(0, lastWrite + getMaxWriteRateInMs() - System.currentTimeMillis());
        deferredWriteScheduler.schedule(this::writeDroppedState, delayInMs);
    }

    private void writeDroppedState() {
        deferredWriteScheduled.set(false);
        if (!deferredWritesStopped && dropped) {
            persist();
        }
    }

    private void persistOnShutdown() {
        deferredWritesStopped = true;
        if (dropped) {
            dropped = false;
            try {
                getPersistence().persist(getPersistableStore().getClone());
            } catch (RuntimeException e) {
                // Persistence.persist has logged the failure without store content. The default handler of the
                // shutdown hook thread would print the messages of all causes.
            }
        }
    }
}