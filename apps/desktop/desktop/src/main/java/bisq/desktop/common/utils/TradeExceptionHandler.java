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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public final class TradeExceptionHandler {
    private TradeExceptionHandler() {
    }

    /**
     * Starts the trade action, and runs onApplied in the UI thread when the trade has applied it. In onApplied the
     * caller sends the data of the action to the peer as trade log message
     * (docs/specifications/trade/trade-log-of-user-actions.md). The trade applies the action in a task of the trade,
     * so the UI thread does not wait for it.
     */
    public static void run(Supplier<? extends CompletableFuture<?>> tradeAction, Runnable onApplied) {
        CompletableFuture<?> result;
        try {
            result = tradeAction.get();
        } catch (TradeRestrictedException | TradeActionNotAppliedException e) {
            showWarning(e);
            return;
        }
        result.whenComplete((nil, throwable) -> UIThread.run(() -> {
            if (throwable == null) {
                onApplied.run();
                return;
            }
            Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null ?
                    throwable.getCause() :
                    throwable;
            if (cause instanceof TradeRestrictedException || cause instanceof TradeActionNotAppliedException) {
                showWarning((RuntimeException) cause);
            } else {
                new Popup().error(cause).show();
            }
        }));
    }

    public static boolean run(Runnable tradeAction) {
        try {
            tradeAction.run();
            return true;
        } catch (TradeRestrictedException | TradeActionNotAppliedException e) {
            showWarning(e);
            return false;
        }
    }

    /**
     * The exception carries a stable English message for API clients; the desktop popup maps the
     * structured restriction back to the user's language instead of showing that raw text.
     */
    public static String localizedMessage(TradeRestrictedException e) {
        return switch (e.getRestriction()) {
            case HALT_TRADING -> Res.get("trade.error.tradingHalted");
            case MIN_VERSION_REQUIRED -> Res.get("trade.error.minVersionRequired", e.findMinRequiredVersion().orElse(""));
            case MAKER_VERSION_BELOW_MIN_REQUIRED -> Res.get("trade.error.makerVersionBelowMinRequired",
                    e.findMinRequiredVersion().orElse(""));
        };
    }

    private static void showWarning(RuntimeException e) {
        if (e instanceof TradeRestrictedException tradeRestrictedException) {
            new Popup().warning(localizedMessage(tradeRestrictedException)).show();
        } else {
            // The trade was closed or failed in the meantime. The caller must not send the data of the action.
            new Popup().warning(Res.get("trade.error.actionNotApplied")).show();
        }
    }
}
