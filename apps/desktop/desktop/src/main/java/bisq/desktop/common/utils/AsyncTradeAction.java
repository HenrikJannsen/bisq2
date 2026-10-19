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

import bisq.desktop.common.threading.UIThread;
import bisq.desktop.components.overlay.Popup;
import bisq.i18n.Res;
import bisq.trade.TradeActionNotAppliedException;
import bisq.trade.TradeRestrictedException;
import com.google.common.annotations.VisibleForTesting;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Tracks an action for one view lifecycle without cancelling accepted domain work on detach. */
public final class AsyncTradeAction {
    private final ReadOnlyBooleanWrapper pending = new ReadOnlyBooleanWrapper();
    private final Executor uiExecutor;
    private final Consumer<Throwable> errorHandler;
    private boolean active;
    private long generation;
    private long operation;

    public AsyncTradeAction() {
        this(UIThread::run, AsyncTradeAction::showError);
    }

    @VisibleForTesting
    AsyncTradeAction(Executor uiExecutor, Consumer<Throwable> errorHandler) {
        this.uiExecutor = uiExecutor;
        this.errorHandler = errorHandler;
    }

    public void activate() {
        active = true;
        generation++;
    }

    public void deactivate() {
        active = false;
        generation++;
    }

    public void invalidate() {
        generation++;
        operation++;
        pending.set(false);
    }

    public ReadOnlyBooleanProperty pendingProperty() {
        return pending.getReadOnlyProperty();
    }

    public void run(Supplier<? extends CompletableFuture<?>> action) {
        run(action, () -> {});
    }

    public void run(Supplier<? extends CompletableFuture<?>> action, Runnable onSuccess) {
        if (!active || pending.get()) {
            return;
        }
        long actionGeneration = generation;
        long actionOperation = ++operation;
        pending.set(true);
        CompletableFuture<?> result;
        try {
            result = action.get();
        } catch (Exception error) {
            result = CompletableFuture.failedFuture(error);
        }
        result.whenComplete((ignored, error) -> uiExecutor.execute(() -> {
            if (operation != actionOperation) {
                return;
            }
            pending.set(false);
            if (!active || generation != actionGeneration) {
                return;
            }
            if (error == null) {
                onSuccess.run();
            } else {
                errorHandler.accept(unwrap(error));
            }
        }));
    }

    public static Throwable unwrap(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) {
            error = error.getCause();
        }
        return error;
    }

    public static void showError(Throwable error) {
        error = unwrap(error);
        if (error instanceof TradeRestrictedException restricted) {
            new Popup().warning(TradeExceptionHandler.localizedMessage(restricted)).show();
        } else if (error instanceof TradeActionNotAppliedException) {
            new Popup().warning(Res.get("trade.error.actionNotApplied")).show();
        } else {
            new Popup().error(error).show();
        }
    }
}
