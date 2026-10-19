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
import bisq.chat.priv.LeavePrivateChatManager;
import bisq.common.threading.ExecutorFactory;
import bisq.i18n.Res;
import bisq.user.identity.UserIdentity;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import bisq.trade.bisq_easy.BisqEasyTradeService;
import com.google.common.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;

/** Coordinates trade commands with their chat projection, independently of the originating view. */
public final class BisqEasyTradeActionService {
    static final int MAX_PENDING_ACTIONS = 256;
    private final BisqEasyOpenTradeChannelService channelService;
    private final Executor executor;
    private final Set<CompletableFuture<Void>> pendingActions = new HashSet<>();
    private boolean acceptingActions = true;

    public BisqEasyTradeActionService(BisqEasyOpenTradeChannelService channelService) {
        this(channelService, ExecutorFactory.commonForkJoinPool());
    }

    @VisibleForTesting
    BisqEasyTradeActionService(BisqEasyOpenTradeChannelService channelService, Executor executor) {
        this.channelService = channelService;
        this.executor = executor;
    }

    public CompletableFuture<Void> executeAndSendTradeLog(Supplier<CompletableFuture<Void>> tradeAction,
                                                          BisqEasyOpenTradeChannel channel,
                                                          String encodedMessage) {
        return executeAndSendTradeLog(tradeAction, channel, () -> encodedMessage);
    }

    public CompletableFuture<Void> executeAndSendTradeLog(Supplier<CompletableFuture<Void>> tradeAction,
                                                          BisqEasyOpenTradeChannel channel,
                                                          Supplier<String> encodedMessage) {
        return execute(tradeAction, ignored -> {
            channelService.sendTradeLogMessage(encodedMessage.get(), channel);
            return CompletableFuture.completedFuture(null);
        });
    }

    public CompletableFuture<Void> executeAndAwaitTradeLog(Supplier<CompletableFuture<Void>> tradeAction,
                                                           BisqEasyOpenTradeChannel channel,
                                                           Supplier<String> encodedMessage) {
        return execute(tradeAction, ignored -> channelService.sendTradeLogMessage(encodedMessage.get(), channel)
                .thenApply(result -> null));
    }

    public CompletableFuture<Void> closeTrade(BisqEasyTradeService tradeService,
                                               LeavePrivateChatManager leavePrivateChatManager,
                                               BisqEasyTradeReadModel trade,
                                               BisqEasyOpenTradeChannel channel) {
        return execute(() -> tradeService.closeTrade(trade, channel.getMyUserIdentity().getUserProfile(), channel.getPeer()),
                ignored -> {
                    leavePrivateChatManager.leaveChannel(channel);
                    return CompletableFuture.completedFuture(null);
                });
    }

    public TakeOfferAttempt takeOffer(Supplier<CompletableFuture<BisqEasyTradeReadModel>> createTrade,
                                      BisqEasyTradeService tradeService,
                                      UserIdentity takerIdentity) {
        CompletableFuture<BisqEasyTradeReadModel> createdTrade = new CompletableFuture<>();
        CompletableFuture<BisqEasyTradeReadModel> result = execute(() -> createTrade.get()
                        .thenComposeAsync(trade -> {
                            createdTrade.complete(trade);
                            return tradeService.takeOffer(trade).thenApply(ignored -> trade);
                        }, executor),
                trade -> {
                    var takeOfferDelivery = channelService.sendTakeOfferMessage(trade.getId(), trade.getOffer(),
                            trade.getContract().getMediator(), takerIdentity);
                    if (!takeOfferDelivery.isCompletedExceptionally()) {
                        channelService.findChannelByTradeId(trade.getId()).ifPresent(channel ->
                                channelService.sendTradeLogMessage(Res.encode("bisqEasy.takeOffer.tradeLogMessage",
                                        takerIdentity.getUserName(), channel.getPeer().getUserName()), channel));
                    }
                    return takeOfferDelivery.thenApply(ignored -> trade);
                });
        result.whenComplete((trade, error) -> {
            if (error != null) {
                createdTrade.completeExceptionally(error);
            }
        });
        return new TakeOfferAttempt(createdTrade.copy(), result);
    }

    /** Stops admission and waits until accepted commands have initiated their chat side effects. */
    public synchronized CompletableFuture<Void> quiesce() {
        acceptingActions = false;
        return CompletableFuture.allOf(pendingActions.toArray(CompletableFuture[]::new));
    }

    private <T, R> CompletableFuture<R> execute(Supplier<CompletableFuture<T>> command,
                                               Function<T, CompletableFuture<R>> afterSuccess) {
        CompletableFuture<Void> initiated = new CompletableFuture<>();
        synchronized (this) {
            if (!acceptingActions || pendingActions.size() >= MAX_PENDING_ACTIONS) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Trade actions are unavailable"));
            }
            pendingActions.add(initiated);
        }
        CompletableFuture<R> result;
        try {
            // A rejected command must never publish the private data of the requested action.
            result = command.get().thenComposeAsync(value -> {
                try {
                    return afterSuccess.apply(value);
                } finally {
                    completeInitiation(initiated);
                }
            }, executor);
        } catch (Exception error) {
            result = CompletableFuture.failedFuture(error);
        }
        // The delivery future can need the shutdown mailbox flush to finish. Only wait for initiation.
        CompletableFuture<R> delivery = result;
        // Failures before the continuation still have to release the accepted workflow.
        delivery.whenComplete((value, error) -> completeInitiation(initiated));
        return delivery.copy();
    }

    private void completeInitiation(CompletableFuture<Void> initiated) {
        synchronized (this) {
            pendingActions.remove(initiated);
        }
        initiated.complete(null);
    }

    public record TakeOfferAttempt(CompletableFuture<BisqEasyTradeReadModel> createdTrade,
                                    CompletableFuture<BisqEasyTradeReadModel> result) {
    }
}
