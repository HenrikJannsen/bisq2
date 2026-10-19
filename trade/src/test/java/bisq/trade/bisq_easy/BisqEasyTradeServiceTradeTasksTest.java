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

import bisq.account.payment_method.BitcoinPaymentMethod;
import bisq.account.payment_method.BitcoinPaymentMethodSpec;
import bisq.account.payment_method.BitcoinPaymentRail;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentMethodSpec;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.account.protocol_type.TradeProtocolType;
import bisq.bonded_roles.release.AppType;
import bisq.common.market.MarketRepository;
import bisq.common.observable.Observable;
import bisq.common.util.StringUtils;
import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.identity.Identity;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.offer.Direction;
import bisq.offer.amount.spec.QuoteSideFixedAmountSpec;
import bisq.offer.bisq_easy.BisqEasyOffer;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.trade.ServiceProvider;
import bisq.trade.bisq_easy.protocol.BisqEasyProtocol;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyAccountDataMessage;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyTakeOfferRequest;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyTakeOfferResponse;
import bisq.user.reputation.ReputationScore;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static bisq.trade.bisq_easy.BisqEasyTakeOfferRequestTestFixtures.makerAcceptsTakeOfferRequests;
import static bisq.trade.bisq_easy.BisqEasyTakeOfferRequestTestFixtures.takeOfferRequest;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.QUOTE_AMOUNT;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.contract;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.identity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The messages of a trade run one after the other as tasks of the trade, on a pool of several threads as in the
 * application (docs/specifications/trade/trade-task-execution.md). The node is the maker of a buy offer, and the
 * seller takes it.
 */
@Isolated
class BisqEasyTradeServiceTradeTasksTest {
    private static final String ACCOUNT_DATA = "SEPA account of the seller";

    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ServiceProvider serviceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
    private final List<Object> sentMessages = new CopyOnWriteArrayList<>();
    private final Identity buyerIdentity = identity("buyer-as-maker");
    private final Identity sellerIdentity = identity("seller-as-taker");
    private final BisqEasyOffer offer = buyOffer(buyerIdentity.getNetworkId());
    private final BisqEasyContract contract = contract(offer, sellerIdentity.getNetworkId(), Optional.empty());
    private final String tradeId = new BisqEasyTrade(contract, false, true, sellerIdentity, offer,
            sellerIdentity.getNetworkId(), buyerIdentity.getNetworkId()).getId();
    private BisqEasyTradeService service;

    @BeforeEach
    void setUp() {
        makerAcceptsTakeOfferRequests(serviceProvider, buyerIdentity, offer);
        // As buyer, the maker checks the reputation of the selling taker
        when(serviceProvider.getUserService().getReputationService()
                .getReputationScore(sellerIdentity.getNetworkId().getId()))
                .thenReturn(new ReputationScore(1_000_000, 5, 1));
        when(serviceProvider.getSettingsService().getCloseMyOfferWhenTaken()).thenReturn(new Observable<>(false));
        when(serviceProvider.getNetworkService().confidentialSend(any(), any(), any())).thenAnswer(invocation -> {
            sentMessages.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture(new SendMessageResult());
        });

        service = new BisqEasyTradeService(serviceProvider, AppType.DESKTOP, pool);
        when(serviceProvider.getBisqEasyTradeService()).thenReturn(service);
        service.initialize().join();
    }

    @AfterEach
    void tearDown() {
        service.shutdown().join();
    }

    // The seller sent the account data right after the request. The task of the account data runs first and finds no
    // protocol. Before it adds the message to the pending messages, the request arrives. The request waits for that
    // task, creates the trade and processes the pending account data. Before the trade tasks, the request ran at the
    // same time, so the account data could become pending after the request had processed the pending messages, and
    // both traders waited for each other.
    @Test
    void accountDataWhichArrivesWithTheRequestIsAppliedAfterTheRequest() throws Exception {
        BisqEasyTakeOfferRequest request = takeOfferRequest(tradeId, sellerIdentity, contract);
        BisqEasyAccountDataMessage accountData = accountDataMessage();

        Logger logger = (Logger) LoggerFactory.getLogger(BisqEasyTradeService.class);
        Level previousLevel = logger.getLevel();
        CountDownLatch reachedPendingBranch = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UnsynchronizedAppenderBase<ILoggingEvent> pauseBeforeAddingToPending = new UnsynchronizedAppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (event.getFormattedMessage().contains("We add the message of type BisqEasyAccountDataMessage")) {
                    reachedPendingBranch.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };
        pauseBeforeAddingToPending.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(pauseBeforeAddingToPending);
        try {
            service.onMessage(accountData);
            assertThat(reachedPendingBranch.await(10, TimeUnit.SECONDS)).isTrue();

            service.onMessage(request);
            assertThat(service.findTrade(tradeId)).isEmpty();

            release.countDown();
            service.afterTasksOfTrade(tradeId).get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            logger.detachAppender(pauseBeforeAddingToPending);
            logger.setLevel(previousLevel);
        }

        BisqEasyTrade trade = service.findTrade(tradeId).orElseThrow();
        assertThat(trade.getPaymentAccountData()).contains(ACCOUNT_DATA);
        assertThat(trade.getTradeState()).isEqualTo(
                BisqEasyTradeState.MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_DID_NOT_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA);
    }

    @Test
    void copiesOfARequestWhichArriveAtTheSameTimeCreateOneTrade() throws Exception {
        BisqEasyTakeOfferRequest request = takeOfferRequest(tradeId, sellerIdentity, contract);

        CompletableFuture.allOf(CompletableFuture.runAsync(() -> service.onMessage(request), pool),
                        CompletableFuture.runAsync(() -> service.onMessage(request), pool))
                .get(10, TimeUnit.SECONDS);
        service.afterTasksOfTrade(tradeId).get(10, TimeUnit.SECONDS);

        assertThat(service.getTrades()).hasSize(1);
        assertThat(sentMessages).filteredOn(BisqEasyTakeOfferResponse.class::isInstance).hasSize(1);
    }

    private BisqEasyAccountDataMessage accountDataMessage() {
        return new BisqEasyAccountDataMessage(StringUtils.createUid(),
                tradeId,
                BisqEasyProtocol.VERSION,
                sellerIdentity.getNetworkId(),
                buyerIdentity.getNetworkId(),
                ACCOUNT_DATA,
                offer);
    }

    // The maker of a buy offer is the buyer
    private static BisqEasyOffer buyOffer(NetworkId makerNetworkId) {
        return new BisqEasyOffer("buy-offer",
                System.currentTimeMillis(),
                makerNetworkId,
                Direction.BUY,
                MarketRepository.getUSDBitcoinMarket(),
                new QuoteSideFixedAmountSpec(QUOTE_AMOUNT),
                new MarketPriceSpec(),
                List.of(TradeProtocolType.BISQ_EASY),
                List.of(new BitcoinPaymentMethodSpec(BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN))),
                List.of(new FiatPaymentMethodSpec(FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.SEPA))),
                List.of(),
                List.of("en"),
                1,
                "1.0.0",
                "2.1.13");
    }
}
