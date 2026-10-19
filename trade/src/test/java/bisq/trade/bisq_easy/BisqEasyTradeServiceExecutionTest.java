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

import bisq.identity.Identity;
import bisq.network.NetworkService;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeActionNotAppliedException;
import bisq.trade.bisq_easy.protocol.BisqEasyProtocol;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyConfirmBtcSentMessage;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyConfirmFiatReceiptMessage;
import com.google.protobuf.Any;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static bisq.trade.bisq_easy.BisqEasyTradeTestData.QUOTE_AMOUNT;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.buyerAsTakerTrade;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.contract;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.identity;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.inState;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.networkId;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.offer;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.sellerAsMakerTrade;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.userProfile;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.BUYER_RECEIVED_BTC_SENT_CONFIRMATION;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.BUYER_RECEIVED_SELLERS_FIAT_RECEIPT_CONFIRMATION;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.BUYER_SENT_FIAT_SENT_CONFIRMATION;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BisqEasyTradeServiceExecutionTest {
    @Test
    void observerQueuesCloseBeforeAnActionWhichThenRejectsTheClosedHandle() throws Exception {
        ServiceProvider provider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        BisqEasyTradeService service = BisqEasyTradeServiceTestSupport.service(provider);
        when(provider.getBisqEasyTradeService()).thenReturn(service);
        Identity maker = identity("maker");
        Identity taker = identity("taker");
        BisqEasyTrade trade = inState(buyerAsTakerTrade(contract(
                offer("offer", maker.getNetworkId(), QUOTE_AMOUNT), taker.getNetworkId(), Optional.empty()), taker),
                BUYER_SENT_FIAT_SENT_CONFIRMATION);
        service.getPersistableStore().addTrade(trade);
        AtomicReference<CompletableFuture<Void>> close = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> action = new AtomicReference<>();
        CountDownLatch observerSubmitted = new CountDownLatch(1);
        CopyOnWriteArrayList<String> events = new CopyOnWriteArrayList<>();
        try {
            service.initialize().get(5, SECONDS);
            BisqEasyTradeReadModel handle = service.findTrade(trade.getId()).orElseThrow();
            Set<BisqEasyTradeReadModel> previouslyPublished = service.getTrades().getUnmodifiableSet();
            handle.stateObservable().addObserver(state -> {
                if (state == BUYER_RECEIVED_SELLERS_FIAT_RECEIPT_CONFIRMATION) {
                    service.assertTradeThread();
                    events.add("observer-start");
                    close.set(service.closeTrade(handle, userProfile(taker.getNetworkId()), userProfile(maker.getNetworkId()))
                            .thenRun(() -> events.add("closed")));
                    action.set(service.buyerSendBitcoinPaymentData(handle, "private-payment-data")
                            .whenComplete((unused, failure) -> events.add("action-completed")));
                    events.add("observer-end");
                    observerSubmitted.countDown();
                }
            });

            service.onMessage(new BisqEasyConfirmFiatReceiptMessage("fiat", trade.getId(), BisqEasyProtocol.VERSION,
                    trade.getPeer().getNetworkId(), trade.getMyself().getNetworkId()));

            assertThat(observerSubmitted.await(5, SECONDS)).isTrue();
            close.get().get(5, SECONDS);
            assertThatThrownBy(() -> action.get().get(5, SECONDS))
                    .hasCauseInstanceOf(TradeActionNotAppliedException.class)
                    .cause().hasMessage("The trade is no longer active");
            service.flush().get(5, SECONDS);
            assertThat(events).containsExactly("observer-start", "observer-end", "closed", "action-completed");
            assertThat(service.findTrade(trade.getId())).isEmpty();
            assertThat(service.getTrades()).isEmpty();
            assertThat(previouslyPublished).containsExactly(handle);
            assertThat(service.getClosedTrades()).singleElement()
                    .satisfies(closed -> assertThat(closed.trade()).isSameAs(handle));
            assertThat(service.getAllTrades()).containsExactly(handle);
            assertThat(handle.getBitcoinPaymentData()).isEmpty();
            assertThat(trade.getBitcoinPaymentData()).isEmpty();
        } finally {
            service.shutdown().get(5, SECONDS);
        }
    }

    @Test
    void persistenceCapturesDeferredInputAndCommittedStateTogetherOnTheOwner() throws Exception {
        ServiceProvider provider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        BisqEasyTradeService service = BisqEasyTradeServiceTestSupport.service(provider);
        when(provider.getBisqEasyTradeService()).thenReturn(service);
        Identity maker = identity("maker");
        Identity taker = identity("taker");
        BisqEasyTrade trade = inState(buyerAsTakerTrade(contract(
                offer("offer", maker.getNetworkId(), QUOTE_AMOUNT), taker.getNetworkId(), Optional.empty()), taker),
                BUYER_SENT_FIAT_SENT_CONFIRMATION);
        service.getPersistableStore().addTrade(trade);
        BisqEasyConfirmBtcSentMessage bitcoinConfirmation = new BisqEasyConfirmBtcSentMessage("bitcoin", trade.getId(),
                BisqEasyProtocol.VERSION, trade.getPeer().getNetworkId(), trade.getMyself().getNetworkId(),
                Optional.of("payment-proof"));
        BisqEasyConfirmFiatReceiptMessage fiatReceipt = new BisqEasyConfirmFiatReceiptMessage("fiat", trade.getId(),
                BisqEasyProtocol.VERSION, trade.getPeer().getNetworkId(), trade.getMyself().getNetworkId());
        CopyOnWriteArrayList<Any> snapshots = new CopyOnWriteArrayList<>();
        when(service.getPersistence().persistSnapshotAsync(any())).thenAnswer(invocation -> {
            service.assertTradeThread();
            snapshots.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        CountDownLatch proofCommitted = new CountDownLatch(1);
        CountDownLatch finishTransition = new CountDownLatch(1);
        try {
            service.initialize().get(5, SECONDS);
            service.onMessage(bitcoinConfirmation);
            service.flush().get(5, SECONDS);
            Any deferredSnapshot = snapshots.getLast();
            BisqEasyTrade deferred = restoreTrade(deferredSnapshot, trade.getId());
            assertThat(deferred.getState()).isEqualTo(BUYER_SENT_FIAT_SENT_CONFIRMATION);
            assertThat(deferred.getEventQueue()).containsExactly(bitcoinConfirmation);
            int snapshotsBeforeTransition = snapshots.size();
            trade.paymentProofObservable().addObserver(proof -> {
                if (proof.isPresent()) {
                    proofCommitted.countDown();
                    try {
                        assertThat(finishTransition.await(5, SECONDS)).isTrue();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(exception);
                    }
                }
            });

            service.onMessage(fiatReceipt);
            assertThat(proofCommitted.await(5, SECONDS)).isTrue();
            CompletableFuture<Boolean> persist = service.persist();
            CompletableFuture<Void> flush = service.flush();
            assertThat(persist).isNotDone();
            assertThat(flush).isNotDone();
            assertThat(snapshots).hasSize(snapshotsBeforeTransition);
            finishTransition.countDown();

            assertThat(persist.get(5, SECONDS)).isTrue();
            flush.get(5, SECONDS);
            BisqEasyTrade restored = restoreTrade(snapshots.getLast(), trade.getId());
            assertThat(restored.getState()).isEqualTo(BUYER_RECEIVED_BTC_SENT_CONFIRMATION);
            assertThat(restored.getPaymentProof()).contains("payment-proof");
            assertThat(restored.getEventQueue()).isEmpty();
            // A pending immutable write cannot observe later mutations of its source model.
            assertThat(restoreTrade(deferredSnapshot, trade.getId()).getEventQueue()).containsExactly(bitcoinConfirmation);
        } finally {
            finishTransition.countDown();
            service.shutdown().get(5, SECONDS);
        }
    }

    @Test
    void quiescenceWaitsForSubscriptionBeforeDetachingTheListener() throws Exception {
        ServiceProvider provider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        BisqEasyTradeService service = BisqEasyTradeServiceTestSupport.service(provider);
        NetworkService networkService = provider.getNetworkService();
        CountDownLatch subscribing = new CountDownLatch(1);
        CountDownLatch releaseSubscription = new CountDownLatch(1);
        doAnswer(invocation -> {
            subscribing.countDown();
            assertThat(releaseSubscription.await(5, SECONDS)).isTrue();
            return null;
        }).when(networkService).subscribeToConfidentialMessages(service);
        try {
            CompletableFuture<Boolean> initialization = service.initialize();
            assertThat(subscribing.await(5, SECONDS)).isTrue();

            CompletableFuture<Void> quiescence = service.quiesce();
            assertThat(quiescence).isNotDone();
            verify(networkService, never()).removeConfidentialMessageListener(service);
            releaseSubscription.countDown();

            assertThat(initialization.get(5, SECONDS)).isTrue();
            quiescence.get(5, SECONDS);
            InOrder lifecycle = inOrder(networkService);
            lifecycle.verify(networkService).subscribeToConfidentialMessages(service);
            lifecycle.verify(networkService).removeConfidentialMessageListener(service);
        } finally {
            releaseSubscription.countDown();
            service.shutdown().get(5, SECONDS);
        }
    }

    @Test
    void failedCaptureDrainsAnEarlierWriteBeforeShutdownReportsFailure() throws Exception {
        ServiceProvider provider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        BisqEasyTradeService service = BisqEasyTradeServiceTestSupport.service(provider);
        when(provider.getBisqEasyTradeService()).thenReturn(service);
        Identity maker = identity("maker");
        BisqEasyTrade trade = spy(sellerAsMakerTrade(contract(offer("offer", maker.getNetworkId(), QUOTE_AMOUNT),
                networkId("taker"), Optional.empty()), maker));
        service.getPersistableStore().addTrade(trade);
        CompletableFuture<Void> diskWrite = new CompletableFuture<>();
        CountDownLatch writeStarted = new CountDownLatch(1);
        try {
            service.initialize().get(5, SECONDS);
            when(service.getPersistence().persistSnapshotAsync(any())).thenAnswer(invocation -> {
                writeStarted.countDown();
                return diskWrite;
            });
            CompletableFuture<Boolean> earlierPersist = service.persist();
            assertThat(writeStarted.await(5, SECONDS)).isTrue();
            CountDownLatch failedCaptures = new CountDownLatch(2);
            doAnswer(invocation -> {
                failedCaptures.countDown();
                throw new IllegalStateException("cannot serialize trade");
            }).when(trade).toProto(false);

            CompletableFuture<Boolean> failedPersist = service.persist();
            CompletableFuture<Boolean> shutdown = service.shutdown();
            assertThat(failedCaptures.await(5, SECONDS)).isTrue();
            assertThat(failedPersist).isNotDone();
            assertThat(shutdown).isNotDone();

            diskWrite.complete(null);

            assertThat(earlierPersist.get(5, SECONDS)).isTrue();
            assertThat(failedPersist.get(5, SECONDS)).isFalse();
            assertThat(shutdown.get(5, SECONDS)).isFalse();
            verify(service.getPersistence(), times(1)).persistSnapshotAsync(any());
        } finally {
            diskWrite.complete(null);
            service.shutdown().get(5, SECONDS);
        }
    }

    @Test
    void persistenceDuringShutdownSharesTheTerminalFlushWithoutStartingAnotherWrite() throws Exception {
        ServiceProvider provider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        BisqEasyTradeService service = BisqEasyTradeServiceTestSupport.service(provider);
        CompletableFuture<Void> diskWrite = new CompletableFuture<>();
        CountDownLatch writeStarted = new CountDownLatch(1);
        when(service.getPersistence().persistSnapshotAsync(any())).thenAnswer(invocation -> {
            writeStarted.countDown();
            return diskWrite;
        });
        try {
            CompletableFuture<Boolean> earlierPersist = service.persist();
            assertThat(writeStarted.await(5, SECONDS)).isTrue();
            service.quiesce().get(5, SECONDS);

            CompletableFuture<Boolean> shutdown = service.shutdown();
            CompletableFuture<Boolean> concurrentPersist = service.persist();
            CompletableFuture<Void> concurrentFlush = service.flush();
            assertThat(shutdown).isNotDone();
            assertThat(concurrentPersist).isNotDone();
            diskWrite.complete(null);

            assertThat(earlierPersist.get(5, SECONDS)).isTrue();
            assertThat(shutdown.get(5, SECONDS)).isTrue();
            assertThat(concurrentPersist.get(5, SECONDS)).isTrue();
            concurrentFlush.get(5, SECONDS);
            verify(service.getPersistence(), times(1)).persistSnapshotAsync(any());
        } finally {
            diskWrite.complete(null);
            service.shutdown().get(5, SECONDS);
        }
    }

    private static BisqEasyTrade restoreTrade(Any snapshot, String tradeId) throws Exception {
        return BisqEasyTradeStore.fromProto(snapshot.unpack(bisq.trade.protobuf.BisqEasyTradeStore.class))
                .findTrade(tradeId).orElseThrow();
    }
}
