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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.oracle_node;

import bisq.network.p2p.services.data.storage.DataStorageResult;
import bisq.network.p2p.services.data.storage.DataStore;
import bisq.network.p2p.services.data.storage.PruneExpiredEntriesService;
import bisq.network.p2p.services.data.storage.auth.AddAuthenticatedDataRequest;
import bisq.network.p2p.services.data.storage.auth.AuthenticatedData;
import bisq.network.p2p.services.data.storage.auth.AuthenticatedDataRequest;
import bisq.network.p2p.services.data.storage.auth.AuthenticatedDataStorageService;
import bisq.network.p2p.services.data.storage.auth.RemoveAuthenticatedDataRequest;
import bisq.network.p2p.services.data.storage.auth.authorized.AuthorizedData;
import bisq.persistence.Persistence;
import bisq.persistence.PersistenceService;
import bisq.security.DigestUtil;
import bisq.security.SignatureUtil;
import bisq.security.keys.KeyGeneration;
import bisq.user.reputation.data.AuthorizedTimestampData;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the behavior of the authenticated data store (storage key V1) on which oracle and seed nodes rely when they
 * move the publisher of their authorized data to the authorized key pair (see
 * docs/specifications/network/authorized-data-publisher.md). A node broadcasts a request only if its store accepts it.
 */
class AuthorizedDataPublisherReplacementTest {
    private final KeyPair authorizedKeyPair = KeyGeneration.generateDefaultEcKeyPair();
    private final KeyPair identityKeyPair = KeyGeneration.generateDefaultEcKeyPair();
    private final AuthorizedTimestampData data = new AuthorizedTimestampData("0123456789abcdef0123456789abcdef01234567",
            System.currentTimeMillis(),
            false);

    @Test
    void publicationWithTheAuthorizedKeyPairReplacesTheEntryOfTheIdentityKeyPair() throws GeneralSecurityException {
        AuthenticatedDataStorageService store = createStore();
        List<AuthenticatedData> added = new ArrayList<>();
        List<AuthenticatedData> removed = new ArrayList<>();
        store.addListener(new AuthenticatedDataStorageService.Listener() {
            @Override
            public void onAdded(AuthenticatedData authenticatedData) {
                added.add(authenticatedData);
            }

            @Override
            public void onRemoved(AuthenticatedData authenticatedData) {
                removed.add(authenticatedData);
            }
        });
        assertThat(store.add(addRequest(store, identityKeyPair)).isSuccess()).isTrue();

        AddAuthenticatedDataRequest publicationWithAuthorizedKeyPair = addRequest(store, authorizedKeyPair);
        DataStorageResult result = store.add(publicationWithAuthorizedKeyPair);

        assertThat(result.isSuccess()).isTrue();
        assertThat(publicationWithAuthorizedKeyPair.getSequenceNumber()).isEqualTo(2);
        assertThat(store.getPersistableStore().getMap().values()).containsExactly(publicationWithAuthorizedKeyPair);
        assertThat(publicationWithAuthorizedKeyPair.getAuthenticatedSequentialData().getPubKeyHash())
                .isEqualTo(DigestUtil.hash(authorizedKeyPair.getPublic().getEncoded()));
        assertThat(added).hasSize(2);
        assertThat(removed).isEmpty();
    }

    @Test
    void storeAcceptsOnlyTheRemovalOfThePublisherOfItsEntry() throws GeneralSecurityException {
        AuthenticatedDataStorageService store = createStore();
        store.add(addRequest(store, identityKeyPair));

        DataStorageResult removalWithAuthorizedKeyPair = store.remove(removeRequest(store, authorizedKeyPair));
        DataStorageResult removalWithIdentityKeyPair = store.remove(removeRequest(store, identityKeyPair));

        assertThat(removalWithAuthorizedKeyPair.isSuccess()).isFalse();
        assertThat(removalWithAuthorizedKeyPair.isPublicKeyHashInvalid()).isTrue();
        assertThat(removalWithIdentityKeyPair.isSuccess()).isTrue();
        assertThat(removalWithIdentityKeyPair.getRemovedData()).isNotNull();
    }

