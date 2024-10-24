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

package bisq.wallets.bitcoind;

import bisq.persistence.Persistence;
import bisq.persistence.PersistenceService;
import bisq.wallets.core.RpcConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class BitcoinWalletService extends AbstractBitcoindWalletService<BitcoinWallet, BitcoinWalletStore> {

    @Getter
    private final BitcoinWalletStore persistableStore = new BitcoinWalletStore();
    @Getter
    private final Persistence<BitcoinWalletStore> persistence;

    public BitcoinWalletService(PersistenceService persistenceService,
                                boolean isRegtest) {
        super("BTC", getOptionalRegtestConfig(isRegtest, 18443), "bisq_bitcoind_default_wallet");
        persistence = persistenceService.getOrCreatePersistence(this, persistableStore);
    }

    @Override
    protected BitcoinWallet createWallet(RpcConfig rpcConfig) {
        return WalletFactory.createBitcoinWallet(rpcConfig, walletName, persistableStore);
    }


    @Override
    protected void persistRpcConfig(RpcConfig rpcConfig) {
        persistableStore.setRpcConfig(Optional.of(rpcConfig));
        persist();
    }

    @Override
    protected Optional<RpcConfig> getRpcConfigFromPersistableStore() {
        return persistableStore.getRpcConfig();
    }
}
