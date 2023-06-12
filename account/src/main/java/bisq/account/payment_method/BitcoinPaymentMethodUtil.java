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

package bisq.account.payment_method;

import bisq.account.protocol_type.TradeProtocolType;

import java.util.List;

public class BitcoinPaymentMethodUtil {
    public static BitcoinPaymentMethod getPaymentMethod(String name) {
        try {
            BitcoinPaymentRail bitcoinPaymentRail = BitcoinPaymentRail.valueOf(name);
            BitcoinPaymentMethod bitcoinPaymentMethod = BitcoinPaymentMethod.fromPaymentRail(bitcoinPaymentRail);
            if (!bitcoinPaymentMethod.isCustomPaymentMethod()) {
                return bitcoinPaymentMethod;
            }
        } catch (Throwable ignore) {
        }
        return BitcoinPaymentMethod.fromCustomName(name);
    }

    public static List<BitcoinPaymentRail> getPaymentRails() {
        return List.of(BitcoinPaymentRail.values());
    }

    public static List<BitcoinPaymentRail> getPaymentRails(TradeProtocolType protocolType) {
        switch (protocolType) {
            case BISQ_EASY:
                return getPaymentRails();               // Support any BTC rail 
            case BISQ_MULTISIG:
            case MONERO_SWAP:
            case BSQ_SWAP:
                return List.of(BitcoinPaymentRail.MAIN_CHAIN);    // Require BTC main chain
            case LIGHTNING_X:
                return List.of(BitcoinPaymentRail.LN);
            case LIQUID_SWAP:
                throw new IllegalArgumentException("No paymentMethods for that protocolType");
            default:
                throw new RuntimeException("Not handled case: protocolType=" + protocolType);
        }
    }
}