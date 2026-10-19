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
import bisq.common.timer.Scheduler;
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
import bisq.persistence.PersistenceClient;
import bisq.persistence.SnapshotPersistence;
import bisq.settings.SettingsService;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeActionNotAppliedException;
import bisq.trade.TradeRestrictedException;
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
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static bisq.trade.bisq_easy.protocol.messages.BisqEasyReportErrorMessage.MAX_LENGTH_ERROR_MESSAGE;
import static bisq.trade.bisq_easy.protocol.messages.BisqEasyReportErrorMessage.MAX_LENGTH_STACKTRACE;
import static bisq.trade.bisq_easy.validation.BisqEasyOfferAmountValidator.validateOfferAmount;
import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class BisqEasyTradeService implements Service, ConfidentialMessageService.Listener {
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
    private final Map<String, BisqEasyProtocol> tradeProtocolById = new HashMap<>();
    private final ContactListService contactListService;
    private final UserProfileService userProfileService;
    private final BisqEasyOpenTradeChannelService bisqEasyOpenTradeChannelService;
    private boolean haltTrading;
    private Optional<String> minRequiredVersionForTrading = Optional.empty();
    @Nullable
    private Pin authorizedAlertDataSetPin, numDaysAfterRedactingTradeDataPin, chatChannelsPin;
    @Nullable
    private Scheduler numDaysAfterRedactingTradeDataScheduler;
    private final PendingTradeMessages<BisqEasyTradeMessage> pendingMessages = persistableStore.getPendingMessages();
    private final BisqEasyTradeExecutor executor = new BisqEasyTradeExecutor(1024);
    private final SnapshotPersistence snapshotPersistence;
    private final Map<String, BisqEasyTradeReadModel> readModels = new HashMap<>();
    private volatile Map<String, BisqEasyTradeReadModel> publishedTrades = Map.of();
    private volatile Set<String> publishedTradeIds = Set.of();
    private volatile Map<String, Participants> participants = Map.of();
    private final BisqEasyPublishedSet<BisqEasyTradeReadModel> trades = new BisqEasyPublishedSet<>();
    private final BisqEasyPublishedSet<BisqEasyTradeReadModel> allTrades = new BisqEasyPublishedSet<>();
    private final BisqEasyPublishedSet<BisqEasyClosedTradeReadModel> closedTrades = new BisqEasyPublishedSet<>();
    private final Map<String, Pin> statePins = new HashMap<>();
    private final List<Publication> publications = new ArrayList<>();
    private final AtomicBoolean restrictionsRefresh = new AtomicBoolean();
    private final AtomicBoolean channelsRefresh = new AtomicBoolean();
    private final AtomicBoolean redactionRefresh = new AtomicBoolean();
    private boolean dirty;
    @Nullable
    private RuntimeException captureFailure;
    @Nullable
    private CompletableFuture<Void> quiescence;
    @Nullable
    private CompletableFuture<Boolean> initialization;
    @Nullable
    private CompletableFuture<Void> terminalFlush;

    public BisqEasyTradeService(ServiceProvider serviceProvider, AppType appType) {
        this.serviceProvider = serviceProvider;
        networkService = serviceProvider.getNetworkService();
        identityService = serviceProvider.getIdentityService();
        settingsService = serviceProvider.getSettingsService();
        bannedUserService = serviceProvider.getUserService().getBannedUserService();
        alertService = serviceProvider.getBondedRolesService().getAlertService();
        contactListService = serviceProvider.getUserService().getContactListService();
        userProfileService = serviceProvider.getUserService().getUserProfileService();
        bisqEasyOpenTradeChannelService = serviceProvider.getChatService().getBisqEasyOpenTradeChannelService();

        persistence = serviceProvider.getPersistenceService().getOrCreatePersistence(new TradePersistenceClient(), DbSubDirectory.PRIVATE, persistableStore);
        this.appType = appType;
        snapshotPersistence = new SnapshotPersistence(persistence::persistSnapshotAsync, executor.completionExecutor());
    }


    /* --------------------------------------------------------------------- */
    // Service
    /* --------------------------------------------------------------------- */

    public synchronized CompletableFuture<Boolean> initialize() {
        if (quiescence != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Trade service is stopping"));
        }
        if (initialization != null) {
            return initialization;
        }
        initialization = submitCommand(() -> {
            initializeOwned();
            return true;
        }).thenComposeAsync(result -> {
            // Subscription can replay many messages synchronously. It must not wait for admission on the owner.
            networkService.subscribeToConfidentialMessages(this);
            return executor.complete(() -> runCommand(() -> true));
        });
        return initialization;
    }

    private void initializeOwned() {
        bisqEasyOpenTradeChannelService.setIsTradeWithPeer((tradeId, senderId, receiverId) -> {
            Participants tradeParticipants = participants.get(tradeId);
            return tradeParticipants != null && tradeParticipants.peerId().equals(senderId) &&
                    tradeParticipants.myId().equals(receiverId);
        });
        persistableStore.getTrades().forEach(this::createAndAddTradeProtocol);
        chatChannelsPin = bisqEasyOpenTradeChannelService.getChannels().addObserver(new CollectionObserver<>() {
            @Override
            public void onAdded(BisqEasyOpenTradeChannel channel) {
                enqueueRefresh(channelsRefresh, () -> persistableStore.getTrades().forEach(trade ->
                        bisqEasyOpenTradeChannelService.findChannelByTradeId(trade.getId())
                                .ifPresent(current -> failTradeOnChatChannelMismatch(trade, current))));
            }

            @Override
            public void onRemoved(Object element) {
            }

            @Override
            public void onCleared() {
            }
        });
        updateTradeRestrictions();
        authorizedAlertDataSetPin = alertService.getAuthorizedAlertDataSet()
                .addObserver(() -> enqueueRefresh(restrictionsRefresh, this::updateTradeRestrictions));
        pendingMessages.getMessagesInArrivalOrder().forEach(this::processMessage);
        numDaysAfterRedactingTradeDataScheduler = Scheduler.run(() -> enqueueRefresh(redactionRefresh, this::maybeRedactDataOfCompletedTrades))
                .host(this)
                .periodically(1, TimeUnit.HOURS);
        numDaysAfterRedactingTradeDataPin = settingsService.getNumDaysAfterRedactingTradeData()
                .addObserver(numDays -> enqueueRefresh(redactionRefresh, this::maybeRedactDataOfCompletedTrades));
    }

    public synchronized CompletableFuture<Void> quiesce() {
        if (quiescence == null) {
            CompletableFuture<?> ready = initialization == null ? CompletableFuture.completedFuture(null) : initialization;
            quiescence = ready.handle((result, failure) -> null).thenCompose(unused -> executor.complete(() -> {
                detachProducers();
                return null;
            })).thenCompose(unused -> executor.quiesce()).thenCompose(unused -> executor.complete(() -> {
                statePins.values().forEach(Pin::unbind);
                statePins.clear();
                return null;
            }));
        }
        return quiescence;
    }

    public CompletableFuture<Boolean> shutdown() {
        return quiesce().thenCompose(unused -> flush())
                .handle((unused, failure) -> {
                    executor.shutdown();
                    if (failure != null) {
                        log.error("Could not flush Bisq Easy trades at shutdown: {}", failure.getClass().getSimpleName());
                    }
                    return failure == null;
                });
    }

    private void detachProducers() {
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
    }


    /* --------------------------------------------------------------------- */
    // ConfidentialMessageService.Listener
    /* --------------------------------------------------------------------- */

    @Override
    public void onMessage(EnvelopePayloadMessage envelopePayloadMessage) {
        if (envelopePayloadMessage instanceof BisqEasyTradeMessage bisqEasyTradeMessage) {
            executor.receive(() -> runCommand(() -> {
                processMessage(bisqEasyTradeMessage);
                return null;
            })).exceptionally(failure -> {
                log.error("Could not process incoming trade message: {}", failure.getClass().getSimpleName());
                return null;
            });
        }
    }

    private void processMessage(BisqEasyTradeMessage bisqEasyTradeMessage) {
        executor.assertOwnerThread();
        try {
            verifyTradingNotOnHalt();
            verifyMinVersionForTrading();
        } catch (TradeRestrictedException e) {
            log.warn("Ignoring inbound trade message as trading is currently not allowed: {}", e.getMessage());
            return;
        }

        if (bannedUserService.isUserProfileBanned(bisqEasyTradeMessage.getSender())) {
            log.warn("Message ignored as sender is banned");
            return;
        }

        if (bisqEasyTradeMessage instanceof BisqEasyTakeOfferRequest) {
            handleBisqEasyTakeOfferMessage((BisqEasyTakeOfferRequest) bisqEasyTradeMessage);
        } else {
            handleBisqEasyTradeMessage(bisqEasyTradeMessage);
        }
    }

    /* --------------------------------------------------------------------- */
    // Message event
    /* --------------------------------------------------------------------- */

    private void handleBisqEasyTakeOfferMessage(BisqEasyTakeOfferRequest message) {
        makerCreatesProtocol(message).ifPresent(protocol -> handleBisqEasyTradeMessage(message, protocol));
    }

    private void handleBisqEasyTradeMessage(BisqEasyTradeMessage message) {
        String tradeId = message.getTradeId();
        Optional<BisqEasyProtocol> optionalProtocol = findProtocol(tradeId);
        if (optionalProtocol.isEmpty()) {
            log.info("Protocol with tradeId {} not found. We add the message of type {} to pendingMessages " +
                            "for re-processing when the next message arrives.",
                    StringUtils.sanitizeForLog(tradeId), message.getClass().getSimpleName());
            pendingMessages.add(message);
        }
        if (optionalProtocol.isEmpty()) {
            requestPersistence();
            return;
        }
        BisqEasyProtocol protocol = optionalProtocol.orElseThrow();
        if (isMessageOfTradePeer(message, protocol)) {
            handleBisqEasyTradeMessage(message, protocol);
        } else if (pendingMessages.remove(message)) {
            requestPersistence();
        }
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

        if (pendingMessages.remove(message)) {
            log.info("We remove the message of type {} with tradeId {} from pendingMessages.",
                    message.getClass().getSimpleName(), StringUtils.sanitizeForLog(message.getTradeId()));
            requestPersistence();
        }

        if (!pendingMessages.isEmpty()) {
            log.info("We have pendingMessages. We try to re-process them now.");
            // A ban or trading restriction may have changed while a message was pending, including across restart.
            pendingMessages.getMessagesInArrivalOrder().forEach(this::processMessage);
        }
    }


    /* --------------------------------------------------------------------- */
    // Events
    /* --------------------------------------------------------------------- */

    private BisqEasyTrade createTakerTrade(Identity takerIdentity,
                                                 BisqEasyOffer bisqEasyOffer,
                                                 Monetary baseSideAmount,
                                                 Monetary quoteSideAmount,
                                                 BitcoinPaymentMethodSpec bitcoinPaymentMethodSpec,
                                                 FiatPaymentMethodSpec fiatPaymentMethodSpec,
                                                 Optional<UserProfile> mediator,
                                                 long marketPrice) {
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
        BisqEasyTrade bisqEasyTrade = new BisqEasyTrade(contract, isBuyer, true, takerIdentity, bisqEasyOffer, takerNetworkId, makerNetworkId);

        checkArgument(findProtocol(bisqEasyTrade.getId()).isEmpty(),
                "We received the BisqEasyTakeOfferRequest for an already existing protocol");

        checkArgument(!persistableStore.tradeExists(bisqEasyTrade.getId()), "A trade with that ID exists already");
        persistableStore.addTrade(bisqEasyTrade);
        requestPersistence();

        maybeAddPeerToContactList(makerNetworkId.getId(), takerNetworkId.getId());

        createAndAddTradeProtocol(bisqEasyTrade);
        return bisqEasyTrade;
    }

    public CompletableFuture<BisqEasyTradeReadModel> takerCreatesTrade(Identity takerIdentity,
                                                                       BisqEasyOffer offer,
                                                                       Monetary baseSideAmount,
                                                                       Monetary quoteSideAmount,
                                                                       BitcoinPaymentMethodSpec bitcoinPaymentMethodSpec,
                                                                       FiatPaymentMethodSpec fiatPaymentMethodSpec,
                                                                       Optional<UserProfile> mediator,
                                                                       long marketPrice) {
        return submitCommand(() -> {
            BisqEasyTrade trade = createTakerTrade(takerIdentity, offer, baseSideAmount, quoteSideAmount,
                    bitcoinPaymentMethodSpec, fiatPaymentMethodSpec, mediator, marketPrice);
            return readModels.get(trade.getId());
        });
    }

    public CompletableFuture<Void> takeOffer(BisqEasyTradeReadModel trade) {
        return submitEvent(trade, new BisqEasyTakeOfferEvent(), false);
    }

    public CompletableFuture<Void> sellerSendsPaymentAccount(BisqEasyTradeReadModel trade, String paymentAccountData) {
        return submitEvent(trade, new BisqEasyAccountDataEvent(paymentAccountData), true);
    }

    public CompletableFuture<Void> buyerConfirmFiatSent(BisqEasyTradeReadModel trade) {
        return submitEvent(trade, new BisqEasyConfirmFiatSentEvent(), true);
    }

    public CompletableFuture<Void> buyerSendBitcoinPaymentData(BisqEasyTradeReadModel trade, String paymentData) {
        return submitCommand(() -> {
            BisqEasyTrade model = resolveActiveTrade(trade);
            RequestedAmountValidation.checkRequestsOnlyAmount(paymentData, model.getContract().getBaseSideAmount());
            applyUserTradeAction(model, new BisqEasySendBtcAddressEvent(paymentData));
            return null;
        });
    }

    public CompletableFuture<Void> sellerConfirmFiatReceipt(BisqEasyTradeReadModel trade) {
        return submitEvent(trade, new BisqEasyConfirmFiatReceiptEvent(), true);
    }

    public CompletableFuture<Void> sellerConfirmBtcSent(BisqEasyTradeReadModel trade, Optional<String> paymentProof) {
        return submitEvent(trade, new BisqEasyConfirmBtcSentEvent(paymentProof), true);
    }

    public CompletableFuture<Void> btcConfirmed(BisqEasyTradeReadModel trade) {
        return submitEvent(trade, new BisqEasyBtcConfirmedEvent(), false);
    }

    public CompletableFuture<Void> rejectTrade(BisqEasyTradeReadModel trade) {
        return submitEvent(trade, new BisqEasyRejectTradeEvent(), false);
    }

    public CompletableFuture<Void> cancelTrade(BisqEasyTradeReadModel trade) {
        return submitEvent(trade, new BisqEasyCancelTradeEvent(), false);
    }

    private CompletableFuture<Void> submitEvent(BisqEasyTradeReadModel trade, BisqEasyTradeEvent event, boolean userAction) {
        return submitCommand(() -> {
            BisqEasyTrade model = resolveActiveTrade(trade);
            if (userAction) {
                applyUserTradeAction(model, event);
            } else {
                handleBisqEasyTradeEvent(model, event);
            }
            return null;
        });
    }

    private BisqEasyTrade resolveActiveTrade(BisqEasyTradeReadModel trade) {
        if (readModels.get(trade.getId()) != trade) {
            throw new TradeActionNotAppliedException("The trade handle is no longer current");
        }
        return persistableStore.findTrade(trade.getId()).orElseThrow(() ->
                new TradeActionNotAppliedException("The trade is no longer active"));
    }

    // A user action whose data the caller also sends to the peer as trade log message, for example the payment account
    // data. The protocol does not throw when it does not apply an event: Fsm ignores an event in a final state, and
    // BisqEasyProtocol turns a failure into the FAILED state. So we report it here, and the caller sends the trade log
    // message only if this method returns normally. Otherwise a peer which cancelled or failed the trade would still get
    // the data.
    private void applyUserTradeAction(BisqEasyTrade trade, BisqEasyTradeEvent event) {
        verifyTradingNotOnHalt();
        verifyMinVersionForTrading();
        String tradeId = trade.getId();
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
    }

    private void handleBisqEasyTradeEvent(BisqEasyTrade trade, BisqEasyTradeEvent event) {
        verifyTradingNotOnHalt();
        verifyMinVersionForTrading();
        String tradeId = trade.getId();
        findProtocol(tradeId).ifPresentOrElse(protocol -> protocol.handle(event),
                () -> log.info("Protocol with tradeId {} not found. This is expected if the trade have been closed already", tradeId));
    }


    /* --------------------------------------------------------------------- */
    // Misc API
    /* --------------------------------------------------------------------- */

    private Optional<BisqEasyProtocol> findProtocol(String id) {
        executor.assertOwnerThread();
        return Optional.ofNullable(tradeProtocolById.get(id));
    }

    public Optional<BisqEasyTradeReadModel> findTrade(String tradeId) {
        return Optional.ofNullable(publishedTrades.get(tradeId)).filter(trades::contains);
    }

    public boolean tradeExists(String tradeId) {
        return publishedTradeIds.contains(tradeId);
    }

    public boolean wasOfferAlreadyTaken(BisqEasyOffer offer, NetworkId takerNetworkId) {
        return trades.stream().anyMatch(trade -> trade.getOffer().getId().equals(offer.getId()) &&
                trade.getTaker().getNetworkId().getId().equals(takerNetworkId.getId()));
    }

    public ReadOnlyObservableSet<BisqEasyTradeReadModel> getTrades() {
        return trades;
    }

    public ReadOnlyObservableSet<BisqEasyTradeReadModel> getAllTrades() {
        return allTrades;
    }

    public ReadOnlyObservableSet<BisqEasyClosedTradeReadModel> getClosedTrades() {
        return closedTrades;
    }

    public CompletableFuture<Void> closeTrade(BisqEasyTradeReadModel handle, UserProfile myUserProfile, UserProfile peerUserProfile) {
        return submitCommand(() -> {
            BisqEasyTrade trade = resolveActiveTrade(handle);
            persistableStore.getTrades().remove(trade);
            persistableStore.getClosedTrades().add(new BisqEasyClosedTrade(trade, myUserProfile, peerUserProfile));
            tradeProtocolById.remove(trade.getId());
            Optional.ofNullable(statePins.remove(trade.getId())).ifPresent(Pin::unbind);
            requestPersistence();
            return null;
        });
    }

    public CompletableFuture<Void> deleteTrade(BisqEasyTradeReadModel trade) {
        return submitCommand(() -> {
            if (readModels.get(trade.getId()) != trade) {
                throw new TradeActionNotAppliedException("The trade handle is no longer current");
            }
            boolean removed = persistableStore.getClosedTrades().removeIf(closed -> closed.trade().getId().equals(trade.getId()));
            if (removed) {
                requestPersistence();
            }
            return null;
        });
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
        if (findProtocol(tradeId).isPresent() || persistableStore.tradeExists(tradeId)) {
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
        requestPersistence();

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
        BisqEasyProtocol tradeProtocol = createTradeProtocol(trade);
        tradeProtocolById.put(id, tradeProtocol);
        readModels.computeIfAbsent(id, unused -> new BisqEasyTradeReadModel(trade));
        statePins.put(id, trade.stateObservable().addObserver(state ->
                publications.add(new Publication(id, BisqEasyTradeSnapshot.from(trade)))));
        updateParticipants();

        bisqEasyOpenTradeChannelService.findChannelByTradeId(id)
                .ifPresent(channel -> failTradeOnChatChannelMismatch(trade, channel));
        // The taker's first chat message, which creates our trade chat channel, can be pending if it arrived first
        bisqEasyOpenTradeChannelService.processPendingMessages(id);
        return tradeProtocol;
    }

    private BisqEasyProtocol createTradeProtocol(BisqEasyTrade trade) {
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
        long numChanges = persistableStore.getAllTrades().stream()
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
            requestPersistence();
        }
    }


    /* --------------------------------------------------------------------- */
    // Execution ownership and publication
    /* --------------------------------------------------------------------- */

    public void assertTradeThread() {
        executor.assertOwnerThread();
    }

    public void requestPersistence() {
        executor.assertOwnerThread();
        dirty = true;
    }

    private <T> CompletableFuture<T> submitCommand(Supplier<T> command) {
        return executor.submit(() -> runCommand(command));
    }

    private <T> T runCommand(Supplier<T> command) {
        executor.assertOwnerThread();
        try {
            return command.get();
        } finally {
            captureChanges();
            publishChanges();
        }
    }

    private void enqueueRefresh(AtomicBoolean scheduled, Runnable callback) {
        executor.refresh(scheduled, () -> {
            try {
                runCommand(() -> {
                    callback.run();
                    return null;
                });
            } catch (RuntimeException exception) {
                log.error("Trade refresh failed: {}", exception.getClass().getSimpleName());
            }
        });
    }

    private void publishChanges() {
        Set<BisqEasyTrade> current = Set.copyOf(persistableStore.getAllTrades());
        Set<String> ids = current.stream().map(BisqEasyTrade::getId).collect(java.util.stream.Collectors.toSet());
        readModels.keySet().retainAll(ids);
        current.forEach(trade -> readModels.computeIfAbsent(trade.getId(), unused -> new BisqEasyTradeReadModel(trade)));
        publishedTrades = Map.copyOf(readModels);
        publishedTradeIds = Set.copyOf(persistableStore.getTradeIds());
        updateParticipants();
        // Bootstrap subscriptions before replaying this command's transition notifications.
        trades.publish(persistableStore.getTrades().stream().map(trade -> readModels.get(trade.getId())).toList());
        allTrades.publish(readModels.values());
        closedTrades.publish(persistableStore.getClosedTrades().stream().map(closed ->
                new BisqEasyClosedTradeReadModel(readModels.get(closed.trade().getId()), closed.myUserProfile(), closed.peerUserProfile())).toList());
        for (Publication publication : publications) {
            BisqEasyTradeReadModel readModel = readModels.get(publication.tradeId());
            if (readModel != null) {
                readModel.publish(publication.snapshot());
            }
        }
        publications.clear();
        current.forEach(trade -> readModels.get(trade.getId()).publish(BisqEasyTradeSnapshot.from(trade)));
    }

    private void updateParticipants() {
        Map<String, Participants> index = new HashMap<>();
        persistableStore.getTrades().forEach(trade -> index.put(trade.getId(),
                new Participants(trade.getPeer().getNetworkId().getId(), trade.getMyself().getNetworkId().getId())));
        participants = Map.copyOf(index);
    }

    private void captureChanges() {
        if (!dirty) {
            return;
        }
        try {
            snapshotPersistence.request(persistableStore.captureSnapshot());
            dirty = false;
            captureFailure = null;
        } catch (RuntimeException exception) {
            captureFailure = exception;
            // Payloads and exception messages can include private trade data.
            log.error("Could not capture a complete trade snapshot: {}", exception.getClass().getSimpleName());
        }
    }

    public synchronized CompletableFuture<Void> flush() {
        if (quiescence != null) {
            if (terminalFlush == null) {
                terminalFlush = quiescence.thenCompose(unused ->
                        executor.complete(this::flushOwned).thenCompose(result -> result));
            }
            return terminalFlush;
        }
        return executor.complete(this::flushOwned).thenCompose(result -> result);
    }

    private CompletableFuture<Void> flushOwned() {
        captureChanges();
        RuntimeException failure = captureFailure;
        // Even a failed capture must wait for already accepted writes before the completion executor can stop.
        return snapshotPersistence.flushLatest().thenRun(() -> {
            if (failure != null) {
                throw failure;
            }
        });
    }

    public synchronized CompletableFuture<Boolean> persist() {
        if (quiescence != null) {
            return flush().handle((unused, failure) -> failure == null);
        }
        return executor.submit(() -> {
            dirty = true;
            return flushOwned();
        }).thenCompose(result -> result).handle((unused, failure) -> failure == null);
    }

    private synchronized Optional<BisqEasyTradeStore> readPersisted() {
        if (initialization != null || executor.isOwnerThread()) {
            throw new IllegalStateException("Trade restoration must finish before initialization");
        }
        Optional<BisqEasyTradeStore> restored = persistence.read();
        restored.ifPresent(store -> executor.submit(() -> {
            persistableStore.applyPersisted(store);
            return null;
        }).join());
        return restored;
    }

    @VisibleForTesting
    BisqEasyTradeStore getPersistableStore() {
        return persistableStore;
    }

    @VisibleForTesting
    Persistence<BisqEasyTradeStore> getPersistence() {
        return persistence;
    }

    private final class TradePersistenceClient implements PersistenceClient<BisqEasyTradeStore> {
        @Override
        public Persistence<BisqEasyTradeStore> getPersistence() {
            return persistence;
        }

        @Override
        public BisqEasyTradeStore getPersistableStore() {
            return persistableStore;
        }

        @Override
        public Optional<BisqEasyTradeStore> readPersisted() {
            return BisqEasyTradeService.this.readPersisted();
        }

        @Override
        public CompletableFuture<Boolean> persist() {
            return BisqEasyTradeService.this.persist();
        }
    }

    private record Participants(String peerId, String myId) {
    }

    private record Publication(String tradeId, BisqEasyTradeSnapshot snapshot) {
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
