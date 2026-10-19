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
import bisq.network.SendMessageResult;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import bisq.trade.bisq_easy.BisqEasyTradeService;
import bisq.user.identity.UserIdentity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BisqEasyTradeActionServiceTest {
    private final BisqEasyOpenTradeChannelService channels = mock(BisqEasyOpenTradeChannelService.class);
    private final BisqEasyOpenTradeChannel channel = mock(BisqEasyOpenTradeChannel.class);
    private final BisqEasyTradeActionService service = new BisqEasyTradeActionService(channels, Runnable::run);

    @Test
    void rejectedActionDoesNotPublishPrivateData() {
        CompletableFuture<Void> command = new CompletableFuture<>();
        var result = service.executeAndSendTradeLog(() -> command, channel, "Private payment data");
        verifyNoInteractions(channels);
        command.completeExceptionally(new IllegalArgumentException("Action rejected"));
        assertInstanceOf(IllegalArgumentException.class, assertThrows(CompletionException.class, result::join).getCause());
        verifyNoInteractions(channels);
    }

    @Test
    void successfulCommandPublishesOnlyAfterCompletionToCapturedChannel() {
        when(channels.sendTradeLogMessage("Private payment data", channel)).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<Void> command = new CompletableFuture<>();
        var result = service.executeAndSendTradeLog(() -> command, channel, "Private payment data");
        assertFalse(result.isDone());
        verifyNoInteractions(channels);
        command.complete(null);
        result.join();
        verify(channels).sendTradeLogMessage("Private payment data", channel);
    }

    @Test
    void applicationSideEffectsRunOnTheContinuationExecutor() {
        var tasks = new ArrayList<Runnable>();
        var service = new BisqEasyTradeActionService(channels, tasks::add);
        when(channels.sendTradeLogMessage("Private payment data", channel)).thenReturn(CompletableFuture.completedFuture(null));
        var result = service.executeAndSendTradeLog(() -> CompletableFuture.completedFuture(null), channel, "Private payment data");
        verifyNoInteractions(channels);
        assertEquals(1, tasks.size());
        tasks.removeFirst().run();
        result.join();
    }

    @Test
    void shutdownWaitsForSendInitiationButNotNetworkDelivery() {
        CompletableFuture<Void> command = new CompletableFuture<>();
        var delivery = new CompletableFuture<SendMessageResult>();
        when(channels.sendTradeLogMessage("Private payment data", channel)).thenReturn(delivery);
        var result = service.executeAndAwaitTradeLog(() -> command, channel, () -> "Private payment data");
        var drained = service.quiesce();
        assertFalse(drained.isDone());
        command.complete(null);
        drained.join();
        verify(channels).sendTradeLogMessage("Private payment data", channel);
        assertFalse(result.isDone());
        delivery.complete(null);
        result.join();
        assertInstanceOf(RejectedExecutionException.class, assertThrows(CompletionException.class,
                () -> service.executeAndSendTradeLog(() -> CompletableFuture.completedFuture(null), channel, "Late data").join()).getCause());
        verify(channels, never()).sendTradeLogMessage("Late data", channel);
    }

    @Test
    void callerCancellationDoesNotReleaseTheShutdownBarrier() {
        CompletableFuture<Void> command = new CompletableFuture<>();
        when(channels.sendTradeLogMessage("Private payment data", channel)).thenReturn(CompletableFuture.completedFuture(null));
        var result = service.executeAndSendTradeLog(() -> command, channel, "Private payment data");
        result.cancel(false);
        var drained = service.quiesce();
        assertFalse(drained.isDone());
        command.complete(null);
        drained.join();
        verify(channels).sendTradeLogMessage("Private payment data", channel);
    }

    @Test
    void admissionIsBoundedBeforeSubmittingAnotherCommand() {
        for (int i = 0; i < BisqEasyTradeActionService.MAX_PENDING_ACTIONS; i++) {
            service.executeAndSendTradeLog(CompletableFuture::new, channel, "Pending");
        }
        var submitted = new java.util.concurrent.atomic.AtomicBoolean();
        var rejected = service.executeAndSendTradeLog(() -> {
            submitted.set(true);
            return CompletableFuture.completedFuture(null);
        }, channel, "Excess");
        assertInstanceOf(RejectedExecutionException.class, assertThrows(CompletionException.class, rejected::join).getCause());
        assertFalse(submitted.get());
    }

    @Test
    void takeOfferKeepsTheCapturedIdentityAndDrainsItsFollowUpCommand() {
        var tradeService = mock(BisqEasyTradeService.class);
        var trade = mock(BisqEasyTradeReadModel.class, RETURNS_DEEP_STUBS);
        var identity = mock(UserIdentity.class);
        var tradeChannel = mock(BisqEasyOpenTradeChannel.class, RETURNS_DEEP_STUBS);
        when(trade.getId()).thenReturn("trade-id");
        when(trade.getContract().getMediator()).thenReturn(Optional.empty());
        when(identity.getUserName()).thenReturn("Taker");
        when(tradeChannel.getPeer().getUserName()).thenReturn("Maker");
        when(channels.findChannelByTradeId("trade-id")).thenReturn(Optional.of(tradeChannel));
        var takeDelivery = new CompletableFuture<SendMessageResult>();
        when(channels.sendTakeOfferMessage(eq("trade-id"), any(), eq(Optional.empty()), same(identity))).thenReturn(takeDelivery);
        when(channels.sendTradeLogMessage(anyString(), same(tradeChannel))).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<BisqEasyTradeReadModel> created = new CompletableFuture<>();
        CompletableFuture<Void> taken = new CompletableFuture<>();
        when(tradeService.takeOffer(trade)).thenReturn(taken);
        var attempt = service.takeOffer(() -> created, tradeService, identity);
        var drained = service.quiesce();
        created.complete(trade);
        assertSame(trade, attempt.createdTrade().join());
        assertFalse(drained.isDone());
        verifyNoInteractions(channels);
        taken.complete(null);
        drained.join();
        verify(channels).sendTakeOfferMessage("trade-id", trade.getOffer(), Optional.empty(), identity);
        verify(channels).sendTradeLogMessage(anyString(), same(tradeChannel));
        assertFalse(attempt.result().isDone());
        takeDelivery.complete(null);
        assertSame(trade, attempt.result().join());
    }
}
