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

package bisq.trade.bisq_easy;

import bisq.account.payment_method.BitcoinPaymentMethodSpec;
import bisq.account.payment_method.fiat.FiatPaymentMethodSpec;
import bisq.bonded_roles.release.AppType;
import bisq.bonded_roles.security_manager.alert.AlertService;
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertDataUtils;
import bisq.chat.bisq_easy.open_trades.BisqEasyOpenTradeChannel;
import bisq.chat.bisq_easy.open_trades.BisqEasyOpenTradeChannelService;
import bisq.common.application.ApplicationVersion;
import bisq.common.application.Service;
import bisq.common.monetary.Monetary;
import bisq.common.observable.Pin;
import bisq.common.observable.collection.CollectionObserver;
import bisq.common.observable.collection.ReadOnlyObservableSet;
import bisq.common.platform.Version;
import bisq.common.threading.ExecutorFactory;
import bisq.common.timer.Scheduler;
import bisq.common.util.CompletableFutureUtils;
import bisq.common.util.ExceptionUtil;
import bisq.common.util.StringUtils;
import bisq.common.validation.RequestedAmountValidation;
import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.i18n.Res;
import bisq.identity.Identity;
import bisq.identity.IdentityService;
import bisq.network.NetworkService;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
import bisq.offer.bisq_easy.BisqEasyOffer;
import bisq.persistence.DbSubDirectory;
import bisq.persistence.Persistence;
import bisq.persistence.RateLimitedPersistenceClient;
import bisq.settings.SettingsService;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeActionNotAppliedException;
import bisq.trade.TradeRestrictedException;
import bisq.trade.TradeTaskExecutor;
import bisq.trade.bisq_easy.protocol.BisqEasyBuyerAsMakerProtocol;
import bisq.trade.bisq_easy.protocol.BisqEasyBuyerAsTakerProtocol;
import bisq.trade.bisq_easy.protocol.BisqEasyClosedTrade;
import bisq.trade.bisq_easy.protocol.BisqEasyProtocol;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import bisq.trade.bisq_easy.protocol.BisqEasySellerAsMakerProtocol;
import bisq.trade.bisq_easy.protocol.BisqEasySellerAsTakerProtocol;
import bisq.trade.bisq_easy.protocol.events.BisqEasyAccountDataEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyBtcConfirmedEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyCancelTradeEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyChatChannelMismatchEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyConfirmBtcSentEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyConfirmFiatReceiptEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyConfirmFiatSentEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyRejectTradeEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasySendBtcAddressEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyTakeOfferEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyTradeEvent;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyReportErrorMessage;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyTakeOfferRequest;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyTakeOfferRequestValidator;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyTradeMessage;
import bisq.trade.bisq_easy.validation.BisqEasySellerReputationValidator;
import bisq.trade.bisq_easy.validation.BisqEasyTradeChatChannelValidator;
import bisq.trade.exceptions.TradeProtocolException;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.protocol.handler.TradeMessageHandler;
import bisq.trade.protocol.messages.PendingTradeMessages;
import bisq.user.banned.BannedUserService;
import bisq.user.contact_list.ContactListService;
import bisq.user.contact_list.ContactReason;
import bisq.user.profile.UserProfile;
import bisq.user.profile.UserProfileService;
import com.google.common.annotations.VisibleForTesting;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static bisq.trade.bisq_easy.protocol.messages.BisqEasyReportErrorMessage.MAX_LENGTH_ERROR_MESSAGE;
import static bisq.trade.bisq_easy.protocol.messages.BisqEasyReportErrorMessage.MAX_LENGTH_STACKTRACE;
import static bisq.trade.bisq_easy.validation.BisqEasyOfferAmountValidator.validateOfferAmount;
import static com.google.common.base.Preconditions.checkArgument;

//TODO Consider to use async calls at handle (CompletableFuture.runAsync(()...)

@Slf4j
@Getter
public class BisqEasyTradeService extends RateLimitedPersistenceClient<BisqEasyTradeStore> implements Service, ConfidentialMessageService.Listener {
    // Tasks of different trades can run at the same time. A task of a trade which waits for the network does not stop
    // the tasks of the other trades.
    private static final int NUM_TRADE_TASK_THREADS = 4;
    private static final long TRADE_TASKS_SHUTDOWN_TIMEOUT_SEC = 10;

    private final ServiceProvider serviceProvider;
    private final NetworkService networkService;
    private final IdentityService identityService;
    private final SettingsService settingsService;
    private final BannedUserService bannedUserService;
    private final AlertService alertService;

    private final Persistence<BisqEasyTradeStore> persistence;
    private final AppType appType;
    private final BisqEasyTradeStore persistableStore = new BisqEasyTradeStore();

