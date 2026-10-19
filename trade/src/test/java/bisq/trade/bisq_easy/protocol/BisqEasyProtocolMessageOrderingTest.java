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

package bisq.trade.bisq_easy.protocol;

import bisq.common.fsm.Event;
import bisq.common.observable.Observable;
import bisq.contract.ContractService;
import bisq.contract.ContractSignatureData;
import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.identity.Identity;
import bisq.network.identity.NetworkId;
import bisq.offer.Direction;
import bisq.offer.bisq_easy.BisqEasyOffer;
import bisq.security.SecurityService;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRole;
import bisq.trade.bisq_easy.BisqEasyTrade;
import bisq.trade.bisq_easy.protocol.events.BisqEasyAccountDataEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyBtcConfirmedEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyConfirmFiatSentEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasySendBtcAddressEvent;
import bisq.trade.bisq_easy.protocol.events.BisqEasyTakeOfferEvent;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyAccountDataMessage;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyBtcAddressMessage;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyConfirmBtcSentMessage;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyConfirmFiatReceiptMessage;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyTakeOfferRequest;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyTakeOfferResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static bisq.trade.bisq_easy.BisqEasyTradeTestData.QUOTE_AMOUNT;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.channel;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.contract;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.identity;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.offer;
import static bisq.trade.bisq_easy.BisqEasyTradeTestData.userProfile;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.BTC_CONFIRMED;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.BUYER_RECEIVED_BTC_SENT_CONFIRMATION;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.BUYER_SENT_FIAT_SENT_CONFIRMATION;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_RECEIVED_BTC_ADDRESS;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.TAKER_RECEIVED_TAKE_OFFER_RESPONSE__BUYER_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA;
import static bisq.trade.bisq_easy.protocol.BisqEasyTradeState.TAKER_RECEIVED_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_RECEIVED_BTC_ADDRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BisqEasyProtocolMessageOrderingTest {
    private static final String BITCOIN_ADDRESS = "bc1qbuyersaddress";
    private static final String ACCOUNT_DATA = "sellers account data";

    private enum SetupStep {
        TAKE_OFFER_MESSAGE,
        PEER_PAYMENT_DATA,
        LOCAL_PAYMENT_DATA
    }

    private static Stream<Arguments> setupOrders() {
        return Arrays.stream(TradeRole.values()).flatMap(role ->
                Arrays.stream(SetupStep.values()).flatMap(first ->
                        Arrays.stream(SetupStep.values()).filter(second -> second != first).map(second -> {
                            SetupStep third = Arrays.stream(SetupStep.values())
                                    .filter(step -> step != first && step != second).findFirst().orElseThrow();
                            return Arguments.of(role, List.of(first, second, third));
                        })));
    }

    @ParameterizedTest
    @MethodSource("setupOrders")
    void exchangesBothPaymentDetailsInEveryArrivalOrder(TradeRole role, List<SetupStep> order) throws Exception {
        Fixture fixture = new Fixture(role);

        for (SetupStep step : order) {
            fixture.protocol.handle(fixture.setupEvent(step));
        }

        assertThat(fixture.trade.getState()).isEqualTo(switch (role) {
            case BUYER_AS_TAKER -> TAKER_RECEIVED_TAKE_OFFER_RESPONSE__BUYER_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA;
            case BUYER_AS_MAKER -> MAKER_SENT_TAKE_OFFER_RESPONSE__BUYER_SENT_BTC_ADDRESS__BUYER_RECEIVED_ACCOUNT_DATA;
            case SELLER_AS_TAKER -> TAKER_RECEIVED_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_RECEIVED_BTC_ADDRESS;
            case SELLER_AS_MAKER -> MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_RECEIVED_BTC_ADDRESS;
        });
        assertThat(fixture.trade.getPaymentAccountData()).contains(ACCOUNT_DATA);
        assertThat(fixture.trade.getBitcoinPaymentData()).contains(BITCOIN_ADDRESS);
        assertThat(fixture.trade.getMaker().getContractSignatureData().get()).isNotNull();
        assertThat(fixture.trade.getTaker().getContractSignatureData().get()).isNotNull();
        assertThat(fixture.trade.getEventQueue()).isEmpty();
        assertThat(fixture.trade.getErrorMessage()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = TradeRole.class, names = {"BUYER_AS_TAKER", "BUYER_AS_MAKER"})
    void buyerProcessesBitcoinSentBeforeFiatReceiptWhenMissingReceiptArrives(TradeRole role) throws Exception {
        Fixture fixture = new Fixture(role);
        for (SetupStep step : SetupStep.values()) {
            fixture.protocol.handle(fixture.setupEvent(step));
        }
        fixture.protocol.handle(new BisqEasyConfirmFiatSentEvent());

        fixture.protocol.handle(new BisqEasyConfirmBtcSentMessage("btc-sent", fixture.trade.getId(),
                BisqEasyProtocol.VERSION, fixture.peer, fixture.myself, Optional.of("transaction-id")));

        assertThat(fixture.trade.getState()).isEqualTo(BUYER_SENT_FIAT_SENT_CONFIRMATION);
        assertThat(fixture.trade.getPaymentProof()).isEmpty();
        assertThat(fixture.trade.getEventQueue()).hasSize(1);

        fixture.protocol.handle(new BisqEasyConfirmFiatReceiptMessage("fiat-received", fixture.trade.getId(),
                BisqEasyProtocol.VERSION, fixture.peer, fixture.myself));

        assertThat(fixture.trade.getState()).isEqualTo(BUYER_RECEIVED_BTC_SENT_CONFIRMATION);
        assertThat(fixture.trade.getPaymentProof()).contains("transaction-id");
        assertThat(fixture.trade.getEventQueue()).isEmpty();

        fixture.protocol.handle(new BisqEasyBtcConfirmedEvent());
        assertThat(fixture.trade.getState()).isEqualTo(BTC_CONFIRMED);
        assertThat(fixture.trade.getTradeCompletedDate()).isPresent();
    }

    private static class Fixture {
        private final BisqEasyTrade trade;
        private final BisqEasyProtocol protocol;
        private final NetworkId myself;
        private final NetworkId peer;
        private final Event takeOfferMessage;

        private Fixture(TradeRole role) throws Exception {
            ServiceProvider serviceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
            Identity maker = identity("maker");
            Identity taker = identity("taker");
            Direction makerDirection = role.isBuyer() == role.isMaker() ? Direction.BUY : Direction.SELL;
            BisqEasyOffer offer = BisqEasyOffer.fromProto(offer("offer-id", maker.getNetworkId(), QUOTE_AMOUNT)
                    .toProto(false).toBuilder().setDirection(makerDirection.toProtoEnum()).build());
            BisqEasyContract contract = contract(offer, taker.getNetworkId(), Optional.empty());
            Identity myIdentity = role.isTaker() ? taker : maker;
            trade = new BisqEasyTrade(contract, role.isBuyer(), role.isTaker(), myIdentity, offer,
                    taker.getNetworkId(), maker.getNetworkId());
            myself = trade.getMyself().getNetworkId();
            peer = trade.getPeer().getNetworkId();

            ContractService contractService = new ContractService(mock(SecurityService.class));
            when(serviceProvider.getContractService()).thenReturn(contractService);
            when(serviceProvider.getSettingsService().getCloseMyOfferWhenTaken()).thenReturn(new Observable<>(false));
            when(serviceProvider.getChatService().getBisqEasyOpenTradeChannelService().findChannelByTradeId(trade.getId()))
                    .thenReturn(Optional.of(channel(trade, offer, userProfile(peer), Optional.empty())));

            protocol = switch (role) {
                case BUYER_AS_TAKER -> new BisqEasyBuyerAsTakerProtocol(serviceProvider, trade);
                case BUYER_AS_MAKER -> new BisqEasyBuyerAsMakerProtocol(serviceProvider, trade);
                case SELLER_AS_TAKER -> new BisqEasySellerAsTakerProtocol(serviceProvider, trade);
                case SELLER_AS_MAKER -> new BisqEasySellerAsMakerProtocol(serviceProvider, trade);
            };
            trade.setProtocolVersion(protocol.getVersion());
            if (role.isTaker()) {
                protocol.handle(new BisqEasyTakeOfferEvent());
                ContractSignatureData makerSignature = contractService.signContract(contract, maker.getKeyBundle().getKeyPair());
                takeOfferMessage = new BisqEasyTakeOfferResponse("take-response", trade.getId(),
                        BisqEasyProtocol.VERSION, peer, myself, makerSignature);
            } else {
                ContractSignatureData takerSignature = contractService.signContract(contract, taker.getKeyBundle().getKeyPair());
                takeOfferMessage = new BisqEasyTakeOfferRequest("take-request", trade.getId(),
                        BisqEasyProtocol.VERSION, peer, myself, contract, takerSignature);
            }
        }

        private Event setupEvent(SetupStep step) {
            return switch (step) {
                case TAKE_OFFER_MESSAGE -> takeOfferMessage;
                case PEER_PAYMENT_DATA -> trade.isBuyer()
                        ? new BisqEasyAccountDataMessage("account-data", trade.getId(), BisqEasyProtocol.VERSION,
                        peer, myself, ACCOUNT_DATA, trade.getOffer())
                        : new BisqEasyBtcAddressMessage("bitcoin-address", trade.getId(), BisqEasyProtocol.VERSION,
                        peer, myself, BITCOIN_ADDRESS, trade.getOffer());
                case LOCAL_PAYMENT_DATA -> trade.isBuyer()
                        ? new BisqEasySendBtcAddressEvent(BITCOIN_ADDRESS)
                        : new BisqEasyAccountDataEvent(ACCOUNT_DATA);
            };
        }
    }
}
