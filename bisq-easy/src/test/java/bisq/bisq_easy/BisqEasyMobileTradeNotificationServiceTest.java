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

import bisq.common.observable.Observable;
import bisq.common.observable.Pin;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.observable.collection.CollectionObserver;
import bisq.i18n.Res;
import bisq.notifications.Notification;
import bisq.notifications.NotificationService;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import bisq.trade.bisq_easy.BisqEasyTradeService;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import bisq.user.profile.UserProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BisqEasyMobileTradeNotificationServiceTest {
    private final BisqEasyTradeService tradeService = mock(BisqEasyTradeService.class);
    private final ObservableSet<BisqEasyTradeReadModel> trades = new ObservableSet<>();
    private final Observable<BisqEasyTradeState> state = new Observable<>(BisqEasyTradeState.INIT);
    private final BisqEasyTradeReadModel trade = mock(BisqEasyTradeReadModel.class, RETURNS_DEEP_STUBS);
    private final List<Notification> notifications = new ArrayList<>();
    private BisqEasyMobileTradeNotificationService service;

    @BeforeEach
    void setUp() {
        Res.setAndApplyLanguageTag("en");
        when(tradeService.getTrades()).thenReturn(trades);
        when(trade.getId()).thenReturn("trade123456");
        when(trade.tradeStateObservable()).thenReturn(state);
        when(trade.isMaker()).thenReturn(true);
        when(trade.getPeer().getNetworkId().getId()).thenReturn("peer");
        NotificationService notificationService = mock(NotificationService.class);
        doAnswer(invocation -> {
            notifications.add(invocation.getArgument(0));
            return null;
        }).when(notificationService).dispatchMobileOnlyNotification(any());
        service = new BisqEasyMobileTradeNotificationService(tradeService, mock(UserProfileService.class),
                notificationService);
        service.initialize().join();
    }

    @AfterEach
    void tearDown() {
        service.shutdown().join();
    }

    @ParameterizedTest
    @EnumSource(value = BisqEasyTradeState.class, names = {
            "MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_DID_NOT_SENT_BTC_ADDRESS__BUYER_DID_NOT_RECEIVED_ACCOUNT_DATA",
            "MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_DID_NOT_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS",
            "MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_DID_NOT_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA_",
            "MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_DID_NOT_SENT_ACCOUNT_DATA__SELLER_RECEIVED_BTC_ADDRESS_"})
    void makerReceivesOfferTakenNotificationAfterAcceptingPeerRequest(BisqEasyTradeState acceptedRequestState) {
        trades.add(trade);

        state.set(acceptedRequestState);

        assertEquals(1, notifications.size());
        assertEquals(1, offerTakenNotifications());
        assertTrue(notifications.stream().allMatch(notification ->
                notification.getTradeId().orElseThrow().equals(trade.getId())));

        state.set(BisqEasyTradeState.MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA);
        assertEquals(1, offerTakenNotifications());
    }

    @Test
    void buyerMakerWithEarlyAccountDataReceivesOnlyTheOfferTakenNotification() {
        state.set(BisqEasyTradeState.MAKER_DID_NOT_SENT_TAKE_OFFER_RESPONSE__BUYER_DID_NOT_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA);
        trades.add(trade);

        state.set(BisqEasyTradeState.MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_DID_NOT_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA_);

        assertEquals(1, notifications.size());
        assertEquals(1, offerTakenNotifications());

        state.set(BisqEasyTradeState.MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA);

        assertEquals(1, notifications.size());
    }

    @Test
    void takersOwnRequestDoesNotNotifyTheTaker() {
        when(trade.isMaker()).thenReturn(false);
        trades.add(trade);

        state.set(BisqEasyTradeState.TAKER_SENT_TAKE_OFFER_REQUEST);

        assertTrue(notifications.isEmpty());
    }

    @Test
    void loadingAnAcceptedTradeDoesNotRepeatTheNotification() {
        state.set(BisqEasyTradeState.MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_DID_NOT_SENT_BTC_ADDRESS__BUYER_DID_NOT_RECEIVED_ACCOUNT_DATA);

        trades.add(trade);

        assertTrue(notifications.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateStateSubscriptionIsClosedAfterRemovalOrShutdown(boolean shutdown) throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch returnPin = new CountDownLatch(1);
        AtomicBoolean pinClosed = new AtomicBoolean();
        Observable<BisqEasyTradeState> delayedState = new Observable<>(BisqEasyTradeState.INIT) {
            @Override
            public Pin addObserver(Consumer<BisqEasyTradeState> observer) {
                Pin pin = super.addObserver(observer);
                subscribed.countDown();
                try {
                    assertTrue(returnPin.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return () -> {
                    pinClosed.set(true);
                    pin.unbind();
                };
            }
        };
        when(trade.tradeStateObservable()).thenReturn(delayedState);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var addingTrade = executor.submit(() -> trades.add(trade));
            try {
                assertTrue(subscribed.await(5, TimeUnit.SECONDS));
                if (shutdown) {
                    service.shutdown().join();
                } else {
                    trades.remove(trade);
                }
                // Even before addObserver returns its Pin, its callback must have lost ownership.
                delayedState.set(BisqEasyTradeState.BTC_CONFIRMED);
                delayedState.set(BisqEasyTradeState.FAILED);
            } finally {
                returnPin.countDown();
            }
            addingTrade.get(5, TimeUnit.SECONDS);
            assertTrue(pinClosed.get(), "The Pin returned after removal/shutdown must be unbound");
            assertTrue(notifications.isEmpty(), "A closed subscription must not dispatch late notifications");
        }
    }

    @Test
    void initializationFinishingAfterShutdownUnbindsTheCollectionObserver() throws Exception {
        service.shutdown().join();
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch returnPin = new CountDownLatch(1);
        AtomicBoolean pinClosed = new AtomicBoolean();
        var delayedTrades = new ObservableSet<BisqEasyTradeReadModel>() {
            @Override
            public Pin addObserver(CollectionObserver<BisqEasyTradeReadModel> observer) {
                Pin pin = super.addObserver(observer);
                subscribed.countDown();
                try {
                    assertTrue(returnPin.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return () -> {
                    pinClosed.set(true);
                    pin.unbind();
                };
            }
        };
        when(tradeService.getTrades()).thenReturn(delayedTrades);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var initializing = executor.submit(service::initialize);
            try {
                assertTrue(subscribed.await(5, TimeUnit.SECONDS));
                service.shutdown().join();
            } finally {
                returnPin.countDown();
            }
            initializing.get(5, TimeUnit.SECONDS);
            assertTrue(pinClosed.get(), "The collection Pin returned after shutdown must be unbound");
            delayedTrades.add(trade);
            state.set(BisqEasyTradeState.BTC_CONFIRMED);
            assertTrue(notifications.isEmpty());
        }
    }

    private long offerTakenNotifications() {
        String title = Res.get("bisqEasy.mobileNotifications.offerTaken.title", "trade123");
        return notifications.stream().filter(notification -> notification.getTitle().equals(title)).count();
    }
}
