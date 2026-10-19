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

import bisq.common.observable.Pin;
import bisq.common.observable.collection.CollectionObserver;
import bisq.contract.Role;
import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.identity.Identity;
import bisq.trade.TradeLifecycleState;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import bisq.trade.exceptions.TradeProtocolFailure;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static bisq.trade.bisq_easy.BisqEasyTradeTestData.QUOTE_AMOUNT;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.contract;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.identity;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.offer;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.sellerAsMakerTrade;
import static org.assertj.core.api.Assertions.assertThat;

class BisqEasyTradeReadModelTest {
    @Test
    void propertyAndCollectionCallbacksCanSubscribeToEachOtherWithoutHoldingPublicationLocks() throws Exception {
        BisqEasyTradeReadModel readModel = new BisqEasyTradeReadModel(trade());
        BisqEasyPublishedSet<BisqEasyTradeReadModel> trades = new BisqEasyPublishedSet<>();
        CountDownLatch propertyCallbackEntered = new CountDownLatch(1);
        CountDownLatch collectionCallbackEntered = new CountDownLatch(1);
        AtomicBoolean nestedPropertyCallbackReceived = new AtomicBoolean();
        trades.addObserver(new CollectionObserver<>() {
            @Override
            public void onAdded(BisqEasyTradeReadModel trade) {
                collectionCallbackEntered.countDown();
                trade.tradeStateObservable().addObserver(state -> nestedPropertyCallbackReceived.set(true));
            }

            @Override
            public void onRemoved(Object element) {
            }

            @Override
            public void onCleared() {
            }
        });
        try (var executor = Executors.newFixedThreadPool(3)) {
            var subscription = executor.submit(() -> readModel.tradeStateObservable().addObserver(state -> {
                propertyCallbackEntered.countDown();
                try {
                    assertThat(collectionCallbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    // The timeout releases this callback even on the old lock inversion, so all threads can finish.
                    executor.submit(() -> trades.addObserver(() -> {})).get(1, TimeUnit.SECONDS).unbind();
                } catch (Exception exception) {
                    throw new AssertionError("Cross-subscription must not wait for an external callback", exception);
                }
            }));
            assertThat(propertyCallbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
            var publication = executor.submit(() -> trades.publish(List.of(readModel)));
            try {
                subscription.get(5, TimeUnit.SECONDS).unbind();
                publication.get(5, TimeUnit.SECONDS);
            } finally {
                collectionCallbackEntered.countDown();
            }
        }
        assertThat(nestedPropertyCallbackReceived).isTrue();
    }

    @Test
    void mutableTradeChangesAreVisibleOnlyAfterPublishingACompleteSnapshot() throws Exception {
        BisqEasyTrade trade = trade();
        BisqEasyTradeReadModel readModel = new BisqEasyTradeReadModel(trade);
        BisqEasyTradeSnapshot original = readModel.getSnapshot();
        trade.setPaymentAccountData(Optional.of("account"));
        trade.setBitcoinPaymentData(Optional.of("address"));
        trade.setPaymentProof(Optional.of("proof"));
        trade.setErrorData(TradeProtocolFailure.CHANNEL_DATA_MISMATCH, "stack", "error");

        assertThat(readModel.getPaymentAccountData()).isEmpty();
        assertThat(readModel.getErrorMessage()).isNull();
        List<BisqEasyTradeSnapshot> observed = new ArrayList<>();
        readModel.paymentAccountDataObservable().addObserver(value -> observed.add(readModel.getSnapshot()));

        BisqEasyTradeSnapshot captured = BisqEasyTradeSnapshot.from(trade);
        readModel.publish(captured);
        trade.setPaymentAccountData(Optional.of("later account"));

        assertThat(observed).containsExactly(original, captured);
        assertThat(captured.paymentAccountData()).contains("account");
        assertThat(captured.bitcoinPaymentData()).contains("address");
        assertThat(captured.paymentProof()).contains("proof");
        assertThat(captured.errorMessage()).isEqualTo("error");
        assertThat(captured.errorStackTrace()).isEqualTo("stack");
        assertThat(captured.tradeProtocolFailure()).isEqualTo(TradeProtocolFailure.CHANNEL_DATA_MISMATCH);
        assertThat(readModel.getPaymentAccountData()).contains("account");
    }

    @Test
    void everyPublishedTransitionNotifiesExistingSubscribersWithMatchingFields() throws Exception {
        BisqEasyTradeReadModel readModel = new BisqEasyTradeReadModel(trade());
        List<BisqEasyTradeSnapshot> observed = new ArrayList<>();
        Pin pin = readModel.tradeStateObservable().addObserver(state -> {
            assertThat(readModel.getSnapshot().tradeState()).isEqualTo(state);
            observed.add(readModel.getSnapshot());
        });
        BisqEasyTradeSnapshot cancelled = snapshot(BisqEasyTradeState.CANCELLED, "account");
        BisqEasyTradeSnapshot failed = snapshot(BisqEasyTradeState.FAILED, "redacted");

        readModel.publish(cancelled);
        readModel.publish(failed);
        readModel.publish(failed);
        pin.unbind();
        readModel.publish(cancelled);

        assertThat(observed).extracting(BisqEasyTradeSnapshot::tradeState)
                .containsExactly(BisqEasyTradeState.INIT, BisqEasyTradeState.CANCELLED, BisqEasyTradeState.FAILED);
        assertThat(observed.get(1).paymentAccountData()).contains("account");
        assertThat(observed.get(2).paymentAccountData()).contains("redacted");
    }

    @Test
    void initialObservationCannotArriveAfterAnUpdateOrOverlapIt() throws Exception {
        BisqEasyTradeReadModel readModel = new BisqEasyTradeReadModel(trade());
        CountDownLatch initialObservationStarted = new CountDownLatch(1);
        CountDownLatch finishInitialObservation = new CountDownLatch(1);
        CountDownLatch publishingStarted = new CountDownLatch(1);
        List<BisqEasyTradeState> observed = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var subscription = executor.submit(() -> readModel.tradeStateObservable().addObserver(state -> {
                observed.add(state);
                if (state == BisqEasyTradeState.INIT) {
                    initialObservationStarted.countDown();
                    try {
                        assertThat(finishInitialObservation.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException exception) {
                        throw new RuntimeException(exception);
                    }
                }
            }));
            try {
                assertThat(initialObservationStarted.await(5, TimeUnit.SECONDS)).isTrue();
                var publication = executor.submit(() -> {
                    publishingStarted.countDown();
                    readModel.publish(snapshot(BisqEasyTradeState.CANCELLED, "account"));
                    readModel.publish(snapshot(BisqEasyTradeState.FAILED, "redacted"));
                });
                assertThat(publishingStarted.await(5, TimeUnit.SECONDS)).isTrue();
                publication.get(5, TimeUnit.SECONDS);
                assertThat(readModel.getTradeState()).isEqualTo(BisqEasyTradeState.FAILED);
                assertThat(observed).containsExactly(BisqEasyTradeState.INIT);
                finishInitialObservation.countDown();
                subscription.get(5, TimeUnit.SECONDS).unbind();
            } finally {
                finishInitialObservation.countDown();
            }
        }
        assertThat(observed).containsExactly(BisqEasyTradeState.INIT, BisqEasyTradeState.CANCELLED, BisqEasyTradeState.FAILED);
    }

    @Test
    void delayedSubscriptionsKeepTheirCapturedInitialValueAndUnboundSubscribersStayDetached() throws Exception {
        BisqEasyTradeReadModel readModel = new BisqEasyTradeReadModel(trade());
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch finishCallback = new CountDownLatch(1);
        readModel.snapshotObservable().addObserver(snapshot -> {
            if (snapshot.tradeState() == BisqEasyTradeState.CANCELLED) {
                callbackEntered.countDown();
                try {
                    assertThat(finishCallback.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    throw new AssertionError(exception);
                }
            }
        });
        List<BisqEasyTradeState> detached = new ArrayList<>();
        Pin detachedPin = readModel.tradeStateObservable().addObserver(detached::add);
        List<BisqEasyTradeState> observed = new ArrayList<>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var publication = executor.submit(() -> readModel.publish(snapshot(BisqEasyTradeState.CANCELLED, "account")));
            try {
                assertThat(callbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
                readModel.tradeStateObservable().addObserver(observed::add);
                readModel.publish(snapshot(BisqEasyTradeState.FAILED, "redacted"));
                detachedPin.unbind();
            } finally {
                finishCallback.countDown();
            }
            publication.get(5, TimeUnit.SECONDS);
        }
        assertThat(observed).containsExactly(BisqEasyTradeState.CANCELLED, BisqEasyTradeState.FAILED);
        assertThat(detached).containsExactly(BisqEasyTradeState.INIT);
    }

    @Test
    void observerFailureDoesNotPreventOtherPropertiesFromReceivingTheSnapshot() throws Exception {
        BisqEasyTradeReadModel readModel = new BisqEasyTradeReadModel(trade());
        readModel.tradeStateObservable().addObserver(state -> {
            throw new IllegalStateException("observer failure");
        });
        List<Optional<String>> observed = new ArrayList<>();
        readModel.paymentAccountDataObservable().addObserver(observed::add);

        readModel.publish(snapshot(BisqEasyTradeState.CANCELLED, "account"));

        assertThat(observed).containsExactly(Optional.empty(), Optional.of("account"));
        assertThat(readModel.getTradeState()).isEqualTo(BisqEasyTradeState.CANCELLED);
    }

    private static BisqEasyTrade trade() throws Exception {
        Identity maker = identity("maker");
        Identity taker = identity("taker");
        BisqEasyContract contract = contract(offer("offer-id", maker.getNetworkId(), QUOTE_AMOUNT),
                taker.getNetworkId(), Optional.empty());
        return sellerAsMakerTrade(contract, maker);
    }

    private static BisqEasyTradeSnapshot snapshot(BisqEasyTradeState state, String account) {
        return new BisqEasyTradeSnapshot(state, Role.TAKER, Optional.of(account), Optional.of("address"),
                Optional.of("proof"), Optional.of(1L), null, null, null, null, null, null,
                "version", TradeLifecycleState.ACTIVE);
    }
}
