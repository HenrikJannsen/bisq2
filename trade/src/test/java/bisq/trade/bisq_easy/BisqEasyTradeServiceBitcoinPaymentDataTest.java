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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.util.Optional;

import static bisq.trade.bisq_easy.BisqEasyTradeTestData.QUOTE_AMOUNT;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.buyerAsTakerTrade;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.contract;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.identity;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.offer;
import static bisq.trade.bisq_easy.BisqEasyTradeServiceTestSupport.assertFutureFailure;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * The buyer does not send bitcoin payment data which requests another amount than the trade amount, as the seller's
 * node would fail the trade (docs/specifications/trade/bisq-easy-bitcoin-payment-data.md). The trade amount is
 * 0.001 BTC.
 */
class BisqEasyTradeServiceBitcoinPaymentDataTest {
    private static final String ADDRESS = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";

    private final BisqEasyTradeService service =
            BisqEasyTradeServiceTestSupport.service(mock(ServiceProvider.class, RETURNS_DEEP_STUBS));
    private final Identity buyerIdentity = identity("buyer");
    private final BisqEasyTrade trade = buyerAsTakerTrade(
            contract(offer("offer-id", identity("seller").getNetworkId(), QUOTE_AMOUNT), buyerIdentity.getNetworkId(),
                    Optional.empty()),
            buyerIdentity);

    @BeforeEach
    void setUp() {
        service.getPersistableStore().addTrade(trade);
        service.initialize().join();
    }

    @AfterEach
    void tearDown() {
        service.shutdown().join();
    }

    @Test
    void dataForAnotherAmountIsNotSent() {
        assertFutureFailure(IllegalArgumentException.class,
                () -> service.buyerSendBitcoinPaymentData(service.findTrade(trade.getId()).orElseThrow(),
                        "bitcoin:" + ADDRESS + "?amount=0.01").join());

        assertTrue(trade.getBitcoinPaymentData().isEmpty());
    }

    @Test
    void addressPassesTheCheck() {
        assertDoesNotThrow(() -> service.buyerSendBitcoinPaymentData(
                service.findTrade(trade.getId()).orElseThrow(), ADDRESS).join());
    }
}