    // Oracle nodes remove a bonded role first with the authorized and then with the identity key pair
    @Test
    void afterTheReplacementBothRemovalsAreAcceptedAndReachNodesWhichMissedTheReplacement()
            throws GeneralSecurityException {
        AuthenticatedDataStorageService store = createStore();
        AuthenticatedDataStorageService storeOfNodeWhichMissedTheReplacement = createStore();
        AddAuthenticatedDataRequest publicationWithIdentityKeyPair = addRequest(store, identityKeyPair);
        store.add(publicationWithIdentityKeyPair);
        storeOfNodeWhichMissedTheReplacement.add(publicationWithIdentityKeyPair);
        store.add(addRequest(store, authorizedKeyPair));

        RemoveAuthenticatedDataRequest removalWithAuthorizedKeyPair = removeRequest(store, authorizedKeyPair);
        DataStorageResult resultWithAuthorizedKeyPair = store.remove(removalWithAuthorizedKeyPair);
        RemoveAuthenticatedDataRequest removalWithIdentityKeyPair = removeRequest(store, identityKeyPair);
        DataStorageResult resultWithIdentityKeyPair = store.remove(removalWithIdentityKeyPair);

        assertThat(resultWithAuthorizedKeyPair.isSuccess()).isTrue();
        assertThat(resultWithAuthorizedKeyPair.getRemovedData()).isNotNull();
        assertThat(resultWithIdentityKeyPair.isSuccess()).isTrue();
        assertThat(resultWithIdentityKeyPair.isAlreadyRemoved()).isTrue();
        assertThat(store.getPersistableStore().getMap().values()).containsExactly(removalWithIdentityKeyPair);

        assertThat(storeOfNodeWhichMissedTheReplacement.remove(removalWithAuthorizedKeyPair).isSuccess()).isFalse();
        assertThat(storeOfNodeWhichMissedTheReplacement.remove(removalWithIdentityKeyPair).getRemovedData()).isNotNull();
    }

    // As NetworkService.publishAuthorizedData
    private AddAuthenticatedDataRequest addRequest(AuthenticatedDataStorageService store, KeyPair publisherKeyPair)
            throws GeneralSecurityException {
        byte[] authorizationSignature = SignatureUtil.sign(data.serializeForHash(), authorizedKeyPair.getPrivate());
        AuthorizedData authorizedData = new AuthorizedData(data,
                Optional.of(authorizationSignature),
                authorizedKeyPair.getPublic());
        return AddAuthenticatedDataRequest.from(store, authorizedData, publisherKeyPair);
    }

    // As NetworkService.removeAuthorizedData
    private RemoveAuthenticatedDataRequest removeRequest(AuthenticatedDataStorageService store,
                                                         KeyPair publisherKeyPair) throws GeneralSecurityException {
        AuthorizedData authorizedData = new AuthorizedData(data, authorizedKeyPair.getPublic());
        return RemoveAuthenticatedDataRequest.from(store, authorizedData, publisherKeyPair);
    }

    @SuppressWarnings("unchecked")
    private static AuthenticatedDataStorageService createStore() {
        Persistence<DataStore<AuthenticatedDataRequest>> persistence = mock(Persistence.class);
        when(persistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(persistence.getStorePath()).thenReturn(Path.of("authorized_timestamp_data_store.protobuf"));
        PersistenceService persistenceService = mock(PersistenceService.class);
        when(persistenceService.<DataStore<AuthenticatedDataRequest>>getOrCreatePersistence(any(),
                any(Path.class),
                any(),
                any(),
                any()))
                .thenReturn(persistence);
        return new AuthenticatedDataStorageService(persistenceService,
                new PruneExpiredEntriesService(),
                "authenticated_data_store",
                "AuthorizedTimestampData");
    }
}
