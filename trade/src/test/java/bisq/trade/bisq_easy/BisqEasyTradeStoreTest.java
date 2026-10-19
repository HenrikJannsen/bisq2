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
import bisq.trade.bisq_easy.protocol.BisqEasyClosedTrade;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static bisq.trade.bisq_easy.BisqEasyTradeTestData.QUOTE_AMOUNT;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.buyerAsTakerTrade;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.contract;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.identity;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.offer;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.userProfile;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A write of the trade store reads the copies of the trades which the tasks of the trades took when they persisted
 * them, not the live trades (docs/specifications/trade/trade-task-execution.md).
 */
class BisqEasyTradeStoreTest {
    private final Identity taker = identity("taker");
    private final BisqEasyTrade trade = buyerAsTakerTrade(
            contract(offer("offer-id", identity("maker").getNetworkId(), QUOTE_AMOUNT), taker.getNetworkId(), Optional.empty()),
            taker);
    private final BisqEasyTradeStore store = new BisqEasyTradeStore();

    @Test
    void writeContainsTheTradeAsItWasPersistedLast() {
        store.addTrade(trade);
        trade.setPaymentAccountData(Optional.of("account data"));

        assertThat(onlyTrade(store.getClone()).getPaymentAccountData()).isEmpty();

        store.updateCopyOfTrade(trade.getId());

        assertThat(onlyTrade(store.getClone()).getPaymentAccountData()).contains("account data");
    }

    @Test
    void writeDoesNotContainTheLiveTrade() {
        store.addTrade(trade);

        assertThat(onlyTrade(store.getClone())).isNotSameAs(trade);
    }

    @Test
    void closedTradeIsWrittenAsClosedTrade() {
        store.addTrade(trade);
        store.getTrades().remove(trade);
        store.getClosedTrades().add(new BisqEasyClosedTrade(trade, userProfile(taker.getNetworkId()),
                userProfile(identity("maker").getNetworkId())));

        store.updateCopyOfTrade(trade.getId());

        BisqEasyTradeStore clone = store.getClone();
        assertThat(clone.getTrades()).isEmpty();
        assertThat(clone.getClosedTrades()).hasSize(1);
        assertThat(clone.getTradeIds()).containsExactly(trade.getId());
    }

    @Test
    void deletedTradeIsNotWritten() {
        BisqEasyClosedTrade closedTrade = new BisqEasyClosedTrade(trade, userProfile(taker.getNetworkId()),
                userProfile(identity("maker").getNetworkId()));
        store.applyPersisted(new BisqEasyTradeStore(Set.of(), Set.of(trade.getId()), Set.of(closedTrade)));
        store.getClosedTrades().remove(closedTrade);

        store.updateCopyOfTrade(trade.getId());

        assertThat(store.getClone().getClosedTrades()).isEmpty();
    }

    @Test
    void storedTradesAreCopiedWhenTheyAreApplied() {
        store.applyPersisted(new BisqEasyTradeStore(Set.of(trade), Set.of(trade.getId()), Set.of()));
        trade.setPaymentAccountData(Optional.of("account data"));

        BisqEasyTrade written = onlyTrade(store.getClone());
        assertThat(written).isNotSameAs(trade);
        assertThat(written.getPaymentAccountData()).isEmpty();
    }

    private static BisqEasyTrade onlyTrade(BisqEasyTradeStore clone) {
        assertThat(clone.getTrades()).hasSize(1);
        return clone.getTrades().iterator().next();
    }
}
