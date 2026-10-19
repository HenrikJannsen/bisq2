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
import bisq.persistence.Persistence;
import bisq.persistence.backup.MaxBackupSize;
import bisq.persistence.backup.RestoreService;
import bisq.trade.bisq_easy.protocol.BisqEasyClosedTrade;
import bisq.trade.bisq_easy.protocol.BisqEasyProtocol;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyBtcAddressMessage;
import com.google.protobuf.Any;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static bisq.trade.bisq_easy.BisqEasyTradeTestData.QUOTE_AMOUNT;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.contract;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.identity;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.networkId;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.offer;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.sellerAsMakerTrade;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.userProfile;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

class BisqEasyTradeStoreSnapshotTest {
    @TempDir
    Path tempDir;

    @Test
    void capturedSnapshotKeepsTradeStateAndPendingMessagesAfterLaterMutations() throws Exception {
        BisqEasyTrade trade = trade("trade");
        BisqEasyTradeStore store = new BisqEasyTradeStore();
        store.addTrade(trade);
        BisqEasyBtcAddressMessage pending = new BisqEasyBtcAddressMessage("pending", trade.getId(),
                BisqEasyProtocol.VERSION, trade.getPeer().getNetworkId(), trade.getMyself().getNetworkId(),
                "address", trade.getOffer());
        store.getPendingMessages().add(pending);
        Any snapshot = store.captureSnapshot();

        trade.setPaymentProof(Optional.of("later-proof"));
        store.getPendingMessages().remove(pending);
        store.addTrade(trade("later-trade"));

        BisqEasyTradeStore restored = BisqEasyTradeStore.fromProto(
                snapshot.unpack(bisq.trade.protobuf.BisqEasyTradeStore.class));
        assertEquals(1, restored.getTrades().size());
        assertTrue(restored.findTrade(trade.getId()).orElseThrow().getPaymentProof().isEmpty());
        assertEquals(List.of(pending), restored.getPendingMessages().getMessagesInArrivalOrder());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializationFailureCannotSaveAStoreWithAMissingTrade(boolean closed) throws Exception {
        BisqEasyTradeStore store = new BisqEasyTradeStore();
        BisqEasyTrade failingTrade = spy(trade("failing-trade"));
        store.addTrade(trade("valid-trade"));
        if (closed) {
            store.getClosedTrades().add(new BisqEasyClosedTrade(failingTrade,
                    userProfile(failingTrade.getMyself().getNetworkId()),
                    userProfile(failingTrade.getPeer().getNetworkId())));
        } else {
            store.addTrade(failingTrade);
        }
        Persistence<BisqEasyTradeStore> persistence = new Persistence<>(tempDir.resolve("db/private"),
                "BisqEasyTradeStore", MaxBackupSize.ZERO, new RestoreService());
        persistence.persistSnapshotAsync(store.captureSnapshot()).join();
        byte[] previous = Files.readAllBytes(persistence.getStorePath());
        IllegalStateException failure = new IllegalStateException("cannot serialize trade");
        doThrow(failure).when(failingTrade).toProto(false);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> persistence.persistSnapshotAsync(store.captureSnapshot()).join()));

        assertArrayEquals(previous, Files.readAllBytes(persistence.getStorePath()));
    }

    private static BisqEasyTrade trade(String id) {
        Identity maker = identity(id + "-maker");
        return sellerAsMakerTrade(contract(offer(id, maker.getNetworkId(), QUOTE_AMOUNT),
                networkId(id + "-taker"), Optional.empty()), maker);
    }
}
