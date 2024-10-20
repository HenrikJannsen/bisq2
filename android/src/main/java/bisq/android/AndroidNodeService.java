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

package bisq.android;

import bisq.common.application.Service;
import bisq.common.encoding.Hex;
import bisq.persistence.PersistenceService;
import bisq.security.SecurityService;
import bisq.security.keys.KeyBundleService;
import lombok.extern.slf4j.Slf4j;

import java.security.KeyPair;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class AndroidNodeService implements Service {
    private final PersistenceService persistenceService;
    private final SecurityService securityService;

    public AndroidNodeService(PersistenceService persistenceService, SecurityService securityService) {
        this.persistenceService = persistenceService;
        this.securityService = securityService;
    }

    @Override
    public CompletableFuture<Boolean> initialize() {
        KeyBundleService keyBundleService = securityService.getKeyBundleService();
        String defaultKeyId = keyBundleService.getDefaultKeyId();
        KeyPair keyPair = keyBundleService.getOrCreateKeyBundle(defaultKeyId).getKeyPair();

        log.info("defaultKeyId={}", defaultKeyId);
        log.info("default pub key as hex={}", Hex.encode(keyPair.getPublic().getEncoded()));
        return CompletableFuture.completedFuture(true);
    }


    @Override
    public CompletableFuture<Boolean> shutdown() {
        return CompletableFuture.completedFuture(true);
    }
}