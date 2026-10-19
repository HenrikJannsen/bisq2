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

package bisq.trade.bisq_easy.handler;

import bisq.common.fsm.Event;
import bisq.network.SendMessageResult;
import bisq.trade.ServiceProvider;
import bisq.trade.bisq_easy.BisqEasyTrade;
import bisq.trade.bisq_easy.BisqEasyTradeService;
import bisq.trade.protocol.handler.TradeEventHandlerAsMessageSender;
import bisq.trade.protocol.messages.TradeMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/**
 * Our trade messages carry {@code trade.getMyself().getNetworkId()} as sender: the network ID which the trade peer
 * stored for us. The identity of the trade, from which we send, can have a newer address map, for example if the maker
 * added a transport after it published the offer. Nodes of version 2.1.13 compare the sender with the stored network ID
 * including the addresses, and fail the trade if they differ. See docs/specifications/user/identity-by-key.md.
 */
@Slf4j
public abstract class BisqEasyTradeEventHandlerAsMessageSender<T extends BisqEasyTrade, E extends Event> extends TradeEventHandlerAsMessageSender<T, E> {
    protected final BisqEasyTradeService tradeService;

    protected BisqEasyTradeEventHandlerAsMessageSender(ServiceProvider serviceProvider, T trade) {
        super(serviceProvider, trade);

        tradeService = serviceProvider.getBisqEasyTradeService();
    }

    public final void handle(E event) {
        super.handle(event);
    }

    // The trade service sends the message in a queue of the trade on other threads, so the task of the trade does not
    // wait for the network (docs/specifications/trade/trade-task-execution.md)
    @Override
    protected CompletableFuture<SendMessageResult> send(TradeMessage message) {
        return tradeService.sendTradeMessage(trade, () -> super.send(message));
    }
}