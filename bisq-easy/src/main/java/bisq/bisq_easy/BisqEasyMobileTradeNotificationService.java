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

import bisq.common.application.Service;
import bisq.common.observable.Pin;
import bisq.common.observable.collection.CollectionObserver;
import bisq.i18n.Res;
import bisq.notifications.NotificationService;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import bisq.trade.bisq_easy.BisqEasyTradeService;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import bisq.user.profile.UserProfile;
import bisq.user.profile.UserProfileService;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Emits mobile-push notifications for the trade-state transitions that the user actually
 * needs to act on, mirroring the Android nodeApp's {@code OpenTradesNotificationService}
 * which has proven sound at scale.
 * <p>
 * Why this exists: the {@link bisq.chat.notifications.ChatNotificationService} push path
 * was suppressing {@code PROTOCOL_LOG_MESSAGE} chat events for mobile (issue
 * {@code bisq-network/bisq-mobile#1450} — they fired 6–10+ times per active trade and
 * spammed users). That filter killed the noise AND the few signal events. This service
 * re-introduces the useful signal events on the mobile relay path, observing
 * {@link BisqEasyTradeReadModel#tradeStateObservable()} directly with a tight whitelist of states.
 * <p>
 * Dispatch is mobile-only (via {@link NotificationService#dispatchMobileOnlyNotification})
 * because the desktop already surfaces these events through the in-app trade chat's
 * protocol log and the trade detail header — we don't want desktop users to see a
 * duplicate OS-level toast.
 * <p>
 * Per-trade {@code notifiedPaymentInfo} dedup mirrors the nodeApp guard documented in
 * {@code OpenTradesNotificationService}: the offer-response state AND the payment-data
 * observer can each fire for the same logical "payment account info exchanged" event,
 * so we collapse them to one push per trade.
 * <p>
 * Whitelist of states (in order):
 * <ul>
 *   <li>{@code BUYER_SENT_FIAT_SENT_CONFIRMATION} — buyer-side push: "you sent fiat";
 *       seller-side push: "peer sent fiat".</li>
 *   <li>{@code SELLER_RECEIVED_FIAT_SENT_CONFIRMATION} — seller-side: "you received
 *       the fiat-sent confirmation"; buyer-side: "you sent fiat".</li>
 *   <li>{@code BUYER_RECEIVED_SELLERS_FIAT_RECEIPT_CONFIRMATION} /
 *       {@code SELLER_CONFIRMED_FIAT_RECEIPT} — buyer-side: "peer confirmed receipt";
 *       seller-side: "you confirmed receipt".</li>
 *   <li>{@code SELLER_SENT_BTC_SENT_CONFIRMATION} — seller-side: "you sent BTC";
 *       buyer-side: "peer sent BTC".</li>
 *   <li>{@code BUYER_RECEIVED_BTC_SENT_CONFIRMATION} — buyer-side: "you received BTC
 *       confirmation"; seller-side: "you sent BTC".</li>
 *   <li>The first {@code MAKER_SENT_TAKE_OFFER_RESPONSE__*} state in each maker
 *       protocol branch - "offer taken" after receiving and accepting the peer's request.</li>
 *   <li>{@code TAKER_RECEIVED_TAKE_OFFER_RESPONSE__*} (3 variants) — payment-info
 *       exchanged; gated by per-trade dedup so the user gets ONE "payment info
 *       sent/received" push regardless of which observer fires first.</li>
 *   <li>Terminal states ({@code BTC_CONFIRMED}, {@code REJECTED}, {@code PEER_REJECTED},
 *       {@code CANCELLED}, {@code PEER_CANCELLED}, {@code FAILED}, {@code FAILED_AT_PEER}).
 * </ul>
 * <p>
 * All other states are intentionally silent — they're intermediate protocol bookkeeping
 * the user doesn't need a push for. Same set as Android nodeApp.
 */
@Slf4j
public class BisqEasyMobileTradeNotificationService implements Service {
    private final BisqEasyTradeService bisqEasyTradeService;
    private final UserProfileService userProfileService;
    private final NotificationService notificationService;

    private final Object subscriptionLock = new Object();
    @Nullable
    private Subscription subscription;

    public BisqEasyMobileTradeNotificationService(BisqEasyTradeService bisqEasyTradeService,
                                                  UserProfileService userProfileService,
                                                  NotificationService notificationService) {
        this.bisqEasyTradeService = bisqEasyTradeService;
        this.userProfileService = userProfileService;
        this.notificationService = notificationService;
    }


    /* --------------------------------------------------------------------- */
    // Service lifecycle
    /* --------------------------------------------------------------------- */

    @Override
    public CompletableFuture<Boolean> initialize() {
        log.info("initialize");
        Subscription current;
        synchronized (subscriptionLock) {
            if (subscription != null) {
                return CompletableFuture.completedFuture(true);
            }
            current = new Subscription();
            subscription = current;
        }
        Pin pin = bisqEasyTradeService.getTrades().addObserver(new CollectionObserver<BisqEasyTradeReadModel>() {
            @Override
            public void onAdded(BisqEasyTradeReadModel trade) {
                subscribeToTrade(current, trade);
            }

            @Override
            public void onRemoved(Object element) {
                if (element instanceof BisqEasyTradeReadModel trade) {
                    unsubscribeFromTrade(current, trade);
                }
            }

            @Override
            public void onCleared() {
                clearAllState(current);
            }
        });
        boolean attach;
        synchronized (subscriptionLock) {
            attach = current.active;
            if (attach) {
                current.tradesPin = pin;
            }
        }
        if (!attach) {
            pin.unbind();
        }
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Boolean> shutdown() {
        log.info("shutdown");
        List<Pin> pins = new ArrayList<>();
        synchronized (subscriptionLock) {
            if (subscription != null) {
                subscription.active = false;
                if (subscription.tradesPin != null) {
                    pins.add(subscription.tradesPin);
                    subscription.tradesPin = null;
                }
                closeTradeSubscriptions(subscription, pins);
                subscription = null;
            }
        }
        pins.forEach(Pin::unbind);
        return CompletableFuture.completedFuture(true);
    }


    /* --------------------------------------------------------------------- */
    // Per-trade subscription
    /* --------------------------------------------------------------------- */

    private void subscribeToTrade(Subscription current, BisqEasyTradeReadModel trade) {
        TradeSubscription registration = new TradeSubscription(trade);
        List<Pin> previousPins = new ArrayList<>();
        synchronized (subscriptionLock) {
            if (!current.active) {
                return;
            }
            TradeSubscription previous = current.trades.get(trade.getId());
            if (previous != null) {
                if (previous.trade == trade) {
                    return;
                }
                closeTradeSubscription(previous, previousPins);
            }
            current.trades.put(trade.getId(), registration);
        }
        previousPins.forEach(Pin::unbind);
        Pin pin = trade.tradeStateObservable().addObserver(state -> onStateEmitted(registration, state));
        boolean attach;
        synchronized (subscriptionLock) {
            attach = registration.active;
            if (attach) {
                registration.statePin = pin;
            }
        }
        // addObserver may invoke callbacks before returning. Removal/shutdown can close the
        // registration during that call, so its late Pin must never become an owned subscription.
        if (!attach) {
            pin.unbind();
        }
    }

    private void unsubscribeFromTrade(Subscription current, BisqEasyTradeReadModel trade) {
        List<Pin> pins = new ArrayList<>();
        synchronized (subscriptionLock) {
            TradeSubscription registration = current.trades.get(trade.getId());
            if (registration != null && registration.trade == trade) {
                current.trades.remove(trade.getId());
                closeTradeSubscription(registration, pins);
            }
        }
        pins.forEach(Pin::unbind);
    }

    private void clearAllState(Subscription current) {
        List<Pin> pins = new ArrayList<>();
        synchronized (subscriptionLock) {
            closeTradeSubscriptions(current, pins);
        }
        pins.forEach(Pin::unbind);
    }

    private void closeTradeSubscriptions(Subscription current, List<Pin> pins) {
        current.trades.values().forEach(registration -> closeTradeSubscription(registration, pins));
        current.trades.clear();
    }

    private void closeTradeSubscription(TradeSubscription registration, List<Pin> pins) {
        registration.active = false;
        if (registration.statePin != null) {
            pins.add(registration.statePin);
            registration.statePin = null;
        }
    }

    private void onStateEmitted(TradeSubscription registration, BisqEasyTradeState state) {
        if (state == null || !registration.active) {
            return;
        }
        String tradeId = registration.trade.getId();
        if (registration.initialized.compareAndSet(false, true)) {
            log.debug("Skipping initial state observation for trade {} (state={})", tradeId, state);
            return;
        }
        try {
            handleStateChange(registration, state);
        } catch (Exception e) {
            log.error("Failed to handle mobile-push trade state change for trade {} state {}", tradeId, state, e);
        }
    }

    private static final class Subscription {
        private boolean active = true;
        @Nullable
        private Pin tradesPin;
        private final Map<String, TradeSubscription> trades = new HashMap<>();
    }

    private static final class TradeSubscription {
        private final BisqEasyTradeReadModel trade;
        private volatile boolean active = true;
        @Nullable
        private Pin statePin;
        // The initial observer emission is a snapshot, not a new transition. Both markers belong
        // to this registration, so callbacks from a removed trade cannot affect its replacement.
        private final AtomicBoolean initialized = new AtomicBoolean();
        private final AtomicBoolean notifiedPaymentInfo = new AtomicBoolean();

        private TradeSubscription(BisqEasyTradeReadModel trade) {
            this.trade = trade;
        }
    }


    /* --------------------------------------------------------------------- */
    // Whitelist + dispatch
    /* --------------------------------------------------------------------- */

    private void handleStateChange(TradeSubscription registration, BisqEasyTradeState state) {
        BisqEasyTradeReadModel trade = registration.trade;
        // Maker protocols respond automatically to a received request. They never enter the
        // TAKER_SENT_TAKE_OFFER_REQUEST state, which belongs to the taker's local action.
        // A buyer maker who received the account data before the request enters a state which also
        // represents the payment-info exchange. Both pushes share one notification id, so a second
        // push would replace the offer-taken push on the device. Offer taken wins: it is the first
        // time the maker learns about the trade, and the trade view shows the received account data.
        // The trade counts as notified for payment info, so the maker's own later BTC address
        // transition does not push either.
        if (trade.isMaker() && isOfferTakenState(state)) {
            if (isPaymentInfoExchangedState(state)) {
                registration.notifiedPaymentInfo.set(true);
            }
            dispatch(registration, "bisqEasy.mobileNotifications.offerTaken.title",
                    "bisqEasy.mobileNotifications.offerTaken.message");
            return;
        }

        // Payment-info exchange — push once per trade as soon as the user's
        // FSM reflects that either (a) account data has been received or (b)
        // account data has been sent. Because the Bisq Easy protocol allows
        // BTC-address and account-data to be sent in either order, and the
        // "received take-offer response" can interleave with both, the
        // payment-info event has many possible state representations across
        // the four trade roles (buyer/seller × maker/taker) and three timing
        // branches (1.1 / 1.2 / 2). We whitelist all of them; the
        // notifiedPaymentInfo dedup collapses them to a single push per trade
        // regardless of which transition fires first.
        //
        // Originally only the three Branch-1.2 / Branch-2 intermediates were
        // listed. The Branch-1.1 ("btc-address sent first, account-data
        // arrives later") and final-converging ("both halves done") paths
        // silently produced no push, which surfaced in
        // bisq-network/bisq-mobile#1464 once the public-chat noise that had
        // been masking the gap got correctly filtered out.
        if (isPaymentInfoExchangedState(state)) {
            if (registration.notifiedPaymentInfo.compareAndSet(false, true)) {
                if (trade.isSeller()) {
                    dispatch(registration, "bisqEasy.mobileNotifications.paymentInfoSent.title",
                            "bisqEasy.mobileNotifications.paymentInfoSent.message");
                } else {
                    dispatch(registration, "bisqEasy.mobileNotifications.paymentInfoReceived.title",
                            "bisqEasy.mobileNotifications.paymentInfoReceived.message");
                }
            }
            return;
        }

        // Self-action suppression rationale (bisq-network/bisq-mobile#1464 follow-up).
        //
        // Each "*_SENT_*" / "*_CONFIRMED_*" state below is reached via a LOCAL event
        // handler in the user's own trade FSM (see e.g.
        // BisqEasyBuyerAsTakerProtocol.java:102-104: BisqEasyConfirmFiatSentEvent → BUYER_SENT_FIAT_SENT_CONFIRMATION).
        // Pushing the user about an action they just took on the device is pure noise —
        // they already see the result in the UI. The nodeApp (decentralised) avoids this
        // by unregistering observers while the app is foregrounded; the relay path has
        // no foreground signal, so we suppress at the source instead.
        //
        // The counterparty's notification still fires on THEIR machine via the
        // symmetric "*_RECEIVED_*" state (e.g. SELLER_RECEIVED_FIAT_SENT_CONFIRMATION
        // for the buyer's "I sent fiat" action), so no information is lost — each side
        // is informed about the OTHER side's action exactly once.
        //
        // Suppressed (own-action) states intentionally fall through to the default
        // branch as no-ops:
        //   - BUYER_SENT_FIAT_SENT_CONFIRMATION                 (buyer's local "I sent fiat" event)
        //   - SELLER_CONFIRMED_FIAT_RECEIPT                     (seller's local "I received fiat" event)
        //   - SELLER_SENT_BTC_SENT_CONFIRMATION                 (seller's local "I sent BTC" event)
        switch (state) {
            case SELLER_RECEIVED_FIAT_SENT_CONFIRMATION -> {
                if (trade.isSeller()) {
                    dispatch(registration, "bisqEasy.mobileNotifications.youReceivedFiatConfirmation.title",
                            "bisqEasy.mobileNotifications.youReceivedFiatConfirmation.message");
                }
            }

            case BUYER_RECEIVED_SELLERS_FIAT_RECEIPT_CONFIRMATION -> {
                // Peer (seller) confirmed fiat receipt — buyer learns about it here.
                // SELLER_CONFIRMED_FIAT_RECEIPT (the seller's own-action twin of this
                // state) is intentionally absent — see suppressed list above.
                if (trade.isBuyer()) {
                    dispatch(registration, "bisqEasy.mobileNotifications.peerReceivedFiat.title",
                            "bisqEasy.mobileNotifications.peerReceivedFiat.message");
                }
            }

            case BUYER_RECEIVED_BTC_SENT_CONFIRMATION -> {
                // Peer (seller) sent BTC — buyer learns about it here.
                // SELLER_SENT_BTC_SENT_CONFIRMATION (seller's own-action twin) is
                // intentionally suppressed — see list above.
                if (trade.isBuyer()) {
                    dispatch(registration, "bisqEasy.mobileNotifications.youReceivedBtc.title",
                            "bisqEasy.mobileNotifications.youReceivedBtc.message");
                }
            }

            default -> {
                if (state.isFinalState()) {
                    String terminalKey = terminalI18nKey(state);
                    if (terminalKey != null) {
                        dispatchTerminal(registration, terminalKey);
                    }
                }
                // Other non-whitelist, non-terminal states are intentionally silent.
            }
        }
    }


    /* --------------------------------------------------------------------- */
    // Helpers
    /* --------------------------------------------------------------------- */

    private static boolean isOfferTakenState(BisqEasyTradeState state) {
        return switch (state) {
            case MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_DID_NOT_SENT_BTC_ADDRESS__BUYER_DID_NOT_RECEIVED_ACCOUNT_DATA,
                 MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_DID_NOT_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS,
                 MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_DID_NOT_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA_,
                 MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_DID_NOT_SENT_ACCOUNT_DATA__SELLER_RECEIVED_BTC_ADDRESS_ -> true;
            default -> false;
        };
    }

    /**
     * States which represent the "payment account info exchanged" event, covering all 4 roles
     * x Branch-1.x + final-converging. See the payment-info block in handleStateChange for rationale.
     */
    private static boolean isPaymentInfoExchangedState(BisqEasyTradeState state) {
        return switch (state) {
            // --- BUYER AS TAKER ---
            case TAKER_RECEIVED_TAKE_OFFER_RESPONSE__BUYER_DID_NOT_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA,
                 TAKER_RECEIVED_TAKE_OFFER_RESPONSE__BUYER_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA,
                 // --- SELLER AS MAKER ---
                 MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS,
                 MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_RECEIVED_BTC_ADDRESS,
                 // --- SELLER AS TAKER ---
                 TAKER_RECEIVED_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS,
                 TAKER_RECEIVED_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS_,
                 TAKER_RECEIVED_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_RECEIVED_BTC_ADDRESS,
                 // --- BUYER AS MAKER ---
                 MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_DID_NOT_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA,
                 MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_DID_NOT_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA_,
                 MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA -> true;
            default -> false;
        };
    }

    private void dispatch(TradeSubscription registration, String titleKey, String messageKey) {
        BisqEasyTradeReadModel trade = registration.trade;
        String shortTradeId = shortTradeId(trade);
        String peerName = peerNameFor(trade);
        String title = Res.get(titleKey, shortTradeId);
        String message = Res.get(messageKey, peerName);
        String notificationId = notificationIdFor(trade);
        if (!registration.active) {
            return;
        }
        notificationService.dispatchMobileOnlyNotification(
                new MobileTradeNotification(notificationId, title, message, trade.getId()));
    }

    private void dispatchTerminal(TradeSubscription registration, String terminalI18nKey) {
        BisqEasyTradeReadModel trade = registration.trade;
        String shortTradeId = shortTradeId(trade);
        String peerName = peerNameFor(trade);
        String terminalLabel = Res.get(terminalI18nKey);
        String title = Res.get("bisqEasy.mobileNotifications.tradeCompleted.title", shortTradeId);
        String message = Res.get("bisqEasy.mobileNotifications.tradeCompleted.message", peerName, terminalLabel);
        if (!registration.active) {
            return;
        }
        notificationService.dispatchMobileOnlyNotification(
                new MobileTradeNotification(notificationIdFor(trade), title, message, trade.getId()));
    }

    @Nullable
    static String terminalI18nKey(BisqEasyTradeState state) {
        return switch (state) {
            case BTC_CONFIRMED -> "bisqEasy.mobileNotifications.terminal.completed";
            case REJECTED -> "bisqEasy.mobileNotifications.terminal.rejected";
            case PEER_REJECTED -> "bisqEasy.mobileNotifications.terminal.peerRejected";
            case CANCELLED -> "bisqEasy.mobileNotifications.terminal.cancelled";
            case PEER_CANCELLED -> "bisqEasy.mobileNotifications.terminal.peerCancelled";
            case FAILED -> "bisqEasy.mobileNotifications.terminal.failed";
            case FAILED_AT_PEER -> "bisqEasy.mobileNotifications.terminal.failedAtPeer";
            default -> null;
        };
    }

    /**
     * Whitelist check — exposed package-private so callers and tests can reason about
     * whether a given state will produce a push without depending on the full handler.
     */
    static boolean isWhitelistedState(BisqEasyTradeState state) {
        // Own-action states (BUYER_SENT_FIAT_SENT_CONFIRMATION,
        // SELLER_CONFIRMED_FIAT_RECEIPT, SELLER_SENT_BTC_SENT_CONFIRMATION,
        // TAKER_SENT_TAKE_OFFER_REQUEST) are deliberately absent - see the
        // self-action suppression comment in handleStateChange.
        return isOfferTakenState(state) || isPaymentInfoExchangedState(state) || switch (state) {
            case SELLER_RECEIVED_FIAT_SENT_CONFIRMATION,
                 BUYER_RECEIVED_SELLERS_FIAT_RECEIPT_CONFIRMATION,
                 BUYER_RECEIVED_BTC_SENT_CONFIRMATION -> true;
            default -> state.isFinalState();
        };
    }

    private static String shortTradeId(BisqEasyTradeReadModel trade) {
        String id = trade.getId();
        return id.substring(0, Math.min(8, id.length()));
    }

    private String peerNameFor(BisqEasyTradeReadModel trade) {
        return userProfileService.findUserProfile(trade.getPeer().getNetworkId().getId())
                .map(UserProfile::getUserName)
                .orElse("?");
    }

    /**
     * Stable per-trade notification id so that subsequent state changes for the same trade
     * REPLACE the previous push on the device rather than stack up another row in the
     * notification tray (this matches Android nodeApp's {@code NotificationIds.getTradeStateUpdatedId(...)}).
     */
    private static String notificationIdFor(BisqEasyTradeReadModel trade) {
        return "bisq-easy-mobile-trade-" + shortTradeId(trade);
    }
}
