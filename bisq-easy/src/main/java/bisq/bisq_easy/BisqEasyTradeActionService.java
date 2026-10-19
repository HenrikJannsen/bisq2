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

package bisq.bisq_easy;

import bisq.chat.bisq_easy.open_trades.BisqEasyOpenTradeChannel;
import bisq.chat.bisq_easy.open_trades.BisqEasyOpenTradeChannelService;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Coordinates trade commands with their chat projection. */
public final class BisqEasyTradeActionService {
    private final BisqEasyOpenTradeChannelService channelService;

    public BisqEasyTradeActionService(BisqEasyOpenTradeChannelService channelService) {
        this.channelService = channelService;
    }

    public CompletableFuture<Void> executeAndSendTradeLog(Runnable tradeAction,
                                                          BisqEasyOpenTradeChannel channel,
                                                          String encodedMessage) {
        return executeAndSendTradeLog(tradeAction, channel, () -> encodedMessage);
    }

    public CompletableFuture<Void> executeAndSendTradeLog(Runnable tradeAction,
                                                          BisqEasyOpenTradeChannel channel,
                                                          Supplier<String> encodedMessage) {
        // A rejected command must never publish the private data of the requested action.
        tradeAction.run();
        return channelService.sendTradeLogMessage(encodedMessage.get(), channel).thenApply(result -> null);
    }
}
