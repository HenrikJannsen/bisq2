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

import bisq.contract.Role;
import bisq.trade.TradeLifecycleState;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import bisq.trade.exceptions.TradeProtocolFailure;

import javax.annotation.Nullable;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** A complete presentation state captured by the trade execution owner. */
public record BisqEasyTradeSnapshot(BisqEasyTradeState tradeState,
                                    @Nullable Role interruptTradeInitiator,
                                    Optional<String> paymentAccountData,
                                    Optional<String> bitcoinPaymentData,
                                    Optional<String> paymentProof,
                                    Optional<Long> tradeCompletedDate,
                                    @Nullable String errorMessage,
                                    @Nullable String errorStackTrace,
                                    @Nullable TradeProtocolFailure tradeProtocolFailure,
                                    @Nullable String peersErrorMessage,
                                    @Nullable String peersErrorStackTrace,
                                    @Nullable TradeProtocolFailure peersTradeProtocolFailure,
                                    @Nullable String protocolVersion,
                                    TradeLifecycleState lifecycleState) {
    public BisqEasyTradeSnapshot {
        requireNonNull(tradeState);
        requireNonNull(paymentAccountData);
        requireNonNull(bitcoinPaymentData);
        requireNonNull(paymentProof);
        requireNonNull(tradeCompletedDate);
        requireNonNull(lifecycleState);
    }

    static BisqEasyTradeSnapshot from(BisqEasyTrade trade) {
        return new BisqEasyTradeSnapshot(trade.getTradeState(), trade.getInterruptTradeInitiator().get(),
                trade.getPaymentAccountData(), trade.getBitcoinPaymentData(), trade.getPaymentProof(),
                trade.getTradeCompletedDate(), trade.getErrorMessage(), trade.getErrorStackTrace(),
                trade.getTradeProtocolFailure(), trade.getPeersErrorMessage(), trade.getPeersErrorStackTrace(),
                trade.getPeersTradeProtocolFailure(), trade.getProtocolVersion(), trade.getLifecycleState());
    }
}