    // We don't persist the protocol, only the model.
    private final Map<String, BisqEasyProtocol> tradeProtocolById = new ConcurrentHashMap<>();
    private final ContactListService contactListService;
    private final UserProfileService userProfileService;
    private final BisqEasyOpenTradeChannelService bisqEasyOpenTradeChannelService;
    // Written from the alert-set observer thread, read from trade calls on other threads
    private volatile boolean haltTrading;
    private volatile Optional<String> minRequiredVersionForTrading = Optional.empty();
    @Nullable
    private Pin authorizedAlertDataSetPin, numDaysAfterRedactingTradeDataPin, chatChannelsPin;
    @Nullable
    private Scheduler numDaysAfterRedactingTradeDataScheduler;
    private final PendingTradeMessages<BisqEasyTradeMessage> pendingMessages = new PendingTradeMessages<>();
    // Runs the operations of each trade one after the other (docs/specifications/trade/trade-task-execution.md)
    @Getter(AccessLevel.NONE)
    private final Executor tradeTaskThreads;
    @Getter(AccessLevel.NONE)
    private final TradeTaskExecutor tradeTaskExecutor;

    public BisqEasyTradeService(ServiceProvider serviceProvider, AppType appType) {
        this(serviceProvider, appType, ExecutorFactory.newFixedThreadPool("BisqEasyTradeTask", NUM_TRADE_TASK_THREADS));
    }

    // Tests pass an executor which runs the tasks in the calling thread
    @VisibleForTesting
    BisqEasyTradeService(ServiceProvider serviceProvider, AppType appType, Executor tradeTaskThreads) {
        this.tradeTaskThreads = tradeTaskThreads;
        tradeTaskExecutor = new TradeTaskExecutor(tradeTaskThreads);
        this.serviceProvider = serviceProvider;
        networkService = serviceProvider.getNetworkService();
        identityService = serviceProvider.getIdentityService();
        settingsService = serviceProvider.getSettingsService();
        bannedUserService = serviceProvider.getUserService().getBannedUserService();
        alertService = serviceProvider.getBondedRolesService().getAlertService();
        contactListService = serviceProvider.getUserService().getContactListService();
        userProfileService = serviceProvider.getUserService().getUserProfileService();
        bisqEasyOpenTradeChannelService = serviceProvider.getChatService().getBisqEasyOpenTradeChannelService();

        persistence = serviceProvider.getPersistenceService().getOrCreatePersistence(this, DbSubDirectory.PRIVATE, persistableStore);
        this.appType = appType;
    }


    /* --------------------------------------------------------------------- */
    // Service
    /* --------------------------------------------------------------------- */

    public CompletableFuture<Boolean> initialize() {
        // A message for one of our trades which the peer sent to another of our user profiles must not create a channel
        // (rule 6 of docs/specifications/trade/pending-trade-messages.md).
        bisqEasyOpenTradeChannelService.setIsTradeWithPeer((tradeId, senderUserProfileId, receiverUserProfileId) ->
                findTrade(tradeId)
                        .filter(trade -> trade.getPeer().getNetworkId().getId().equals(senderUserProfileId) &&
                                trade.getMyself().getNetworkId().getId().equals(receiverUserProfileId))
                        .isPresent());

        // Submitted before the subscription below, so a message of a stored trade runs after the creation of its protocol
        List<CompletableFuture<Void>> protocolCreations = persistableStore.getTrades().stream()
                .map(trade -> tradeTaskExecutor.submit(trade.getId(),
                                () -> processPendingMessages(createAndAddTradeProtocol(trade)))
                        .exceptionally(throwable -> {
                            log.error("Creating the protocol of trade {} failed", trade.getId(), throwable);
                            return null;
                        }))
                .toList();

        // Checks all existing channels against their trades, and later a channel which appears for an existing
        // trade. A trade which appears for an existing channel is checked when its protocol gets created.
        chatChannelsPin = bisqEasyOpenTradeChannelService.getChannels().addObserver(new CollectionObserver<>() {
            @Override
            public void onAdded(BisqEasyOpenTradeChannel channel) {
                String tradeId = channel.getTradeId();
                runTradeTask(tradeId, () -> findTrade(tradeId).ifPresent(trade -> failTradeOnChatChannelMismatch(trade, channel)));
            }

            @Override
            public void onRemoved(Object element) {
            }

            @Override
            public void onCleared() {
            }
        });

        // The subscription replays the processed messages of earlier sessions, so the trade restrictions of the stored
        // alerts must be known before it (see docs/specifications/network/startup-replay.md).
        authorizedAlertDataSetPin = alertService.getAuthorizedAlertDataSet().addObserver(this::updateTradeRestrictions);

        networkService.subscribeToConfidentialMessages(this);

        numDaysAfterRedactingTradeDataScheduler = Scheduler.run(this::maybeRedactDataOfCompletedTrades)
                .host(this)
                .periodically(1, TimeUnit.HOURS);
        numDaysAfterRedactingTradeDataPin = settingsService.getNumDaysAfterRedactingTradeData().addObserver(numDays -> maybeRedactDataOfCompletedTrades());

        // After the initialization, the protocols of the stored trades exist
        return CompletableFutureUtils.allOf(protocolCreations).thenApply(nil -> true);
    }

