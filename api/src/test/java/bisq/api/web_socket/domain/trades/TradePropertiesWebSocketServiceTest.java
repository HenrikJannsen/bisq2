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

package bisq.api.web_socket.domain.trades;

import bisq.api.web_socket.subscription.SubscriberRepository;
import bisq.common.observable.Pin;
import bisq.common.observable.ReadOnlyObservable;
import bisq.common.observable.collection.CollectionObserver;
import bisq.common.observable.collection.ReadOnlyObservableSet;
import bisq.trade.TradeService;
import bisq.trade.bisq_easy.BisqEasyTradeReadModel;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static bisq.api.web_socket.subscription.Topic.TRADE_PROPERTIES;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TradePropertiesWebSocketServiceTest {
    @Test
    void activeTradeUpdatesContinueToReachSubscribers() {
        Fixture fixture = new Fixture(false);
        fixture.finishSubscription.countDown();
        fixture.service.initialize().join();
        fixture.collectionObserver.get().onAdded(fixture.trade);

        fixture.stateObserver.get().accept(BisqEasyTradeState.CANCELLED);

        verify(fixture.subscribers).findSubscribers(TRADE_PROPERTIES);
        fixture.service.shutdown().join();
        verify(fixture.statePin).unbind();
    }

    @Test
    void shutdownDetachesSubscriptionsWhichReturnAfterTheSessionHasClosed() throws Exception {
        Fixture fixture = new Fixture(true);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var initialization = executor.submit(() -> fixture.service.initialize().join());
            try {
                assertTrue(fixture.subscriptionStarted.await(5, TimeUnit.SECONDS));
                fixture.service.shutdown().join();
            } finally {
                fixture.finishSubscription.countDown();
            }
            initialization.get(5, TimeUnit.SECONDS);
        }

        verify(fixture.statePin).unbind();
        verify(fixture.collectionPin).unbind();
        fixture.stateObserver.get().accept(BisqEasyTradeState.CANCELLED);
        verifyNoInteractions(fixture.subscribers);
        verify(fixture.trade, never()).getInterruptTradeInitiator();
    }

    @Test
    void removingATradeDetachesItsLateSubscriptionAndSuppressesLaterUpdates() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.service.initialize().join();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var registration = executor.submit(() -> fixture.collectionObserver.get().onAdded(fixture.trade));
            try {
                assertTrue(fixture.subscriptionStarted.await(5, TimeUnit.SECONDS));
                fixture.collectionObserver.get().onRemoved(fixture.trade);
            } finally {
                fixture.finishSubscription.countDown();
            }
            registration.get(5, TimeUnit.SECONDS);
        }

        verify(fixture.statePin).unbind();
        verify(fixture.collectionPin, never()).unbind();
        fixture.stateObserver.get().accept(BisqEasyTradeState.CANCELLED);
        verifyNoInteractions(fixture.subscribers);
        fixture.service.shutdown().join();
    }

    private static final class Fixture {
        private final SubscriberRepository subscribers = mock(SubscriberRepository.class);
        private final BisqEasyTradeReadModel trade = mock(BisqEasyTradeReadModel.class, RETURNS_DEEP_STUBS);
        private final Pin collectionPin = mock(Pin.class);
        private final Pin statePin = mock(Pin.class);
        private final CountDownLatch subscriptionStarted = new CountDownLatch(1);
        private final CountDownLatch finishSubscription = new CountDownLatch(1);
        private final AtomicReference<CollectionObserver<BisqEasyTradeReadModel>> collectionObserver = new AtomicReference<>();
        private final AtomicReference<Consumer<BisqEasyTradeState>> stateObserver = new AtomicReference<>();
        private final TradePropertiesWebSocketService service;

        @SuppressWarnings("unchecked")
        private Fixture(boolean emitInitialTrade) {
            TradeService tradeService = mock(TradeService.class, RETURNS_DEEP_STUBS);
            ReadOnlyObservableSet<BisqEasyTradeReadModel> trades = mock(ReadOnlyObservableSet.class);
            ReadOnlyObservable<BisqEasyTradeState> state = mock(ReadOnlyObservable.class);
            when(trade.getId()).thenReturn("trade-id");
            when(trade.tradeStateObservable()).thenReturn(state);
            when(tradeService.getBisqEasyTradeService().getTrades()).thenReturn(trades);
            doAnswer(invocation -> {
                CollectionObserver<BisqEasyTradeReadModel> observer = invocation.getArgument(0);
                collectionObserver.set(observer);
                if (emitInitialTrade) {
                    observer.onAdded(trade);
                }
                return collectionPin;
            }).when(trades).addObserver(any(CollectionObserver.class));
            doAnswer(invocation -> {
                stateObserver.set(invocation.getArgument(0));
                subscriptionStarted.countDown();
                assertTrue(finishSubscription.await(5, TimeUnit.SECONDS));
                return statePin;
            }).when(state).addObserver(any());
            service = new TradePropertiesWebSocketService(subscribers, tradeService);
        }
    }
}
