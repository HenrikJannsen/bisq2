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
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Coalesces complete immutable snapshots without losing the last requested write. All methods and callbacks must
 * run on the store's state owner. The completion executor must dispatch to that owner and continue accepting
 * callbacks until the final flush completes, including while ordinary command admission is closed.
 *
 * A write failure fails current flush requests and retains the latest snapshot as dirty. A later request, flush,
 * or explicit retry attempts it again; failures never cause an automatic retry loop. Successful writes refer to
 * file replacement, not an fsync guarantee. Snapshot capture failures belong to the caller, before request().
 */
public final class SnapshotPersistence {
    private final Function<Any, CompletableFuture<Void>> writer;
    private final Executor completionExecutor;
    private final List<FlushRequest> flushRequests = new ArrayList<>();
    @Getter
    private long requestedRevision;
    @Getter
    private long writtenRevision;
    @Getter
    private boolean writeInProgress;
    @Nullable
    private Any latestSnapshot;

    public SnapshotPersistence(Function<Any, CompletableFuture<Void>> writer, Executor completionExecutor) {
        this.writer = checkNotNull(writer);
        this.completionExecutor = checkNotNull(completionExecutor);
    }

    public long request(Any snapshot) {
        latestSnapshot = checkNotNull(snapshot);
        long revision = ++requestedRevision;
        startWriteIfNeeded();
        return revision;
    }

    /** Completes when this revision or a newer complete snapshot replaces the store file. */
    public CompletableFuture<Void> flush(long revision) {
        checkArgument(revision >= 0 && revision <= requestedRevision, "Unknown snapshot revision");
        if (revision <= writtenRevision) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        flushRequests.add(new FlushRequest(revision, result));
        startWriteIfNeeded();
        return result;
    }

    public CompletableFuture<Void> flushLatest() {
        return flush(requestedRevision);
    }

    public void retry() {
        startWriteIfNeeded();
    }

    public boolean isDirty() {
        return writtenRevision < requestedRevision;
    }

    private void startWriteIfNeeded() {
        if (writeInProgress || !isDirty()) {
            return;
        }
        long revision = requestedRevision;
        Any snapshot = checkNotNull(latestSnapshot);
        writeInProgress = true;
        CompletableFuture<Void> write;
        try {
            write = checkNotNull(writer.apply(snapshot));
        } catch (RuntimeException exception) {
            write = CompletableFuture.failedFuture(exception);
        }
        write.whenComplete((unused, throwable) ->
                completionExecutor.execute(() -> onWriteCompleted(revision, throwable)));
    }

    private void onWriteCompleted(long revision, @Nullable Throwable throwable) {
        writeInProgress = false;
        if (throwable != null) {
            List<FlushRequest> failed = List.copyOf(flushRequests);
            flushRequests.clear();
            failed.forEach(request -> request.result().completeExceptionally(throwable));
            return;
        }
        writtenRevision = revision;
        List<FlushRequest> completed = flushRequests.stream()
                .filter(request -> request.revision() <= writtenRevision)
                .toList();
        flushRequests.removeAll(completed);
        if (!isDirty()) {
            latestSnapshot = null;
        }
        // Start the latest pending snapshot even when no new command follows this completion.
        startWriteIfNeeded();
        completed.forEach(request -> request.result().complete(null));
    }

    private record FlushRequest(long revision, CompletableFuture<Void> result) {
    }
}
