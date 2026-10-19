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

package bisq.desktop.common.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AsyncTradeActionTest {
    @Test
    void suppressesDuplicateActionsAndDispatchesCompletionToUi() {
        var ui = new ArrayList<Runnable>();
        var errors = new ArrayList<Throwable>();
        var action = new AsyncTradeAction(ui::add, errors::add);
        var command = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var completed = new AtomicInteger();
        action.activate();
        action.run(() -> { calls.incrementAndGet(); return command; }, completed::incrementAndGet);
        action.run(() -> { calls.incrementAndGet(); return command; });
        assertEquals(1, calls.get());
        assertTrue(action.pendingProperty().get());
        command.complete(null);
        assertEquals(0, completed.get());
        ui.removeFirst().run();
        assertEquals(1, completed.get());
        assertFalse(action.pendingProperty().get());
        assertTrue(errors.isEmpty());
    }

    @Test
    void detachedViewDoesNotDisplayLateFailureOrCancelAcceptedWork() {
        var ui = new ArrayList<Runnable>();
        var errors = new ArrayList<Throwable>();
        var action = new AsyncTradeAction(ui::add, errors::add);
        var command = new CompletableFuture<Void>();
        action.activate();
        action.run(() -> command);
        action.deactivate();
        assertFalse(command.isCancelled());
        command.completeExceptionally(new IllegalStateException("Rejected"));
        action.activate();
        ui.removeFirst().run();
        assertTrue(errors.isEmpty());
        assertFalse(action.pendingProperty().get());
    }

    @Test
    void oldSelectionCompletionDoesNotClearTheNewSelectionsPendingAction() {
        var ui = new ArrayList<Runnable>();
        var action = new AsyncTradeAction(ui::add, error -> fail(error));
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        action.activate();
        action.run(() -> first, () -> fail("Old selection must not navigate"));
        action.invalidate();
        action.run(() -> second);
        first.complete(null);
        ui.removeFirst().run();
        assertTrue(action.pendingProperty().get());
        second.complete(null);
        ui.removeFirst().run();
        assertFalse(action.pendingProperty().get());
    }

    @Test
    void unwrapsRejectedCommandAndAllowsRetry() {
        var errors = new ArrayList<Throwable>();
        var action = new AsyncTradeAction(Runnable::run, errors::add);
        var rejected = new IllegalArgumentException("Rejected");
        action.activate();
        action.run(() -> CompletableFuture.failedFuture(rejected).thenApply(ignored -> null));
        assertEquals(java.util.List.of(rejected), errors);
        assertFalse(action.pendingProperty().get());
        var completed = new AtomicInteger();
        action.run(() -> CompletableFuture.completedFuture(null), completed::incrementAndGet);
        assertEquals(1, completed.get());
    }
}