    public CompletableFuture<Boolean> shutdown() {
        log.info("shutdown");
        if (authorizedAlertDataSetPin != null) {
            authorizedAlertDataSetPin.unbind();
            authorizedAlertDataSetPin = null;
        }
        if (numDaysAfterRedactingTradeDataPin != null) {
            numDaysAfterRedactingTradeDataPin.unbind();
            numDaysAfterRedactingTradeDataPin = null;
        }
        if (chatChannelsPin != null) {
            chatChannelsPin.unbind();
            chatChannelsPin = null;
        }
        if (numDaysAfterRedactingTradeDataScheduler != null) {
            numDaysAfterRedactingTradeDataScheduler.stop();
            numDaysAfterRedactingTradeDataScheduler = null;
        }

        networkService.removeConfidentialMessageListener(this);

        // The tasks which were submitted before still run, so their changes reach the store before it gets persisted
        if (!tradeTaskExecutor.shutdown(TRADE_TASKS_SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            log.warn("Not all trade tasks completed within {} seconds at shutdown", TRADE_TASKS_SHUTDOWN_TIMEOUT_SEC);
        }
        if (tradeTaskThreads instanceof ExecutorService executorService) {
            ExecutorFactory.shutdownAndAwaitTermination(executorService);
        }
        return CompletableFuture.completedFuture(true);
    }


    /* --------------------------------------------------------------------- */
    // ConfidentialMessageService.Listener
    /* --------------------------------------------------------------------- */

    // A message runs as task of the trade whose ID it carries, also if we have no trade with that ID yet. So a message
    // which arrives while the take offer request creates the trade waits for the request, and the request processes
    // it as pending message (docs/specifications/trade/pending-trade-messages.md).
    @Override
    public void onMessage(EnvelopePayloadMessage envelopePayloadMessage) {
        if (envelopePayloadMessage instanceof BisqEasyTradeMessage bisqEasyTradeMessage) {
            runTradeTask(bisqEasyTradeMessage.getTradeId(), () -> processTradeMessage(bisqEasyTradeMessage));
        }
    }


    /* --------------------------------------------------------------------- */
    // Message event
    /* --------------------------------------------------------------------- */

    private void processTradeMessage(BisqEasyTradeMessage message) {
        try {
            verifyTradingNotOnHalt();
            verifyMinVersionForTrading();
        } catch (TradeRestrictedException e) {
            log.warn("Ignoring inbound trade message as trading is currently not allowed: {}", e.getMessage());
            return;
        }

        if (bannedUserService.isUserProfileBanned(message.getSender())) {
            log.warn("Message ignored as sender is banned");
            return;
        }

        if (message instanceof BisqEasyTakeOfferRequest takeOfferRequest) {
            handleBisqEasyTakeOfferMessage(takeOfferRequest);
        } else {
            handleBisqEasyTradeMessage(message);
        }
    }

    // The pending messages of the new trade arrived before the request, or while it waited for its task. We process them
    // after the request, so the protocol has the state in which the maker answered the request.
    private void handleBisqEasyTakeOfferMessage(BisqEasyTakeOfferRequest message) {
        makerCreatesProtocol(message).ifPresent(protocol -> {
            handleBisqEasyTradeMessage(message, protocol);
            processPendingMessages(protocol);
        });
    }

    private void handleBisqEasyTradeMessage(BisqEasyTradeMessage message) {
        String tradeId = message.getTradeId();
        findProtocol(tradeId).ifPresentOrElse(protocol -> {
                    if (isMessageOfTradePeer(message, protocol)) {
                        handleBisqEasyTradeMessage(message, protocol);
                    }
                },
                () -> {
                    // Trade messages carry private data of the traders, so the log names only the message type.
                    log.info("Protocol with tradeId {} not found. We add the message of type {} to pendingMessages " +
                                    "for processing when the protocol of the trade is created.",
                            StringUtils.sanitizeForLog(tradeId), message.getClass().getSimpleName());
                    pendingMessages.add(message);
                });
    }

    // Runs right after the protocol of the trade was created, before a later message of the trade is processed. A maker
    // runs it after the take offer request.
    private void processPendingMessages(BisqEasyProtocol protocol) {
        String tradeId = protocol.getModel().getId();
        pendingMessages.getMessagesOfTrade(tradeId).forEach(message -> {
            pendingMessages.remove(message);
            if (isMessageOfTradePeer(message, protocol)) {
                log.info("We process the pending message of type {} with tradeId {}.",
                        message.getClass().getSimpleName(), StringUtils.sanitizeForLog(tradeId));
                handleBisqEasyTradeMessage(message, protocol);
            }
        });
    }

    // The state machine turns an exception of a message handler into a failure of the trade (Fsm.handle), and the
    // handlers throw for a message which is not from the trade peer. So such a message must not reach the protocol:
    // anybody who knows a trade ID could end the trade otherwise. The check runs when a message is processed, also for
    // a message which was queued before the protocol existed. The take offer request creates the trade, so
    // makerCreatesProtocol checks it against the trade before the trade is stored.
    private static boolean isMessageOfTradePeer(BisqEasyTradeMessage message, BisqEasyProtocol protocol) {
        boolean isMessageOfTradePeer = TradeMessageHandler.isMessageOfTradePeer(message, protocol.getModel());
        if (!isMessageOfTradePeer) {
            log.warn("We drop a trade message of type {} which the trade peer did not send.",
                    message.getClass().getSimpleName());
        }
        return isMessageOfTradePeer;
    }

    private void handleBisqEasyTradeMessage(BisqEasyTradeMessage message, BisqEasyProtocol protocol) {
        protocol.handle(message);
    }


    /* --------------------------------------------------------------------- */
    // Events
    /* --------------------------------------------------------------------- */

    // The checks report their failure by an exception of this call. The trade and its protocol get created in a task of
    // the new trade, whose result the returned future carries.
    public CompletableFuture<BisqEasyProtocol> takerCreatesProtocol(Identity takerIdentity,
                                                                    BisqEasyOffer bisqEasyOffer,
                                                                    Monetary baseSideAmount,
                                                                    Monetary quoteSideAmount,
                                                                    BitcoinPaymentMethodSpec bitcoinPaymentMethodSpec,
                                                                    FiatPaymentMethodSpec fiatPaymentMethodSpec,
                                                                    Optional<UserProfile> mediator,
                                                                    long marketPrice) {
        verifyTakeOffer(bisqEasyOffer, baseSideAmount, quoteSideAmount);
        BisqEasyTrade bisqEasyTrade = createTakersTrade(takerIdentity,
                bisqEasyOffer,
                baseSideAmount,
                quoteSideAmount,
                bitcoinPaymentMethodSpec,
                fiatPaymentMethodSpec,
                mediator,
                marketPrice);
        return tradeTaskExecutor.supply(bisqEasyTrade.getId(), () -> takerAddsTradeAndCreatesProtocol(bisqEasyTrade));
    }

    private void verifyTakeOffer(BisqEasyOffer bisqEasyOffer, Monetary baseSideAmount, Monetary quoteSideAmount) {
        verifyTradingNotOnHalt();
        verifyMinVersionForTrading();
        verifyMakerMeetsMinVersionForTrading(bisqEasyOffer);
        validateOfferAmount(bisqEasyOffer, baseSideAmount.getValue(), quoteSideAmount.getValue());
        // As buyer the taker sends the fiat first, so the seller's reputation is its only security. The node of the
        // buyer checks it for every client, also for the REST API and the mobile node, which do not use the take offer
        // views of the desktop (see docs/specifications/trade/bisq-easy-seller-reputation-limit.md).
        if (bisqEasyOffer.getTakersDirection().isBuy()) {
            BisqEasySellerReputationValidator.validateSellersReputationForTradeAmount(
                    serviceProvider.getBondedRolesService().getMarketPriceService(),
                    serviceProvider.getUserService().getReputationService(),
                    bisqEasyOffer.getMarket(),
                    quoteSideAmount,
                    bisqEasyOffer.getMakersUserProfileId());
        }
    }

    private static BisqEasyTrade createTakersTrade(Identity takerIdentity,
                                                   BisqEasyOffer bisqEasyOffer,
                                                   Monetary baseSideAmount,
                                                   Monetary quoteSideAmount,
                                                   BitcoinPaymentMethodSpec bitcoinPaymentMethodSpec,
                                                   FiatPaymentMethodSpec fiatPaymentMethodSpec,
                                                   Optional<UserProfile> mediator,
                                                   long marketPrice) {
        NetworkId takerNetworkId = takerIdentity.getNetworkId();
        BisqEasyContract contract = new BisqEasyContract(
                System.currentTimeMillis(),
                bisqEasyOffer,
                takerNetworkId,
                baseSideAmount.getValue(),
                quoteSideAmount.getValue(),
                bitcoinPaymentMethodSpec,
                fiatPaymentMethodSpec,
                mediator,
                // The contract keeps the price spec of the offer (docs/specifications/trade/bisq-easy-contract-price.md)
                bisqEasyOffer.getPriceSpec(),
                marketPrice);
        boolean isBuyer = bisqEasyOffer.getTakersDirection().isBuy();
        NetworkId makerNetworkId = contract.getMaker().getNetworkId();
        return new BisqEasyTrade(contract, isBuyer, true, takerIdentity, bisqEasyOffer, takerNetworkId, makerNetworkId);
    }

    private BisqEasyProtocol takerAddsTradeAndCreatesProtocol(BisqEasyTrade bisqEasyTrade) {
        checkArgument(findProtocol(bisqEasyTrade.getId()).isEmpty(),
                "We received the BisqEasyTakeOfferRequest for an already existing protocol");

        checkArgument(!tradeExists(bisqEasyTrade.getId()), "A trade with that ID exists already");
        persistableStore.addTrade(bisqEasyTrade);
        persist();

        maybeAddPeerToContactList(bisqEasyTrade.getMaker().getNetworkId().getId(),
                bisqEasyTrade.getTaker().getNetworkId().getId());

        BisqEasyProtocol protocol = createAndAddTradeProtocol(bisqEasyTrade);
        processPendingMessages(protocol);
        return protocol;
    }

    public CompletableFuture<Void> takeOffer(BisqEasyTrade trade) {
        return handleBisqEasyTradeEvent(trade, new BisqEasyTakeOfferEvent());
    }

    public CompletableFuture<Void> sellerSendsPaymentAccount(BisqEasyTrade trade, String paymentAccountData) {
        return applyUserTradeAction(trade, new BisqEasyAccountDataEvent(paymentAccountData));
    }

    public CompletableFuture<Void> buyerConfirmFiatSent(BisqEasyTrade trade) {
        return applyUserTradeAction(trade, new BisqEasyConfirmFiatSentEvent());
    }

    // The seller's node fails the trade when the data requests another amount than the trade amount
    // (docs/specifications/trade/bisq-easy-bitcoin-payment-data.md)
    public CompletableFuture<Void> buyerSendBitcoinPaymentData(BisqEasyTrade trade, String buyersBitcoinPaymentData) {
        long tradeAmount = trade.getContract().getBaseSideAmount();
        RequestedAmountValidation.checkRequestsOnlyAmount(buyersBitcoinPaymentData, tradeAmount);
        return applyUserTradeAction(trade, new BisqEasySendBtcAddressEvent(buyersBitcoinPaymentData));
    }

    public CompletableFuture<Void> sellerConfirmFiatReceipt(BisqEasyTrade trade) {
        return applyUserTradeAction(trade, new BisqEasyConfirmFiatReceiptEvent());
    }

    public CompletableFuture<Void> sellerConfirmBtcSent(BisqEasyTrade trade, Optional<String> paymentProof) {
        return applyUserTradeAction(trade, new BisqEasyConfirmBtcSentEvent(paymentProof));
    }

    public CompletableFuture<Void> btcConfirmed(BisqEasyTrade trade) {
        return handleBisqEasyTradeEvent(trade, new BisqEasyBtcConfirmedEvent());
    }

    public CompletableFuture<Void> rejectTrade(BisqEasyTrade trade) {
        return handleBisqEasyTradeEvent(trade, new BisqEasyRejectTradeEvent());
    }

    public CompletableFuture<Void> cancelTrade(BisqEasyTrade trade) {
        return handleBisqEasyTradeEvent(trade, new BisqEasyCancelTradeEvent());
    }

    // A user action whose data the caller also sends to the peer as trade log message, for example the payment account
    // data. The protocol does not throw when it does not apply an event: Fsm ignores an event in a final state, and
    // BisqEasyProtocol turns a failure into the FAILED state. So we report it here: the returned future fails with
    // TradeActionNotAppliedException, and the caller sends the trade log message only if it completes normally.
    // Otherwise a peer which cancelled or failed the trade would still get the data. A trade restriction is reported
    // by an exception of this call (docs/specifications/trade/trade-log-of-user-actions.md).
    private CompletableFuture<Void> applyUserTradeAction(BisqEasyTrade trade, BisqEasyTradeEvent event) {
        verifyTradingNotOnHalt();
        verifyMinVersionForTrading();
        String tradeId = trade.getId();
        return tradeTaskExecutor.submit(tradeId, () -> {
            BisqEasyProtocol protocol = findProtocol(tradeId).orElseThrow(() ->
                    new TradeActionNotAppliedException("The trade has no protocol anymore. tradeId=" + tradeId));
            if (trade.getTradeState().isFinalState()) {
                throw new TradeActionNotAppliedException("The trade is in the final state " + trade.getTradeState());
            }
            protocol.handle(event);
            BisqEasyTradeState state = trade.getTradeState();
            if (state == BisqEasyTradeState.FAILED || state == BisqEasyTradeState.FAILED_AT_PEER) {
                throw new TradeActionNotAppliedException("The trade failed at handling " + event.getClass().getSimpleName());
            }
        });
    }

    private CompletableFuture<Void> handleBisqEasyTradeEvent(BisqEasyTrade trade, BisqEasyTradeEvent event) {
        verifyTradingNotOnHalt();
        verifyMinVersionForTrading();
        String tradeId = trade.getId();
        return submitTradeTask(tradeId, () -> findProtocol(tradeId).ifPresentOrElse(protocol -> protocol.handle(event),
                () -> log.info("Protocol with tradeId {} not found. This is expected if the trade have been closed already", tradeId)));
    }


    /* --------------------------------------------------------------------- */
    // Misc API
    /* --------------------------------------------------------------------- */

    public Optional<BisqEasyProtocol> findProtocol(String id) {
        return Optional.ofNullable(tradeProtocolById.get(id));
    }

    public Optional<BisqEasyTrade> findTrade(String tradeId) {
        return persistableStore.findTrade(tradeId);
    }

    public boolean tradeExists(String tradeId) {
        return persistableStore.tradeExists(tradeId);
    }

    public boolean wasOfferAlreadyTaken(BisqEasyOffer bisqEasyOffer, NetworkId takerNetworkId) {
        return getTrades().stream().anyMatch(trade ->
                trade.getOffer().getId().equals(bisqEasyOffer.getId()) &&
                        trade.getTaker().getNetworkId().getId().equals(takerNetworkId.getId())
        );
    }

    public ReadOnlyObservableSet<BisqEasyTrade> getTrades() {
        return persistableStore.getTrades();
    }

    public ReadOnlyObservableSet<BisqEasyTrade> getAllTrades() {
        return persistableStore.getAllTrades();
    }

    public ReadOnlyObservableSet<BisqEasyClosedTrade> getClosedTrades() {
        return persistableStore.getClosedTrades();
    }

    public void closeTrade(BisqEasyTrade trade, UserProfile myUserProfile, UserProfile peerUserProfile) {
        persistableStore.getTrades().remove(trade);
        BisqEasyClosedTrade bisqEasyClosedTrade = new BisqEasyClosedTrade(trade, myUserProfile, peerUserProfile);
        persistableStore.getClosedTrades().add(bisqEasyClosedTrade);

        tradeProtocolById.remove(trade.getId());
        persist();
    }

    public void deleteTrade(BisqEasyTrade trade) {
        Set<BisqEasyClosedTrade> closedTrades = persistableStore.getClosedTrades();
        Optional<BisqEasyClosedTrade> closedTrade = closedTrades.stream()
                .filter(ct -> ct.trade().getId().equals(trade.getId()))
                .findFirst();
        if (closedTrade.isPresent()) {
            closedTrades.remove(closedTrade.get());
            persist();
        } else {
            log.warn("Could not delete trade {}", trade.getId());
        }
    }


    /* --------------------------------------------------------------------- */
    // TradeProtocol factory
    /* --------------------------------------------------------------------- */

    // We verify the request before we create the trade (BisqEasyTakeOfferRequestValidator) and answer a rejection with
    // an error report to the taker, so a rejected request leaves no trade and no contact. A request which cannot create
    // a trade of ours is dropped here without an answer: the sender controls the conditions, so they must not throw.
    private Optional<BisqEasyProtocol> makerCreatesProtocol(BisqEasyTakeOfferRequest message) {
        BisqEasyContract contract = message.getBisqEasyContract();
        BisqEasyOffer offer = contract.getOffer();
        NetworkId sender = message.getSender();
        NetworkId receiver = message.getReceiver();
        // A party is identified by its key. The addresses of our network ID can have changed since we published the
        // offer, for example when we added a transport. The layer for confidential messages has bound the key of the
        // receiver to the key with which we decrypted the request, so the request reached the key of the offer's maker.
        String makerId = offer.getMakerNetworkId().getId();
        if (!receiver.getId().equals(makerId)) {
            logDroppedTakeOfferRequest(message, "it is not addressed to the maker of its offer", message.getTradeId());
            return Optional.empty();
        }
        Optional<Identity> myIdentity = identityService.findAnyIdentityById(makerId);
        if (myIdentity.isEmpty()) {
            logDroppedTakeOfferRequest(message, "its offer is not one of our offers", message.getTradeId());
            return Optional.empty();
        }

        boolean isBuyer = offer.getMakersDirection().isBuy();
        BisqEasyTrade bisqEasyTrade = new BisqEasyTrade(contract, isBuyer, false, myIdentity.get(), offer, sender, receiver);
        String tradeId = bisqEasyTrade.getId();
        if (!TradeMessageHandler.isMessageOfTradePeer(message, bisqEasyTrade)) {
            logDroppedTakeOfferRequest(message, "its trade ID is not the ID of the trade which it would create",
                    message.getTradeId());
            return Optional.empty();
        }
        // The request runs as task of its trade ID, which is the ID of the trade (checked above). So a copy of the
        // request runs after it, and finds the trade.
        if (findProtocol(tradeId).isPresent() || tradeExists(tradeId)) {
            logDroppedTakeOfferRequest(message, "a trade with its trade ID exists already", tradeId);
            return Optional.empty();
        }
        // Only after the check above: a rejection of a copy of a request which we have handled would fail the
        // trade at the taker
        try {
            BisqEasyTakeOfferRequestValidator.validate(serviceProvider, myIdentity.get(), message);
        } catch (RuntimeException e) {
            log.warn("We reject the take offer request. tradeId={}", tradeId, e);
            reportTakeOfferRejection(bisqEasyTrade, e);
            return Optional.empty();
        }
        persistableStore.addTrade(bisqEasyTrade);
        persist();

        maybeAddPeerToContactList(sender.getId(), myIdentity.get().getId());

        return Optional.of(createAndAddTradeProtocol(bisqEasyTrade));
    }

    // A rejected take offer request creates no trade, so no state machine reports the rejection to the taker. We
    // report it with the data which BisqEasyFsmErrorEventHandler reports for a failed trade. Like the trade messages,
    // the report names us by the network ID to which the taker addressed the request, not by the current addresses of
    // our identity (docs/specifications/user/identity-by-key.md, rule 3).
    private void reportTakeOfferRejection(BisqEasyTrade bisqEasyTrade, RuntimeException exception) {
        TradeProtocolFailure tradeProtocolFailure = exception instanceof TradeProtocolException tradeProtocolException ?
                tradeProtocolException.getTradeProtocolFailure() :
                TradeProtocolFailure.UNKNOWN;
        Identity myIdentity = bisqEasyTrade.getMyIdentity();
        NetworkId takerNetworkId = bisqEasyTrade.getPeer().getNetworkId();
        BisqEasyReportErrorMessage report = new BisqEasyReportErrorMessage(StringUtils.createUid(),
                bisqEasyTrade.getId(),
                BisqEasyProtocol.VERSION,
                bisqEasyTrade.getMyself().getNetworkId(),
                takerNetworkId,
                StringUtils.truncate(ExceptionUtil.getRootCauseMessage(exception), MAX_LENGTH_ERROR_MESSAGE),
                StringUtils.truncate(ExceptionUtil.getSafeStackTraceAsString(exception), MAX_LENGTH_STACKTRACE),
                tradeProtocolFailure);
        networkService.confidentialSend(report, takerNetworkId, myIdentity.getNetworkIdWithKeyPair());
    }

    // The request is data of its sender, so the log names only the message type and the sanitized trade ID.
    private static void logDroppedTakeOfferRequest(BisqEasyTakeOfferRequest message, String reason, String tradeId) {
        log.warn("We drop a {} because {}. tradeId={}",
                message.getClass().getSimpleName(), reason, StringUtils.sanitizeForLog(tradeId));
    }

    private BisqEasyProtocol createAndAddTradeProtocol(BisqEasyTrade trade) {
        String id = trade.getId();
        BisqEasyProtocol tradeProtocol;
        boolean isBuyer = trade.isBuyer();
        if (trade.isTaker()) {
            if (isBuyer) {
                tradeProtocol = new BisqEasyBuyerAsTakerProtocol(serviceProvider, trade);
            } else {
                tradeProtocol = new BisqEasySellerAsTakerProtocol(serviceProvider, trade);
            }
        } else {
            if (isBuyer) {
                tradeProtocol = new BisqEasyBuyerAsMakerProtocol(serviceProvider, trade);
            } else {
                tradeProtocol = new BisqEasySellerAsMakerProtocol(serviceProvider, trade);
            }
        }
        trade.setProtocolVersion(tradeProtocol.getVersion());
        tradeProtocolById.put(id, tradeProtocol);

        bisqEasyOpenTradeChannelService.findChannelByTradeId(id)
                .ifPresent(channel -> failTradeOnChatChannelMismatch(trade, channel));
        // The taker's first chat message, which creates our trade chat channel, can be pending if it arrived first
        bisqEasyOpenTradeChannelService.processPendingMessages(id);
        return tradeProtocol;
    }

    // A trade may only progress while its chat channel data matches the trade and its signed contract. The event
    // handlers enforce that at the user's actions, here we fail a mismatching trade as soon as both exist.
    // Failing a mismatching trade must not wait for the halt or version checks of handleBisqEasyTradeEvent.
    private void failTradeOnChatChannelMismatch(BisqEasyTrade trade, BisqEasyOpenTradeChannel channel) {
        if (trade.getState().isFinalState()) {
            return;
        }
        BisqEasyTradeChatChannelValidator.findMismatch(channel, trade).ifPresent(mismatch -> {
            log.warn("Trade chat channel does not match the trade or its contract. We fail the trade. {}", mismatch);
            findProtocol(trade.getId()).ifPresent(protocol -> protocol.handle(new BisqEasyChatChannelMismatchEvent(mismatch)));
        });
    }

    private void updateTradeRestrictions() {
        haltTrading = AuthorizedAlertDataUtils.isTradingHalted(
                alertService.getAuthorizedAlertDataSet().stream(), appType);
        minRequiredVersionForTrading = AuthorizedAlertDataUtils.findMinRequiredVersionForTrading(
                alertService.getAuthorizedAlertDataSet().stream(), appType);
    }

    private void verifyTradingNotOnHalt() {
        if (haltTrading) {
            throw TradeRestrictedException.haltTrading();
        }
    }

    private void verifyMinVersionForTrading() {
        Optional<String> minRequiredVersion = minRequiredVersionForTrading;
        if (minRequiredVersion.isPresent()
                && !ApplicationVersion.getVersion().aboveOrEqual(new Version(minRequiredVersion.get()))) {
            throw TradeRestrictedException.minVersionRequired(minRequiredVersion.get());
        }
    }

    // A node of a version which the alert restricts acknowledges a take offer request and drops it, so the take would
    // hang without an error. The node of the taker refuses the take instead. The version of the maker is the higher of
    // the version of its user profile, which the maker signed when it published the profile, and the version which
    // created the offer. Before the cutoff date of signature V1, a relay can change the version of an offer without
    // signature V2. A maker without a readable version is refused (see docs/specifications/trade/trade-restrictions.md).
    private void verifyMakerMeetsMinVersionForTrading(BisqEasyOffer bisqEasyOffer) {
        Optional<String> minRequiredVersion = minRequiredVersionForTrading;
        if (minRequiredVersion.isPresent()
                && !isMakerVersionAtLeast(bisqEasyOffer, new Version(minRequiredVersion.get()))) {
            throw TradeRestrictedException.makerVersionBelowMinRequired(minRequiredVersion.get());
        }
    }

    private boolean isMakerVersionAtLeast(BisqEasyOffer bisqEasyOffer, Version requiredVersion) {
        Stream<String> versionOfProfile = userProfileService.findUserProfile(bisqEasyOffer.getMakersUserProfileId())
                .map(UserProfile::getApplicationVersion)
                .stream();
        return Stream.concat(versionOfProfile, Stream.of(bisqEasyOffer.getAppVersion()))
                .anyMatch(version -> isAtLeast(version, requiredVersion));
    }

    private static boolean isAtLeast(String version, Version requiredVersion) {
        try {
            return new Version(version).aboveOrEqual(requiredVersion);
        } catch (RuntimeException e) {
            // An empty or malformed version, or a part which does not fit into an int
            return false;
        }
    }


    /* --------------------------------------------------------------------- */
    // Redact sensible data
    /* --------------------------------------------------------------------- */

    private void maybeRedactDataOfCompletedTrades() {
        int numDays = settingsService.getNumDaysAfterRedactingTradeData().get();
        long redactDate = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(numDays);
        // Trades which ended up with a failure or got stuck will never get the completed date set.
        // We use a more constrained duration of 45-90 days.
        int numDaysForNotCompletedTrades = Math.max(45, Math.min(90, numDays));
        long redactDateForNotCompletedTrades = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(numDaysForNotCompletedTrades);
        String redactedMarker = Res.get("data.redacted");
        long numChanges = getAllTrades().stream()
                .filter(trade -> {
                    if (trade.getPaymentAccountData().map(data -> StringUtils.isEmpty(data) || data.equals(redactedMarker)).orElse(true)) {
                        return false;
                    }
                    boolean doRedaction = trade.getTradeCompletedDate().map(date -> date < redactDate)
                            .orElseGet(() -> trade.getContract().getTakeOfferDate() < redactDateForNotCompletedTrades);
                    if (doRedaction) {

                        trade.setPaymentAccountData(Optional.of(redactedMarker));
                    }
                    return doRedaction;
                })
                .count();
        if (numChanges > 0) {
            persist();
        }
    }


    /* --------------------------------------------------------------------- */
    // Trade tasks
    /* --------------------------------------------------------------------- */

    // Completes when the tasks of the trade which were submitted before have run
    @VisibleForTesting
    CompletableFuture<Void> afterTasksOfTrade(String tradeId) {
        return tradeTaskExecutor.submit(tradeId, () -> {
        });
    }

    // For a task for which no caller waits. Without the log, its failure would not be visible.
    private void runTradeTask(String tradeId, Runnable task) {
        submitTradeTask(tradeId, task);
    }

    // For a task whose caller may ignore the result. A failure is logged, and the returned future fails as well.
    private CompletableFuture<Void> submitTradeTask(String tradeId, Runnable task) {
        return tradeTaskExecutor.submit(tradeId, task).whenComplete((nil, throwable) -> {
            if (throwable instanceof RejectedExecutionException) {
                log.info("We did not run a task of trade {} because the trade service is shut down",
                        StringUtils.sanitizeForLog(tradeId));
            } else if (throwable != null) {
                log.error("A task of trade {} failed", StringUtils.sanitizeForLog(tradeId), throwable);
            }
        });
    }


    /* --------------------------------------------------------------------- */
    // Misc
    /* --------------------------------------------------------------------- */

    private void maybeAddPeerToContactList(String peersProfileId, String myProfileId) {
        if (settingsService.getDoAutoAddToContactList()) {
            Optional<UserProfile> peersProfile = userProfileService.findUserProfile(peersProfileId);
            Optional<UserProfile> myProfile = userProfileService.findUserProfile(myProfileId);
            if (peersProfile.isPresent() && myProfile.isPresent()) {
                contactListService.addContactListEntry(peersProfile.get(), myProfile.get(), ContactReason.BISQ_EASY_TRADE);
            }
        }
    }
}
