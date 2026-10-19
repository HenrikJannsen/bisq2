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

package bisq.trade.mu_sig;

import bisq.account.payment_method.PaymentMethodSpecUtil;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.bonded_roles.BondedRolesService;
import bisq.bonded_roles.release.AppType;
import bisq.bonded_roles.security_manager.alert.AlertService;
import bisq.chat.ChatService;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.common.market.Market;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.network.ClearnetAddress;
import bisq.common.network.TransportType;
import bisq.common.observable.Observable;
import bisq.common.observable.collection.ObservableSet;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.NetworkService;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.offer.Direction;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.options.CollateralOption;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.persistence.Persistence;
import bisq.persistence.PersistenceService;
import bisq.security.keys.I2PKeyGeneration;
import bisq.security.keys.KeyBundle;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.security.keys.TorKeyGeneration;
import bisq.settings.SettingsService;
import bisq.trade.ServiceProvider;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.protocol.MuSigProtocol;
import bisq.trade.mu_sig.protocol.MuSigTradeState;
import bisq.trade.protobuf.MusigGrpc;

import bisq.user.UserService;
import bisq.user.banned.BannedUserService;
import bisq.user.profile.UserProfileService;
import io.grpc.Grpc;
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
class MuSigPendingMessageHandoffRaceTest {
    private Server server;
    private ServiceProvider serviceProvider;
    private NetworkId peerNetworkId;
    private NetworkId myNetworkId;
    private MuSigTrade trade;
    private MuSigTradeService service;

    @BeforeEach
    void setUp() throws Exception {
        server = Grpc.newServerBuilderForPort(0, InsecureServerCredentials.create())
                .addService(new MusigGrpc.MusigImplBase() {
                })
                .build()
                .start();

        NetworkService networkService = mock(NetworkService.class);
        when(networkService.getConfidentialMessageServices()).thenReturn(Set.of());
        when(networkService.confidentialSend(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new SendMessageResult()));

        MuSigOpenTradeChannelService channelService = mock(MuSigOpenTradeChannelService.class);
        when(channelService.getChannels()).thenReturn(new ObservableSet<>());
        when(channelService.findChannelByTradeId(anyString())).thenReturn(Optional.empty());
        ChatService chatService = mock(ChatService.class);
        when(chatService.getMuSigOpenTradeChannelService()).thenReturn(channelService);

        UserService userService = mock(UserService.class);
        when(userService.getBannedUserService()).thenReturn(mock(BannedUserService.class));
        when(userService.getUserProfileService()).thenReturn(mock(UserProfileService.class));

        BondedRolesService bondedRolesService = mock(BondedRolesService.class);
        AlertService alertService = mock(AlertService.class);
        when(alertService.getAuthorizedAlertDataSet()).thenReturn(new ObservableSet<>());
        when(bondedRolesService.getAlertService()).thenReturn(alertService);

        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getNumDaysAfterRedactingTradeData()).thenReturn(new Observable<>(90));

        @SuppressWarnings("unchecked")
        Persistence<MuSigTradeStore> persistence = mock(Persistence.class);
        when(persistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        PersistenceService persistenceService = mock(PersistenceService.class);
        when(persistenceService.getOrCreatePersistence(any(), any(), any(MuSigTradeStore.class)))
                .thenReturn(persistence);

        serviceProvider = mock(ServiceProvider.class);
        when(serviceProvider.getNetworkService()).thenReturn(networkService);
        when(serviceProvider.getChatService()).thenReturn(chatService);
        when(serviceProvider.getUserService()).thenReturn(userService);
        when(serviceProvider.getBondedRolesService()).thenReturn(bondedRolesService);
        when(serviceProvider.getSettingsService()).thenReturn(settingsService);
        when(serviceProvider.getPersistenceService()).thenReturn(persistenceService);

        myNetworkId = createNetworkId("my-key", 9997);
        peerNetworkId = createNetworkId("peer-key", 9998);
        trade = createSellerAsMakerTradeAtInit();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) {
            service.shutdown().get(5, TimeUnit.SECONDS);
            service = null;
        }
        server.shutdownNow();
        server.awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Control: the peer's error report reaches the protocol when the protocol exists when the message is
     * delivered. This holds also on the unpatched code, so a failure of the proof test below cannot come from
     * a broken harness.
     */
    @Test
    void control_reportErrorMessageIsAppliedWhenProtocolExists() throws Exception {
        service = createInitializedService(false);

        service.onMessage(createReportErrorMessage("report-msg-control"));

        drainTradeTasks();
        assertEquals(MuSigTradeState.FAILED_AT_PEER, trade.getTradeState(),
                "the peer's error report must reach the protocol and fail the trade at the peer");
    }

