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

package bisq.trade.bisq_easy.protocol;

import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.identity.Identity;
import bisq.offer.bisq_easy.BisqEasyOffer;
import bisq.trade.ServiceProvider;
import bisq.trade.bisq_easy.BisqEasyTrade;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyConfirmBtcSentMessage;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyConfirmFiatReceiptMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static bisq.trade.bisq_easy.BisqEasyTradeTestData.QUOTE_AMOUNT;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.buyerAsTakerTrade;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.contract;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.identity;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.inState;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.networkId;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.offer;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.BUYER_RECEIVED_BTC_SENT_CONFIRMATION;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.BUYER_RECEIVED_SELLERS_FIAT_RECEIPT_CONFIRMATION;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.BUYER_SENT_FIAT_SENT_CONFIRMATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class BisqEasyProtocolRestartTest {
    private ServiceProvider serviceProvider;
    private BisqEasyTrade trade;
    private BisqEasyConfirmBtcSentMessage bitcoinConfirmation;
    private BisqEasyConfirmFiatReceiptMessage fiatReceipt;

    @BeforeEach
    void setUp() {
        serviceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        Identity makerIdentity = identity("maker");
        Identity takerIdentity = identity("taker");
        BisqEasyOffer offer = offer("offer-id", makerIdentity.getNetworkId(), QUOTE_AMOUNT);
        BisqEasyContract contract = contract(offer, takerIdentity.getNetworkId(), Optional.empty());
        trade = inState(buyerAsTakerTrade(contract, takerIdentity), BUYER_SENT_FIAT_SENT_CONFIRMATION);
        bitcoinConfirmation = new BisqEasyConfirmBtcSentMessage("bitcoin-confirmation", trade.getId(),
                BisqEasyProtocol.VERSION, trade.getPeer().getNetworkId(), trade.getMyself().getNetworkId(),
                Optional.of("payment-proof"));
        fiatReceipt = new BisqEasyConfirmFiatReceiptMessage("fiat-receipt", trade.getId(),
                BisqEasyProtocol.VERSION, trade.getPeer().getNetworkId(), trade.getMyself().getNetworkId());
    }

    @Test
    void earlyBitcoinConfirmationIsAppliedWhenFiatReceiptArrives() {
        BisqEasyProtocol protocol = new BisqEasyBuyerAsTakerProtocol(serviceProvider, trade);

        protocol.handle(bitcoinConfirmation);
        assertEquals(BUYER_SENT_FIAT_SENT_CONFIRMATION, trade.getState());
        assertTrue(trade.getEventQueue().contains(bitcoinConfirmation));
        protocol.handle(fiatReceipt);

        assertBitcoinConfirmationApplied(trade);
    }

    @Test
    void earlyBitcoinConfirmationSurvivesRestartBeforeFiatReceiptArrives() throws Exception {
        new BisqEasyBuyerAsTakerProtocol(serviceProvider, trade).handle(bitcoinConfirmation);
        assertTrue(trade.getEventQueue().contains(bitcoinConfirmation));

        BisqEasyTrade restoredTrade = BisqEasyTrade.fromProto(trade.toProto(false));
        new BisqEasyBuyerAsTakerProtocol(serviceProvider, restoredTrade).handle(fiatReceipt);

        assertBitcoinConfirmationApplied(restoredTrade);
    }

    @Test
    void appliedBitcoinConfirmationIsNotPendingAfterRestart() throws Exception {
        BisqEasyProtocol protocol = new BisqEasyBuyerAsTakerProtocol(serviceProvider, trade);
        protocol.handle(bitcoinConfirmation);
        protocol.handle(fiatReceipt);

        BisqEasyTrade restoredTrade = BisqEasyTrade.fromProto(trade.toProto(false));

        assertBitcoinConfirmationApplied(restoredTrade);
    }

    @ParameterizedTest
    @ValueSource(strings = {"trade", "sender", "receiver"})
    void restoredPendingMessageMustBelongToTrade(String changedBinding) throws Exception {
        var message = bitcoinConfirmation.getValueBuilder(false);
        switch (changedBinding) {
            case "trade" -> message.setTradeId("other-trade");
            case "sender" -> message.setSender(networkId("other-sender").toProto(false));
            case "receiver" -> message.setReceiver(networkId("other-receiver").toProto(false));
            default -> throw new IllegalArgumentException("Unknown binding");
        }
        var proto = trade.toProto(false).toBuilder();
        proto.getBisqEasyTradeBuilder().addPendingMessages(message);

        BisqEasyTrade restoredTrade = BisqEasyTrade.fromProto(proto.build());
        assertTrue(restoredTrade.getEventQueue().isEmpty());
        new BisqEasyBuyerAsTakerProtocol(serviceProvider, restoredTrade).handle(fiatReceipt);

        assertEquals(BUYER_RECEIVED_SELLERS_FIAT_RECEIPT_CONFIRMATION, restoredTrade.getState());
        assertTrue(restoredTrade.getPaymentProof().isEmpty());
    }

    @Test
    void malformedPendingMessageDoesNotPreventRestoringAnotherMessage() throws Exception {
        var proto = trade.toProto(false).toBuilder();
        proto.getBisqEasyTradeBuilder()
                .addPendingMessages(bisq.trade.protobuf.TradeMessage.getDefaultInstance())
                .addPendingMessages(bitcoinConfirmation.getValueBuilder(false));

        BisqEasyTrade restoredTrade = BisqEasyTrade.fromProto(proto.build());
        new BisqEasyBuyerAsTakerProtocol(serviceProvider, restoredTrade).handle(fiatReceipt);

        assertBitcoinConfirmationApplied(restoredTrade);
    }

    @Test
    void tradeWithoutPendingMessagesStillLoads() throws Exception {
        BisqEasyTrade restoredTrade = BisqEasyTrade.fromProto(trade.toProto(false));

        assertEquals(BUYER_SENT_FIAT_SENT_CONFIRMATION, restoredTrade.getState());
        assertTrue(restoredTrade.getEventQueue().isEmpty());
    }

    @Test
    void persistenceWaitsForDeferredMessageAndStateToCommitTogether() throws Exception {
        BisqEasyProtocol protocol = new BisqEasyBuyerAsTakerProtocol(serviceProvider, trade);
        protocol.handle(bitcoinConfirmation);
        CountDownLatch proofCommitted = new CountDownLatch(1);
        CountDownLatch finishTransition = new CountDownLatch(1);
        trade.paymentProofObservable().addObserver(proof -> {
            if (proof.isPresent()) {
                proofCommitted.countDown();
                try {
                    assertTrue(finishTransition.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        });

        CompletableFuture<bisq.trade.protobuf.Trade> snapshot = new CompletableFuture<>();
        Thread handlingThread = new Thread(() -> protocol.handle(fiatReceipt));
        Thread persistingThread = new Thread(() -> {
            try {
                snapshot.complete(trade.toProto(false));
            } catch (Throwable throwable) {
                snapshot.completeExceptionally(throwable);
            }
        });
        try {
            handlingThread.start();
            assertTrue(proofCommitted.await(10, TimeUnit.SECONDS));
            persistingThread.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (persistingThread.getState() != Thread.State.BLOCKED && persistingThread.isAlive()) {
                assertTrue(System.nanoTime() < deadline, "Persistence did not wait for the trade transition");
                Thread.sleep(1);
            }
            assertEquals(Thread.State.BLOCKED, persistingThread.getState());
        } finally {
            finishTransition.countDown();
            handlingThread.join(TimeUnit.SECONDS.toMillis(10));
            persistingThread.join(TimeUnit.SECONDS.toMillis(10));
        }

        assertFalse(handlingThread.isAlive());
        assertFalse(persistingThread.isAlive());
        assertBitcoinConfirmationApplied(BisqEasyTrade.fromProto(snapshot.get(10, TimeUnit.SECONDS)));
    }

    private static void assertBitcoinConfirmationApplied(BisqEasyTrade trade) {
        assertEquals(BUYER_RECEIVED_BTC_SENT_CONFIRMATION, trade.getState());
        assertEquals(Optional.of("payment-proof"), trade.getPaymentProof());
        assertTrue(trade.getEventQueue().isEmpty());
    }
}
