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

import bisq.common.observable.Observable;
import bisq.contract.ContractService;
import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.identity.Identity;
import bisq.security.SecurityService;
import bisq.trade.ServiceProvider;
import bisq.trade.bisq_easy.protocol.BisqEasyProtocol;
import bisq.trade.bisq_easy.protocol.BisqEasySellerAsMakerProtocol;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyBtcAddressMessage;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyTakeOfferRequest;
import bisq.trade.protocol.messages.PendingTradeMessages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static bisq.trade.bisq_easy.BisqEasyTradeTestData.QUOTE_AMOUNT;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.contract;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.identity;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.offer;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.sellerAsMakerTrade;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class BisqEasyTradeStorePendingMessagesTest {
    @Test
    void snapshotKeepsMessageWhenProtocolConsumesItAfterItsTradeWasSerialized() throws Exception {
        Fixture fixture = new Fixture();
        BisqEasyBtcAddressMessage message = fixture.message("pending-address");
        fixture.store.getPendingMessages().add(message);
        CountDownLatch tradeSerialized = new CountDownLatch(1);
        CountDownLatch finishSnapshot = new CountDownLatch(1);
        doAnswer(invocation -> {
            bisq.trade.protobuf.Trade serialized = (bisq.trade.protobuf.Trade) invocation.callRealMethod();
            tradeSerialized.countDown();
            assertThat(finishSnapshot.await(5, TimeUnit.SECONDS)).isTrue();
            return serialized;
        }).when(fixture.trade).toProto(false);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<bisq.trade.protobuf.BisqEasyTradeStore> snapshot = executor.submit(() -> fixture.store.toProto(false));
            assertThat(tradeSerialized.await(5, TimeUnit.SECONDS)).isTrue();

            fixture.protocol.handle(message);
            assertThat(fixture.trade.getBitcoinPaymentData()).contains("bitcoin-address");
            assertThat(fixture.store.getPendingMessages().remove(message)).isTrue();
            finishSnapshot.countDown();

            BisqEasyTradeStore restored = BisqEasyTradeStore.fromProto(snapshot.get(5, TimeUnit.SECONDS));
            assertThat(restored.findTrade(fixture.trade.getId()).orElseThrow().getBitcoinPaymentData()).isEmpty();
            assertThat(restored.getPendingMessages().getMessagesInArrivalOrder()).containsExactly(message);
        } finally {
            finishSnapshot.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cloneKeepsIndependentPendingMessagesInArrivalOrder() throws Exception {
        Fixture fixture = new Fixture();
        BisqEasyBtcAddressMessage first = fixture.message("first-address");
        BisqEasyBtcAddressMessage second = fixture.message("second-address");
        fixture.store.getPendingMessages().add(first);
        fixture.store.getPendingMessages().add(second);

        BisqEasyTradeStore clone = fixture.store.getClone();
        fixture.store.getPendingMessages().remove(first);
        fixture.store.getPendingMessages().add(fixture.message("third-address"));

        BisqEasyTradeStore restored = BisqEasyTradeStore.fromProto(clone.toProto(false));
        assertThat(restored.getPendingMessages().getMessagesInArrivalOrder()).containsExactly(first, second);
    }

    @Test
    void restoringPendingMessagesSkipsInvalidEntriesAndKeepsTheExistingBound() throws Exception {
        Fixture fixture = new Fixture();
        bisq.trade.protobuf.BisqEasyTradeStore.Builder builder = bisq.trade.protobuf.BisqEasyTradeStore.newBuilder();
        builder.addPendingMessages(bisq.trade.protobuf.TradeMessage.getDefaultInstance());
        for (int index = 0; index <= PendingTradeMessages.MAX_PENDING_MESSAGES; index++) {
            builder.addPendingMessages(fixture.message("address-" + index).getValueBuilder(false));
        }

        BisqEasyTradeStore restored = BisqEasyTradeStore.fromProto(builder.build());

        List<String> restoredIds = restored.getPendingMessages().getMessagesInArrivalOrder().stream()
                .map(message -> message.getId()).toList();
        assertThat(restoredIds).hasSize(PendingTradeMessages.MAX_PENDING_MESSAGES);
        assertThat(restoredIds.getFirst()).isEqualTo("address-1");
        assertThat(restoredIds.getLast()).isEqualTo("address-100");
    }

    @Test
    void applyingPersistedStorePreservesPendingQueueIdentity() throws Exception {
        Fixture fixture = new Fixture();
        var queue = fixture.store.getPendingMessages();
        queue.add(fixture.message("old-message"));
        BisqEasyTradeStore persisted = new BisqEasyTradeStore();
        BisqEasyBtcAddressMessage restoredMessage = fixture.message("restored-message");
        persisted.getPendingMessages().add(restoredMessage);

        fixture.store.applyPersisted(persisted);

        assertThat(fixture.store.getPendingMessages()).isSameAs(queue);
        assertThat(queue.getMessagesInArrivalOrder()).containsExactly(restoredMessage);
    }

    private static class Fixture {
        private final BisqEasyTradeStore store = new BisqEasyTradeStore();
        private final BisqEasyTrade trade;
        private final BisqEasyProtocol protocol;

        private Fixture() throws Exception {
            ServiceProvider serviceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
            Identity maker = identity("maker");
            Identity taker = identity("taker");
            BisqEasyContract contract = contract(offer("offer-id", maker.getNetworkId(), QUOTE_AMOUNT),
                    taker.getNetworkId(), Optional.empty());
            ContractService contractService = new ContractService(mock(SecurityService.class));
            when(serviceProvider.getContractService()).thenReturn(contractService);
            when(serviceProvider.getSettingsService().getCloseMyOfferWhenTaken()).thenReturn(new Observable<>(false));
            trade = spy(sellerAsMakerTrade(contract, maker));
            protocol = new BisqEasySellerAsMakerProtocol(serviceProvider, trade);
            trade.setProtocolVersion(protocol.getVersion());
            protocol.handle(new BisqEasyTakeOfferRequest("take-request", trade.getId(), BisqEasyProtocol.VERSION,
                    taker.getNetworkId(), maker.getNetworkId(), contract,
                    contractService.signContract(contract, taker.getKeyBundle().getKeyPair())));
            store.addTrade(trade);
        }

        private BisqEasyBtcAddressMessage message(String id) {
            return new BisqEasyBtcAddressMessage(id, trade.getId(), BisqEasyProtocol.VERSION,
                    trade.getPeer().getNetworkId(), trade.getMyself().getNetworkId(), "bitcoin-address", trade.getOffer());
        }
    }
}
