/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.trade.bisq_easy;

import bisq.account.payment_method.BitcoinPaymentMethod;
import bisq.account.payment_method.BitcoinPaymentMethodSpec;
import bisq.account.payment_method.BitcoinPaymentRail;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentMethodSpec;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.bonded_roles.release.AppType;
import bisq.common.market.Market;
import bisq.common.network.Address;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.identity.Identity;
import bisq.network.NetworkService;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.offer.Direction;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.bisq_easy.BisqEasyOffer;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.persistence.PersistenceService;
import bisq.security.keys.I2PKeyGeneration;
import bisq.security.keys.KeyBundle;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.security.keys.TorKeyGeneration;
import bisq.trade.ServiceProvider;
import bisq.trade.bisq_easy.protocol.BisqEasyProtocol;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyBtcAddressMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * A trade message which the network layer delivers while its trade's protocol is being created must reach the
 * protocol although it first sees no protocol. The network layer delivers the messages of a trade on several
 * threads: the take offer request of the taker can create the trade and its protocol on one thread while the
 * taker's next trade message arrives on another thread. If that message finds no protocol, the trade service
 * keeps it as a pending message. The request thread re-processes the pending messages once, after it created
 * the protocol. A message whose delivery interleaves with that pass - it found no protocol, and its arrival in
 * the pending messages happens after the request thread's pass - never reaches the protocol: no later trade
 * message re-processes it, and the network layer does not deliver it again because it acknowledged and
 * mailbox-cleared it at decryption time. The peer then waits for a reaction which our node never sends: the
 * trade is stuck while both traders are online and no restart happened.
 * <p>
 * The deterministic test simulates the interleaving at the seam where it happens: {@code findProtocol} returns
 * that no protocol exists at the first check of the delivered message, and the protocol exists at the check
 * which the fix adds after the message became pending. On the unpatched code the first check is the only one,
 * so the test fails there and reproduces the stuck trade.
 */
class BisqEasyPendingMessageHandoffRaceTest {
    private static final String BTC_ADDRESS = "bc1qxyzxyzxyzxyzxyzxyzxyzxyzxyzxyzxyzxyzx";
    private static final BisqEasyTradeState START_STATE =
            BisqEasyTradeState.MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_DID_NOT_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS;
    private static final BisqEasyTradeState STATE_AFTER_BTC_ADDRESS =
            BisqEasyTradeState.MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_DID_NOT_SENT_ACCOUNT_DATA__SELLER_RECEIVED_BTC_ADDRESS;

    @TempDir
    Path persistenceDir;

    private ServiceProvider serviceProvider;
    private NetworkId takerNetworkId;
    private NetworkId makerNetworkId;
    private BisqEasyOffer offer;
    private BisqEasyTradeService tradeService;

    @BeforeEach
    void setUp() {
        serviceProvider = createServiceProvider(persistenceDir);
        takerNetworkId = createNetworkId("buyer-taker");
        makerNetworkId = createNetworkId("seller-maker");
        offer = createRealOffer(makerNetworkId);
    }

    @AfterEach
    void tearDown() {
        if (tradeService != null) {
            tradeService.shutdown();
            tradeService = null;
        }
    }

    /**
     * Control: the btc address message reaches the protocol when the protocol exists when the message is
     * delivered. This holds also on the unpatched code, so a failure of the proof test below cannot come from a
     * broken harness.
     */
    @Test
    void control_btcAddressMessageIsAppliedWhenProtocolExists() throws UnresolvableProtobufException {
        BisqEasyTrade trade = createTradeAtState(START_STATE);
        tradeService = createInitializedService(trade);

        tradeService.onMessage(new BisqEasyBtcAddressMessage(
                "btc-address-msg-control", trade.getId(), BisqEasyProtocol.VERSION,
                takerNetworkId, makerNetworkId, BTC_ADDRESS, offer));

        assertEquals(STATE_AFTER_BTC_ADDRESS, trade.getTradeState(),
                "the btc address message must reach the protocol and advance the trade");
        assertTrue(trade.getBitcoinPaymentData().isPresent(),
                "the btc address handler must have run");
    }

