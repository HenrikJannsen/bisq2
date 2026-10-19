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
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BisqEasyTradeActionServiceTest {
    private final BisqEasyOpenTradeChannelService channels = mock(BisqEasyOpenTradeChannelService.class);
    private final BisqEasyOpenTradeChannel channel = mock(BisqEasyOpenTradeChannel.class);
    private final BisqEasyTradeActionService service = new BisqEasyTradeActionService(channels);

    @Test
    void rejectedActionDoesNotPublishPrivateData() {
        assertThrows(IllegalStateException.class, () -> service.executeAndSendTradeLog(() -> {
            throw new IllegalStateException("Action rejected");
        }, channel, "Private payment data"));
        verifyNoInteractions(channels);
    }

    @Test
    void successfulActionPublishesToCapturedChannel() {
        when(channels.sendTradeLogMessage("Private payment data", channel))
                .thenReturn(CompletableFuture.completedFuture(null));
        service.executeAndSendTradeLog(() -> verifyNoInteractions(channels), channel, "Private payment data").join();
        verify(channels).sendTradeLogMessage("Private payment data", channel);
    }

    @Test
    void completionWaitsForTradeLog() {
        var delivery = new CompletableFuture<bisq.network.SendMessageResult>();
        when(channels.sendTradeLogMessage("Private payment data", channel)).thenReturn(delivery);
        var result = service.executeAndSendTradeLog(() -> {}, channel, "Private payment data");
        assertFalse(result.isDone());
        delivery.complete(null);
        assertTrue(result.isDone());
    }
}
