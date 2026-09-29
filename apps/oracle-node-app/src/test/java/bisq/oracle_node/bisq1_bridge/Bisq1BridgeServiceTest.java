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

package bisq.oracle_node.bisq1_bridge;

import bisq.bonded_roles.bonded_role.AuthorizedBondedRole;
import bisq.bonded_roles.bonded_role.AuthorizedBondedRolesService;
import bisq.bonded_roles.oracle.AuthorizedOracleNode;
import bisq.bonded_roles.registration.BondedRoleRegistrationRequest;
import bisq.common.data.ByteArray;
import bisq.common.encoding.Hex;
import bisq.identity.IdentityService;
import bisq.network.NetworkService;
import bisq.network.p2p.services.data.BroadcastResult;
import bisq.network.p2p.services.data.DataService;
import bisq.network.p2p.services.data.storage.StorageService;
import bisq.network.p2p.services.data.storage.auth.AddAuthenticatedDataRequest;
import bisq.network.p2p.services.data.storage.auth.AuthenticatedDataRequest;
import bisq.network.p2p.services.data.storage.auth.AuthenticatedDataStorageService;
import bisq.network.p2p.services.data.storage.auth.AuthenticatedSequentialData;
import bisq.network.p2p.services.data.storage.auth.RemoveAuthenticatedDataRequest;
import bisq.network.p2p.services.data.storage.auth.authorized.AuthorizedData;
import bisq.network.p2p.services.data.storage.auth.authorized.AuthorizedDistributedData;
import bisq.persistence.PersistenceService;
import bisq.security.DigestUtil;
import bisq.security.SignatureUtil;
import bisq.security.keys.KeyGeneration;
import bisq.user.reputation.data.AuthorizedTimestampData;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static bisq.oracle_node.TestBondedRoleRegistrations.createCurrentRequest;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Bisq1BridgeServiceTest {
    private static final String PROFILE_ID = "0123456789abcdef0123456789abcdef01234567";

    private final KeyPair authorizedKeyPair = KeyGeneration.generateDefaultEcKeyPair();
    private final KeyPair identityKeyPair = KeyGeneration.generateDefaultEcKeyPair();

    // Earlier versions published our authorized data with the key pair of our network identity. We publish it once
    // again with our authorized key pair (see docs/specifications/network/authorized-data-publisher.md).
    @Test
    void publishesOurAuthorizedDataWhichAnotherKeyPublishedOnceAgainWithTheAuthorizedKeyPair() throws Exception {
        long now = System.currentTimeMillis();
        AuthorizedTimestampData dataOfIdentityKeyPair = timestampData(now - 1);
        AuthorizedTimestampData dataOfAuthorizedKeyPair = timestampData(now - 2);
        AuthorizedTimestampData expiredData = timestampData(now - 3);
        AuthorizedTimestampData dataOfAnotherOracleNode = timestampData(now - 4);
        AuthorizedTimestampData removedData = timestampData(now - 5);
        KeyPair authorizedKeyPairOfAnotherOracleNode = KeyGeneration.generateDefaultEcKeyPair();
        KeyPair identityKeyPairOfAnotherOracleNode = KeyGeneration.generateDefaultEcKeyPair();
        BondedRoleRegistrationRequest registration = createCurrentRequest();
        AuthorizedOracleNode authorizedOracleNode = new AuthorizedOracleNode(registration.getNetworkId(),
                PROFILE_ID,
                Hex.encode(authorizedKeyPair.getPublic().getEncoded()),
                "bond-user",
                "signature",
                false);
        AuthorizedBondedRole authorizedBondedRole = new AuthorizedBondedRole(registration.getProfileId(),
                registration.getAuthorizedPublicKey(),
                registration.getBondedRoleType(),
                registration.getBondUserName(),
                registration.getSignatureBase64(),
                registration.getAddressByTransportTypeMap(),
                registration.getNetworkId(),
                Optional.empty(),
                false,
                registration.getRegistrationProtocolVersion(),
                registration.getProposalTxId(),
                registration.getLockupTxId());
        long expiredCreationDate = now - AuthorizedTimestampData.TTL - TimeUnit.MINUTES.toMillis(1);
        AuthenticatedDataStorageService store = mock(AuthenticatedDataStorageService.class);
        List<AuthenticatedDataRequest> requests = List.of(
                addRequest(dataOfIdentityKeyPair, authorizedKeyPair, identityKeyPair, now),
                addRequest(dataOfAuthorizedKeyPair, authorizedKeyPair, authorizedKeyPair, now),
                addRequest(expiredData, authorizedKeyPair, identityKeyPair, expiredCreationDate),
                addRequest(dataOfAnotherOracleNode,
                        authorizedKeyPairOfAnotherOracleNode,
                        identityKeyPairOfAnotherOracleNode,
                        now),
                // AuthorizedOracleNode names the key of our network ID as its owner
                addRequest(authorizedOracleNode, authorizedKeyPair, identityKeyPair, now),
                // republishAuthorizedBondedRoles publishes our bonded roles again, except banned roles
                addRequest(authorizedBondedRole, authorizedKeyPair, identityKeyPair, now),
                RemoveAuthenticatedDataRequest.from(store,
                        new AuthorizedData(removedData, authorizedKeyPair.getPublic()),
                        identityKeyPair));
        Map<ByteArray, AuthenticatedDataRequest> storeMap = new HashMap<>();
        requests.forEach(request -> storeMap.put(new ByteArray(DigestUtil.hash(request.serializeForHash())), request));
        NetworkService networkService = networkServiceWithStoreMap(storeMap);
        Bisq1BridgeService service = createService(networkService);

        service.queueDataForPublisherMigration();
        service.maybePublish();
        service.maybePublish();

        verify(networkService).publishAuthorizedData(eq(dataOfIdentityKeyPair),
                argThat((KeyPair keyPair) -> keyPair.getPublic().equals(authorizedKeyPair.getPublic())));
        verify(networkService, times(1)).publishAuthorizedData(any(), any());
        verify(networkService, never()).publishAuthorizedData(any(), any(), any(), any());
    }

    private static AuthorizedTimestampData timestampData(long date) {
        return new AuthorizedTimestampData(PROFILE_ID, date, false);
    }

    // Builds the add request as NetworkService.publishAuthorizedData does, but with the given creation date
    private static AddAuthenticatedDataRequest addRequest(AuthorizedDistributedData data,
                                                          KeyPair authorizedKeyPair,
                                                          KeyPair publisherKeyPair,
                                                          long created) throws GeneralSecurityException {
        byte[] authorizationSignature = SignatureUtil.sign(data.serializeForHash(), authorizedKeyPair.getPrivate());
        AuthorizedData authorizedData = new AuthorizedData(data,
                Optional.of(authorizationSignature),
                authorizedKeyPair.getPublic());
        AuthenticatedSequentialData sequentialData = new AuthenticatedSequentialData(authorizedData,
                1,
                DigestUtil.hash(publisherKeyPair.getPublic().getEncoded()),
                created);
        byte[] signature = SignatureUtil.sign(sequentialData.serializeForHash(), publisherKeyPair.getPrivate());
        return new AddAuthenticatedDataRequest(sequentialData, signature, publisherKeyPair.getPublic());
    }

    private static NetworkService networkServiceWithStoreMap(Map<ByteArray, AuthenticatedDataRequest> storeMap) {
        StorageService storageService = mock(StorageService.class);
        when(storageService.getAuthenticatedDataStoreMaps()).thenAnswer(invocation -> Stream.of(storeMap));
        DataService dataService = mock(DataService.class);
        when(dataService.getStorageService()).thenReturn(storageService);
        NetworkService networkService = mock(NetworkService.class);
        when(networkService.getDataService()).thenReturn(Optional.of(dataService));
        when(networkService.publishAuthorizedData(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new BroadcastResult()));
        return networkService;
    }

    private Bisq1BridgeService createService(NetworkService networkService) {
        return new Bisq1BridgeService(new Bisq1BridgeService.Config(50051, 120, 1, 8, false, true),
                mock(PersistenceService.class),
                mock(IdentityService.class),
                networkService,
                mock(AuthorizedBondedRolesService.class),
                authorizedKeyPair.getPrivate(),
                authorizedKeyPair.getPublic(),
                false,
                mock(AuthorizedOracleNode.class));
    }
}
