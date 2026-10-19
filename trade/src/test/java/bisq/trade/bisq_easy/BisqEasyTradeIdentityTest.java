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
import bisq.trade.ServiceProvider;
import bisq.trade.bisq_easy.protocol.BisqEasyBuyerAsTakerProtocol;
import bisq.trade.bisq_easy.protocol.BisqEasyClosedTrade;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyConfirmFiatReceiptMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static bisq.trade.bisq_easy.BisqEasyTradeTestData.QUOTE_AMOUNT;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.buyerAsTakerTrade;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.contract;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.identity;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.inState;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.networkId;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.offer;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.userProfile;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.BUYER_RECEIVED_SELLERS_FIAT_RECEIPT_CONFIRMATION;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.BUYER_SENT_FIAT_SENT_CONFIRMATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

class BisqEasyTradeIdentityTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stateAndPaymentChangesDoNotPreventRemovingActiveOrClosedTrade(boolean closed) {
        BisqEasyTrade trade = inState(trade("offer"), BUYER_SENT_FIAT_SENT_CONFIRMATION);
        BisqEasyTradeStore store = new BisqEasyTradeStore();
        BisqEasyClosedTrade archived = new BisqEasyClosedTrade(trade,
                userProfile(trade.getMyself().getNetworkId()), userProfile(trade.getPeer().getNetworkId()));
        if (closed) {
            store.getClosedTrades().add(archived);
        } else {
            store.addTrade(trade);
        }

        new BisqEasyBuyerAsTakerProtocol(mock(ServiceProvider.class, RETURNS_DEEP_STUBS), trade)
                .handle(new BisqEasyConfirmFiatReceiptMessage("receipt", trade.getId(), "1.0.0",
                        trade.getPeer().getNetworkId(), trade.getMyself().getNetworkId()));
        assertThat(trade.getState()).isEqualTo(BUYER_RECEIVED_SELLERS_FIAT_RECEIPT_CONFIRMATION);
        trade.setPaymentAccountData(Optional.of("changed-account"));
        trade.setBitcoinPaymentData(Optional.of("changed-address"));
        trade.setPaymentProof(Optional.of("changed-proof"));

        assertThat(closed ? store.getClosedTrades().remove(archived) : store.getTrades().remove(trade)).isTrue();
        assertThat(store.getAllTrades()).isEmpty();
    }

    @Test
    void identityDependsOnlyOnTradeIdWhileRoundTripChecksEveryPersistedField() throws Exception {
        BisqEasyTrade trade = trade("offer");
        BisqEasyTrade sameIdentity = BisqEasyTrade.fromProto(trade.toProto(false));
        int hashBeforeChanges = trade.hashCode();
        trade.setPaymentAccountData(Optional.of("account"));
        trade.setBitcoinPaymentData(Optional.of("address"));
        trade.setPaymentProof(Optional.of("proof"));
        trade.setProtocolVersion("1.0.0");

        assertThat(trade).isEqualTo(sameIdentity);
        assertThat(sameIdentity).isEqualTo(trade);
        assertThat(trade.hashCode()).isEqualTo(hashBeforeChanges);
        assertThat(trade).isNotEqualTo(trade("other-offer"));
        // Identity equality is insufficient to test serialization of mutable trade state.
        bisq.trade.protobuf.Trade proto = trade.toProto(false);
        assertThat(BisqEasyTrade.fromProto(proto).toProto(false)).isEqualTo(proto);
    }

    private static BisqEasyTrade trade(String offerId) {
        Identity taker = identity("taker");
        return buyerAsTakerTrade(contract(offer(offerId, networkId("maker"), QUOTE_AMOUNT),
                taker.getNetworkId(), Optional.empty()), taker);
    }
}