    /**
     * The proof: the peer's error report is delivered while the protocol of its trade is being created, so its
     * first protocol lookup finds none. The message must still reach the protocol: the take offer request
     * thread's single pass over the pending messages can already be over when our message lands in them, and
     * nothing else would deliver it again. On the unpatched code the message stays pending and the trade is
     * stuck although the peer sent it exactly once and both traders are online.
     */
    @Test
    void reportErrorMessageWhichFoundNoProtocolIsNotOrphaned() throws Exception {
        MuSigTradeService spiedService = createInitializedService(true);
        service = spiedService;
        // The interleaving under test: the message's first protocol lookup runs before the take offer request
        // thread registers the protocol; the lookup after the message became pending runs after it. Until the
        // request thread registers the protocol, the second lookup is the real one.
        doReturn(Optional.<MuSigProtocol>empty()).doCallRealMethod()
                .when(spiedService).findProtocol(anyString());

        spiedService.onMessage(createReportErrorMessage("report-msg"));

        drainTradeTasks();
        assertEquals(MuSigTradeState.FAILED_AT_PEER, trade.getTradeState(),
                "STUCK TRADE: the error report found no protocol at its first check and was kept as a pending "
                        + "message; the pending pass of the take offer request thread had already run, so no later "
                        + "event delivered it to the protocol (pending messages: "
                        + spiedService.getPendingTradeMessages().getMessagesInArrivalOrder().size() + "). The "
                        + "peer's node acknowledged the message at decryption time and never sends it again, so the "
                        + "trade never learns that the peer failed and waits forever.");
        assertTrue(spiedService.getPendingTradeMessages().isEmpty(),
                "a message which the protocol processed must not stay pending");
    }


    /* --------------------------------------------------------------------- */
    // Harness
    /* --------------------------------------------------------------------- */

    private MuSigTradeService createInitializedService(boolean spyOnService) throws Exception {
        MuSigTradeService plainService = new MuSigTradeService(
                new MuSigTradeService.Config("127.0.0.1", server.getPort()),
                serviceProvider, AppType.DESKTOP);
        MuSigTradeService serviceUnderTest = spyOnService ? spy(plainService) : plainService;
        when(serviceProvider.getMuSigTradeService()).thenReturn(serviceUnderTest);
        serviceUnderTest.getPersistableStore().addTrade(trade);
        serviceUnderTest.initialize().get(5, TimeUnit.SECONDS);
        return serviceUnderTest;
    }

    private void drainTradeTasks() throws Exception {
        // The trade tasks run on the single core thread of the service executor, so a task which we submit
        // afterwards and await completes only after the trade task.
        service.getExecutor().submit(() -> {
        }).get(5, TimeUnit.SECONDS);
    }

    private MuSigReportErrorMessage createReportErrorMessage(String messageId) {
        return new MuSigReportErrorMessage(messageId,
                trade.getId(),
                MuSigProtocol.VERSION,
                peerNetworkId,
                myNetworkId,
                "The peer reported a trade failure",
                "",
                TradeProtocolFailure.UNKNOWN);
    }

    private MuSigTrade createSellerAsMakerTradeAtInit() {
        KeyPair keyPair = KeyGeneration.generateDefaultEcKeyPair();
        Identity identity = new Identity("my-identity", myNetworkId,
                new KeyBundle("my-key-bundle", keyPair,
                        TorKeyGeneration.generateKeyPair(),
                        I2PKeyGeneration.generateKeyPair()));
        Market market = new Market("BTC", "EUR", "Bitcoin", "Euro");
        FiatPaymentMethod paymentMethod = FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK);
        MarketPriceSpec priceSpec = new MarketPriceSpec();
        MuSigOffer offer = new MuSigOffer("handoff-race-test-offer", myNetworkId,
                Direction.SELL, market, new BaseSideFixedAmountSpec(100_000), priceSpec,
                List.of(paymentMethod), List.of(new CollateralOption(0.25, 0.25)), MuSigProtocol.VERSION);
        MuSigContract contract = new MuSigContract(System.currentTimeMillis(), offer, peerNetworkId,
                100_000, 3_500_000,
                PaymentMethodSpecUtil.createPaymentMethodSpec(paymentMethod, market.getQuoteCurrencyCode()),
                new byte[20], Optional.empty(), Optional.empty(), priceSpec, 0);
        return new MuSigTrade(contract, false, false, identity, offer, peerNetworkId, myNetworkId);
    }

    private static NetworkId createNetworkId(String keyId, int port) {
        KeyPair keyPair = KeyGeneration.generateDefaultEcKeyPair();
        return new NetworkId(new AddressByTransportTypeMap(Map.of(
                TransportType.CLEAR, new ClearnetAddress("127.0.0.1", port))),
                new PubKey(keyPair.getPublic(), keyId));
    }
}