    /**
     * The proof: the btc address message is delivered while the protocol of its trade is being created, so its
     * first protocol lookup finds none. The message must still reach the protocol: the take offer request
     * thread's single pass over the pending messages can already be over when our message lands in them, and
     * nothing else would deliver it again. On the unpatched code the message stays pending and the trade is
     * stuck although the peer sent it exactly once and both traders are online.
     */
    @Test
    void btcAddressMessageWhichFoundNoProtocolIsNotOrphaned() throws UnresolvableProtobufException {
        BisqEasyTrade trade = createTradeAtState(START_STATE);
        BisqEasyTradeService spiedService = createSpiedInitializedService(trade);
        tradeService = spiedService;
        // The interleaving under test: the message's first protocol lookup runs before the take offer request
        // thread registers the protocol; the lookup after the message became pending runs after it. Until the
        // request thread registers the protocol, the second lookup is the real one.
        doReturn(Optional.<BisqEasyProtocol>empty()).doCallRealMethod()
                .when(spiedService).findProtocol(anyString());

        spiedService.onMessage(new BisqEasyBtcAddressMessage(
                "btc-address-msg", trade.getId(), BisqEasyProtocol.VERSION,
                takerNetworkId, makerNetworkId, BTC_ADDRESS, offer));

        assertEquals(STATE_AFTER_BTC_ADDRESS, trade.getTradeState(),
                "STUCK TRADE: the btc address message found no protocol at its first check and was kept as a "
                        + "pending message; the pending pass of the take offer request thread had already run, so no "
                        + "later event delivered it to the protocol (pending messages: "
                        + spiedService.getPendingMessages().getMessagesInArrivalOrder().size() + "). The peer's "
                        + "node acknowledged the message at decryption time and never sends it again, so the trade "
                        + "waits forever for a step whose message arrived.");
        assertTrue(trade.getBitcoinPaymentData().isPresent(),
                "the btc address handler must have run");
        assertTrue(spiedService.getPendingMessages().isEmpty(),
                "a message which the protocol processed must not stay pending");
    }


    /* --------------------------------------------------------------------- */
    // Harness
    /* --------------------------------------------------------------------- */

    // A real BisqEasyTradeService with the dependencies which initialize() and the message handlers touch mocked
    // at the network and bonded-roles boundary.
    private static ServiceProvider createServiceProvider(Path persistenceDir) {
        NetworkService networkService = mock(NetworkService.class);
        when(networkService.confidentialSend(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendMessageResult.class)));

        ServiceProvider serviceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        when(serviceProvider.getNetworkService()).thenReturn(networkService);
        when(serviceProvider.getPersistenceService()).thenReturn(new PersistenceService(persistenceDir));
        return serviceProvider;
    }

    private BisqEasyTradeService createInitializedService(BisqEasyTrade trade) {
        BisqEasyTradeService service = new BisqEasyTradeService(serviceProvider, AppType.DESKTOP);
        when(serviceProvider.getBisqEasyTradeService()).thenReturn(service);
        service.getPersistableStore().addTrade(trade);
        service.initialize();
        return service;
    }

    // The spy is created before initialize() so the protocol map which initialize() fills belongs to the spy.
    // The findProtocol stub is installed by the test after initialize(), so the lookups of initialize() run
    // against the real method.
    private BisqEasyTradeService createSpiedInitializedService(BisqEasyTrade trade) {
        BisqEasyTradeService service = new BisqEasyTradeService(serviceProvider, AppType.DESKTOP);
        BisqEasyTradeService spiedService = spy(service);
        when(serviceProvider.getBisqEasyTradeService()).thenReturn(spiedService);
        spiedService.getPersistableStore().addTrade(trade);
        spiedService.initialize();
        return spiedService;
    }

    // Places a real seller-as-maker trade at the given state via the proto round trip which persistence uses.
    private BisqEasyTrade createTradeAtState(BisqEasyTradeState state) throws UnresolvableProtobufException {
        BisqEasyContract contract = createRealContract(offer, takerNetworkId);
        BisqEasyTrade freshTrade = new BisqEasyTrade(contract, false, false,
                createIdentity(makerNetworkId), offer, takerNetworkId, makerNetworkId);
        bisq.trade.protobuf.Trade proto = freshTrade.toProto(false).toBuilder()
                .setState(state.name())
                .build();
        return BisqEasyTrade.fromProto(proto);
    }

    private static BisqEasyContract createRealContract(BisqEasyOffer offer, NetworkId takerNetworkId) {
        return new BisqEasyContract(System.currentTimeMillis(),
                offer,
                takerNetworkId,
                100_000,
                3_500_000,
                new BitcoinPaymentMethodSpec(BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN)),
                new FiatPaymentMethodSpec(FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK)),
                Optional.empty(),
                new MarketPriceSpec(),
                0);
    }

    private static BisqEasyOffer createRealOffer(NetworkId makerNetworkId) {
        return new BisqEasyOffer(makerNetworkId,
                Direction.SELL,
                new Market("BTC", "EUR", "Bitcoin", "Euro"),
                new BaseSideFixedAmountSpec(100_000),
                new MarketPriceSpec(),
                List.of(BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN)),
                List.of(FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK)),
                "",
                List.of("en"),
                "1.0.0");
    }

    private static Identity createIdentity(NetworkId networkId) {
        KeyBundle keyBundle = new KeyBundle("test-key-bundle",
                KeyGeneration.generateDefaultEcKeyPair(),
                TorKeyGeneration.generateKeyPair(),
                I2PKeyGeneration.generateKeyPair());
        return new Identity("test-id", networkId, keyBundle);
    }

    private static NetworkId createNetworkId(String keyId) {
        KeyPair keyPair = KeyGeneration.generateDefaultEcKeyPair();
        Address address = Address.from("127.0.0.1", 1000);
        return new NetworkId(new AddressByTransportTypeMap(Map.of(address.getTransportType(), address)),
                new PubKey(keyPair.getPublic(), keyId));
    }
}
